package com.vpn0wn.android.viewmodel

import android.app.Application
import com.vpn0wn.config.Attribute
import com.vpn0wn.config.BadConfigException
import com.vpn0wn.config.InetNetwork
import com.vpn0wn.config.Config as TunnelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PeerProxyTest {
    @Test
    fun mixedDnsExclusionRoutesOnlyTheNumericIpv4Resolver() {
        val config = profile("10.0.0.53, 2001:db8::53, corp.example")
        val original = config.`interface`.resolve()
        val peer = config.peers.single()

        peer.setExcludingPrivateIps(true)

        val networks = Attribute.split(peer.allowedIps).toSet()
        assertTrue("10.0.0.53/32" in networks)
        assertTrue(networks.map { InetNetwork.parse(it) }.contains(InetNetwork.parse("::/0")))
        assertFalse("corp.example/32" in networks)
        assertFalse(networks.map { InetNetwork.parse(it) }.contains(InetNetwork.parse("2001:db8::53/128")))
        val resolved = config.resolve().getInterface()
        assertEquals(original.dnsServers, resolved.dnsServers)
        assertEquals(original.dnsSearchDomains, resolved.dnsSearchDomains)
    }

    @Test
    fun domainOnlyDnsSurvivesExclusionAndEditorRoundTrips() {
        val config = profile("corp.example, localhost, 127.0.0.1.invalid")
        val peer = config.peers.single()
        val originalAllowedIps = peer.allowedIps

        peer.setExcludingPrivateIps(true)
        assertFalse(Attribute.split(peer.allowedIps).any { it.endsWith("/32") })
        val restored = ConfigProxy(config.resolve()).resolve().getInterface()
        assertTrue(restored.dnsServers.isEmpty())
        assertEquals(setOf("corp.example", "localhost", "127.0.0.1.invalid"), restored.dnsSearchDomains)

        peer.setExcludingPrivateIps(false)
        assertEquals(originalAllowedIps, peer.allowedIps)
        config.resolve()
    }

    @Test
    fun changingDnsWhileExcludingReplacesOnlyDerivedIpv4Routes() {
        val config = profile("10.0.0.53, old.example")
        val peer = config.peers.single()
        peer.setExcludingPrivateIps(true)

        config.`interface`.dnsServers = "192.168.1.53, 2001:db8::53, new.example"

        val networks = Attribute.split(peer.allowedIps).toSet()
        assertFalse("10.0.0.53/32" in networks)
        assertTrue("192.168.1.53/32" in networks)
        assertFalse("old.example/32" in networks)
        assertFalse("new.example/32" in networks)
        assertTrue(networks.map { InetNetwork.parse(it) }.contains(InetNetwork.parse("::/0")))
        assertEquals(setOf("new.example"), config.resolve().getInterface().dnsSearchDomains)

        config.`interface`.dnsServers = ""
        assertFalse("192.168.1.53/32" in Attribute.split(peer.allowedIps))
        config.resolve()
    }

    @Test
    fun disablingExclusionRemovesDerivedResolverRoute() {
        val config = profile("10.0.0.53, corp.example")
        val peer = config.peers.single()
        val originalAllowedIps = peer.allowedIps

        peer.setExcludingPrivateIps(true)
        peer.setExcludingPrivateIps(false)

        assertEquals(originalAllowedIps, peer.allowedIps)
        assertEquals(setOf("corp.example"), config.resolve().getInterface().dnsSearchDomains)
    }

    @Test
    fun invalidDnsDraftRetainsTheDnsFieldFailure() {
        val config = profile("10.0.0.53")
        val peer = config.peers.single()
        peer.setExcludingPrivateIps(true)

        config.`interface`.dnsServers = "bad domain"

        assertFalse(Attribute.split(peer.allowedIps).any { it.contains("bad domain") })
        val failure = assertThrows(BadConfigException::class.java) { config.resolve() }
        assertEquals(BadConfigException.Section.INTERFACE, failure.section)
        assertEquals(BadConfigException.Location.DNS, failure.location)
    }

    private fun profile(dns: String) = ConfigProxy(TunnelConfig.parse("""
        [Interface]
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        Address = 192.0.2.2/32
        DNS = $dns
        [Peer]
        PublicKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=
        AllowedIPs = 0.0.0.0/0, ::/0
    """.trimIndent().byteInputStream()))
}
