/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.databinding

import android.content.Context
import android.content.res.Configuration
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.model.TunnelTestEnvironment
import com.wireguard.config.Config
import com.wireguard.crypto.Key
import java.util.Locale
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(application = android.app.Application::class, sdk = [28])
class BindingAdaptersTest {
    @get:Rule val folder = TemporaryFolder()
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun shortKeyReturnsDashForEmptyKey() {
        assertEquals("—", BindingAdapters.shortKey(null))
        assertEquals("—", BindingAdapters.shortKey(""))
    }

    @Test
    fun shortKeyLeavesShortKeyUnchanged() {
        assertEquals("abc123", BindingAdapters.shortKey("abc123"))
    }

    @Test
    fun shortKeyTruncatesLongKeyWithMiddleEllipsis() {
        assertEquals("abcdef…uvwxyz", BindingAdapters.shortKey("abcdefghijklmnopqrstuvwxyz"))
    }

    @Test
    fun emptyHomeHeaderKeepsWordmarkAndConfiguredCount() {
        assertEquals("Vpn0wn · 0 configured", BindingAdapters.homeHeaderSummary(context, emptyList()))
    }

    @Test
    fun emptyHomeConnectionShowsDisconnected() {
        assertEquals("VPN disconnected", BindingAdapters.homeProtectionLabel(context, emptyList()))
    }

    @Test
    fun emptyHomeEndpointShowsDash() {
        assertEquals("—", BindingAdapters.homeEndpointHost(emptyList()))
    }

    @Test
    fun homeUsesRecentActivePeerHostnameWithoutResolution() = runTest {
        val env = TunnelTestEnvironment(folder.root, backgroundScope)
        val config = Config.parse("""
            [Interface]
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            [Peer]
            PublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            Endpoint = unresolvable.invalid:51820
        """.trimIndent().byteInputStream())
        val inactive = ObservableTunnel(env.manager, "inactive", config, Tunnel.State.DOWN)
        val active = ObservableTunnel(env.manager, "active", config, Tunnel.State.UP)
        active.onStatisticsChanged(statistics(System.currentTimeMillis()))
        val tunnels = listOf(inactive, active)
        assertEquals("Vpn0wn · 2 configured", BindingAdapters.homeHeaderSummary(context, tunnels))
        assertEquals("VPN connected", BindingAdapters.homeProtectionLabel(context, tunnels))
        assertEquals("unresolvable.invalid", BindingAdapters.homeEndpointHost(tunnels))
        assertEquals("—", BindingAdapters.homeEndpointHost(listOf(inactive)))
        val header = TextView(context)
        BindingAdapters.setHomeHeaderTunnels(header, tunnels)
        assertEquals("Vpn0wn · 2 configured", header.text.toString())
    }

    @Test
    fun homeHandshakeAddsLocalizedAgeWithoutChangingDetailFormatter() {
        val snapshot = statistics(System.currentTimeMillis() - 14_000L)
        val view = TextView(context)
        BindingAdapters.setHomeHandshakeStatistics(view, snapshot)
        assertEquals("14s ago", view.text.toString())
        assertEquals("14s", BindingAdapters.tunnelHandshake(snapshot))
        val french = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.FRENCH)
        })
        val frenchView = TextView(french)
        BindingAdapters.setHomeHandshakeStatistics(frenchView, snapshot)
        assertEquals("il y a 14s", frenchView.text.toString())
        assertEquals("Vpn0wn · 0 configurés", BindingAdapters.homeHeaderSummary(french, emptyList()))
    }

    @Test
    fun homeHandshakeKeepsMissingMarkerAndNewestPeerSemantics() {
        val view = TextView(context)
        for (snapshot in listOf(null, statistics(0L))) {
            BindingAdapters.setHomeHandshakeStatistics(view, snapshot)
            assertEquals("--", view.text.toString())
        }
        BindingAdapters.setHomeHandshakeStatistics(view,
            statistics(0L, System.currentTimeMillis() - 60_000L, System.currentTimeMillis() + 60_000L))
        assertEquals("0s ago", view.text.toString())
    }

    private fun statistics(vararg epochs: Long): Statistics {
        val snapshot = Statistics::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val add = Statistics::class.java.getDeclaredMethod("add", Key::class.java, Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType).apply { isAccessible = true }
        epochs.forEachIndexed { index, epoch ->
            add.invoke(snapshot, Key.fromBytes(ByteArray(32) { index.toByte() }), 0L, 0L, epoch)
        }
        return snapshot
    }
}
