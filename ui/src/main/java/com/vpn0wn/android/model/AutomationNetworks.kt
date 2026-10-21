/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.model

import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build

internal data class AutomationNetwork(
    val network: Network,
    val capabilities: NetworkCapabilities? = null,
    val blocked: Boolean? = null,
    val losing: Boolean = false,
) {
    val transport: Int?
        get() {
            val caps = capabilities ?: return null
            val advertised = (0..LAST_KNOWN_TRANSPORT).filter(caps::hasTransport)
            return advertised.singleOrNull()?.takeIf { it in SUPPORTED_TRANSPORTS }
        }

    val eligible: Boolean
        get() {
            val caps = capabilities ?: return false
            return transport != null && REQUIRED_CAPABILITIES.all(caps::hasCapability) && blocked != true &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED))
        }

    val validated: Boolean
        get() = capabilities?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        } == true

    companion object {
        private const val LAST_KNOWN_TRANSPORT = 10
        private val SUPPORTED_TRANSPORTS = setOf(
            NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR,
        )
        private val REQUIRED_CAPABILITIES = setOf(
            NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_NOT_VPN,
            NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED, NetworkCapabilities.NET_CAPABILITY_TRUSTED,
        )
    }
}

internal data class AutomationNetworks(
    val generation: Any,
    val sequence: Long,
    val candidates: List<AutomationNetwork>,
    val appDefault: Network?,
) {
    fun select(current: Network?): Network? {
        val eligible = candidates.filter { it.eligible }
        if (eligible.isEmpty()) return null
        val quality = eligible.any { it.validated }
        val tier = eligible.filter { it.validated == quality }
        val nonLosing = tier.filterNot { it.losing }
        val retainLosing = nonLosing.isEmpty() && tier.any { it.network == current }
        val preferred = nonLosing.ifEmpty { tier }
        val priority = preferred.minOf { priority(it.transport) }
        val tied = preferred.filter { priority(it.transport) == priority }
        return when {
            retainLosing || tied.any { it.network == current } -> current
            tied.size == 1 -> tied.single().network
            else -> appDefault?.takeIf { candidate -> tied.any { it.network == candidate } }
        }
    }

    private fun priority(transport: Int?): Int = when (transport) {
        NetworkCapabilities.TRANSPORT_ETHERNET -> 0
        NetworkCapabilities.TRANSPORT_WIFI -> 1
        else -> 2
    }
}
