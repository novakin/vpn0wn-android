/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject

internal class VpsPlanningTestPeer : RemoteShellClientFactory {
    val connections = Collections.synchronizedList(mutableListOf<VpsConnection>())
    val commands = Collections.synchronizedList(mutableListOf<String>())
    val closes = AtomicInteger()
    val detectEntered = CompletableDeferred<Unit>()
    val installEntered = CompletableDeferred<Unit>()
    var blockInstall: CompletableDeferred<Unit>? = null
    var installFailure: ProvisioningException? = null
    var installOutput: String? = null
    var installExitCode = 0
    val addEntered = CompletableDeferred<Unit>()
    var blockAdd: CompletableDeferred<Unit>? = null
    var addFailure: ProvisioningException? = null
    var addOutput: String? = null
    var addExitCode = 0
    var afterAdd: (() -> Unit)? = null
    var configExists = false
    var planOverwrites = false
    var statusEntered = CompletableDeferred<Unit>()
    var blockStatus: CompletableDeferred<Unit>? = null
    var statusFailure: ProvisioningException? = null
    var statusOutput: String? = null
    var blockDetect: CompletableDeferred<Unit>? = null
    var failure: ProvisioningException? = null
    var detectOutput: String? = null
    var detectedEndpoint = ""
    var supported = true
    var planRequest = VpsInstallRequest("198.51.100.42", "10.8.0.0/24", "1.1.1.1, 1.0.0.1")

    override fun create(connection: VpsConnection): RemoteShellClient {
        connections.add(connection)
        return object : RemoteShellClient {
            override suspend fun execute(command: String): ProvisioningCommandOutput {
                commands.add(command)
                return when {
                    command.startsWith("mktemp ") -> ProvisioningCommandOutput(0, "/tmp/vpn0wn-provisioning.test01\n", "")
                    command.startsWith("mkdir -p ") -> ProvisioningCommandOutput(0, "", "")
                    command.startsWith("rm -rf ") -> ProvisioningCommandOutput(0, "", "")
                    command.contains("./'detect.sh'") -> {
                        detectEntered.complete(Unit)
                        blockDetect?.await()
                        failure?.let { throw it }
                        ProvisioningCommandOutput(0, detectOutput ?: detectJson(), "")
                    }
                    command.contains("./'sync-status.sh'") -> {
                        statusEntered.complete(Unit)
                        blockStatus?.await()
                        statusFailure?.let { throw it }
                        failure?.let { throw it }
                        ProvisioningCommandOutput(0, statusOutput ?: statusJson(), "")
                    }
                    command.contains("./'plan.sh'") -> {
                        failure?.let { throw it }
                        ProvisioningCommandOutput(0, planJson(), "")
                    }
                    command.contains("./'install.sh'") -> {
                        installEntered.complete(Unit)
                        blockInstall?.await()
                        installFailure?.let { throw it }
                        ProvisioningCommandOutput(installExitCode, installOutput ?: installJson(), "")
                    }
                    command.contains("./'add-peer.sh'") -> {
                        addEntered.complete(Unit)
                        blockAdd?.await()
                        afterAdd?.invoke()
                        addFailure?.let { throw it }
                        ProvisioningCommandOutput(addExitCode, requireNotNull(addOutput), "")
                    }
                    else -> error("Unexpected provisioning command")
                }
            }

            override suspend fun upload(path: String, content: String, mode: String): ProvisioningCommandOutput = ProvisioningCommandOutput(0, "", "")
            override suspend fun close() { closes.incrementAndGet() }
        }
    }

    private fun detectJson(): String = JSONObject()
        .put("ok", true).put("os", "debian").put("version", "12").put("supported", supported)
        .put("packageManager", "apt").put("firewall", "iptables").put("wireGuardInstalled", false)
        .put("configExists", configExists).put("interface", "wg0").put("endpoint", detectedEndpoint).put("listenPort", "")
        .put("cidr", "").put("ipv4Forwarding", "0").toString()

    private fun statusJson(): String = JSONObject()
        .put("ok", true).put("interface", "wg0").put("running", true)
        .put("endpoint", "198.51.100.42").put("listenPort", "51820")
        .put("cidr", "10.8.0.0/24").put("dns", "1.1.1.1, 1.0.0.1")
        .put("peers", org.json.JSONArray().put(JSONObject()
            .put("name", "phone").put("publicKey", "synthetic-peer-public-key")
            .put("allowedIps", "10.8.0.2/32").put("latestHandshake", "1710000000")
            .put("rxBytes", "1024").put("txBytes", "2048")))
        .toString()

    private fun planJson(): String = JSONObject()
        .put("ok", true).put("supported", true).put("endpoint", planRequest.endpoint)
        .put("port", planRequest.port.toString()).put("cidr", planRequest.cidr).put("dns", planRequest.dns)
        .put("willOverwriteConfig", planOverwrites)
        .put("steps", org.json.JSONArray(listOf("install wireguard iptables qrencode with apt", "enable and start wg-quick@wg0")))
        .toString()

    private fun installJson(): String = JSONObject()
        .put("ok", true).put("interface", "wg0").put("endpoint", planRequest.endpoint)
        .put("port", planRequest.port.toString()).put("cidr", planRequest.cidr)
        .put("serverPublicKey", java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }))
        .toString()
}
