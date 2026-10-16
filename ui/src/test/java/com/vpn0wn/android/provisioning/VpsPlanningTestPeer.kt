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
                    command.contains("./'plan.sh'") -> {
                        failure?.let { throw it }
                        ProvisioningCommandOutput(0, planJson(), "")
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
        .put("configExists", false).put("interface", "wg0").put("endpoint", detectedEndpoint).put("listenPort", "")
        .put("cidr", "").put("ipv4Forwarding", "0").toString()

    private fun planJson(): String = JSONObject()
        .put("ok", true).put("supported", true).put("endpoint", planRequest.endpoint)
        .put("port", planRequest.port.toString()).put("cidr", planRequest.cidr).put("dns", planRequest.dns)
        .put("willOverwriteConfig", false)
        .put("steps", org.json.JSONArray(listOf("install wireguard iptables qrencode with apt", "enable and start wg-quick@wg0")))
        .toString()
}
