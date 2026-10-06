/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.provisioning

object VpsDeployFixtureData {
    const val NOW: Long = 1_710_000_000L

    fun storedServers(): List<StoredVpsServer> = listOf(
        storedServer(
            id = "ams-01",
            provider = "Hetzner",
            region = "Amsterdam",
            os = "Ubuntu",
            version = "24.04",
            engine = "kernel",
            endpoint = "203.0.113.42",
            cidr = "10.8.0.0/24",
            dns = "1.1.1.1, 9.9.9.9",
            lastSyncEpochSeconds = NOW - 18L,
        ),
        storedServer(
            id = "fra-01",
            provider = "Netcup",
            region = "Frankfurt",
            os = "Debian",
            version = "12",
            engine = "userspace",
            endpoint = "",
            cidr = "",
            dns = "9.9.9.9",
            lastSyncEpochSeconds = 0L,
        ),
        storedServer(
            id = "nyc-01",
            provider = "DigitalOcean",
            region = "New York",
            os = "Ubuntu",
            version = "22.04",
            engine = "kernel",
            endpoint = "192.0.2.88",
            cidr = "10.10.0.0/24",
            dns = "AdGuard",
            lastSyncEpochSeconds = NOW - 1_440L,
        ),
    )

    fun onlineStatusWithPeers(): VpsServerStatus = VpsServerStatus(
        interfaceName = "wg0",
        running = true,
        endpoint = "203.0.113.42",
        listenPort = "51820",
        cidr = "10.8.0.0/24",
        dns = "1.1.1.1, 9.9.9.9",
        peers = listOf(
            VpsPeerStatus(
                name = "Guillaume Phone",
                publicKey = "o3JbN8xYQhz9npgJd9a2kQw4lG1c6XmEwLq9bF4hHgA=",
                allowedIps = "10.8.0.2/32",
                latestHandshake = NOW - 12L,
                rxBytes = 1_975_000_000L,
                txBytes = 284_000_000L,
            ),
            VpsPeerStatus(
                name = "Work Laptop",
                publicKey = "y1HcL7kTeG4z2Sw9Aj6KpBq8qLa0mR3dFp5nTxVc8xM=",
                allowedIps = "10.8.0.3/32",
                latestHandshake = NOW - 11_880L,
                rxBytes = 620_000_000L,
                txBytes = 91_000_000L,
            ),
        ),
    )

    private fun storedServer(
        id: String,
        provider: String,
        region: String,
        os: String,
        version: String,
        engine: String,
        endpoint: String,
        cidr: String,
        dns: String,
        lastSyncEpochSeconds: Long,
    ) = StoredVpsServer(
        id = id,
        name = id,
        host = endpoint.ifBlank { "198.51.100.17" },
        sshPort = 22,
        sshUser = "root",
        endpoint = endpoint,
        listenPort = "51820",
        cidr = cidr,
        dns = dns,
        os = os,
        version = version,
        provider = provider,
        region = region,
        engine = engine,
        lastSyncEpochSeconds = lastSyncEpochSeconds,
        running = if (lastSyncEpochSeconds > 0L) true else null,
    )
}
