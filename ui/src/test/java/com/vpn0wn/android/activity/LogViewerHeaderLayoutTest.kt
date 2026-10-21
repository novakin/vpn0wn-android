/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.activity

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import com.vpn0wn.android.R
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LogViewerHeaderLayoutTest {
    @Test fun nativeHeaderTargetsAndMetadataFitWithoutStartingLogcat() {
        val cases = listOf(
            Preview("compact-day", "en-rUS-w320dp-h640dp-port-notnight-mdpi", 320, 640, 1f, View.LAYOUT_DIRECTION_LTR),
            Preview("wide-night", "fr-w360dp-h800dp-port-night-mdpi", 360, 800, 1f, View.LAYOUT_DIRECTION_LTR),
            Preview("short-rtl", "fr-ldrtl-w640dp-h320dp-land-night-mdpi", 640, 320, 2f, View.LAYOUT_DIRECTION_RTL),
            Preview("compact-rtl", "ar-rSA-ldrtl-w320dp-h640dp-port-notnight-mdpi", 320, 640, 2f, View.LAYOUT_DIRECTION_RTL),
        )
        try {
            for (case in cases) {
                RuntimeEnvironment.setQualifiers(case.qualifiers)
                RuntimeEnvironment.setFontScale(case.fontScale)
                val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
                controller.get().setTheme(R.style.AppTheme)
                controller.setup()
                val activity = controller.get()
                try {
                    val root = activity.layoutInflater.inflate(R.layout.log_viewer_activity, null) as ViewGroup
                    root.layoutDirection = case.direction
                    activity.setContentView(root)
                    var ready = false
                    repeat(3) {
                        root.measure(View.MeasureSpec.makeMeasureSpec(case.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(case.height, View.MeasureSpec.EXACTLY))
                        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                        ready = !root.viewTreeObserver.dispatchOnPreDraw()
                        shadowOf(Looper.getMainLooper()).idle()
                    }
                    assertTrue("Native Logs traversal is ready", ready)
                    val buttons = listOf(R.id.back_button, R.id.refresh_button, R.id.share_button).map { root.findViewById<ImageButton>(it) }
                    val labels = listOf(R.string.vpn0wn_navigate_back, R.string.refresh, R.string.log_export_title).map(activity::getString)
                    assertEquals(labels, buttons.map { it.contentDescription.toString() })
                    for (button in buttons) {
                        assertEquals(48 * button.resources.displayMetrics.density, button.width.toFloat(), 0f)
                        assertEquals(48 * button.resources.displayMetrics.density, button.height.toFloat(), 0f)
                        assertTrue("Native pressed/focus feedback", requireNotNull(button.background).isStateful)
                        assertFullyVisible(button)
                    }
                    val header = root.getChildAt(0) as ViewGroup
                    val texts = descendants(header).filterIsInstance<TextView>().toList()
                    val title = texts.single { it.text.toString() == activity.getString(R.string.log_viewer_title) }
                    assertEquals(ResourcesCompat.getFont(activity, R.font.jetbrains_mono_medium), title.typeface)
                    assertEquals("ss01, zero, tnum", title.fontFeatureSettings)
                    val live = root.findViewById<View>(R.id.live_toggle)
                    assertFullyVisible(live)
                    val titleBounds = Rect().also { title.getGlobalVisibleRect(it) }
                    val liveBounds = Rect().also { live.getGlobalVisibleRect(it) }
                    val gap = if (case.direction == View.LAYOUT_DIRECTION_RTL) titleBounds.left - liveBounds.right
                        else liveBounds.left - titleBounds.right
                    assertTrue("Context title and live action retain an 8dp logical gap", gap >= 8 * root.resources.displayMetrics.density)
                    for (text in texts) {
                        assertFullyVisible(text)
                        val layout = requireNotNull(text.layout)
                        assertTrue("Header text stays complete", (0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
                    }
                    val metadata = texts.single { it.text.toString() == activity.getString(R.string.vpn0wn_log_stream_subtitle) }
                    val metadataBounds = Rect().also { metadata.getGlobalVisibleRect(it) }
                    for (button in buttons) {
                        val bounds = Rect().also { button.getGlobalVisibleRect(it) }
                        assertFalse("Metadata never overlaps an action", Rect.intersects(metadataBounds, bounds))
                    }
                    assertEquals(case.direction, root.layoutDirection)
                    assertEquals(case.fontScale, root.resources.configuration.fontScale, 0f)
                    val expectedLanguage = when (case.id) { "wide-night", "short-rtl" -> "fr"; "compact-rtl" -> "ar"; else -> "en" }
                    assertEquals(expectedLanguage, root.resources.configuration.locales[0].language)
                    val expectedNight = if (case.id.contains("night") || case.id == "short-rtl") Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    assertEquals(expectedNight, root.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                    val project = File(requireNotNull(System.getProperty("user.dir"))).let { if (it.name == "ui") it.parentFile else it }
                    val directory = File(project, ".artifacts/log-header-previews").apply { mkdirs() }
                    val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    try {
                        root.draw(Canvas(bitmap))
                        File(directory, "${case.id}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } finally {
                        bitmap.recycle()
                    }
                } finally {
                    controller.pause().stop().destroy()
                }
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    private fun assertFullyVisible(view: View) {
        val visible = Rect()
        assertTrue(view.getGlobalVisibleRect(visible))
        assertEquals(view.width, visible.width())
        assertEquals(view.height, visible.height())
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private data class Preview(val id: String, val qualifiers: String, val width: Int, val height: Int, val fontScale: Float, val direction: Int)
}
