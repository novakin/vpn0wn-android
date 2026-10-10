/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.provisioning

import com.vpn0wn.android.R

enum class VpsDeployServerStatus(val labelRes: Int, val shortLabelRes: Int, val dot: String, val colorRes: Int) {
    ONLINE(R.string.tunnl_status_online, R.string.tunnl_status_online_short, "◆", R.color.tunnl_signal_deep),
    PROVISIONING(R.string.tunnl_status_provisioning, R.string.tunnl_status_provisioning_short, "◑", R.color.tunnl_warn),
    ATTENTION(R.string.tunnl_status_attention, R.string.tunnl_status_attention_short, "✕", R.color.tunnl_error),
    OFFLINE(R.string.tunnl_status_offline, R.string.tunnl_status_offline_short, "●", R.color.tunnl_ink_3),
}

enum class VpsDeployUserStatus(val labelRes: Int, val colorRes: Int) {
    ACTIVE(R.string.tunnl_user_status_active, R.color.tunnl_signal_deep),
    IDLE(R.string.tunnl_user_status_idle, R.color.tunnl_warn),
    REVOKED(R.string.tunnl_user_status_revoked, R.color.tunnl_error),
}

data class VpsDeployHealthCheck(val label: String, val ok: Boolean?)

data class VpsDeployServer(
    val id: String,
    val name: String,
    val provider: String,
    val region: String,
    val endpoint: String,
    val port: Int,
    var status: VpsDeployServerStatus,
    val distro: String,
    val version: String,
    val engine: String,
    val cidr: String,
    val dns: String,
    val uptime: String,
    val lastSync: String,
    val health: List<VpsDeployHealthCheck>,
    val users: MutableList<VpsDeployUser> = mutableListOf(),
)

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
