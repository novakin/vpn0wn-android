/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.provisioning

import java.util.concurrent.TimeUnit
import android.content.res.Resources
import com.vpn0wn.android.R
import com.vpn0wn.android.util.QuantityFormatter
import kotlin.math.max

object VpsDeployMapper {
    fun fromStored(resources: Resources, server: StoredVpsServer, nowEpochSeconds: Long): VpsDeployServer {
        val status = when {
            server.endpoint.isBlank() || server.cidr.isBlank() -> VpsDeployServerStatus.PROVISIONING
            !hasRecentSync(server, nowEpochSeconds) -> VpsDeployServerStatus.ATTENTION
            server.running == true -> VpsDeployServerStatus.ONLINE
            server.running == false -> VpsDeployServerStatus.OFFLINE
            else -> VpsDeployServerStatus.ATTENTION
        }
        return VpsDeployServer(
            id = server.id,
            name = server.name,
            provider = server.provider.ifBlank { "—" },
            region = server.region.ifBlank { "—" },
            endpoint = server.endpoint.takeIf { it.isNotBlank() },
            port = server.listenPort.toIntOrNull()?.takeIf { it in 1..MAX_PORT },
            status = status,
            distro = listOf(server.os, server.version).filter { it.isNotBlank() }.joinToString(" ").ifBlank { resources.getString(R.string.vpn0wn_awaiting_detect) },
            engine = server.engine.ifBlank { resources.getString(R.string.vpn0wn_unknown) },
            cidr = server.cidr.ifBlank { "—" },
            dns = server.dns.ifBlank { "—" },
            uptime = "—",
            lastSync = formatAgo(server.lastSyncEpochSeconds, nowEpochSeconds),
            health = healthFor(resources, server, nowEpochSeconds),
        )
    }

    fun fromStatus(
        resources: Resources,
        stored: StoredVpsServer,
        status: VpsServerStatus,
        nowEpochSeconds: Long,
        observedAtEpochSeconds: Long = nowEpochSeconds,
    ): VpsDeployServer {
        val observed = stored.copy(
            endpoint = status.endpoint,
            listenPort = status.listenPort,
            cidr = status.cidr,
            dns = status.dns,
            lastSyncEpochSeconds = observedAtEpochSeconds,
            running = status.running,
        )
        val currentRunning = if (hasRecentSync(observed, nowEpochSeconds)) status.running else null
        return fromStored(resources, observed, nowEpochSeconds).copy(
            users = status.peers.mapIndexed { index, peer ->
                peer.toDeployUser(resources, index, nowEpochSeconds, currentRunning)
            }.toMutableList(),
            usersKnown = true,
        )
    }

    private fun VpsPeerStatus.toDeployUser(resources: Resources, index: Int, nowEpochSeconds: Long, running: Boolean?): VpsDeployUser {
        val handshake = latestHandshake
        val validHandshake = handshake != null && handshake > 0L && handshake <= nowEpochSeconds
        val handshakeAge = if (validHandshake) nowEpochSeconds - handshake else Long.MAX_VALUE
        return VpsDeployUser(
            id = publicKey.ifBlank { "peer-$index" },
            name = name.ifBlank { resources.getString(R.string.vpn0wn_default_device_name, (index + 1).toString().padStart(2, '0')) },
            device = name.ifBlank { resources.getString(R.string.vpn0wn_device) },
            ip = allowedIps.substringBefore(',').ifBlank { "—" },
            allowedIps = allowedIps.ifBlank { "—" },
            status = when {
                running != true || !validHandshake -> VpsDeployUserStatus.IDLE
                handshakeAge > STALE_HANDSHAKE_SECONDS -> VpsDeployUserStatus.IDLE
                else -> VpsDeployUserStatus.ACTIVE
            },
            rx = rxBytes?.let { QuantityFormatter.formatBytes(it) } ?: "—",
            tx = txBytes?.let { QuantityFormatter.formatBytes(it) } ?: "—",
            handshake = if (validHandshake) formatDuration(handshakeAge) else "—",
            created = "—",
            key = publicKey,
        )
    }

    private fun healthFor(resources: Resources, server: StoredVpsServer, nowEpochSeconds: Long): List<VpsDeployHealthCheck> {
        return listOf(
            VpsDeployHealthCheck(resources.getString(R.string.vpn0wn_health_server_record), true),
            VpsDeployHealthCheck(resources.getString(R.string.endpoint), server.endpoint.isNotBlank()),
            VpsDeployHealthCheck(
                resources.getString(R.string.vpn0wn_health_recent_sync),
                if (server.lastSyncEpochSeconds <= 0L) null else hasRecentSync(server, nowEpochSeconds),
            ),
            VpsDeployHealthCheck(resources.getString(R.string.vpn0wn_health_wg_running), if (hasRecentSync(server, nowEpochSeconds)) server.running else null),
        )
    }

    private fun hasRecentSync(server: StoredVpsServer, nowEpochSeconds: Long): Boolean =
        server.lastSyncEpochSeconds > 0L &&
            server.lastSyncEpochSeconds <= nowEpochSeconds &&
            nowEpochSeconds - server.lastSyncEpochSeconds <= STALE_SYNC_SECONDS

    private fun formatAgo(epochSeconds: Long, nowEpochSeconds: Long): String {
        if (epochSeconds <= 0L || epochSeconds > nowEpochSeconds) return "—"
        return formatDuration(max(0L, nowEpochSeconds - epochSeconds))
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds < TimeUnit.MINUTES.toSeconds(1)) return "${seconds}s"
        val minutes = TimeUnit.SECONDS.toMinutes(seconds)
        val remainingSeconds = seconds % TimeUnit.MINUTES.toSeconds(1)
        if (minutes < TimeUnit.HOURS.toMinutes(1)) return "${minutes.toString().padStart(2, '0')}m ${remainingSeconds.toString().padStart(2, '0')}s"
        val hours = TimeUnit.MINUTES.toHours(minutes)
        val remainingMinutes = minutes % TimeUnit.HOURS.toMinutes(1)
        return "${hours.toString().padStart(2, '0')}h ${remainingMinutes.toString().padStart(2, '0')}m"
    }

    private const val STALE_SYNC_SECONDS = 600L
    private const val STALE_HANDSHAKE_SECONDS = 180L
    private const val MAX_PORT = 65535
}
