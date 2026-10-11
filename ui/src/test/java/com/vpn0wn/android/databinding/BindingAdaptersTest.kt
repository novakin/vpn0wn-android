/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.databinding

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.widget.TextView
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.vpn0wn.android.R
import com.vpn0wn.android.backend.Statistics
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.model.ObservableTunnel
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.android.widget.TunnelThroughputView
import com.vpn0wn.config.Config
import com.vpn0wn.crypto.Key
import java.util.Locale
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
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
        assertEquals("vpnøwn · 0 configured", BindingAdapters.homeHeaderSummary(context, emptyList()))
    }

    @Test
    fun homeWordmarkUsesArtworkWithoutReplacingLocalizedCountOrBrandDescription() {
        val header = TextView(context)
        BindingAdapters.setHomeHeaderTunnels(header, emptyList())
        val text = header.text as Spanned
        val artwork = text.getSpans(0, text.length, ImageSpan::class.java).single()
        assertEquals(0, text.getSpanStart(artwork))
        assertEquals(6, text.getSpanEnd(artwork))
        assertEquals(" · 0 configured", text.subSequence(text.getSpanEnd(artwork), text.length).toString().replace('\u00a0', ' '))
        assertTrue(artwork.drawable.bounds.width() > artwork.drawable.bounds.height())
        assertEquals("vpn0wn · 0 configured", header.contentDescription.toString())
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun homeWordmarkAndCountShareVisibleVerticalCenterAtNormalAndLargeText() {
        try {
            for (scale in listOf(1f, 2f)) {
                RuntimeEnvironment.setFontScale(scale)
                val header = TextView(context, null, 0, R.style.TunnlHomeHeaderText)
                header.setTextColor(context.getColor(R.color.tunnl_ink_3))
                BindingAdapters.setHomeHeaderTunnels(header, emptyList())
                val density = header.resources.displayMetrics.density
                val width = (320 * density).toInt()
                header.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((80 * density).toInt(), View.MeasureSpec.AT_MOST))
                header.layout(0, 0, width, header.measuredHeight)
                assertEquals(1, header.layout.lineCount)
                val bitmap = Bitmap.createBitmap(width, header.height, Bitmap.Config.ARGB_8888)
                header.draw(Canvas(bitmap))
                try {
                    val text = header.text as Spanned
                    val logoWidth = text.getSpans(0, text.length, ImageSpan::class.java).single().drawable.bounds.width()
                    val logoCenter = visibleInkCenter(bitmap, 0 until logoWidth)
                    val countCenter = visibleInkCenter(bitmap, logoWidth until width)
                    assertTrue("$scale text: logo center $logoCenter, count center $countCenter",
                        kotlin.math.abs(logoCenter - countCenter) <= density)
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    private fun visibleInkCenter(bitmap: Bitmap, columns: IntRange): Float {
        val rows = (0 until bitmap.height).filter { y ->
            columns.any { x -> bitmap.getPixel(x, y) ushr 24 >= 128 }
        }
        assertTrue("Expected rendered ink", rows.isNotEmpty())
        return (rows.first() + rows.last()) / 2f
    }

    @Test
    fun homeStatusColorsOnlyStateAndLeavesCipherTextMixedCase() {
        val config = Config.parse("""
            [Interface]
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            [Peer]
            PublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            PresharedKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        """.trimIndent().byteInputStream())
        val view = TextView(context)
        BindingAdapters.setTunnelStatusTunnel(view, null, Tunnel.State.DOWN, null, config)
        val status = context.getString(R.string.tunnl_tunnel_status_inactive)
        val text = view.text as Spanned
        assertEquals("$status · ChaCha20 + PSK", text.toString())
        val color = text.getSpans(0, text.length, ForegroundColorSpan::class.java).single()
        assertEquals(0, text.getSpanStart(color))
        assertEquals(status.length, text.getSpanEnd(color))
        assertEquals(context.getColor(R.color.tunnl_ink_3), color.foregroundColor)
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
        assertEquals("vpnøwn · 2 configured", BindingAdapters.homeHeaderSummary(context, tunnels))
        assertEquals("VPN connected", BindingAdapters.homeProtectionLabel(context, tunnels))
        assertEquals("unresolvable.invalid", BindingAdapters.homeEndpointHost(tunnels))
        assertEquals("—", BindingAdapters.homeEndpointHost(listOf(inactive)))
        val header = TextView(context)
        BindingAdapters.setHomeHeaderTunnels(header, tunnels)
        assertEquals("vpnøwn · 2 configured", header.text.toString().replace('\u00a0', ' '))
        assertEquals("vpn0wn · 2 configured", header.contentDescription.toString())
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
        assertEquals("vpnøwn · 0 configurés", BindingAdapters.homeHeaderSummary(french, emptyList()))
        val frenchHeader = TextView(french)
        BindingAdapters.setHomeHeaderTunnels(frenchHeader, emptyList())
        assertEquals("vpn0wn · 0 configurés", frenchHeader.contentDescription.toString())
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

    @Test
    fun topologyDrawsConfiguredMtuInUpAndDownStates() {
        val view = TunnelThroughputView(context)
        for (mtu in listOf(1280, 1380)) {
            val config = topologyConfig(mtu)
            for (state in listOf(Tunnel.State.UP, Tunnel.State.DOWN)) {
                BindingAdapters.setTopologyConfig(view, config)
                BindingAdapters.setThroughputModeTunnel(view, null, state, statistics(System.currentTimeMillis()))
                assertEquals(state == Tunnel.State.UP, view.live)
                assertEquals(listOf("MTU ${config.`interface`.mtu.get()}"), drawnMtu(view))
            }
        }
    }

    @Test
    fun topologyDoesNotInventMtuForOmittedZeroOrMissingConfig() {
        val view = TunnelThroughputView(context)
        for (config in listOf(topologyConfig(null), topologyConfig(0), null)) {
            for (state in listOf(Tunnel.State.UP, Tunnel.State.DOWN)) {
                BindingAdapters.setTopologyConfig(view, config)
                BindingAdapters.setThroughputModeTunnel(view, null, state, statistics(System.currentTimeMillis()))
                assertEquals(listOf("MTU —"), drawnMtu(view))
            }
        }
    }

    @Test
    fun topologyRebindingReplacesMtuAndClearsPreviousValue() {
        val view = TunnelThroughputView(context)
        val updates = listOf(topologyConfig(1280), topologyConfig(0), topologyConfig(1380),
            topologyConfig(null), topologyConfig(1420), null, topologyConfig(1280))
        val labels = listOf("MTU 1280", "MTU —", "MTU 1380", "MTU —", "MTU 1420", "MTU —", "MTU 1280")
        for ((index, config) in updates.withIndex()) {
            BindingAdapters.setTopologyConfig(view, config)
            assertEquals(listOf(labels[index]), drawnMtu(view))
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun nativeTopologyOutputCoversConfiguredAndUnknownMtuAcrossLayouts() {
        val cases = listOf(
            Preview("portrait-light-1280-up", 320, 640, 1f, "en", false, 1280, Tunnel.State.UP),
            Preview("portrait-dark-1380-down", 320, 640, 1f, "en", true, 1380, Tunnel.State.DOWN),
            Preview("landscape-dark-unknown-up", 640, 320, 1f, "en", true, null, Tunnel.State.UP),
            Preview("large-rtl-light-1280-down", 320, 640, 2f, "ar", false, 1280, Tunnel.State.DOWN),
            Preview("large-rtl-dark-zero-up", 320, 640, 2f, "ar", true, 0, Tunnel.State.UP)
        )
        for (case in cases) {
            val direction = if (case.locale == "ar") "ldrtl" else "ldltr"
            val orientation = if (case.width > case.height) "land" else "port"
            val night = if (case.night) "night" else "notnight"
            RuntimeEnvironment.setQualifiers("${case.locale}-$direction-w${case.width}dp-h${case.height}dp-$orientation-$night")
            RuntimeEnvironment.setFontScale(case.fontScale)
            val controller = Robolectric.buildActivity(android.app.Activity::class.java)
            controller.get().setTheme(R.style.AppTheme)
            controller.setup()
            try {
                val view = TunnelThroughputView(controller.get())
                controller.get().setContentView(view)
                BindingAdapters.setTopologyConfig(view, topologyConfig(case.mtu))
                BindingAdapters.setThroughputModeTunnel(view, null, case.state, statistics(System.currentTimeMillis()))
                view.setBackgroundColor(context.getColor(R.color.tunnl_paper_2))
                val density = view.resources.displayMetrics.density
                val width = ((case.width - DETAIL_HORIZONTAL_GUTTER_DP) * density).toInt()
                val height = (DETAIL_TOPOLOGY_HEIGHT_DP * density).toInt()
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, width, height)
                assertEquals(case.state == Tunnel.State.UP, view.live)
                assertEquals(if (case.locale == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR, view.layoutDirection)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val directory = File("../.artifacts/detail-mtu-previews").apply { mkdirs() }
                File(directory, "${case.name}.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                bitmap.recycle()
            } finally {
                controller.pause().stop().destroy()
            }
        }
    }

    private fun topologyConfig(mtu: Int?): Config = Config.parse("""
        [Interface]
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        Address = 192.0.2.2/32
        ${mtu?.let { "MTU = $it" } ?: ""}
        [Peer]
        PublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        Endpoint = 192.0.2.1:51820
    """.trimIndent().byteInputStream())

    private fun drawnMtu(view: TunnelThroughputView): List<String> {
        view.layout(0, 0, DETAIL_TOPOLOGY_TEST_WIDTH, DETAIL_TOPOLOGY_HEIGHT_DP)
        val canvas = Canvas()
        view.draw(canvas)
        val shadow = shadowOf(canvas)
        return (0 until shadow.textHistoryCount).map { shadow.getDrawnTextEvent(it).text }.filter { it.startsWith("MTU ") }
    }

    private data class Preview(val name: String, val width: Int, val height: Int, val fontScale: Float,
        val locale: String, val night: Boolean, val mtu: Int?, val state: Tunnel.State)

    private fun statistics(vararg epochs: Long): Statistics {
        val snapshot = Statistics::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val add = Statistics::class.java.getDeclaredMethod("add", Key::class.java, Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType).apply { isAccessible = true }
        epochs.forEachIndexed { index, epoch ->
            add.invoke(snapshot, Key.fromBytes(ByteArray(32) { index.toByte() }), 0L, 0L, epoch)
        }
        return snapshot
    }

    private companion object {
        const val DETAIL_HORIZONTAL_GUTTER_DP = 48
        const val DETAIL_TOPOLOGY_HEIGHT_DP = 206
        const val DETAIL_TOPOLOGY_TEST_WIDTH = 272
    }
}
