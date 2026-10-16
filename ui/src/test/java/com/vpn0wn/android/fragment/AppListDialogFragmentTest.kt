/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.fragment

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.descendants
import com.vpn0wn.android.R
import com.vpn0wn.android.Application as Vpn0wnApplication
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowApplicationPackageManager
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [AppListDialogFragmentTest.InventoryShadow::class])
class AppListDialogFragmentTest {
    private lateinit var application: Vpn0wnApplication
    private var originalApplication: WeakReference<Vpn0wnApplication>? = null
    private lateinit var originalQualifiers: String
    private lateinit var originalLocale: Locale
    private var originalFontScale = 1f
    private var originalFailure: RuntimeException? = null
    private var originalObservedFailure: RuntimeException? = null
    private var originalEmptyInventory = false

    @Before fun initializeResourceOwner() {
        originalApplication = ReflectionHelpers.getStaticField(Vpn0wnApplication::class.java, "weakSelf")
        originalQualifiers = RuntimeEnvironment.getQualifiers()
        originalLocale = Locale.getDefault()
        originalFontScale = RuntimeEnvironment.getApplication().resources.configuration.fontScale
        originalFailure = InventoryShadow.failure
        originalObservedFailure = InventoryShadow.observedFailure
        originalEmptyInventory = InventoryShadow.emptyInventory
        application = Vpn0wnApplication()
        Vpn0wnApplication::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(application, RuntimeEnvironment.getApplication())
    }

    @After fun restoreEnvironment() {
        try {
            RuntimeEnvironment.setQualifiers(originalQualifiers)
            RuntimeEnvironment.setFontScale(originalFontScale)
            Locale.setDefault(originalLocale)
        } finally {
            ReflectionHelpers.setStaticField(Vpn0wnApplication::class.java, "weakSelf", originalApplication)
            InventoryShadow.failure = originalFailure
            InventoryShadow.observedFailure = originalObservedFailure
            InventoryShadow.emptyInventory = originalEmptyInventory
            Dispatchers.resetMain()
        }
    }

    @Test fun pendingApplyAndToggleAllCannotReplaceSelectionOrDismissDialog() = runTest {
        withDialog { fixture ->
            assertFalse(fixture.apply.isEnabled)
            assertFalse(fixture.toggle.isEnabled)
            assertTrue(fixture.cancel.isEnabled)
            assertEquals(View.VISIBLE, fixture.progress.visibility)
            fixture.apply.performClick()
            fixture.toggle.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(fixture.receipt)
            assertTrue(fixture.dialog.isShowing)
            fixture.finishLoading()
            fixture.apply.performClick()
            assertEquals(listOf(APP), fixture.selection())
        }
    }

    @Test fun loadedApplyPreservesTheSelectedPackageAndMode() = runTest {
        withDialog { fixture ->
            fixture.finishLoading()
            assertTrue(fixture.apply.isEnabled)
            assertTrue(fixture.toggle.isEnabled)
            assertEquals(View.GONE, fixture.progress.visibility)
            fixture.apply.performClick()
            assertEquals(listOf(APP), fixture.selection())
            assertTrue(fixture.receipt!!.getBoolean(AppListDialogFragment.KEY_IS_EXCLUDED))
        }
    }

    @Test fun loadedToggleAllCanDeliberatelyClearTheSelection() = runTest {
        withDialog { fixture ->
            fixture.finishLoading()
            fixture.toggle.performClick()
            fixture.apply.performClick()
            assertEquals(emptyList<String>(), fixture.selection())
        }
    }

    @Test fun successfullyLoadedEmptyInventoryStopsLoadingAndAllowsDeliberateEmptySelection() = runTest {
        InventoryShadow.emptyInventory = true
        withDialog { fixture ->
            fixture.finishLoading()
            assertEquals(View.GONE, fixture.progress.visibility)
            assertTrue(fixture.apply.isEnabled)
            fixture.apply.performClick()
            assertEquals(emptyList<String>(), fixture.selection())
        }
    }

    @Test fun cancelWhilePendingEmitsNoReplacementAndCancelsTheOwnedLoad() = runTest {
        withDialog { fixture ->
            val loads = fixture.loads()
            fixture.cancel.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            fixture.activity.supportFragmentManager.executePendingTransactions()
            runCurrent()
            loads.joinAll()
            assertTrue(loads.all { it.isCancelled })
            assertNull(fixture.receipt)
        }
    }

    @Test fun inventoryFailuresCannotEmitAnEmptyReplacement() = runTest {
        for (failure in listOf(SecurityException("synthetic denied inventory"), Resources.NotFoundException("synthetic missing icon"))) {
            InventoryShadow.failure = failure
            withDialog { fixture ->
                val dialog = fixture.dialog
                assertFalse(fixture.apply.isEnabled)
                fixture.finishLoading()
                assertSame("inventory received ${failure.javaClass.simpleName}", failure, InventoryShadow.observedFailure)
                shadowOf(Looper.getMainLooper()).idle()
                fixture.activity.supportFragmentManager.executePendingTransactions()
                assertNull(fixture.receipt)
                assertFalse(dialog.isShowing)
            }
        }
    }

    @Test fun recreationCancelsOldPublicationAndRequiresFreshInventory() = runTest {
        withDialog { fixture ->
            val oldLoads = fixture.loads()
            val oldApply = fixture.apply
            val manager = fixture.activity.supportFragmentManager
            manager.beginTransaction().detach(fixture.fragment).commitNow()
            assertTrue(oldLoads.all { it.isCancelled })
            oldApply.performClick()
            assertNull(fixture.receipt)
            manager.beginTransaction().attach(fixture.fragment).commitNow()
            shadowOf(Looper.getMainLooper()).idle()
            val replacement = fixture.fragment.requireDialog() as AlertDialog
            assertFalse(replacement.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            runCurrent()
            oldLoads.joinAll()
            fixture.finishLoading()
            replacement.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals(listOf(APP), fixture.selection())
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun nativePendingAndLoadedFeedbackPreserveControlsAcrossSizesThemesAndRtl() = runTest {
        val cases = listOf(
            Preview("portrait", 360, 720, 1f, "en"),
            Preview("compact", 320, 480, 1f, "en"),
            Preview("landscape", 640, 320, 1f, "en"),
            Preview("large-rtl", 360, 720, 1.5f, "ar")
        )
        for (preview in cases) for (night in listOf(false, true)) {
            val direction = if (preview.locale == "ar") "ldrtl" else "ldltr"
            val orientation = if (preview.width > preview.height) "land" else "port"
            RuntimeEnvironment.setQualifiers("${preview.locale}-$direction-w${preview.width}dp-h${preview.height}dp-$orientation-${if (night) "night" else "notnight"}")
            RuntimeEnvironment.setFontScale(preview.fontScale)
            Locale.setDefault(Locale.forLanguageTag(preview.locale))
            withDialog { fixture ->
                val suffix = "${preview.name}-${if (night) "dark" else "light"}"
                assertFalse(fixture.apply.isEnabled)
                assertTrue(fixture.cancel.isEnabled)
                fixture.render(preview, "$suffix-pending", night)
                fixture.finishLoading()
                assertTrue(fixture.apply.isEnabled)
                assertEquals(View.GONE, fixture.progress.visibility)
                fixture.render(preview, "$suffix-loaded", night)
            }
        }
    }

    private suspend fun TestScope.withDialog(block: suspend TestScope.(Fixture) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildActivity(HostActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.AppTheme) }
        var fixture: Fixture? = null
        try {
            controller.setup()
            shadowOf(activity.packageManager).installPackage(PackageInfo().apply {
                packageName = APP
                requestedPermissions = arrayOf(Manifest.permission.INTERNET)
                requestedPermissionsFlags = intArrayOf(PackageInfo.REQUESTED_PERMISSION_GRANTED)
                applicationInfo = ApplicationInfo(activity.applicationInfo).apply {
                    packageName = APP
                    nonLocalizedLabel = "Synthetic selected application"
                }
            })
            val created = Fixture(activity, AppListDialogFragment.newInstance(arrayListOf(APP), true))
            fixture = created
            activity.supportFragmentManager.setFragmentResultListener(AppListDialogFragment.REQUEST_SELECTION, activity) { _, value -> created.receipt = value }
            created.fragment.showNow(activity.supportFragmentManager, "apps")
            shadowOf(Looper.getMainLooper()).idle()
            block(created)
        } finally {
            val loads = fixture?.loads().orEmpty()
            controller.pause().stop().destroy()
            runCurrent()
            loads.joinAll()
            InventoryShadow.failure = null
            InventoryShadow.observedFailure = null
            InventoryShadow.emptyInventory = false
            Dispatchers.resetMain()
        }
    }

    private class Fixture(val activity: HostActivity, val fragment: AppListDialogFragment) {
        var receipt: Bundle? = null
        val dialog get() = fragment.requireDialog() as AlertDialog
        val apply get() = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val toggle get() = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
        val cancel get() = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        val progress get() = dialog.findViewById<ProgressBar>(R.id.progress_bar)!!

        fun loads() = listOfNotNull(ReflectionHelpers.getField<Job?>(fragment, "loadJob"))
        suspend fun finishLoading() = loads().joinAll()
        fun selection(): List<String> {
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(receipt)
            return receipt!!.getStringArray(AppListDialogFragment.KEY_SELECTED_APPS)!!.toList()
        }

        fun render(preview: Preview, name: String, night: Boolean) {
            val root = dialog.window!!.decorView as ViewGroup
            val configuration = activity.resources.configuration
            assertEquals(preview.locale, configuration.locales[0].language)
            assertEquals(preview.fontScale, configuration.fontScale, 0f)
            assertEquals(if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO, configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            root.layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            val density = activity.resources.displayMetrics.density
            val width = (preview.width * density).toInt()
            val heightLimit = (preview.height * density).toInt()
            repeat(3) {
                root.forceLayout()
                root.descendants.forEach { it.forceLayout() }
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(heightLimit, View.MeasureSpec.AT_MOST))
                root.layout(0, 0, width, root.measuredHeight)
                assertFalse("$name: draw was cancelled", root.viewTreeObserver.dispatchOnPreDraw())
            }
            assertEquals(if (preview.locale == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR, root.layoutDirection)
            assertEquals(width, root.width)
            assertTrue(root.height <= heightLimit)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val output = File("../.artifacts/app-selection-previews").apply { mkdirs() }
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val spinner = progress
            val running = (spinner.indeterminateDrawable as? Animatable)?.isRunning
            File(output, "$name.txt").writeText("root=${root.width}x${root.height}; progress=${spinner.width}x${spinner.height}; shown=${spinner.isShown}; drawable=${spinner.indeterminateDrawable.javaClass.name}; running=$running; bounds=${spinner.indeterminateDrawable.bounds}")
            bitmap.recycle()
            val actionScroll = dialog.findViewById<ScrollView>(androidx.appcompat.R.id.buttonPanel)!!
            val originalScrollX = actionScroll.scrollX
            val originalScrollY = actionScroll.scrollY
            try {
                for ((action, button) in listOf("apply" to apply, "toggle" to toggle, "cancel" to cancel)) {
                    assertEquals(View.VISIBLE, button.visibility)
                    assertTrue("$name: ${button.text} target height", button.height >= 48 * density)
                    assertTrue("$name: ${button.text} target width", button.width >= 48 * density)
                    assertTrue("$name: ${button.text} has layout", button.layout.lineCount > 0)
                    val initialVisible = Rect()
                    val needsReveal = !button.getGlobalVisibleRect(initialVisible) || initialVisible.width() != button.width || initialVisible.height() != button.height
                    val bounds = Rect()
                    button.getDrawingRect(bounds)
                    button.requestRectangleOnScreen(bounds, true)
                    button.getDrawingRect(bounds)
                    root.offsetDescendantRectToMyCoords(button, bounds)
                    assertTrue("$name: ${button.text} bounds=$bounds root=${root.width}x${root.height}", bounds.left >= 0 && bounds.top >= 0 && bounds.right <= root.width && bounds.bottom <= root.height)
                    val visible = Rect()
                    assertTrue("$name: ${button.text} visible target", button.getGlobalVisibleRect(visible))
                    assertEquals("$name: ${button.text} visible width", button.width, visible.width())
                    assertEquals("$name: ${button.text} visible height", button.height, visible.height())
                    if (needsReveal) {
                        val revealed = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                        root.draw(Canvas(revealed))
                        File(output, "$name-action-$action.png").outputStream().use { revealed.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        revealed.recycle()
                    }
                }
            } finally {
                actionScroll.scrollTo(originalScrollX, originalScrollY)
            }
        }
    }

    @Implements(className = "android.app.ApplicationPackageManager")
    class InventoryShadow : ShadowApplicationPackageManager() {
        @Implementation
        override fun getPackagesHoldingPermissions(permissions: Array<String>, flags: Int): List<PackageInfo> {
            failure?.let {
                observedFailure = it
                throw it
            }
            return if (emptyInventory) emptyList() else super.getPackagesHoldingPermissions(permissions, flags)
        }

        companion object {
            @Volatile var failure: RuntimeException? = null
            @Volatile var observedFailure: RuntimeException? = null
            @Volatile var emptyInventory = false
        }
    }

    class HostActivity : AppCompatActivity()
    private data class Preview(val name: String, val width: Int, val height: Int, val fontScale: Float, val locale: String)
    companion object { private const val APP = "example.appselection.selected" }
}
