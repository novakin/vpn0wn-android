/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.provisioning

import com.vpn0wn.android.R

enum class VpsDeployServerStatus(val labelRes: Int, val shortLabelRes: Int, val dot: String, val colorRes: Int) {
    ONLINE(R.string.vpn0wn_status_online, R.string.vpn0wn_status_online_short, "◆", R.color.vpn0wn_signal_deep),
    PROVISIONING(R.string.vpn0wn_status_provisioning, R.string.vpn0wn_status_provisioning_short, "◑", R.color.vpn0wn_warn),
    ATTENTION(R.string.vpn0wn_status_attention, R.string.vpn0wn_status_attention_short, "✕", R.color.vpn0wn_error),
    OFFLINE(R.string.vpn0wn_status_offline, R.string.vpn0wn_status_offline_short, "●", R.color.vpn0wn_ink_3),
}

enum class VpsDeployUserStatus(val labelRes: Int, val colorRes: Int) {
    ACTIVE(R.string.vpn0wn_user_status_active, R.color.vpn0wn_signal_deep),
    IDLE(R.string.vpn0wn_user_status_idle, R.color.vpn0wn_warn),
    REVOKED(R.string.vpn0wn_user_status_revoked, R.color.vpn0wn_error),
}

data class VpsDeployHealthCheck(val label: String, val ok: Boolean?)

data class VpsDeployServer(
    val id: String,
    val name: String,
    val provider: String,
    val region: String,
    val endpoint: String?,
    val port: Int?,
    var status: VpsDeployServerStatus,
    val distro: String,
    val engine: String,
    val cidr: String,
    val dns: String,
    val uptime: String,
    val lastSync: String,
    val health: List<VpsDeployHealthCheck>,
    val users: MutableList<VpsDeployUser> = mutableListOf(),
    val usersKnown: Boolean = false,
) {
    val endpointAddress: String?
        get() = if (endpoint != null && port != null) "$endpoint:$port" else null
}

data class VpsDeployUser(
    val id: String,
    val name: String,
    val device: String,
    val ip: String,
    val allowedIps: String,
    var status: VpsDeployUserStatus,
    val rx: String,
    val tx: String,
    var handshake: String,
    val created: String,
    var key: String,
)

data class VpsDeployAction(
    val iconRes: Int,
    val label: String,
    val sub: String,
    val colorRes: Int,
    val disabled: Boolean = false,
    val onClick: () -> Unit,
)
