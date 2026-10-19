/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import com.vpn0wn.android.model.ObservableTunnel
import com.vpn0wn.android.model.TunnelManager
import com.vpn0wn.config.Config
import com.vpn0wn.config.InetAddresses
import com.vpn0wn.config.InetEndpoint
import com.vpn0wn.config.ParseException
import com.vpn0wn.config.Peer
import com.vpn0wn.crypto.Key
import com.vpn0wn.crypto.KeyFormatException

class VpsLocalEndpointUpdate private constructor(
    private val tunnel: ObservableTunnel,
    private val expectedConfig: Config,
    private val config: Config,
    val tunnelName: String,
    val oldEndpoint: InetEndpoint,
    val newEndpoint: InetEndpoint,
    val serverPublicKey: String,
    val clientPublicKey: String,
) {
    enum class Reason { CLIENT_IDENTITY, SERVER_IDENTITY, MISSING_ENDPOINT, NON_LITERAL_ENDPOINT, INVALID_PORT }

    class RejectedException(val reason: Reason) : Exception("Local tunnel endpoint update is unavailable")

    suspend fun apply(manager: TunnelManager, validateAdmission: suspend () -> Unit): Config =
        manager.setTunnelConfigIfDown(tunnel, tunnelName, expectedConfig, config, validateAdmission)

    override fun toString(): String = "VpsLocalEndpointUpdate(redacted)"

    companion object {
        private const val MAX_PORT = 65_535

        // Typed review refusals must not make callers catch programming failures.
        @Suppress("ThrowsCount")
        fun create(tunnel: ObservableTunnel, expectedConfig: Config, expectedServerPublicKey: String, clientPublicKey: String,
            endpoint: InetEndpoint): VpsLocalEndpointUpdate {
            val clientKey = parseKey(clientPublicKey, Reason.CLIENT_IDENTITY)
            if (expectedConfig.`interface`.keyPair.publicKey != clientKey) throw RejectedException(Reason.CLIENT_IDENTITY)
            val serverKey = parseKey(expectedServerPublicKey, Reason.SERVER_IDENTITY)
            val peer = expectedConfig.peers.singleOrNull { it.publicKey == serverKey }
                ?: throw RejectedException(Reason.SERVER_IDENTITY)
            val previous = peer.endpoint.orElse(null) ?: throw RejectedException(Reason.MISSING_ENDPOINT)
            requireNumericEndpoint(previous)
            requireNumericEndpoint(endpoint)
            val replacement = Peer.Builder().setPublicKey(peer.publicKey).addAllowedIps(peer.allowedIps).setEndpoint(endpoint).apply {
                peer.preSharedKey.ifPresent { setPreSharedKey(it) }
                peer.persistentKeepalive.ifPresent { setPersistentKeepalive(it) }
                peer.amneziaKeepalive.ifPresent { parseAmneziaKeepalive(it) }
            }.build()
            val updated = if (previous == endpoint) expectedConfig else Config.Builder().setInterface(expectedConfig.`interface`)
                .addPeers(expectedConfig.peers.map { if (it === peer) replacement else it }).build()
            return VpsLocalEndpointUpdate(tunnel, expectedConfig, updated, tunnel.name, previous, endpoint,
                serverKey.toBase64(), clientKey.toBase64())
        }

        private fun parseKey(value: String, reason: Reason): Key = try {
            Key.fromBase64(value)
        } catch (_: KeyFormatException) {
            throw RejectedException(reason)
        }

        private fun requireNumericEndpoint(endpoint: InetEndpoint) {
            if (endpoint.port !in 1..MAX_PORT) throw RejectedException(Reason.INVALID_PORT)
            try {
                InetAddresses.parse(endpoint.host)
            } catch (_: ParseException) {
                throw RejectedException(Reason.NON_LITERAL_ENDPOINT)
            }
        }
    }
}
