package com.wireguard.android.activity

import android.app.Application
import android.content.Context
import android.content.ComponentName
import android.content.RestrictionsManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.ImageView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.wireguard.android.QuickTileService
import com.wireguard.android.R
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.WgQuickBackend
import com.wireguard.android.model.TunnelTestEnvironment
import com.wireguard.android.preference.KernelModuleEnablerPreference
import com.wireguard.android.util.RootShell
import com.wireguard.android.util.ToolsInstaller
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.Executor
import java.util.function.Consumer

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [SettingsActivityTest.KernelSupport::class, SettingsActivityTest.InstalledTools::class, SettingsActivityTest.TileRequests::class])
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsActivityTest {
    @get:Rule val folder = TemporaryFolder()
    private val controllers = mutableListOf<ActivityController<SettingsActivity>>()
    private lateinit var testEnvironment: TunnelTestEnvironment

    @Before fun resetPlatform() {
        KernelSupport.available = false
        setTileAdded(false)
    }

    @After fun tearDown() {
        controllers.forEach { it.pause().stop().destroy() }
        Dispatchers.resetMain()
        setTileAdded(false)
    }

    @Test fun userspaceRemovesUnsupportedConnectionAndKeepsNativeActions() = runTest {
        environment()
        val activity = openSettings()
        settle(activity)
        val fragment = fragment(activity)
        assertNull(fragment.findPreference<Preference>("settings_connection"))
        assertNull(fragment.findPreference<Preference>("quick_tile"))
        assertNotNull(fragment.findPreference<SwitchPreferenceCompat>("dark_theme"))
        assertNotNull(fragment.findPreference<Preference>("zip_exporter"))
        assertNotNull(fragment.findPreference<Preference>("version"))
        assertEquals(Int.MAX_VALUE, fragment.preferenceScreen.initialExpandedChildrenCount)
        assertNonemptyCategories(fragment)
    }

    @Test @Config(sdk = [33]) fun modernAndroidKeepsTileAndRemovesEmptyAppearance() = runTest {
        environment()
        val activity = openSettings()
        settle(activity)
        val fragment = fragment(activity)
        assertNotNull(fragment.findPreference<Preference>("quick_tile"))
        assertNull(fragment.findPreference<Preference>("dark_theme"))
        assertNull(fragment.findPreference<Preference>("settings_appearance"))
        assertNonemptyCategories(fragment)
    }

    @Test @Config(sdk = [33]) fun addedTileIsRemovedWithoutRemovingControlSection() = runTest {
        environment()
        setTileAdded(true)
        val activity = openSettings()
        settle(activity)
        val fragment = fragment(activity)
        assertNull(fragment.findPreference<Preference>("quick_tile"))
        assertNotNull(fragment.findPreference<Preference>("allow_remote_control_intents"))
        assertNotNull(fragment.findPreference<Preference>("settings_control"))
    }

    @Test fun adminRestrictionRemovesExportButKeepsLogs() = runTest {
        val env = environment()
        val restrictions = env.application.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
        shadowOf(restrictions).setApplicationRestrictions(Bundle().apply { putBoolean("disable_config_export", true) })
        val activity = openSettings()
        settle(activity)
        assertNull(fragment(activity).findPreference<Preference>("zip_exporter"))
        assertNotNull(fragment(activity).findPreference<Preference>("log_viewer"))
        assertNotNull(fragment(activity).findPreference<Preference>("settings_data"))
    }

    @Test fun pendingBackendDoesNotExposeEmptyConnectionHeader() = runTest {
        val env = environment()
        val pending = CompletableDeferred<Backend>()
        setApplicationField(env, "futureBackend", pending)
        val activity = openSettings()
        runCurrent()
        assertFalse(fragment(activity).findPreference<Preference>("settings_connection")!!.isVisible)
        pending.complete(env.backend)
        settle(activity)
        assertNull(fragment(activity).findPreference<Preference>("settings_connection"))
    }

    @Test fun kernelBackendKeepsSwitchKeysValuesAndActionOwner() = runTest {
        val env = environment()
        KernelSupport.available = true
        val shell = ProbeShell(env.application)
        val tools = ToolsInstaller(env.application, shell)
        setApplicationField(env, "toolsInstaller", tools)
        setApplicationField(env, "futureBackend", CompletableDeferred<Backend>(WgQuickBackend(env.application, shell, tools)))
        val activity = openSettings()
        settle(activity)
        val fragment = fragment(activity)
        assertTrue(fragment.findPreference<Preference>("settings_connection")!!.isVisible)
        assertTrue(fragment.findPreference<Preference>("tools_installer")!!.isVisible)
        assertNotNull(fragment.findPreference<KernelModuleEnablerPreference>("kernel_module_enabler"))
        for (key in listOf("restore_on_boot", "multiple_tunnels")) {
            val preference = fragment.findPreference<SwitchPreferenceCompat>(key)!!
            assertFalse(preference.isChecked)
            preference.isChecked = true
        }
        settle(activity)
        controllers.last().recreate()
        settle(controllers.last().get())
        for (key in listOf("restore_on_boot", "multiple_tunnels")) {
            assertTrue(fragment(controllers.last().get()).findPreference<SwitchPreferenceCompat>(key)!!.isChecked)
        }
        assertEquals(0, shell.calls)
    }

    @Test fun successfulRootProbeKeepsKernelActionAndFailureRemovesEmptyGroup() = runTest {
        val env = environment()
        KernelSupport.available = true
        val shell = ProbeShell(env.application)
        setApplicationField(env, "rootShell", shell)
        val activity = openSettings()
        settle(activity)
        assertNotNull(fragment(activity).findPreference<KernelModuleEnablerPreference>("kernel_module_enabler"))
        assertTrue(fragment(activity).findPreference<Preference>("settings_connection")!!.isVisible)
        shell.failure = java.io.IOException("fixture denied")
        controllers.last().recreate()
        settle(controllers.last().get())
        assertNull(fragment(controllers.last().get()).findPreference<Preference>("settings_connection"))
        assertEquals(2, shell.calls)
    }

    @Test fun remoteControlValueAndLogNavigationSurviveRecreation() = runTest {
        environment()
        val activity = openSettings()
        settle(activity)
        fragment(activity).findPreference<SwitchPreferenceCompat>("allow_remote_control_intents")!!.isChecked = true
        settle(activity)
        controllers.last().recreate()
        val recreated = controllers.last().get()
        settle(recreated)
        assertEquals(1, recreated.supportFragmentManager.fragments.size)
        assertTrue(fragment(recreated).findPreference<SwitchPreferenceCompat>("allow_remote_control_intents")!!.isChecked)
        assertEquals(Int.MAX_VALUE, fragment(recreated).preferenceScreen.initialExpandedChildrenCount)
        fragment(recreated).findPreference<Preference>("log_viewer")!!.performClick()
        assertEquals(LogViewerActivity::class.java.name, shadowOf(recreated).nextStartedActivity.component!!.className)
        recreated.findViewById<View>(R.id.settings_back).performClick()
        assertTrue(recreated.isFinishing)
    }

    @Test @Config(sdk = [33]) fun tileSuccessRemovesOnlyTileAndPreservesExpandedGroups() = runTest {
        environment()
        val activity = openSettings()
        settle(activity)
        fragment(activity).findPreference<Preference>("quick_tile")!!.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(fragment(activity).findPreference<Preference>("quick_tile"))
        assertNotNull(fragment(activity).findPreference<Preference>("allow_remote_control_intents"))
        assertEquals(Int.MAX_VALUE, fragment(activity).preferenceScreen.initialExpandedChildrenCount)
    }

    @Test fun versionRowKeepsNativeArtworkWithoutGenericIconTint() = runTest {
        environment()
        val activity = openSettings()
        settle(activity)
        val row = activity.layoutInflater.inflate(R.layout.tunnl_preference_item, null)
        val icon = row.findViewById<ImageView>(android.R.id.icon)
        assertNotNull(icon.imageTintList)
        fragment(activity).findPreference<Preference>("version")!!.onBindViewHolder(PreferenceViewHolder.createInstanceForTests(row))
        assertNull(icon.imageTintList)
        assertNotNull(icon.drawable)
    }

    @Test @Config(sdk = [33]) fun rootInsetsAreIdempotentAndLeaveImeInsetsForDescendants() = runTest {
        environment()
        val activity = openSettings()
        val root = activity.findViewById<View>(R.id.settings_root)
        val handled = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        val safe = Insets.of(40, 30, 20, 24)
        val input = WindowInsetsCompat.Builder().setInsets(handled, safe)
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 240)).build()
        repeat(2) {
            val remaining = ViewCompat.dispatchApplyWindowInsets(root, input)
            assertEquals(safe.left, root.paddingLeft)
            assertEquals(safe.top, root.paddingTop)
            assertEquals(safe.right, root.paddingRight)
            assertEquals(safe.bottom, root.paddingBottom)
            assertEquals(Insets.NONE, remaining.getInsets(handled))
            assertEquals(240, remaining.getInsets(WindowInsetsCompat.Type.ime()).bottom)
        }
        settle(activity)
        assertFalse(fragment(activity).requireView().fitsSystemWindows)
        val back = activity.findViewById<View>(R.id.settings_back)
        assertEquals(activity.getString(androidx.appcompat.R.string.abc_action_bar_up_description), back.contentDescription.toString())
        assertEquals((48 * activity.resources.displayMetrics.density).toInt(), back.layoutParams.width)
        assertEquals(back.layoutParams.width, back.layoutParams.height)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun renderedSmallPortraitLandscapeAndLargeFontRtlStayUsableInBothThemes() = runTest {
        environment()
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
            val controller = Robolectric.buildActivity(SettingsActivity::class.java)
            controllers += controller
            val activity = controller.get()
            activity.setTheme(R.style.AppTheme)
            controller.setup()
            activity.supportFragmentManager.executePendingTransactions()
            settle(activity)
            val root = activity.findViewById<View>(R.id.settings_root)
            val density = activity.resources.displayMetrics.density
            val width = (case.width * density).toInt()
            val height = (case.height * density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val back = root.findViewById<View>(R.id.settings_back)
            assertTrue(back.width >= 48 * density)
            assertTrue(back.height >= 48 * density)
            assertTrue(root.findViewById<View>(R.id.settings_content).height > 0)
            for (text in textViews(root)) {
                if (text.visibility != View.VISIBLE || text.text.isEmpty()) continue
                assertTrue("${case.name}: ${text.text}", text.layout.getLineTop(text.layout.lineCount) <= text.height)
            }
            assertEquals(if (case.locale == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR, root.layoutDirection)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val directory = File("../.artifacts/settings-previews").apply { mkdirs() }
            File(directory, "${case.name}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun textViews(view: View): List<TextView> {
        if (view is TextView) return listOf(view)
        if (view !is android.view.ViewGroup) return emptyList()
        return (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
    }

    private data class LayoutCase(val name: String, val width: Int, val height: Int, val fontScale: Float, val locale: String, val night: Boolean)

    private fun TestScope.environment(): TunnelTestEnvironment {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        testEnvironment = TunnelTestEnvironment(folder.root, backgroundScope)
        val shell = ProbeShell(testEnvironment.application)
        setApplicationField(testEnvironment, "rootShell", shell)
        setApplicationField(testEnvironment, "toolsInstaller", ToolsInstaller(testEnvironment.application, shell))
        val restrictions = testEnvironment.application.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
        shadowOf(restrictions).setApplicationRestrictions(Bundle())
        return testEnvironment
    }

    private fun openSettings(): SettingsActivity {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        controllers += controller
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
        controller.get().supportFragmentManager.executePendingTransactions()
        return controller.get()
    }

    private suspend fun TestScope.settle(activity: SettingsActivity) {
        activity.supportFragmentManager.executePendingTransactions()
        runCurrent()
        fragment(activity).lifecycleScope.coroutineContext[Job]!!.children.toList().joinAll()
        activity.lifecycleScope.coroutineContext[Job]!!.children.toList().joinAll()
        runCurrent()
    }

    private fun fragment(activity: SettingsActivity) =
        activity.supportFragmentManager.findFragmentById(R.id.settings_content) as SettingsActivity.SettingsFragment

    private fun assertNonemptyCategories(fragment: SettingsActivity.SettingsFragment) {
        for (index in 0 until fragment.preferenceScreen.preferenceCount) {
            val category = fragment.preferenceScreen.getPreference(index) as PreferenceCategory
            assertTrue(category.preferenceCount > 0)
        }
    }

    private fun setApplicationField(env: TunnelTestEnvironment, name: String, value: Any) {
        com.wireguard.android.Application::class.java.getDeclaredField(name).apply { isAccessible = true }.set(env.application, value)
    }

    private fun setTileAdded(added: Boolean) {
        QuickTileService::class.java.getDeclaredField("isAdded").apply { isAccessible = true }.setBoolean(null, added)
    }

    private class ProbeShell(context: Context) : RootShell(context) {
        var calls = 0
        var failure: java.io.IOException? = null
        override fun start() {
            calls++
            failure?.let { throw it }
        }
    }

    @Implements(WgQuickBackend::class)
    class KernelSupport {
        // Robolectric must match the native backend method rather than expose a fixture constant.
        @Suppress("FunctionOnlyReturningConstant")
        @Implementation fun getVersion() = "fixture"
        companion object {
            var available = false
            @JvmStatic @Implementation fun hasKernelSupport() = available
        }
    }

    @Implements(ToolsInstaller::class)
    class InstalledTools {
        @Implementation fun areInstalled() = ToolsInstaller.NO
    }

    @Implements(className = "android.app.StatusBarManager", minSdk = 33)
    class TileRequests {
        @Implementation
        fun requestAddTileService(component: ComponentName, label: CharSequence, icon: Icon, executor: Executor, callback: Consumer<Int>) {
            assertEquals(QuickTileService::class.java.name, component.className)
            assertFalse(label.isBlank())
            assertEquals(R.drawable.ic_tile, icon.resId)
            executor.execute { callback.accept(android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) }
        }
    }
}
