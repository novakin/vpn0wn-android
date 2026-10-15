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
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
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
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, Vpn0wnApplication.getVpsServerStore(), JschRemoteShellClientFactory())

    enum class Step { SSH, DETECT, PLAN, APPLY }
    enum class AuthMode { KEY, PASSWORD }
    enum class SavedAccess { NONE, SAVED, METADATA_ONLY, UNKNOWN, CLEARED }
    enum class Problem { INVALID_INPUT, CONNECTION_FAILED, INVALID_RESPONSE, ACCESS_MISSING, CONNECTION_CHANGED, STORAGE_FAILED, STORAGE_CONFLICT, CANCELLED }

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
    )

    private val client = Vpn0wnProvisioningClient(application.applicationContext, factory)
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    var draft = Draft()
        private set
    private var server: StoredVpsServer? = null
    private var newServerId = UUID.randomUUID().toString()
    private var verifiedEndpoint: VpsEndpoint? = null
    private var verifiedFingerprint: String? = null
    private var endpointOverridden = false
    private var operation: Job? = null

    fun begin() {
        if (mutableState.value.busy) return
        clearDraft()
        server = null
        newServerId = UUID.randomUUID().toString()
        invalidateVerification()
        mutableState.value = State(active = true, serverId = newServerId)
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
            currentCoroutineContext().ensureActive()
            if (mutableState.value.active) mutableState.value = mutableState.value.copy(detect = result, plan = null, step = Step.DETECT)
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

    fun back() {
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
    }
}
