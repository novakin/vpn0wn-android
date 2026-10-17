/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.fragment

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.vpn0wn.android.R
import com.vpn0wn.android.provisioning.VpsDeployFixtureData
import com.vpn0wn.android.provisioning.VpsDeployMapper
import com.vpn0wn.android.provisioning.VpsDeployServer
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class VpsHeroMetadataLayoutTest {
    @get:Rule val folder = TemporaryFolder()

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun nativeHeroKeepsObservedMetadataCompleteAndReachableAcrossConfigurations() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val qualifiers = RuntimeEnvironment.getQualifiers()
        val scale = RuntimeEnvironment.getApplication().resources.configuration.fontScale
        val locale = Locale.getDefault()
        try {
            val previews = listOf(Preview("en", 320, 640, 1f), Preview("fr", 640, 320, 2f, true), Preview("ar", 320, 640, 2f, true))
            for (preview in previews) for (night in listOf(false, true)) {
                val case = preview.copy(night = night)
                val orientation = if (case.width > case.height) "land" else "port"
                RuntimeEnvironment.setQualifiers("${case.locale}-w${case.width}dp-h${case.height}dp-$orientation-${if (night) "night" else "notnight"}-mdpi")
                RuntimeEnvironment.setFontScale(case.scale)
                RuntimeEnvironment.setQualifiers(if (case.rtl) "+ldrtl" else "+ldltr")
                for (count in listOf(null, 0, 1, 3)) {
                    val direction = if (case.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    val fixture = provisioningLayoutFixture(folder, direction)
                    fixture.activity.supportActionBar?.hide()
                    try {
                        val state = count?.let { "known-$it" } ?: "unknown"
                        val server = server(count)
                        val source = server.copy(users = server.users.toMutableList())
                        val detail = VpsProvisioningFragment::class.java.getDeclaredMethod("detailScreen", VpsDeployServer::class.java)
                            .apply { isAccessible = true }.invoke(fixture.fragment, server) as View
                        fixture.root.removeAllViews()
                        fixture.root.addView(detail)
                        val root = fixture.root.rootView
                        layout(root, case)
                        assertConfiguration(root, case)
                        val metadata = requireNotNull(detail.findViewById<LinearLayout>(R.id.vpn0wn_vps_hero_metadata))
                        val cells = (0 until metadata.childCount).map(metadata::getChildAt).filterIsInstance<LinearLayout>()
                        assertEquals("Three approved metadata cells", 3, cells.size)
                        val stacked = case.width < 600 && case.scale >= 1.5f
                        assertEquals(if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL, metadata.orientation)
                        if (stacked) cells.forEach { assertEquals("Stacked cell uses complete width", metadata.width, it.width) }
                        else assertTrue("Normal columns share available width", cells.maxOf { it.width } - cells.minOf { it.width } <= 1)
                        val peers = if (server.usersKnown) fixture.activity.resources.getQuantityString(
                            R.plurals.vpn0wn_topology_peers, server.users.size, server.users.size)
                            else fixture.activity.getString(R.string.vpn0wn_topology_peers_unknown)
                        val expected = listOf(server.name, server.distro, server.endpoint ?: "—",
                            fixture.activity.getString(R.string.vpn0wn_topology_udp, server.port?.toString() ?: "—"), server.cidr, peers)
                        val ids = listOf(R.id.vpn0wn_vps_hero_server, R.id.vpn0wn_vps_hero_distro,
                            R.id.vpn0wn_vps_hero_endpoint, R.id.vpn0wn_vps_hero_udp, R.id.vpn0wn_vps_hero_cidr, R.id.vpn0wn_vps_hero_peers)
                        ids.forEachIndexed { index, id ->
                            val text = requireNotNull(detail.findViewById<TextView>(id))
                            assertEquals("Complete native metadata source", expected[index], text.text.toString())
                            val node = text.createAccessibilityNodeInfo()
                            try { assertEquals("Complete native accessible metadata", expected[index], node.text.toString()) }
                            finally { node.recycle() }
                        }
                        for (id in listOf(R.id.vpn0wn_vps_hero_endpoint, R.id.vpn0wn_vps_hero_udp, R.id.vpn0wn_vps_hero_cidr))
                            assertEquals("Technical metadata remains LTR", View.TEXT_DIRECTION_LTR, detail.findViewById<TextView>(id).textDirection)
                        val scroll = descendants(detail).filterIsInstance<ScrollView>().single()
                        scroll.scrollTo(0, 0)
                        capture(root, case, state, "top")
                        for ((index, text) in descendants(metadata).filterIsInstance<TextView>().withIndex()) {
                            assertEquals(ResourcesCompat.getFont(text.context, R.font.jetbrains_mono_regular), text.typeface)
                            assertEquals("ss01, zero, tnum", text.fontFeatureSettings)
                            assertFalse(text.paint.isFakeBoldText)
                            assertFalse("Static metadata is not an action", text.isClickable || text.isLongClickable || text.isFocusable)
                            assertTextFits(text)
                            for (line in 0 until text.lineCount) revealLine(text, line, root, case)
                            revealValue(text, scroll, root, case)
                            capture(root, case, state, "field-$index")
                        }
                        assertEquals("Presentation preserves the complete bound model", source, server)
                        assertTrue("Metadata rendering opens no SSH", fixture.peer.connections.isEmpty())
                    } finally { fixture.close() }
                }
            }
        } finally {
            RuntimeEnvironment.setQualifiers(qualifiers)
            RuntimeEnvironment.setFontScale(scale)
            Locale.setDefault(locale)
        }
    }

    @Test fun resourceQuantitiesAndUnknownListenerRespectEnglishAndFrenchSemantics() {
        val original = RuntimeEnvironment.getQualifiers()
        try {
            for (locale in listOf("en", "fr")) {
                RuntimeEnvironment.setQualifiers(locale)
                val resources = RuntimeEnvironment.getApplication().resources
                val expected = if (locale == "fr") listOf("0 pair", "1 pair", "3 pairs") else listOf("0 peers", "1 peer", "3 peers")
                listOf(0, 1, 3).forEachIndexed { index, count ->
                    assertEquals(expected[index], resources.getQuantityString(R.plurals.vpn0wn_topology_peers, count, count))
                }
                assertEquals("UDP —", resources.getString(R.string.vpn0wn_topology_udp, "—"))
                assertEquals("UDP 65535", resources.getString(R.string.vpn0wn_topology_udp, "65535"))
                assertEquals(if (locale == "fr") "Nombre de pairs inconnu" else "Peer count unknown",
                    resources.getString(R.string.vpn0wn_topology_peers_unknown))
            }
        } finally { RuntimeEnvironment.setQualifiers(original) }
    }

    private fun server(count: Int?): VpsDeployServer {
        val resources = RuntimeEnvironment.getApplication().resources
        val stored = VpsDeployFixtureData.storedServers().first()
        val status = VpsDeployFixtureData.onlineStatusWithPeers()
        if (count == null) return VpsDeployMapper.fromStored(resources, stored.copy(name = "unknown-server", endpoint = "", listenPort = "",
            cidr = "", os = "", version = ""), VpsDeployFixtureData.NOW)
        val current = stored.copy(name = if (count == 1) "synthetic-server-with-a-long-context-name" else "server-$count",
            os = "Debian GNU/Linux", version = "12 (bookworm)")
        val peers = List(count) { index -> status.peers.first().copy(name = "peer-$index", publicKey = "synthetic-public-$index") }
        val observed = status.copy(peers = peers, endpoint = if (count == 1) "2001:db8:1234:5678:abcd:ef01:2345:6789" else "203.0.113.42",
            listenPort = if (count == 3) "65535" else "51821", cidr = "10.123.240.0/24")
        return VpsDeployMapper.fromStatus(resources, current, observed, VpsDeployFixtureData.NOW)
    }

    private fun TestScope.layout(root: View, case: Preview) {
        var ready = false
        repeat(3) {
            root.measure(View.MeasureSpec.makeMeasureSpec(case.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(case.height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            ready = !root.viewTreeObserver.dispatchOnPreDraw()
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue("Settled native traversal permits drawing", ready)
    }

    private fun assertConfiguration(root: View, case: Preview) {
        val config = root.resources.configuration
        assertEquals(case.locale, config.locales[0].language)
        assertEquals(case.scale, config.fontScale, 0f)
        assertEquals(if (case.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR, root.layoutDirection)
        assertEquals(if (case.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR, config.layoutDirection)
        assertEquals(if (case.night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
            config.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertEquals(case.width, root.width)
        assertEquals(case.height, root.height)
    }

    private fun TestScope.revealValue(view: TextView, scroll: ScrollView, root: View, case: Preview) {
        val target = (view.parent as View).takeIf { it.height <= scroll.height } ?: view
        if (target.height <= scroll.height) {
            target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true)
            layout(root, case)
            val visible = Rect()
            assertTrue("Complete metadata target is reachable", target.getLocalVisibleRect(visible))
            visible.offset(-target.scrollX, -target.scrollY)
            assertEquals(Rect(0, 0, target.width, target.height), visible)
        } else revealLine(view, view.lineCount - 1, root, case)
    }

    private fun TestScope.revealLine(view: TextView, line: Int, root: View, case: Preview) {
        val text = view.layout
        val requested = Rect(kotlin.math.floor(text.getLineLeft(line)).toInt(), text.getLineTop(line),
            kotlin.math.ceil(text.getLineRight(line)).toInt(), text.getLineBottom(line)).apply {
            offset(view.totalPaddingLeft, view.totalPaddingTop)
        }
        view.requestRectangleOnScreen(Rect(requested), true)
        layout(root, case)
        val location = IntArray(2).also(view::getLocationOnScreen)
        requested.offset(location[0] - view.scrollX, location[1] - view.scrollY)
        val visible = Rect()
        assertTrue("Metadata line is reachable", view.getGlobalVisibleRect(visible))
        assertTrue("Complete metadata line is visible: ${view.text}", visible.contains(requested))
    }

    private fun assertTextFits(view: TextView) {
        val text = requireNotNull(view.layout)
        val width = view.width - view.compoundPaddingLeft - view.compoundPaddingRight
        val height = view.height - view.compoundPaddingTop - view.compoundPaddingBottom
        assertTrue("Complete characters: ${view.text}", text.lineCount > 0 && text.getLineEnd(text.lineCount - 1) == text.text.length)
        assertTrue("Complete text height: ${view.text}", text.height <= height)
        for (line in 0 until text.lineCount) {
            assertEquals("No metadata ellipsis: ${view.text}", 0, text.getEllipsisCount(line))
            assertTrue("Complete metadata line width: ${view.text}", text.getLineLeft(line) - view.scrollX >= -1f &&
                text.getLineRight(line) - view.scrollX <= width + 1f)
        }
        val bitmap = Bitmap.createBitmap(width + 40, height + 40, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.translate((20 - view.scrollX).toFloat(), (20 - view.scrollY).toFloat())
            text.draw(canvas)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("Native metadata glyphs painted", pixels.any { it ushr 24 != 0 })
            assertTrue("Complete native glyphs: ${view.text}", pixels.indices.all {
                if (pixels[it] ushr 24 == 0) true else {
                    val x = it % bitmap.width - 20
                    val y = it / bitmap.width - 20
                    x >= -1 && x < width + 1 && y >= -1 && y < height + 1
                }
            })
        } finally { bitmap.recycle() }
    }

    private fun capture(root: View, case: Preview, state: String, position: String) {
        assertConfiguration(root, case)
        val project = File(requireNotNull(System.getProperty("user.dir"))).let { if (it.name == "ui") it.parentFile else it }
        val directory = System.getenv("VPN0WN_VPS_HERO_OUTPUT")?.let(::File) ?: File(project, ".artifacts/vps-hero-previews")
        assertTrue(directory.isDirectory || directory.mkdirs())
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            File(directory, "${case.locale}-${case.width}x${case.height}-${case.scale}-${if (case.night) "night" else "day"}-$state-$position.png")
                .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private data class Preview(val locale: String, val width: Int, val height: Int, val scale: Float,
        val rtl: Boolean = false, val night: Boolean = false)
}
