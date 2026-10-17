/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import com.vpn0wn.config.InetAddresses
import com.vpn0wn.config.InetNetwork
import com.vpn0wn.config.ParseException
import com.vpn0wn.crypto.Key
import com.vpn0wn.crypto.KeyFormatException
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

data class PendingVpsRestore(
    val operationId: String,
    val serverId: String,
    val ssh: VpsEndpoint,
    val fingerprint: String,
    val ciphertextSha256: String,
    val plan: VpsRestoreCheckPlan,
) {
    fun matches(server: StoredVpsServer): Boolean = serverId == server.id || ssh == VpsEndpoint(server.host, server.sshPort, server.sshUser)

    internal fun owns(server: StoredVpsServer): Boolean = serverId == server.id && ssh == VpsEndpoint(server.host, server.sshPort, server.sshUser)

    internal fun observes(verification: VpsRestoreVerification): Boolean = verification == VpsRestoreVerification(
        plan.interfaceName, plan.endpoint, plan.port, plan.cidr, plan.dns, plan.serverPublicKey, plan.peerCount, plan.revokedCount,
        running = true, serviceEnabled = true,
    )

    internal fun validate() {
        if (!VpsRevokedArchive.OPERATION_ID.matches(operationId) || !SHA256.matches(ciphertextSha256) ||
            serverId.isBlank() || ssh.user.isBlank() || ssh.port !in 1..MAX_PORT || plan.interfaceName != "wg0" ||
            plan.endpoint.isBlank() || plan.dns.isBlank() || plan.port.toIntOrNull()?.let { it in 1..MAX_PORT && it.toString() == plan.port } != true ||
            plan.peerCount !in 0..MAX_PEERS || plan.revokedCount !in 0..VpsBackupResult.MAX_REVOKED_FILES) invalid()
        try {
            InetAddresses.parse(ssh.host)
            val network = InetNetwork.parse(plan.cidr)
            if (!plan.cidr.endsWith(".0/24") || network.address.address.size != IPV4_BYTES || network.toString() != plan.cidr ||
                Key.fromBase64(plan.serverPublicKey).toBase64() != plan.serverPublicKey) invalid()
            JschRemoteShellClientFactory.decodeHostFingerprint(fingerprint)
        } catch (_: ParseException) {
            invalid()
        } catch (_: KeyFormatException) {
            invalid()
        } catch (_: ProvisioningException) {
            invalid()
        }
        if (listOf(serverId, ssh.user, plan.endpoint, plan.dns).any { it.length > MAX_FIELD_CHARS || it.any(Char::isISOControl) }) invalid()
    }

    companion object {
        private const val MAX_ATTEMPTS = 256
        private const val MAX_PORT = 65_535
        private const val MAX_PEERS = 253
        private const val IPV4_BYTES = 4
        private const val MAX_FIELD_CHARS = 1024
        private const val MAX_STORE_CHARS = 2_097_152
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val FIELDS = setOf("operationId", "serverId", "ssh", "fingerprint", "ciphertextSha256", "plan")
        private val SSH_FIELDS = setOf("host", "port", "user")
        private val PLAN_FIELDS = setOf("interface", "endpoint", "port", "cidr", "dns", "serverPublicKey", "peerCount", "revokedCount")

        internal fun encode(attempts: List<PendingVpsRestore>): String {
            validateAttempts(attempts)
            return JSONArray().apply {
                attempts.forEach { attempt ->
                    val plan = attempt.plan
                    put(JSONObject().put("operationId", attempt.operationId).put("serverId", attempt.serverId)
                        .put("ssh", JSONObject().put("host", attempt.ssh.host).put("port", attempt.ssh.port).put("user", attempt.ssh.user))
                        .put("fingerprint", attempt.fingerprint).put("ciphertextSha256", attempt.ciphertextSha256)
                        .put("plan", JSONObject().put("interface", plan.interfaceName).put("endpoint", plan.endpoint).put("port", plan.port)
                            .put("cidr", plan.cidr).put("dns", plan.dns).put("serverPublicKey", plan.serverPublicKey)
                            .put("peerCount", plan.peerCount).put("revokedCount", plan.revokedCount)))
                }
            }.toString().also { if (it.length > MAX_STORE_CHARS) invalid() }
        }

        internal fun decode(text: String): List<PendingVpsRestore> {
            if (text.isEmpty()) return emptyList()
            if (text.length > MAX_STORE_CHARS) invalid()
            try {
                val tokener = JSONTokener(text)
                val array = tokener.nextValue() as? JSONArray ?: invalid()
                if (tokener.nextClean() != '\u0000' || array.length() > MAX_ATTEMPTS) invalid()
                return (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index).requireFields(FIELDS)
                    val ssh = item.getJSONObject("ssh").requireFields(SSH_FIELDS)
                    val plan = item.getJSONObject("plan").requireFields(PLAN_FIELDS)
                    PendingVpsRestore(item.text("operationId"), item.text("serverId"),
                        VpsEndpoint(ssh.text("host"), ssh.integer("port"), ssh.text("user")), item.text("fingerprint"), item.text("ciphertextSha256"),
                        VpsRestoreCheckPlan(plan.text("interface"), plan.text("endpoint"), plan.text("port"), plan.text("cidr"), plan.text("dns"),
                            plan.text("serverPublicKey"), plan.integer("peerCount"), plan.integer("revokedCount")))
                }.also(::validateAttempts)
            } catch (_: JSONException) {
                invalid()
            }
        }

        private fun validateAttempts(attempts: List<PendingVpsRestore>) {
            if (attempts.size > MAX_ATTEMPTS) invalid()
            attempts.forEach { it.validate() }
            if (attempts.map { it.serverId }.distinct().size != attempts.size || attempts.map { it.ssh }.distinct().size != attempts.size ||
                attempts.map { it.operationId }.distinct().size != attempts.size) invalid()
        }

        private fun JSONObject.requireFields(fields: Set<String>): JSONObject = apply { if (keys().asSequence().toSet() != fields) invalid() }
        private fun JSONObject.text(name: String): String = get(name) as? String ?: invalid()
        private fun JSONObject.integer(name: String): Int = get(name) as? Int ?: invalid()
        private fun invalid(): Nothing = throw IOException("Pending server restoration record is invalid")
    }
}
