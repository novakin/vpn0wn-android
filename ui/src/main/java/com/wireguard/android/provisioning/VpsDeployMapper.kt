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
            !hasRecentSync(server, nowEpochSeconds) -> VpsDeployServerStatus.ATTENTION
            server.running == true -> VpsDeployServerStatus.ONLINE
            server.running == false -> VpsDeployServerStatus.OFFLINE
            else -> VpsDeployServerStatus.ATTENTION
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
            health = healthFor(server, nowEpochSeconds),
        )
    }

    fun fromStatus(
        stored: StoredVpsServer,
        status: VpsServerStatus,
        nowEpochSeconds: Long,
    ): VpsDeployServer {
        return fromStored(
            stored.copy(
                endpoint = status.endpoint,
                listenPort = status.listenPort,
                cidr = status.cidr,
                dns = status.dns,
                lastSyncEpochSeconds = nowEpochSeconds,
                running = status.running,
            ),
            nowEpochSeconds,
        ).copy(
            engine = stored.engine.ifBlank { status.interfaceName },
            users = status.peers.mapIndexed { index, peer ->
                peer.toDeployUser(index, nowEpochSeconds, status.running)
            }.toMutableList(),
        )
    }

    private fun VpsPeerStatus.toDeployUser(index: Int, nowEpochSeconds: Long, running: Boolean?): VpsDeployUser {
        val handshake = latestHandshake
        val validHandshake = handshake != null && handshake > 0L && handshake <= nowEpochSeconds
        val handshakeAge = if (validHandshake) nowEpochSeconds - handshake else Long.MAX_VALUE
        return VpsDeployUser(
            id = publicKey.ifBlank { "peer-$index" },
            name = name.ifBlank { "Device ${(index + 1).toString().padStart(2, '0')}" },
            device = name.ifBlank { "device" },
            ip = allowedIps.substringBefore(',').ifBlank { "—" },
            allowedIps = allowedIps.ifBlank { "0.0.0.0/0, ::/0" },
            status = when {
                running != true || !validHandshake -> VpsDeployUserStatus.IDLE
                handshakeAge > STALE_HANDSHAKE_SECONDS -> VpsDeployUserStatus.IDLE
                else -> VpsDeployUserStatus.ACTIVE
            },
            rx = rxBytes?.let { QuantityFormatter.formatBytes(it) } ?: "—",
            tx = txBytes?.let { QuantityFormatter.formatBytes(it) } ?: "—",
            handshake = if (validHandshake) formatDuration(handshakeAge) else "—",
            created = if (validHandshake) DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochSecond(handshake).atZone(ZoneOffset.UTC)) else "—",
            key = publicKey,
        )
    }

    private fun healthFor(server: StoredVpsServer, nowEpochSeconds: Long): List<VpsDeployHealthCheck> = listOf(
        VpsDeployHealthCheck("server record", true),
        VpsDeployHealthCheck("endpoint", server.endpoint.isNotBlank()),
        VpsDeployHealthCheck(
            "recent sync",
            if (server.lastSyncEpochSeconds <= 0L) null else hasRecentSync(server, nowEpochSeconds),
        ),
        VpsDeployHealthCheck("wg running", if (hasRecentSync(server, nowEpochSeconds)) server.running else null),
    )

    private fun hasRecentSync(server: StoredVpsServer, nowEpochSeconds: Long): Boolean =
        server.lastSyncEpochSeconds > 0L &&
            server.lastSyncEpochSeconds <= nowEpochSeconds &&
            nowEpochSeconds - server.lastSyncEpochSeconds <= STALE_SYNC_SECONDS

    private fun formatAgo(epochSeconds: Long, nowEpochSeconds: Long): String {
        if (epochSeconds <= 0L || epochSeconds > nowEpochSeconds) return "—"
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
