/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.model

import android.content.Context
import android.net.Network
import com.vpn0wn.android.Application
import com.vpn0wn.android.backend.BackendException
import com.vpn0wn.android.backend.Backend
import com.vpn0wn.android.backend.GoBackend
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.util.UserKnobs
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal class NetworkAutomationController(
    private val manager: TunnelManager,
    private val context: Context,
    private val scope: CoroutineScope,
) {
    enum class Condition { DISABLED, INACTIVE, MONITORING, WAITING_NETWORK, OWNED, MANUAL_HOLD, PAUSED, UNRESOLVED, FAILED, EXIT_PENDING, EXIT_RETAINED_PIN, EXIT_NO_TRANSPORT, EXIT_UNKNOWN, ANDROID_HANDLING }
    data class Status(val condition: Condition = Condition.DISABLED, val enabled: Boolean = false, val defaultName: String? = null,
        val exit: NetworkAutomationState.Exit = NetworkAutomationState.Exit.NONE, val settling: Boolean = false)
    data class ManualAdmission(val tunnel: ObservableTunnel, val name: String, val requested: Tunnel.State, val episode: Long, val serial: Long, val revision: Long,
        val authority: GoBackend.TransportAuthority, val recovery: Job?, val settlement: CompletableDeferred<Unit>, val previousManual: CompletableDeferred<Unit>?)
    private data class Request(
        val tunnel: ObservableTunnel,
        val backend: GoBackend,
        val state: NetworkAutomationState,
        val receipt: NetworkAutomationState.Receipt,
        val expected: GoBackend.TransportSnapshot?,
        val network: Network?,
        val recovery: Job?,
        val authority: GoBackend.TransportAuthority,
        val config: com.vpn0wn.config.Config,
    )

    private val mutableStatus = MutableStateFlow(Status())
    val status = mutableStatus.asStateFlow()
    private val intake = AtomicLong()
    private val evaluations = Channel<Unit>(Channel.CONFLATED)
    private var observer: Any? = null
    private var latest: AutomationNetworks? = null
    private var baseline = false
    private var selected: Network? = null
    private var ownedWork: Deferred<Unit>? = null
    private var liveExit: Pair<ObservableTunnel, GoBackend.TransportSnapshot>? = null
    private var liveExitBackend: GoBackend? = null
    private var exitWork: Deferred<Unit>? = null
    private var manualFrontier = 0L
    private var ownedAuthority: GoBackend.TransportAuthority? = null
    private var manualAuthority: GoBackend.TransportAuthority? = null
    private var manualWork: CompletableDeferred<Unit>? = null
    private var exitAuthority: GoBackend.TransportAuthority? = null
    private var exitSession: com.vpn0wn.android.backend.HandshakeSession? = null
    private var manualOwner: Pair<ObservableTunnel, com.vpn0wn.android.backend.HandshakeSession>? = null
    private var automaticOwner: Pair<ObservableTunnel, com.vpn0wn.android.backend.HandshakeSession>? = null

    fun startFailed() {
        mutableStatus.value = mutableStatus.value.copy(condition = Condition.FAILED, settling = false)
    }

    init {
        scope.launch {
            for (ignored in evaluations) evaluate()
        }
    }

    fun nextSequence(): Long = intake.incrementAndGet()

    private suspend fun read(): NetworkAutomationState = NetworkAutomationState.read(Application.getPreferencesDataStore().data.first())

    suspend fun refresh(): NetworkAutomationState = manager.automationAdmission {
        read().also { show(it, when {
            it.paused -> Condition.PAUSED
            it.enabled && observer != null -> mutableStatus.value.condition
            it.enabled -> Condition.INACTIVE
            else -> disabledCondition(it)
        }) }
    }

    suspend fun selectDefault(name: String) = manager.automationAdmission {
        val tunnel = manager.automationTarget(name) ?: throw IOException("Default tunnel is unavailable")
        tunnel.getConfigAsync()
        val state = NetworkAutomationState.update { it.copy(defaultName = name, resolved = true, revision = it.revision + 1) }
        retirePending()
        show(state, if (state.enabled) Condition.MONITORING else disabledCondition(state))
        evaluations.trySend(Unit)
    }

    suspend fun enable() = manager.automationAdmission {
        val previous = read()
        if (previous.enabled) throw IOException("Network automation is already enabled")
        if (ownedWork?.isCompleted == false || manualWork?.isCompleted == false || exitWork?.isCompleted == false) throw IOException("Transport settlement is still pending")
        val name = previous.defaultName ?: throw IOException("Choose a default tunnel first")
        val tunnel = manager.automationTarget(name) ?: throw IOException("Default tunnel is unavailable")
        tunnel.getConfigAsync()
        val backend = requireSingleUserspace(Application.getBackend())
        val running = withContext(Dispatchers.IO) { backend.runningTunnelNames }
        if (running.any { it != name }) throw IOException("Disconnect other tunnels first")
        val history = withContext(Dispatchers.IO) { AutomationStopHistory.read(context) }
        val enabled = NetworkAutomationState.update {
            AutomationStopHistory.acknowledge(it, history).copy(
                enabled = true, episode = it.episode + 1, resolved = true,
                manual = null, attempt = null, exit = NetworkAutomationState.Exit.NONE,
            )
        }
        liveExit = null
        liveExitBackend = null
        manualOwner = null
        automaticOwner = null
        show(enabled, Condition.MONITORING)
    }

    suspend fun resume() = manager.automationAdmission {
        val state = read()
        if (!state.enabled) throw IOException("Network automation is disabled")
        val history = withContext(Dispatchers.IO) { AutomationStopHistory.read(context) }
        val resumed = NetworkAutomationState.update { AutomationStopHistory.acknowledge(it, history) }
        val activeCondition = mutableStatus.value.condition.takeUnless { it == Condition.PAUSED || it == Condition.INACTIVE }
        show(resumed, if (observer == null) Condition.MONITORING else activeCondition ?: Condition.MONITORING)
        if (observer != null && activeCondition == null) evaluations.trySend(Unit)
    }

    private data class ExitAdmission(
        val episode: Long,
        val target: ObservableTunnel?,
        val backend: GoBackend?,
        val session: com.vpn0wn.android.backend.HandshakeSession?,
        val manualFrontier: Long,
        val previousWork: Deferred<Unit>?,
        val previousManual: CompletableDeferred<Unit>?,
        val recovery: Job?,
    )

    suspend fun disable() = withContext(NonCancellable) {
        val work = manager.automationAdmission {
            exitWork?.takeIf { !it.isCompleted }?.let { return@automationAdmission it }
            val state = read()
            if (!state.enabled) {
                reconcileLiveExit(state)
                return@automationAdmission null
            }
            val backend = Application.getBackend() as? GoBackend
            val target = manager.getTunnels().firstOrNull { backend?.getTransportSnapshot(it) != null }
            val actual = target?.let { backend?.getTransportSnapshot(it) }
            val owners = listOfNotNull(manualOwner, automaticOwner)
            val liveReadyNames = manager.getTunnels().filter { tunnel ->
                backend?.getTransportSnapshot(tunnel)?.let { snapshot ->
                    snapshot.ready() && owners.any { it.first === tunnel && it.second === snapshot.session() }
                } == true
            }.map { it.name }.toSet()
            val disabled = try {
                NetworkAutomationState.disable(liveReadyNames)
            } catch (failure: IOException) {
                show(state, Condition.FAILED)
                throw failure
            }
            exitSession = actual?.session()
            manualOwner = null
            automaticOwner = null
            observer = null
            latest = null
            retirePending()
            val admission = ExitAdmission(disabled.episode, target, backend, actual?.session(), manualFrontier,
                ownedWork, manualWork, manager.invalidateAutomationRecovery())
            show(disabled, Condition.EXIT_PENDING, settling = true)
            scope.async(start = CoroutineStart.LAZY) { finishDisable(admission) }.also { exitWork = it }
        } ?: return@withContext
        work.start()
        work.await()
    }

    private suspend fun reconcileLiveExit(state: NetworkAutomationState) {
        val proof = liveExit
        if (state.exit == NetworkAutomationState.Exit.PENDING && proof != null) {
            val backend = Application.getBackend() as? GoBackend
            val actual = backend?.getTransportSnapshot(proof.first)
            if (actual?.session() === proof.second.session() && actual.ready() && actual.mode() == GoBackend.TransportMode.LEGACY) {
                val completed = NetworkAutomationState.update { it.copy(exit = NetworkAutomationState.Exit.COMPLETE) }
                show(completed, Condition.ANDROID_HANDLING)
                return
            }
        }
        show(state, disabledCondition(state))
    }

    // Opt-out is already durable. Native settlement and original failures survive caller/service cancellation.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun finishDisable(admission: ExitAdmission) = withContext(NonCancellable) {
        admission.previousWork?.join()
        admission.previousManual?.join()
        admission.recovery?.join()
        val transport = try {
            manager.automationAdmission {
                val state = read()
                if (state.enabled || state.episode != admission.episode || state.exit != NetworkAutomationState.Exit.PENDING ||
                    manualFrontier != admission.manualFrontier || admission.target == null || admission.backend == null ||
                    !manager.isAutomationTarget(admission.target)) return@automationAdmission null
                admission.backend.getTransportSnapshot(admission.target)?.takeIf { it.session() === admission.session }?.let { expected ->
                    val authority = GoBackend.TransportAuthority()
                    exitAuthority = authority
                    expected to authority
                }
            }
        } catch (_: IOException) {
            mutableStatus.value = mutableStatus.value.copy(condition = Condition.EXIT_UNKNOWN, settling = false)
            return@withContext
        }
        var failure: Throwable? = null
        if (transport != null && !(transport.first.ready() && transport.first.mode() == GoBackend.TransportMode.LEGACY)) {
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(admission.backend).replaceTransport(requireNotNull(admission.target), transport.first,
                        GoBackend.TransportMode.LEGACY, null, transport.second)
                }
            } catch (caught: Throwable) {
                failure = caught
            }
        }
        var settlementFailure: Throwable? = null
        try {
            manager.automationAdmission {
                val actual = admission.target?.let { admission.backend?.getTransportSnapshot(it) }
                val superseded = manualFrontier != admission.manualFrontier ||
                    actual?.session() !== admission.session || admission.target?.let { !manager.isAutomationTarget(it) } == true
                val legacy = !superseded && actual?.ready() == true && actual.mode() == GoBackend.TransportMode.LEGACY
                if (legacy) {
                    liveExit = requireNotNull(admission.target) to requireNotNull(actual)
                    liveExitBackend = admission.backend
                }
                manager.reconcileAutomation(persistRunningIntent = false)
                val settled = NetworkAutomationState.update { state ->
                    if (state.enabled || state.episode != admission.episode) state else state.copy(exit = when {
                        admission.session == null && actual == null && !superseded -> NetworkAutomationState.Exit.NONE
                        legacy -> NetworkAutomationState.Exit.COMPLETE
                        superseded -> NetworkAutomationState.Exit.SUPERSEDED
                        else -> NetworkAutomationState.Exit.FAILED
                    })
                }
                show(settled, disabledCondition(settled, actual))
            }
        } catch (caught: Throwable) {
            settlementFailure = caught
            mutableStatus.value = mutableStatus.value.copy(condition = Condition.EXIT_UNKNOWN, settling = false)
        }
        if (exitAuthority === transport?.second) exitAuthority = null
        if (failure != null && failure !is IOException && failure !is BackendException && failure !is InterruptedException) {
            settlementFailure?.let(failure::addSuppressed)
            throw requireNotNull(failure)
        }
        settlementFailure?.let { settlement ->
            if (settlement !is IOException) {
                failure?.let(settlement::addSuppressed)
                throw settlement
            }
        }
        Unit
    }

    suspend fun mayStart(): Boolean = manager.automationAdmission {
        val state = observeStop(read())
        show(state, when {
            state.paused -> Condition.PAUSED
            state.enabled && observer != null -> mutableStatus.value.condition
            state.enabled -> Condition.INACTIVE
            else -> disabledCondition(state)
        })
        state.enabled && !state.paused
    }

    private suspend fun observeStop(state: NetworkAutomationState): NetworkAutomationState {
        if (!state.enabled) return state
        val history = withContext(Dispatchers.IO) { AutomationStopHistory.read(context) }
        val observed = AutomationStopHistory.observe(state, history)
        return if (observed == state) state else NetworkAutomationState.update { AutomationStopHistory.observe(it, history) }
    }

    suspend fun attach(generation: Any): Boolean = manager.automationAdmission {
        val state = observeStop(read())
        if (!state.enabled || state.paused) return@automationAdmission false
        intake.updateAndGet { maxOf(it, state.frontier) }
        observer = generation
        latest = null
        baseline = false
        selected = null
        show(state, Condition.MONITORING)
        true
    }

    fun detach(generation: Any) {
        if (observer !== generation) return
        observer = null
        latest = null
        baseline = false
        if (ownedWork?.isCompleted == false) ownedAuthority?.retire()
        ownedWork?.cancel()
        if (mutableStatus.value.enabled) mutableStatus.value = mutableStatus.value.copy(condition = Condition.INACTIVE)
    }

    fun observe(networks: AutomationNetworks) {
        if (observer !== networks.generation || networks.sequence <= (latest?.sequence ?: -1)) return
        latest = networks
        if (ownedWork?.isCompleted == false && networks.select(selected) != selected) {
            ownedAuthority?.retire()
            ownedWork?.cancel()
        }
        evaluations.trySend(Unit)
    }

    suspend fun fence(names: Set<String>) {
        val state = read()
        if (state.fence(names) == state) return
        NetworkAutomationState.update { it.fence(names) }
        retirePending()
    }

    suspend fun resolveBinding(tunnel: ObservableTunnel, previousName: String) {
        if (!manager.isAutomationTarget(tunnel)) return
        val state = read()
        if (!state.enabled || state.defaultName != previousName) return
        NetworkAutomationState.update { it.copy(defaultName = tunnel.name, resolved = true) }
        evaluations.trySend(Unit)
    }

    suspend fun requireDisabled() {
        if (read().enabled || ownedWork?.isCompleted == false || manualWork?.isCompleted == false || exitWork?.isCompleted == false) {
            throw IOException("Disable network automation before changing backend")
        }
    }

    suspend fun restorationHandled(): Boolean = manager.automationAdmission { observeStop(read()).enabled }

    suspend fun suppressesRestore(name: String): Boolean = observeStop(read()).suppressesRestore(name)

    suspend fun suppressesRecovery(tunnel: ObservableTunnel): Boolean {
        val state = read()
        val unresolved = setOf(NetworkAutomationState.Outcome.PENDING, NetworkAutomationState.Outcome.FAILED, NetworkAutomationState.Outcome.UNRESOLVED)
        val manual = state.manual
        val failedAttempt = state.attempt?.takeIf { it.outcome in unresolved }
        return when {
            state.exit in setOf(NetworkAutomationState.Exit.PENDING, NetworkAutomationState.Exit.FAILED, NetworkAutomationState.Exit.SUPERSEDED) -> {
                val backend = Application.getBackend() as? GoBackend
                val actual = backend?.getTransportSnapshot(tunnel)
                actual?.ready() != true || actual.mode() != GoBackend.TransportMode.LEGACY || actual.session() === exitSession
            }
            ownedWork?.isCompleted == false || state.enabled && observer == null -> true
            !state.enabled -> false
            manual?.outcome in unresolved -> true
            failedAttempt != null && (manual?.outcome != NetworkAutomationState.Outcome.UP || manual.serial <= failedAttempt.serial) -> true
            else -> {
                val backend = Application.getBackend() as? GoBackend
                val actual = backend?.getTransportSnapshot(tunnel)
                actual == null || !actual.ready() || actual.mode() != GoBackend.TransportMode.OWNED
            }
        }
    }

    suspend fun admitManual(tunnel: ObservableTunnel, requested: Tunnel.State): ManualAdmission? {
        val state = read()
        if (!state.enabled) {
            manualFrontier++
            exitAuthority?.retire()
            return null
        }
        val serial = state.serial + 1
        val frontier = maxOf(state.frontier, intake.get())
        val receipt = NetworkAutomationState.Receipt(tunnel.name, requested.name,
            requested.takeUnless { it == Tunnel.State.TOGGLE }?.name, state.revision, frontier, state.selection, serial)
        NetworkAutomationState.update { it.copy(manual = receipt, serial = serial, frontier = frontier) }
        manualOwner = null
        manualFrontier++
        retirePending()
        exitAuthority?.retire()
        val authority = GoBackend.TransportAuthority()
        val previousManual = manualWork
        val settlement = CompletableDeferred<Unit>()
        manualAuthority = authority
        manualWork = settlement
        return ManualAdmission(tunnel, tunnel.name, requested, state.episode, serial, state.revision, authority,
            manager.invalidateAutomationRecovery(), settlement, previousManual)
    }

    private fun retirePending() {
        if (ownedWork?.isCompleted == false) ownedAuthority?.retire()
        if (manualWork?.isCompleted == false) manualAuthority?.retire()
        ownedWork?.cancel()
    }

    private suspend fun requireCurrentManual(admission: ManualAdmission) {
        val state = read()
        if (state.episode != admission.episode || state.manual?.serial != admission.serial || state.revision != admission.revision ||
            !manager.isAutomationTarget(admission.tunnel) || admission.tunnel.name != admission.name) {
            throw CancellationException("Manual request superseded")
        }
    }

    private suspend fun requireSingleUserspace(backend: Backend): GoBackend {
        if (backend !is GoBackend || UserKnobs.multipleTunnels.first()) throw IOException("Network automation requires single userspace mode")
        return backend
    }

    // Backend contracts admit arbitrary defects; retain their identity after required durable reconciliation.
    @Suppress("TooGenericExceptionCaught")
    suspend fun runManual(admission: ManualAdmission): Tunnel.State {
        try {
            var result: Tunnel.State? = null
            var failure: Throwable? = null
            var observed = admission.tunnel.state
            withContext(NonCancellable) {
                try {
                    if (admission.requested != Tunnel.State.DOWN) {
                        admission.recovery?.join()
                        admission.previousManual?.join()
                    }
                    val backend = Application.getBackend()
                    observed = withContext(Dispatchers.IO) { backend.getState(admission.tunnel) }
                    val target = if (admission.requested == Tunnel.State.TOGGLE) {
                        if (observed == Tunnel.State.UP) Tunnel.State.DOWN else Tunnel.State.UP
                    } else admission.requested
                    manager.automationAdmission {
                        requireCurrentManual(admission)
                        if (target == Tunnel.State.UP) requireSingleUserspace(backend)
                        NetworkAutomationState.update { it.copy(manual = it.manual?.copy(absolute = target.name)) }
                        if (target != observed) admission.tunnel.onTransitionStarted(target)
                    }
                    val config = if (target == Tunnel.State.UP) admission.tunnel.getConfigAsync() else null
                    manager.automationAdmission {
                        requireCurrentManual(admission)
                    }
                    if (backend !is GoBackend) {
                        result = manager.automationAdmission {
                            requireCurrentManual(admission)
                            manager.ordinaryAutomationTransition(admission.tunnel, target)
                        }
                    } else result = withContext(Dispatchers.IO) {
                        if (target == Tunnel.State.UP && config != null) {
                            setManualOwnedState(admission, backend, config)
                        } else backend.setStateWithAuthority(admission.tunnel, target, config, admission.authority)
                    }
                } catch (caught: Throwable) {
                    failure = caught
                }
                admission.recovery?.join()
                admission.previousManual?.join()
                try {
                    manager.automationAdmission {
                        val current = read()
                        if (current.episode == admission.episode && current.manual?.serial == admission.serial &&
                            manager.isAutomationTarget(admission.tunnel)) {
                            (result ?: observed).takeUnless { it == Tunnel.State.TOGGLE }?.let(admission.tunnel::onStateChanged)
                        }
                        val ownsIntent = current.enabled && current.episode == admission.episode && current.manual?.serial == admission.serial &&
                            current.revision == admission.revision && manager.isAutomationTarget(admission.tunnel) && admission.tunnel.name == admission.name
                        manager.reconcileAutomation(admission.tunnel.takeIf { result == Tunnel.State.UP && ownsIntent }, persistRunningIntent = ownsIntent)
                        NetworkAutomationState.update { state ->
                            if (state.episode != admission.episode || state.manual?.serial != admission.serial || state.revision != admission.revision ||
                                !manager.isAutomationTarget(admission.tunnel) || admission.tunnel.name != admission.name) state
                            else state.copy(manual = requireNotNull(state.manual).copy(outcome = when {
                                failure != null -> NetworkAutomationState.Outcome.FAILED
                                result == Tunnel.State.UP -> NetworkAutomationState.Outcome.UP
                                else -> NetworkAutomationState.Outcome.DOWN
                            }))
                        }
                        if (failure == null && result == Tunnel.State.UP && current.episode == admission.episode &&
                            current.manual?.serial == admission.serial && current.revision == admission.revision &&
                            manager.isAutomationTarget(admission.tunnel) && admission.tunnel.name == admission.name) {
                            val backend = Application.getBackend() as? GoBackend
                            manualOwner = backend?.getTransportSnapshot(admission.tunnel)?.takeIf { it.ready() }
                                ?.let { admission.tunnel to it.session() }
                        }
                    }
                } catch (settlement: Throwable) {
                    if (failure == null) failure = settlement else failure?.addSuppressed(settlement)
                }
            }
            evaluations.trySend(Unit)
            failure?.let { throw it }
            currentCoroutineContext().ensureActive()
            return requireNotNull(result)
        } finally {
            if (manualWork === admission.settlement) {
                manualWork = null
                manualAuthority = null
            }
            admission.settlement.complete(Unit)
        }
    }

    private fun setManualOwnedState(admission: ManualAdmission, backend: GoBackend, config: com.vpn0wn.config.Config): Tunnel.State {
        val actual = backend.getTransportSnapshot(admission.tunnel)
        val network = latest?.select(actual?.network()) ?: throw IOException("No eligible public network")
        if (actual == null) {
            return backend.setStateOwned(admission.tunnel, Tunnel.State.UP, config, network, admission.authority)
        }
        val transport = backend.replaceTransport(admission.tunnel, actual, GoBackend.TransportMode.OWNED, network, admission.authority)
        if (transport.outcome() != GoBackend.TransportOutcome.READY || !transport.currentActivation()) {
            throw IOException("Owned transport is unavailable")
        }
        return Tunnel.State.UP
    }

    private suspend fun admit(): Request? = manager.automationAdmission {
        val networks = latest ?: return@automationAdmission null
        if (observer !== networks.generation) return@automationAdmission null
        var state = read()
        if (!state.enabled || state.paused) return@automationAdmission null
        val manual = state.manual
        if (manual != null && manual.outcome != NetworkAutomationState.Outcome.UP) {
            show(state, Condition.MANUAL_HOLD)
            return@automationAdmission null
        }
        val requestedName = manual?.name ?: state.defaultName
        val tunnel = requestedName?.let { manager.automationTarget(it) }
        if (tunnel == null || requestedName == state.defaultName && !state.resolved) {
            show(state, if (manual == null) Condition.UNRESOLVED else Condition.MANUAL_HOLD)
            return@automationAdmission null
        }
        val name = tunnel.name
        val backend = requireSingleUserspace(Application.getBackend())
        val config = tunnel.getConfigAsync()
        val actual = backend.getTransportSnapshot(tunnel)
        val exactManualOwner = actual != null && manualOwner?.first === tunnel && actual.session() === manualOwner?.second
        if (manual != null && (actual?.mode() == GoBackend.TransportMode.LEGACY ||
                name != state.defaultName && !exactManualOwner ||
                name == state.defaultName && actual == null && name !in UserKnobs.runningTunnels.first())) {
            show(state, Condition.MANUAL_HOLD)
            return@automationAdmission null
        }
        val winner = networks.select(actual?.network())
        val changed = baseline && winner != selected
        selected = winner
        if (winner != null) baseline = true
        state = NetworkAutomationState.update {
            it.copy(frontier = maxOf(it.frontier, networks.sequence), selection = it.selection + if (changed) 1 else 0)
        }
        if (state.equivalentAttempt(name) && !(exactManualOwner && manual != null && state.attempt?.let { manual.serial > it.serial } == true)) {
            show(state, Condition.FAILED)
            return@automationAdmission null
        }
        if (actual?.ready() == true && actual.mode() == GoBackend.TransportMode.OWNED && actual.network() == winner) {
            show(state, Condition.OWNED)
            return@automationAdmission null
        }
        if (winner == null && (actual == null || !actual.ready() && actual.mode() == GoBackend.TransportMode.UNAVAILABLE)) {
            show(state, Condition.WAITING_NETWORK)
            return@automationAdmission null
        }
        val receipt = NetworkAutomationState.Receipt(name, if (actual == null) "UP" else "TRANSPORT", "UP",
            state.revision, networks.sequence, state.selection, state.serial + 1)
        state = NetworkAutomationState.update { it.copy(attempt = receipt, serial = receipt.serial) }
        val recovery = manager.invalidateAutomationRecovery()
        val authority = GoBackend.TransportAuthority()
        ownedAuthority = authority
        Request(tunnel, backend, state, receipt, actual, winner, recovery, authority, config)
    }

    private class Entry {
        @Volatile var result: GoBackend.TransportResult? = null
        @Volatile var state: Tunnel.State? = null
        @Volatile var failure: Throwable? = null
    }

    // Capture results and original defects inside IO before dispatcher cancellation can discard them.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun evaluate() {
        val request = try {
            admit()
        } catch (_: IOException) {
            mutableStatus.value = mutableStatus.value.copy(condition = Condition.FAILED)
            return
        } ?: return
        val entry = Entry()
        val child = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            runInterruptible {
                try {
                    if (request.expected == null) {
                        entry.state = request.backend.setStateOwned(request.tunnel, Tunnel.State.UP,
                            request.config, requireNotNull(request.network), request.authority)
                    } else {
                        entry.result = request.backend.replaceTransport(request.tunnel, request.expected,
                            if (request.network == null) GoBackend.TransportMode.UNAVAILABLE else GoBackend.TransportMode.OWNED, request.network, request.authority)
                    }
                } catch (caught: Throwable) {
                    entry.failure = caught
                }
            }
        }
        ownedWork = child
        var dispatchFailure: Throwable? = null
        var settlementFailure: Throwable? = null
        try {
            request.recovery?.join()
            manager.automationAdmission {
                val state = read()
                if (!state.enabled || state.paused || state.episode != request.state.episode ||
                    state.attempt?.serial != request.receipt.serial || state.revision != request.state.revision ||
                    observer !== latest?.generation || !manager.isAutomationTarget(request.tunnel)) child.cancel()
            }
            child.start()
            child.await()
        } catch (caught: Throwable) {
            dispatchFailure = caught
        } finally {
            withContext(NonCancellable) {
                child.cancel()
                child.join()
                try {
                    manager.automationAdmission {
                        if (ownedWork === child) ownedWork = null
                        val current = read()
                        val ownsIntent = current.enabled && !current.paused && current.episode == request.state.episode &&
                            current.revision == request.state.revision && current.attempt?.serial == request.receipt.serial &&
                            (current.manual?.serial ?: -1) < request.receipt.serial && manager.isAutomationTarget(request.tunnel)
                        manager.reconcileAutomation(persistRunningIntent = ownsIntent)
                        val success = entry.failure == null && (entry.state == Tunnel.State.UP ||
                            entry.result?.let { it.currentActivation() && it.contextReusable() && it.outcome() == GoBackend.TransportOutcome.READY } == true)
                        val lossSettled = request.network == null && entry.failure == null && entry.result?.let {
                            it.currentActivation() && it.contextReusable() && it.outcome() == GoBackend.TransportOutcome.NO_TRANSPORT &&
                                it.snapshot()?.let { snapshot -> !snapshot.ready() && snapshot.mode() == GoBackend.TransportMode.UNAVAILABLE } == true
                        } == true
                        val state = NetworkAutomationState.update {
                            if (it.episode != request.state.episode || it.attempt?.serial != request.receipt.serial) it
                            else it.copy(attempt = requireNotNull(it.attempt).copy(outcome = when {
                                success -> NetworkAutomationState.Outcome.UP
                                lossSettled -> NetworkAutomationState.Outcome.NO_TRANSPORT
                                else -> NetworkAutomationState.Outcome.FAILED
                            }))
                        }
                        if (success && state.episode == request.state.episode && state.attempt?.serial == request.receipt.serial &&
                            manager.isAutomationTarget(request.tunnel)) {
                            automaticOwner = request.backend.getTransportSnapshot(request.tunnel)?.takeIf { it.ready() }
                                ?.let { request.tunnel to it.session() }
                        }
                        show(state, when {
                            !state.enabled -> disabledCondition(state)
                            state.manual?.let { it.outcome != NetworkAutomationState.Outcome.UP || it.name != state.defaultName } == true -> Condition.MANUAL_HOLD
                            success -> Condition.OWNED
                            request.network == null -> Condition.WAITING_NETWORK
                            else -> Condition.FAILED
                        })
                    }
                } catch (caught: Throwable) {
                    settlementFailure = caught
                    mutableStatus.value = mutableStatus.value.copy(condition = Condition.FAILED, settling = false)
                }
            }
        }
        val failure = entry.failure ?: dispatchFailure
        if (failure != null && failure !is IOException && failure !is BackendException && failure !is InterruptedException && failure !is CancellationException) {
            settlementFailure?.let(failure::addSuppressed)
            throw failure
        }
        settlementFailure?.let { settlement ->
            if (settlement !is IOException) {
                failure?.let(settlement::addSuppressed)
                throw settlement
            }
        }
    }

    private fun disabledCondition(state: NetworkAutomationState, actual: GoBackend.TransportSnapshot? = null): Condition = when (state.exit) {
        NetworkAutomationState.Exit.COMPLETE -> liveExit?.let { proof ->
            liveExitBackend?.getTransportSnapshot(proof.first)?.takeIf {
                it.session() === proof.second.session() && it.ready() && it.mode() == GoBackend.TransportMode.LEGACY
            }?.let { Condition.ANDROID_HANDLING }
        } ?: Condition.DISABLED
        NetworkAutomationState.Exit.PENDING -> Condition.EXIT_PENDING
        NetworkAutomationState.Exit.FAILED, NetworkAutomationState.Exit.SUPERSEDED -> when {
            actual?.ready() == true && actual.mode() == GoBackend.TransportMode.OWNED -> Condition.EXIT_RETAINED_PIN
            actual?.mode() == GoBackend.TransportMode.UNAVAILABLE -> Condition.EXIT_NO_TRANSPORT
            else -> Condition.EXIT_UNKNOWN
        }
        else -> Condition.DISABLED
    }

    private fun show(state: NetworkAutomationState, condition: Condition, settling: Boolean = false) {
        mutableStatus.value = Status(condition, state.enabled, state.defaultName, state.exit, settling)
    }
}
