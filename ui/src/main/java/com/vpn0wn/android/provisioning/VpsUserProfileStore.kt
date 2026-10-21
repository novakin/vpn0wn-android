/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.content.Context
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import com.vpn0wn.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

class VpsUserProfileStore internal constructor(private val file: VpsUserProfileFile) {
    constructor(context: Context) : this(VpsUserProfileFile(context))

    private val mutation = Mutex()

    suspend fun saveGenerated(server: StoredVpsServer, serverPublicKey: String, request: VpsAddPeerRequest, result: VpsAddPeerResult): VpsUserProfile =
        withContext(Dispatchers.IO) {
            val profile = VpsUserProfile.generated(server, serverPublicKey, request, result)
            access { records ->
                requireNotRotating(records, profile.identity)
                val previous = records[profile.identity]
                if (previous != null) {
                    if (!previous.sameContent(profile)) conflict()
                } else {
                    records[profile.identity] = profile
                    write(records.values.toList())
                }
                profile
            }
        }

    suspend fun load(server: StoredVpsServer, serverPublicKey: String, clientPublicKey: String): VpsUserProfile? {
        val identity = VpsUserProfileIdentity(server.id, VpsEndpoint(server.host, server.sshPort, server.sshUser), serverPublicKey, clientPublicKey)
        identity.validate()
        return access { records -> records[identity]?.takeUnless { it.rotation?.settled == false } }
    }

    suspend fun delete(expected: VpsUserProfile): Boolean = access { records ->
        requireNotRotating(records, expected.identity)
        val current = records[expected.identity]
        if (current == null) {
            false
        } else {
            if (!current.sameContent(expected)) conflict()
            records.remove(expected.identity)
            write(records.values.toList())
            true
        }
    }

    internal suspend fun stageRotation(expected: VpsUserProfile, operationId: String, keyPair: KeyPair = KeyPair()): VpsUserProfile = access { records ->
        val current = records[expected.identity] ?: conflict()
        if (!current.sameContent(expected)) conflict()
        if (records.values.any { it.rotation != null && (it.identity.serverId == expected.identity.serverId || it.identity.ssh == expected.identity.ssh) })
            conflict()
        val proposed = expected.rotated(keyPair, operationId)
        if (proposed.identity in records) conflict()
        records[proposed.identity] = proposed
        write(records.values.toList())
        proposed
    }

    internal suspend fun loadRotation(attempt: PendingVpsRotate): VpsUserProfile = access { records ->
        records[attempt.identity(attempt.newClientPublicKey)]?.takeIf(attempt::owns) ?: conflict()
    }

    internal suspend fun settleRotation(attempt: PendingVpsRotate) = access { records ->
        val proposed = records[attempt.identity(attempt.newClientPublicKey)]?.takeIf(attempt::owns) ?: conflict()
        val oldIdentity = attempt.identity(attempt.oldClientPublicKey)
        val old = records[oldIdentity]
        if (proposed.rotation?.settled == true) {
            if (old != null) conflict()
        } else {
            if (old == null || !proposed.preservesSettings(old)) conflict()
            records.remove(oldIdentity)
            records[proposed.identity] = proposed.withRotation(requireNotNull(proposed.rotation).copy(settled = true))
            write(records.values.toList())
        }
    }

    internal suspend fun discardFailedRotation(attempt: PendingVpsRotate) = access { records ->
        if (attempt.phase != PendingVpsRotate.Phase.REJECTED) conflict()
        val old = records[attempt.identity(attempt.oldClientPublicKey)] ?: conflict()
        if (old.name != attempt.name || old.rotation != null) conflict()
        val proposed = records[attempt.identity(attempt.newClientPublicKey)]
        if (proposed != null) {
            if (!attempt.owns(proposed) || proposed.rotation?.settled != false || !proposed.preservesSettings(old)) conflict()
            records.remove(proposed.identity)
            write(records.values.toList())
        }
    }

    internal suspend fun cleanupRotations(active: List<PendingVpsRotate>) = access { records ->
        var changed = false
        records.values.filter { it.rotation != null }.forEach { proposed ->
            val marker = requireNotNull(proposed.rotation)
            val intent = active.singleOrNull { it.operationId == marker.operationId }
            if (intent != null) {
                if (!intent.owns(proposed)) conflict()
            } else {
                val old = records[proposed.identity.copy(clientPublicKey = marker.oldClientPublicKey)]
                if (marker.settled) {
                    if (old != null) conflict()
                    records[proposed.identity] = proposed.withRotation(null)
                } else {
                    if (old == null || !proposed.preservesSettings(old)) conflict()
                    records.remove(proposed.identity)
                }
                changed = true
            }
        }
        if (changed) write(records.values.toList())
    }

    private fun requireNotRotating(records: Map<VpsUserProfileIdentity, VpsUserProfile>, identity: VpsUserProfileIdentity) {
        if (records.values.any { profile -> profile.rotation?.let { profile.identity == identity ||
                profile.identity.copy(clientPublicKey = it.oldClientPublicKey) == identity } == true }) conflict()
    }

    private suspend fun <T> access(block: (MutableMap<VpsUserProfileIdentity, VpsUserProfile>) -> T): T = mutation.withLock {
        currentCoroutineContext().ensureActive()
        // A committed snapshot must settle before cancellation can reach its caller.
        val result = withContext(NonCancellable + Dispatchers.IO) {
            val records = file.read()?.let { bytes ->
                try {
                    VpsUserProfileRecords.decode(bytes)
                } finally {
                    bytes.fill(0)
                }
            } ?: linkedMapOf()
            block(records)
        }
        currentCoroutineContext().ensureActive()
        result
    }

    private fun write(profiles: List<VpsUserProfile>) {
        val bytes = VpsUserProfileRecords.encode(profiles)
        try {
            file.write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun conflict(): Nothing = throw VpsUserProfileException(VpsUserProfileException.Category.CONFLICT)
}

internal object VpsUserProfileRecords {
    private const val VERSION = 1
    private const val MAX_PROFILES = 256
    private val RECORD_FIELDS = setOf("serverId", "host", "sshPort", "sshUser", "serverPublicKey", "clientPublicKey", "name", "config")
    private val PREFIX = "{\"version\":$VERSION,\"profiles\":[".toByteArray(Charsets.US_ASCII)
    private val SUFFIX = "]}".toByteArray(Charsets.US_ASCII)

    fun encode(profiles: List<VpsUserProfile>): ByteArray {
        if (profiles.size > MAX_PROFILES) limit()
        val records = mutableListOf<ByteArray>()
        var size = PREFIX.size + SUFFIX.size + (profiles.size - 1).coerceAtLeast(0)
        try {
            profiles.forEach { profile ->
                val identity = profile.identity
                val record = JSONObject()
                    .put("serverId", identity.serverId)
                    .put("host", identity.ssh.host)
                    .put("sshPort", identity.ssh.port)
                    .put("sshUser", identity.ssh.user)
                    .put("serverPublicKey", identity.serverPublicKey)
                    .put("clientPublicKey", identity.clientPublicKey)
                    .put("name", profile.name)
                    .put("config", profile.payload())
                profile.rotation?.let { rotation ->
                    record.put("rotation", JSONObject().put("operationId", rotation.operationId)
                        .put("oldClientPublicKey", rotation.oldClientPublicKey).put("settled", rotation.settled))
                }
                val bytes = record.toString().toByteArray(Charsets.UTF_8)
                if (bytes.size > VpsUserProfileFile.MAX_PLAINTEXT_BYTES - size) {
                    bytes.fill(0)
                    limit()
                }
                records.add(bytes)
                size += bytes.size
            }
            val encoded = ByteArray(size)
            PREFIX.copyInto(encoded)
            var offset = PREFIX.size
            records.forEachIndexed { index, bytes ->
                if (index != 0) encoded[offset++] = ','.code.toByte()
                bytes.copyInto(encoded, offset)
                offset += bytes.size
            }
            SUFFIX.copyInto(encoded, offset)
            return encoded
        } finally {
            records.forEach { it.fill(0) }
        }
    }

    fun decode(bytes: ByteArray): MutableMap<VpsUserProfileIdentity, VpsUserProfile> {
        if (bytes.size > VpsUserProfileFile.MAX_PLAINTEXT_BYTES) corrupt()
        try {
            val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            val tokener = JSONTokener(text)
            val json = tokener.nextValue() as? JSONObject ?: corrupt()
            if (tokener.nextClean() != '\u0000' || json.keys().asSequence().toSet() != setOf("version", "profiles") ||
                json.get("version") != VERSION) corrupt()
            val array = json.getJSONArray("profiles")
            if (array.length() > MAX_PROFILES) corrupt()
            val records = linkedMapOf<VpsUserProfileIdentity, VpsUserProfile>()
            for (index in 0 until array.length()) {
                val record = array.getJSONObject(index)
                val fields = record.keys().asSequence().toSet()
                if (fields != RECORD_FIELDS && fields != RECORD_FIELDS + "rotation") corrupt()
                val port = record.get("sshPort") as? Int ?: corrupt()
                val identity = VpsUserProfileIdentity(record.text("serverId"), VpsEndpoint(record.text("host"), port, record.text("sshUser")),
                    record.text("serverPublicKey"), record.text("clientPublicKey"))
                val rotation = if (record.has("rotation")) {
                    val marker = record.getJSONObject("rotation")
                    if (marker.keys().asSequence().toSet() != setOf("operationId", "oldClientPublicKey", "settled")) corrupt()
                    VpsUserProfile.Rotation(marker.text("operationId"), marker.text("oldClientPublicKey"), marker.get("settled") as? Boolean ?: corrupt())
                } else null
                val profile = VpsUserProfile.decode(identity, record.text("name"), record.text("config")).withRotation(rotation)
                if (records.put(identity, profile) != null) corrupt()
            }
            validateRotations(records)
            return records
        } catch (_: JSONException) {
            corrupt()
        } catch (_: CharacterCodingException) {
            corrupt()
        } catch (_: VpsUserProfileException) {
            corrupt()
        }
    }

    private fun validateRotations(records: Map<VpsUserProfileIdentity, VpsUserProfile>) {
        val proposals = records.values.filter { it.rotation != null }
        if (proposals.map { it.rotation?.operationId }.distinct().size != proposals.size ||
            proposals.map { it.identity.serverId }.distinct().size != proposals.size ||
            proposals.map { it.identity.ssh }.distinct().size != proposals.size) corrupt()
        proposals.forEach { proposed ->
            val marker = requireNotNull(proposed.rotation)
            val old = records[proposed.identity.copy(clientPublicKey = marker.oldClientPublicKey)]
            if (marker.settled) {
                if (old != null) corrupt()
            } else if (old == null || old.rotation != null || !proposed.preservesSettings(old)) corrupt()
        }
    }

    private fun JSONObject.text(name: String): String = get(name) as? String ?: corrupt()
    private fun limit(): Nothing = throw VpsUserProfileException(VpsUserProfileException.Category.LIMIT)
    private fun corrupt(): Nothing = throw VpsUserProfileException(VpsUserProfileException.Category.CORRUPT)
}
