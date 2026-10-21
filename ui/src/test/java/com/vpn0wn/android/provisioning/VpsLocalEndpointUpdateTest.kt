/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.app.Application
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.model.ObservableTunnel
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.config.Config
import com.vpn0wn.config.InetEndpoint
import com.vpn0wn.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class VpsLocalEndpointUpdateTest {
    @get:Rule val folder = TemporaryFolder()
    private val client = VpsProfileFixture.client
    private val serverKey = VpsProfileFixture.serverKey.publicKey
    private val otherKey = KeyPair().publicKey

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun endpointChangePreservesEntireInterfacePeerOptionsAndUnrelatedTunnels() = runTest {
        for (included in listOf(false, true)) withTunnel(profile(included = included)) { env, tunnel, original ->
            val unrelated = env.manager.create("unrelated", TunnelTestEnvironment.profile())
            val unrelatedConfig = unrelated.peekConfig()
            val endpoint = InetEndpoint.parse("198.51.100.25:51821")
            val update = create(tunnel, original, endpoint)
            val actual = update.apply(env.manager) { }
            assertSame(original.`interface`, actual.`interface`)
            assertSame(original.peers[0], actual.peers[0])
            val oldPeer = original.peers[1]
            val newPeer = actual.peers[1]
            assertEquals(oldPeer.publicKey, newPeer.publicKey)
            assertEquals(oldPeer.preSharedKey, newPeer.preSharedKey)
            assertEquals(oldPeer.allowedIps, newPeer.allowedIps)
            assertEquals(oldPeer.persistentKeepalive, newPeer.persistentKeepalive)
            assertEquals(endpoint, newPeer.endpoint.get())
            assertEquals(original.peers.size, actual.peers.size)
            assertSame(actual, tunnel.peekConfig())
            assertSame(actual, env.store.configs[tunnel.name])
            assertSame(unrelatedConfig, unrelated.peekConfig())
            assertEquals(Tunnel.State.DOWN, tunnel.state)
            assertEquals(Tunnel.State.DOWN, env.backend.getState(tunnel))
            assertEquals("selected", update.tunnelName)
            assertEquals(serverKey.toBase64(), update.serverPublicKey)
            assertEquals(client.publicKey.toBase64(), update.clientPublicKey)
            assertEquals("203.0.113.10:51820", update.oldEndpoint.toString())
            assertEquals(endpoint, update.newEndpoint)
        }
    }

    @Test fun bracketedIpv6EndpointsRemainValidWithoutChangingOtherFields() = runTest {
        withTunnel(profile("[2001:db8::10]:51820")) { env, tunnel, original ->
            val endpoint = InetEndpoint.parse("[2001:db8::25]:51821")
            val update = create(tunnel, original, endpoint)
            val actual = update.apply(env.manager) { }
            assertEquals("[2001:db8::10]:51820", update.oldEndpoint.toString())
            assertEquals("[2001:db8::25]:51821", update.newEndpoint.toString())
            assertSame(original.`interface`, actual.`interface`)
            assertSame(original.peers[0], actual.peers[0])
            assertEquals(endpoint, actual.peers[1].endpoint.get())
        }
    }

    @Test fun bothKeysAndUniqueConfiguredServerPeerAreRequired() = runTest {
        withTunnel(profile()) { _, tunnel, original ->
            val endpoint = InetEndpoint.parse("198.51.100.25:51821")
            assertReason(VpsLocalEndpointUpdate.Reason.CLIENT_IDENTITY) {
                VpsLocalEndpointUpdate.create(tunnel, original, serverKey.toBase64(), otherKey.toBase64(), endpoint)
            }
            assertReason(VpsLocalEndpointUpdate.Reason.SERVER_IDENTITY) {
                VpsLocalEndpointUpdate.create(tunnel, original, KeyPair().publicKey.toBase64(), client.publicKey.toBase64(), endpoint)
            }
            val duplicate = Config.Builder().setInterface(original.`interface`).addPeers(original.peers).addPeer(original.peers[1]).build()
            assertReason(VpsLocalEndpointUpdate.Reason.SERVER_IDENTITY) { create(tunnel, duplicate, endpoint) }
            val unmatched = Config.Builder().setInterface(original.`interface`).addPeer(original.peers[0]).build()
            assertReason(VpsLocalEndpointUpdate.Reason.SERVER_IDENTITY) { create(tunnel, unmatched, endpoint) }
            assertReason(VpsLocalEndpointUpdate.Reason.CLIENT_IDENTITY) {
                VpsLocalEndpointUpdate.create(tunnel, original, serverKey.toBase64(), "invalid", endpoint)
            }
            assertReason(VpsLocalEndpointUpdate.Reason.SERVER_IDENTITY) {
                VpsLocalEndpointUpdate.create(tunnel, original, "invalid", client.publicKey.toBase64(), endpoint)
            }
        }
    }

    @Test fun missingHostnameAndZeroPortEndpointsHaveTypedReviewRefusals() = runTest {
        withTunnel(profile()) { _, tunnel, original ->
            val endpoint = InetEndpoint.parse("198.51.100.25:51821")
            assertReason(VpsLocalEndpointUpdate.Reason.MISSING_ENDPOINT) { create(tunnel, profile(null), endpoint) }
            assertReason(VpsLocalEndpointUpdate.Reason.NON_LITERAL_ENDPOINT) { create(tunnel, profile("vpn.example:51820"), endpoint) }
            assertReason(VpsLocalEndpointUpdate.Reason.INVALID_PORT) { create(tunnel, profile("203.0.113.10:0"), endpoint) }
            assertReason(VpsLocalEndpointUpdate.Reason.NON_LITERAL_ENDPOINT) {
                create(tunnel, original, InetEndpoint.parse("vpn.example:51821"))
            }
            assertReason(VpsLocalEndpointUpdate.Reason.INVALID_PORT) { create(tunnel, original, InetEndpoint.parse("198.51.100.25:0")) }
        }
    }

    @Test fun alreadyMatchingEndpointAvoidsWritesAndReviewValueNeverStringifiesPrivateState() = runTest {
        withTunnel(profile()) { env, tunnel, original ->
            val update = create(tunnel, original, original.peers[1].endpoint.get())
            env.store.failSave = true
            env.backend.failDown = true
            assertSame(original, update.apply(env.manager) { })
            assertSame(original, tunnel.peekConfig())
            assertSame(original, env.store.configs[tunnel.name])
            val text = update.toString()
            assertEquals("VpsLocalEndpointUpdate(redacted)", text)
            assertFalse(text.contains(client.privateKey.toBase64()))
            assertFalse(text.contains(VpsProfileFixture.psk.toBase64()))
            assertFalse(text.contains("[Interface]"))
        }
    }

    private fun create(tunnel: ObservableTunnel, config: Config, endpoint: InetEndpoint) =
        VpsLocalEndpointUpdate.create(tunnel, config, serverKey.toBase64(), client.publicKey.toBase64(), endpoint)

    private fun assertReason(reason: VpsLocalEndpointUpdate.Reason, action: () -> Unit) {
        val failure = assertThrows(VpsLocalEndpointUpdate.RejectedException::class.java) { action() }
        assertEquals(reason, failure.reason)
        assertEquals("Local tunnel endpoint update is unavailable", failure.message)
    }

    private suspend fun TestScope.withTunnel(config: Config, block: suspend (TunnelTestEnvironment, ObservableTunnel, Config) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.newFolder(), backgroundScope)
        try {
            block(env, env.manager.create("selected", config), config)
        } finally {
            env.close()
        }
    }

    private fun profile(endpoint: String? = "203.0.113.10:51820", included: Boolean = false): Config = Config.parse(buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = ${client.privateKey.toBase64()}")
        appendLine("Address = 10.8.0.2/32, fd00::2/128")
        appendLine("DNS = 1.1.1.1, vpn.example")
        appendLine("ListenPort = 35000")
        appendLine("MTU = 1420")
        appendLine("${if (included) "IncludedApplications" else "ExcludedApplications"} = com.example.one, com.example.two")
        appendLine("[Peer]")
        appendLine("PublicKey = ${otherKey.toBase64()}")
        appendLine("AllowedIPs = 192.0.2.0/24")
        appendLine("[Peer]")
        appendLine("PublicKey = ${serverKey.toBase64()}")
        appendLine("PresharedKey = ${VpsProfileFixture.psk.toBase64()}")
        appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
        appendLine("PersistentKeepalive = 25")
        endpoint?.let { appendLine("Endpoint = $it") }
    }.byteInputStream())
}
