package com.wireguard.android.activity

import android.app.Application
import android.app.assist.AssistStructure
import android.content.Context
import android.content.ComponentName
import android.content.RestrictionsManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewStructure
import android.widget.TextView
import android.widget.ImageView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import com.wireguard.android.QuickTileService
import com.wireguard.android.R
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.WgQuickBackend
import com.wireguard.android.model.TunnelTestEnvironment
import com.wireguard.android.preference.KernelModuleEnablerPreference
import com.wireguard.android.util.RootShell
import com.wireguard.android.util.ToolsInstaller
import com.wireguard.android.util.UserKnobs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
import java.io.IOException
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
        KernelSupport.probedOnMain = null
        TileRequests.result = android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
        TileRequests.deferResult = false
        TileRequests.pendingResult = null
    }

    @After fun tearDown() {
        controllers.forEach { it.pause().stop().destroy() }
        Dispatchers.resetMain()
    }

    @Test fun userspaceKeepsSystemVpnConnectionActionAndNativeActions() = runTest {
        environment()
        val activity = openSettings()
        settle(activity)
        val fragment = fragment(activity)
        assertTrue(fragment.findPreference<Preference>("settings_connection")!!.isVisible)
        assertNotNull(fragment.findPreference<Preference>("system_vpn_settings"))
        assertNull(fragment.findPreference<Preference>("kernel_module_enabler"))
        assertNull(fragment.findPreference<Preference>("quick_tile"))
        assertNotNull(fragment.findPreference<SwitchPreferenceCompat>("dark_theme"))
        assertNotNull(fragment.findPreference<Preference>("zip_exporter"))
        assertNotNull(fragment.findPreference<Preference>("version"))
        assertEquals(Int.MAX_VALUE, fragment.preferenceScreen.initialExpandedChildrenCount)
        assertNonemptyCategories(fragment)
    }

    @Test fun kernelSupportFilesystemProbeRunsOffMain() = runTest {
        environment()
        val activity = openSettings()
        settle(activity)
        assertEquals(false, KernelSupport.probedOnMain)
    }

    @Test @Config(sdk = [36]) fun preferenceStructuresHaveNoInvalidResourceIdsAfterRecreation() = runTest {
        val env = environment()
        KernelSupport.available = true
        val shell = ProbeShell(env.application)
        val tools = ToolsInstaller(env.application, shell)
        setApplicationField(env, "toolsInstaller", tools)
        setApplicationField(env, "futureBackend", CompletableDeferred<Backend>(WgQuickBackend(env.application, shell, tools)))
        openSettings()
        repeat(2) { pass ->
            if (pass > 0) controllers.last().recreate()
            val activity = controllers.last().get()
            settle(activity)
            val list = fragment(activity).listView
            val adapter = list.adapter as PreferenceGroupAdapter
            assertTrue(adapter.itemCount > 0)
            assertTrue(adapter.hasStableIds())
            for (position in 0 until adapter.itemCount) {
                val holder = adapter.createViewHolder(list, adapter.getItemViewType(position))
                adapter.bindViewHolder(holder, position)
                val width = activity.resources.displayMetrics.widthPixels
                holder.itemView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                holder.itemView.layout(0, 0, width, holder.itemView.measuredHeight)
                val structure = Class.forName("android.app.assist.AssistStructure\$ViewNodeBuilder")
                    .getDeclaredConstructor().newInstance() as ViewStructure
                holder.itemView.dispatchProvideStructure(structure)
                val node = structure.javaClass.getMethod("getViewNode").invoke(structure) as AssistStructure.ViewNode
                assertTrue("Preference structure must retain its children", node.childCount > 0)
                assertValidStructureIds(node)
                assertNotEquals(RecyclerView.NO_ID, holder.itemId)
                assertEquals(adapter.getItemId(position), holder.itemId)
            }
        }
    }

    @Test @Config(sdk = [33]) fun modernAndroidKeepsTileAndRemovesEmptyAppearance() = runTest {
        environment()
        val activity = openSettings()
        settle(activity)
        val fragment = fragment(activity)
        assertNotNull(fragment.findPreference<Preference>("quick_tile"))
        assertTrue(fragment.findPreference<Preference>("quick_tile")!!.isVisible)
        assertNull(UserKnobs.quickTileAdded.first())
        assertNull(fragment.findPreference<Preference>("dark_theme"))
        assertNull(fragment.findPreference<Preference>("settings_appearance"))
        assertNonemptyCategories(fragment)
    }

    @Test @Config(sdk = [33]) fun addedTileIsRemovedWithoutRemovingControlSection() = runTest {
        environment()
        UserKnobs.setQuickTileAdded(true)
        val activity = openSettings()
        settle(activity)
        val fragment = fragment(activity)
        assertFalse(fragment.findPreference<Preference>("quick_tile")!!.isVisible)
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

    @Test fun pendingBackendDefersConnectionHeaderUntilSystemVpnActionIsReady() = runTest {
        val env = environment()
        val pending = CompletableDeferred<Backend>()
        setApplicationField(env, "futureBackend", pending)
        val activity = openSettings()
        runCurrent()
        assertFalse(fragment(activity).findPreference<Preference>("settings_connection")!!.isVisible)
        pending.complete(env.backend)
        settle(activity)
        assertTrue(fragment(activity).findPreference<Preference>("settings_connection")!!.isVisible)
        assertTrue(fragment(activity).findPreference<Preference>("system_vpn_settings")!!.isEnabled)
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

    @Test fun rootProbeFailureRemovesKernelActionAndKeepsSystemVpnGroup() = runTest {
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
        assertTrue(fragment(controllers.last().get()).findPreference<Preference>("settings_connection")!!.isVisible)
        assertNull(fragment(controllers.last().get()).findPreference<Preference>("kernel_module_enabler"))
        assertNotNull(fragment(controllers.last().get()).findPreference<Preference>("system_vpn_settings"))
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
        assertEquals(true, UserKnobs.quickTileAdded.first { it == true })
        settle(activity)
        assertFalse(fragment(activity).findPreference<Preference>("quick_tile")!!.isVisible)
        assertNotNull(fragment(activity).findPreference<Preference>("allow_remote_control_intents"))
        assertEquals(Int.MAX_VALUE, fragment(activity).preferenceScreen.initialExpandedChildrenCount)
        controllers.last().recreate()
        val recreated = controllers.last().get()
        settle(recreated)
        assertFalse(fragment(recreated).findPreference<Preference>("quick_tile")!!.isVisible)
    }

    @Test @Config(sdk = [33]) fun alreadyAddedResultRetainsMembershipAcrossReopening() = runTest {
        environment()
        TileRequests.result = android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
        val activity = openSettings()
        settle(activity)
        fragment(activity).findPreference<Preference>("quick_tile")!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(true, UserKnobs.quickTileAdded.first { it == true })
        settle(activity)
        assertFalse(fragment(activity).findPreference<Preference>("quick_tile")!!.isVisible)
        val reopened = openSettings()
        settle(reopened)
        assertFalse(fragment(reopened).findPreference<Preference>("quick_tile")!!.isVisible)
    }

    @Test @Config(sdk = [33]) fun cancelledAddKeepsUnknownMembershipAndAvailableRow() = runTest {
        environment()
        TileRequests.result = android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED
        val activity = openSettings()
        settle(activity)
        fragment(activity).findPreference<Preference>("quick_tile")!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        settle(activity)
        assertNull(UserKnobs.quickTileAdded.first())
        assertTrue(fragment(activity).findPreference<Preference>("quick_tile")!!.isVisible)
        assertNull(activity.findViewById<TextView>(com.google.android.material.R.id.snackbar_text))
    }

    @Test @Config(sdk = [33]) fun requestErrorsKeepUnknownMembershipAndExistingFeedback() = runTest {
        environment()
        val results = listOf(
            android.app.StatusBarManager.TILE_ADD_REQUEST_ERROR_MISMATCHED_PACKAGE,
            android.app.StatusBarManager.TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS,
            android.app.StatusBarManager.TILE_ADD_REQUEST_ERROR_BAD_COMPONENT,
            android.app.StatusBarManager.TILE_ADD_REQUEST_ERROR_NOT_CURRENT_USER,
            android.app.StatusBarManager.TILE_ADD_REQUEST_ERROR_APP_NOT_IN_FOREGROUND,
            android.app.StatusBarManager.TILE_ADD_REQUEST_ERROR_NO_STATUS_BAR_SERVICE
        )
        for (result in results) {
            TileRequests.result = result
            val activity = openSettings()
            settle(activity)
            fragment(activity).findPreference<Preference>("quick_tile")!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            settle(activity)
            assertNull(UserKnobs.quickTileAdded.first())
            assertTrue(fragment(activity).findPreference<Preference>("quick_tile")!!.isVisible)
            assertEquals(activity.getString(R.string.quick_settings_tile_add_failure, result),
                activity.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).text.toString())
            controllers.last().pause().stop().destroy()
            controllers.removeAt(controllers.lastIndex)
        }
    }

    @Test @Config(sdk = [33]) fun membershipChangesUpdateOnlyTheTileWhileSettingsStaysOpen() = runTest {
        environment()
        val activity = openSettings()
        settle(activity)
        val tile = fragment(activity).findPreference<Preference>("quick_tile")!!
        assertTrue(tile.isVisible)
        UserKnobs.setQuickTileAdded(true)
        settle(activity)
        assertFalse(tile.isVisible)
        UserKnobs.setQuickTileAdded(false)
        settle(activity)
        assertTrue(tile.isVisible)
        assertNotNull(fragment(activity).findPreference<Preference>("allow_remote_control_intents"))
        assertEquals(Int.MAX_VALUE, fragment(activity).preferenceScreen.initialExpandedChildrenCount)
    }

    @Test @Config(sdk = [33]) fun loadingMembershipDoesNotExposeSetupUntilItsFirstValue() = runTest {
        val env = environment()
        UserKnobs.setQuickTileAdded(true)
        val activity = openSettings()
        val dataStore = com.wireguard.android.Application.getPreferencesDataStore()
        val loaded = CompletableDeferred<Unit>()
        val delayedStore = object : DataStore<Preferences> by dataStore {
            override val data = flow {
                loaded.await()
                emitAll(dataStore.data)
            }
        }
        setApplicationField(env, "preferencesDataStore", delayedStore)
        runCurrent()
        assertFalse(fragment(activity).findPreference<Preference>("quick_tile")!!.isVisible)
        loaded.complete(Unit)
        assertEquals(true, UserKnobs.quickTileAdded.first())
        settle(activity)
        assertFalse(fragment(activity).findPreference<Preference>("quick_tile")!!.isVisible)
    }

    @Test @Config(sdk = [33]) fun unavailableMembershipKeepsSetupHiddenWithoutWritingAbsence() = runTest {
        val env = environment()
        val activity = openSettings()
        val dataStore = com.wireguard.android.Application.getPreferencesDataStore()
        val unavailableStore = object : DataStore<Preferences> by dataStore {
            override val data = flow<Preferences> { throw IOException("fixture unavailable") }
        }
        setApplicationField(env, "preferencesDataStore", unavailableStore)
        settle(activity)
        assertFalse(fragment(activity).findPreference<Preference>("quick_tile")!!.isVisible)
        setApplicationField(env, "preferencesDataStore", dataStore)
        assertNull(UserKnobs.quickTileAdded.first())
    }

    @Test @Config(sdk = [33]) fun membershipReadFailureAfterLoadedRemovalHidesTheAvailableRow() = runTest {
        val env = environment()
        UserKnobs.setQuickTileAdded(false)
        val activity = openSettings()
        val dataStore = com.wireguard.android.Application.getPreferencesDataStore()
        val failRead = CompletableDeferred<Unit>()
        val failingStore = object : DataStore<Preferences> by dataStore {
            override val data = flow {
                emit(dataStore.data.first())
                failRead.await()
                throw IOException("fixture unavailable")
            }
        }
        setApplicationField(env, "preferencesDataStore", failingStore)
        settle(activity)
        val tile = fragment(activity).findPreference<Preference>("quick_tile")!!
        assertTrue(tile.isVisible)
        failRead.complete(Unit)
        runCurrent()
        assertFalse(tile.isVisible)
        setApplicationField(env, "preferencesDataStore", dataStore)
        assertEquals(false, UserKnobs.quickTileAdded.first())
    }

    @Test @Config(sdk = [33]) fun lateSuccessfulResultPersistsAfterSettingsDestruction() = runTest {
        environment()
        TileRequests.deferResult = true
        val activity = openSettings()
        settle(activity)
        fragment(activity).findPreference<Preference>("quick_tile")!!.performClick()
        controllers.last().pause().stop().destroy()
        controllers.removeAt(controllers.lastIndex)
        TileRequests.pendingResult!!.invoke()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(true, UserKnobs.quickTileAdded.first { it == true })
        val reopened = openSettings()
        settle(reopened)
        assertFalse(fragment(reopened).findPreference<Preference>("quick_tile")!!.isVisible)
    }

    @Test @Config(sdk = [33]) fun removalWinsOverDeferredSuccessfulResultsAfterSettingsSurvivesOrCloses() = runTest {
        environment()
        val successfulResults = listOf(
            android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
            android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
        )
        for (result in successfulResults) {
            for (closeSettings in listOf(false, true)) {
                TileRequests.result = result
                TileRequests.deferResult = true
                val activity = openSettingsWithLoadedMembership()
                val tile = fragment(activity).findPreference<Preference>("quick_tile")!!
                assertTrue("Tile setup before result $result, closed Settings $closeSettings", tile.isVisible)
                val removalRevision = UserKnobs.quickTileRemovalRevision
                tile.performClick()
                QuickTileService().onTileRemoved()
                assertEquals(removalRevision + 1, UserKnobs.quickTileRemovalRevision)
                assertEquals(false, UserKnobs.quickTileAdded.first { it == false })
                if (closeSettings) {
                    controllers.last().pause().stop().destroy()
                    controllers.removeAt(controllers.lastIndex)
                }
                TileRequests.pendingResult!!.invoke()
                shadowOf(Looper.getMainLooper()).idle()
                runCurrent()
                com.wireguard.android.Application.getPreferencesDataStore().updateData { it }
                assertEquals(false, UserKnobs.quickTileAdded.first())
                val current = if (closeSettings) openSettingsWithLoadedMembership() else activity
                settle(current)
                assertTrue(fragment(current).findPreference<Preference>("quick_tile")!!.isVisible)
                controllers.last().pause().stop().destroy()
                controllers.removeAt(controllers.lastIndex)
            }
        }
    }

    @Test @Config(sdk = [33]) fun successfulResultPersistenceFailureLeavesUnknownFallback() = runTest {
        val env = environment()
        val activity = openSettings()
        settle(activity)
        val dataStore = com.wireguard.android.Application.getPreferencesDataStore()
        var attempts = 0
        val unavailableStore = object : DataStore<Preferences> by dataStore {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                attempts++
                throw IOException("fixture unavailable")
            }
        }
        setApplicationField(env, "preferencesDataStore", unavailableStore)
        val tile = fragment(activity).findPreference<Preference>("quick_tile")!!
        tile.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertEquals(1, attempts)
        assertNull(UserKnobs.quickTileAdded.first())
        assertTrue(tile.isVisible)
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

    @Test @Config(sdk = [28, 33]) fun offlineCreditsAndVersionNavigationSurviveRecreationWithoutPersistingAction() = runTest {
        environment()
        val activity = openSettings()
        settle(activity)
        val licenses = fragment(activity).findPreference<Preference>("licenses")!!
        val preferencesBefore = com.wireguard.android.Application.getPreferencesDataStore().data.first().asMap()
        assertEquals(activity.getString(R.string.licenses_title), licenses.title)
        assertFalse(licenses.isPersistent)
        licenses.performClick()
        val first = shadowOf(activity).nextStartedActivity
        assertEquals(LicensesActivity::class.java.name, first.component!!.className)
        assertNull(first.action)
        assertNull(first.data)
        controllers.last().recreate()
        val recreated = controllers.last().get()
        settle(recreated)
        fragment(recreated).findPreference<Preference>("version")!!.performClick()
        val version = shadowOf(recreated).nextStartedActivity
        assertEquals(LicensesActivity::class.java.name, version.component!!.className)
        assertNull(version.action)
        assertNull(version.data)
        assertEquals(preferencesBefore, com.wireguard.android.Application.getPreferencesDataStore().data.first().asMap())
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

    private fun assertValidStructureIds(node: AssistStructure.ViewNode) {
        assertNotEquals("Invalid resource ID in ${node.className}", 0, node.id)
        for (index in 0 until node.childCount) assertValidStructureIds(node.getChildAt(index))
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

    private suspend fun TestScope.openSettingsWithLoadedMembership(): SettingsActivity {
        val dataStore = com.wireguard.android.Application.getPreferencesDataStore()
        val activity = openSettings()
        val membershipLoaded = CompletableDeferred<Unit>()
        val observingStore = object : DataStore<Preferences> by dataStore {
            override val data = flow {
                dataStore.data.collect { preferences ->
                    emit(preferences)
                    membershipLoaded.complete(Unit)
                }
            }
        }
        setApplicationField(testEnvironment, "preferencesDataStore", observingStore)
        settle(activity)
        membershipLoaded.await()
        runCurrent()
        setApplicationField(testEnvironment, "preferencesDataStore", dataStore)
        return activity
    }

    private suspend fun TestScope.settle(activity: SettingsActivity) {
        activity.supportFragmentManager.executePendingTransactions()
        runCurrent()
        fragment(activity).lifecycleScope.coroutineContext[Job]!!.children.toList().joinAll()
        val policyJob = SettingsActivity.SettingsFragment::class.java.getDeclaredField("vpnPolicyJob")
            .apply { isAccessible = true }.get(fragment(activity)) as Job?
        policyJob?.join()
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
            var probedOnMain: Boolean? = null
            @JvmStatic @Implementation fun hasKernelSupport(): Boolean {
                probedOnMain = Looper.myLooper() == Looper.getMainLooper()
                return available
            }
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
            val requestResult = result
            val deliver = { executor.execute { callback.accept(requestResult) } }
            if (deferResult) pendingResult = deliver else deliver()
        }

        companion object {
            var result = android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
            var deferResult = false
            var pendingResult: (() -> Unit)? = null
        }
    }
}
