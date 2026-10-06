/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.provisioning

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.wireguard.android.Application
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
