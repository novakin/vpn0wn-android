/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.provisioning

import android.util.Log
import org.json.JSONException
import org.json.JSONObject

data class VpsEndpoint(
    val host: String,
    val port: Int = 22,
    val user: String = "root",
)

sealed class VpsCredential {
    class Password(val value: CharArray) : VpsCredential() {
        fun clear() {
            value.fill('\u0000')
        }
    }
    data class PrivateKey(val pem: String, val passphrase: String? = null) : VpsCredential()
}

data class VpsConnection(
    val endpoint: VpsEndpoint,
    val credential: VpsCredential,
    val trustHostKey: Boolean,
    val hostKeyFingerprint: String? = null,
    val maxAuthAttempts: Int = 3,
)

data class ProvisioningCommandOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    fun requireSuccess(): ProvisioningCommandOutput {
        if (exitCode == 0) return this
        Log.d(TAG, "Remote command failed with exit code $exitCode")
        throw ProvisioningException("Remote command failed with exit code $exitCode", this)
    }

    companion object {
        private const val TAG = "WireGuard/Provisioning"
    }
}

enum class ProvisioningFailureCategory(val safeMessage: String) {
    COMMAND_FAILED("Remote command failed"),
    INVALID_RESPONSE("Remote command returned an invalid response"),
    INVALID_INPUT("Provisioning input is invalid"),
    UNSUPPORTED_SERVER("Server operating system is unsupported"),
    PERMISSION_DENIED("Provisioning requires root access"),
    CONFLICT("Server state prevents this operation"),
    NOT_INSTALLED("Managed WireGuard configuration is absent"),
    RUNTIME_UNKNOWN("Server runtime could not be observed"),
    MUTATION_FAILED("Server mutation failed; inspect recovery before retrying; package or forwarding effects may remain"),
    REMOTE_FAILURE("Server rejected the provisioning operation"),
}

enum class ProvisioningRecovery { UNCHANGED, RESTORED, INCOMPLETE, UNKNOWN }

class ProvisioningException(
    message: String,
    val output: ProvisioningCommandOutput? = null,
    cause: Throwable? = null,
    val category: ProvisioningFailureCategory = ProvisioningFailureCategory.COMMAND_FAILED,
    val remoteCode: String? = null,
    val recovery: ProvisioningRecovery? = null,
) : Exception(message, cause)

data class VpsDetectResult(
    val os: String,
    val version: String,
    val supported: Boolean,
    val packageManager: String,
    val firewall: String,
    val wireGuardInstalled: Boolean,
    val configExists: Boolean,
    val interfaceName: String,
    val endpoint: String,
    val listenPort: String,
    val cidr: String,
    val ipv4Forwarding: String,
)

data class VpsPlanResult(
    val endpoint: String,
    val port: String,
    val cidr: String,
    val dns: String,
    val willOverwriteConfig: Boolean,
    val steps: List<String>,
)

data class VpsInstallResult(
    val interfaceName: String,
    val endpoint: String,
    val port: String,
    val cidr: String,
    val serverPublicKey: String,
    val cleanupPath: String = "",
)

data class VpsPeerStatus(
    val name: String,
    val publicKey: String,
    val allowedIps: String,
    val latestHandshake: Long?,
    val rxBytes: Long?,
    val txBytes: Long?,
)

data class VpsServerStatus(
    val interfaceName: String,
    val running: Boolean?,
    val endpoint: String,
    val listenPort: String,
    val cidr: String,
    val dns: String,
    val peers: List<VpsPeerStatus>,
)

data class VpsAddPeerResult(
    val name: String,
    val address: String,
    val publicKey: String,
    val config: String,
    val cleanupPath: String = "",
)

data class VpsPeerMutationResult(
    val name: String,
    val removed: Boolean = false,
    val revoked: Boolean = false,
    val archive: String = "",
    val cleanupPath: String = "",
)

data class VpsBackupResult(
    val interfaceName: String,
    val config: String,
    val revokedCount: Int,
)

data class VpsUninstallResult(
    val uninstalled: Boolean,
    val backup: String,
    val packagesPurged: Boolean,
    val cleanupPath: String = "",
)

object ProvisioningJson {
    fun parseDetect(stdout: String): VpsDetectResult = parse(stdout) { json ->
        VpsDetectResult(
            os = json.string("os"),
            version = json.string("version"),
            supported = json.boolean("supported"),
            packageManager = json.string("packageManager"),
            firewall = json.string("firewall"),
            wireGuardInstalled = json.boolean("wireGuardInstalled"),
            configExists = json.boolean("configExists"),
            interfaceName = json.string("interface"),
            endpoint = json.string("endpoint"),
            listenPort = json.string("listenPort"),
            cidr = json.string("cidr"),
            ipv4Forwarding = json.string("ipv4Forwarding"),
        )
    }

    fun parsePlan(stdout: String): VpsPlanResult = parse(stdout) { json ->
        if (!json.boolean("supported")) throw JSONException("Plan support was not confirmed")
        val steps = json.getJSONArray("steps")
        VpsPlanResult(
            endpoint = json.string("endpoint"),
            port = json.string("port"),
            cidr = json.string("cidr"),
            dns = json.string("dns"),
            willOverwriteConfig = json.boolean("willOverwriteConfig"),
            steps = (0 until steps.length()).map { index ->
                steps.get(index) as? String ?: throw JSONException("Invalid step type")
            },
        )
    }

    fun parseInstall(stdout: String): VpsInstallResult = parse(stdout) { json ->
        VpsInstallResult(
            interfaceName = json.string("interface"),
            endpoint = json.string("endpoint"),
            port = json.string("port"),
            cidr = json.string("cidr"),
            serverPublicKey = json.string("serverPublicKey"),
            cleanupPath = json.optionalString("cleanupPath"),
        )
    }

    fun parseStatus(stdout: String): VpsServerStatus = parse(stdout) { json ->
        val peers = json.getJSONArray("peers")
        VpsServerStatus(
            interfaceName = json.string("interface"),
            running = json.nullableBoolean("running"),
            endpoint = json.string("endpoint"),
            listenPort = json.string("listenPort"),
            cidr = json.string("cidr"),
            dns = json.string("dns"),
            peers = (0 until peers.length()).map { index ->
                val peer = peers.getJSONObject(index)
                VpsPeerStatus(
                    name = peer.string("name"),
                    publicKey = peer.string("publicKey"),
                    allowedIps = peer.string("allowedIps"),
                    latestHandshake = peer.nullableCounter("latestHandshake"),
                    rxBytes = peer.nullableCounter("rxBytes"),
                    txBytes = peer.nullableCounter("txBytes"),
                )
            },
        )
    }

    fun parseAddPeer(stdout: String): VpsAddPeerResult = parse(stdout) { json ->
        VpsAddPeerResult(
            name = json.string("name"),
            address = json.string("address"),
            publicKey = json.string("publicKey"),
            config = json.string("config"),
            cleanupPath = json.optionalString("cleanupPath"),
        )
    }

    fun parsePeerMutation(stdout: String): VpsPeerMutationResult = parse(stdout) { json ->
        val removed = json.boolean("removed")
        if (!removed) throw JSONException("Peer removal was not confirmed")
        val revoked = json.boolean("revoked")
        val archive = if (revoked) json.string("archive") else json.optionalString("archive")
        if (revoked && archive.isBlank()) throw JSONException("Revocation archive was not provided")
        VpsPeerMutationResult(
            name = json.string("name"),
            removed = removed,
            revoked = revoked,
            archive = archive,
            cleanupPath = json.optionalString("cleanupPath"),
        )
    }

    fun parseBackup(stdout: String): VpsBackupResult = parse(stdout) { json ->
        val revokedCount = json.counter("revokedCount")
        if (revokedCount > Int.MAX_VALUE) throw JSONException("Counter out of range")
        VpsBackupResult(
            interfaceName = json.string("interface"),
            config = json.string("config"),
            revokedCount = revokedCount.toInt(),
        )
    }

    fun parseUninstall(stdout: String): VpsUninstallResult = parse(stdout) { json ->
        val uninstalled = json.boolean("uninstalled")
        if (!uninstalled) throw JSONException("Uninstall was not confirmed")
        VpsUninstallResult(
            uninstalled = uninstalled,
            backup = json.string("backup"),
            packagesPurged = json.boolean("packagesPurged"),
            cleanupPath = json.optionalString("cleanupPath"),
        )
    }

    internal fun requireScriptSuccess(output: ProvisioningCommandOutput): ProvisioningCommandOutput {
        if (output.exitCode != 0) {
            val json = try {
                JSONObject(output.stdout.trim())
            } catch (_: JSONException) {
                null
            }
            if (json?.opt("ok") == false) {
                parse(output.stdout) { it }
            }
        }
        return output.requireSuccess()
    }

    private fun <T> parse(stdout: String, read: (JSONObject) -> T): T {
        val json = try {
            JSONObject(stdout.trim())
        } catch (_: JSONException) {
            invalidResponse("Remote command returned invalid JSON")
        }
        try {
            if (!json.boolean("ok")) throw remoteFailure(json)
            return read(json)
        } catch (_: JSONException) {
            invalidResponse()
        }
    }

    private fun remoteFailure(json: JSONObject): ProvisioningException {
        val code = json.string("error")
        json.string("message")
        val category = when (code) {
            "invalid_arg", "missing_arg", "missing_endpoint", "invalid_endpoint",
            "invalid_port", "invalid_dns", "invalid_allowed_ips", "invalid_cidr",
            "missing_name", "invalid_name", "invalid_interface", "confirmation_required" -> ProvisioningFailureCategory.INVALID_INPUT
            "unsupported_os" -> ProvisioningFailureCategory.UNSUPPORTED_SERVER
            "not_root" -> ProvisioningFailureCategory.PERMISSION_DENIED
            "unmanaged_config", "unmanaged_forwarding", "shared_wireguard_state",
            "unmanaged_runtime", "unsafe_path", "config_exists", "peer_exists",
            "peer_not_found", "address_pool_full" -> ProvisioningFailureCategory.CONFLICT
            "not_installed", "missing_server_metadata" -> ProvisioningFailureCategory.NOT_INSTALLED
            "runtime_unknown" -> ProvisioningFailureCategory.RUNTIME_UNKNOWN
            "missing_tool", "lock_failed", "snapshot_failed", "key_failed", "write_failed",
            "invalid_config", "apply_failed", "postcondition_failed", "archive_failed",
            "package_failed", "stop_failed", "forwarding_failed", "enable_failed",
            "disable_failed", "backup_failed", "delete_failed", "purge_failed",
            "purge_unknown", "interrupted", "command_failed" -> ProvisioningFailureCategory.MUTATION_FAILED
            else -> ProvisioningFailureCategory.REMOTE_FAILURE
        }
        val recovery = if (json.has("recovery")) {
            when (json.opt("recovery")) {
                "unchanged" -> ProvisioningRecovery.UNCHANGED
                "restored" -> ProvisioningRecovery.RESTORED
                "incomplete" -> ProvisioningRecovery.INCOMPLETE
                else -> ProvisioningRecovery.UNKNOWN
            }
        } else null
        val message = if (recovery == ProvisioningRecovery.INCOMPLETE || recovery == ProvisioningRecovery.UNKNOWN) {
            "${category.safeMessage}; recovery requires inspection"
        } else category.safeMessage
        return ProvisioningException(
            message,
            category = category,
            remoteCode = code.takeUnless { category == ProvisioningFailureCategory.REMOTE_FAILURE },
            recovery = recovery,
        )
    }

    private fun JSONObject.string(name: String): String =
        get(name) as? String ?: throw JSONException("Invalid string type")

    private fun JSONObject.optionalString(name: String): String =
        if (has(name)) string(name) else ""

    private fun JSONObject.boolean(name: String): Boolean =
        get(name) as? Boolean ?: throw JSONException("Invalid Boolean type")

    private fun JSONObject.nullableBoolean(name: String): Boolean? =
        if (get(name) == JSONObject.NULL) null else boolean(name)

    private fun JSONObject.counter(name: String): Long {
        val value = string(name)
        if (value.isEmpty() || value.any { it !in '0'..'9' }) throw JSONException("Invalid counter")
        return value.toLongOrNull() ?: throw JSONException("Counter out of range")
    }

    private fun JSONObject.nullableCounter(name: String): Long? =
        if (get(name) == JSONObject.NULL) null else counter(name)

    private fun invalidResponse(message: String = ProvisioningFailureCategory.INVALID_RESPONSE.safeMessage): Nothing {
        Log.d(TAG, message)
        throw ProvisioningException(message, category = ProvisioningFailureCategory.INVALID_RESPONSE)
    }

    private const val TAG = "WireGuard/Provisioning"
}
