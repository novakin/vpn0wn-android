/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import com.vpn0wn.crypto.Key
import com.vpn0wn.crypto.KeyFormatException
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

data class PendingVpsAdd(
    val serverId: String,
    val ssh: VpsEndpoint,
    val fingerprint: String,
    val serverPublicKey: String,
    val request: VpsAddPeerRequest,
    val clientPublicKey: String? = null,
) {
    fun matches(server: StoredVpsServer): Boolean = serverId == server.id ||
        ssh == VpsEndpoint(server.host, server.sshPort, server.sshUser)

    internal fun validate() {
        if (serverId.isBlank() || ssh.host.isBlank() || ssh.user.isBlank() || ssh.port !in 1..MAX_PORT ||
            !NAME.matches(request.name) || request.allowedIps != DEFAULT_ROUTES || request.dns.isNullOrBlank()) invalid()
        try {
            JschRemoteShellClientFactory.decodeHostFingerprint(fingerprint)
            if (Key.fromBase64(serverPublicKey).toBase64() != serverPublicKey) invalid()
            clientPublicKey?.let { if (Key.fromBase64(it).toBase64() != it) invalid() }
        } catch (_: KeyFormatException) {
            invalid()
        }
    }

    companion object {
        internal const val DEFAULT_ROUTES = "0.0.0.0/0"
        internal val NAME = Regex("[A-Za-z0-9_-]{1,32}")
        private const val MAX_PORT = 65_535
        private const val MAX_ATTEMPTS = 256
        private val FIELDS = setOf("serverId", "host", "sshPort", "user", "fingerprint", "serverPublicKey", "name", "routes", "dns", "clientPublicKey")

        internal fun encode(attempts: List<PendingVpsAdd>): String {
            if (attempts.size > MAX_ATTEMPTS) invalid()
            return JSONArray().apply {
                attempts.forEach { attempt ->
                    attempt.validate()
                    put(JSONObject().put("serverId", attempt.serverId).put("host", attempt.ssh.host)
                        .put("sshPort", attempt.ssh.port).put("user", attempt.ssh.user)
                        .put("fingerprint", attempt.fingerprint).put("serverPublicKey", attempt.serverPublicKey)
                        .put("name", attempt.request.name).put("routes", attempt.request.allowedIps)
                        .put("dns", attempt.request.dns).put("clientPublicKey", attempt.clientPublicKey ?: JSONObject.NULL))
                }
            }.toString()
        }

        internal fun decode(text: String): List<PendingVpsAdd> {
            if (text.isEmpty()) return emptyList()
            try {
                val tokener = JSONTokener(text)
                val array = tokener.nextValue() as? JSONArray ?: invalid()
                if (tokener.nextClean() != '\u0000' || array.length() > MAX_ATTEMPTS) invalid()
                val attempts = (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    if (item.keys().asSequence().toSet() != FIELDS) invalid()
                    val port = item.get("sshPort") as? Int ?: invalid()
                    val clientKey = when (val value = item.get("clientPublicKey")) {
                        JSONObject.NULL -> null
                        is String -> value
                        else -> invalid()
                    }
                    PendingVpsAdd(item.text("serverId"), VpsEndpoint(item.text("host"), port, item.text("user")),
                        item.text("fingerprint"), item.text("serverPublicKey"),
                        VpsAddPeerRequest(item.text("name"), item.text("routes"), item.text("dns")), clientKey)
                        .also { it.validate() }
                }
                if (attempts.map { it.serverId }.distinct().size != attempts.size ||
                    attempts.map { it.ssh }.distinct().size != attempts.size) invalid()
                return attempts
            } catch (_: JSONException) {
                invalid()
            } catch (_: ProvisioningException) {
                invalid()
            }
        }

        private fun JSONObject.text(name: String): String = get(name) as? String ?: invalid()
        private fun invalid(): Nothing = throw IOException("Pending user creation record is invalid")
    }
}
