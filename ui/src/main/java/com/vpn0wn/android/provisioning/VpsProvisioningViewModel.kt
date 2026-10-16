/*
 * Copyright © 2026 vpn0wn contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.provisioning

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vpn0wn.android.Application as Vpn0wnApplication
import com.vpn0wn.config.InetAddresses
import com.vpn0wn.config.ParseException
import com.vpn0wn.crypto.Key
import com.vpn0wn.crypto.KeyFormatException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VpsProvisioningViewModel internal constructor(
    application: Application,
    private val store: VpsServerStore,
    factory: RemoteShellClientFactory,
    private val nowEpochSeconds: () -> Long = { TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()) },
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, Vpn0wnApplication.getVpsServerStore(), JschRemoteShellClientFactory())

    enum class Step { SSH, DETECT, PLAN, APPLY }
    enum class AuthMode { KEY, PASSWORD }
    enum class SavedAccess { NONE, SAVED, METADATA_ONLY, UNKNOWN, CLEARED }
    enum class Problem { INVALID_INPUT, CONNECTION_FAILED, INVALID_RESPONSE, ACCESS_MISSING, CONNECTION_CHANGED, STORAGE_FAILED, STORAGE_CONFLICT, CANCELLED, INSTALL_FAILED, INSTALL_PENDING }
    enum class Installation { NONE, RUNNING, INSTALLED, OBSERVED, UNCERTAIN }

    data class InstallConfirmation(val plan: VpsPlanResult, val ssh: VpsEndpoint, val fingerprint: String, val server: StoredVpsServer)

    data class Draft(
        val host: String = "",
        val sshPort: String = "22",
        val user: String = "root",
        val fingerprint: String = "",
        val authMode: AuthMode = AuthMode.KEY,
        val privateKey: String = "",
        val passphrase: String = "",
        val password: CharArray = CharArray(0),
        val endpoint: String = "",
        val port: String = "51820",
        val cidr: String = "10.8.0.0/24",
        val dns: String = "1.1.1.1, 1.0.0.1",
    ) {
        override fun toString(): String = "Draft(redacted)"
    }

    data class State(
        val active: Boolean = false,
        val serverId: String? = null,
        val step: Step = Step.SSH,
        val busy: Boolean = false,
        val usingSavedAccess: Boolean = false,
        val savedAccess: SavedAccess = SavedAccess.NONE,
        val detect: VpsDetectResult? = null,
        val plan: VpsPlanResult? = null,
        val problem: Problem? = null,
        val statusSnapshots: Map<String, StatusSnapshot> = emptyMap(),
        val installation: Installation = Installation.NONE,
        val pendingInstall: Boolean = false,
        val recovery: ProvisioningRecovery? = null,
        val cleanupRequired: Boolean = false,
    )

    data class StatusSnapshot(val server: StoredVpsServer, val status: VpsServerStatus, val observedAtEpochSeconds: Long) {
        fun matches(current: StoredVpsServer): Boolean = server == current
    }

    private val client = Vpn0wnProvisioningClient(application.applicationContext, factory)
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    var draft = Draft()
        private set
    private var server: StoredVpsServer? = null
    private var persistedDetection: StoredVpsServer? = null
    private var newServerId = UUID.randomUUID().toString()
    private var verifiedEndpoint: VpsEndpoint? = null
    private var verifiedFingerprint: String? = null
    private var endpointOverridden = false
    private var operation: Job? = null
    private var installConfirmation: InstallConfirmation? = null

    val canInstall: Boolean get() {
        val expected = persistedDetection ?: return false
        return !mutableState.value.busy && mutableState.value.active &&
            mutableState.value.step == Step.APPLY && mutableState.value.detect?.configExists == false &&
            mutableState.value.plan?.willOverwriteConfig == false && !mutableState.value.pendingInstall &&
            mutableState.value.installation == Installation.NONE && server == expected &&
            mutableState.value.serverId == expected.id && verifiedFingerprint != null &&
            verifiedEndpoint == VpsEndpoint(expected.host, expected.sshPort, expected.sshUser)
    }

    fun retainCurrentServers(servers: List<StoredVpsServer>) {
        val current = servers.associateBy { it.id }
        val retained = mutableState.value.statusSnapshots.filter { (id, snapshot) -> current[id]?.let(snapshot::matches) == true }
        if (retained != mutableState.value.statusSnapshots)
            mutableState.value = mutableState.value.copy(statusSnapshots = retained)
    }

    fun syncStatus(savedServer: StoredVpsServer): Job? = operate(requireActive = false) {
        mutableState.value = mutableState.value.copy(serverId = savedServer.id, savedAccess = SavedAccess.NONE)
        val connection = try {
            store.loadConnection(savedServer) ?: run {
                mutableState.value = mutableState.value.copy(problem = Problem.ACCESS_MISSING)
                return@operate
            }
        } catch (_: VpsServerStore.CredentialConflictException) {
            mutableState.value = mutableState.value.copy(problem = Problem.CONNECTION_CHANGED)
            return@operate
        }
        try {
            val status = client.syncStatus(connection)
            val observedAt = nowEpochSeconds()
            currentCoroutineContext().ensureActive()
            val committed = store.updateStatus(savedServer, status, observedAt)
            mutableState.value = mutableState.value.copy(
                statusSnapshots = mutableState.value.statusSnapshots + (savedServer.id to StatusSnapshot(committed, status, observedAt)),
            )
        } finally {
            (connection.credential as? VpsCredential.Password)?.clear()
        }
    }

    fun begin() {
        if (mutableState.value.busy) return
        clearDraft()
        server = null
        newServerId = UUID.randomUUID().toString()
        invalidateVerification()
        mutableState.value = State(active = true, serverId = newServerId, statusSnapshots = mutableState.value.statusSnapshots)
    }

    fun edit(next: Draft) {
        if (mutableState.value.busy) {
            if (next.password !== draft.password) next.password.fill('\u0000')
            return
        }
        val sshChanged = next.host != draft.host || next.sshPort != draft.sshPort || next.user != draft.user ||
            next.fingerprint != draft.fingerprint || next.authMode != draft.authMode || next.privateKey != draft.privateKey ||
            next.passphrase != draft.passphrase || next.password !== draft.password
        if (next.endpoint != draft.endpoint) endpointOverridden = next.endpoint.isNotBlank()
        installConfirmation = null
        if (next.password !== draft.password) draft.password.fill('\u0000')
        draft = when {
            next.authMode == draft.authMode -> next
            next.authMode == AuthMode.KEY -> {
                next.password.fill('\u0000')
                next.copy(password = CharArray(0))
            }
            else -> next.copy(privateKey = "", passphrase = "")
        }
        if (sshChanged) invalidateVerification()
        mutableState.value = mutableState.value.copy(
            usingSavedAccess = mutableState.value.usingSavedAccess && !sshChanged,
            savedAccess = if (sshChanged) SavedAccess.NONE else mutableState.value.savedAccess,
            detect = if (sshChanged) null else mutableState.value.detect,
            plan = null,
            problem = null,
            installation = if (mutableState.value.pendingInstall) Installation.UNCERTAIN else Installation.NONE,
        )
    }

    fun enterCredentials() {
        if (mutableState.value.busy) return
        invalidateVerification()
        mutableState.value = mutableState.value.copy(usingSavedAccess = false, savedAccess = SavedAccess.NONE, detect = null, plan = null, problem = null, step = Step.SSH)
    }

    fun reuse(savedServer: StoredVpsServer): Job? {
        if (mutableState.value.busy) return null
        begin()
        server = savedServer
        draft = draft.copy(host = savedServer.host, sshPort = savedServer.sshPort.toString(), user = savedServer.sshUser)
        mutableState.value = mutableState.value.copy(serverId = savedServer.id, usingSavedAccess = true)
        return detect()
    }

    fun detect(): Job? = operate {
        invalidateVerification()
        mutableState.value = mutableState.value.copy(detect = null, plan = null, step = Step.SSH)
        val connection = connection() ?: return@operate
        try {
            val result = client.detect(connection)
            currentCoroutineContext().ensureActive()
            verifiedEndpoint = connection.endpoint
            verifiedFingerprint = connection.hostKeyFingerprint
            val detectedEndpoint = result.endpoint.ifBlank {
                val host = connection.endpoint.host
                if (':' in host) "[$host]" else host
            }
            draft = draft.copy(
                fingerprint = requireNotNull(connection.hostKeyFingerprint),
                endpoint = if (endpointOverridden) draft.endpoint else detectedEndpoint,
            )
            saveAuthenticated(connection, result)
            val pending = server?.let { store.pendingInstall(it) } != null
            currentCoroutineContext().ensureActive()
            if (mutableState.value.active) mutableState.value = mutableState.value.copy(
                detect = result, plan = null, step = Step.DETECT, pendingInstall = pending,
                installation = if (pending) Installation.UNCERTAIN else Installation.NONE,
            )
        } finally {
            (connection.credential as? VpsCredential.Password)?.clear()
        }
    }

    fun plan(): Job? {
        if (mutableState.value.detect?.supported != true || verifiedEndpoint == null) return null
        return operate {
            mutableState.value = mutableState.value.copy(plan = null, step = Step.DETECT)
            val connection = connection() ?: return@operate
            try {
                if (connection.endpoint != verifiedEndpoint || connection.hostKeyFingerprint != verifiedFingerprint) {
                    invalidateVerification()
                    mutableState.value = mutableState.value.copy(detect = null, plan = null, step = Step.SSH, problem = Problem.CONNECTION_CHANGED)
                    return@operate
                }
                val request = installRequest()
                val result = client.plan(connection, request)
                if (result.endpoint != request.endpoint || result.port != request.port.toString() || result.cidr != request.cidr || result.dns != request.dns)
                    throw ProvisioningException("Plan does not match the requested inputs", category = ProvisioningFailureCategory.INVALID_RESPONSE)
                currentCoroutineContext().ensureActive()
                if (mutableState.value.active) mutableState.value = mutableState.value.copy(plan = result, step = Step.PLAN)
            } finally {
                (connection.credential as? VpsCredential.Password)?.clear()
            }
        }
    }

    fun reviewApply() {
        if (!mutableState.value.busy && mutableState.value.plan != null)
            mutableState.value = mutableState.value.copy(step = Step.APPLY)
    }

    fun requestInstallConfirmation(): InstallConfirmation? {
        if (!canInstall) return null
        return InstallConfirmation(requireNotNull(mutableState.value.plan), requireNotNull(verifiedEndpoint),
            requireNotNull(verifiedFingerprint), requireNotNull(persistedDetection))
            .also { installConfirmation = it }
    }

    fun confirmInstall(confirmation: InstallConfirmation): Job? {
        if (!canInstall || installConfirmation !== confirmation || mutableState.value.plan !== confirmation.plan ||
            server != confirmation.server || persistedDetection != confirmation.server) return null
        installConfirmation = null
        return operate {
            val expected = confirmation.server
            val connection = connection() ?: return@operate
            try {
                requireInstallTarget(confirmation, connection)
                val request = installRequest()
                if (!matches(confirmation.plan, request)) throw invalidInput()
                val result = client.install(connection, request) {
                    withContext(Dispatchers.Main.immediate) {
                        currentCoroutineContext().ensureActive()
                        requireInstallTarget(confirmation, connection)
                        // Persist admission before the remote command; cancellation cannot erase an uncertain mutation.
                        withContext(NonCancellable) {
                            admitInstall(expected, connection.endpoint, request)
                            mutableState.value = mutableState.value.copy(pendingInstall = true, installation = Installation.RUNNING, recovery = null)
                        }
                        currentCoroutineContext().ensureActive()
                    }
                }
                mutableState.value = mutableState.value.copy(cleanupRequired = result.cleanupPath.isNotEmpty())
                validateInstall(result, request)
                val status = client.syncStatus(connection)
                validateInstalledStatus(status, request)
                if (status.peers.isNotEmpty()) throw invalidInstallResponse()
                publishInstalledStatus(expected, request, status, Installation.INSTALLED)
            } catch (failure: ProvisioningException) {
                mutableState.value = mutableState.value.copy(recovery = failure.recovery, problem = Problem.INSTALL_FAILED)
                if (!mutableState.value.pendingInstall) throw failure
            } finally {
                if (mutableState.value.pendingInstall)
                    mutableState.value = mutableState.value.copy(installation = Installation.UNCERTAIN)
                (connection.credential as? VpsCredential.Password)?.clear()
            }
        }
    }

    private fun requireInstallTarget(confirmation: InstallConfirmation, connection: VpsConnection) {
        val expected = confirmation.server
        val endpoint = VpsEndpoint(expected.host, expected.sshPort, expected.sshUser)
        if (server != expected || persistedDetection != expected || mutableState.value.plan !== confirmation.plan ||
            connection.endpoint != endpoint || connection.endpoint != confirmation.ssh || connection.endpoint != verifiedEndpoint ||
            connection.hostKeyFingerprint != confirmation.fingerprint || connection.hostKeyFingerprint != verifiedFingerprint)
            throw VpsServerStore.ServerChangedException()
    }

    private suspend fun admitInstall(expected: StoredVpsServer, target: VpsEndpoint, request: VpsInstallRequest) {
        try {
            store.admitInstall(expected, target, request)
        } catch (failure: IOException) {
            mutableState.value = mutableState.value.copy(pendingInstall = true, installation = Installation.UNCERTAIN)
            throw failure
        } catch (failure: SecurityException) {
            mutableState.value = mutableState.value.copy(pendingInstall = true, installation = Installation.UNCERTAIN)
            throw failure
        }
    }

    fun reconcileInstall(): Job? {
        val expected = server ?: return null
        if (!mutableState.value.pendingInstall) return null
        return operate {
            val attempt = store.pendingInstall(expected) ?: throw VpsServerStore.InstallPendingException()
            val connection = connection() ?: return@operate
            try {
                if (connection.endpoint != attempt.ssh) throw VpsServerStore.ServerChangedException()
                val status = try {
                    client.syncStatus(connection)
                } catch (failure: ProvisioningException) {
                    val knownRecovery = mutableState.value.recovery == ProvisioningRecovery.UNCHANGED ||
                        mutableState.value.recovery == ProvisioningRecovery.RESTORED
                    if (failure.category != ProvisioningFailureCategory.NOT_INSTALLED || !knownRecovery) throw failure
                    reconcileAbsentInstall(expected, attempt, connection)
                    return@operate
                }
                validateInstalledStatus(status, attempt.request, requireRunning = false)
                publishInstalledStatus(expected, attempt.request, status, Installation.OBSERVED)
            } finally {
                (connection.credential as? VpsCredential.Password)?.clear()
            }
        }
    }

    private suspend fun reconcileAbsentInstall(expected: StoredVpsServer, attempt: PendingVpsInstall, connection: VpsConnection) {
        val detected = client.detect(connection)
        if (!detected.supported || detected.configExists || detected.interfaceName != DEFAULT_INTERFACE) throw invalidInstallResponse()
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            store.clearFailedInstall(expected, attempt)
            mutableState.value = mutableState.value.copy(
                pendingInstall = false, installation = Installation.NONE, problem = null,
                detect = detected, plan = null, step = Step.DETECT,
            )
        }
    }

    private suspend fun publishInstalledStatus(expected: StoredVpsServer, request: VpsInstallRequest, status: VpsServerStatus, installation: Installation) {
        val attempt = store.pendingInstall(expected) ?: throw VpsServerStore.InstallPendingException()
        if (attempt.request != request) throw VpsServerStore.InstallPendingException()
        val observedAt = nowEpochSeconds()
        currentCoroutineContext().ensureActive()
        // Settle admission, metadata and publication together before propagating cancellation.
        withContext(NonCancellable) {
            val committed = store.settleInstall(expected, attempt, status, observedAt)
            server = committed
            mutableState.value = mutableState.value.copy(
                pendingInstall = false, installation = installation, problem = null,
                statusSnapshots = mutableState.value.statusSnapshots + (expected.id to StatusSnapshot(committed, status, observedAt)),
            )
        }
    }

    private fun validateInstall(result: VpsInstallResult, request: VpsInstallRequest) {
        if (result.interfaceName != DEFAULT_INTERFACE || result.endpoint != request.endpoint ||
            result.port != request.port.toString() || result.cidr != request.cidr) throw invalidInstallResponse()
        try {
            Key.fromBase64(result.serverPublicKey)
        } catch (_: KeyFormatException) {
            throw invalidInstallResponse()
        }
    }

    private fun validateInstalledStatus(status: VpsServerStatus, request: VpsInstallRequest, requireRunning: Boolean = true) {
        if (status.interfaceName != DEFAULT_INTERFACE || status.endpoint != request.endpoint ||
            status.listenPort != request.port.toString() || status.cidr != request.cidr || status.dns != request.dns ||
            status.running == null || requireRunning && status.running != true) throw invalidInstallResponse()
    }

    private fun matches(plan: VpsPlanResult, request: VpsInstallRequest): Boolean = !request.force &&
        !plan.willOverwriteConfig && plan.endpoint == request.endpoint && plan.port == request.port.toString() &&
        plan.cidr == request.cidr && plan.dns == request.dns

    private fun invalidInstallResponse() = ProvisioningException("Installation state could not be verified", category = ProvisioningFailureCategory.INVALID_RESPONSE)

    fun back() {
        installConfirmation = null
        if (mutableState.value.busy) {
            operation?.cancel()
            return
        }
        when (mutableState.value.step) {
            Step.SSH -> leave()
            Step.DETECT -> mutableState.value = mutableState.value.copy(step = Step.SSH)
            Step.PLAN -> mutableState.value = mutableState.value.copy(step = Step.DETECT)
            Step.APPLY -> mutableState.value = mutableState.value.copy(step = Step.PLAN)
        }
    }

    fun leave() {
        operation?.cancel()
        clearDraft()
        invalidateVerification()
        mutableState.value = mutableState.value.copy(active = false, detect = null, plan = null, usingSavedAccess = false)
    }

    fun cancelOperation() { operation?.cancel() }

    fun clearSavedAccess(savedServer: StoredVpsServer): Job? = operate(requireActive = false) {
        withContext(NonCancellable) {
            mutableState.value = mutableState.value.copy(serverId = savedServer.id, savedAccess = SavedAccess.UNKNOWN)
            try {
                store.clearCredential(savedServer.id)
                if (server?.id == savedServer.id) {
                    clearDraft()
                    invalidateVerification()
                }
                mutableState.value = mutableState.value.copy(
                    savedAccess = SavedAccess.CLEARED, usingSavedAccess = false, detect = null, plan = null, step = Step.SSH,
                )
            } catch (_: IOException) {
                mutableState.value = mutableState.value.copy(problem = Problem.STORAGE_FAILED)
            } catch (_: SecurityException) {
                mutableState.value = mutableState.value.copy(problem = Problem.STORAGE_FAILED)
            }
        }
    }

    private fun operate(requireActive: Boolean = true, action: suspend () -> Unit): Job? {
        if (mutableState.value.busy || requireActive && !mutableState.value.active) return null
        mutableState.value = mutableState.value.copy(busy = true, problem = null)
        return viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(problem = Problem.CANCELLED)
                throw cancelled
            } catch (failure: ProvisioningException) {
                val problem = when (failure.category) {
                    ProvisioningFailureCategory.INVALID_INPUT -> Problem.INVALID_INPUT
                    ProvisioningFailureCategory.INVALID_RESPONSE -> Problem.INVALID_RESPONSE
                    else -> Problem.CONNECTION_FAILED
                }
                mutableState.value = mutableState.value.copy(problem = problem)
            } catch (_: VpsServerStore.ServerChangedException) {
                mutableState.value = mutableState.value.copy(problem = Problem.CONNECTION_CHANGED)
            } catch (_: VpsServerStore.InstallPendingException) {
                mutableState.value = mutableState.value.copy(problem = Problem.INSTALL_PENDING, pendingInstall = true, installation = Installation.UNCERTAIN)
            } catch (_: VpsServerStore.InvalidTransferMetadataException) {
                mutableState.value = mutableState.value.copy(problem = Problem.STORAGE_FAILED)
            } catch (_: IOException) {
                mutableState.value = mutableState.value.copy(problem = Problem.STORAGE_FAILED)
            } catch (_: SecurityException) {
                mutableState.value = mutableState.value.copy(problem = Problem.STORAGE_FAILED)
            } finally {
                operation = null
                mutableState.value = mutableState.value.copy(busy = false)
            }
        }.also { operation = it }
    }

    // Explicit endpoint and selected-auth rejections preserve validation order before secret copying.
    @Suppress("ThrowsCount")
    private suspend fun connection(): VpsConnection? {
        if (mutableState.value.usingSavedAccess) {
            val saved = server ?: return null
            return try {
                store.loadConnection(saved) ?: connectionUnavailable(Problem.ACCESS_MISSING)
            } catch (_: VpsServerStore.CredentialConflictException) {
                connectionUnavailable(Problem.STORAGE_CONFLICT)
            } catch (_: IOException) {
                connectionUnavailable(Problem.STORAGE_FAILED)
            } catch (_: SecurityException) {
                connectionUnavailable(Problem.STORAGE_FAILED)
            } catch (_: VpsServerStore.InvalidTransferMetadataException) {
                connectionUnavailable(Problem.STORAGE_FAILED)
            }
        }
        try {
            InetAddresses.parse(draft.host.trim())
        } catch (_: ParseException) {
            throw invalidInput()
        }
        JschRemoteShellClientFactory.decodeHostFingerprint(draft.fingerprint.trim())
        val port = draft.sshPort.toIntOrNull()?.takeIf { it in 1..MAX_PORT } ?: throw invalidInput()
        if (draft.user.isBlank()) throw invalidInput()
        val credential = when (draft.authMode) {
            AuthMode.PASSWORD -> {
                if (draft.password.isEmpty()) throw invalidInput()
                VpsCredential.Password(draft.password.copyOf())
            }
            AuthMode.KEY -> {
                if (draft.privateKey.isBlank()) throw invalidInput()
                VpsCredential.PrivateKey(draft.privateKey, draft.passphrase.ifEmpty { null })
            }
        }
        return VpsConnection(VpsEndpoint(draft.host.trim(), port, draft.user.trim()), credential, false, draft.fingerprint.trim())
    }

    private fun installRequest(): VpsInstallRequest {
        val port = draft.port.toIntOrNull()?.takeIf { it in 1..MAX_PORT } ?: throw invalidInput()
        if (draft.endpoint.isBlank() || draft.cidr.isBlank() || draft.dns.isBlank()) throw invalidInput()
        return VpsInstallRequest(draft.endpoint.trim(), draft.cidr.trim(), draft.dns.trim(), port)
    }

    private fun connectionUnavailable(problem: Problem): VpsConnection? {
        invalidateVerification()
        mutableState.value = mutableState.value.copy(
            usingSavedAccess = false, problem = problem, step = Step.SSH, detect = null, plan = null,
        )
        return null
    }

    private suspend fun saveAuthenticated(connection: VpsConnection, detected: VpsDetectResult) {
        val reuseSavedAccess = mutableState.value.usingSavedAccess
        val previous = server
        val record = StoredVpsServer(
            id = previous?.id ?: newServerId, name = previous?.name ?: connection.endpoint.host,
            host = connection.endpoint.host, sshPort = connection.endpoint.port, sshUser = connection.endpoint.user,
            endpoint = detected.endpoint, listenPort = detected.listenPort, cidr = detected.cidr,
            dns = previous?.dns.orEmpty(), os = detected.os, version = detected.version,
            provider = previous?.provider.orEmpty(), region = previous?.region.orEmpty(), engine = previous?.engine.orEmpty(),
            lastSyncEpochSeconds = previous?.lastSyncEpochSeconds ?: 0L, running = previous?.running,
        )
        // Once local persistence starts, settle both owners before cancellation reaches the UI.
        withContext(NonCancellable) {
            mutableState.value = mutableState.value.copy(savedAccess = SavedAccess.UNKNOWN)
            try {
                store.upsert(record)
                server = record
                persistedDetection = record
                mutableState.value = mutableState.value.copy(savedAccess = SavedAccess.METADATA_ONLY)
                if (!reuseSavedAccess)
                    store.saveCredential(record, requireNotNull(connection.hostKeyFingerprint), connection.credential)
                mutableState.value = mutableState.value.copy(savedAccess = SavedAccess.SAVED)
            } catch (_: VpsServerStore.CredentialConflictException) {
                mutableState.value = mutableState.value.copy(problem = Problem.STORAGE_CONFLICT)
            } catch (_: IOException) {
                mutableState.value = mutableState.value.copy(problem = Problem.STORAGE_FAILED)
            } catch (_: SecurityException) {
                mutableState.value = mutableState.value.copy(problem = Problem.STORAGE_FAILED)
            } catch (_: VpsServerStore.InvalidTransferMetadataException) {
                mutableState.value = mutableState.value.copy(problem = Problem.STORAGE_FAILED)
            }
        }
    }

    private fun invalidateVerification() {
        installConfirmation = null
        persistedDetection = null
        verifiedEndpoint = null
        verifiedFingerprint = null
    }

    private fun clearDraft() {
        draft.password.fill('\u0000')
        draft = Draft()
        endpointOverridden = false
    }

    override fun onCleared() {
        leave()
        super.onCleared()
    }

    private fun invalidInput() = ProvisioningException("SSH or deployment input is invalid", category = ProvisioningFailureCategory.INVALID_INPUT)

    private companion object {
        const val MAX_PORT = 65_535
        const val DEFAULT_INTERFACE = "wg0"
    }
}
