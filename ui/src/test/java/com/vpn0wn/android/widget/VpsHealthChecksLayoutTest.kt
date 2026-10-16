/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.widget

import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.vpn0wn.android.R
import com.vpn0wn.android.provisioning.VpsDeployFixtureData
import com.vpn0wn.android.provisioning.VpsDeployHealthCheck
import com.vpn0wn.android.provisioning.VpsDeployMapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VpsHealthChecksLayoutTest {
    @After fun restoreFontScale() = RuntimeEnvironment.setFontScale(1f)

    @Test fun normalChecksRetainAllLabelsAndWrapWithinAvailableWidth() = verifyLayouts(1f)

    @Test fun enlargedChecksRetainCompleteTextInNarrowAndMirroredLayouts() = verifyLayouts(2f)

    private fun verifyLayouts(fontScale: Float) {
        val mappedChecks = VpsDeployFixtureData.storedServers().map {
            VpsDeployMapper.fromStored(it, VpsDeployFixtureData.NOW).health
        }
        for (locale in listOf("en", "fr")) for (night in listOf("night", "notnight")) {
            for (direction in listOf("ldltr", "ldrtl")) for (width in listOf(160, 320, 640)) {
                val height = if (width == 640) 320 else 640
                val orientation = if (width > height) "land" else "port"
                RuntimeEnvironment.setQualifiers("$locale-$direction-w${width}dp-h${height}dp-$orientation-$night-mdpi")
                RuntimeEnvironment.setFontScale(fontScale)
                val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
                val activity = controller.get().apply { setTheme(R.style.AppTheme) }
                controller.setup()
                try {
                    val root = FrameLayout(activity).apply {
                        layoutDirection = if (direction == "ldrtl") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    }
                    activity.setContentView(root)
                    for (checks in mappedChecks + listOf(translatedLabelStressChecks(activity))) {
                        val layout = VpsHealthChecksLayout(activity, checks).apply { setPadding(14, 10, 14, 10) }
                        root.removeAllViews()
                        root.addView(layout, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT))
                        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        root.layout(0, 0, width, height)
                        assertEquals("Requested direction", root.layoutDirection, layout.layoutDirection)
                        val badges = (0 until layout.childCount).map { layout.getChildAt(it) }.filterIsInstance<TextView>()
                        assertEquals("All checks retained", checks.size, badges.size)
                        checks.zip(badges).forEach { (check, badge) -> assertVisibleCheck(layout, check, badge) }
                        for (index in badges.indices) for (other in index + 1 until badges.size) {
                            assertFalse("Checks do not overlap", Rect.intersects(bounds(badges[index]), bounds(badges[other])))
                        }
                        val first = badges.first()
                        if (direction == "ldrtl") assertEquals("RTL starts at right", layout.width - layout.paddingRight, first.right)
                        else assertEquals("LTR starts at left", layout.paddingLeft, first.left)
                        if (width == 160) assertTrue("Narrow checks wrap into rows", badges.map { it.top }.distinct().size > 1)
                        if (width == 160 && fontScale == 2f) assertTrue("Wide labels grow vertically", badges.any { it.lineCount > 1 })
                        if (width == 640 && fontScale == 1f) assertEquals("Roomy checks keep one row", 1, badges.map { it.top }.distinct().size)
                    }
                } finally {
                    controller.pause().stop().destroy()
                }
            }
        }
    }

    private fun assertVisibleCheck(parent: ViewGroup, check: VpsDeployHealthCheck, badge: TextView) {
        val (glyph, colorRes) = when (check.ok) {
            true -> "◆" to R.color.vpn0wn_signal_deep
            false -> "✕" to R.color.vpn0wn_error
            null -> "◑" to R.color.vpn0wn_warn
        }
        assertEquals("Complete original case", "$glyph ${check.label}", badge.text.toString())
        assertEquals("Status palette", ContextCompat.getColor(badge.context, colorRes), badge.currentTextColor)
        assertEquals("Real regular font", ResourcesCompat.getFont(badge.context, R.font.jetbrains_mono_regular), badge.typeface)
        assertEquals("Shared mono features", "ss01, zero, tnum", badge.fontFeatureSettings)
        assertFalse("Shared label omits extra font padding", badge.includeFontPadding)
        assertEquals("Badge tracking", 0.05f, badge.letterSpacing, 0f)
        assertFalse("Read-only check", badge.isClickable || badge.isFocusable)
        assertTrue("Inside horizontal padding", badge.left >= parent.paddingLeft && badge.right <= parent.width - parent.paddingRight)
        assertTrue("Inside vertical padding", badge.top >= parent.paddingTop && badge.bottom <= parent.height - parent.paddingBottom)
        val visible = Rect()
        assertTrue("Badge visible through ancestors", badge.getLocalVisibleRect(visible))
        assertEquals("Entire badge visible", Rect(0, 0, badge.width, badge.height), visible)
        val textLayout = requireNotNull(badge.layout)
        assertTrue("All characters laid out", textLayout.lineCount > 0 &&
            textLayout.getLineEnd(textLayout.lineCount - 1) == badge.text.length)
        assertTrue("Full text height", textLayout.height <= badge.height - badge.compoundPaddingTop - badge.compoundPaddingBottom)
        val textWidth = badge.width - badge.compoundPaddingLeft - badge.compoundPaddingRight
        for (line in 0 until textLayout.lineCount) {
            assertEquals("No ellipsis", 0, textLayout.getEllipsisCount(line))
            assertTrue("Full line width", textLayout.getLineLeft(line) >= 0 && textLayout.getLineRight(line) <= textWidth)
        }
    }

    private fun translatedLabelStressChecks(context: Context) = listOf(
        VpsDeployHealthCheck(context.getString(R.string.vpn0wn_section_health), true),
        VpsDeployHealthCheck(context.getString(R.string.vpn0wn_section_deployment_plan), false),
        VpsDeployHealthCheck(context.getString(R.string.vpn0wn_hint_no_cloud_relay), null),
        VpsDeployHealthCheck(context.getString(R.string.vpn0wn_label_last_sync), true),
    )

    private fun bounds(view: View) = Rect(view.left, view.top, view.right, view.bottom)
}
