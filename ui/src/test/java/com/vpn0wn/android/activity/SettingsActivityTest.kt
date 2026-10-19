package com.vpn0wn.android.activity

import android.app.Application
import android.app.assist.AssistStructure
import android.content.Context
import android.content.ComponentName
import android.content.RestrictionsManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewStructure
import android.widget.TextView
import android.widget.ImageView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import com.vpn0wn.android.QuickTileService
import com.vpn0wn.android.R
import com.vpn0wn.android.backend.Backend
import com.vpn0wn.android.backend.GoBackend
import com.vpn0wn.android.backend.GoBackendLifecycleTest
import com.vpn0wn.android.backend.WgQuickBackend
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.android.model.NetworkAutomationController
import com.vpn0wn.android.model.NetworkAutomationState
import com.vpn0wn.android.preference.KernelModuleEnablerPreference
import com.vpn0wn.android.util.RootShell
import com.vpn0wn.android.util.ToolsInstaller
import com.vpn0wn.android.util.UserKnobs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestWatcher
import org.junit.runner.Description
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [SettingsActivityTest.KernelSupport::class, SettingsActivityTest.InstalledTools::class, SettingsActivityTest.TileRequests::class])
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsActivityTest {
    @get:Rule val folder = TemporaryFolder()
    private var quickTileMembershipPhase: String? = null
    private var quickTileMembershipDetails: (() -> String)? = null
    @get:Rule val quickTileMembershipFailure = object : TestWatcher() {
        override fun failed(failure: Throwable, description: Description) {
            val phase = quickTileMembershipPhase ?: return
            failure.addSuppressed(AssertionError(
                "Quick tile membership phase: $phase\n${quickTileMembershipDetails?.invoke().orEmpty()}"
            ))
        }
    }
    private val controllers = mutableListOf<ActivityController<SettingsActivity>>()
    private val lifecycleJobs = mutableSetOf<Job>()
    private lateinit var testEnvironment: TunnelTestEnvironment

    @Before fun resetPlatform() {
        KernelSupport.available = false
        KernelSupport.probedOnMain = null
        TileRequests.result = android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
        TileRequests.deferResult = false
        TileRequests.pendingResult = null
        TileRequests.requestCount = 0
        TileRequests.requestedResult = null
        TileRequests.observeResult = null
    }

    @Test fun userspaceKeepsSystemVpnConnectionActionAndNativeActions() = settingsTest {
        environment()
        val activity = openSettings()
        settle(activity)
        val fragment = fragment(activity)
        assertTrue(fragment.findPreference<Preference>("settings_connection")!!.isVisible)
        assertNotNull(fragment.findPreference<Preference>("system_vpn_settings"))
        assertNull(fragment.findPreference<Preference>("kernel_module_enabler"))
        assertNull(fragment.findPreference<Preference>("multiple_tunnels"))
        assertNull(fragment.findPreference<Preference>("quick_tile"))
        assertNotNull(fragment.findPreference<SwitchPreferenceCompat>("dark_theme"))
        assertNotNull(fragment.findPreference<Preference>("zip_exporter"))
        assertNotNull(fragment.findPreference<Preference>("version"))
        assertEquals(Int.MAX_VALUE, fragment.preferenceScreen.initialExpandedChildrenCount)
        assertNonemptyCategories(fragment)
    }

    @Test @Config(shadows = [GoBackendLifecycleTest.LibraryShadow::class, SettingsVpnPolicyTest.NativeVersion::class])
    fun goBackendRetainsOnlyTheSavedModeOffRepairAndRejectsOn() = settingsTest {
        val env = environment()
        env.replaceBackend(GoBackend(env.application))
        val activity = openSettings()
        settle(activity)
        val preference = fragment(activity).findPreference<SwitchPreferenceCompat>("multiple_tunnels")!!
        assertFalse(preference.isVisible)
        assertFalse(preference.isChecked)
        assertFalse(preference.callChangeListener(true))
        runCurrent()
        assertFalse(UserKnobs.multipleTunnels.first())

        UserKnobs.setMultipleTunnels(true)
        settle(activity)
        assertTrue(preference.isVisible)
        assertTrue(preference.isChecked)
        assertTrue(preference.isEnabled)
        assertEquals(activity.getString(R.string.multiple_tunnels_saved_title), preference.title)
        assertEquals(activity.getString(R.string.multiple_tunnels_saved_summary), preference.summaryOn)
        preference.performClick()
        UserKnobs.multipleTunnels.first { !it }
        runCurrent()
        assertFalse(preference.isVisible)
        assertFalse(preference.isChecked)
        controllers.last().recreate()
        settle(controllers.last().get())
        assertFalse(fragment(controllers.last().get()).findPreference<Preference>("multiple_tunnels")!!.isVisible)
    }

    @Test fun automationUnsupportedBackendDisablesEnableWithoutClaimingASelectedDefaultOrSuccess() = settingsTest {
        environment()
        val activity = openSettings()
        settle(activity)
        val fragment = fragment(activity)
        assertEquals(activity.getString(R.string.automation_choose_default), fragment.findPreference<Preference>("automation_default")!!.summary)
        assertEquals(activity.getString(R.string.automation_unsupported), fragment.findPreference<Preference>("automation_status")!!.summary)
        assertTrue(fragment.findPreference<Preference>("automation_enable")!!.isVisible)
        assertFalse(fragment.findPreference<Preference>("automation_enable")!!.isEnabled)
        assertFalse(fragment.findPreference<Preference>("automation_resume")!!.isVisible)
        assertFalse(fragment.findPreference<Preference>("automation_disable")!!.isVisible)
    }

    @Test fun automationExitFeedbackDistinguishesPinLossAndUnknownWithoutClaimingAndroidHandling() = settingsTest {
        val env = environment()
        val activity = openSettings()
        settle(activity)
        val controller = env.manager.networkAutomation
        @Suppress("UNCHECKED_CAST")
        val statuses = controller.javaClass.getDeclaredField("mutableStatus").apply { isAccessible = true }
            .get(controller) as MutableStateFlow<NetworkAutomationController.Status>
        val fragment = fragment(activity)
        for ((condition, summary) in listOf(
            NetworkAutomationController.Condition.EXIT_RETAINED_PIN to R.string.automation_status_exit_pin,
            NetworkAutomationController.Condition.EXIT_NO_TRANSPORT to R.string.automation_status_exit_unavailable,
            NetworkAutomationController.Condition.EXIT_UNKNOWN to R.string.automation_status_exit_unknown,
        )) {
            statuses.value = NetworkAutomationController.Status(condition, false, "fixture", NetworkAutomationState.Exit.FAILED)
            runCurrent()
            val actual = fragment.findPreference<Preference>("automation_status")!!.summary
            assertEquals(activity.getString(summary), actual)
            assertNotEquals(activity.getString(R.string.automation_status_android), actual)
            assertTrue(fragment.findPreference<Preference>("automation_enable")!!.isVisible)
            assertFalse(fragment.findPreference<Preference>("automation_resume")!!.isVisible)
        }
    }

    @Test fun automationPendingSettlementDisablesActionsAndRetainsExplicitDisableRow() = settingsTest {
        val env = environment()
        val activity = openSettings()
        settle(activity)
        @Suppress("UNCHECKED_CAST")
        val statuses = env.manager.networkAutomation.javaClass.getDeclaredField("mutableStatus").apply { isAccessible = true }
            .get(env.manager.networkAutomation) as MutableStateFlow<NetworkAutomationController.Status>
        statuses.value = NetworkAutomationController.Status(NetworkAutomationController.Condition.EXIT_PENDING,
            false, "fixture", NetworkAutomationState.Exit.PENDING, true)
        runCurrent()
        val fragment = fragment(activity)
        assertFalse(fragment.findPreference<Preference>("automation_default")!!.isEnabled)
        assertFalse(fragment.findPreference<Preference>("automation_enable")!!.isEnabled)
        assertTrue(fragment.findPreference<Preference>("automation_disable")!!.isVisible)
        assertFalse(fragment.findPreference<Preference>("automation_disable")!!.isEnabled)
        assertEquals(activity.getString(R.string.automation_status_exit_pending), fragment.findPreference<Preference>("automation_status")!!.summary)
    }

    @Test fun kernelSupportFilesystemProbeRunsOffMain() = settingsTest {
        environment()
        val activity = openSettings()
        settle(activity)
        assertEquals(false, KernelSupport.probedOnMain)
    }

    @Test @Config(sdk = [36]) fun preferenceStructuresHaveNoInvalidResourceIdsAfterRecreation() = settingsTest {
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

    @Test @Config(sdk = [33]) fun modernAndroidKeepsTileAndRemovesEmptyAppearance() = settingsTest {
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

    @Test @Config(sdk = [33]) fun addedTileIsRemovedWithoutRemovingControlSection() = settingsTest {
        environment()
        UserKnobs.setQuickTileAdded(true)
        val activity = openSettings()
        settle(activity)
        val fragment = fragment(activity)
        assertFalse(fragment.findPreference<Preference>("quick_tile")!!.isVisible)
        assertNotNull(fragment.findPreference<Preference>("allow_remote_control_intents"))
        assertNotNull(fragment.findPreference<Preference>("settings_control"))
    }

    @Test fun adminRestrictionRemovesExportButKeepsLogs() = settingsTest {
        val env = environment()
        val restrictions = env.application.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
        shadowOf(restrictions).setApplicationRestrictions(Bundle().apply { putBoolean("disable_config_export", true) })
        val activity = openSettings()
        settle(activity)
        assertNull(fragment(activity).findPreference<Preference>("zip_exporter"))
        assertNotNull(fragment(activity).findPreference<Preference>("log_viewer"))
        assertNotNull(fragment(activity).findPreference<Preference>("settings_data"))
    }

    @Test fun pendingBackendDefersConnectionHeaderUntilSystemVpnActionIsReady() = settingsTest {
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

    @Test fun kernelBackendKeepsSwitchKeysValuesAndActionOwner() = settingsTest {
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
            preference.performClick()
        }
        UserKnobs.multipleTunnels.first { it }
        settle(activity)
        controllers.last().recreate()
        settle(controllers.last().get())
        for (key in listOf("restore_on_boot", "multiple_tunnels")) {
            assertTrue(fragment(controllers.last().get()).findPreference<SwitchPreferenceCompat>(key)!!.isChecked)
        }
        assertEquals(0, shell.calls)
    }

    @Test fun rootProbeFailureRemovesKernelActionAndKeepsSystemVpnGroup() = settingsTest {
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

    @Test fun remoteControlValueAndLogNavigationSurviveRecreation() = settingsTest {
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

    @Test @Config(sdk = [33]) fun tileSuccessRemovesOnlyTileAndPreservesExpandedGroups() = settingsTest {
        quickTileMembershipPhase = "initial membership"
        environment()
        val activity = openSettingsWithLoadedMembership()
        assertTrue(fragment(activity).findPreference<Preference>("quick_tile")!!.isVisible)
        val membershipCommitted = observeQuickTileTransaction()
        quickTileMembershipPhase = "native request"
        fragment(activity).findPreference<Preference>("quick_tile")!!.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        runCurrent()
        quickTileMembershipPhase = "committed membership"
        assertEquals(true, membershipCommitted.await()[booleanPreferencesKey("quick_tile_added")])
        quickTileMembershipPhase = "persisted membership readback"
        assertEquals(true, UserKnobs.quickTileAdded.first())
        quickTileMembershipPhase = "settle after persistence"
        settle(activity)
        assertFalse(fragment(activity).findPreference<Preference>("quick_tile")!!.isVisible)
        assertNotNull(fragment(activity).findPreference<Preference>("allow_remote_control_intents"))
        assertEquals(Int.MAX_VALUE, fragment(activity).preferenceScreen.initialExpandedChildrenCount)
        quickTileMembershipPhase = "Settings recreation"
        controllers.last().recreate()
        val recreated = controllers.last().get()
        quickTileMembershipPhase = "recreated Settings settle"
        settle(recreated)
        assertFalse(fragment(recreated).findPreference<Preference>("quick_tile")!!.isVisible)
        quickTileMembershipPhase = null
        quickTileMembershipDetails = null
        TileRequests.observeResult = null
    }

    @Test @Config(sdk = [33]) fun alreadyAddedResultRetainsMembershipAcrossReopening() = settingsTest {
        quickTileMembershipPhase = "initial membership"
        environment()
        TileRequests.result = android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
        val activity = openSettingsWithLoadedMembership()
        assertTrue(fragment(activity).findPreference<Preference>("quick_tile")!!.isVisible)
        val membershipCommitted = observeQuickTileTransaction()
        quickTileMembershipPhase = "native request"
        fragment(activity).findPreference<Preference>("quick_tile")!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        quickTileMembershipPhase = "committed membership"
        assertEquals(true, membershipCommitted.await()[booleanPreferencesKey("quick_tile_added")])
        quickTileMembershipPhase = "persisted membership readback"
        assertEquals(true, UserKnobs.quickTileAdded.first())
        quickTileMembershipPhase = "settle after persistence"
        settle(activity)
        assertFalse(fragment(activity).findPreference<Preference>("quick_tile")!!.isVisible)
        quickTileMembershipPhase = "Settings reopening"
        val reopened = openSettings()
        quickTileMembershipPhase = "reopened Settings settle"
        settle(reopened)
        assertFalse(fragment(reopened).findPreference<Preference>("quick_tile")!!.isVisible)
        quickTileMembershipPhase = null
        quickTileMembershipDetails = null
        TileRequests.observeResult = null
    }

    @Test @Config(sdk = [33]) fun cancelledAddKeepsUnknownMembershipAndAvailableRow() = settingsTest {
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

    @Test @Config(sdk = [33]) fun requestErrorsKeepUnknownMembershipAndExistingFeedback() = settingsTest {
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

    @Test @Config(sdk = [33]) fun membershipChangesUpdateOnlyTheTileWhileSettingsStaysOpen() = settingsTest {
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

    @Test @Config(sdk = [33]) fun loadingMembershipDoesNotExposeSetupUntilItsFirstValue() = settingsTest {
        val env = environment()
        val dataStore = com.vpn0wn.android.Application.getPreferencesDataStore()
        val loaded = CompletableDeferred<Unit>()
        val delayedStore = object : DataStore<Preferences> by dataStore {
            override val data = flow {
                loaded.await()
                emit(preferencesOf(booleanPreferencesKey("quick_tile_added") to false))
            }
        }
        val activity = openSettings { setApplicationField(env, "preferencesDataStore", delayedStore) }
        runCurrent()
        assertFalse(fragment(activity).findPreference<Preference>("quick_tile")!!.isVisible)
        loaded.complete(Unit)
        settle(activity)
        assertTrue(fragment(activity).findPreference<Preference>("quick_tile")!!.isVisible)
    }

    @Test @Config(sdk = [33]) fun unavailableMembershipKeepsSetupHiddenWithoutWritingAbsence() = settingsTest {
        val env = environment()
        val dataStore = com.vpn0wn.android.Application.getPreferencesDataStore()
        val unavailableStore = object : DataStore<Preferences> by dataStore {
            override val data = flow<Preferences> { throw IOException("fixture unavailable") }
        }
        val activity = openSettings { setApplicationField(env, "preferencesDataStore", unavailableStore) }
        settle(activity)
        assertFalse(fragment(activity).findPreference<Preference>("quick_tile")!!.isVisible)
        for (key in listOf("automation_default", "automation_enable", "automation_resume", "automation_disable")) {
            assertFalse(fragment(activity).findPreference<Preference>(key)!!.isEnabled)
        }
        setApplicationField(env, "preferencesDataStore", dataStore)
        assertNull(UserKnobs.quickTileAdded.first())
    }

    @Test @Config(sdk = [33]) fun membershipReadFailureAfterLoadedRemovalHidesTheAvailableRow() = settingsTest {
        val env = environment()
        KernelSupport.available = true
        val shell = ProbeShell(env.application)
        val tools = ToolsInstaller(env.application, shell)
        setApplicationField(env, "futureBackend", CompletableDeferred<Backend>(WgQuickBackend(env.application, shell, tools)))
        UserKnobs.setQuickTileAdded(false)
        UserKnobs.setMultipleTunnels(true)
        val dataStore = com.vpn0wn.android.Application.getPreferencesDataStore()
        val failRead = CompletableDeferred<Unit>()
        val failingStore = object : DataStore<Preferences> by dataStore {
            override val data = flow {
                emit(dataStore.data.first())
                failRead.await()
                throw IOException("fixture unavailable")
            }
        }
        val activity = openSettings { setApplicationField(env, "preferencesDataStore", failingStore) }
        settle(activity)
        val tile = fragment(activity).findPreference<Preference>("quick_tile")!!
        assertTrue(tile.isVisible)
        failRead.complete(Unit)
        runCurrent()
        assertFalse(tile.isVisible)
        assertFalse(fragment(activity).findPreference<Preference>("multiple_tunnels")!!.isEnabled)
        assertTrue(fragment(activity).findPreference<SwitchPreferenceCompat>("multiple_tunnels")!!.isChecked)
        for (key in listOf("automation_default", "automation_enable", "automation_resume", "automation_disable")) {
            assertFalse(fragment(activity).findPreference<Preference>(key)!!.isEnabled)
        }
        setApplicationField(env, "preferencesDataStore", dataStore)
        assertEquals(false, UserKnobs.quickTileAdded.first())
        assertTrue(UserKnobs.multipleTunnels.first())
    }

    @Test @Config(sdk = [33]) fun lateSuccessfulResultPersistsAfterSettingsDestruction() = settingsTest {
        quickTileMembershipPhase = "initial membership"
        environment()
        TileRequests.deferResult = true
        val activity = openSettingsWithLoadedMembership()
        assertTrue(fragment(activity).findPreference<Preference>("quick_tile")!!.isVisible)
        val membershipCommitted = observeQuickTileTransaction()
        quickTileMembershipPhase = "native request"
        fragment(activity).findPreference<Preference>("quick_tile")!!.performClick()
        quickTileMembershipPhase = "Settings destruction"
        controllers.last().pause().stop().destroy()
        controllers.removeAt(controllers.lastIndex)
        quickTileMembershipPhase = "late native result"
        TileRequests.pendingResult!!.invoke()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        quickTileMembershipPhase = "committed membership"
        assertEquals(true, membershipCommitted.await()[booleanPreferencesKey("quick_tile_added")])
        quickTileMembershipPhase = "persisted membership readback"
        assertEquals(true, UserKnobs.quickTileAdded.first())
        quickTileMembershipPhase = "Settings reopening"
        val reopened = openSettings()
        quickTileMembershipPhase = "reopened Settings settle"
        settle(reopened)
        assertFalse(fragment(reopened).findPreference<Preference>("quick_tile")!!.isVisible)
        quickTileMembershipPhase = null
        quickTileMembershipDetails = null
        TileRequests.observeResult = null
    }

    @Test @Config(sdk = [33]) fun removalWinsOverDeferredSuccessfulResultsAfterSettingsSurvivesOrCloses() = settingsTest {
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
                com.vpn0wn.android.Application.getPreferencesDataStore().updateData { it }
                assertEquals(false, UserKnobs.quickTileAdded.first())
                val current = if (closeSettings) openSettingsWithLoadedMembership() else activity
                settle(current)
                assertTrue(fragment(current).findPreference<Preference>("quick_tile")!!.isVisible)
                controllers.last().pause().stop().destroy()
                controllers.removeAt(controllers.lastIndex)
            }
        }
    }

    @Test @Config(sdk = [33]) fun successfulResultPersistenceFailureLeavesUnknownFallback() = settingsTest {
        val env = environment()
        val activity = openSettings()
        settle(activity)
        val dataStore = com.vpn0wn.android.Application.getPreferencesDataStore()
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

    @Test fun versionRowKeepsNativeArtworkWithoutGenericIconTint() = settingsTest {
        environment()
        val activity = openSettings()
        settle(activity)
        val row = activity.layoutInflater.inflate(R.layout.vpn0wn_preference_item, null)
        val icon = row.findViewById<ImageView>(android.R.id.icon)
        assertNotNull(icon.imageTintList)
        fragment(activity).findPreference<Preference>("version")!!.onBindViewHolder(PreferenceViewHolder.createInstanceForTests(row))
        assertNull(icon.imageTintList)
        assertNotNull(icon.drawable)
    }

    @Test @Config(sdk = [28, 33]) fun offlineCreditsAndVersionNavigationSurviveRecreationWithoutPersistingAction() = settingsTest {
        environment()
        val activity = openSettings()
        settle(activity)
        val licenses = fragment(activity).findPreference<Preference>("licenses")!!
        val preferencesBefore = com.vpn0wn.android.Application.getPreferencesDataStore().data.first().asMap()
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
        assertEquals(preferencesBefore, com.vpn0wn.android.Application.getPreferencesDataStore().data.first().asMap())
    }

    @Test @Config(sdk = [33]) fun rootInsetsAreIdempotentAndLeaveImeInsetsForDescendants() = settingsTest {
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
    fun inflatedPreferenceAndHeaderPreserveTheirFontRolesInBothSystemModes() = settingsTest {
        environment()
        for (mode in listOf("notnight", "night")) {
            RuntimeEnvironment.setQualifiers("en-$mode")
            val activity = openSettings()
            settle(activity)
            val expectedMode = if (mode == "night") Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            assertEquals("$mode effective activity mode", expectedMode,
                activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            val preference = fragment(activity).findPreference<Preference>("system_vpn_settings")!!
            val row = activity.layoutInflater.inflate(R.layout.vpn0wn_preference_item, fragment(activity).listView, false)
            preference.onBindViewHolder(PreferenceViewHolder.createInstanceForTests(row))
            val title = row.findViewById<TextView>(android.R.id.title)
            val summary = row.findViewById<TextView>(android.R.id.summary)
            val header = textViews(activity.findViewById(R.id.settings_root)).single {
                it.text == activity.getString(R.string.vpn0wn_settings_title)
            }

            assertTrue(title.text.isNotEmpty())
            assertTrue(summary.text.isNotEmpty())
            assertEquals("$mode preference title", requireNotNull(ResourcesCompat.getFont(activity, R.font.inter_tight)), title.typeface)
            assertEquals("$mode preference summary", requireNotNull(ResourcesCompat.getFont(activity, R.font.jetbrains_mono_regular)), summary.typeface)
            assertEquals("$mode styled header", requireNotNull(ResourcesCompat.getFont(activity, R.font.jetbrains_mono_medium)), header.typeface)
            assertFalse(summary.paint.isFakeBoldText)
            assertFalse(header.paint.isFakeBoldText)
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun renderedSmallPortraitLandscapeAndLargeFontRtlStayUsableInBothThemes() = settingsTest {
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

    @Test fun fixtureCleanupWaitsForRealIoCancellationBeforeReturning() = settingsTest {
        val ownerNames = listOf("application", "activity", "fragment", "view")
        for ((index, ownerName) in ownerNames.withIndex()) {
            val env = environment()
            val activity = openSettings()
            settle(activity)
            val owner = listOf(env.applicationScope, activity.lifecycleScope, fragment(activity).lifecycleScope,
                fragment(activity).viewLifecycleOwner.lifecycleScope)[index]
            val entered = CompletableDeferred<Unit>()
            val cancelling = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val completedOnMain = AtomicInteger()
            val worker = owner.launch(Dispatchers.IO) {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        cancelling.complete(Unit)
                        release.await()
                        withContext(Dispatchers.Main) { completedOnMain.incrementAndGet() }
                    }
                }
            }
            var cleanup: Job? = null
            try {
                entered.await()
                worker.cancel()
                cancelling.await()
                val closing = async { closeSettings() }
                cleanup = closing
                runCurrent()
                assertFalse("Cleanup must await the $ownerName IO cancellation descendant", closing.isCompleted)
                release.complete(Unit)
                closing.await()
                assertTrue("$ownerName worker completed", worker.isCompleted)
                assertTrue("$ownerName owner completed", owner.coroutineContext[Job]!!.isCompleted)
                assertEquals("$ownerName finalizer reached Main", 1, completedOnMain.get())
            } finally {
                release.complete(Unit)
                withContext(NonCancellable) {
                    worker.cancel()
                    worker.join()
                    cleanup?.join()
                }
            }
        }
    }

    private fun settingsTest(body: suspend TestScope.() -> Unit) = runTest {
        val result = runCatching { body() }
        if (result.isFailure && quickTileMembershipPhase != null) {
            val details = quickTileMembershipDetails?.invoke().orEmpty()
            quickTileMembershipDetails = { details }
        }
        val cleanup = runCatching { closeSettings() }
        Dispatchers.resetMain()
        val failure = result.exceptionOrNull()
        if (failure != null) {
            cleanup.exceptionOrNull()?.let { if (it !== failure) failure.addSuppressed(it) }
            throw failure
        }
        cleanup.getOrThrow()
    }

    private suspend fun closeSettings() = withContext(NonCancellable) {
        val destruction = controllers.map { controller ->
            runCatching {
                captureLifecycleJobs(controller.get())
                controller.pause().stop().destroy()
            }
        }
        controllers.clear()
        try {
            lifecycleJobs.forEach { it.cancel() }
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            try {
                if (::testEnvironment.isInitialized) testEnvironment.close()
            } finally {
                lifecycleJobs.toList().joinAll()
                lifecycleJobs.clear()
            }
        }
        destruction.forEach { it.getOrThrow() }
    }

    private fun captureLifecycleJobs(activity: SettingsActivity) {
        lifecycleJobs += activity.lifecycleScope.coroutineContext[Job]!!
        activity.supportFragmentManager.fragments.forEach { fragment ->
            lifecycleJobs += fragment.lifecycleScope.coroutineContext[Job]!!
            fragment.viewLifecycleOwnerLiveData.value?.lifecycleScope?.coroutineContext?.get(Job)?.let {
                lifecycleJobs += it
            }
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

    private fun quickTileMembershipOwnerDetails(): String {
        val scope = com.vpn0wn.android.Application.getCoroutineScope()
        return "appMatches=${com.vpn0wn.android.Application.get() === testEnvironment.application} " +
            "scopeMatches=${scope === testEnvironment.applicationScope} scopeActive=${scope.coroutineContext[Job]?.isActive} " +
            "revision=${UserKnobs.quickTileRemovalRevision} main=${Looper.myLooper() == Looper.getMainLooper()} " +
            "looperIdle=${shadowOf(Looper.getMainLooper()).isIdle}"
    }

    private fun observeQuickTileTransaction(): CompletableDeferred<Preferences> {
        val dataStore = com.vpn0wn.android.Application.getPreferencesDataStore()
        val membershipKey = booleanPreferencesKey("quick_tile_added")
        val membershipCommitted = CompletableDeferred<Preferences>()
        val preRequestRevision = UserKnobs.quickTileRemovalRevision
        val writes = AtomicInteger()
        val writeStages = StringBuffer("not entered")
        val observingStore = object : DataStore<Preferences> by dataStore {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                val operation = writes.incrementAndGet()
                writeStages.append("; update=$operation entered")
                try {
                    return dataStore.updateData { previous ->
                        writeStages.append("; update=$operation transform revision=${UserKnobs.quickTileRemovalRevision}")
                        transform(previous).also {
                            writeStages.append("; update=$operation transformed=${it[membershipKey]} revision=${UserKnobs.quickTileRemovalRevision}")
                        }
                    }.also {
                        writeStages.append("; update=$operation committed=${it[membershipKey]}")
                        membershipCommitted.complete(it)
                    }
                } catch (failure: Throwable) {
                    writeStages.append("; update=$operation failed=${failure.javaClass.name}")
                    membershipCommitted.completeExceptionally(failure)
                    throw failure
                }
            }
        }
        testEnvironment.replacePreferences(observingStore)
        var callbacks = 0
        var callbackOwner = "not delivered"
        TileRequests.observeResult = { result ->
            callbacks++
            callbackOwner = "result=$result ${quickTileMembershipOwnerDetails()} " +
                "storeMatches=${com.vpn0wn.android.Application.getPreferencesDataStore() === observingStore}"
        }
        quickTileMembershipDetails = {
            "requests=${TileRequests.requestCount} requestedResult=${TileRequests.requestedResult} callbacks=$callbacks " +
                "callback=[$callbackOwner] preRequestRevision=$preRequestRevision current=[${quickTileMembershipOwnerDetails()} " +
                "storeMatches=${com.vpn0wn.android.Application.getPreferencesDataStore() === observingStore}] " +
                "writes=${writes.get()} stages=[$writeStages] transactionCompleted=${membershipCommitted.isCompleted}"
        }
        return membershipCommitted
    }

    private fun TestScope.environment(): TunnelTestEnvironment {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        testEnvironment = TunnelTestEnvironment(folder.root, backgroundScope)
        if (quickTileMembershipPhase != null) {
            quickTileMembershipDetails = {
                "requests=${TileRequests.requestCount} requestedResult=${TileRequests.requestedResult} " +
                    "owner=[${quickTileMembershipOwnerDetails()}]"
            }
        }
        val shell = ProbeShell(testEnvironment.application)
        setApplicationField(testEnvironment, "rootShell", shell)
        setApplicationField(testEnvironment, "toolsInstaller", ToolsInstaller(testEnvironment.application, shell))
        val restrictions = testEnvironment.application.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
        shadowOf(restrictions).setApplicationRestrictions(Bundle())
        return testEnvironment
    }

    private fun openSettings(beforeStart: () -> Unit = {}): SettingsActivity {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        controllers += controller
        controller.get().setTheme(R.style.AppTheme)
        controller.create()
        controller.get().supportFragmentManager.executePendingTransactions()
        beforeStart()
        controller.start().postCreate(null).resume().visible().topActivityResumed(true)
        controller.get().supportFragmentManager.executePendingTransactions()
        captureLifecycleJobs(controller.get())
        return controller.get()
    }

    private suspend fun TestScope.openSettingsWithLoadedMembership(): SettingsActivity {
        val phase = quickTileMembershipPhase
        val dataStore = com.vpn0wn.android.Application.getPreferencesDataStore()
        val membershipLoaded = CompletableDeferred<Unit>()
        val observingStore = object : DataStore<Preferences> by dataStore {
            override val data = flow {
                dataStore.data.collect { preferences ->
                    emit(preferences)
                    membershipLoaded.complete(Unit)
                }
            }
        }
        val activity = openSettings { setApplicationField(testEnvironment, "preferencesDataStore", observingStore) }
        runCurrent()
        if (phase != null) quickTileMembershipPhase = "$phase: initial Settings settle"
        settle(activity)
        if (phase != null) quickTileMembershipPhase = "$phase: membership emission"
        membershipLoaded.await()
        runCurrent()
        setApplicationField(testEnvironment, "preferencesDataStore", dataStore)
        quickTileMembershipPhase = phase
        return activity
    }

    private suspend fun TestScope.settle(activity: SettingsActivity) {
        val phase = quickTileMembershipPhase
        captureLifecycleJobs(activity)
        activity.supportFragmentManager.executePendingTransactions()
        runCurrent()
        if (phase != null) quickTileMembershipPhase = "$phase: fragment children"
        fragment(activity).lifecycleScope.coroutineContext[Job]!!.children.toList().joinAll()
        val policyJob = SettingsActivity.SettingsFragment::class.java.getDeclaredField("vpnPolicyJob")
            .apply { isAccessible = true }.get(fragment(activity)) as Job?
        if (phase != null) quickTileMembershipPhase = "$phase: VPN policy job"
        policyJob?.join()
        if (phase != null) quickTileMembershipPhase = "$phase: activity children"
        activity.lifecycleScope.coroutineContext[Job]!!.children.toList().joinAll()
        runCurrent()
        quickTileMembershipPhase = phase
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
        if (name == "preferencesDataStore") {
            @Suppress("UNCHECKED_CAST")
            env.replacePreferences(value as DataStore<Preferences>)
            return
        }
        com.vpn0wn.android.Application::class.java.getDeclaredField(name).apply { isAccessible = true }.set(env.application, value)
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
            requestCount++
            requestedResult = requestResult
            val deliver = {
                executor.execute {
                    observeResult?.invoke(requestResult)
                    callback.accept(requestResult)
                }
            }
            if (deferResult) pendingResult = deliver else deliver()
        }

        companion object {
            var result = android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
            var deferResult = false
            var pendingResult: (() -> Unit)? = null
            var requestCount = 0
            var requestedResult: Int? = null
            var observeResult: ((Int) -> Unit)? = null
        }
    }
}
