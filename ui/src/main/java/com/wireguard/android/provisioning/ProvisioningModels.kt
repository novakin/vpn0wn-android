/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.provisioning

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

class ProvisioningException(message: String, val output: ProvisioningCommandOutput? = null, cause: Throwable? = null) : Exception(message, cause)

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
)

data class VpsPeerStatus(
    val name: String,
    val publicKey: String,
    val allowedIps: String,
    val latestHandshake: Long,
    val rxBytes: Long,
    val txBytes: Long,
)

data class VpsServerStatus(
    val interfaceName: String,
    val running: Boolean,
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
)

data class VpsPeerMutationResult(
    val name: String,
    val removed: Boolean = false,
    val revoked: Boolean = false,
    val archive: String = "",
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
)

object ProvisioningJson {
    fun parseDetect(stdout: String): VpsDetectResult {
        val json = requireOk(stdout)
        return VpsDetectResult(
            os = json.optString("os"),
            version = json.optString("version"),
            supported = json.optBoolean("supported"),
            packageManager = json.optString("packageManager"),
            firewall = json.optString("firewall"),
            wireGuardInstalled = json.optBoolean("wireGuardInstalled"),
            configExists = json.optBoolean("configExists"),
            interfaceName = json.optString("interface"),
            endpoint = json.optString("endpoint"),
            listenPort = json.optString("listenPort"),
            cidr = json.optString("cidr"),
            ipv4Forwarding = json.optString("ipv4Forwarding"),
        )
    }

    fun parsePlan(stdout: String): VpsPlanResult {
        val json = requireOk(stdout)
        val stepsJson = json.optJSONArray("steps")
        val steps = buildList {
            if (stepsJson != null) {
                for (index in 0 until stepsJson.length())
                    add(stepsJson.optString(index))
            }
        }
        return VpsPlanResult(
            endpoint = json.optString("endpoint"),
            port = json.optString("port"),
            cidr = json.optString("cidr"),
            dns = json.optString("dns"),
            willOverwriteConfig = json.optBoolean("willOverwriteConfig"),
            steps = steps,
        )
    }

    fun parseInstall(stdout: String): VpsInstallResult {
        val json = requireOk(stdout)
        return VpsInstallResult(
            interfaceName = json.optString("interface"),
            endpoint = json.optString("endpoint"),
            port = json.optString("port"),
            cidr = json.optString("cidr"),
            serverPublicKey = json.optString("serverPublicKey"),
        )
    }

    fun parseStatus(stdout: String): VpsServerStatus {
        val json = requireOk(stdout)
        val peersJson = json.optJSONArray("peers")
        val peers = buildList {
            if (peersJson != null) {
                for (index in 0 until peersJson.length()) {
                    val peer = peersJson.getJSONObject(index)
                    add(
                        VpsPeerStatus(
                            name = peer.optString("name"),
                            publicKey = peer.optString("publicKey"),
                            allowedIps = peer.optString("allowedIps"),
                            latestHandshake = peer.optString("latestHandshake").toLongOrNull() ?: 0L,
                            rxBytes = peer.optString("rxBytes").toLongOrNull() ?: 0L,
                            txBytes = peer.optString("txBytes").toLongOrNull() ?: 0L,
                        ),
                    )
                }
            }
        }
        return VpsServerStatus(
            interfaceName = json.optString("interface"),
            running = json.optBoolean("running"),
            endpoint = json.optString("endpoint"),
            listenPort = json.optString("listenPort"),
            cidr = json.optString("cidr"),
            dns = json.optString("dns"),
            peers = peers,
        )
    }

    fun parseAddPeer(stdout: String): VpsAddPeerResult {
        val json = requireOk(stdout)
        return VpsAddPeerResult(
            name = json.optString("name"),
            address = json.optString("address"),
            publicKey = json.optString("publicKey"),
            config = json.optString("config"),
        )
    }

    fun parsePeerMutation(stdout: String): VpsPeerMutationResult {
        val json = requireOk(stdout)
        return VpsPeerMutationResult(
            name = json.optString("name"),
            removed = json.optBoolean("removed"),
            revoked = json.optBoolean("revoked"),
            archive = json.optString("archive"),
        )
    }

    fun parseBackup(stdout: String): VpsBackupResult {
        val json = requireOk(stdout)
        return VpsBackupResult(
            interfaceName = json.optString("interface"),
            config = json.optString("config"),
            revokedCount = json.optString("revokedCount").toIntOrNull() ?: 0,
        )
    }

    fun parseUninstall(stdout: String): VpsUninstallResult {
        val json = requireOk(stdout)
        return VpsUninstallResult(
            uninstalled = json.optBoolean("uninstalled"),
            backup = json.optString("backup"),
            packagesPurged = json.optBoolean("packagesPurged"),
        )
    }

    private fun requireOk(stdout: String): JSONObject {
        val json = try {
            JSONObject(stdout.trim())
        } catch (_: JSONException) {
            Log.d(TAG, "Remote command returned invalid JSON")
            throw ProvisioningException("Remote command returned invalid JSON")
        }
        if (json.optBoolean("ok")) return json
        throw ProvisioningException("${json.optString("error")}: ${json.optString("message")}")
    }

    private const val TAG = "WireGuard/Provisioning"
}
