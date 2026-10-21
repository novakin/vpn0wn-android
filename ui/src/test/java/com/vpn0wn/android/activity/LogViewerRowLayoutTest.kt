/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.activity

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import com.vpn0wn.android.R
import com.vpn0wn.android.util.LogBuffer
import com.vpn0wn.android.widget.VpsTerminalStreamView
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LogViewerRowLayoutTest {
    @Test fun populatedRowsKeepMessagesReadableAndExpandableAcrossConfigurations() {
        val originalQualifiers = RuntimeEnvironment.getQualifiers()
        val originalFontScale = RuntimeEnvironment.getApplication().resources.configuration.fontScale
        val originalLocale = Locale.getDefault()
        try {
            for ((width, height, scale) in listOf(Triple(320, 640, 1f), Triple(320, 640, 2f), Triple(640, 320, 2f))) {
                for (night in listOf(false, true)) {
                    val rtl = scale > 1f
                    val locale = if (rtl) "ar" else "en"
                    val direction = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    RuntimeEnvironment.setQualifiers("$locale-${if (rtl) "ldrtl" else "ldltr"}-w${width}dp-h${height}dp-${if (width > height) "land" else "port"}-${if (night) "night" else "notnight"}-mdpi")
                    RuntimeEnvironment.setFontScale(scale)
                    // Robolectric reapplies locale direction after combined qualifiers.
                    RuntimeEnvironment.setQualifiers(if (rtl) "+ldrtl" else "+ldltr")
                    val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
                    val activity = controller.get().apply { setTheme(R.style.AppTheme) }
                    controller.setup()
                    try {
                        val parent = FrameLayout(activity).apply { layoutDirection = direction }
                        activity.setContentView(parent)
                        val owner = Robolectric.buildActivity(LogViewerActivity::class.java).get().apply { setTheme(R.style.AppTheme) }
                        val adapterClass = LogViewerActivity::class.java.declaredClasses.single { it.simpleName == "LogEntryAdapter" }
                        @Suppress("UNCHECKED_CAST")
                        val adapter = adapterClass.getDeclaredConstructor(LogViewerActivity::class.java).apply { isAccessible = true }
                            .newInstance(owner) as RecyclerView.Adapter<RecyclerView.ViewHolder>
                        val buffer = ReflectionHelpers.callInstanceMethod<LogBuffer>(owner, "getLogBuffer")
                        val tag = "vpn0wn/SyntheticVeryLongTunnelManagerTag"
                        val message = "Synthetic endpoint [2001:db8:1234:5678::42]:51820 completed a state transition; " +
                            "the complete diagnostic message must remain available after expansion."
                        buffer.append(listOf("10-17 12:34:56.789 1 2 E $tag: $message", "10-17 12:34:57.123 1 2 W Short: rebound"))
                        val holder = adapter.createViewHolder(parent, 0)
                        adapter.bindViewHolder(holder, 0)
                        parent.addView(holder.itemView)
                        measure(parent, width, height)
                        assertEquals(direction, parent.layoutDirection)
                        assertEquals(direction, holder.itemView.layoutDirection)
                        assertEquals(direction, parent.resources.configuration.layoutDirection)
                        assertEquals(locale, parent.resources.configuration.locales[0].language)
                        assertEquals(scale, parent.resources.configuration.fontScale)
                        assertEquals(if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
                            parent.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                        val time = holder.itemView.findViewById<TextView>(R.id.log_time)
                        val level = holder.itemView.findViewById<TextView>(R.id.log_level)
                        val tagView = holder.itemView.findViewById<TextView>(R.id.log_tag)
                        val text = holder.itemView.findViewById<TextView>(R.id.log_msg)
                        assertEquals("12:34:56.789", time.text.toString())
                        assertEquals("E", level.text.toString())
                        assertEquals(tag, tagView.text.toString())
                        assertEquals(message, text.text.toString())
                        assertAccessibleText(tagView, tag)
                        assertAccessibleText(text, message)
                        assertTrue("Whole entry is the single expand owner", holder.itemView.isClickable && holder.itemView.isFocusable)
                        assertTrue("Whole entry provides native feedback", requireNotNull(holder.itemView.foreground).isStateful)
                        assertTrue("Whole entry has a 48dp target", holder.itemView.height >= 48 * parent.resources.displayMetrics.density)
                        assertFalse("Message has no competing action", text.isClickable || text.hasOnClickListeners())
                        assertActionContext(holder.itemView, "12:34:56.789 E $tag: $message")
                        val content = text.parent as View
                        assertEquals("Message has the complete content width", content.width - content.paddingLeft - content.paddingRight, text.width)
                        assertTrue("Message retains positive capacity", text.width > 0)
                        assertTrue("Message follows metadata", text.top >= (tagView.parent as View).bottom)
                        for (view in listOf(time, level, tagView, text)) assertInside(view, holder.itemView)
                        for (view in listOf(time, level, tagView, text)) assertMonoFeatures(view)
                        val rail = holder.itemView.findViewById<View>(R.id.level_border)
                        assertEquals(2, rail.width)
                        assertEquals(ContextCompat.getColor(activity, R.color.vpn0wn_error), (rail.background as ColorDrawable).color)
                        val name = "$width-${if (rtl) "rtl" else "ltr"}-$scale-${if (night) "night" else "day"}"
                        save(holder.itemView, "$name-collapsed")
                        assertTrue(holder.itemView.performClick())
                        measure(parent, width, height)
                        assertTrue("Actual adapter expands message", text.lineCount > 1)
                        assertCompleteLines(text)
                        assertAccessibleText(text, message)
                        save(holder.itemView, "$name-expanded")
                        assertTrue(holder.itemView.performClick())
                        measure(parent, width, height)
                        assertEquals("Actual adapter collapses message", 1, text.lineCount)
                        adapter.bindViewHolder(holder, 1)
                        measure(parent, width, height)
                        assertEquals("Rebound metadata", "Short", tagView.text.toString())
                        assertEquals("Rebound message", "rebound", text.text.toString())
                        assertActionContext(holder.itemView, "12:34:57.123 W Short: rebound")
                        assertEquals(ContextCompat.getColor(activity, R.color.vpn0wn_warn), (rail.background as ColorDrawable).color)
                    } finally { controller.pause().stop().destroy() }
                }
            }
        } finally {
            RuntimeEnvironment.setQualifiers(originalQualifiers)
            RuntimeEnvironment.setFontScale(originalFontScale)
            Locale.setDefault(originalLocale)
        }
    }

    @Test fun actualPlanTerminalRetainsItsPackagedFaceAndSharedFeatures() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.AppTheme) }
        controller.setup()
        try {
            val terminal = VpsTerminalStreamView(activity)
            terminal.setLines(listOf("synthetic step 01", "endpoint 203.0.113.42"), false)
            assertEquals("synthetic step 01\nendpoint 203.0.113.42", terminal.text.toString())
            assertEquals(ResourcesCompat.getFont(activity, R.font.jetbrains_mono_regular), terminal.typeface)
            assertMonoFeatures(terminal)
        } finally { controller.pause().stop().destroy() }
    }

    private fun measure(view: View, width: Int, height: Int) {
        repeat(2) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
        }
        assertFalse("Native draw accepted", view.viewTreeObserver.dispatchOnPreDraw())
    }

    private fun assertMonoFeatures(view: TextView) {
        assertEquals("ss01, zero, tnum", view.fontFeatureSettings)
        assertFalse(view.paint.isFakeBoldText)
    }

    private fun assertAccessibleText(view: TextView, expected: String) {
        val node = view.createAccessibilityNodeInfo()
        try { assertEquals(expected, node.text.toString()) } finally { node.recycle() }
    }

    private fun assertActionContext(view: View, expected: String) {
        val node = view.createAccessibilityNodeInfo()
        try {
            assertEquals("Complete current entry context", expected, node.contentDescription.toString())
            assertTrue(node.isClickable)
        } finally { node.recycle() }
    }

    private fun assertInside(view: View, root: View) {
        val bounds = Rect()
        assertTrue(view.getGlobalVisibleRect(bounds))
        assertEquals(view.width, bounds.width())
        assertEquals(view.height, bounds.height())
        val rootBounds = Rect()
        root.getGlobalVisibleRect(rootBounds)
        assertTrue(rootBounds.contains(bounds))
    }

    private fun assertCompleteLines(view: TextView) {
        val layout = requireNotNull(view.layout)
        assertEquals(view.text.length, layout.getLineEnd(layout.lineCount - 1))
        for (line in 0 until layout.lineCount) {
            assertEquals(0, layout.getEllipsisCount(line))
            assertTrue(layout.getLineLeft(line) >= 0 && layout.getLineRight(line) <= view.width)
        }
        assertTrue(layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
    }

    private fun save(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val directory = File("../.artifacts/log-row-previews").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
