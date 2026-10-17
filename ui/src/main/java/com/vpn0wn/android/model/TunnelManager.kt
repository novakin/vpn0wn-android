/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Modified for vpn0wn.
 */
package com.vpn0wn.android.model

import com.vpn0wn.android.Application

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.databinding.BaseObservable
import androidx.databinding.Bindable
import androidx.datastore.preferences.core.edit
import com.vpn0wn.android.Application.Companion.get
import com.vpn0wn.android.Application.Companion.getBackend
import com.vpn0wn.android.Application.Companion.getTunnelManager
import com.vpn0wn.android.Application.Companion.getPreferencesDataStore
import com.vpn0wn.android.BR
import com.vpn0wn.android.R
import com.vpn0wn.android.backend.Backend
import com.vpn0wn.android.backend.BackendException
import com.vpn0wn.android.backend.GoBackend
import com.vpn0wn.android.backend.HandshakeObservation
import com.vpn0wn.android.backend.HandshakeSession
import com.vpn0wn.android.backend.RecoveryResult
import com.vpn0wn.android.backend.Statistics
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.backend.WgQuickBackend
import com.vpn0wn.android.configStore.ConfigStore
import com.vpn0wn.android.databinding.ObservableSortedKeyedArrayList
import com.vpn0wn.android.util.ErrorMessages
import com.vpn0wn.android.util.RootShell.RootShellException
import com.vpn0wn.android.util.AppDataTransfer
import com.vpn0wn.android.util.UserKnobs
import com.vpn0wn.android.util.applicationScope
import com.vpn0wn.config.Config
import com.vpn0wn.config.InetAddresses
import com.vpn0wn.config.ParseException
import com.vpn0wn.crypto.Key
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Maintains and mediates changes to the set of available WireGuard tunnels,
 */
class TunnelManager(private val configStore: ConfigStore) : BaseObservable() {
    internal class NotManagedException : IllegalStateException("Tunnel is no longer managed")

    private var tunnels = CompletableDeferred<ObservableSortedKeyedArrayList<String, ObservableTunnel>>()
    private var loadingJob: Job? = null
    private val operationLock = Mutex()
    private val context: Context = get()
    private val tunnelMap: ObservableSortedKeyedArrayList<String, ObservableTunnel> = ObservableSortedKeyedArrayList(TunnelComparator)
    private var haveLoaded = false
    private var activityRestoreRequested = false
    private var backendSwitchPending = false
    private var lastUsedPersistence: Job? = null
    private var selectedLastUsedTunnel: ObservableTunnel? = null
    private val statisticsScope = applicationScope
    private val statisticsRequests = mutableMapOf<ObservableTunnel, StatisticsRequest>()
    private var recoveryActivation: RecoveryActivation? = null

    private class RecoveryActivation(
        val tunnel: ObservableTunnel,
        val backend: GoBackend,
        val session: HandshakeSession,
    ) {
        var generation = 0L
        val handledEpisodes = mutableMapOf<Key, Long>()
        var confirmedAdmissions = 0
        var chargedAttempts = 0
        var backendEntries = 0
        var unknownSettlements = 0
        var lastChargedAt: Long? = null
        var disabled = false
        var request: RecoveryRequest? = null
        var lastTriggerPeer: Key? = null
        var lastRequestPeer: Key? = null
        var lastRequestAt: Long? = null
        var lastOutcome: RecoveryResult.Outcome? = null
        var lastResultRecordedAt: Long? = null
        var status = ObservableTunnel.RecoveryStatus.IDLE
        var failureCategory: ObservableTunnel.RecoveryFailureCategory? = null
        var transportState = ObservableTunnel.RecoveryTransportState.UNKNOWN
        var powerState = ObservableTunnel.RecoveryPowerState.UNKNOWN
    }

    private class RecoveryRequest(
        val activation: RecoveryActivation,
        val observation: HandshakeObservation,
        val generation: Long,
    ) {
        lateinit var job: Job
        @Volatile var started = false
        var child: Deferred<Unit>? = null
    }

    private class RecoveryEntry(val requestedAt: Long) {
        @Volatile var entered = false
        @Volatile var enteredAt: Long? = null
        @Volatile var result: RecoveryResult? = null
        @Volatile var failure: Throwable? = null
    }

    private class StatisticsRequest(
        val generation: Long,
        val backend: Backend,
        val name: String,
        val read: Deferred<Statistics>,
    ) {
        var readers = 0
        val obsoleteCause = CancellationException("Tunnel statistics session changed")
    }

    private fun addToList(name: String, config: Config?, state: Tunnel.State): ObservableTunnel {
        val tunnel = ObservableTunnel(this, name, config, state)
        tunnelMap.add(tunnel)
        return tunnel
    }

    suspend fun getTunnels(): ObservableSortedKeyedArrayList<String, ObservableTunnel> = withContext(Dispatchers.Main.immediate) {
        if (!haveLoaded && loadingJob?.isActive != true)
            onCreate()
        tunnels.await()
    }

    private suspend fun <T> mutate(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) {
        operationLock.withLock {
            check(!backendSwitchPending) { "Backend switch awaits restart" }
            // Once admitted, finish backend/store reconciliation before honouring caller cancellation.
            val result = withContext(NonCancellable) { block() }
            currentCoroutineContext().ensureActive()
            result
        }
    }

    private fun requireManaged(tunnel: ObservableTunnel) {
        if (tunnelMap[tunnel.name] !== tunnel) throw NotManagedException()
    }

    private fun addRecoveryFailure(failure: Throwable, recovery: Throwable) {
        if (failure === recovery) return
        failure.addSuppressed(recovery)
        // Coroutine stack recovery may unwrap a same-class copy at the next suspension boundary.
        val cause = failure.cause
        if (cause != null && cause.javaClass == failure.javaClass && cause !== recovery) cause.addSuppressed(recovery)
    }

    suspend fun create(name: String, config: Config): ObservableTunnel = mutate {
        getTunnels()
        if (Tunnel.isNameInvalid(name))
            throw IllegalArgumentException(context.getString(R.string.tunnel_error_invalid_name))
        if (tunnelMap.containsKey(name))
            throw IllegalArgumentException(context.getString(R.string.tunnel_error_already_exists, name))
        addToList(name, withContext(Dispatchers.IO) { configStore.create(name, config) }, Tunnel.State.DOWN)
    }

    internal suspend fun exportAppData(): AppDataTransfer.Archive = mutate {
        getTunnels()
        lastUsedPersistence?.join()
        val profiles = withContext(Dispatchers.IO) {
            tunnelMap.associate { it.name to configStore.load(it.name) }
        }
        val preferences = getPreferencesDataStore().data.first()
        withContext(Dispatchers.IO) { AppDataTransfer.snapshot(profiles, preferences).also(AppDataTransfer::validate) }
    }

    // ConfigStore/metadata failure needs owned-file rollback before propagating cancellation or defects.
    @Suppress("TooGenericExceptionCaught")
    internal suspend fun importAppData(archive: AppDataTransfer.Archive): Int = mutate {
        withContext(Dispatchers.IO) { AppDataTransfer.validate(archive) }
        getTunnels()
        lastUsedPersistence?.join()
        val existingNames = tunnelMap.map { it.name }.toSet()
        val newProfiles = withContext(Dispatchers.IO) {
            archive.profiles.filter { (name, config) ->
                if (name !in existingNames) true
                else {
                    if (configStore.load(name).toWgQuickString() != config.toWgQuickString()) throw AppDataTransfer.ConflictException()
                    false
                }
            }
        }
        val selection = archive.lastUsedTunnel
        if (selection != null && selection !in archive.profiles && !tunnelMap.containsKey(selection)) {
            throw AppDataTransfer.InvalidArchiveException()
        }
        Application.getVpsServerStore().withImportAdmission(archive.servers) {
            val preferencesStore = getPreferencesDataStore()
            val restoreNames = preferencesStore.data.first()[UserKnobs.RUNNING_TUNNELS].orEmpty()
            if (restoreNames.any { it in newProfiles }) {
                // Commit stale-intent removal before files can survive a failed metadata commit and rollback.
                preferencesStore.edit { preferences ->
                    AppDataTransfer.validateMetadata(archive, preferences)
                    AppDataTransfer.clearRestoreMarkers(preferences, newProfiles.keys)
                }
            }
            val created = linkedMapOf<String, Config>()
            try {
                preferencesStore.edit { preferences ->
                    val servers = AppDataTransfer.validateMetadata(archive, preferences)
                    for ((name, config) in newProfiles) {
                        created[name] = withContext(Dispatchers.IO) { configStore.create(name, config) }
                    }
                    AppDataTransfer.applyMetadata(archive, preferences, servers, created.keys)
                }
            } catch (failure: Throwable) {
                var incomplete = false
                for ((name, config) in created) {
                    try {
                        withContext(Dispatchers.IO) { configStore.delete(name) }
                    } catch (recovery: Throwable) {
                        incomplete = true
                        addRecoveryFailure(failure, recovery)
                        addToList(name, config, Tunnel.State.DOWN)
                    }
                }
                if (incomplete) throwIncompleteTransferRecovery(failure)
                throw failure
            }
            for ((name, config) in created) addToList(name, config, Tunnel.State.DOWN)
            if (selection != null) publishLastUsedTunnel(tunnelMap[selection])
            created.size
        }
    }

    private fun throwIncompleteTransferRecovery(failure: Throwable): Nothing {
        if (failure !is Exception || failure is RuntimeException && failure !is SecurityException) throw failure
        throw AppDataTransfer.IncompleteRecoveryException().apply { initCause(failure) }
    }

    // Backend/ConfigStore declare Exception; admitted mutations reconcile before rethrowing, and recovery failures stay attached.
    @Suppress("TooGenericExceptionCaught")
    suspend fun delete(tunnel: ObservableTunnel) = mutate {
        requireManaged(tunnel)
        invalidateRecovery(tunnel)
        tunnel.invalidateStatistics()
        val backend = getBackend()
        val originalState = withContext(Dispatchers.IO) { backend.getState(tunnel) }
        val wasLastUsed = tunnel == lastUsedTunnel
        val config = getDownConfig(tunnel, backend, originalState)
        withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.DOWN, config) }
        tunnel.onStateChanged(Tunnel.State.DOWN)
        try {
            withContext(Dispatchers.IO) { configStore.delete(tunnel.name) }
        } catch (e: Exception) {
            if (originalState == Tunnel.State.UP) {
                try {
                    val config = tunnel.getConfigAsync()
                    tunnel.onStateChanged(withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.UP, config) })
                } catch (recovery: Exception) {
                    e.addSuppressed(recovery)
                }
            }
            throw e
        }
        tunnelMap.remove(tunnel)
        if (wasLastUsed)
            lastUsedTunnel = null
        saveState()
    }

    @get:Bindable
    var lastUsedTunnel: ObservableTunnel?
        get() = selectedLastUsedTunnel
        private set(value) {
            if (!publishLastUsedTunnel(value)) return
            val previous = lastUsedPersistence
            lastUsedPersistence = applicationScope.launch {
                previous?.join()
                UserKnobs.setLastUsedTunnel(value?.name)
            }
        }

    private fun publishLastUsedTunnel(value: ObservableTunnel?): Boolean {
        if (value == selectedLastUsedTunnel) return false
        selectedLastUsedTunnel = value
        notifyPropertyChanged(BR.lastUsedTunnel)
        return true
    }

    suspend fun getTunnelConfig(tunnel: ObservableTunnel): Config = withContext(Dispatchers.Main.immediate) {
        requireManaged(tunnel)
        var current: Config?
        do {
            val name = tunnel.name
            val generation = tunnel.configGeneration
            val config = withContext(Dispatchers.IO) { configStore.load(name) }
            requireManaged(tunnel)
            current = if (name == tunnel.name && generation == tunnel.configGeneration)
                tunnel.onConfigChanged(config) else tunnel.peekConfig()
        } while (current == null)
        current
    }

    // Backend/ConfigStore declare Exception; admitted mutations reconcile before rethrowing, and recovery failures stay attached.
    @Suppress("TooGenericExceptionCaught")
    fun onCreate() {
        if (haveLoaded || loadingJob?.isActive == true)
            return
        if (tunnels.isCompleted)
            tunnels = CompletableDeferred()
        val result = tunnels
        loadingJob = applicationScope.launch {
            try {
                onTunnelsLoaded(withContext(Dispatchers.IO) { configStore.enumerate() }, withContext(Dispatchers.IO) { getBackend().runningTunnelNames })
                result.complete(tunnelMap)
            } catch (e: CancellationException) {
                result.cancel(e)
                throw e
            } catch (e: Exception) {
                result.completeExceptionally(e)
                Log.e(TAG, "Failed to load tunnels on create", e)
                return@launch
            }
        }.also { job ->
            job.invokeOnCompletion { cause ->
                if (cause != null)
                    result.completeExceptionally(cause)
            }
        }
    }

    // Backend/ConfigStore expose Exception; background restore reports failures while cancellation and Errors propagate.
    @Suppress("TooGenericExceptionCaught")
    fun onActivityCreated() {
        if (activityRestoreRequested) return
        activityRestoreRequested = true
        applicationScope.launch {
            try {
                restoreState(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore tunnel state", e)
            }
        }
    }

    private suspend fun onTunnelsLoaded(present: Iterable<String>, running: Collection<String>) {
        val lastUsedName = UserKnobs.lastUsedTunnel.first()
        for (name in present)
            addToList(name, null, if (running.contains(name)) Tunnel.State.UP else Tunnel.State.DOWN)
        if (lastUsedName != null)
            publishLastUsedTunnel(tunnelMap[lastUsedName])
        haveLoaded = true
    }

    // Backend/ConfigStore declare Exception; admitted mutations reconcile before rethrowing, and recovery failures stay attached.
    @Suppress("TooGenericExceptionCaught")
    private fun refreshTunnelStates() {
        applicationScope.launch {
            try {
                mutate {
                    refreshObservedStates()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh tunnel states", e)
            }
        }
    }

    // Backend/ConfigStore declare Exception; admitted mutations reconcile before rethrowing, and recovery failures stay attached.
    @Suppress("TooGenericExceptionCaught")
    suspend fun restoreState(force: Boolean) {
        val eligible = getTunnels().toList()
        if (!force && !UserKnobs.restoreOnBoot.first()) return
        val previouslyRunning = UserKnobs.runningTunnels.first()
        if (previouslyRunning.isEmpty()) return
        coroutineScope {
            eligible.filter { previouslyRunning.contains(it.name) }.map { tunnel ->
                async {
                    try {
                        mutate {
                            requireManaged(tunnel)
                            changeTunnelState(tunnel, Tunnel.State.UP)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restore tunnel ${tunnel.name}", e)
                    }
                }
            }.awaitAll()
        }
    }

    suspend fun saveState() {
        val tunnels = getTunnels()
        UserKnobs.setRunningTunnels(tunnels.filter { it.state == Tunnel.State.UP }.map { it.name }.toSet())
    }

    private suspend fun refreshObservedStates() {
        val running = withContext(Dispatchers.IO) { getBackend().runningTunnelNames }
        for (tunnel in tunnelMap)
            tunnel.onStateChanged(if (running.contains(tunnel.name)) Tunnel.State.UP else Tunnel.State.DOWN)
    }

    // Backend/ConfigStore declare Exception; admitted mutations reconcile before rethrowing, and recovery failures stay attached.
    @Suppress("TooGenericExceptionCaught")
    suspend fun setTunnelConfig(tunnel: ObservableTunnel, config: Config): Config = mutate {
        requireManaged(tunnel)
        invalidateRecovery(tunnel)
        tunnel.invalidateStatistics()
        val previousConfig = tunnel.getConfigAsync()
        val backend = getBackend()
        tunnel.onConfigChanged(withContext(Dispatchers.IO) {
            val state = backend.getState(tunnel)
            backend.setState(tunnel, state, config)
            try {
                configStore.save(tunnel.name, config)
            } catch (e: Exception) {
                try {
                    backend.setState(tunnel, state, previousConfig)
                } catch (recovery: Exception) {
                    e.addSuppressed(recovery)
                }
                throw e
            }
        })!!
    }

    // Backend/ConfigStore declare Exception; admitted mutations reconcile before rethrowing, and recovery failures stay attached.
    @Suppress("TooGenericExceptionCaught")
    suspend fun setTunnelName(tunnel: ObservableTunnel, name: String): String = mutate {
        requireManaged(tunnel)
        if (Tunnel.isNameInvalid(name))
            throw IllegalArgumentException(context.getString(R.string.tunnel_error_invalid_name))
        if (tunnelMap.containsKey(name)) {
            throw IllegalArgumentException(context.getString(R.string.tunnel_error_already_exists, name))
        }
        invalidateRecovery(tunnel)
        tunnel.invalidateStatistics()
        val backend = getBackend()
        val originalState = withContext(Dispatchers.IO) { backend.getState(tunnel) }
        val wasLastUsed = tunnel == lastUsedTunnel
        if (wasLastUsed)
            lastUsedTunnel = null
        var failure: Exception? = null
        try {
            if (originalState == Tunnel.State.UP) {
                val config = getDownConfig(tunnel, backend, originalState)
                withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.DOWN, config) }
            }
            withContext(Dispatchers.IO) { configStore.rename(tunnel.name, name) }
            tunnelMap.remove(tunnel)
            tunnel.onNameChanged(name)
            tunnelMap.add(tunnel)
            if (originalState == Tunnel.State.UP)
                withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.UP, tunnel.getConfigAsync()) }
        } catch (e: Exception) {
            failure = e
            try {
                tunnel.onStateChanged(withContext(Dispatchers.IO) { backend.getState(tunnel) })
            } catch (recovery: Exception) {
                addRecoveryFailure(e, recovery)
            }
        } finally {
            tunnelMap.add(tunnel)
            if (wasLastUsed)
                lastUsedTunnel = tunnel
        }
        try {
            refreshObservedStates()
            saveState()
        } catch (e: Exception) {
            if (failure == null) throw e
            addRecoveryFailure(failure, e)
        }
        if (failure != null) throw failure
        tunnel.name
    }

    suspend fun setTunnelState(tunnel: ObservableTunnel, state: Tunnel.State): Tunnel.State = mutate {
        requireManaged(tunnel)
        invalidateRecovery(if (state == Tunnel.State.DOWN) tunnel else null)
        changeTunnelState(tunnel, state)
    }

    suspend fun prepareBackendSwitch(enableKernel: Boolean) = mutate {
        invalidateRecovery()
        for (tunnel in tunnelMap)
            tunnel.invalidateStatistics()
        for (tunnel in tunnelMap)
            changeTunnelState(tunnel, Tunnel.State.DOWN)
        UserKnobs.setEnableKernelModule(enableKernel)
        backendSwitchPending = true
    }

    suspend fun cancelBackendSwitch(previousSetting: Boolean?) = withContext(Dispatchers.Main.immediate + NonCancellable) {
        operationLock.withLock {
            UserKnobs.setEnableKernelModule(previousSetting)
            backendSwitchPending = false
        }
    }

    private suspend fun getDownConfig(tunnel: ObservableTunnel, backend: Backend, observedState: Tunnel.State): Config? =
        if (backend is WgQuickBackend && observedState == Tunnel.State.UP) tunnel.getConfigAsync() else null

    // Backend/ConfigStore declare Exception; admitted mutations reconcile before rethrowing, and recovery failures stay attached.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun changeTunnelState(tunnel: ObservableTunnel, state: Tunnel.State): Tunnel.State {
        val backend = getBackend()
        var newState = withContext(Dispatchers.IO) { backend.getState(tunnel) }
        val target = if (state == Tunnel.State.TOGGLE) {
            if (newState == Tunnel.State.UP) Tunnel.State.DOWN else Tunnel.State.UP
        } else state
        if (target == newState) {
            tunnel.onStateChanged(newState)
            refreshObservedStates()
            saveState()
            return newState
        }
        var failure: Exception? = null
        tunnel.onTransitionStarted(target)
        try {
            val config = if (target == Tunnel.State.DOWN) getDownConfig(tunnel, backend, newState) else tunnel.getConfigAsync()
            newState = withContext(Dispatchers.IO) { backend.setState(tunnel, target, config) }
            if (newState == Tunnel.State.UP)
                lastUsedTunnel = tunnel
        } catch (e: Exception) {
            failure = e
            try {
                newState = withContext(Dispatchers.IO) { backend.getState(tunnel) }
            } catch (recovery: Exception) {
                e.addSuppressed(recovery)
            }
        } finally {
            tunnel.onStateChanged(newState)
        }
        try {
            refreshObservedStates()
            saveState()
        } catch (e: Exception) {
            if (failure == null)
                throw e
            failure.addSuppressed(e)
        }
        if (failure != null)
            throw failure
        return newState
    }

    class IntentReceiver : BroadcastReceiver() {
        // Backend/ConfigStore declare Exception; admitted mutations reconcile before rethrowing, and recovery failures stay attached.
        @Suppress("TooGenericExceptionCaught")
        override fun onReceive(context: Context, intent: Intent?) {
            applicationScope.launch {
                val manager = getTunnelManager()
                if (intent == null) return@launch
                val action = intent.action ?: return@launch
                if (action == "com.vpn0wn.android.action.REFRESH_TUNNEL_STATES" ||
                    action == "com.wireguard.android.action.REFRESH_TUNNEL_STATES") {
                    manager.refreshTunnelStates()
                    return@launch
                }
                if (!UserKnobs.allowRemoteControlIntents.first())
                    return@launch
                val state = when (action) {
                    "com.vpn0wn.android.action.SET_TUNNEL_UP" -> Tunnel.State.UP
                    "com.vpn0wn.android.action.SET_TUNNEL_DOWN" -> Tunnel.State.DOWN
                    else -> return@launch
                }
                val tunnelName = intent.getStringExtra("tunnel") ?: return@launch
                val tunnels = try {
                    manager.getTunnels()
                } catch (e: IOException) {
                    Toast.makeText(context, ErrorMessages[e], Toast.LENGTH_LONG).show()
                    return@launch
                } catch (e: RootShellException) {
                    Toast.makeText(context, ErrorMessages[e], Toast.LENGTH_LONG).show()
                    return@launch
                }
                val tunnel = tunnels[tunnelName] ?: return@launch
                try {
                    manager.setTunnelState(tunnel, state)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.makeText(context, ErrorMessages[e], Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    suspend fun getTunnelState(tunnel: ObservableTunnel): Tunnel.State = mutate {
        requireManaged(tunnel)
        tunnel.onStateChanged(withContext(Dispatchers.IO) { getBackend().getState(tunnel) })
    }

    // Backend/ConfigStore declare Exception; admitted mutations reconcile before rethrowing, and recovery failures stay attached.
    @Suppress("TooGenericExceptionCaught")
    internal fun onTunnelStateChange(tunnel: ObservableTunnel) {
        applicationScope.launch {
            tunnel.discardObsoleteStatistics()
            try {
                mutate {
                    if (tunnelMap[tunnel.name] === tunnel) {
                        tunnel.onStateChanged(withContext(Dispatchers.IO) { getBackend().getState(tunnel) })
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reconcile backend state notification", e)
            }
        }
    }

    internal suspend fun onHandshakeSessionAccepted(tunnel: ObservableTunnel, session: HandshakeSession) {
        if (tunnelMap[tunnel.name] !== tunnel || tunnel.activeHandshakeSession !== session) return
        val backend = getBackend() as? GoBackend ?: return
        val previous = recoveryActivation
        if (tunnelMap[tunnel.name] !== tunnel || tunnel.activeHandshakeSession !== session ||
            previous?.tunnel === tunnel && previous.session === session) return
        invalidateRecovery()
        val activation = RecoveryActivation(tunnel, backend, session)
        recoveryActivation = activation
        publishRecovery(activation)
    }

    internal fun onHandshakeSessionRetired(tunnel: ObservableTunnel) {
        val activation = recoveryActivation ?: return
        if (activation.tunnel !== tunnel || tunnel.activeHandshakeSession === activation.session) return
        invalidateRecovery(tunnel)
        recoveryActivation = null
    }

    internal suspend fun onHandshakeExhausted(tunnel: ObservableTunnel, observation: HandshakeObservation) {
        onHandshakeSessionAccepted(tunnel, observation.session())
        val activation = recoveryActivation ?: return
        if (!claimRecoveryEpisode(activation, tunnel, observation)) return
        if (activation.request != null) {
            activation.status = ObservableTunnel.RecoveryStatus.DROPPED_BUSY
            publishRecovery(activation)
        } else {
            val request = RecoveryRequest(activation, observation, activation.generation)
            activation.request = request
            activation.status = ObservableTunnel.RecoveryStatus.PENDING
            request.job = applicationScope.launch(start = CoroutineStart.LAZY) {
                request.started = true
                runRecovery(request)
            }
            request.job.invokeOnCompletion {
                if (!request.started) applicationScope.launch(Dispatchers.Main.immediate + NonCancellable) {
                    releaseRecovery(request, true)
                }
            }
            publishRecovery(activation)
            request.job.start()
        }
    }

    private fun claimRecoveryEpisode(
        activation: RecoveryActivation,
        tunnel: ObservableTunnel,
        observation: HandshakeObservation,
    ): Boolean {
        if (activation.tunnel !== tunnel || activation.session !== observation.session()) return false
        val peers = tunnel.peekConfig()?.peers?.map { it.publicKey }?.toSet() ?: return false
        activation.handledEpisodes.keys.retainAll(peers)
        val previousEpisode = activation.handledEpisodes[observation.peer()] ?: 0L
        val fresh = observation.peer() in peers && observation.exhaustedEpisode() > previousEpisode
        if (fresh) {
            activation.handledEpisodes[observation.peer()] = observation.exhaustedEpisode()
            activation.lastTriggerPeer = observation.peer()
        }
        return fresh && hasNumericEndpoint(observation)
    }

    private fun hasNumericEndpoint(observation: HandshakeObservation): Boolean {
        val endpoint = observation.endpoint() ?: return false
        val address = try {
            InetAddresses.parse(endpoint.host)
        } catch (failure: ParseException) {
            return false
        }
        return when (observation.addressFamily()) {
            HandshakeObservation.AddressFamily.IPV4 -> address is Inet4Address
            HandshakeObservation.AddressFamily.IPV6 -> address is Inet6Address
            HandshakeObservation.AddressFamily.UNKNOWN -> false
        }
    }

    private fun invalidateRecovery(tunnel: ObservableTunnel? = null) {
        val activation = recoveryActivation ?: return
        if (tunnel != null && activation.tunnel !== tunnel) return
        activation.generation++
        activation.request?.job?.cancel(CancellationException("Tunnel recovery request changed"))
    }

    private suspend fun runRecovery(request: RecoveryRequest) {
        val activation = request.activation
        try {
            if (admitRecovery(request)) {
                val first = enterRecovery(request)
                if (first?.outcome() == RecoveryResult.Outcome.BUSY && first.contextReusable() && first.currentActivation()) {
                    currentCoroutineContext().ensureActive()
                    activation.status = ObservableTunnel.RecoveryStatus.BUSY_DEFERRED
                    publishRecovery(activation)
                    delay(RECOVERY_BUSY_DELAY_MILLIS)
                    if (admitRecovery(request)) enterRecovery(request)
                }
            }
        } finally {
            val cancelled = currentCoroutineContext()[Job]?.isCancelled == true
            withContext(NonCancellable) {
                request.child?.cancel()
                request.child?.join()
                withContext(Dispatchers.Main.immediate) {
                    releaseRecovery(request, cancelled)
                }
            }
        }
    }

    private fun releaseRecovery(request: RecoveryRequest, cancelled: Boolean) {
        val activation = request.activation
        if (activation.request === request) activation.request = null
        if (cancelled && activation.status != ObservableTunnel.RecoveryStatus.FAILED)
            activation.status = ObservableTunnel.RecoveryStatus.CANCELLED
        publishRecovery(activation)
    }

    private suspend fun admitRecovery(request: RecoveryRequest): Boolean = operationLock.withLock {
        currentCoroutineContext().ensureActive()
        val activation = request.activation
        val tunnel = activation.tunnel
        val currentObservation = tunnel.handshakeDiagnostics?.get(request.observation.peer())?.lastObservation
        val obsolete = recoveryActivation !== activation || activation.request !== request ||
            activation.generation != request.generation || tunnelMap[tunnel.name] !== tunnel ||
            tunnel.activeHandshakeSession !== activation.session || getBackend() !== activation.backend ||
            backendSwitchPending || tunnel.state != Tunnel.State.UP || tunnel.transitionTarget != null ||
            tunnel.peekConfig()?.peers?.any { it.publicKey == request.observation.peer() } != true ||
            currentObservation?.sequence() != request.observation.sequence()
        val now = SystemClock.elapsedRealtime()
        val status = when {
            obsolete -> ObservableTunnel.RecoveryStatus.OBSOLETE
            activation.disabled -> ObservableTunnel.RecoveryStatus.DISABLED
            readRecoveryPower(activation) == ObservableTunnel.RecoveryPowerState.DEEP_IDLE -> ObservableTunnel.RecoveryStatus.DROPPED_DOZE
            activation.chargedAttempts >= RECOVERY_ATTEMPT_LIMIT -> ObservableTunnel.RecoveryStatus.DROPPED_ATTEMPT_LIMIT
            activation.backendEntries >= RECOVERY_ENTRY_LIMIT -> ObservableTunnel.RecoveryStatus.DROPPED_ENTRY_LIMIT
            activation.lastChargedAt?.let { now - it < RECOVERY_SPACING_MILLIS } == true -> ObservableTunnel.RecoveryStatus.DROPPED_COOLDOWN
            else -> null
        }
        if (status != null) {
            activation.status = status
            publishRecovery(activation)
        }
        status == null
    }

    private fun readRecoveryPower(activation: RecoveryActivation): ObservableTunnel.RecoveryPowerState {
        val power = context.getSystemService(PowerManager::class.java)
        activation.powerState = when {
            power == null -> ObservableTunnel.RecoveryPowerState.UNKNOWN
            power.isDeviceIdleMode -> ObservableTunnel.RecoveryPowerState.DEEP_IDLE
            else -> ObservableTunnel.RecoveryPowerState.NOT_DEEP_IDLE
        }
        return activation.powerState
    }

    // Both dispatch boundaries can copy defects; preserve native identity and propagate cancellation after owned settlement.
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private suspend fun enterRecovery(request: RecoveryRequest): RecoveryResult? {
        val activation = request.activation
        val entry = RecoveryEntry(SystemClock.elapsedRealtime())
        activation.chargedAttempts++
        activation.lastRequestPeer = request.observation.peer()
        activation.lastRequestAt = entry.requestedAt
        activation.lastOutcome = null
        activation.lastResultRecordedAt = null
        activation.failureCategory = null
        activation.status = ObservableTunnel.RecoveryStatus.REQUESTED
        publishRecovery(activation)
        val child = applicationScope.async(Dispatchers.IO) {
            try {
                runInterruptible {
                    entry.enteredAt = SystemClock.elapsedRealtime()
                    entry.entered = true
                    try {
                        entry.result = activation.backend.recover(activation.tunnel, request.observation)
                    } catch (failure: Throwable) {
                        entry.failure = failure
                        throw failure
                    }
                }
            } catch (failure: IOException) {
                if (entry.failure == null) entry.failure = failure
            } catch (failure: BackendException) {
                if (entry.failure == null) entry.failure = failure
                if (failure.reason != BackendException.Reason.UNABLE_TO_START_VPN) throw failure
            } catch (failure: Throwable) {
                if (entry.failure == null) entry.failure = failure
                throw failure
            }
        }
        request.child = child
        var waitingFailure: Throwable? = null
        try {
            child.await()
        } catch (failure: Throwable) {
            waitingFailure = failure
        } finally {
            withContext(NonCancellable) {
                child.cancel()
                child.join()
                withContext(Dispatchers.Main.immediate) {
                    settleRecovery(activation, entry)
                    request.child = null
                }
            }
        }
        val original = entry.failure
        if (original != null && recoveryFailureCategory(original) == ObservableTunnel.RecoveryFailureCategory.DEFECT)
            throw original
        if (waitingFailure != null) throw waitingFailure
        return entry.result
    }

    private fun settleRecovery(activation: RecoveryActivation, entry: RecoveryEntry) {
        val result = entry.result
        if (entry.entered) activation.backendEntries++
        if (!entry.entered || result?.admitted() == false) {
            activation.chargedAttempts--
        } else {
            activation.lastChargedAt = checkNotNull(entry.enteredAt)
            if (result != null) activation.confirmedAdmissions++
            else {
                activation.unknownSettlements++
                activation.disabled = true
                activation.transportState = ObservableTunnel.RecoveryTransportState.UNKNOWN
            }
        }
        activation.lastRequestAt = entry.enteredAt ?: entry.requestedAt
        activation.lastOutcome = result?.outcome()
        activation.lastResultRecordedAt = SystemClock.elapsedRealtime()
        activation.failureCategory = recoveryFailureCategory(entry.failure)
        activation.status = when {
            result != null -> ObservableTunnel.RecoveryStatus.COMPLETED
            entry.failure is CancellationException || entry.failure is InterruptedException -> ObservableTunnel.RecoveryStatus.CANCELLED
            entry.failure != null -> ObservableTunnel.RecoveryStatus.FAILED
            else -> ObservableTunnel.RecoveryStatus.CANCELLED
        }
        if (result != null) {
            if (!result.contextReusable() || !result.currentActivation()) activation.disabled = true
            if (result.outcome() == RecoveryResult.Outcome.TRANSPORT_FAILED)
                activation.transportState = ObservableTunnel.RecoveryTransportState.UNAVAILABLE
            else if (result.transportReady())
                activation.transportState = ObservableTunnel.RecoveryTransportState.READY
            else if (!result.contextReusable() && activation.transportState != ObservableTunnel.RecoveryTransportState.UNAVAILABLE)
                activation.transportState = ObservableTunnel.RecoveryTransportState.UNKNOWN
        }
        publishRecovery(activation)
    }

    private fun recoveryFailureCategory(failure: Throwable?): ObservableTunnel.RecoveryFailureCategory? = when (failure) {
        null -> null
        is CancellationException -> ObservableTunnel.RecoveryFailureCategory.CANCELLED
        is InterruptedException -> ObservableTunnel.RecoveryFailureCategory.INTERRUPTED
        is IOException -> ObservableTunnel.RecoveryFailureCategory.IO
        is BackendException -> if (failure.reason == BackendException.Reason.UNABLE_TO_START_VPN)
            ObservableTunnel.RecoveryFailureCategory.SERVICE_UNAVAILABLE else ObservableTunnel.RecoveryFailureCategory.DEFECT
        else -> ObservableTunnel.RecoveryFailureCategory.DEFECT
    }

    private fun publishRecovery(activation: RecoveryActivation) {
        if (recoveryActivation !== activation || activation.tunnel.activeHandshakeSession !== activation.session) return
        activation.tunnel.onRecoverySnapshotChanged(activation.session, ObservableTunnel.RecoverySnapshot(
            activation.confirmedAdmissions, activation.chargedAttempts, activation.backendEntries, activation.unknownSettlements,
            activation.request != null, activation.disabled, activation.lastTriggerPeer, activation.lastRequestPeer,
            activation.lastRequestAt, activation.lastOutcome,
            activation.status, activation.failureCategory, activation.transportState, activation.powerState,
            activation.lastResultRecordedAt,
        ))
    }

    suspend fun getTunnelStatistics(tunnel: ObservableTunnel): Statistics? = withContext(Dispatchers.Main.immediate) {
        requireManaged(tunnel)
        if (backendSwitchPending || tunnel.state != Tunnel.State.UP) return@withContext null
        val request = operationLock.withLock {
            requireManaged(tunnel)
            if (backendSwitchPending || tunnel.state != Tunnel.State.UP) return@withContext null
            val backend = getBackend()
            if (tunnel.statisticsBackend != null && tunnel.statisticsBackend !== backend)
                tunnel.invalidateStatistics()
            val cached = tunnel.peekStatistics()
            if (cached != null && !cached.isStale && tunnel.statisticsBackend === backend)
                return@withContext cached
            var current = statisticsRequests[tunnel]
            if (current != null && current.backend !== backend) {
                tunnel.invalidateStatistics()
                current = null
            }
            if (current != null && current.generation != tunnel.statisticsGeneration) {
                invalidateTunnelStatistics(tunnel)
                current = null
            }
            if (current == null || current.read.isCancelled) {
                current = StatisticsRequest(tunnel.statisticsGeneration, backend, tunnel.name,
                    statisticsScope.async(Dispatchers.IO) {
                        runInterruptible { backend.getStatistics(tunnel) }
                    })
                statisticsRequests[tunnel] = current
            }
            current.readers++
            current
        }
        try {
            val statistics = request.read.await()
            currentCoroutineContext().ensureActive()
            statisticsScope.coroutineContext.ensureActive()
            if (!isCurrentStatisticsRequest(tunnel, request)) return@withContext null
            tunnel.onStatisticsChanged(statistics, request.backend, request.generation)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            statisticsScope.coroutineContext.ensureActive()
            if (e !== request.obsoleteCause && e.cause !== request.obsoleteCause) throw e
            null
        } finally {
            request.readers--
            if (request.readers == 0) {
                if (statisticsRequests[tunnel] === request) statisticsRequests.remove(tunnel)
                request.read.cancel()
            }
        }
    }

    private suspend fun isCurrentStatisticsRequest(tunnel: ObservableTunnel, request: StatisticsRequest): Boolean {
        if (getBackend() !== request.backend) return false
        if (backendSwitchPending || tunnel.state != Tunnel.State.UP) return false
        if (tunnel.statisticsGeneration != request.generation || tunnel.name != request.name) return false
        if (statisticsRequests[tunnel] !== request || tunnelMap[tunnel.name] !== tunnel) return false
        return true
    }

    internal fun invalidateTunnelStatistics(tunnel: ObservableTunnel) {
        val request = statisticsRequests[tunnel] ?: return
        if (request.generation == tunnel.statisticsGeneration) return
        statisticsRequests.remove(tunnel)
        request.read.cancel(request.obsoleteCause)
    }

    companion object {
        private const val TAG = "vpn0wn/TunnelManager"
        private const val RECOVERY_ATTEMPT_LIMIT = 2
        private const val RECOVERY_ENTRY_LIMIT = 4
        private const val RECOVERY_SPACING_MILLIS = 30_000L
        private const val RECOVERY_BUSY_DELAY_MILLIS = 1_000L
    }
}
