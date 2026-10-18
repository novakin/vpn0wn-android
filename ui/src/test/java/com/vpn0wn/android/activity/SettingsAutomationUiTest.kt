/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.activity

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.SwitchPreferenceCompat
import com.vpn0wn.android.Application as VpnApplication
import com.vpn0wn.android.NetworkMonitorService
import com.vpn0wn.android.R
import com.vpn0wn.android.backend.GoBackend
import com.vpn0wn.android.backend.GoBackendLifecycleTest
import com.vpn0wn.android.model.NetworkAutomationController
import com.vpn0wn.android.model.NetworkAutomationState
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.android.util.AppDataTransfer
import com.vpn0wn.android.util.RootShell
import com.vpn0wn.android.util.ToolsInstaller
import com.vpn0wn.android.util.UserKnobs
import com.vpn0wn.config.Config as TunnelConfig
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
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
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowVpnService

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [
    SettingsActivityTest.KernelSupport::class,
    SettingsActivityTest.InstalledTools::class,
    GoBackendLifecycleTest.LibraryShadow::class,
    SettingsVpnPolicyTest.NativeVersion::class,
])
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsAutomationUiTest {
    @get:Rule val folder = TemporaryFolder()
    private val activities = mutableListOf<ActivityController<SettingsActivity>>()
    private val lifecycleJobs = mutableSetOf<Job>()
    private lateinit var testEnvironment: TunnelTestEnvironment

    @Before fun resetPlatform() {
        SettingsActivityTest.KernelSupport.available = false
        ShadowVpnService.setPrepareResult(null)
    }

    @Test fun supportedUserspaceStartsDisabledAndRequiresAnExplicitDefault() = settingsTest {
        val env = environment()
        env.manager.create("only-tunnel", TunnelTestEnvironment.profile())
        val activity = openSettings()
        settle(activity)

        assertEquals(activity.getString(R.string.automation_choose_default), preference(activity, "automation_default").summary)
        assertEquals(activity.getString(R.string.automation_status_disabled), preference(activity, "automation_status").summary)
        assertFalse(preference(activity, "automation_enable").isEnabled)
        assertFalse(preference(activity, "automation_resume").isVisible)
        assertFalse(preference(activity, "automation_disable").isVisible)
        assertNull(state().defaultName)
        assertFalse(state().enabled)
        assertNull(shadowOf(activity).nextStartedService)
    }

    @Test fun importedMultipleModeHasAReachableNativeOffRepairBeforeEnable() = settingsTest {
        val case = Preview("en", 320, 640, 1f)
        configure(case)
        val env = environment()
        env.manager.create("default", TunnelTestEnvironment.profile())
        env.manager.networkAutomation.selectDefault("default")
        assertFalse(state().enabled)
        assertEquals(0, env.manager.importAppData(AppDataTransfer.Archive(
            emptyMap(), mapOf("multiple_tunnels" to true), null, emptyList(),
        )))
        assertTrue(UserKnobs.multipleTunnels.first())
        val activity = openSettings()
        settle(activity)
        val repair = preference(activity, "multiple_tunnels") as SwitchPreferenceCompat
        assertTrue(repair.isVisible && repair.isEnabled && repair.isChecked)
        assertFalse(repair.isPersistent)
        assertEquals(activity.getString(R.string.multiple_tunnels_saved_title), repair.title)
        assertEquals(activity.getString(R.string.multiple_tunnels_saved_summary), repair.summaryOn)
        assertFalse(preference(activity, "automation_enable").isEnabled)
        assertEquals(activity.getString(R.string.automation_unsupported), preference(activity, "automation_status").summary)
        assertNull(shadowOf(activity).nextStartedService)

        val root = activity.findViewById<View>(R.id.settings_root)
        val row = revealPreference(activity, "multiple_tunnels", root, case)
        assertEquals(activity.getString(R.string.multiple_tunnels_saved_title), row.findViewById<TextView>(android.R.id.title).text)
        assertEquals(activity.getString(R.string.multiple_tunnels_saved_summary), row.findViewById<TextView>(android.R.id.summary).text)
        val switch = requireNotNull(row.findViewById<SwitchCompat>(androidx.preference.R.id.switchWidget))
        assertTrue(switch.isChecked)
        assertTrue(row.isEnabled && row.isClickable && row.isFocusable)
        val owner = fragment(activity).viewLifecycleOwner.lifecycleScope.coroutineContext[Job]!!
        val previousJobs = owner.children.toSet()
        assertTrue(row.performClick())
        runCurrent()
        owner.children.filterNot { it in previousJobs }.toList().joinAll()
        settle(activity)

        assertFalse(UserKnobs.multipleTunnels.first())
        assertFalse(repair.isChecked)
        assertFalse(repair.isVisible)
        assertEquals("default", state().defaultName)
        assertFalse(state().enabled)
        assertTrue(preference(activity, "automation_enable").isEnabled)
        assertEquals(activity.getString(R.string.automation_status_disabled), preference(activity, "automation_status").summary)
        assertNull(shadowOf(activity).nextStartedService)
    }

    @Test fun defaultChooserAcceptsOnlyActualNumericOrEndpointFreeProfilesAndCancelKeepsSelection() = settingsTest {
        val env = environment()
        env.manager.create("numeric", peerProfile("192.0.2.1:51820"))
        env.manager.create("endpoint-free", TunnelTestEnvironment.profile())
        env.manager.create("hostname", peerProfile("vpn.example.invalid:51820"))
        val activity = openSettings()
        settle(activity)

        preference(activity, "automation_default").performClick()
        settle(activity)
        val chooser = latestDialog()
        val names = (0 until chooser.listView.adapter.count).map { chooser.listView.adapter.getItem(it).toString() }
        assertEquals(setOf("numeric", "endpoint-free"), names.toSet())
        val index = names.indexOf("numeric")
        val selectionOwner = fragment(activity).viewLifecycleOwner.lifecycleScope.coroutineContext[Job]!!
        val previousJobs = selectionOwner.children.toSet()
        chooser.listView.performItemClick(null, index, chooser.listView.adapter.getItemId(index))
        runCurrent()
        selectionOwner.children.filterNot { it in previousJobs }.toList().joinAll()
        settle(activity)

        assertEquals("numeric", state().defaultName)
        assertFalse("Selecting a default does not enable automation", state().enabled)
        assertEquals("numeric", preference(activity, "automation_default").summary)
        assertTrue(preference(activity, "automation_enable").isEnabled)
        assertNull(shadowOf(activity).nextStartedService)

        preference(activity, "automation_default").performClick()
        settle(activity)
        latestDialog().getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        settle(activity)
        assertEquals("numeric", state().defaultName)
    }

    @Test fun deniedVpnConsentLeavesAutomationDisabledAndExplicitRetryStartsOnlyOneEnableRequest() = settingsTest {
        val env = environment()
        env.manager.create("default", TunnelTestEnvironment.profile())
        env.manager.networkAutomation.selectDefault("default")
        val activity = openSettings()
        settle(activity)
        ShadowVpnService.setPrepareResult(Intent("test.automation.consent"))
        val enable = preference(activity, "automation_enable")

        enable.performClick()
        val denied = requireNotNull(shadowOf(activity).nextStartedActivityForResult)
        assertEquals("test.automation.consent", denied.intent.action)
        enable.performClick()
        assertNull("Consent already pending", shadowOf(activity).nextStartedActivityForResult)
        assertNull(shadowOf(activity).nextStartedService)
        activity.activityResultRegistry.dispatchResult(denied.requestCode, Activity.RESULT_CANCELED, null)
        runCurrent()
        assertFalse(state().enabled)
        assertEquals(activity.getString(R.string.automation_status_disabled), preference(activity, "automation_status").summary)

        enable.performClick()
        val allowed = requireNotNull(shadowOf(activity).nextStartedActivityForResult)
        activity.activityResultRegistry.dispatchResult(allowed.requestCode, Activity.RESULT_OK, null)
        runCurrent()
        assertServiceRequest(activity, NetworkMonitorService.ENABLE)
        enable.performClick()
        assertNull("Pending Enable is not submitted twice", shadowOf(activity).nextStartedService)
        assertFalse("A submitted service request is not an enabled result", state().enabled)
        assertEquals(activity.getString(R.string.automation_status_disabled), preference(activity, "automation_status").summary)
    }

    @Test @Config(sdk = [33])
    fun notificationPermissionIsContextualAndItsDenialDoesNotClaimEnableSucceeded() = settingsTest {
        val env = environment()
        env.manager.create("default", TunnelTestEnvironment.profile())
        env.manager.networkAutomation.selectDefault("default")
        val activity = openSettings()
        settle(activity)
        assertNull(shadowOf(activity).lastRequestedPermission)
        shadowOf(activity).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        preference(activity, "automation_enable").performClick()
        val request = requireNotNull(shadowOf(activity).lastRequestedPermission)
        assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions)
        assertNull(shadowOf(activity).nextStartedService)
        val denied = Intent().putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS, request.requestedPermissions)
            .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS, intArrayOf(PackageManager.PERMISSION_DENIED))
        activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, denied)
        runCurrent()

        assertServiceRequest(activity, NetworkMonitorService.ENABLE)
        assertFalse(state().enabled)
        assertEquals(activity.getString(R.string.automation_status_disabled), preference(activity, "automation_status").summary)
    }

    @Test fun resumeIsExplicitAndPreservesManualHoldAndFailedAttempt() = settingsTest {
        val env = environment()
        env.manager.create("default", TunnelTestEnvironment.profile())
        val manual = NetworkAutomationState.Receipt("default", "DOWN", "DOWN", 2, 3, 4, 5, NetworkAutomationState.Outcome.DOWN)
        val failed = NetworkAutomationState.Receipt("default", "UP", "UP", 2, 3, 4, 6, NetworkAutomationState.Outcome.FAILED)
        NetworkAutomationState.update { it.copy(enabled = true, defaultName = "default", resolved = true,
            paused = true, manual = manual, attempt = failed) }
        val activity = openSettings()
        settle(activity)
        assertEquals(activity.getString(R.string.automation_status_paused), preference(activity, "automation_status").summary)
        assertFalse(preference(activity, "automation_enable").isVisible)
        assertTrue(preference(activity, "automation_resume").isEnabled)
        assertTrue(preference(activity, "automation_disable").isVisible)
        assertNull("Opening paused Settings does not resume", shadowOf(activity).nextStartedService)

        preference(activity, "automation_resume").performClick()
        assertServiceRequest(activity, NetworkMonitorService.RESUME)
        assertEquals(manual, state().manual)
        assertEquals(failed, state().attempt)
        env.manager.networkAutomation.resume()
        runCurrent()
        assertEquals(manual, state().manual)
        assertEquals(failed, state().attempt)
        assertTrue(state().suppressesRestore("default"))
    }

    @Test fun disableRequiresConfirmationAndCancelKeepsMonitoringWithoutClaimingHandBack() = settingsTest {
        val env = environment()
        env.manager.create("default", TunnelTestEnvironment.profile())
        val activity = openSettings()
        settle(activity)
        NetworkAutomationState.update { it.copy(enabled = true, defaultName = "default", resolved = true) }
        env.manager.networkAutomation.refresh()
        runCurrent()
        preference(activity, "automation_disable").performClick()
        val cancelled = latestDialog()
        assertEquals(activity.getString(R.string.automation_disable_confirmation), cancelled.findViewById<TextView>(android.R.id.message)!!.text)
        assertNull(shadowOf(activity).nextStartedService)
        cancelled.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        settle(activity)
        assertNull(shadowOf(activity).nextStartedService)
        assertTrue(state().enabled)
        assertEquals(activity.getString(R.string.automation_status_inactive), preference(activity, "automation_status").summary)

        preference(activity, "automation_disable").performClick()
        latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle(activity)
        assertServiceRequest(activity, NetworkMonitorService.DISABLE)
        assertTrue("Opt-out has not run in the service yet", state().enabled)
        assertEquals("A request is not proof of Android handling", activity.getString(R.string.automation_status_inactive),
            preference(activity, "automation_status").summary)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun nativeAutomationReadoutsAndActionsRemainReachableAcrossThemesSizesFrenchAndRtl() = settingsTest {
        val env = environment()
        env.manager.create("default", TunnelTestEnvironment.profile())
        env.manager.create("numeric", peerProfile("192.0.2.1:51820"))
        for (case in previews()) {
            configure(case)
            val activity = openSettings()
            settle(activity)
            val root = activity.findViewById<View>(R.id.settings_root)
            layout(root, case)
            assertConfiguration(root, case)
            assertFalse(preference(activity, "automation_enable").isEnabled)
            val unavailableEnable = revealPreference(activity, "automation_enable", root, case)
            assertActionTitleState(unavailableEnable, false)
            capture(root, case, "initial-disabled")
            preference(activity, "automation_default").performClick()
            settle(activity)
            val chooser = latestDialog()
            val chooserRoot = requireNotNull(chooser.window).decorView
            layout(chooserRoot, case)
            assertConfiguration(chooserRoot, case)
            assertEquals(2, chooser.listView.adapter.count)
            for (index in 0 until chooser.listView.adapter.count) {
                chooser.listView.setSelection(index)
                layout(chooserRoot, case)
                val choice = chooser.listView.getChildAt(index - chooser.listView.firstVisiblePosition)
                assertTextFits(choice.findViewById(android.R.id.text1))
                assertFullyVisible(choice)
                assertTrue("Native default choice keeps 48dp target", choice.height >= MINIMUM_TARGET_DP * choice.resources.displayMetrics.density)
            }
            capture(chooserRoot, case, "default-chooser")
            chooser.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            for (readout in readouts()) {
                publish(env, readout.condition, readout.enabled, readout.exit, readout.settling)
                runCurrent()
                val status = preference(activity, "automation_status")
                assertEquals(activity.getString(readout.summary), status.summary)
                val statusRow = revealPreference(activity, "automation_status", root, case)
                val summary = statusRow.findViewById<TextView>(android.R.id.summary)
                assertEquals(ResourcesCompat.getFont(activity, R.font.jetbrains_mono_regular), summary.typeface)
                assertEquals("Status warnings keep their readable color", activity.getColor(R.color.vpn0wn_ink_3), summary.currentTextColor)
                assertFalse(summary.paint.isFakeBoldText)
                assertTextFits(summary)
                for (line in 0 until summary.lineCount) revealLine(summary, line, root, case)
                capture(root, case, "${readout.condition.name.lowercase()}-status")
                val keys = when {
                    readout.enabled -> listOf("automation_default", "automation_resume", "automation_disable")
                    readout.exit == NetworkAutomationState.Exit.PENDING -> listOf("automation_default", "automation_enable", "automation_disable")
                    else -> listOf("automation_default", "automation_enable")
                }
                for (key in keys) {
                    val preference = preference(activity, key)
                    assertTrue(preference.isVisible)
                    val row = revealPreference(activity, key, root, case)
                    assertEquals(!readout.settling, preference.isEnabled)
                    assertActionTitleState(row, !readout.settling)
                    val title = row.findViewById<TextView>(android.R.id.title)
                    assertEquals(ResourcesCompat.getFont(activity, R.font.inter_tight), title.typeface)
                    assertTextFits(title)
                    for (line in 0 until title.lineCount) revealLine(title, line, root, case)
                    val detail = row.findViewById<TextView>(android.R.id.summary)
                    assertTextFits(detail)
                    for (line in 0 until detail.lineCount) revealLine(detail, line, root, case)
                    assertTrue("Native row target remains at least 48dp", row.height >= MINIMUM_TARGET_DP * row.resources.displayMetrics.density)
                    if (row.height <= fragment(activity).listView.height) {
                        row.requestRectangleOnScreen(Rect(0, 0, row.width, row.height), true)
                        layout(root, case)
                        assertFullyVisible(row)
                    }
                }
                capture(root, case, "${readout.condition.name.lowercase()}-actions")
            }
            destroyActivities()
        }
        env.manager.networkAutomation.selectDefault("default")
        for (case in previews()) {
            configure(case)
            assertEquals(0, env.manager.importAppData(AppDataTransfer.Archive(
                emptyMap(), mapOf("multiple_tunnels" to true), null, emptyList(),
            )))
            val activity = openSettings()
            settle(activity)
            val root = activity.findViewById<View>(R.id.settings_root)
            layout(root, case)
            val repair = preference(activity, "multiple_tunnels") as SwitchPreferenceCompat
            assertTrue(repair.isVisible && repair.isEnabled && repair.isChecked)
            assertEquals(activity.getString(R.string.multiple_tunnels_saved_title), repair.title)
            assertEquals(activity.getString(R.string.multiple_tunnels_saved_summary), repair.summaryOn)
            assertFalse(preference(activity, "automation_enable").isEnabled)
            assertEquals(activity.getString(R.string.automation_unsupported), preference(activity, "automation_status").summary)
            assertActionTitleState(revealPreference(activity, "automation_enable", root, case), false)
            val row = revealPreference(activity, "multiple_tunnels", root, case)
            assertActionTitleState(row, true)
            val title = row.findViewById<TextView>(android.R.id.title)
            val summary = row.findViewById<TextView>(android.R.id.summary)
            assertEquals(activity.getString(R.string.multiple_tunnels_saved_title), title.text)
            assertEquals(activity.getString(R.string.multiple_tunnels_saved_summary), summary.text)
            assertEquals(ResourcesCompat.getFont(activity, R.font.inter_tight), title.typeface)
            assertEquals(ResourcesCompat.getFont(activity, R.font.jetbrains_mono_regular), summary.typeface)
            assertTextFits(title)
            assertTextFits(summary)
            for (line in 0 until title.lineCount) revealLine(title, line, root, case)
            for (line in 0 until summary.lineCount) revealLine(summary, line, root, case)
            row.requestRectangleOnScreen(Rect(0, 0, row.width, row.height), true)
            layout(root, case)
            assertFullyVisible(row)
            assertTrue(row.height >= MINIMUM_TARGET_DP * row.resources.displayMetrics.density)
            val switch = requireNotNull(row.findViewById<SwitchCompat>(androidx.preference.R.id.switchWidget))
            assertTrue(switch.isChecked && switch.isEnabled)
            assertFullyVisible(switch)
            capture(root, case, "multiple-saved-repair")

            val owner = fragment(activity).viewLifecycleOwner.lifecycleScope.coroutineContext[Job]!!
            val previousJobs = owner.children.toSet()
            assertTrue(row.performClick())
            runCurrent()
            owner.children.filterNot { it in previousJobs }.toList().joinAll()
            settle(activity)
            assertFalse(UserKnobs.multipleTunnels.first())
            assertFalse(repair.isChecked)
            assertFalse(repair.isVisible)
            assertEquals("default", state().defaultName)
            assertFalse(state().enabled)
            assertTrue(preference(activity, "automation_enable").isEnabled)
            assertEquals(activity.getString(R.string.automation_status_disabled), preference(activity, "automation_status").summary)
            val enable = revealPreference(activity, "automation_enable", root, case)
            assertActionTitleState(enable, true)
            for (text in listOf(enable.findViewById<TextView>(android.R.id.title), enable.findViewById<TextView>(android.R.id.summary))) {
                assertTextFits(text)
                for (line in 0 until text.lineCount) revealLine(text, line, root, case)
            }
            capture(root, case, "multiple-saved-corrected")
            assertNull(shadowOf(activity).nextStartedService)
            destroyActivities()
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun nativeDisableConfirmationKeepsTheFullWarningAndBothActionsReachable() = settingsTest {
        val env = environment()
        for (case in previews()) {
            configure(case)
            val activity = openSettings()
            settle(activity)
            publish(env, NetworkAutomationController.Condition.OWNED, true)
            runCurrent()
            preference(activity, "automation_disable").performClick()
            val dialog = latestDialog()
            val root = requireNotNull(dialog.window).decorView
            layout(root, case)
            assertConfiguration(root, case)
            val warning = requireNotNull(dialog.findViewById<TextView>(android.R.id.message))
            assertEquals(activity.getString(R.string.automation_disable_confirmation), warning.text)
            assertTextFits(warning)
            for (line in 0 until warning.lineCount) {
                revealLine(warning, line, root, case)
                if (line == 0 || line == warning.lineCount - 1) capture(root, case, "disable-warning-$line")
            }
            for (button in listOf(DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_POSITIVE)) {
                val action = dialog.getButton(button)
                action.requestRectangleOnScreen(Rect(0, 0, action.width, action.height), true)
                layout(root, case)
                assertTextFits(action)
                assertFullyVisible(action)
                assertTrue(action.isEnabled && action.isClickable && action.isFocusable)
                assertTrue("Native dialog action keeps 48dp target", action.height >= MINIMUM_TARGET_DP * action.resources.displayMetrics.density)
            }
            capture(root, case, "disable-actions")
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            destroyActivities()
        }
    }

    private fun settingsTest(body: suspend TestScope.() -> Unit) = runTest {
        val qualifiers = RuntimeEnvironment.getQualifiers()
        val fontScale = RuntimeEnvironment.getApplication().resources.configuration.fontScale
        val locale = Locale.getDefault()
        val result = runCatching { body() }
        val cleanup = runCatching {
            withContext(NonCancellable) {
                destroyActivities()
                if (::testEnvironment.isInitialized) testEnvironment.close()
                lifecycleJobs.toList().joinAll()
                lifecycleJobs.clear()
            }
        }
        RuntimeEnvironment.setQualifiers(qualifiers)
        RuntimeEnvironment.setFontScale(fontScale)
        Locale.setDefault(locale)
        Dispatchers.resetMain()
        val failure = result.exceptionOrNull()
        if (failure != null) {
            cleanup.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        cleanup.getOrThrow()
    }

    private fun TestScope.environment(): TunnelTestEnvironment {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        testEnvironment = TunnelTestEnvironment(folder.root, backgroundScope)
        testEnvironment.replaceBackend(GoBackend(testEnvironment.application))
        val shell = RootShell(testEnvironment.application)
        for ((name, value) in mapOf("rootShell" to shell, "toolsInstaller" to ToolsInstaller(testEnvironment.application, shell))) {
            VpnApplication::class.java.getDeclaredField(name).apply { isAccessible = true }.set(testEnvironment.application, value)
        }
        return testEnvironment
    }

    private fun openSettings(): SettingsActivity {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        activities += controller
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
        controller.get().supportFragmentManager.executePendingTransactions()
        return controller.get()
    }

    private fun destroyActivities() {
        for (controller in activities) {
            val activity = controller.get()
            lifecycleJobs += activity.lifecycleScope.coroutineContext[Job]!!
            lifecycleJobs += fragment(activity).lifecycleScope.coroutineContext[Job]!!
            fragment(activity).viewLifecycleOwnerLiveData.value?.lifecycleScope?.coroutineContext?.get(Job)?.let { lifecycleJobs += it }
            controller.pause().stop().destroy()
        }
        activities.clear()
        lifecycleJobs.forEach { it.cancel() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private suspend fun TestScope.settle(activity: SettingsActivity) {
        activity.supportFragmentManager.executePendingTransactions()
        runCurrent()
        fragment(activity).lifecycleScope.coroutineContext[Job]!!.children.toList().joinAll()
        val policy = SettingsActivity.SettingsFragment::class.java.getDeclaredField("vpnPolicyJob")
            .apply { isAccessible = true }.get(fragment(activity)) as Job?
        policy?.join()
        activity.lifecycleScope.coroutineContext[Job]!!.children.toList().joinAll()
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun fragment(activity: SettingsActivity) =
        activity.supportFragmentManager.findFragmentById(R.id.settings_content) as SettingsActivity.SettingsFragment

    private fun preference(activity: SettingsActivity, key: String) = requireNotNull(fragment(activity).findPreference<Preference>(key))

    private suspend fun state() = NetworkAutomationState.read(VpnApplication.getPreferencesDataStore().data.first())

    private fun latestDialog() = ShadowDialog.getLatestDialog() as AlertDialog

    private fun assertServiceRequest(activity: SettingsActivity, action: String) {
        val intent = requireNotNull(shadowOf(activity).nextStartedService)
        assertEquals(NetworkMonitorService::class.java.name, intent.component!!.className)
        assertEquals(action, intent.action)
    }

    private fun publish(env: TunnelTestEnvironment, condition: NetworkAutomationController.Condition, enabled: Boolean,
        exit: NetworkAutomationState.Exit = NetworkAutomationState.Exit.NONE, settling: Boolean = false) {
        @Suppress("UNCHECKED_CAST")
        val statuses = NetworkAutomationController::class.java.getDeclaredField("mutableStatus").apply { isAccessible = true }
            .get(env.manager.networkAutomation) as MutableStateFlow<NetworkAutomationController.Status>
        statuses.value = NetworkAutomationController.Status(condition, enabled, "default", exit, settling)
    }

    private fun peerProfile(endpoint: String) = TunnelConfig.parse("""
        [Interface]
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        Address = 192.0.2.2/32
        [Peer]
        PublicKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=
        AllowedIPs = 0.0.0.0/0
        Endpoint = $endpoint
    """.trimIndent().byteInputStream())

    private fun configure(case: Preview) {
        val orientation = if (case.width > case.height) "land" else "port"
        RuntimeEnvironment.setQualifiers("${case.locale}-w${case.width}dp-h${case.height}dp-$orientation-${if (case.night) "night" else "notnight"}-mdpi")
        RuntimeEnvironment.setFontScale(case.scale)
        RuntimeEnvironment.setQualifiers(if (case.locale == "ar") "+ldrtl" else "+ldltr")
    }

    private fun TestScope.layout(root: View, case: Preview) {
        var ready = false
        repeat(LAYOUT_PASSES) {
            root.measure(View.MeasureSpec.makeMeasureSpec(case.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(case.height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            ready = !root.viewTreeObserver.dispatchOnPreDraw()
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue("Native traversal permits drawing", ready)
    }

    private fun assertConfiguration(root: View, case: Preview) {
        val config = root.resources.configuration
        assertEquals(case.locale, config.locales[0].language)
        assertEquals(case.scale, config.fontScale, 0f)
        val direction = if (case.locale == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        assertEquals(direction, config.layoutDirection)
        assertEquals(direction, root.layoutDirection)
        assertEquals(if (case.night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
            config.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertEquals(case.width, root.width)
        assertEquals(case.height, root.height)
    }

    private fun TestScope.revealPreference(activity: SettingsActivity, key: String, root: View, case: Preview): View {
        val list = fragment(activity).listView
        val position = (list.adapter as PreferenceGroupAdapter).getPreferenceAdapterPosition(preference(activity, key))
        assertTrue("$key is in the native preference list", position >= 0)
        list.scrollToPosition(position)
        layout(root, case)
        return requireNotNull(list.findViewHolderForAdapterPosition(position)).itemView
    }

    private fun TestScope.revealLine(view: TextView, line: Int, root: View, case: Preview) {
        val text = view.layout
        val requested = Rect(kotlin.math.floor(text.getLineLeft(line)).toInt(), text.getLineTop(line),
            kotlin.math.ceil(text.getLineRight(line)).toInt(), text.getLineBottom(line))
        requested.offset(view.totalPaddingLeft - view.scrollX, view.totalPaddingTop - view.scrollY)
        view.requestRectangleOnScreen(Rect(requested), true)
        layout(root, case)
        val position = IntArray(2).also(view::getLocationOnScreen)
        val actual = Rect(requested).apply { offset(position[0], position[1]) }
        val visible = Rect()
        assertTrue("Native text is reachable: ${view.text}", view.getGlobalVisibleRect(visible))
        assertTrue("Complete line is reachable: $actual within $visible; ${view.text}", visible.contains(actual))
    }

    private fun assertTextFits(view: TextView) {
        val text = requireNotNull(view.layout)
        val width = view.width - view.compoundPaddingLeft - view.compoundPaddingRight
        val height = view.height - view.compoundPaddingTop - view.compoundPaddingBottom
        assertTrue("Complete characters: ${view.text}", text.lineCount > 0 && text.getLineEnd(text.lineCount - 1) == text.text.length)
        assertTrue("Complete text height: ${view.text}", text.height <= height)
        for (line in 0 until text.lineCount) {
            assertEquals("No ellipsis: ${view.text}", 0, text.getEllipsisCount(line))
            assertTrue("Complete line width: ${view.text}", text.getLineLeft(line) - view.scrollX >= -INK_TOLERANCE_PX &&
                text.getLineRight(line) - view.scrollX <= width + INK_TOLERANCE_PX)
        }
        val bitmap = Bitmap.createBitmap(width + INK_MARGIN_PX * 2, height + INK_MARGIN_PX * 2, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.translate((INK_MARGIN_PX - view.scrollX).toFloat(), (INK_MARGIN_PX - view.scrollY).toFloat())
            text.draw(canvas)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("Native glyphs painted", pixels.any { it ushr ALPHA_SHIFT != 0 })
            assertTrue("Complete painted glyphs: ${view.text}", pixels.indices.all {
                if (pixels[it] ushr ALPHA_SHIFT == 0) true else {
                    val x = it % bitmap.width - INK_MARGIN_PX
                    val y = it / bitmap.width - INK_MARGIN_PX
                    x >= -INK_TOLERANCE_PX && x < width + INK_TOLERANCE_PX && y >= -INK_TOLERANCE_PX && y < height + INK_TOLERANCE_PX
                }
            })
        } finally { bitmap.recycle() }
    }

    private fun assertFullyVisible(view: View) {
        val visible = Rect()
        assertTrue(view.getLocalVisibleRect(visible))
        visible.offset(-view.scrollX, -view.scrollY)
        assertEquals("Complete native action is visible", Rect(0, 0, view.width, view.height), visible)
    }

    private fun assertActionTitleState(row: View, enabled: Boolean) {
        assertEquals("Native row exposes its enabled state", enabled, row.isEnabled)
        val title = row.findViewById<TextView>(android.R.id.title)
        val summary = row.findViewById<TextView>(android.R.id.summary)
        val color = if (enabled) R.color.vpn0wn_ink else R.color.vpn0wn_ink_3
        assertEquals("Action title shows its enabled state", row.context.getColor(color), title.currentTextColor)
        assertEquals("Supporting warnings are not faded", row.context.getColor(R.color.vpn0wn_ink_3), summary.currentTextColor)
    }

    private fun capture(root: View, case: Preview, state: String) {
        assertConfiguration(root, case)
        val project = File(requireNotNull(System.getProperty("user.dir"))).let { if (it.name == "ui") it.parentFile else it }
        val output = System.getenv("VPN0WN_AUTOMATION_OUTPUT")?.let(::File) ?: File(project, ".artifacts/settings-automation-previews")
        assertTrue(output.isDirectory || output.mkdirs())
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            File(output, "${case.locale}-${case.width}x${case.height}-${case.scale}-${if (case.night) "night" else "day"}-$state.png")
                .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun previews() = listOf(
        Preview("en", 320, 640, 1f), Preview("en", 411, 891, 1f),
        Preview("fr", 640, 320, 2f), Preview("ar", 320, 640, 2f),
    ).flatMap { case -> listOf(case, case.copy(night = true)) }

    private fun readouts() = listOf(
        Readout(NetworkAutomationController.Condition.DISABLED, R.string.automation_status_disabled, false),
        Readout(NetworkAutomationController.Condition.INACTIVE, R.string.automation_status_inactive),
        Readout(NetworkAutomationController.Condition.MONITORING, R.string.automation_status_monitoring),
        Readout(NetworkAutomationController.Condition.WAITING_NETWORK, R.string.automation_status_waiting),
        Readout(NetworkAutomationController.Condition.OWNED, R.string.automation_status_owned),
        Readout(NetworkAutomationController.Condition.MANUAL_HOLD, R.string.automation_status_manual),
        Readout(NetworkAutomationController.Condition.PAUSED, R.string.automation_status_paused),
        Readout(NetworkAutomationController.Condition.UNRESOLVED, R.string.automation_status_unresolved),
        Readout(NetworkAutomationController.Condition.FAILED, R.string.automation_status_failed),
        Readout(NetworkAutomationController.Condition.EXIT_PENDING, R.string.automation_status_exit_pending, false, NetworkAutomationState.Exit.PENDING, true),
        Readout(NetworkAutomationController.Condition.EXIT_RETAINED_PIN, R.string.automation_status_exit_pin, false, NetworkAutomationState.Exit.FAILED),
        Readout(NetworkAutomationController.Condition.EXIT_NO_TRANSPORT, R.string.automation_status_exit_unavailable, false, NetworkAutomationState.Exit.FAILED),
        Readout(NetworkAutomationController.Condition.EXIT_UNKNOWN, R.string.automation_status_exit_unknown, false, NetworkAutomationState.Exit.FAILED),
        Readout(NetworkAutomationController.Condition.ANDROID_HANDLING, R.string.automation_status_android, false, NetworkAutomationState.Exit.COMPLETE),
    )

    private data class Preview(val locale: String, val width: Int, val height: Int, val scale: Float, val night: Boolean = false)
    private data class Readout(val condition: NetworkAutomationController.Condition, val summary: Int, val enabled: Boolean = true,
        val exit: NetworkAutomationState.Exit = NetworkAutomationState.Exit.NONE, val settling: Boolean = false)

    companion object {
        private const val LAYOUT_PASSES = 3
        private const val MINIMUM_TARGET_DP = 48
        private const val INK_MARGIN_PX = 8
        private const val INK_TOLERANCE_PX = 1
        private const val ALPHA_SHIFT = 24
    }
}
