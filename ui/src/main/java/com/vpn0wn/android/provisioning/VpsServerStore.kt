/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.provisioning

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class StoredVpsServer(
    val id: String,
    val name: String,
    val host: String,
    val sshPort: Int,
    val sshUser: String,
    val endpoint: String,
    val listenPort: String,
    val cidr: String,
    val dns: String,
    val os: String = "",
    val version: String = "",
    val provider: String = "",
    val region: String = "",
    val engine: String = "",
    val lastSyncEpochSeconds: Long = 0L,
    val running: Boolean? = null,
)

class VpsServerStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val credentialFile: VpsCredentialFile,
) {
    internal class InvalidTransferMetadataException : IllegalArgumentException("Invalid VPS fleet metadata")
    class CredentialConflictException : IllegalStateException("Saved SSH credentials do not match the current server; remove them before changing SSH settings")
    class ServerChangedException : IllegalStateException("Server changed while status was being read")
    class AddPendingException : IllegalStateException("A user creation needs reconciliation")
    class RemovePendingException(val ownerServerId: String? = null) : IllegalStateException("A user removal needs reconciliation")
    class InstallPendingException : IllegalStateException("An earlier installation needs reconciliation")
    class IncompleteCredentialRecoveryException : IOException("Server change failed; saved SSH credentials could not be restored")
    private val mutationMutex = Mutex()

    val servers: Flow<List<StoredVpsServer>> = dataStore.data.map { preferences ->
        decodeServers(preferences[SERVERS].orEmpty())
    }.distinctUntilChanged()

    suspend fun upsert(server: StoredVpsServer) = mutate {
        decodeTransferServers(encodeServers(listOf(server)))
        val previous = currentServers().singleOrNull { it.id == server.id }
        if (previous != server && pendingRemovals().any { it.matches(server) }) throw RemovePendingException()
        if (previous == null || !sameSshEndpoint(previous, server)) {
            withCredentials { records ->
                if (server.id in records) throw CredentialConflictException()
            }
        }
        dataStore.edit { preferences ->
            val servers = decodeServersForMutation(preferences[SERVERS].orEmpty())
                .filterNot { it.id == server.id } + server
            preferences[SERVERS] = encodeServers(servers)
        }
        Unit
    }

    // Separate commits need recovery without reclassifying cancellation or programming defects.
    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    suspend fun remove(id: String) = mutate {
        if (pendingRemovals().any { it.serverId == id }) throw RemovePendingException()
        val original = currentServers().singleOrNull { it.id == id }
        withCredentials { records ->
            val removed = records.remove(id)
            var credentialRemoved = false
            try {
                if (removed != null) {
                    writeCredentials(records)
                    credentialRemoved = true
                }
                dataStore.edit { preferences ->
                    val servers = decodeServersForMutation(preferences[SERVERS].orEmpty()).filterNot { it.id == id }
                    preferences[SERVERS] = encodeServers(servers)
                }
            } catch (failure: Throwable) {
                if (credentialRemoved && removed != null && original != null) {
                    try {
                        val current = currentServers().singleOrNull { it.id == id }
                        if (current == original) {
                            records[id] = removed
                            writeCredentials(records)
                        } else if (current != null) {
                            throw CredentialConflictException()
                        }
                    } catch (recovery: Throwable) {
                        if (recovery !== failure) failure.addSuppressed(recovery)
                        if (failure !is Exception || failure is RuntimeException && failure !is SecurityException) throw failure
                        throw IncompleteCredentialRecoveryException().apply { initCause(failure) }
                    }
                }
                throw failure
            } finally {
                (removed?.credential as? VpsCredential.Password)?.clear()
            }
        }
    }

    suspend fun pendingInstall(expected: StoredVpsServer): PendingVpsInstall? = mutate {
        val preferences = dataStore.data.first()
        val servers = decodeServersForMutation(preferences[SERVERS].orEmpty())
        if (servers.singleOrNull { it.id == expected.id } != expected) throw ServerChangedException()
        PendingVpsInstall.decode(preferences[PENDING_INSTALLS].orEmpty()).firstOrNull { it.matches(expected) }
    }

    // Each conflicting owner must reject before either operation can reach the server.
    @Suppress("ThrowsCount")
    suspend fun admitInstall(expected: StoredVpsServer, target: VpsEndpoint, request: VpsInstallRequest): PendingVpsInstall = mutate {
        require(!request.force)
        if (target != VpsEndpoint(expected.host, expected.sshPort, expected.sshUser)) throw ServerChangedException()
        val attempt = PendingVpsInstall(expected.id, target, request)
        dataStore.edit { preferences ->
            val servers = decodeServersForMutation(preferences[SERVERS].orEmpty())
            if (servers.singleOrNull { it.id == expected.id } != expected) throw ServerChangedException()
            val attempts = PendingVpsInstall.decode(preferences[PENDING_INSTALLS].orEmpty())
            if (attempts.any { it.matches(expected) }) throw InstallPendingException()
            if (PendingVpsAdd.decode(preferences[PENDING_ADDS].orEmpty()).any { it.matches(expected) }) throw AddPendingException()
            PendingVpsRemove.decode(preferences[PENDING_REMOVALS].orEmpty()).firstOrNull { it.matches(expected) }
                ?.let { throw RemovePendingException(it.serverId) }
            preferences[PENDING_INSTALLS] = PendingVpsInstall.encode(attempts + attempt)
        }
        attempt
    }

    suspend fun settleInstall(expected: StoredVpsServer, attempt: PendingVpsInstall, status: VpsServerStatus, syncedAtEpochSeconds: Long): StoredVpsServer = mutate {
        val updated = observedRecord(expected, status, syncedAtEpochSeconds)
        dataStore.edit { preferences ->
            val servers = decodeServersForMutation(preferences[SERVERS].orEmpty())
            if (servers.singleOrNull { it.id == expected.id } != expected) throw ServerChangedException()
            val attempts = PendingVpsInstall.decode(preferences[PENDING_INSTALLS].orEmpty())
            if (attempt !in attempts || !attempt.matches(expected) ||
                attempt.ssh != VpsEndpoint(expected.host, expected.sshPort, expected.sshUser)) throw InstallPendingException()
            preferences[SERVERS] = encodeServers(servers.map { if (it.id == expected.id) updated else it })
            preferences[PENDING_INSTALLS] = PendingVpsInstall.encode(attempts - attempt)
        }
        updated
    }

    suspend fun clearFailedInstall(expected: StoredVpsServer, attempt: PendingVpsInstall) = mutate {
        dataStore.edit { preferences ->
            val servers = decodeServersForMutation(preferences[SERVERS].orEmpty())
            if (servers.singleOrNull { it.id == expected.id } != expected) throw ServerChangedException()
            val attempts = PendingVpsInstall.decode(preferences[PENDING_INSTALLS].orEmpty())
            if (attempt !in attempts || attempt.ssh != VpsEndpoint(expected.host, expected.sshPort, expected.sshUser))
                throw InstallPendingException()
            preferences[PENDING_INSTALLS] = PendingVpsInstall.encode(attempts - attempt)
        }
        Unit
    }

    suspend fun updateStatus(expected: StoredVpsServer, status: VpsServerStatus, syncedAtEpochSeconds: Long): StoredVpsServer = mutate {
        val updated = observedRecord(expected, status, syncedAtEpochSeconds)
        dataStore.edit { preferences ->
            val servers = decodeServersForMutation(preferences[SERVERS].orEmpty())
            if (servers.singleOrNull { it.id == expected.id } != expected) throw ServerChangedException()
            preferences[SERVERS] = encodeServers(servers.map { if (it.id == expected.id) updated else it })
        }
        updated
    }

    suspend fun pendingAdd(expected: StoredVpsServer): PendingVpsAdd? = mutate {
        val preferences = dataStore.data.first()
        if (decodeServersForMutation(preferences[SERVERS].orEmpty()).singleOrNull { it.id == expected.id } != expected)
            throw ServerChangedException()
        PendingVpsAdd.decode(preferences[PENDING_ADDS].orEmpty()).firstOrNull { it.matches(expected) }
    }

    // Current-record, install and add conflicts need distinct admission outcomes.
    @Suppress("ThrowsCount")
    suspend fun admitAdd(expected: StoredVpsServer, fingerprint: String, serverPublicKey: String, request: VpsAddPeerRequest): PendingVpsAdd = mutate {
        val attempt = PendingVpsAdd(expected.id, VpsEndpoint(expected.host, expected.sshPort, expected.sshUser), fingerprint, serverPublicKey, request)
        attempt.validate()
        requireCurrentCredential(expected, fingerprint)
        dataStore.edit { preferences ->
            if (decodeServersForMutation(preferences[SERVERS].orEmpty()).singleOrNull { it.id == expected.id } != expected)
                throw ServerChangedException()
            if (PendingVpsInstall.decode(preferences[PENDING_INSTALLS].orEmpty()).any { it.matches(expected) })
                throw InstallPendingException()
            PendingVpsRemove.decode(preferences[PENDING_REMOVALS].orEmpty()).firstOrNull { it.matches(expected) }
                ?.let { throw RemovePendingException(it.serverId) }
            val attempts = PendingVpsAdd.decode(preferences[PENDING_ADDS].orEmpty())
            if (attempts.any { it.matches(expected) }) throw AddPendingException()
            preferences[PENDING_ADDS] = PendingVpsAdd.encode(attempts + attempt)
        }
        attempt
    }

    suspend fun recordAddReceipt(attempt: PendingVpsAdd, clientPublicKey: String): PendingVpsAdd = mutate {
        val retained = attempt.copy(clientPublicKey = clientPublicKey).also { it.validate() }
        dataStore.edit { preferences ->
            val attempts = PendingVpsAdd.decode(preferences[PENDING_ADDS].orEmpty())
            if (retained !in attempts) {
                if (attempt !in attempts || attempt.clientPublicKey != null) throw AddPendingException()
                preferences[PENDING_ADDS] = PendingVpsAdd.encode(attempts.map { if (it == attempt) retained else it })
            }
        }
        retained
    }

    // Identity, record and durable-intent checks all precede the shared commit.
    @Suppress("ThrowsCount")
    suspend fun settleAdd(expected: StoredVpsServer, attempt: PendingVpsAdd, status: VpsServerStatus, syncedAtEpochSeconds: Long): StoredVpsServer = mutate {
        requireCurrentCredential(expected, attempt.fingerprint)
        if (attempt.serverId != expected.id || attempt.ssh != VpsEndpoint(expected.host, expected.sshPort, expected.sshUser) ||
            attempt.clientPublicKey == null || status.serverPublicKey != attempt.serverPublicKey ||
            status.peers.singleOrNull { it.name == attempt.request.name }?.publicKey != attempt.clientPublicKey)
            throw AddPendingException()
        val updated = observedRecord(expected, status, syncedAtEpochSeconds)
        dataStore.edit { preferences ->
            val servers = decodeServersForMutation(preferences[SERVERS].orEmpty())
            if (servers.singleOrNull { it.id == expected.id } != expected) throw ServerChangedException()
            val attempts = PendingVpsAdd.decode(preferences[PENDING_ADDS].orEmpty())
            if (attempt !in attempts) throw AddPendingException()
            preferences[SERVERS] = encodeServers(servers.map { if (it.id == expected.id) updated else it })
            preferences[PENDING_ADDS] = PendingVpsAdd.encode(attempts - attempt)
        }
        updated
    }

    suspend fun clearRejectedAdd(expected: StoredVpsServer, attempt: PendingVpsAdd) = mutate {
        requireCurrentCredential(expected, attempt.fingerprint)
        dataStore.edit { preferences ->
            if (decodeServersForMutation(preferences[SERVERS].orEmpty()).singleOrNull { it.id == expected.id } != expected)
                throw ServerChangedException()
            val attempts = PendingVpsAdd.decode(preferences[PENDING_ADDS].orEmpty())
            if (attempt !in attempts || attempt.clientPublicKey != null ||
                attempt.serverId != expected.id || attempt.ssh != VpsEndpoint(expected.host, expected.sshPort, expected.sshUser))
                throw AddPendingException()
            preferences[PENDING_ADDS] = PendingVpsAdd.encode(attempts - attempt)
        }
        Unit
    }

    private suspend fun requireCurrentCredential(expected: StoredVpsServer, fingerprint: String) {
        if (currentServers().singleOrNull { it.id == expected.id } != expected) throw ServerChangedException()
        withCredentials { records ->
            val credential = records[expected.id]
            if (credential == null || !credential.matches(expected) || credential.fingerprint != fingerprint)
                throw CredentialConflictException()
        }
    }

    suspend fun pendingRemove(expected: StoredVpsServer): PendingVpsRemove? = mutate {
        val preferences = dataStore.data.first()
        if (decodeServersForMutation(preferences[SERVERS].orEmpty()).singleOrNull { it.id == expected.id } != expected)
            throw ServerChangedException()
        PendingVpsRemove.decode(preferences[PENDING_REMOVALS].orEmpty()).firstOrNull { it.matches(expected) }
    }

    // Each existing durable writer must exclude a conflicting removal before dispatch.
    @Suppress("ThrowsCount")
    suspend fun admitRemove(expected: StoredVpsServer, attempt: PendingVpsRemove): PendingVpsRemove = mutate {
        attempt.validate()
        if (!attempt.owns(expected)) throw ServerChangedException()
        requireCurrentCredential(expected, attempt.fingerprint)
        dataStore.edit { preferences ->
            if (decodeServersForMutation(preferences[SERVERS].orEmpty()).singleOrNull { it.id == expected.id } != expected)
                throw ServerChangedException()
            if (PendingVpsInstall.decode(preferences[PENDING_INSTALLS].orEmpty()).any { it.matches(expected) }) throw InstallPendingException()
            if (PendingVpsAdd.decode(preferences[PENDING_ADDS].orEmpty()).any { it.matches(expected) }) throw AddPendingException()
            val attempts = PendingVpsRemove.decode(preferences[PENDING_REMOVALS].orEmpty())
            attempts.firstOrNull { it.matches(expected) }?.let { throw RemovePendingException(it.serverId) }
            preferences[PENDING_REMOVALS] = PendingVpsRemove.encode(attempts + attempt)
        }
        attempt
    }

    // Observation, current owner and exact durable intent must all hold before settlement.
    @Suppress("ThrowsCount")
    suspend fun settleRemove(expected: StoredVpsServer, attempt: PendingVpsRemove, status: VpsServerStatus,
        syncedAtEpochSeconds: Long, removeProfile: suspend () -> Unit): StoredVpsServer = mutate {
        requireCurrentCredential(expected, attempt.fingerprint)
        if (!attempt.owns(expected) || !attempt.observesCompletion(status)) throw RemovePendingException()
        if (attempt !in pendingRemovals()) throw RemovePendingException()
        val updated = observedRecord(expected, status, syncedAtEpochSeconds)
        removeProfile()
        dataStore.edit { preferences ->
            val servers = decodeServersForMutation(preferences[SERVERS].orEmpty())
            if (servers.singleOrNull { it.id == expected.id } != expected) throw ServerChangedException()
            val attempts = PendingVpsRemove.decode(preferences[PENDING_REMOVALS].orEmpty())
            if (attempt !in attempts) throw RemovePendingException()
            preferences[SERVERS] = encodeServers(servers.map { if (it.id == expected.id) updated else it })
            preferences[PENDING_REMOVALS] = PendingVpsRemove.encode(attempts - attempt)
        }
        updated
    }

    // A structured rejection may clear only the captured credential, record and intent owner.
    @Suppress("ThrowsCount")
    suspend fun clearRejectedRemove(expected: StoredVpsServer, attempt: PendingVpsRemove) = mutate {
        requireCurrentCredential(expected, attempt.fingerprint)
        if (!attempt.owns(expected)) throw RemovePendingException()
        dataStore.edit { preferences ->
            if (decodeServersForMutation(preferences[SERVERS].orEmpty()).singleOrNull { it.id == expected.id } != expected) throw ServerChangedException()
            val attempts = PendingVpsRemove.decode(preferences[PENDING_REMOVALS].orEmpty())
            if (attempt !in attempts) throw RemovePendingException()
            preferences[PENDING_REMOVALS] = PendingVpsRemove.encode(attempts - attempt)
        }
        Unit
    }

    suspend fun checkAddOwner(expected: StoredVpsServer, fingerprint: String) = mutate {
        requireCurrentCredential(expected, fingerprint)
    }

    private fun observedRecord(expected: StoredVpsServer, status: VpsServerStatus, syncedAtEpochSeconds: Long): StoredVpsServer {
        require(syncedAtEpochSeconds > 0L)
        return expected.copy(
            endpoint = status.endpoint,
            listenPort = status.listenPort,
            cidr = status.cidr,
            dns = status.dns,
            lastSyncEpochSeconds = syncedAtEpochSeconds,
            running = status.running,
        )
    }

    suspend fun saveCredential(server: StoredVpsServer, fingerprint: String, credential: VpsCredential) = mutate {
        JschRemoteShellClientFactory.decodeHostFingerprint(fingerprint)
        requireCurrent(server)
        if (pendingRemovals().any { it.matches(server) && (!it.owns(server) || it.fingerprint != fingerprint) }) throw RemovePendingException()
        withCredentials { records ->
            records[server.id]?.let { if (!it.matches(server)) throw CredentialConflictException() }
            val saved = SavedVpsCredential(
                VpsEndpoint(server.host, server.sshPort, server.sshUser),
                fingerprint,
                when (credential) {
                    is VpsCredential.Password -> VpsCredential.Password(credential.value.copyOf())
                    is VpsCredential.PrivateKey -> credential.copy()
                },
            )
            val previous = records.put(server.id, saved)
            (previous?.credential as? VpsCredential.Password)?.clear()
            writeCredentials(records)
        }
    }

    // Cancellation can reject a decrypted result after the admitted read finishes.
    suspend fun loadConnection(server: StoredVpsServer): VpsConnection? {
        var loaded: VpsConnection? = null
        var published = false
        try {
            val result = mutate {
                requireCurrent(server)
                withCredentials { records ->
                    records[server.id]?.let { saved ->
                        if (!saved.matches(server)) throw CredentialConflictException()
                        saved.connection().also { loaded = it }
                    }
                }
            }
            published = true
            return result
        } finally {
            if (!published) (loaded?.credential as? VpsCredential.Password)?.clear()
        }
    }

    suspend fun clearCredential(id: String) = mutate {
        if (pendingRemovals().any { it.serverId == id }) throw RemovePendingException()
        withCredentials { records ->
            val removed = records.remove(id)
            try {
                if (removed != null) writeCredentials(records)
            } finally {
                (removed?.credential as? VpsCredential.Password)?.clear()
            }
        }
    }

    internal suspend fun <T> withImportAdmission(incoming: List<StoredVpsServer>, block: suspend () -> T): T = mutate {
        val current = currentServers().associateBy { it.id }
        val pending = pendingRemovals()
        if (incoming.any { candidate -> current[candidate.id] != candidate && pending.any { it.matches(candidate) } }) throw RemovePendingException()
        val currentIds = current.keys
        val newIds = incoming.map { it.id }.filterNot { it in currentIds }
        if (newIds.isNotEmpty()) {
            withCredentials { records ->
                if (newIds.any { it in records }) throw CredentialConflictException()
            }
        }
        block()
    }

    private suspend fun requireCurrent(server: StoredVpsServer) {
        val current = currentServers().singleOrNull { it.id == server.id }
        if (current == null || !sameSshEndpoint(current, server)) throw CredentialConflictException()
    }

    private fun sameSshEndpoint(first: StoredVpsServer, second: StoredVpsServer): Boolean =
        first.host == second.host && first.sshPort == second.sshPort && first.sshUser == second.sshUser

    private suspend fun currentServers(): List<StoredVpsServer> =
        decodeServersForMutation(dataStore.data.first()[SERVERS].orEmpty())

    private suspend fun pendingRemovals(): List<PendingVpsRemove> =
        PendingVpsRemove.decode(dataStore.data.first()[PENDING_REMOVALS].orEmpty())

    private suspend fun <T> withCredentials(block: suspend (MutableMap<String, SavedVpsCredential>) -> T): T {
        val records = withContext(Dispatchers.IO) {
            credentialFile.read()?.let { bytes ->
                try { VpsCredentialRecords.decode(bytes) } finally { bytes.fill(0) }
            } ?: linkedMapOf()
        }
        return try { block(records) } finally { VpsCredentialRecords.clear(records) }
    }

    private suspend fun writeCredentials(records: Map<String, SavedVpsCredential>) = withContext(Dispatchers.IO) {
        val bytes = VpsCredentialRecords.encode(records)
        try { credentialFile.write(bytes) } finally { bytes.fill(0) }
    }

    private suspend fun <T> mutate(block: suspend () -> T): T = mutationMutex.withLock {
        currentCoroutineContext().ensureActive()
        // Once admitted, commit or recover both owners before honouring cancellation.
        val result = withContext(NonCancellable) { block() }
        currentCoroutineContext().ensureActive()
        result
    }

    private fun decodeServersForMutation(value: String): List<StoredVpsServer> {
        if (value.isEmpty()) return emptyList()
        try {
            val tokener = JSONTokener(value)
            val array = tokener.nextValue() as? JSONArray ?: invalidTransferServers()
            if (tokener.nextClean() != '\u0000') invalidTransferServers()
            val ids = mutableSetOf<String>()
            for (index in 0 until array.length()) {
                val json = array.get(index) as? JSONObject ?: invalidTransferServers()
                if (json.keys().asSequence().any { it !in SERVER_FIELDS }) invalidTransferServers()
                val id = json.get("id") as? String ?: invalidTransferServers()
                val host = json.get("host") as? String ?: invalidTransferServers()
                val user = if (json.has("sshUser")) {
                    json.get("sshUser") as? String ?: invalidTransferServers()
                } else {
                    "root"
                }
                val port = if (json.has("sshPort")) {
                    json.get("sshPort") as? Int ?: invalidTransferServers()
                } else {
                    DEFAULT_SSH_PORT
                }
                if (id.isBlank() || host.isBlank() || user.isBlank() || port !in 1..MAX_PORT_NUMBER || !ids.add(id))
                    invalidTransferServers()
            }
        } catch (_: JSONException) {
            invalidTransferServers()
        }
        return decodeServers(value)
    }

    private fun decodeServers(value: String): List<StoredVpsServer> {
        if (value.isBlank()) return emptyList()
        val array = try {
            JSONArray(value)
        } catch (_: JSONException) {
            Log.w(TAG, "Ignoring corrupt VPS server store payload")
            return emptyList()
        }
        return buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                add(
                    StoredVpsServer(
                        id = json.optString("id"),
                        name = json.optString("name"),
                        host = json.optString("host"),
                        sshPort = json.optInt("sshPort", DEFAULT_SSH_PORT).coerceIn(1, MAX_PORT_NUMBER),
                        sshUser = json.optString("sshUser", "root"),
                        endpoint = json.optString("endpoint"),
                        listenPort = json.optString("listenPort"),
                        cidr = json.optString("cidr"),
                        dns = json.optString("dns"),
                        os = json.optString("os"),
                        version = json.optString("version"),
                        provider = json.optString("provider"),
                        region = json.optString("region"),
                        engine = json.optString("engine"),
                        lastSyncEpochSeconds = json.optLong("lastSyncEpochSeconds"),
                        running = json.opt("running") as? Boolean,
                    ),
                )
            }
        }.filter { it.id.isNotBlank() && it.host.isNotBlank() && it.sshUser.isNotBlank() }
    }

    companion object {
        private const val DEFAULT_SSH_PORT = 22
        private const val MAX_PORT_NUMBER = 65_535
        private const val TRANSFER_FIELD_COUNT = 16
        private val SERVER_FIELDS = setOf(
            "id", "name", "host", "sshPort", "sshUser", "endpoint", "listenPort", "cidr", "dns",
            "os", "version", "provider", "region", "engine", "lastSyncEpochSeconds", "running",
        )
        private const val TAG = "vpn0wn/VpsServerStore"
        internal val SERVERS = stringPreferencesKey("vps_servers")
        private val PENDING_INSTALLS = stringPreferencesKey("vps_pending_installs")
        private val PENDING_ADDS = stringPreferencesKey("vps_pending_adds")
        private val PENDING_REMOVALS = stringPreferencesKey("vps_pending_removals")

        internal fun encodeServers(servers: List<StoredVpsServer>): String {
            val array = JSONArray()
            for (server in servers) {
                array.put(
                    JSONObject()
                        .put("id", server.id)
                        .put("name", server.name)
                        .put("host", server.host)
                        .put("sshPort", server.sshPort)
                        .put("sshUser", server.sshUser)
                        .put("endpoint", server.endpoint)
                        .put("listenPort", server.listenPort)
                        .put("cidr", server.cidr)
                        .put("dns", server.dns)
                        .put("os", server.os)
                        .put("version", server.version)
                        .put("provider", server.provider)
                        .put("region", server.region)
                        .put("engine", server.engine)
                        .put("lastSyncEpochSeconds", server.lastSyncEpochSeconds)
                        .put("running", server.running ?: JSONObject.NULL),
                )
            }
            return array.toString()
        }

        internal fun decodeTransferServers(value: String): List<StoredVpsServer> = decodeTransferServers(value, false)

        internal fun decodeStoredServersForTransfer(value: String): List<StoredVpsServer> = decodeTransferServers(value, true)

        private fun decodeTransferServers(value: String, allowLegacyRunning: Boolean): List<StoredVpsServer> {
            if (value.isEmpty()) return emptyList()
            try {
                val tokener = JSONTokener(value)
                val array = tokener.nextValue() as? JSONArray ?: invalidTransferServers()
                if (tokener.nextClean() != '\u0000') invalidTransferServers()
                val ids = mutableSetOf<String>()
                return buildList {
                    for (index in 0 until array.length()) {
                        val json = array.get(index) as? JSONObject ?: invalidTransferServers()
                        // The original store writer preceded running observations; its other fields were complete.
                        if (allowLegacyRunning && json.length() == TRANSFER_FIELD_COUNT - 1 && !json.has("running")) {
                            json.put("running", JSONObject.NULL)
                        }
                        val server = decodeTransferServer(json)
                        if (!ids.add(server.id)) invalidTransferServers()
                        add(server)
                    }
                }
            } catch (_: JSONException) {
                invalidTransferServers()
            }
        }

        private fun decodeTransferServer(json: JSONObject): StoredVpsServer {
            if (json.length() != TRANSFER_FIELD_COUNT) invalidTransferServers()
            val id = json.transferString("id")
            val host = json.transferString("host")
            val sshUser = json.transferString("sshUser")
            val sshPort = json.transferLong("sshPort")
            val lastSync = json.transferLong("lastSyncEpochSeconds")
            if (id.isBlank() || host.isBlank() || sshUser.isBlank() ||
                sshPort !in 1..MAX_PORT_NUMBER || lastSync < 0L
            ) invalidTransferServers()
            return StoredVpsServer(
                id = id,
                name = json.transferString("name"),
                host = host,
                sshPort = sshPort.toInt(),
                sshUser = sshUser,
                endpoint = json.transferString("endpoint"),
                listenPort = json.transferString("listenPort"),
                cidr = json.transferString("cidr"),
                dns = json.transferString("dns"),
                os = json.transferString("os"),
                version = json.transferString("version"),
                provider = json.transferString("provider"),
                region = json.transferString("region"),
                engine = json.transferString("engine"),
                lastSyncEpochSeconds = lastSync,
                running = when (val running = json.get("running")) {
                    JSONObject.NULL -> null
                    is Boolean -> running
                    else -> invalidTransferServers()
                },
            )
        }

        private fun JSONObject.transferString(name: String): String =
            get(name) as? String ?: invalidTransferServers()

        private fun JSONObject.transferLong(name: String): Long = when (val value = get(name)) {
            is Int -> value.toLong()
            is Long -> value
            else -> invalidTransferServers()
        }

        private fun invalidTransferServers(): Nothing =
            throw InvalidTransferMetadataException()
    }
}
