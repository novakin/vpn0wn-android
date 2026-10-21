/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.widget

import android.app.Application
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.ContextThemeWrapper
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.vpn0wn.android.R
import com.vpn0wn.android.provisioning.VpsDeployFixtureData
import com.vpn0wn.android.provisioning.VpsDeployMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CanvasTypographyTest {
    private val resources get() = RuntimeEnvironment.getApplication().resources

    @Test fun throughputUsesPackagedRegularAndMediumWithoutSyntheticBold() {
        val context = context()
        val view = TunnelThroughputView(context)
        assertFont(context, paint(view, "textPaint"), R.font.jetbrains_mono_regular)
        assertFont(context, paint(view, "labelPaint"), R.font.jetbrains_mono_medium)
        val density = context.resources.displayMetrics.density
        assertEquals(10f * density, paint(view, "textPaint").textSize, 0f)
        assertEquals(9.5f * density, paint(view, "labelPaint").textSize, 0f)
    }

    @Test fun localizedThroughputCaptionsFitNativeOutputAcrossCompactLayouts() {
        val originalQualifiers = RuntimeEnvironment.getQualifiers()
        val originalScale = resources.configuration.fontScale
        try {
            for (locale in listOf("en", "fr", "ar", "fa")) {
                for (night in listOf(false, true)) {
                    val sizes = if (locale in listOf("ar", "fa")) listOf(320 to 2f) else {
                        listOf(320 to 1f, 320 to 2f, 640 to 1f, 640 to 2f)
                    }
                    for ((widthDp, scale) in sizes) renderThroughputCaptions(locale, night, widthDp, scale)
                }
            }
        } finally {
            RuntimeEnvironment.setQualifiers(originalQualifiers)
            RuntimeEnvironment.setFontScale(originalScale)
        }
    }

    private fun renderThroughputCaptions(locale: String, night: Boolean, widthDp: Int, scale: Float) {
        val rtl = locale in listOf("ar", "fa")
        val direction = if (rtl) "ldrtl" else "ldltr"
        val heightDp = if (widthDp == 320) 640 else 320
        val orientation = if (widthDp == 320) "port" else "land"
        val theme = if (night) "night" else "notnight"
        RuntimeEnvironment.setQualifiers("$locale-$direction-w${widthDp}dp-h${heightDp}dp-$orientation-$theme")
        RuntimeEnvironment.setFontScale(scale)
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
        try {
            val context = controller.get()
            val view = TunnelThroughputView(context)
            controller.get().setContentView(view)
            view.setAddress("192.0.2.2")
            view.setEndpoint("192.0.2.1:51820")
            view.setMtu("1280")
            view.setRates("2 KiB/s", "1 KiB/s")
            view.live = night
            view.connecting = !night
            view.setBackgroundColor(context.getColor(R.color.vpn0wn_paper_2))
            val density = view.resources.displayMetrics.density
            val width = ((widthDp - 28) * density).toInt()
            val height = (206 * density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            assertEquals(locale, view.resources.configuration.locales[0].language)
            assertEquals(scale, view.resources.configuration.fontScale, 0f)
            assertEquals(if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR, view.layoutDirection)
            assertEquals(if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
                view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            val labels = if (locale == "fr") {
                listOf("APPAREIL", "CHIFFRÉ · CHACHA20-POLY1305", "PAIR")
            } else {
                listOf("DEVICE", "ENCRYPTED · CHACHA20-POLY1305", "PEER")
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = CaptionCanvas(bitmap)
                view.draw(canvas)
                val captions = canvas.text.filter { it.first in labels }
                assertEquals("All complete captions are painted", labels, captions.map { it.first })
                for ((label, bounds) in captions) {
                    assertTrue("$locale $label glyphs fit: $bounds", RectF(0f, 0f, width.toFloat(), height.toFloat()).contains(bounds))
                }
                for (technical in listOf("192.0.2.2", "192.0.2.1:51820", "MTU 1280")) {
                    assertTrue("Technical value preserved: $technical", canvas.text.any { it.first == technical })
                }
                val directory = System.getenv("VPN0WN_CANVAS_PREVIEW_DIR")?.let(::File)
                if (directory != null) {
                    directory.mkdirs()
                    File(directory, "$locale-$theme-${widthDp}dp-${scale}x.png").outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                }
            } finally {
                bitmap.recycle()
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private class CaptionCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val text = mutableListOf<Pair<String, RectF>>()

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            val bounds = Rect()
            paint.getTextBounds(text, 0, text.length, bounds)
            val offset = when (paint.textAlign) {
                Paint.Align.LEFT -> 0f
                Paint.Align.CENTER -> paint.measureText(text) / 2f
                Paint.Align.RIGHT -> paint.measureText(text)
            }
            this.text += text to RectF(bounds).apply { offset(x - offset, y) }
            super.drawText(text, x, y, paint)
        }
    }

    @Test fun topologyUsesPackagedRegularAndSharedFeatures() {
        val context = context()
        val view = VpsTopologyView(context)
        view.bind(VpsDeployMapper.fromStored(resources, VpsDeployFixtureData.storedServers().first(), VpsDeployFixtureData.NOW))
        assertFont(context, paint(view, "paint"), R.font.jetbrains_mono_regular)
    }

    @Test fun topologyFitsCentralNameOnResizeAndRebindWithoutMutatingServer() {
        val context = context()
        val view = VpsTopologyView(context)
        val server = VpsDeployMapper.fromStored(resources, VpsDeployFixtureData.storedServers().first(), VpsDeployFixtureData.NOW)
            .copy(name = "synthetic-server-with-a-long-context-name-and-extra-device-context", endpoint = "2001:db8:1234:5678::42", cidr = "10.123.240.0/24")
        view.bind(server)
        val density = context.resources.displayMetrics.density
        val originals = listOf(server.name, server.endpoint, server.cidr)
        for (width in listOf(320, 640, 320)) {
            view.layout(0, 0, (width * density).toInt(), (104 * density).toInt())
            val fittedName = field(view, "serverSublabel") as String
            if (fittedName != server.name) assertTrue("Truncation is disclosed", fittedName.endsWith("…"))
            val nodePaint = Paint(paint(view, "paint")).apply { textSize = (7 * density).toInt().toFloat() }
            val nameWidth = nodePaint.measureText(field(view, "serverSublabel") as String)
            val leftEdge = view.width * 0.12f + nodePaint.measureText(field(view, "deviceSublabel") as String) / 2f
            val rightEdge = view.width * 0.88f - nodePaint.measureText(field(view, "wanSublabel") as String) / 2f
            assertTrue("Central name clears DEVICE", view.width / 2f - nameWidth / 2f >= leftEdge + 8 * density)
            assertTrue("Central name clears WAN", view.width / 2f + nameWidth / 2f <= rightEdge - 8 * density)
        }
        assertSame("Original bound server retained", server, field(view, "server"))
        assertEquals("Original values remain complete", originals, listOf(server.name, server.endpoint, server.cidr))
        val short = server.copy(name = "ams", endpoint = null, cidr = "—")
        view.bind(short)
        assertEquals("ams", field(view, "serverSublabel"))
    }

    private fun context(): Context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)

    private fun paint(view: View, name: String) = field(view, name) as Paint

    private fun field(view: View, name: String): Any = view.javaClass.getDeclaredField(name).run {
        isAccessible = true
        get(view)!!
    }

    private fun assertFont(context: Context, paint: Paint, font: Int) {
        val expected = requireNotNull(ResourcesCompat.getFont(context, font))
        assertEquals("Packaged face", expected, paint.typeface)
        assertEquals("Actual face weight", expected.weight, paint.typeface.weight)
        assertEquals("Shared Mono features", "ss01, zero, tnum", paint.fontFeatureSettings)
        assertFalse("No fake bold", paint.isFakeBoldText)
    }
}
