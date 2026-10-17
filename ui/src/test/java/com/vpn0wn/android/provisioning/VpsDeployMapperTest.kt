/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.provisioning

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.vpn0wn.android.Application
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class VpsDeployMapperTest {
    @Test
    fun storedServerWithoutSyncMapsToUnknownUiState() {
        val server = VpsDeployMapper.fromStored(storedServer(lastSyncEpochSeconds = 0L), NOW)

        assertEquals(VpsDeployServerStatus.ATTENTION, server.status)
        assertEquals("Hetzner", server.provider)
        assertEquals("Amsterdam", server.region)
        assertEquals("kernel", server.engine)
        assertEquals("—", server.lastSync)
        assertEquals("203.0.113.42", server.endpoint)
        assertEquals(51820, server.port)
    }

    @Test
    fun missingVpnEndpointNeverUsesSshHost() {
        val stored = storedServer(lastSyncEpochSeconds = 0L).copy(host = "192.0.2.10", endpoint = "")
        val server = VpsDeployMapper.fromStored(stored, NOW)

        assertEquals(null, server.endpoint)
        assertEquals(51820, server.port)
        assertEquals(null, server.endpointAddress)
        assertEquals(false, server.health.first { it.label == "endpoint" }.ok)
        assertEquals(VpsDeployServerStatus.PROVISIONING, server.status)
    }

    @Test
    fun unknownProviderAndRegionNeverUseSshIdentity() {
        val stored = storedServer(lastSyncEpochSeconds = 0L).copy(provider = "", region = "", host = "192.0.2.10", sshUser = "operator")
        val server = VpsDeployMapper.fromStored(stored, NOW)

        assertEquals("—", server.provider)
        assertEquals("—", server.region)
        assertEquals("192.0.2.10", stored.host)
        assertEquals("operator", stored.sshUser)
    }

    @Test
    fun missingMalformedAndOutOfRangeListenersStayUnknown() {
        for (port in listOf("", " ", "invalid", "0", "-1", "65536", "2147483648")) {
            val server = VpsDeployMapper.fromStored(storedServer(lastSyncEpochSeconds = NOW).copy(listenPort = port), NOW)

            assertEquals("Listener $port", null, server.port)
            assertEquals("Endpoint socket requires a known listener", null, server.endpointAddress)
            assertEquals("203.0.113.42", server.endpoint)
        }
    }

    @Test
    fun observedEndpointAndNonDefaultListenerAreIndependentOfSshAddress() {
        for (port in listOf(1, 51821, 65535)) {
            val stored = storedServer(lastSyncEpochSeconds = NOW).copy(host = "192.0.2.10", listenPort = port.toString())
            val server = VpsDeployMapper.fromStored(stored, NOW)

            assertEquals("203.0.113.42", server.endpoint)
            assertEquals(port, server.port)
            assertEquals("203.0.113.42:$port", server.endpointAddress)
        }
    }

    @Test
    fun endpointAddressBracketsIpv6OnceWithoutChangingObservedHost() {
        for ((endpoint, expected) in listOf(
            "2001:db8:1234:5678::42" to "[2001:db8:1234:5678::42]:51821",
            "[2001:db8:1234:5678::42]" to "[2001:db8:1234:5678::42]:51821",
            "fe80::42%wlan0" to "[fe80::42%wlan0]:51821",
            "[fe80::42%wlan0]" to "[fe80::42%wlan0]:51821",
        )) {
            val stored = storedServer(lastSyncEpochSeconds = NOW).copy(endpoint = endpoint, listenPort = "51821")
            val server = VpsDeployMapper.fromStored(stored, NOW)
            assertEquals(expected, server.endpointAddress)
            assertEquals("Observed spelling retained", endpoint, server.endpoint)
            assertEquals("Stored host retained", endpoint, stored.endpoint)
        }
    }

    @Test
    fun endpointAddressPreservesIpv4HostnamesAndUnknownFields() {
        val server = VpsDeployMapper.fromStored(storedServer(lastSyncEpochSeconds = NOW), NOW)
        for (endpoint in listOf("203.0.113.42", "VPN.example", "vpn.example.")) {
            assertEquals("$endpoint:51820", server.copy(endpoint = endpoint).endpointAddress)
        }
        assertEquals(null, server.copy(endpoint = null).endpointAddress)
        assertEquals(null, server.copy(port = null).endpointAddress)
        assertEquals(null, server.copy(endpoint = null, port = null).endpointAddress)
    }

    @Test
    fun refreshReplacesSavedMetadataIncludingStoppedAndUnknownValues() {
        val stored = storedServer(lastSyncEpochSeconds = NOW - 18L).copy(host = "192.0.2.10", running = true)
        for (running in listOf(true, false, null)) {
            for ((endpoint, port) in listOf("vpn.example" to "51821", "" to "", "vpn.example" to "invalid")) {
                val status = VpsDeployFixtureData.onlineStatusWithPeers().copy(running = running, endpoint = endpoint, listenPort = port)
                val refreshed = VpsDeployMapper.fromStatus(stored, status, NOW)
                val persisted = VpsDeployMapper.fromStored(stored.copy(endpoint = endpoint, listenPort = port,
                    cidr = status.cidr, dns = status.dns, running = running, lastSyncEpochSeconds = NOW), NOW)

                assertEquals(persisted.endpoint, refreshed.endpoint)
                assertEquals(persisted.port, refreshed.port)
                assertEquals(persisted.endpointAddress, refreshed.endpointAddress)
                assertEquals(persisted.status, refreshed.status)
                assertEquals(persisted.health, refreshed.health)
            }
        }
    }

    @Test
    fun recentlySyncedStoredServerMapsToOnlineUiState() {
        val server = VpsDeployMapper.fromStored(storedServer(lastSyncEpochSeconds = NOW - 18L).copy(running = true), NOW)

        assertEquals(VpsDeployServerStatus.ONLINE, server.status)
        assertEquals("18s", server.lastSync)
        assertEquals(true, server.health.first { it.label == "recent sync" }.ok)
    }

    @Test
    fun staleStoredServerMapsToAttentionUiState() {
        val server = VpsDeployMapper.fromStored(storedServer(lastSyncEpochSeconds = NOW - 700L), NOW)

        assertEquals(VpsDeployServerStatus.ATTENTION, server.status)
        assertEquals(false, server.health.first { it.label == "recent sync" }.ok)
    }

    @Test
    fun statusResultMapsPeersToUsers() {
        val server = VpsDeployMapper.fromStatus(
            stored = storedServer(lastSyncEpochSeconds = 0L),
            status = VpsServerStatus(
                interfaceName = "wg0",
                running = true,
                endpoint = "203.0.113.42",
                listenPort = "51820",
                cidr = "10.8.0.0/24",
                dns = "1.1.1.1",
                peers = listOf(
                    VpsPeerStatus(
                        name = "phone",
                        publicKey = "abc",
                        allowedIps = "10.8.0.2/32",
                        latestHandshake = NOW - 12L,
                        rxBytes = 1024L * 1024L,
                        txBytes = 2048L,
                    ),
                ),
            ),
            nowEpochSeconds = NOW,
        )

        assertEquals(VpsDeployServerStatus.ONLINE, server.status)
        assertEquals(1, server.users.size)
        assertEquals("phone", server.users.single().name)
        assertEquals(VpsDeployUserStatus.ACTIVE, server.users.single().status)
        assertEquals("12s", server.users.single().handshake)
        assertEquals("1.00 MiB", server.users.single().rx)
    }

    @Test
    fun statusDoesNotInventEngineFactsFromInterfaceOrOsRelease() {
        val status = VpsDeployFixtureData.onlineStatusWithPeers().copy(interfaceName = "wg0")
        for ((knownEngine, expectedEngine) in listOf("" to "unknown", "kernel" to "kernel", "WireGuard" to "WireGuard")) {
            val stored = storedServer(lastSyncEpochSeconds = 0L).copy(engine = knownEngine)
            val server = VpsDeployMapper.fromStatus(stored, status, NOW)
            assertEquals(expectedEngine, server.engine)
            assertEquals("Ubuntu 24.04", server.distro)
        }
    }

    @Test
    fun fixtureSetCoversFleetVisualStates() {
        val servers = VpsDeployFixtureData.storedServers().map {
            VpsDeployMapper.fromStored(it, VpsDeployFixtureData.NOW)
        }

        assertEquals(
            listOf(
                VpsDeployServerStatus.ONLINE,
                VpsDeployServerStatus.PROVISIONING,
                VpsDeployServerStatus.ATTENTION,
            ),
            servers.map { it.status },
        )
    }

    @Test
    fun freshLegacyRecordCannotProveOnline() {
        val server = VpsDeployMapper.fromStored(storedServer(lastSyncEpochSeconds = NOW - 18L), NOW)
        assertEquals(VpsDeployServerStatus.ATTENTION, server.status)
    }

    @Test
    fun futureTimestampCannotProveOnline() {
        val server = VpsDeployMapper.fromStored(storedServer(lastSyncEpochSeconds = NOW + 1L).copy(running = true), NOW)
        assertEquals(VpsDeployServerStatus.ATTENTION, server.status)
    }

    @Test
    fun stoppedUnknownAndStaleHealthAreIndependentOfFreshness() {
        val fresh = storedServer(lastSyncEpochSeconds = NOW - 18L)
        val stopped = VpsDeployMapper.fromStored(fresh.copy(running = false), NOW)
        assertEquals(VpsDeployServerStatus.OFFLINE, stopped.status)
        assertEquals(true, stopped.health.first { it.label == "recent sync" }.ok)
        assertEquals(false, stopped.health.first { it.label == "wg running" }.ok)
        val unknown = VpsDeployMapper.fromStored(fresh, NOW)
        assertEquals(VpsDeployServerStatus.ATTENTION, unknown.status)
        assertEquals(true, unknown.health.first { it.label == "recent sync" }.ok)
        assertEquals(null, unknown.health.first { it.label == "wg running" }.ok)
        for (running in listOf(true, false, null)) {
            val stale = VpsDeployMapper.fromStored(fresh.copy(running = running, lastSyncEpochSeconds = NOW - 601L), NOW)
            assertEquals(VpsDeployServerStatus.ATTENTION, stale.status)
            assertEquals(false, stale.health.first { it.label == "recent sync" }.ok)
            assertEquals(null, stale.health.first { it.label == "wg running" }.ok)
        }
        assertEquals(VpsDeployServerStatus.ONLINE, VpsDeployMapper.fromStored(fresh.copy(running = true, lastSyncEpochSeconds = NOW - 600L), NOW).status)
    }

    @Test
    fun liveAndPersistedProjectionsAgreeAndUnknownCountersStayUnknown() {
        val base = storedServer(lastSyncEpochSeconds = 0L)
        for (running in listOf(true, false, null)) {
            val status = VpsDeployFixtureData.onlineStatusWithPeers().copy(running = running)
            val live = VpsDeployMapper.fromStatus(base, status, NOW)
            val persisted = VpsDeployMapper.fromStored(base.copy(endpoint = status.endpoint, listenPort = status.listenPort, cidr = status.cidr, dns = status.dns, lastSyncEpochSeconds = NOW, running = running), NOW)
            assertEquals(persisted.status, live.status)
            assertEquals(persisted.health, live.health)
        }
        val peer = VpsPeerStatus("unknown", "key", "10.8.0.2/32", null, null, null)
        val status = VpsDeployFixtureData.onlineStatusWithPeers().copy(peers = listOf(peer))
        val user = VpsDeployMapper.fromStatus(base, status, NOW).users.single()
        assertEquals(VpsDeployUserStatus.IDLE, user.status)
        assertEquals("—", user.rx)
        assertEquals("—", user.tx)
        assertEquals("—", user.handshake)
        val future = VpsDeployMapper.fromStatus(base, status.copy(peers = listOf(peer.copy(latestHandshake = NOW + 1L))), NOW).users.single()
        assertEquals(VpsDeployUserStatus.IDLE, future.status)
        assertEquals("—", future.handshake)
    }

    @Test
    fun retainedStatusUsesOriginalObservationTimeAndNeverInventsCreationDate() {
        val stored = storedServer(lastSyncEpochSeconds = 0L)
        val status = VpsDeployFixtureData.onlineStatusWithPeers().copy(peers = listOf(
            VpsPeerStatus("phone", "public-key", "10.8.0.2/32", NOW + 600L, 1L, 2L),
        ))
        val fresh = VpsDeployMapper.fromStatus(stored, status, NOW, NOW)
        assertEquals(VpsDeployServerStatus.ONLINE, fresh.status)
        assertEquals("—", fresh.users.single().created)
        val retained = VpsDeployMapper.fromStatus(stored, status, NOW + 601L, NOW)
        assertEquals(VpsDeployServerStatus.ATTENTION, retained.status)
        assertEquals(VpsDeployUserStatus.IDLE, retained.users.single().status)
        assertEquals(false, retained.health.first { it.label == "recent sync" }.ok)
        assertEquals(null, retained.health.first { it.label == "wg running" }.ok)
        assertEquals(true, retained.usersKnown)
        assertEquals("public-key", retained.users.single().key)
        assertEquals("10m 01s", retained.lastSync)
    }

    @Test
    fun unknownInventoryDiffersFromValidatedEmptyAndStoppedObservations() {
        val stored = storedServer(lastSyncEpochSeconds = 0L)
        assertEquals(false, VpsDeployMapper.fromStored(stored, NOW).usersKnown)
        for (running in listOf(true, false, null)) {
            val status = VpsDeployFixtureData.onlineStatusWithPeers().copy(running = running, peers = emptyList())
            val mapped = VpsDeployMapper.fromStatus(stored, status, NOW, NOW)
            assertEquals(true, mapped.usersKnown)
            assertEquals(emptyList<VpsDeployUser>(), mapped.users)
        }
    }

    private fun storedServer(lastSyncEpochSeconds: Long) = StoredVpsServer(
        id = "ams-01",
        name = "ams-01",
        host = "203.0.113.42",
        sshPort = 22,
        sshUser = "root",
        endpoint = "203.0.113.42",
        listenPort = "51820",
        cidr = "10.8.0.0/24",
        dns = "1.1.1.1, 9.9.9.9",
        os = "Ubuntu",
        version = "24.04",
        provider = "Hetzner",
        region = "Amsterdam",
        engine = "kernel",
        lastSyncEpochSeconds = lastSyncEpochSeconds,
    )

    private companion object {
        private const val NOW = 1_710_000_000L
    }
}
