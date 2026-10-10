package com.wireguard.android

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import java.io.File
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrandAssetsTest {
    @Test fun applicationLabelKeepsProductAndBuildVariantIdentity() {
        val expected = if (BuildConfig.DEBUG) "vpn0wn β" else "vpn0wn"
        assertEquals(expected, RuntimeEnvironment.getApplication().getString(R.string.app_name))
    }

    @Test fun adaptiveGradientAndMonochromeKeepCounterAndCentralSafeArea() {
        val icon = drawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        val foreground = render(icon.foreground, ADAPTIVE_LAYER_SIZE, ADAPTIVE_LAYER_SIZE)
        val monochrome = render(requireNotNull(icon.monochrome), ADAPTIVE_LAYER_SIZE, ADAPTIVE_LAYER_SIZE)
        val center = ADAPTIVE_LAYER_SIZE / 2.0
        val foregroundBounds = inkBounds(foreground)
        assertTrue("adaptive logo remains visible", foregroundBounds.height() >= 48)
        assertTrue("adaptive logo stays centered", foregroundBounds.exactCenterX() in 53f..55f)
        assertTrue("adaptive logo stays centered", foregroundBounds.exactCenterY() in 53f..55f)
        for (y in 0 until foreground.height) {
            for (x in 0 until foreground.width) {
                val alpha = Color.alpha(foreground.getPixel(x, y))
                // Skia rounds gradient and solid edge coverage one alpha level apart.
                assertEquals("adaptive layer alpha at ($x,$y)", alpha.toDouble(), Color.alpha(monochrome.getPixel(x, y)).toDouble(), 1.0)
                if (alpha != 0) {
                    assertTrue("adaptive ink outside central 66dp safe circle", hypot(x + 0.5 - center, y + 0.5 - center) <= ADAPTIVE_SAFE_RADIUS)
                }
            }
        }
        assertEquals("foreground counter remains transparent", 0, Color.alpha(foreground.getPixel(50, 45)))
        assertEquals("monochrome counter remains transparent", 0, Color.alpha(monochrome.getPixel(50, 45)))
        assertSolidWhite(monochrome)
        val upperBody = foreground.getPixel(43, 35)
        val lowerBody = foreground.getPixel(66, 71)
        assertEquals(255, Color.alpha(upperBody))
        assertEquals(255, Color.alpha(lowerBody))
        assertTrue("display mark retains its vertical gradient", Color.red(upperBody) > Color.red(lowerBody) + 20)
        save(foreground, "adaptive-foreground-108")
        save(monochrome, "adaptive-monochrome-108")
    }

    @Test @Config(sdk = [33, 36]) fun adaptiveWrappersDrawNativeMasksAndMonochromeArtwork() {
        for ((name, resource) in listOf("launcher" to R.mipmap.ic_launcher, "launcher-round" to R.mipmap.ic_launcher_round)) {
            val icon = drawable(resource) as AdaptiveIconDrawable
            val bitmap = render(icon, LEGACY_ICON_SIZE, LEGACY_ICON_SIZE)
            assertEquals("adaptive mask clips corner", 0, Color.alpha(bitmap.getPixel(0, 0)))
            assertEquals("adaptive background fills center", 255, Color.alpha(bitmap.getPixel(LEGACY_ICON_SIZE / 2, LEGACY_ICON_SIZE / 2)))
            assertFalse("actual adaptive mask exists", icon.iconMask.isEmpty)
            save(bitmap, "adaptive-$name-48")
            val themed = AdaptiveIconDrawable(ColorDrawable(NAVY_BACKGROUND), requireNotNull(icon.monochrome))
            save(render(themed, LEGACY_ICON_SIZE, LEGACY_ICON_SIZE), "adaptive-$name-monochrome-48")
        }
    }

    @Test fun legacyDensityResourcesKeepNativeSizesShapesAndNavyBackground() {
        for ((density, size) in listOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192)) {
            for (suffix in listOf("", "_round")) {
                val bitmap = sourceBitmap("mipmap-$density/ic_launcher$suffix.png")
                assertEquals("$density$suffix native width", size, bitmap.width)
                assertEquals("$density$suffix native height", size, bitmap.height)
                assertEquals("$density$suffix transparent corner", 0, Color.alpha(bitmap.getPixel(0, 0)))
                assertEquals("$density$suffix approved navy", NAVY_BACKGROUND, bitmap.getPixel(size / 5, size / 2))
                assertTrue("$density$suffix cyan symbol exists", Color.blue(bitmap.getPixel(size / 2, size / 2)) > Color.blue(NAVY_BACKGROUND))
                save(bitmap, "legacy-$density$suffix-$size")
            }
        }
    }

    @Test @Config(sdk = [24, 25]) @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun preAdaptiveAndroidResolvesNativeDensityLauncherBitmaps() {
        for ((density, size) in listOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192)) {
            RuntimeEnvironment.setQualifiers(density)
            for (resource in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
                val launcher = drawable(resource)
                assertTrue("API${Build.VERSION.SDK_INT} resolves bitmap fallback at $density", launcher is BitmapDrawable)
                val bitmap = (launcher as BitmapDrawable).bitmap
                assertEquals("$density fallback width", size, bitmap.width)
                assertEquals("$density fallback height", size, bitmap.height)
            }
        }
    }

    @Test fun televisionBannerKeepsPlainWordmarkAspectAndTransparentVector() {
        val banner = sourceBitmap("mipmap-xhdpi/banner.png")
        assertEquals(320, banner.width)
        assertEquals(180, banner.height)
        assertEquals(NAVY_BACKGROUND, banner.getPixel(0, 0))
        assertEquals("banner side clear space", NAVY_BACKGROUND, banner.getPixel(23, banner.height / 2))
        val logo = drawable(R.drawable.tv_logo_banner)
        val homeLogo = drawable(R.drawable.vpnown_wordmark)
        assertEquals(homeLogo.intrinsicWidth, logo.intrinsicWidth)
        assertEquals(homeLogo.intrinsicHeight, logo.intrinsicHeight)
        assertEquals("approved wordmark aspect", WORDMARK_ASPECT, logo.intrinsicWidth.toDouble() / logo.intrinsicHeight, 0.02)
        val bitmap = render(logo)
        assertEquals("TV vector has no baked background", 0, Color.alpha(bitmap.getPixel(0, 0)))
        assertTrue("complete wordmark reaches both sides", inkBounds(bitmap).width() > bitmap.width * 0.95)
        assertTrue("outlined letters remain visible", bitmap.getPixel(8, 18) != Color.TRANSPARENT)
        save(bitmap, "tv-wordmark-native")
        save(banner, "tv-banner-320x180")
    }

    @Test fun standaloneAndTileArtworkStayTransparentMonochromeAndUnmirrored() {
        val tile = drawable(R.drawable.ic_tile)
        assertEquals(24, tile.intrinsicWidth)
        assertEquals(24, tile.intrinsicHeight)
        val bitmap = render(tile)
        assertEquals("tile background remains transparent", 0, Color.alpha(bitmap.getPixel(0, 0)))
        assertEquals("tile counter remains transparent", 0, Color.alpha(bitmap.getPixel(10, 8)))
        assertTrue("tile main master remains visible", Color.alpha(bitmap.getPixel(6, 12)) > 0)
        val bounds = inkBounds(bitmap)
        assertTrue("tile retains transparent padding", bounds.width() < bitmap.width && bounds.height() < bitmap.height)
        assertSolidWhite(bitmap)
        save(bitmap, "tile-24")
        for (resource in listOf(R.drawable.vpnown_symbol, R.drawable.vpnown_wordmark, R.drawable.tv_logo_banner, R.drawable.ic_tile, R.drawable.ic_launcher_foreground, R.drawable.ic_launcher_monochrome)) {
            val artwork = drawable(resource)
            assertFalse("brand artwork never automatically mirrors", artwork.isAutoMirrored)
            val ltr = render(artwork)
            artwork.layoutDirection = View.LAYOUT_DIRECTION_RTL
            val rtl = render(artwork)
            assertTrue("RTL preserves brand orientation", ltr.sameAs(rtl))
        }
        val symbol = render(drawable(R.drawable.vpnown_symbol))
        assertEquals("standalone symbol has no background", 0, Color.alpha(symbol.getPixel(0, 0)))
        assertEquals("standalone counter remains transparent", 0, Color.alpha(symbol.getPixel(40, 38)))
        save(symbol, "symbol-native")
        save(render(drawable(R.drawable.vpnown_wordmark)), "home-wordmark-native")
    }

    private fun drawable(resource: Int): Drawable = RuntimeEnvironment.getApplication().getDrawable(resource)!!

    private fun render(drawable: Drawable, width: Int = drawable.intrinsicWidth, height: Int = drawable.intrinsicHeight): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private fun sourceBitmap(relativePath: String): Bitmap = requireNotNull(BitmapFactory.decodeFile(File("src/main/res", relativePath).path))

    private fun inkBounds(bitmap: Bitmap): Rect {
        val bounds = Rect(bitmap.width, bitmap.height, 0, 0)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) != 0) bounds.union(x, y, x + 1, y + 1)
            }
        }
        return bounds
    }

    private fun assertSolidWhite(bitmap: Bitmap) {
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) != 0) assertEquals("solid white mask", Color.WHITE, pixel or OPAQUE_ALPHA)
            }
        }
    }

    private fun save(bitmap: Bitmap, name: String) {
        val directory = File("../.artifacts/android-brand-previews").apply { mkdirs() }
        File(directory, "$name-api${Build.VERSION.SDK_INT}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val ADAPTIVE_LAYER_SIZE = 108
        const val ADAPTIVE_SAFE_RADIUS = 33.0
        const val LEGACY_ICON_SIZE = 48
        const val NAVY_BACKGROUND = -16115929
        const val OPAQUE_ALPHA = -16777216
        const val WORDMARK_ASPECT = 730.0 / 206.0
    }
}
