/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

data class PendingVpsInstall(val serverId: String, val ssh: VpsEndpoint, val request: VpsInstallRequest) {
    fun matches(server: StoredVpsServer): Boolean = serverId == server.id ||
        ssh == VpsEndpoint(server.host, server.sshPort, server.sshUser)

    companion object {
        internal fun encode(attempts: List<PendingVpsInstall>): String = JSONArray().apply {
            attempts.forEach { attempt ->
                put(JSONObject().put("serverId", attempt.serverId).put("host", attempt.ssh.host)
                    .put("sshPort", attempt.ssh.port).put("user", attempt.ssh.user)
                    .put("endpoint", attempt.request.endpoint).put("port", attempt.request.port)
                    .put("cidr", attempt.request.cidr).put("dns", attempt.request.dns))
            }
        }.toString()

        internal fun decode(text: String): List<PendingVpsInstall> {
            if (text.isEmpty()) return emptyList()
            try {
                val tokener = JSONTokener(text)
                val array = tokener.nextValue() as? JSONArray ?: invalid()
                if (tokener.nextClean() != '\u0000') invalid()
                val attempts = (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    if (item.keys().asSequence().toSet() != FIELDS) invalid()
                    val attempt = PendingVpsInstall(
                        item.text("serverId"), VpsEndpoint(item.text("host"), item.port("sshPort"), item.text("user")),
                        VpsInstallRequest(item.text("endpoint"), item.text("cidr"), item.text("dns"), item.port("port")),
                    )
                    attempt
                }
                if (attempts.map { it.serverId }.distinct().size != attempts.size ||
                    attempts.map { it.ssh }.distinct().size != attempts.size) invalid()
                return attempts
            } catch (_: JSONException) {
                invalid()
            }
        }

        private fun JSONObject.text(name: String): String =
            (get(name) as? String)?.takeIf { it.isNotBlank() } ?: invalid()

        private fun JSONObject.port(name: String): Int =
            (get(name) as? Int)?.takeIf { it in 1..MAX_PORT } ?: invalid()

        private fun invalid(): Nothing = throw IOException("Pending server installation record is invalid")

        private const val MAX_PORT = 65_535
        private val FIELDS = setOf("serverId", "host", "sshPort", "user", "endpoint", "port", "cidr", "dns")
    }
}
