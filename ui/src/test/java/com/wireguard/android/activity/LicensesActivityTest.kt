package com.wireguard.android.activity

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class LicensesActivityTest {
    private val controllers = mutableListOf<ActivityController<LicensesActivity>>()

    @After fun tearDown() {
        controllers.forEach { it.pause().stop().destroy() }
        Dispatchers.resetMain()
        RuntimeEnvironment.setQualifiers("")
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test fun offlineFullTextsRemainSelectableAcrossRecreationAndBack() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = open()
        val activity = controller.get()
        settle(activity)
        val texts = notices(activity)
        val payload = activity.assets.open("licenses/credits.txt").bufferedReader().use { it.readText() }
        assertEquals(payload, texts.joinToString("") { it.text.toString() })
        for (text in texts) {
            assertTrue(text.isTextSelectable)
            assertEquals(View.TEXT_DIRECTION_LTR, text.textDirection)
        }
        val sourceLicense = File("../COPYING").readLines().joinToString("\n") { it.trimEnd() }
        assertTrue(payload.contains(sourceLicense.trim()))
        for (font in listOf("inter-tight-OFL.txt", "jetbrains-mono-OFL.txt", "sora-OFL.txt")) {
            val notice = activity.assets.open("licenses/$font").bufferedReader().use { it.readText() }
            val fullNotice = notice.lineSequence().joinToString("\n") { it.trimEnd() }
            assertTrue(payload.contains(fullNotice.trim()))
        }
        for (marker in listOf("WireGuard LLC", "Jason A. Donenfeld", "GNU GENERAL PUBLIC LICENSE",
            "GNU LESSER GENERAL PUBLIC LICENSE", "Classpath", "Go Authors", "Nyr", "ZXing", "Protocol Buffers")) {
            assertTrue("Missing notice: $marker", payload.contains(marker))
        }
        assertFalse(payload.contains("wireguard.com/donations"))
        val info = activity.packageManager.getActivityInfo(ComponentName(activity, LicensesActivity::class.java), 0)
        assertFalse(info.exported)
        controller.recreate()
        settle(controller.get())
        assertEquals(payload, notices(controller.get()).joinToString("") { it.text.toString() })
        controller.get().findViewById<View>(R.id.licenses_back).performClick()
        assertTrue(controller.get().isFinishing)
    }

    @Test @Config(sdk = [36]) fun everyFullNoticeReachesAccessibilityWithoutParcelTruncation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val activity = open().get()
        settle(activity)
        val payload = activity.assets.open("licenses/credits.txt").bufferedReader().use { it.readText() }
        val unsplitText = TextView(activity).apply { text = payload }
        assertEquals(ACCESSIBILITY_TEXT_LIMIT, unsplitText.createAccessibilityNodeInfo().text.length)
        val texts = notices(activity)
        assertEquals(28, texts.size)
        val accessibleTexts = texts.map { text ->
            assertTrue(text.text.length < ACCESSIBILITY_TEXT_LIMIT)
            val accessibleText = text.createAccessibilityNodeInfo().text.toString()
            assertEquals(text.text.toString(), accessibleText)
            accessibleText
        }
        assertEquals(payload, accessibleTexts.joinToString(""))
        val fontNotice = activity.assets.open("licenses/jetbrains-mono-OFL.txt").bufferedReader().use { it.readText() }
        val fullFontNotice = fontNotice.lineSequence().joinToString("\n") { it.trimEnd() }.trim()
        assertTrue(accessibleTexts.any { it.contains(fullFontNotice) })
        val termuxNotice = accessibleTexts.single { it.startsWith("Termux elf-cleaner — host build helper\n") }
        assertTrue(termuxNotice.contains("GNU GENERAL PUBLIC LICENSE"))
        assertEquals(payload.substring(payload.indexOf("Termux elf-cleaner — host build helper\n"), payload.indexOf("Sora brand wordmark\n")), termuxNotice)
        val soraNotice = activity.assets.open("licenses/sora-OFL.txt").bufferedReader().use { it.readText() }
        assertTrue(accessibleTexts.last().startsWith("Sora brand wordmark\n"))
        assertTrue(accessibleTexts.last().contains(soraNotice.lineSequence().joinToString("\n") { it.trimEnd() }.trim()))
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun nativeOutputWrapsAndScrollsAtSmallLargeTextAndRtlSizesInBothThemes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val cases = listOf(
            LayoutCase("portrait-light", 320, 640, 1f, "en", false),
            LayoutCase("portrait-dark", 320, 640, 1f, "en", true),
            LayoutCase("landscape-dark", 640, 320, 1f, "en", true),
            LayoutCase("large-rtl-light", 320, 640, 2f, "ar", false),
            LayoutCase("large-rtl-dark", 320, 640, 2f, "ar", true)
        )
        for (case in cases) {
            val direction = if (case.locale == "ar") "ldrtl" else "ldltr"
            val orientation = if (case.width > case.height) "land" else "port"
            val theme = if (case.night) "night" else "notnight"
            RuntimeEnvironment.setQualifiers("${case.locale}-$direction-w${case.width}dp-h${case.height}dp-$orientation-$theme")
            RuntimeEnvironment.setFontScale(case.fontScale)
            val activity = open().get()
            settle(activity)
            val root = activity.findViewById<View>(R.id.licenses_root)
            val density = activity.resources.displayMetrics.density
            val width = (case.width * density).toInt()
            val height = (case.height * density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val back = root.findViewById<View>(R.id.licenses_back)
            assertTrue(back.width >= 48 * density)
            assertTrue(back.height >= 48 * density)
            assertEquals(if (case.locale == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR, root.layoutDirection)
            val scroll = root.findViewById<ScrollView>(R.id.licenses_scroll)
            assertTrue(scroll.height > 0)
            assertTrue(scroll.canScrollVertically(1))
            for (text in notices(activity)) {
                assertTrue(text.layout.getLineTop(text.lineCount) <= text.height)
                assertEquals(View.LAYOUT_DIRECTION_LTR, text.layoutDirection)
                assertEquals(View.TEXT_DIRECTION_LTR, text.textDirection)
            }
            preview(root, width, height, "${case.name}-top")
            scroll.scrollTo(0, root.findViewById<View>(R.id.licenses_content).height)
            assertTrue(scroll.scrollY > 0)
            assertFalse(scroll.canScrollVertically(1))
            preview(root, width, height, "${case.name}-bottom")
        }
    }

    @Test @Config(sdk = [36]) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun keyboardFocusKeepsNoticeTextReadableAndSelectionAvailable() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val activity = open().get()
        settle(activity)
        val root = LinearLayout(activity).apply {
            isFocusableInTouchMode = true
            setBackgroundColor(activity.getColor(R.color.tunnl_paper))
        }
        val notice = activity.layoutInflater.inflate(R.layout.licenses_notice, root, false) as TextView
        notice.text = "Sora brand wordmark\nSIL OPEN FONT LICENSE Version 1.1"
        root.addView(notice)
        activity.setContentView(root)
        val width = 640
        val height = 160
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        assertTrue(root.requestFocusFromTouch())
        val ink = activity.getColor(R.color.tunnl_ink)
        val signal = activity.getColor(R.color.tunnl_signal)
        val unfocusedInk = renderedColorCount(root, width, height, ink)
        assertTrue(unfocusedInk > 0)
        assertEquals(0, renderedColorCount(root, width, height, signal))
        assertTrue(notice.requestFocusFromTouch())
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(notice.isFocused)
        assertFalse(notice.isInTouchMode)
        assertEquals(unfocusedInk, renderedColorCount(root, width, height, ink))
        assertTrue("Keyboard focus needs a visible indicator", renderedColorCount(root, width, height, signal) > 0)
        assertTrue(notice.isTextSelectable)
        assertEquals(notice.text.toString(), notice.createAccessibilityNodeInfo().text.toString())
    }

    private fun renderedColorCount(view: View, width: Int, height: Int, color: Int): Int {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        return pixels.count { it == color }
    }

    private fun notices(activity: LicensesActivity): List<TextView> {
        val content = activity.findViewById<LinearLayout>(R.id.licenses_content)
        return (0 until content.childCount).map { content.getChildAt(it) as TextView }
    }

    private fun open(): ActivityController<LicensesActivity> {
        val controller = Robolectric.buildActivity(LicensesActivity::class.java)
        controllers += controller
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
        return controller
    }

    private suspend fun TestScope.settle(activity: LicensesActivity) {
        runCurrent()
        activity.lifecycleScope.coroutineContext[Job]!!.children.toList().joinAll()
        runCurrent()
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun preview(root: View, width: Int, height: Int, name: String) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val directory = File("../.artifacts/licenses-previews").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private data class LayoutCase(val name: String, val width: Int, val height: Int, val fontScale: Float, val locale: String, val night: Boolean)

    private companion object {
        const val ACCESSIBILITY_TEXT_LIMIT = 100_000
    }
}
