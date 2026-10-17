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
import com.vpn0wn.config.Attribute
import com.vpn0wn.config.ParseException
import com.vpn0wn.crypto.Key
import com.vpn0wn.crypto.KeyFormatException
import com.vpn0wn.crypto.KeyPair
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
    private val profiles: VpsUserProfileStore = VpsUserProfileStore(application),
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, Vpn0wnApplication.getVpsServerStore(), JschRemoteShellClientFactory(),
        profiles = Vpn0wnApplication.getVpsUserProfileStore())

    enum class Step { SSH, DETECT, PLAN, APPLY }
    enum class AuthMode { KEY, PASSWORD }
    enum class SavedAccess { NONE, SAVED, METADATA_ONLY, UNKNOWN, CLEARED }
    enum class Problem { INVALID_INPUT, CONNECTION_FAILED, INVALID_RESPONSE, ACCESS_MISSING, CONNECTION_CHANGED, STORAGE_FAILED, STORAGE_CONFLICT, CANCELLED, INSTALL_FAILED, INSTALL_PENDING, ADD_PENDING, REMOVE_PENDING, ROTATE_PENDING, PEER_CHANGED, IDENTITY_UNAVAILABLE, PROFILE_UNAVAILABLE, ARCHIVE_UNAVAILABLE, ARCHIVE_FULL }
    enum class Installation { NONE, RUNNING, INSTALLED, OBSERVED, UNCERTAIN }
    enum class UserCreation { NONE, RUNNING, RETAINED, UNSAVED, UNCERTAIN, CREATED }
    enum class UserRemoval { NONE, CHECKING, RUNNING, UNCERTAIN, FAILED, REMOVED, OBSERVED }
    enum class UserRotation { NONE, CHECKING, RUNNING, UNCERTAIN, FAILED, ROTATED, OBSERVED }
    enum class BackupFailure { PASSWORD, INPUT, FORMAT, AUTHENTICATION, SNAPSHOT, CRYPTO }

    data class BackupSummary(val capturedAtUtc: String, val interfaceName: String, val version: Int,
        val revokedCount: Int, val serverPublicKey: String?)

    data class InstallConfirmation(val plan: VpsPlanResult, val ssh: VpsEndpoint, val fingerprint: String, val server: StoredVpsServer)
    data class AddUserConfirmation(val server: StoredVpsServer, val ssh: VpsEndpoint, val fingerprint: String,
        val serverPublicKey: String, val request: VpsAddPeerRequest)
    data class RemoveUserConfirmation(val server: StoredVpsServer, val attempt: PendingVpsRemove)
    data class RotateUserConfirmation(val server: StoredVpsServer, val ssh: VpsEndpoint, val fingerprint: String,
        val serverPublicKey: String, val name: String, val clientPublicKey: String)

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
        val recoveryOwner: StoredVpsServer? = null,
        val cleanupRequired: Boolean = false,
        val cleanupOwner: StoredVpsServer? = null,
        val addConfirmation: AddUserConfirmation? = null,
        val creation: UserCreation = UserCreation.NONE,
        val pendingAdd: Boolean = false,
        val creationOwner: StoredVpsServer? = null,
        val removeConfirmation: RemoveUserConfirmation? = null,
        val removal: UserRemoval = UserRemoval.NONE,
        val pendingRemove: Boolean = false,
        val removalOwner: StoredVpsServer? = null,
        val removalAction: PendingVpsRemove.Action = PendingVpsRemove.Action.REMOVE,
        val removalAttempt: PendingVpsRemove? = null,
        val rotateConfirmation: RotateUserConfirmation? = null,
        val rotation: UserRotation = UserRotation.NONE,
        val pendingRotation: Boolean = false,
        val rotationOwner: StoredVpsServer? = null,
        val rotationAttempt: PendingVpsRotate? = null,
        val backupSummary: BackupSummary? = null,
        val backupFailure: BackupFailure? = null,
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
    private var receivedUser: ReceivedUser? = null
    private var reviewedRotation: ReviewedRotation? = null

    private data class ReviewedRotation(val confirmation: RotateUserConfirmation, val profile: VpsUserProfile) {
        override fun toString(): String = "ReviewedRotation(redacted)"
    }

    private data class ReceivedUser(val confirmation: AddUserConfirmation, val attempt: PendingVpsAdd,
        val result: VpsAddPeerResult, val profile: VpsUserProfile) {
        override fun toString(): String = "ReceivedUser(redacted)"
    }

    val canInstall: Boolean get() {
        val expected = persistedDetection ?: return false
        return !mutableState.value.busy && mutableState.value.active &&
            mutableState.value.step == Step.APPLY && mutableState.value.detect?.configExists == false &&
            mutableState.value.plan?.willOverwriteConfig == false && !mutableState.value.pendingInstall &&
            !mutableState.value.pendingRemove && !mutableState.value.pendingRotation &&
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
        mutableState.value = mutableState.value.copy(serverId = savedServer.id, savedAccess = SavedAccess.NONE,
            pendingAdd = false, creation = UserCreation.NONE, addConfirmation = null, creationOwner = savedServer)
        val pending = store.pendingAdd(savedServer)
        mutableState.value = mutableState.value.copy(pendingAdd = pending != null,
            creation = if (pending == null) UserCreation.NONE else UserCreation.UNCERTAIN)
        val pendingRemoval = store.pendingRemove(savedServer)
        val pendingRotation = store.pendingRotation(savedServer)
        mutableState.value = mutableState.value.copy(removeConfirmation = null, pendingRemove = pendingRemoval != null,
            removal = if (pendingRemoval == null) UserRemoval.NONE else UserRemoval.UNCERTAIN,
            removalAction = pendingRemoval?.takeIf { it.owns(savedServer) }?.action ?: PendingVpsRemove.Action.REMOVE,
            removalAttempt = pendingRemoval?.takeIf { it.owns(savedServer) },
            removalOwner = savedServer.takeIf { pendingRemoval?.owns(it) != false },
            rotateConfirmation = null, pendingRotation = pendingRotation != null,
            rotation = if (pendingRotation == null) UserRotation.NONE else UserRotation.UNCERTAIN,
            rotationAttempt = pendingRotation?.takeIf { it.owns(savedServer) }, rotationOwner = savedServer.takeIf { pendingRotation?.owns(it) != false })
        reviewedRotation = null
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
            val committed = if (pendingRotation == null) store.updateStatus(savedServer, status, observedAt) else savedServer
            mutableState.value = mutableState.value.copy(
                statusSnapshots = mutableState.value.statusSnapshots + (savedServer.id to StatusSnapshot(committed, status, observedAt)),
                creationOwner = committed,
                removalOwner = committed.takeIf { pendingRemoval?.owns(it) != false },
            )
        } finally {
            (connection.credential as? VpsCredential.Password)?.clear()
        }
    }

    fun exportBackup(savedServer: StoredVpsServer, password: CharArray, consume: suspend (ByteArray) -> Unit): Job? {
        if (mutableState.value.busy) return null
        val ownedPassword = password.copyOf()
        val job = operate(requireActive = false, initialState = mutableState.value.copy(serverId = savedServer.id, savedAccess = SavedAccess.NONE)) {
            val observed = mutableState.value.statusSnapshots[savedServer.id]?.takeIf { it.matches(savedServer) }
            val expectedKey = observed?.status?.serverPublicKey
            if (expectedKey == null) {
                mutableState.value = mutableState.value.copy(problem = Problem.IDENTITY_UNAVAILABLE)
                return@operate
            }
            val connection = store.loadConnection(savedServer) ?: run {
                mutableState.value = mutableState.value.copy(problem = Problem.ACCESS_MISSING)
                return@operate
            }
            val fingerprint = requireNotNull(connection.hostKeyFingerprint)
            val snapshot = try {
                store.checkAddOwner(savedServer, fingerprint)
                val status = client.syncStatus(connection)
                if (status.serverPublicKey != expectedKey || status.interfaceName != observed.status.interfaceName)
                    throw invalidBackupResponse()
                store.checkAddOwner(savedServer, fingerprint)
                client.backup(connection).also { backup ->
                    if (backup.interfaceName != status.interfaceName || backupPublicKey(backup) != expectedKey) throw invalidBackupResponse()
                }
            } finally {
                (connection.credential as? VpsCredential.Password)?.clear()
            }
            val archive = VpsBackupArchive().encrypt(snapshot, ownedPassword)
            try {
                store.checkAddOwner(savedServer, fingerprint)
                currentCoroutineContext().ensureActive()
                consume(archive)
            } finally {
                archive.fill(0)
            }
        }
        if (job == null) ownedPassword.fill('\u0000') else job.invokeOnCompletion { ownedPassword.fill('\u0000') }
        return job
    }

    internal fun inspectBackup(document: VpsBackupDocument, password: CharArray): Job? {
        if (mutableState.value.busy) return null
        val owned = password.copyOf()
        val job = operate(requireActive = false, initialState = mutableState.value.copy(backupSummary = null, backupFailure = null)) {
            var archive: ByteArray? = null
            try {
                val bytes = document.read()
                archive = bytes
                currentCoroutineContext().ensureActive()
                val summary = withContext(Dispatchers.Default) {
                    val snapshot = VpsBackupArchive().decrypt(bytes, owned)
                    BackupSummary(snapshot.capturedAtUtc, snapshot.interfaceName, snapshot.snapshotVersion,
                        snapshot.revokedCount, backupPublicKey(snapshot))
                }
                currentCoroutineContext().ensureActive()
                mutableState.value = mutableState.value.copy(backupSummary = summary)
            } catch (failure: VpsBackupArchiveException) {
                val category = when (failure.category) {
                    VpsBackupArchiveException.Category.PASSWORD -> BackupFailure.PASSWORD
                    VpsBackupArchiveException.Category.FORMAT -> BackupFailure.FORMAT
                    VpsBackupArchiveException.Category.AUTHENTICATION -> BackupFailure.AUTHENTICATION
                    VpsBackupArchiveException.Category.SNAPSHOT -> BackupFailure.SNAPSHOT
                    VpsBackupArchiveException.Category.CRYPTO -> BackupFailure.CRYPTO
                }
                mutableState.value = mutableState.value.copy(backupFailure = category)
            } catch (_: IOException) {
                mutableState.value = mutableState.value.copy(backupFailure = BackupFailure.INPUT)
            } catch (_: SecurityException) {
                mutableState.value = mutableState.value.copy(backupFailure = BackupFailure.INPUT)
            } finally {
                archive?.fill(0)
                owned.fill('\u0000')
            }
        }
        if (job == null) owned.fill('\u0000') else job.invokeOnCompletion { owned.fill('\u0000') }
        return job
    }

    fun clearBackupInspection() { mutableState.value = mutableState.value.copy(backupSummary = null, backupFailure = null) }

    private fun backupPublicKey(backup: VpsBackupResult): String? {
        val key = backup.config.lineSequence().mapNotNull { Attribute.parse(it.substringBefore('#').trim()).orElse(null) }
            .singleOrNull { it.key.equals("PrivateKey", ignoreCase = true) }?.value ?: return null
        return try {
            KeyPair(Key.fromBase64(key)).publicKey.toBase64()
        } catch (_: KeyFormatException) {
            null
        }
    }

    private fun invalidBackupResponse() = ProvisioningException(
        "Backup server identity could not be verified", category = ProvisioningFailureCategory.INVALID_RESPONSE,
    )

    fun useUserProfile(savedServer: StoredVpsServer, clientPublicKey: String, consume: suspend (VpsUserProfile) -> Unit): Job? =
        operate(requireActive = false, initialState = mutableState.value.copy(serverId = savedServer.id, savedAccess = SavedAccess.NONE)) {
            val snapshot = mutableState.value.statusSnapshots[savedServer.id]?.takeIf { it.matches(savedServer) }
            val serverKey = snapshot?.status?.serverPublicKey
            val selectedPeer = snapshot?.status?.peers?.singleOrNull { it.publicKey == clientPublicKey }
            if (serverKey == null || selectedPeer == null) {
                mutableState.value = mutableState.value.copy(problem = Problem.PROFILE_UNAVAILABLE)
                return@operate
            }
            store.cleanupRotationProfiles(profiles)
            val connection = store.loadConnection(savedServer) ?: run {
                mutableState.value = mutableState.value.copy(problem = Problem.ACCESS_MISSING)
                return@operate
            }
            val fingerprint = requireNotNull(connection.hostKeyFingerprint)
            val status = try {
                store.checkProfileOwner(savedServer, fingerprint)
                client.syncStatus(connection)
            } finally {
                (connection.credential as? VpsCredential.Password)?.clear()
            }
            store.checkProfileOwner(savedServer, fingerprint)
            val peer = status.peers.singleOrNull { it.publicKey == clientPublicKey && it.name == selectedPeer.name }
            val profile = if (peer != null && status.serverPublicKey == serverKey && status.running != null &&
                status.interfaceName == snapshot.status.interfaceName && status.cidr == snapshot.status.cidr)
                profiles.load(savedServer, serverKey, clientPublicKey) else null
            if (profile == null || !profile.matchesObserved(status, requireNotNull(peer))) {
                mutableState.value = mutableState.value.copy(problem = Problem.PROFILE_UNAVAILABLE)
                return@operate
            }
            store.checkProfileOwner(savedServer, fingerprint)
            currentCoroutineContext().ensureActive()
            consume(profile)
        }

    // Input, pending owners and stale observations have distinct admission failures.
    @Suppress("ThrowsCount")
    fun requestAddUserConfirmation(savedServer: StoredVpsServer, name: String): Job? = operate(
        requireActive = false,
        initialState = mutableState.value.copy(serverId = savedServer.id, addConfirmation = null,
            pendingAdd = false, creation = UserCreation.NONE, creationOwner = savedServer),
    ) {
        receivedUser?.let { retainReceivedUser(it) }
        mutableState.value = mutableState.value.copy(serverId = savedServer.id, addConfirmation = null,
            pendingAdd = false, creation = UserCreation.NONE, creationOwner = savedServer)
        if (!PendingVpsAdd.NAME.matches(name) || savedServer.dns.isBlank()) throw invalidInput()
        val snapshot = mutableState.value.statusSnapshots[savedServer.id]
        val serverKey = snapshot?.takeIf { it.matches(savedServer) }?.status?.serverPublicKey
        if (snapshot == null || serverKey == null) {
            mutableState.value = mutableState.value.copy(problem = Problem.IDENTITY_UNAVAILABLE)
            return@operate
        }
        if (snapshot.status.peers.any { it.name == name }) throw invalidInput()
        if (store.pendingAdd(savedServer) != null) throw VpsServerStore.AddPendingException()
        if (store.pendingInstall(savedServer) != null) throw VpsServerStore.InstallPendingException()
        store.pendingRotation(savedServer)?.let { throw VpsServerStore.RotatePendingException(it.serverId) }
        store.pendingRemove(savedServer)?.let { pending ->
            mutableState.value = mutableState.value.copy(removalOwner = savedServer.takeIf(pending::owns))
            throw VpsServerStore.RemovePendingException()
        }
        val connection = store.loadConnection(savedServer) ?: run {
            mutableState.value = mutableState.value.copy(problem = Problem.ACCESS_MISSING)
            return@operate
        }
        try {
            currentCoroutineContext().ensureActive()
            if (mutableState.value.statusSnapshots[savedServer.id] !== snapshot) throw VpsServerStore.ServerChangedException()
            mutableState.value = mutableState.value.copy(addConfirmation = AddUserConfirmation(
                savedServer, connection.endpoint, requireNotNull(connection.hostKeyFingerprint), serverKey,
                VpsAddPeerRequest(name, PendingVpsAdd.DEFAULT_ROUTES, savedServer.dns),
            ))
        } finally {
            (connection.credential as? VpsCredential.Password)?.clear()
        }
    }

    fun dismissAddUserConfirmation() {
        mutableState.value = mutableState.value.copy(addConfirmation = null)
    }

    fun confirmAddUser(confirmation: AddUserConfirmation): Job? {
        if (mutableState.value.busy || mutableState.value.addConfirmation !== confirmation) return null
        mutableState.value = mutableState.value.copy(addConfirmation = null)
        return operate(requireActive = false) {
            requireAddSnapshot(confirmation)
            val connection = store.loadConnection(confirmation.server) ?: run {
                mutableState.value = mutableState.value.copy(problem = Problem.ACCESS_MISSING)
                return@operate
            }
            var attempt: PendingVpsAdd? = null
            var retainedProfile: VpsUserProfile? = null
            try {
                requireAddConnection(confirmation, connection)
                val result = client.addPeer(connection, confirmation.request, confirmation.serverPublicKey,
                    onAdmitted = {
                        withContext(Dispatchers.Main.immediate) {
                            currentCoroutineContext().ensureActive()
                            requireAddSnapshot(confirmation)
                            requireAddConnection(confirmation, connection)
                            withContext(NonCancellable) {
                                attempt = store.admitAdd(confirmation.server, confirmation.fingerprint,
                                    confirmation.serverPublicKey, confirmation.request)
                                mutableState.value = mutableState.value.copy(pendingAdd = true, creation = UserCreation.RUNNING,
                                    creationOwner = confirmation.server)
                            }
                            currentCoroutineContext().ensureActive()
                        }
                    },
                    onReceived = { receipt ->
                        val profile = try {
                            VpsUserProfile.generated(confirmation.server, confirmation.serverPublicKey, confirmation.request, receipt)
                        } catch (_: VpsUserProfileException) {
                            throw invalidAddResponse()
                        }
                        retainedProfile = profile
                        val received = ReceivedUser(confirmation, requireNotNull(attempt), receipt, profile)
                        withContext(Dispatchers.Main.immediate) {
                            receivedUser = received
                            mutableState.value = mutableState.value.copy(creation = UserCreation.UNSAVED,
                                cleanupRequired = receipt.cleanupPath.isNotEmpty(), cleanupOwner = confirmation.server, creationOwner = null)
                        }
                        val owner = currentAddOwner(confirmation)
                        withContext(Dispatchers.Main.immediate) {
                            mutableState.value = mutableState.value.copy(creationOwner = owner)
                        }
                        attempt = retainReceivedUser(received)
                    },
                )
                mutableState.value = mutableState.value.copy(cleanupRequired = result.cleanupPath.isNotEmpty(), cleanupOwner = confirmation.server)
                val retained = requireNotNull(retainedProfile)
                store.checkAddOwner(confirmation.server, confirmation.fingerprint)
                val status = client.syncStatus(connection)
                validateCreatedUser(requireNotNull(attempt), retained, status)
                publishCreatedUser(confirmation.server, requireNotNull(attempt), status)
            } catch (failure: ProvisioningException) {
                val admitted = attempt
                if (admitted != null && retainedProfile == null &&
                    failure.remoteCode in REJECTED_ADD_CODES) {
                    withContext(NonCancellable) {
                        store.clearRejectedAdd(confirmation.server, admitted)
                        mutableState.value = mutableState.value.copy(pendingAdd = false, creation = UserCreation.NONE)
                    }
                }
                throw failure
            } finally {
                if (mutableState.value.pendingAdd && mutableState.value.creation == UserCreation.RUNNING)
                    mutableState.value = mutableState.value.copy(creation = UserCreation.UNCERTAIN)
                (connection.credential as? VpsCredential.Password)?.clear()
            }
        }
    }

    // A missing intent, changed access and a different observed key cannot share a retry path.
    @Suppress("ThrowsCount")
    fun reconcileAddUser(savedServer: StoredVpsServer): Job? = operate(requireActive = false) {
        mutableState.value = mutableState.value.copy(serverId = savedServer.id, addConfirmation = null, creationOwner = savedServer)
        var attempt = store.pendingAdd(savedServer) ?: throw VpsServerStore.AddPendingException()
        mutableState.value = mutableState.value.copy(pendingAdd = true, creation = UserCreation.UNCERTAIN)
        val receipt = receivedUser?.takeIf { it.confirmation.server.id == attempt.serverId &&
            it.confirmation.ssh == attempt.ssh && it.confirmation.fingerprint == attempt.fingerprint &&
            it.confirmation.serverPublicKey == attempt.serverPublicKey && it.confirmation.request == attempt.request }
        if (receipt != null) {
            attempt = retainReceivedUser(receipt)
        }
        val connection = store.loadConnection(savedServer) ?: run {
            mutableState.value = mutableState.value.copy(problem = Problem.ACCESS_MISSING)
            return@operate
        }
        try {
            if (connection.endpoint != attempt.ssh || connection.hostKeyFingerprint != attempt.fingerprint ||
                savedServer.id != attempt.serverId) throw VpsServerStore.CredentialConflictException()
            store.checkAddOwner(savedServer, attempt.fingerprint)
            val status = client.syncStatus(connection)
            if (status.serverPublicKey != attempt.serverPublicKey) throw invalidAddResponse()
            val peer = status.peers.singleOrNull { it.name == attempt.request.name }
            val clientKey = attempt.clientPublicKey ?: peer?.publicKey
            val profile = clientKey?.let { profiles.load(savedServer, attempt.serverPublicKey, it) }
            if (profile == null || profile.name != attempt.request.name) {
                mutableState.value = mutableState.value.copy(problem = Problem.ADD_PENDING, creation = UserCreation.UNCERTAIN)
                return@operate
            }
            validateCreatedUser(attempt.copy(clientPublicKey = clientKey), profile, status)
            withContext(NonCancellable) { attempt = store.recordAddReceipt(attempt, requireNotNull(clientKey)) }
            publishCreatedUser(savedServer, attempt, status)
        } finally {
            (connection.credential as? VpsCredential.Password)?.clear()
        }
    }

    private fun requireAddSnapshot(confirmation: AddUserConfirmation) {
        val snapshot = mutableState.value.statusSnapshots[confirmation.server.id] ?: throw VpsServerStore.ServerChangedException()
        if (mutableState.value.serverId != confirmation.server.id || !snapshot.matches(confirmation.server) ||
            snapshot.status.serverPublicKey != confirmation.serverPublicKey ||
            snapshot.status.peers.any { it.name == confirmation.request.name }) throw VpsServerStore.ServerChangedException()
    }

    private suspend fun retainReceivedUser(receipt: ReceivedUser): PendingVpsAdd = withContext(NonCancellable) {
        val confirmation = receipt.confirmation
        profiles.saveGenerated(confirmation.server, confirmation.serverPublicKey, confirmation.request, receipt.result)
        withContext(Dispatchers.Main.immediate) {
            if (receivedUser === receipt) receivedUser = null
            mutableState.value = mutableState.value.copy(creation = UserCreation.RETAINED, creationOwner = null)
        }
        val retained = store.recordAddReceipt(receipt.attempt, receipt.profile.identity.clientPublicKey)
        val owner = currentAddOwner(confirmation)
        withContext(Dispatchers.Main.immediate) {
            mutableState.value = mutableState.value.copy(creationOwner = owner)
        }
        retained
    }

    // Unverified access suppresses presentation without discarding a received private profile.
    private suspend fun currentAddOwner(confirmation: AddUserConfirmation): StoredVpsServer? = try {
        store.checkAddOwner(confirmation.server, confirmation.fingerprint)
        confirmation.server
    } catch (_: VpsServerStore.ServerChangedException) {
        null
    } catch (_: VpsServerStore.CredentialConflictException) {
        null
    } catch (_: VpsServerStore.InvalidTransferMetadataException) {
        null
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun requireAddConnection(confirmation: AddUserConfirmation, connection: VpsConnection) {
        if (connection.endpoint != confirmation.ssh || connection.hostKeyFingerprint != confirmation.fingerprint)
            throw VpsServerStore.CredentialConflictException()
    }

    private fun validateCreatedUser(attempt: PendingVpsAdd, profile: VpsUserProfile, status: VpsServerStatus) {
        val peer = status.peers.singleOrNull { it.name == attempt.request.name }
        val address = profile.config.`interface`.addresses.singleOrNull()?.toString()
        val endpoint = profile.config.peers.single().endpoint.get().toString()
        if (status.serverPublicKey != attempt.serverPublicKey || status.running == null ||
            profile.identity.clientPublicKey != attempt.clientPublicKey || profile.name != attempt.request.name ||
            peer == null || peer.publicKey != profile.identity.clientPublicKey || peer.allowedIps != address ||
            endpoint != "${status.endpoint}:${status.listenPort}" || status.dns != attempt.request.dns)
            throw invalidAddResponse()
    }

    private suspend fun publishCreatedUser(expected: StoredVpsServer, attempt: PendingVpsAdd, status: VpsServerStatus) {
        val observedAt = nowEpochSeconds()
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            val committed = store.settleAdd(expected, attempt, status, observedAt)
            mutableState.value = mutableState.value.copy(pendingAdd = false, creation = UserCreation.CREATED, problem = null,
                statusSnapshots = mutableState.value.statusSnapshots + (expected.id to StatusSnapshot(committed, status, observedAt)),
                creationOwner = committed)
            receivedUser = null
        }
    }

    private fun invalidAddResponse() = ProvisioningException(
        "Created user could not be verified", category = ProvisioningFailureCategory.INVALID_RESPONSE,
    )

    fun requestRemoveUserConfirmation(savedServer: StoredVpsServer, name: String, clientPublicKey: String): Job? =
        requestPeerRemovalConfirmation(savedServer, name, clientPublicKey, PendingVpsRemove.Action.REMOVE)

    fun requestRevokeUserConfirmation(savedServer: StoredVpsServer, name: String, clientPublicKey: String): Job? =
        requestPeerRemovalConfirmation(savedServer, name, clientPublicKey, PendingVpsRemove.Action.REVOKE)

    // Existing pending writers have distinct admission outcomes before a destructive confirmation.
    @Suppress("ThrowsCount")
    private fun requestPeerRemovalConfirmation(savedServer: StoredVpsServer, name: String, clientPublicKey: String, action: PendingVpsRemove.Action): Job? = operate(
        requireActive = false,
        initialState = mutableState.value.copy(serverId = savedServer.id, removeConfirmation = null,
            removal = UserRemoval.CHECKING, pendingRemove = false, removalOwner = savedServer, removalAction = action, removalAttempt = null),
    ) {
        store.cleanupRotationProfiles(profiles)
        store.pendingRotation(savedServer)?.let { throw VpsServerStore.RotatePendingException(it.serverId) }
        val snapshot = mutableState.value.statusSnapshots[savedServer.id]?.takeIf { it.matches(savedServer) }
        val serverKey = snapshot?.status?.serverPublicKey
        if (!PendingVpsAdd.NAME.matches(name) || snapshot == null || serverKey == null ||
            snapshot.status.peers.singleOrNull { it.name == name }?.publicKey != clientPublicKey) {
            mutableState.value = mutableState.value.copy(problem = Problem.PEER_CHANGED)
            return@operate
        }
        try {
            if (Key.fromBase64(serverKey).toBase64() != serverKey || Key.fromBase64(clientPublicKey).toBase64() != clientPublicKey) {
                mutableState.value = mutableState.value.copy(problem = Problem.PEER_CHANGED)
                return@operate
            }
        } catch (_: KeyFormatException) {
            mutableState.value = mutableState.value.copy(problem = Problem.PEER_CHANGED)
            return@operate
        }
        store.pendingRemove(savedServer)?.let { pending ->
            mutableState.value = mutableState.value.copy(removalOwner = savedServer.takeIf(pending::owns),
                removalAction = if (pending.owns(savedServer)) pending.action else action, removalAttempt = pending.takeIf { it.owns(savedServer) })
            throw VpsServerStore.RemovePendingException()
        }
        if (store.pendingAdd(savedServer) != null) throw VpsServerStore.AddPendingException()
        if (store.pendingInstall(savedServer) != null) throw VpsServerStore.InstallPendingException()
        val connection = store.loadConnection(savedServer) ?: run {
            mutableState.value = mutableState.value.copy(problem = Problem.ACCESS_MISSING)
            return@operate
        }
        try {
            val attempt = PendingVpsRemove(savedServer.id, connection.endpoint, requireNotNull(connection.hostKeyFingerprint), serverKey, name, clientPublicKey,
                action, if (action == PendingVpsRemove.Action.REVOKE) UUID.randomUUID().toString() else null)
            attempt.validate()
            store.checkAddOwner(savedServer, attempt.fingerprint)
            val status = client.syncStatus(connection)
            store.checkAddOwner(savedServer, attempt.fingerprint)
            if (status.interfaceName != DEFAULT_INTERFACE || status.serverPublicKey != serverKey || status.running == null ||
                status.peers.singleOrNull { it.name == name }?.publicKey != clientPublicKey) {
                mutableState.value = mutableState.value.copy(problem = Problem.PEER_CHANGED)
                return@operate
            }
            if (action == PendingVpsRemove.Action.REVOKE && status.revokedArchives == null) {
                mutableState.value = mutableState.value.copy(problem = Problem.ARCHIVE_UNAVAILABLE)
                return@operate
            }
            if (action == PendingVpsRemove.Action.REVOKE && requireNotNull(status.revokedArchives).size >= VpsBackupResult.MAX_REVOKED_FILES) {
                mutableState.value = mutableState.value.copy(problem = Problem.ARCHIVE_FULL)
                return@operate
            }
            currentCoroutineContext().ensureActive()
            mutableState.value = mutableState.value.copy(removeConfirmation = RemoveUserConfirmation(savedServer, attempt),
                statusSnapshots = mutableState.value.statusSnapshots + (savedServer.id to StatusSnapshot(savedServer, status, nowEpochSeconds())))
        } finally {
            (connection.credential as? VpsCredential.Password)?.clear()
        }
    }

    fun dismissRemoveUserConfirmation() { mutableState.value = mutableState.value.copy(removeConfirmation = null) }

    // Admission, typed rollback and post-receipt failures require distinct settlement paths.
    @Suppress("ThrowsCount")
    fun confirmRemoveUser(confirmation: RemoveUserConfirmation): Job? {
        if (mutableState.value.busy || mutableState.value.removeConfirmation !== confirmation) return null
        mutableState.value = mutableState.value.copy(removeConfirmation = null)
        return operate(requireActive = false) {
            val expected = confirmation.server
            val attempt = confirmation.attempt
            val connection = store.loadConnection(expected) ?: run {
                mutableState.value = mutableState.value.copy(problem = Problem.ACCESS_MISSING)
                return@operate
            }
            var admitted = false
            var receiptReceived = false
            try {
                requireRemoveConnection(attempt, connection)
                val onAdmitted: suspend () -> Unit = {
                    withContext(Dispatchers.Main.immediate) {
                        currentCoroutineContext().ensureActive()
                        val snapshot = mutableState.value.statusSnapshots[expected.id]
                        if (mutableState.value.serverId != expected.id || snapshot?.matches(expected) != true ||
                            snapshot.status.serverPublicKey != attempt.serverPublicKey ||
                            snapshot.status.peers.singleOrNull { it.name == attempt.name }?.publicKey != attempt.clientPublicKey)
                            throw VpsServerStore.ServerChangedException()
                        withContext(NonCancellable) {
                            store.admitRemove(expected, attempt)
                            admitted = true
                            mutableState.value = mutableState.value.copy(pendingRemove = true, removal = UserRemoval.RUNNING,
                                removalOwner = expected, removalAction = attempt.action, removalAttempt = attempt)
                        }
                        currentCoroutineContext().ensureActive()
                    }
                }
                val result = try {
                    when (attempt.action) {
                        PendingVpsRemove.Action.REMOVE -> client.removePeer(connection, attempt.name, attempt.clientPublicKey,
                            attempt.serverPublicKey, onAdmitted, onReceived = { receiptReceived = true })
                        PendingVpsRemove.Action.REVOKE -> client.revokePeer(connection, attempt.name, attempt.clientPublicKey,
                            attempt.serverPublicKey, requireNotNull(attempt.operationId), onAdmitted, onReceived = { receiptReceived = true })
                    }
                } catch (failure: ProvisioningException) {
                    val rollback = failure.recovery == ProvisioningRecovery.UNCHANGED || failure.recovery == ProvisioningRecovery.RESTORED
                    val rejectedRevoke = attempt.action == PendingVpsRemove.Action.REVOKE && failure.recovery == null &&
                        failure.remoteCode in REJECTED_REVOKE_CODES
                    if (!admitted || receiptReceived || !rollback && !rejectedRevoke) throw failure
                    withContext(NonCancellable) {
                        store.clearRejectedRemove(expected, attempt)
                        mutableState.value = mutableState.value.copy(pendingRemove = false, removal = UserRemoval.FAILED,
                            removalAttempt = null, recovery = failure.recovery, recoveryOwner = expected,
                            statusSnapshots = mutableState.value.statusSnapshots - expected.id)
                    }
                    return@operate
                }
                mutableState.value = mutableState.value.copy(cleanupRequired = result.cleanupPath.isNotEmpty(), cleanupOwner = expected)
                store.checkAddOwner(expected, attempt.fingerprint)
                publishRemovedUser(expected, attempt, client.syncStatus(connection), UserRemoval.REMOVED)
            } catch (failure: ProvisioningException) {
                if (admitted && !receiptReceived && failure.recovery == null && failure.remoteCode in REJECTED_REMOVE_CODES) {
                    withContext(NonCancellable) {
                        store.clearRejectedRemove(expected, attempt)
                        mutableState.value = mutableState.value.copy(pendingRemove = false, removal = UserRemoval.NONE,
                            removalAttempt = null, problem = Problem.PEER_CHANGED, statusSnapshots = mutableState.value.statusSnapshots - expected.id)
                    }
                    return@operate
                }
                mutableState.value = mutableState.value.copy(recovery = failure.recovery.takeIf { !receiptReceived },
                    recoveryOwner = expected.takeIf { !receiptReceived })
                throw failure
            } finally {
                if (mutableState.value.pendingRemove && mutableState.value.removal == UserRemoval.RUNNING)
                    mutableState.value = mutableState.value.copy(removal = UserRemoval.UNCERTAIN)
                (connection.credential as? VpsCredential.Password)?.clear()
            }
        }
    }

    fun reconcileRemoveUser(savedServer: StoredVpsServer): Job? = operate(requireActive = false,
        initialState = mutableState.value.copy(serverId = savedServer.id, removeConfirmation = null, removalOwner = savedServer,
            pendingRemove = false, removal = UserRemoval.CHECKING, removalAction = PendingVpsRemove.Action.REMOVE, removalAttempt = null),
    ) {
        val attempt = store.pendingRemove(savedServer) ?: return@operate
        mutableState.value = mutableState.value.copy(pendingRemove = true, removal = UserRemoval.UNCERTAIN)
        if (!attempt.owns(savedServer)) {
            mutableState.value = mutableState.value.copy(removalOwner = null)
            throw VpsServerStore.CredentialConflictException()
        }
        mutableState.value = mutableState.value.copy(removalAction = attempt.action, removalAttempt = attempt)
        val connection = store.loadConnection(savedServer) ?: run {
            mutableState.value = mutableState.value.copy(problem = Problem.ACCESS_MISSING)
            return@operate
        }
        try {
            requireRemoveConnection(attempt, connection)
            store.checkAddOwner(savedServer, attempt.fingerprint)
            publishRemovedUser(savedServer, attempt, client.syncStatus(connection), UserRemoval.OBSERVED)
        } finally {
            (connection.credential as? VpsCredential.Password)?.clear()
        }
    }

    private fun requireRemoveConnection(attempt: PendingVpsRemove, connection: VpsConnection) {
        if (connection.endpoint != attempt.ssh || connection.hostKeyFingerprint != attempt.fingerprint)
            throw VpsServerStore.CredentialConflictException()
    }

    private suspend fun publishRemovedUser(expected: StoredVpsServer, attempt: PendingVpsRemove, status: VpsServerStatus, removal: UserRemoval) {
        if (!attempt.observesCompletion(status)) throw VpsServerStore.RemovePendingException()
        store.checkAddOwner(expected, attempt.fingerprint)
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            val observedAt = nowEpochSeconds()
            val committed = store.settleRemove(expected, attempt, status, observedAt) {
                profiles.load(expected, attempt.serverPublicKey, attempt.clientPublicKey)?.let { profiles.delete(it) }
            }
            mutableState.value = mutableState.value.copy(pendingRemove = false, removal = removal, problem = null,
                removalAction = attempt.action, removalAttempt = null,
                removalOwner = committed, statusSnapshots = mutableState.value.statusSnapshots + (expected.id to StatusSnapshot(committed, status, observedAt)))
        }
    }

    fun requestRotateUserConfirmation(savedServer: StoredVpsServer, name: String, clientPublicKey: String): Job? = operate(requireActive = false,
        initialState = mutableState.value.copy(serverId = savedServer.id, rotateConfirmation = null, rotation = UserRotation.CHECKING,
            pendingRotation = false, rotationOwner = savedServer, rotationAttempt = null),
    ) {
        reviewedRotation = null
        store.cleanupRotationProfiles(profiles)
        val snapshot = mutableState.value.statusSnapshots[savedServer.id]?.takeIf { it.matches(savedServer) }
        val serverKey = snapshot?.status?.serverPublicKey
        if (snapshot == null || serverKey == null || snapshot.status.peers.singleOrNull { it.name == name }?.publicKey != clientPublicKey) {
            mutableState.value = mutableState.value.copy(problem = Problem.PROFILE_UNAVAILABLE)
            return@operate
        }
        store.pendingRotation(savedServer)?.let { pending ->
            mutableState.value = mutableState.value.copy(rotationOwner = savedServer.takeIf(pending::owns), rotationAttempt = pending.takeIf { it.owns(savedServer) })
            throw VpsServerStore.RotatePendingException(pending.serverId)
        }
        if (store.pendingAdd(savedServer) != null) throw VpsServerStore.AddPendingException()
        if (store.pendingRemove(savedServer) != null) throw VpsServerStore.RemovePendingException()
        if (store.pendingInstall(savedServer) != null) throw VpsServerStore.InstallPendingException()
        val connection = store.loadConnection(savedServer) ?: run {
            mutableState.value = mutableState.value.copy(problem = Problem.ACCESS_MISSING)
            return@operate
        }
        try {
            val fingerprint = requireNotNull(connection.hostKeyFingerprint)
            store.checkProfileOwner(savedServer, fingerprint)
            val status = client.syncStatus(connection)
            store.checkProfileOwner(savedServer, fingerprint)
            val peer = status.peers.singleOrNull { it.name == name && it.publicKey == clientPublicKey }
            val old = profiles.load(savedServer, serverKey, clientPublicKey)
            if (peer == null || old == null || status.interfaceName != DEFAULT_INTERFACE || status.running == null ||
                status.configuredPeerPublicKeys?.count { it == clientPublicKey } != 1 ||
                status.running == true && status.runtimePeerPublicKeys?.count { it == clientPublicKey } != 1 || !old.matchesObserved(status, peer)) {
                mutableState.value = mutableState.value.copy(problem = Problem.PROFILE_UNAVAILABLE)
                return@operate
            }
            currentCoroutineContext().ensureActive()
            val confirmation = RotateUserConfirmation(savedServer, connection.endpoint, fingerprint, serverKey, name, clientPublicKey)
            reviewedRotation = ReviewedRotation(confirmation, old)
            mutableState.value = mutableState.value.copy(rotateConfirmation = confirmation,
                statusSnapshots = mutableState.value.statusSnapshots + (savedServer.id to StatusSnapshot(savedServer, status, nowEpochSeconds())))
        } finally {
            (connection.credential as? VpsCredential.Password)?.clear()
        }
    }

    fun dismissRotateUserConfirmation() {
        reviewedRotation = null
        mutableState.value = mutableState.value.copy(rotateConfirmation = null)
    }

    // Dispatch rejection and cleanup failures must retain distinct provenance, including cancellation and defects.
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    fun confirmRotateUser(confirmation: RotateUserConfirmation): Job? {
        val reviewed = reviewedRotation?.takeIf { it.confirmation === confirmation } ?: return null
        if (mutableState.value.busy || mutableState.value.rotateConfirmation !== confirmation) return null
        dismissRotateUserConfirmation()
        return operate(requireActive = false) {
            val expected = confirmation.server
            requireRotateSnapshot(confirmation)
            val connection = store.loadConnection(expected) ?: run {
                mutableState.value = mutableState.value.copy(problem = Problem.ACCESS_MISSING)
                return@operate
            }
            var attempt: PendingVpsRotate? = null
            var receiptReceived = false
            try {
                if (connection.endpoint != confirmation.ssh || connection.hostKeyFingerprint != confirmation.fingerprint)
                    throw VpsServerStore.CredentialConflictException()
                withContext(NonCancellable) {
                    attempt = store.admitRotation(expected, confirmation.fingerprint, reviewed.profile, profiles)
                    mutableState.value = mutableState.value.copy(pendingRotation = true, rotation = UserRotation.RUNNING,
                        rotationOwner = expected, rotationAttempt = attempt)
                }
                currentCoroutineContext().ensureActive()
                val proposed = profiles.loadRotation(requireNotNull(attempt))
                val result = try {
                    client.rotatePeer(connection, expected, requireNotNull(attempt), proposed,
                        onAdmitted = {
                            withContext(Dispatchers.Main.immediate) {
                                currentCoroutineContext().ensureActive()
                                requireRotateSnapshot(confirmation)
                                withContext(NonCancellable) {
                                    attempt = store.dispatchRotation(expected, requireNotNull(attempt), profiles)
                                    mutableState.value = mutableState.value.copy(rotationAttempt = attempt)
                                }
                                currentCoroutineContext().ensureActive()
                            }
                        }, onReceived = { receiptReceived = true })
                } catch (failure: ProvisioningException) {
                    val dispatched = requireNotNull(attempt)
                    val rollback = failure.recovery == ProvisioningRecovery.UNCHANGED || failure.recovery == ProvisioningRecovery.RESTORED
                    val rejected = failure.recovery == null && failure.remoteCode in REJECTED_ROTATION_CODES
                    if (dispatched.phase != PendingVpsRotate.Phase.DISPATCHED || receiptReceived || !rollback && !rejected) throw failure
                    withContext(NonCancellable) {
                        store.failRotation(expected, dispatched, profiles)
                        publishFailedRotation(expected)
                    }
                    return@operate
                }
                mutableState.value = mutableState.value.copy(cleanupRequired = result.cleanupPath.isNotEmpty(), cleanupOwner = expected)
                val dispatched = requireNotNull(attempt)
                val verification = client.verifyPeerRotation(connection, expected, dispatched, proposed)
                publishRotatedUser(expected, dispatched, verification, UserRotation.ROTATED)
            } catch (failure: Throwable) {
                attempt?.takeIf { it.phase == PendingVpsRotate.Phase.PREPARED }?.let { prepared ->
                    withContext(NonCancellable) {
                        try {
                            store.abandonPreparedRotation(expected, prepared, profiles)
                            publishFailedRotation(expected)
                        } catch (cleanup: Throwable) {
                            if (cleanup !== failure) failure.addSuppressed(cleanup)
                        }
                    }
                }
                throw failure
            } finally {
                if (mutableState.value.pendingRotation && mutableState.value.rotation == UserRotation.RUNNING)
                    mutableState.value = mutableState.value.copy(rotation = UserRotation.UNCERTAIN)
                (connection.credential as? VpsCredential.Password)?.clear()
            }
        }
    }

    fun reconcileUserRotation(savedServer: StoredVpsServer): Job? = operate(requireActive = false,
        initialState = mutableState.value.copy(serverId = savedServer.id, rotateConfirmation = null, rotation = UserRotation.CHECKING,
            pendingRotation = false, rotationOwner = savedServer, rotationAttempt = null),
    ) {
        reviewedRotation = null
        store.cleanupRotationProfiles(profiles)
        val attempt = store.pendingRotation(savedServer) ?: run {
            mutableState.value = mutableState.value.copy(rotation = UserRotation.NONE, rotationOwner = null, rotationAttempt = null,
                statusSnapshots = mutableState.value.statusSnapshots - savedServer.id)
            return@operate
        }
        mutableState.value = mutableState.value.copy(pendingRotation = true, rotation = UserRotation.UNCERTAIN,
            rotationOwner = savedServer.takeIf(attempt::owns), rotationAttempt = attempt.takeIf { it.owns(savedServer) })
        if (!attempt.owns(savedServer)) throw VpsServerStore.CredentialConflictException()
        if (attempt.phase != PendingVpsRotate.Phase.DISPATCHED) {
            withContext(NonCancellable) {
                if (attempt.phase == PendingVpsRotate.Phase.PREPARED) store.abandonPreparedRotation(savedServer, attempt, profiles)
                else store.failRotation(savedServer, attempt, profiles)
                publishFailedRotation(savedServer)
            }
            return@operate
        }
        val proposed = profiles.loadRotation(attempt)
        val connection = store.loadConnection(savedServer) ?: run {
            mutableState.value = mutableState.value.copy(problem = Problem.ACCESS_MISSING)
            return@operate
        }
        try {
            val verification = client.verifyPeerRotation(connection, savedServer, attempt, proposed)
            publishRotatedUser(savedServer, attempt, verification, UserRotation.OBSERVED)
        } finally {
            (connection.credential as? VpsCredential.Password)?.clear()
        }
    }

    private fun requireRotateSnapshot(confirmation: RotateUserConfirmation) {
        val snapshot = mutableState.value.statusSnapshots[confirmation.server.id]
        if (mutableState.value.serverId != confirmation.server.id || snapshot?.matches(confirmation.server) != true ||
            snapshot.status.serverPublicKey != confirmation.serverPublicKey ||
            snapshot.status.peers.singleOrNull { it.name == confirmation.name }?.publicKey != confirmation.clientPublicKey)
            throw VpsServerStore.ServerChangedException()
    }

    private fun publishFailedRotation(expected: StoredVpsServer) {
        mutableState.value = mutableState.value.copy(pendingRotation = false, rotation = UserRotation.FAILED, rotationOwner = expected,
            rotationAttempt = null, statusSnapshots = mutableState.value.statusSnapshots - expected.id)
    }

    private suspend fun publishRotatedUser(expected: StoredVpsServer, attempt: PendingVpsRotate, verification: VpsPeerRotationResult, outcome: UserRotation) {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            val committed = store.settleRotation(expected, attempt, verification, nowEpochSeconds(), profiles)
            mutableState.value = mutableState.value.copy(pendingRotation = false, rotation = outcome, rotationOwner = committed,
                rotationAttempt = null, problem = null, statusSnapshots = mutableState.value.statusSnapshots - expected.id)
        }
    }

    fun begin() {
        if (mutableState.value.busy) return
        clearDraft()
        reviewedRotation = null
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
        mutableState.value = mutableState.value.copy(detect = null, plan = null, step = Step.SSH, recovery = null, recoveryOwner = null)
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
            val pendingRemoval = server?.let { store.pendingRemove(it) }
            currentCoroutineContext().ensureActive()
            if (mutableState.value.active) mutableState.value = mutableState.value.copy(
                detect = result, plan = null, step = Step.DETECT, pendingInstall = pending,
                installation = if (pending) Installation.UNCERTAIN else Installation.NONE,
                pendingRemove = pendingRemoval != null, removal = if (pendingRemoval != null) UserRemoval.UNCERTAIN else UserRemoval.NONE,
                removalAction = pendingRemoval?.takeIf { attempt -> server?.let(attempt::owns) == true }?.action ?: PendingVpsRemove.Action.REMOVE,
                removalAttempt = pendingRemoval?.takeIf { attempt -> server?.let(attempt::owns) == true },
                removalOwner = server?.takeIf { pendingRemoval?.owns(it) != false },
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

    // Input, receipt and pre-admission transport failures retain distinct recovery semantics.
    @Suppress("ThrowsCount")
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
                            mutableState.value = mutableState.value.copy(pendingInstall = true, installation = Installation.RUNNING,
                                recovery = null, recoveryOwner = null)
                        }
                        currentCoroutineContext().ensureActive()
                    }
                }
                mutableState.value = mutableState.value.copy(cleanupRequired = result.cleanupPath.isNotEmpty(), cleanupOwner = expected)
                validateInstall(result, request)
                val status = client.syncStatus(connection)
                validateInstalledStatus(status, request)
                if (status.peers.isNotEmpty()) throw invalidInstallResponse()
                publishInstalledStatus(expected, request, status, Installation.INSTALLED)
            } catch (failure: ProvisioningException) {
                mutableState.value = mutableState.value.copy(recovery = failure.recovery, recoveryOwner = expected, problem = Problem.INSTALL_FAILED)
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

    // Unknown admission, changed target and remote failure must not collapse into a retryable outcome.
    @Suppress("ThrowsCount")
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
                    val recoveryOwner = mutableState.value.recoveryOwner
                    val knownRecovery = recoveryOwner != null && recoveryOwner.id == expected.id &&
                        VpsEndpoint(recoveryOwner.host, recoveryOwner.sshPort, recoveryOwner.sshUser) == attempt.ssh &&
                        (mutableState.value.recovery == ProvisioningRecovery.UNCHANGED ||
                            mutableState.value.recovery == ProvisioningRecovery.RESTORED)
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
                pendingInstall = false, installation = Installation.NONE, problem = null, recovery = null, recoveryOwner = null,
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
                pendingInstall = false, installation = installation, problem = null, recovery = null, recoveryOwner = null,
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
        reviewedRotation = null
        mutableState.value = mutableState.value.copy(active = false, detect = null, plan = null, usingSavedAccess = false,
            addConfirmation = null, removeConfirmation = null, rotateConfirmation = null)
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

    private fun operate(requireActive: Boolean = true, initialState: State = mutableState.value, action: suspend () -> Unit): Job? {
        if (mutableState.value.busy || requireActive && !mutableState.value.active) return null
        mutableState.value = initialState.copy(busy = true, problem = null)
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
                mutableState.value = mutableState.value.copy(problem = Problem.CONNECTION_CHANGED, creationOwner = null)
            } catch (_: VpsServerStore.InstallPendingException) {
                mutableState.value = mutableState.value.copy(problem = Problem.INSTALL_PENDING, pendingInstall = true, installation = Installation.UNCERTAIN)
            } catch (_: VpsServerStore.AddPendingException) {
                mutableState.value = mutableState.value.copy(problem = Problem.ADD_PENDING, pendingAdd = true, creation = UserCreation.UNCERTAIN)
            } catch (failure: VpsServerStore.RemovePendingException) {
                val owner = mutableState.value.removalOwner?.takeIf { failure.ownerServerId == null || it.id == failure.ownerServerId }
                mutableState.value = mutableState.value.copy(problem = Problem.REMOVE_PENDING, pendingRemove = true,
                    removal = UserRemoval.UNCERTAIN, removalOwner = owner)
            } catch (failure: VpsServerStore.RotatePendingException) {
                val owner = mutableState.value.rotationOwner?.takeIf { failure.ownerServerId == null || it.id == failure.ownerServerId }
                mutableState.value = mutableState.value.copy(problem = Problem.ROTATE_PENDING, pendingRotation = true,
                    rotation = UserRotation.UNCERTAIN, rotationOwner = owner, rotationAttempt = mutableState.value.rotationAttempt?.takeIf { owner?.let(it::owns) == true })
            } catch (_: VpsServerStore.CredentialConflictException) {
                mutableState.value = mutableState.value.copy(problem = Problem.CONNECTION_CHANGED, creationOwner = null)
            } catch (_: VpsServerStore.InvalidTransferMetadataException) {
                mutableState.value = mutableState.value.copy(problem = Problem.STORAGE_FAILED)
            } catch (_: IOException) {
                mutableState.value = mutableState.value.copy(problem = Problem.STORAGE_FAILED)
            } catch (_: SecurityException) {
                mutableState.value = mutableState.value.copy(problem = Problem.STORAGE_FAILED)
            } finally {
                operation = null
                mutableState.value = mutableState.value.copy(busy = false,
                    removal = if (mutableState.value.removal == UserRemoval.CHECKING) UserRemoval.NONE else mutableState.value.removal,
                    rotation = if (mutableState.value.rotation == UserRotation.CHECKING) UserRotation.NONE else mutableState.value.rotation)
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
                val pendingRemoval = previous?.let { store.pendingRemove(it) }
                val pendingRotation = previous?.let { store.pendingRotation(it) }
                // Credential refresh must preserve the exact record fenced by a pending removal.
                val recordToSave = if (pendingRemoval == null && pendingRotation == null) record else {
                    val captured = requireNotNull(previous)
                    if (pendingRemoval != null && (!pendingRemoval.owns(captured) || pendingRemoval.ssh != connection.endpoint ||
                        pendingRemoval.fingerprint != connection.hostKeyFingerprint)) throw VpsServerStore.RemovePendingException(pendingRemoval.serverId)
                    if (pendingRotation != null && (!pendingRotation.owns(captured) || pendingRotation.ssh != connection.endpoint ||
                        pendingRotation.fingerprint != connection.hostKeyFingerprint)) throw VpsServerStore.RotatePendingException(pendingRotation.serverId)
                    captured
                }
                store.upsert(recordToSave)
                server = recordToSave
                persistedDetection = recordToSave
                mutableState.value = mutableState.value.copy(savedAccess = SavedAccess.METADATA_ONLY)
                if (!reuseSavedAccess)
                    store.saveCredential(recordToSave, requireNotNull(connection.hostKeyFingerprint), connection.credential)
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
        val REJECTED_REVOKE_CODES = setOf("archive_inventory_unknown", "archive_limit", "operation_id_conflict")
        const val MAX_PORT = 65_535
        const val DEFAULT_INTERFACE = "wg0"
        val REJECTED_ADD_CODES = setOf("peer_exists", "server_identity_mismatch")
        val REJECTED_REMOVE_CODES = setOf("peer_not_found", "peer_identity_mismatch", "server_identity_mismatch")
        val REJECTED_ROTATION_CODES = setOf("peer_not_found", "peer_identity_mismatch", "server_identity_mismatch", "rotation_mismatch",
            "new_peer_exists", "invalid_rotation_input", "invalid_public_key", "invalid_config", "runtime_unknown")
    }
}
