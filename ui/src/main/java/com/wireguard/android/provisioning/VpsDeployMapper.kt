/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.provisioning

import com.wireguard.android.util.QuantityFormatter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.max

object VpsDeployMapper {
    fun fromStored(server: StoredVpsServer, nowEpochSeconds: Long): VpsDeployServer {
        val status = when {
            server.endpoint.isBlank() || server.cidr.isBlank() -> VpsDeployServerStatus.PROVISIONING
            server.lastSyncEpochSeconds == 0L -> VpsDeployServerStatus.OFFLINE
            nowEpochSeconds - server.lastSyncEpochSeconds > STALE_SYNC_SECONDS -> VpsDeployServerStatus.ATTENTION
            else -> VpsDeployServerStatus.ONLINE
        }
        val version = server.version.ifBlank { "pending sync" }
        return VpsDeployServer(
            id = server.id,
            name = server.name,
            provider = server.provider.ifBlank { server.sshUser },
            region = server.region.ifBlank { server.host },
            endpoint = server.endpoint.ifBlank { server.host },
            port = server.listenPort.toIntOrNull() ?: 51820,
            status = status,
            distro = listOf(server.os, server.version).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Awaiting detect" },
            version = version,
            engine = server.engine.ifBlank { "unknown" },
            cidr = server.cidr.ifBlank { "—" },
            dns = server.dns.ifBlank { "—" },
            uptime = "—",
            lastSync = formatAgo(server.lastSyncEpochSeconds, nowEpochSeconds),
            health = healthFor(server, status),
        )
    }

    fun fromStatus(
        stored: StoredVpsServer,
        status: VpsServerStatus,
        nowEpochSeconds: Long,
    ): VpsDeployServer {
        val deployStatus = if (status.running) VpsDeployServerStatus.ONLINE else VpsDeployServerStatus.ATTENTION
        return fromStored(
            stored.copy(
                endpoint = status.endpoint,
                listenPort = status.listenPort,
                cidr = status.cidr,
                dns = status.dns,
                lastSyncEpochSeconds = nowEpochSeconds,
            ),
            nowEpochSeconds,
        ).copy(
            status = deployStatus,
            engine = stored.engine.ifBlank { status.interfaceName },
            users = status.peers.mapIndexed { index, peer ->
                peer.toDeployUser(index, nowEpochSeconds)
            }.toMutableList(),
            health = listOf(
                VpsDeployHealthCheck("wg running", status.running),
                VpsDeployHealthCheck("endpoint", status.endpoint.isNotBlank()),
                VpsDeployHealthCheck("peers synced", true),
            ),
        )
    }

    private fun VpsPeerStatus.toDeployUser(index: Int, nowEpochSeconds: Long): VpsDeployUser {
        val handshakeAge = if (latestHandshake > 0L) max(0L, nowEpochSeconds - latestHandshake) else Long.MAX_VALUE
        return VpsDeployUser(
            id = publicKey.ifBlank { "peer-$index" },
            name = name.ifBlank { "Device ${(index + 1).toString().padStart(2, '0')}" },
            device = name.ifBlank { "device" },
            ip = allowedIps.substringBefore(',').ifBlank { "—" },
            allowedIps = allowedIps.ifBlank { "0.0.0.0/0, ::/0" },
            status = when {
                latestHandshake <= 0L -> VpsDeployUserStatus.IDLE
                handshakeAge > STALE_HANDSHAKE_SECONDS -> VpsDeployUserStatus.IDLE
                else -> VpsDeployUserStatus.ACTIVE
            },
            rx = QuantityFormatter.formatBytes(rxBytes),
            tx = QuantityFormatter.formatBytes(txBytes),
            handshake = if (latestHandshake <= 0L) "—" else formatDuration(handshakeAge),
            created = DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochSecond(latestHandshake.coerceAtLeast(0L)).atZone(ZoneOffset.UTC)),
            key = publicKey,
        )
    }

    private fun healthFor(server: StoredVpsServer, status: VpsDeployServerStatus): List<VpsDeployHealthCheck> = listOf(
        VpsDeployHealthCheck("server record", true),
        VpsDeployHealthCheck("endpoint", server.endpoint.isNotBlank()),
        VpsDeployHealthCheck(
            "recent sync",
            when (status) {
                VpsDeployServerStatus.ONLINE -> true
                VpsDeployServerStatus.PROVISIONING -> null
                else -> false
            },
        ),
    )

    private fun formatAgo(epochSeconds: Long, nowEpochSeconds: Long): String {
        if (epochSeconds <= 0L) return "—"
        return formatDuration(max(0L, nowEpochSeconds - epochSeconds))
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds < 60L) return "${seconds}s"
        val minutes = seconds / 60L
        val remainingSeconds = seconds % 60L
        if (minutes < 60L) return "${minutes.toString().padStart(2, '0')}m ${remainingSeconds.toString().padStart(2, '0')}s"
        val hours = minutes / 60L
        val remainingMinutes = minutes % 60L
        return "${hours.toString().padStart(2, '0')}h ${remainingMinutes.toString().padStart(2, '0')}m"
    }

    private const val STALE_SYNC_SECONDS = 600L
    private const val STALE_HANDSHAKE_SECONDS = 180L
}
