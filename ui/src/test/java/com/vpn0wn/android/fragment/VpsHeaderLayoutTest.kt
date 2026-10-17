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
import android.widget.TextView
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
                val server = VpsDeployMapper.fromStored(VpsDeployFixtureData.storedServers().first(), VpsDeployFixtureData.NOW)
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
