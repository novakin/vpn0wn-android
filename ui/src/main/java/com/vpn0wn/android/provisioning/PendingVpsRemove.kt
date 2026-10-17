/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import com.vpn0wn.crypto.Key
import com.vpn0wn.crypto.KeyFormatException
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

data class PendingVpsRemove(
    val serverId: String,
    val ssh: VpsEndpoint,
    val fingerprint: String,
    val serverPublicKey: String,
    val name: String,
    val clientPublicKey: String,
) {
    fun matches(server: StoredVpsServer): Boolean = serverId == server.id ||
        ssh == VpsEndpoint(server.host, server.sshPort, server.sshUser)

    internal fun owns(server: StoredVpsServer): Boolean = serverId == server.id &&
        ssh == VpsEndpoint(server.host, server.sshPort, server.sshUser)

    internal fun observesAbsence(status: VpsServerStatus): Boolean =
        status.interfaceName == "wg0" && status.serverPublicKey == serverPublicKey &&
            status.peers.none { it.publicKey == clientPublicKey } &&
            status.configuredPeerPublicKeys?.none { it == clientPublicKey } == true &&
            (status.running == false && (status.runtimePeerPublicKeys == null || status.runtimePeerPublicKeys.isEmpty()) ||
                status.running == true && status.runtimePeerPublicKeys?.none { it == clientPublicKey } == true)

    internal fun validate() {
        if (serverId.isBlank() || ssh.host.isBlank() || ssh.user.isBlank() || ssh.port !in 1..MAX_PORT || !PendingVpsAdd.NAME.matches(name)) invalid()
        try {
            JschRemoteShellClientFactory.decodeHostFingerprint(fingerprint)
            if (Key.fromBase64(serverPublicKey).toBase64() != serverPublicKey ||
                Key.fromBase64(clientPublicKey).toBase64() != clientPublicKey) invalid()
        } catch (_: KeyFormatException) {
            invalid()
        }
    }

    companion object {
        private const val MAX_PORT = 65_535
        private const val MAX_ATTEMPTS = 256
        private val FIELDS = setOf("serverId", "host", "sshPort", "user", "fingerprint", "serverPublicKey", "name", "clientPublicKey")

        internal fun encode(attempts: List<PendingVpsRemove>): String {
            if (attempts.size > MAX_ATTEMPTS) invalid()
            return JSONArray().apply {
                attempts.forEach { attempt ->
                    attempt.validate()
                    put(JSONObject().put("serverId", attempt.serverId).put("host", attempt.ssh.host)
                        .put("sshPort", attempt.ssh.port).put("user", attempt.ssh.user).put("fingerprint", attempt.fingerprint)
                        .put("serverPublicKey", attempt.serverPublicKey).put("name", attempt.name).put("clientPublicKey", attempt.clientPublicKey))
                }
            }.toString()
        }

        internal fun decode(text: String): List<PendingVpsRemove> {
            if (text.isEmpty()) return emptyList()
            try {
                val tokener = JSONTokener(text)
                val array = tokener.nextValue() as? JSONArray ?: invalid()
                if (tokener.nextClean() != '\u0000' || array.length() > MAX_ATTEMPTS) invalid()
                val attempts = (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    if (item.keys().asSequence().toSet() != FIELDS) invalid()
                    PendingVpsRemove(item.text("serverId"), VpsEndpoint(item.text("host"), item.get("sshPort") as? Int ?: invalid(), item.text("user")),
                        item.text("fingerprint"), item.text("serverPublicKey"), item.text("name"), item.text("clientPublicKey"))
                        .also { it.validate() }
                }
                if (attempts.map { it.serverId }.distinct().size != attempts.size || attempts.map { it.ssh }.distinct().size != attempts.size) invalid()
                return attempts
            } catch (_: JSONException) {
                invalid()
            } catch (_: ProvisioningException) {
                invalid()
            }
        }

        private fun JSONObject.text(name: String): String = get(name) as? String ?: invalid()
        private fun invalid(): Nothing = throw IOException("Pending user removal record is invalid")
    }
}
