package com.vpn0wn.android.fragment

import android.app.Application
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.vpn0wn.android.R
import com.vpn0wn.android.widget.VpsSectionHeaderLayout
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class VpsSectionHeaderLayoutTest {
    @get:Rule val folder = TemporaryFolder()

    @After fun tearDown() {
        RuntimeEnvironment.setFontScale(1f)
        Dispatchers.resetMain()
    }

    @Test fun normalHeadersRetainCompleteSingleRowWhereTheyFit() = verifyHeaders(1f)

    @Test fun enlargedHeadersWrapWithoutLosingTitleOrMetadata() = verifyHeaders(2f)

    private fun verifyHeaders(fontScale: Float) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (locale in listOf("en", "fr")) for (night in listOf("night", "notnight")) {
            for (direction in listOf("ldltr", "ldrtl")) for ((width, height) in listOf(320 to 640, 640 to 320)) {
                val orientation = if (width > height) "land" else "port"
                RuntimeEnvironment.setQualifiers("$locale-$direction-w${width}dp-h${height}dp-$orientation-$night-mdpi")
                RuntimeEnvironment.setFontScale(fontScale)
                val requestedDirection = if (direction == "ldrtl") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                val fixture = provisioningLayoutFixture(folder, requestedDirection)
                try {
                    val activity = fixture.activity
                    val root = fixture.root
                    val factory = VpsProvisioningFragment::class.java.getDeclaredMethod("sectionHeader",
                        Int::class.javaPrimitiveType, String::class.java, String::class.java).apply { isAccessible = true }
                    val headers = listOf(
                        R.string.vpn0wn_server_fleet to activity.getString(R.string.vpn0wn_self_hosted),
                        R.string.vpn0wn_section_health to activity.getString(R.string.vpn0wn_status_online),
                        R.string.vpn0wn_section_vpn_users to activity.getString(R.string.vpn0wn_peers_count, 3),
                        R.string.vpn0wn_section_network to "WG0",
                        R.string.vpn0wn_section_actions to activity.getString(R.string.vpn0wn_hint_remote),
                        R.string.vpn0wn_section_ssh_access to activity.getString(R.string.vpn0wn_hint_local),
                        R.string.vpn0wn_section_ssh_access to activity.getString(R.string.vpn0wn_hint_no_cloud_relay),
                        R.string.vpn0wn_step_detect to activity.getString(R.string.vpn0wn_hint_dry_run),
                        R.string.vpn0wn_section_network to activity.getString(R.string.vpn0wn_hint_review_before_apply),
                        R.string.vpn0wn_section_deployment_plan to activity.getString(R.string.vpn0wn_hint_review_before_apply),
                        R.string.vpn0wn_step_apply to activity.getString(R.string.vpn0wn_apply_unavailable),
                        R.string.vpn0wn_section_profile to activity.getString(R.string.vpn0wn_hint_wireguard),
                        R.string.vpn0wn_section_actions to activity.getString(R.string.vpn0wn_hint_device),
                    )
                    for ((titleResource, hintText) in headers) {
                        val titleText = activity.getString(titleResource).let {
                            if (titleResource == R.string.vpn0wn_step_detect || titleResource == R.string.vpn0wn_step_apply)
                                it.lowercase(Locale.ROOT) else it
                        }
                        val header = factory.invoke(fixture.fragment, 1, titleText, hintText) as VpsSectionHeaderLayout
                        root.removeAllViews()
                        root.addView(header, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            marginStart = 14
                            marginEnd = 14
                        })
                        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        root.layout(0, 0, width, height)
                        assertEquals("Actual requested direction", requestedDirection, header.layoutDirection)
                        for (index in 0 until header.childCount) {
                            val child = header.getChildAt(index)
                            assertTrue("Child remains inside header", child.left >= 0 && child.right <= header.width &&
                                child.top >= header.paddingTop && child.bottom <= header.height - header.paddingBottom)
                            if (child is TextView) assertTextFits(child)
                        }
                        val textBounds = (0 until header.childCount).map { header.getChildAt(it) }.filterIsInstance<TextView>()
                            .map { Rect(it.left, it.top, it.right, it.bottom) }
                        for (index in textBounds.indices) for (other in index + 1 until textBounds.size) {
                            assertTrue("Header labels do not overlap", !Rect.intersects(textBounds[index], textBounds[other]))
                        }
                        val index = header.getChildAt(0)
                        val title = header.getChildAt(1) as TextView
                        val divider = header.getChildAt(2)
                        val hint = header.getChildAt(3) as TextView
                        assertTrue("Title retains positive space", title.width > title.compoundPaddingLeft + title.compoundPaddingRight)
                        if (direction == "ldrtl") assertTrue("Mirrored index/title order", index.left >= title.right)
                        else assertTrue("Index/title order", index.right <= title.left)
                        if (locale == "en" && width == 320 && titleResource == R.string.vpn0wn_section_ssh_access &&
                            hintText == activity.getString(R.string.vpn0wn_hint_no_cloud_relay)) {
                            if (fontScale == 1f) {
                                assertTrue("Normal SSH header remains one row", hint.top < title.bottom && divider.width > 0)
                            } else {
                                assertTrue("Narrow enlarged hint moves below title", hint.top >= title.bottom)
                            }
                        }
                    }
                    assertTrue("Header renderer never opens SSH", fixture.peer.connections.isEmpty())
                } finally { fixture.close() }
            }
        }
    }

    private fun assertTextFits(view: TextView) {
        val layout = requireNotNull(view.layout)
        assertTrue("Complete text: ${view.text}", layout.lineCount > 0 && layout.getLineEnd(layout.lineCount - 1) == view.text.length)
        assertTrue("Text height: ${view.text}", layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
        val contentWidth = view.width - view.compoundPaddingLeft - view.compoundPaddingRight
        for (line in 0 until layout.lineCount) {
            assertEquals("No truncation: ${view.text}", 0, layout.getEllipsisCount(line))
            assertTrue("Text width: ${view.text}", layout.getLineLeft(line) >= 0 && layout.getLineRight(line) <= contentWidth)
        }
    }
}
