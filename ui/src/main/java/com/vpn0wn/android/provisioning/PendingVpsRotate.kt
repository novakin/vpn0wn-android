/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

data class PendingVpsRotate(
    val serverId: String,
    val ssh: VpsEndpoint,
    val fingerprint: String,
    val serverPublicKey: String,
    val name: String,
    val oldClientPublicKey: String,
    val newClientPublicKey: String,
    val operationId: String,
    val phase: Phase = Phase.PREPARED,
) {
    enum class Phase { PREPARED, DISPATCHED, REJECTED }
    fun matches(server: StoredVpsServer): Boolean = serverId == server.id || ssh == VpsEndpoint(server.host, server.sshPort, server.sshUser)

    internal fun owns(server: StoredVpsServer): Boolean = serverId == server.id && ssh == VpsEndpoint(server.host, server.sshPort, server.sshUser)

    internal fun identity(clientPublicKey: String): VpsUserProfileIdentity = VpsUserProfileIdentity(serverId, ssh, serverPublicKey, clientPublicKey)

    internal fun owns(profile: VpsUserProfile): Boolean = profile.identity == identity(newClientPublicKey) && profile.name == name &&
        profile.rotation?.let { it.operationId == operationId && it.oldClientPublicKey == oldClientPublicKey } == true

    internal fun validate() {
        identity(oldClientPublicKey).validate()
        identity(newClientPublicKey).validate()
        if (!PendingVpsAdd.NAME.matches(name) || oldClientPublicKey == newClientPublicKey || !VpsRevokedArchive.OPERATION_ID.matches(operationId)) invalid()
        JschRemoteShellClientFactory.decodeHostFingerprint(fingerprint)
    }

    companion object {
        private const val MAX_ATTEMPTS = 256
        private val FIELDS = setOf("serverId", "host", "sshPort", "user", "fingerprint", "serverPublicKey", "name",
            "oldClientPublicKey", "newClientPublicKey", "operationId", "phase")

        internal fun encode(attempts: List<PendingVpsRotate>): String {
            validateAttempts(attempts)
            return JSONArray().apply {
                attempts.forEach { attempt ->
                    put(JSONObject().put("serverId", attempt.serverId).put("host", attempt.ssh.host).put("sshPort", attempt.ssh.port)
                        .put("user", attempt.ssh.user).put("fingerprint", attempt.fingerprint).put("serverPublicKey", attempt.serverPublicKey)
                        .put("name", attempt.name).put("oldClientPublicKey", attempt.oldClientPublicKey).put("newClientPublicKey", attempt.newClientPublicKey)
                        .put("operationId", attempt.operationId).put("phase", attempt.phase.name.lowercase()))
                }
            }.toString()
        }

        internal fun decode(text: String): List<PendingVpsRotate> {
            if (text.isEmpty()) return emptyList()
            try {
                val tokener = JSONTokener(text)
                val array = tokener.nextValue() as? JSONArray ?: invalid()
                if (tokener.nextClean() != '\u0000' || array.length() > MAX_ATTEMPTS) invalid()
                return (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    if (item.keys().asSequence().toSet() != FIELDS) invalid()
                    val phase = Phase.entries.singleOrNull { it.name.lowercase() == item.text("phase") } ?: invalid()
                    PendingVpsRotate(item.text("serverId"), VpsEndpoint(item.text("host"), item.get("sshPort") as? Int ?: invalid(), item.text("user")),
                        item.text("fingerprint"), item.text("serverPublicKey"), item.text("name"), item.text("oldClientPublicKey"),
                        item.text("newClientPublicKey"), item.text("operationId"), phase)
                }.also(::validateAttempts)
            } catch (_: JSONException) {
                invalid()
            } catch (_: ProvisioningException) {
                invalid()
            } catch (_: VpsUserProfileException) {
                invalid()
            }
        }

        private fun validateAttempts(attempts: List<PendingVpsRotate>) {
            if (attempts.size > MAX_ATTEMPTS) invalid()
            attempts.forEach { it.validate() }
            if (attempts.map { it.serverId }.distinct().size != attempts.size || attempts.map { it.ssh }.distinct().size != attempts.size ||
                attempts.map { it.operationId }.distinct().size != attempts.size) invalid()
        }

        private fun JSONObject.text(name: String): String = get(name) as? String ?: invalid()
        private fun invalid(): Nothing = throw IOException("Pending user rotation record is invalid")
    }
}
