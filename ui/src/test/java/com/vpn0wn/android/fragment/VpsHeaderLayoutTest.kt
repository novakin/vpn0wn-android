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
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.vpn0wn.android.R
import com.vpn0wn.android.provisioning.VpsDeployFixtureData
import com.vpn0wn.android.provisioning.VpsDeployMapper
import com.vpn0wn.android.provisioning.VpsDeployServer
import com.vpn0wn.android.provisioning.VpsDeployUser
import com.vpn0wn.android.provisioning.VpsDeployUserStatus
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
class VpsHeaderLayoutTest {
    private val resources get() = RuntimeEnvironment.getApplication().resources

    @get:Rule val folder = TemporaryFolder()

    @After fun tearDown() {
        RuntimeEnvironment.setFontScale(1f)
        Dispatchers.resetMain()
    }

    @Test fun realScreenHeadersKeepTheApprovedBrandAndNativeActions() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val cases = listOf(
            Preview("compact-day", "en-rUS-w320dp-h640dp-port-notnight-mdpi", 320, 640, 1f, View.LAYOUT_DIRECTION_LTR),
            Preview("wide-night", "fr-w360dp-h800dp-port-night-mdpi", 360, 800, 1f, View.LAYOUT_DIRECTION_LTR),
            Preview("short-rtl", "fr-ldrtl-w640dp-h320dp-land-night-mdpi", 640, 320, 2f, View.LAYOUT_DIRECTION_RTL),
            Preview("compact-rtl", "ar-rSA-ldrtl-w320dp-h640dp-port-notnight-mdpi", 320, 640, 2f, View.LAYOUT_DIRECTION_RTL),
        )
        for (case in cases) {
            RuntimeEnvironment.setQualifiers(case.qualifiers)
            RuntimeEnvironment.setFontScale(case.fontScale)
            val fixture = provisioningLayoutFixture(folder, case.direction)
            try {
                val server = VpsDeployMapper.fromStored(resources, VpsDeployFixtureData.storedServers().first(), VpsDeployFixtureData.NOW)
                    .copy(name = "synthetic-server-with-a-long-context-name")
                val user = VpsDeployUser("peer", "synthetic-user-with-a-long-context-name", "phone", "10.8.0.2/32",
                    "0.0.0.0/0", VpsDeployUserStatus.IDLE, "0 B", "0 B", "—", "—", "synthetic-public-key")
                val type = VpsProvisioningFragment::class.java
                val screens = listOf(
                    "fleet" to (type.getDeclaredMethod("fleetScreen").apply { isAccessible = true }.invoke(fixture.fragment) as View),
                    "detail" to (type.getDeclaredMethod("detailScreen", VpsDeployServer::class.java)
                        .apply { isAccessible = true }.invoke(fixture.fragment, server) as View),
                    "user" to (type.getDeclaredMethod("userScreen", VpsDeployServer::class.java, VpsDeployUser::class.java)
                        .apply { isAccessible = true }.invoke(fixture.fragment, server, user) as View),
                    "provision" to (type.getDeclaredMethod("provisionScreen").apply { isAccessible = true }.invoke(fixture.fragment) as View),
                )
                for ((name, screen) in screens) {
                    fixture.root.removeAllViews()
                    fixture.root.addView(screen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    runCurrent()
                    layout(fixture.root, case)
                    val logo = requireNotNull(fixture.root.findViewById<ImageView>(R.id.vpn0wn_vps_wordmark))
                    val row = logo.parent.parent as ViewGroup
                    val bar = row.parent as ViewGroup
                    assertEquals("vpn0wn", logo.contentDescription.toString())
                    assertFalse("The wordmark is not a navigation control", logo.isClickable)
                    assertFalse("The approved artwork never mirrors", requireNotNull(logo.drawable).isAutoMirrored)
                    assertEquals(logo.resources.getDimensionPixelSize(R.dimen.vpnown_home_wordmark_height), logo.height)
                    assertEquals(730f / 206f, logo.width.toFloat() / logo.height, 1f / logo.height)
                    assertFullyVisible(logo)
                    val buttons = descendants(row).filterIsInstance<ImageButton>().toList()
                    val expectedNames = when (name) {
                        "fleet" -> listOf(R.string.vpn0wn_navigate_back, R.string.vpn0wn_logs_title, R.string.settings)
                        "detail" -> listOf(R.string.vpn0wn_navigate_back, R.string.vpn0wn_more_actions)
                        "user" -> listOf(R.string.vpn0wn_navigate_back, R.string.vpn0wn_action_export)
                        else -> listOf(R.string.vpn0wn_navigate_back)
                    }.map(fixture.activity::getString)
                    assertEquals(expectedNames, buttons.map { it.contentDescription.toString() })
                    for (button in buttons) {
                        assertEquals(48 * button.resources.displayMetrics.density, button.width.toFloat(), 0f)
                        assertEquals(48 * button.resources.displayMetrics.density, button.height.toFloat(), 0f)
                        assertFullyVisible(button)
                    }
                    val title = when (name) {
                        "fleet" -> fixture.activity.getString(R.string.vpn0wn_servers_title).lowercase(Locale.ROOT)
                        "detail" -> server.name
                        "user" -> user.name
                        else -> fixture.activity.getString(R.string.vpn0wn_deploy_server_title)
                    }
                    assertTrue("Current context title is preserved", descendants(bar).filterIsInstance<TextView>().any { it.text.toString() == title })
                    if (name == "fleet" && case.id != "compact-rtl") {
                        val expected = fixture.activity.getString(R.string.vpn0wn_servers_summary, 0, 0).uppercase(Locale.ROOT)
                        val subtitle = descendants(bar).filterIsInstance<TextView>().single { it.text.toString() == expected }
                        assertSame("Normal Fleet metadata retains its artwork column", logo.parent, subtitle.parent)
                        assertTrue("Normal Fleet metadata stays below artwork", subtitle.top >= logo.bottom)
                    }
                    for (text in descendants(bar).filterIsInstance<TextView>()) {
                        assertFullyVisible(text)
                        val nativeLayout = requireNotNull(text.layout)
                        assertTrue("Header text is not ellipsized", (0 until nativeLayout.lineCount).all { nativeLayout.getEllipsisCount(it) == 0 })
                    }
                    assertEquals(case.direction, fixture.root.layoutDirection)
                    assertEquals(case.fontScale, fixture.root.resources.configuration.fontScale, 0f)
                    val expectedLanguage = when (case.id) { "wide-night", "short-rtl" -> "fr"; "compact-rtl" -> "ar"; else -> "en" }
                    assertEquals(expectedLanguage, fixture.root.resources.configuration.locales[0].language)
                    val expectedNight = if (case.id.contains("night") || case.id == "short-rtl") Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    assertEquals(expectedNight, fixture.root.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                    capture(fixture.root, "${case.id}-$name")
                }
            } finally {
                fixture.close()
            }
        }
    }

    @Test fun compactFleetMetadataKeepsWordsWholeAtEnlargedText() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val qualifiers = RuntimeEnvironment.getQualifiers()
        val fontScale = resources.configuration.fontScale
        val locale = Locale.getDefault()
        val case = Preview("fleet-h4-compact-rtl", "ar-rSA-w320dp-h562dp-port-notnight-mdpi", 320, 562, 2f, View.LAYOUT_DIRECTION_RTL)
        try {
            RuntimeEnvironment.setQualifiers(case.qualifiers)
            RuntimeEnvironment.setFontScale(case.fontScale)
            RuntimeEnvironment.setQualifiers("+ldrtl")
            val fixture = provisioningLayoutFixture(folder, case.direction)
            try {
                fixture.activity.supportActionBar?.hide()
                val screen = VpsProvisioningFragment::class.java.getDeclaredMethod("fleetScreen")
                    .apply { isAccessible = true }.invoke(fixture.fragment) as View
                fixture.root.removeAllViews()
                fixture.root.addView(screen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                runCurrent()
                layout(fixture.root.rootView, case)
                assertEquals("Native content width", case.width, fixture.root.width)
                assertEquals("Native content height", case.height, fixture.root.height)
                assertEquals("ar", fixture.root.resources.configuration.locales[0].language)
                assertEquals(case.fontScale, fixture.root.resources.configuration.fontScale, 0f)
                assertEquals(case.direction, fixture.root.layoutDirection)
                assertEquals(case.direction, fixture.root.resources.configuration.layoutDirection)
                assertEquals(Configuration.UI_MODE_NIGHT_NO,
                    fixture.root.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                val logo = requireNotNull(screen.findViewById<ImageView>(R.id.vpn0wn_vps_wordmark))
                val row = logo.parent.parent as ViewGroup
                val bar = row.parent as ViewGroup
                assertEquals("vpn0wn", logo.contentDescription.toString())
                assertFalse("The wordmark is not a navigation control", logo.isClickable)
                assertFalse("The approved artwork never mirrors", requireNotNull(logo.drawable).isAutoMirrored)
                assertEquals(logo.resources.getDimensionPixelSize(R.dimen.vpnown_home_wordmark_height), logo.height)
                assertEquals(730f / 206f, logo.width.toFloat() / logo.height, 1f / logo.height)
                assertFullyVisible(logo)
                val expected = fixture.activity.getString(R.string.vpn0wn_servers_summary, 0, 0).uppercase(Locale.ROOT)
                val subtitle = descendants(bar).filterIsInstance<TextView>().single { it.text.toString() == expected }
                assertEquals(expected, subtitle.text.toString())
                assertFullyVisible(subtitle)
                assertEquals(ResourcesCompat.getFont(subtitle.context, R.font.jetbrains_mono_regular), subtitle.typeface)
                assertEquals("ss01, zero, tnum", subtitle.fontFeatureSettings)
                assertEquals(10f * subtitle.resources.displayMetrics.scaledDensity, subtitle.textSize, 0.01f)
                assertEquals(0.08f, subtitle.letterSpacing, 0f)
                assertFalse("Metadata does not synthesize bold", subtitle.paint.isFakeBoldText)
                val nativeLayout = requireNotNull(subtitle.layout)
                assertTrue("Metadata has native lines", nativeLayout.lineCount > 0)
                assertEquals("Complete metadata is laid out", expected.length, nativeLayout.getLineEnd(nativeLayout.lineCount - 1))
                assertTrue("Complete metadata height", nativeLayout.height <= subtitle.height - subtitle.compoundPaddingTop - subtitle.compoundPaddingBottom)
                for (line in 0 until nativeLayout.lineCount) {
                    assertEquals("Metadata is not ellipsized", 0, nativeLayout.getEllipsisCount(line))
                }
                val logoBounds = Rect().also(logo::getDrawingRect)
                bar.offsetDescendantRectToMyCoords(logo, logoBounds)
                val subtitleBounds = Rect().also(subtitle::getDrawingRect)
                bar.offsetDescendantRectToMyCoords(subtitle, subtitleBounds)
                val buttons = descendants(row).filterIsInstance<ImageButton>().toList()
                assertEquals(listOf(R.string.vpn0wn_navigate_back, R.string.vpn0wn_logs_title, R.string.settings)
                    .map(fixture.activity::getString), buttons.map { it.contentDescription.toString() })
                for ((index, button) in buttons.withIndex()) {
                    assertEquals(48 * button.resources.displayMetrics.density, button.width.toFloat(), 0f)
                    assertEquals(48 * button.resources.displayMetrics.density, button.height.toFloat(), 0f)
                    assertFullyVisible(button)
                    val bounds = Rect().also(button::getDrawingRect)
                    bar.offsetDescendantRectToMyCoords(button, bounds)
                    assertFalse("Header actions do not overlap artwork", Rect.intersects(bounds, logoBounds))
                    assertFalse("Header actions do not overlap metadata", Rect.intersects(bounds, subtitleBounds))
                    for (other in buttons.drop(index + 1)) {
                        val otherBounds = Rect().also(other::getDrawingRect)
                        bar.offsetDescendantRectToMyCoords(other, otherBounds)
                        assertFalse("Header action targets do not overlap", Rect.intersects(bounds, otherBounds))
                    }
                }
                val title = descendants(bar).filterIsInstance<TextView>().single {
                    it.text.toString() == fixture.activity.getString(R.string.vpn0wn_servers_title).lowercase(Locale.ROOT)
                }
                assertFullyVisible(title)
                capture(fixture.root, case.id)
                for (line in 0 until nativeLayout.lineCount - 1) {
                    val end = nativeLayout.getLineEnd(line)
                    assertTrue("Fleet metadata keeps words whole: $expected",
                        end in 1 until expected.length && (expected[end - 1].isWhitespace() || expected[end].isWhitespace()))
                }
                assertEquals("Compact metadata spans bar content width", bar.width - bar.paddingLeft - bar.paddingRight, subtitle.width)
                assertEquals("Compact metadata starts at bar content edge", bar.paddingLeft, subtitleBounds.left)
                assertTrue("Compact metadata sits below artwork and actions", subtitleBounds.top >= row.bottom)
                assertTrue("Current context title stays below metadata", title.top >= subtitleBounds.bottom)
            } finally {
                fixture.close()
            }
        } finally {
            RuntimeEnvironment.setQualifiers(qualifiers)
            RuntimeEnvironment.setFontScale(fontScale)
            Locale.setDefault(locale)
        }
    }

    @Test fun fleetActionsRemainFullyReachableInShortEnlargedWindows() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val cases = listOf(
            Preview("fleet-compact-day", "en-rUS-w320dp-h640dp-port-notnight-mdpi", 320, 640, 1f, View.LAYOUT_DIRECTION_LTR),
            Preview("fleet-short-night-rtl", "fr-ldrtl-w640dp-h244dp-land-night-mdpi", 640, 244, 2f, View.LAYOUT_DIRECTION_RTL),
        )
        for (case in cases) {
            RuntimeEnvironment.setQualifiers(case.qualifiers)
            RuntimeEnvironment.setFontScale(case.fontScale)
            val fixture = provisioningLayoutFixture(folder, case.direction)
            try {
                fixture.activity.supportActionBar?.hide()
                val screen = VpsProvisioningFragment::class.java.getDeclaredMethod("fleetScreen")
                    .apply { isAccessible = true }.invoke(fixture.fragment) as View
                fixture.root.removeAllViews()
                fixture.root.addView(screen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                runCurrent()
                layout(fixture.root.rootView, case)
                assertEquals("Native content width", case.width, fixture.root.width)
                assertEquals("Native content height", case.height, fixture.root.height)
                assertEquals(case.direction, fixture.root.layoutDirection)
                assertEquals(case.qualifiers.substringBefore('-'), fixture.root.resources.configuration.locales[0].language)
                assertEquals(case.fontScale, fixture.root.resources.configuration.fontScale, 0f)
                assertEquals(if (case.id.contains("night")) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
                    fixture.root.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                val actionName = fixture.activity.getString(R.string.vpn0wn_add_server).removePrefix("+ ").trim()
                val action = descendants(screen).single { it.isClickable && it.contentDescription?.toString() == actionName }
                val scroll = descendants(screen).filterIsInstance<ScrollView>().single()
                val all = descendants(screen).filterIsInstance<TextView>().single { it.text.toString() == fixture.activity.getString(R.string.vpn0wn_filter_all) }
                val tile = generateSequence<View>(all) { it.parent as? View }.first { it.isClickable }
                val strip = tile.parent as View
                val stripBounds = Rect().also(strip::getDrawingRect)
                fixture.root.offsetDescendantRectToMyCoords(strip, stripBounds)
                assertEquals("Status band retains full width", Rect(0, stripBounds.top, case.width, stripBounds.bottom), stripBounds)
                val importAction = requireNotNull(screen.findViewById<View>(R.id.vpn0wn_vps_import_backup))
                val body = importAction.parent as View
                assertEquals("Existing logical body gutter", (14 * resources.displayMetrics.density).toInt(), body.paddingLeft)
                assertEquals(body.paddingLeft, body.paddingRight)
                capture(fixture.root, "${case.id}-top")
                assertTrue("Add server retains native minimum width", action.width >= 48 * resources.displayMetrics.density)
                assertTrue("Add server retains native minimum height", action.height >= 48 * resources.displayMetrics.density)
                action.requestRectangleOnScreen(Rect().also(action::getDrawingRect), true)
                runCurrent()
                layout(fixture.root.rootView, case)
                capture(fixture.root, "${case.id}-add")
                assertTrue("Complete Add server target fits native scroll viewport: ${action.height} within ${scroll.height}",
                    action.height <= scroll.height - scroll.paddingTop - scroll.paddingBottom)
                assertFullyVisible(action)
                val bounds = Rect().also(action::getDrawingRect)
                fixture.root.offsetDescendantRectToMyCoords(action, bounds)
                assertTrue("Complete Add server target fits captured root: $bounds", Rect(0, 0, case.width, case.height).contains(bounds))
            } finally {
                fixture.close()
            }
        }
    }

    private fun layout(root: View, case: Preview) {
        var ready = false
        repeat(3) {
            root.measure(View.MeasureSpec.makeMeasureSpec(case.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(case.height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            ready = !root.viewTreeObserver.dispatchOnPreDraw()
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue("Native header traversal is ready", ready)
    }

    private fun assertFullyVisible(view: View) {
        val visible = Rect()
        assertTrue("Visible ${view.javaClass.simpleName}", view.getGlobalVisibleRect(visible))
        assertEquals("Complete width", view.width, visible.width())
        assertEquals("Complete height", view.height, visible.height())
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun capture(root: View, name: String) {
        val project = File(requireNotNull(System.getProperty("user.dir"))).let { if (it.name == "ui") it.parentFile else it }
        val directory = File(project, ".artifacts/vps-header-previews").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }

    private data class Preview(val id: String, val qualifiers: String, val width: Int, val height: Int, val fontScale: Float, val direction: Int)
}
