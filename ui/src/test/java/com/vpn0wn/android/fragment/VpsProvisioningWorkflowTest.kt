/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.fragment

import android.app.Application
import android.content.DialogInterface
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vpn0wn.android.Application as Vpn0wnApplication
import com.vpn0wn.android.R
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.android.provisioning.VpsCredential
import com.vpn0wn.android.provisioning.ProvisioningException
import com.vpn0wn.android.provisioning.VpsPlanningTestPeer
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.Step
import java.util.Base64
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class VpsProvisioningWorkflowTest {
    @get:Rule val folder = TemporaryFolder()

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun actualActionsRequireExplicitConfirmationBeforeInstallation() = runTest {
        val fixture = fixture()
        try {
            fillAccess(fixture)
            click(fixture, R.string.vpn0wn_continue)
            fixture.model.state.first { !it.busy && it.detect != null }
            settle()
            assertTrue(text(fixture.root, "debian 12").isShown)
            click(fixture, R.string.vpn0wn_generate_plan)
            fixture.model.state.first { !it.busy && it.plan != null }
            settle()
            assertEquals(Step.PLAN, fixture.model.state.value.step)
            assertTrue(text(fixture.root, fixture.activity.getString(R.string.vpn0wn_plan_advisory)).isShown)
            click(fixture, R.string.vpn0wn_review_apply)
            settle()
            val apply = text(fixture.root, fixture.activity.getString(R.string.vpn0wn_apply_plan_button), clickable = true)
            assertTrue(apply.isEnabled)
            apply.performClick()
            settle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(dialog.isShowing)
            assertTrue(dialog.findViewById<TextView>(android.R.id.message)!!.text.contains("198.51.100.42"))
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            assertFalse(fixture.peer.commands.any { it.contains("./'install.sh'") || it.contains("./'add-peer.sh'") })
            assertEquals(Step.APPLY, fixture.model.state.value.step)
        } finally { fixture.close() }
    }

    @Test fun detachedConfirmationAfterRotationCannotStartInstallButFreshConfirmationCan() = runTest {
        val fixture = fixture()
        try {
            fillAccess(fixture)
            requireNotNull(fixture.model.detect()).join()
            requireNotNull(fixture.model.plan()).join()
            fixture.model.reviewApply()
            settle()
            click(fixture, R.string.vpn0wn_apply_plan_button)
            val oldDialog = ShadowDialog.getLatestDialog() as AlertDialog
            fixture.controller.recreate()
            settle()
            oldDialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            assertFalse(fixture.peer.commands.any { it.contains("./'install.sh'") })
            fixture.peer.statusOutput = """{"ok":true,"interface":"wg0","running":true,"endpoint":"198.51.100.42","listenPort":"51820","cidr":"10.8.0.0/24","dns":"1.1.1.1, 1.0.0.1","peers":[]}"""
            click(fixture, R.string.vpn0wn_apply_plan_button)
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            fixture.model.state.first { !it.busy && it.installation == VpsProvisioningViewModel.Installation.INSTALLED }
            settle()
            assertEquals(1, fixture.peer.commands.count { it.contains("./'install.sh'") })
            assertTrue(text(fixture.root, fixture.activity.getString(R.string.vpn0wn_apply_verified)).isShown)
            assertFalse(fixture.peer.commands.any { it.contains("./'add-peer.sh'") })
        } finally { fixture.close() }
    }

    @Test fun failedDetectSaveCanShowAPlanButApplyExplainsTheMissingCurrentRecord() = runTest {
        val fixture = fixture()
        try {
            fillAccess(fixture)
            click(fixture, R.string.vpn0wn_continue)
            fixture.model.state.first { !it.busy && it.detect != null }
            val original = Vpn0wnApplication.getVpsServerStore().servers.first().single()
            fixture.model.back()
            settle()
            input(fixture.root, fixture.activity.getString(R.string.vpn0wn_host_placeholder)).setText("203.0.113.9")
            input(fixture.root, "SHA256:…").setText("SHA256:" + Base64.getEncoder().withoutPadding()
                .encodeToString(ByteArray(32) { (it + 1).toByte() }))
            click(fixture, R.string.vpn0wn_continue)
            fixture.model.state.first { !it.busy && it.detect != null }
            assertEquals(VpsProvisioningViewModel.Problem.STORAGE_CONFLICT, fixture.model.state.value.problem)
            assertFalse(requireNotNull(fixture.model.state.value.detect).configExists)
            fixture.peer.planRequest = fixture.peer.planRequest.copy(endpoint = "203.0.113.9")
            settle()
            click(fixture, R.string.vpn0wn_generate_plan)
            fixture.model.state.first { !it.busy && it.plan != null }
            settle()
            click(fixture, R.string.vpn0wn_review_apply)
            settle()
            assertTrue(text(fixture.root, fixture.activity.getString(R.string.vpn0wn_apply_current_record_required)).isShown)
            val incorrectReason = fixture.activity.getString(R.string.vpn0wn_plan_existing_config)
            assertFalse(descendants(fixture.root).filterIsInstance<TextView>().any { it.text.toString() == incorrectReason })
            val action = text(fixture.root, fixture.activity.getString(R.string.vpn0wn_apply_unavailable), clickable = true)
            assertFalse(action.isEnabled)
            assertFalse(fixture.model.state.value.pendingInstall)
            assertFalse(fixture.peer.commands.any { it.contains("./'install.sh'") })
            assertEquals(original, Vpn0wnApplication.getVpsServerStore().servers.first().single())
            assertNull(Vpn0wnApplication.getVpsServerStore().pendingInstall(original))
        } finally { fixture.close() }
    }

    @Test fun rotationRetainsOperationAndDraftWithoutSecretSavedState() = runTest {
        val fixture = fixture()
        try {
            fillAccess(fixture)
            val bundle = Bundle()
            fixture.controller.saveInstanceState(bundle)
            val parcel = Parcel.obtain()
            val bytes = try { parcel.writeBundle(bundle); parcel.marshall() } finally { parcel.recycle() }
            for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE))
                assertFalse(bytes.toString(charset).contains(PASSWORD))
            fixture.peer.blockDetect = CompletableDeferred()
            click(fixture, R.string.vpn0wn_continue)
            fixture.peer.detectEntered.await()
            fixture.controller.recreate()
            settle()
            val restored = fixture.activity.supportFragmentManager.fragments.filterIsInstance<VpsProvisioningFragment>().single()
            assertSame(fixture.model, ViewModelProvider(restored)[VpsProvisioningViewModel::class.java])
            assertEquals("198.51.100.42", fixture.model.draft.host)
            assertTrue(fixture.model.state.value.busy)
            assertEquals(1, fixture.peer.connections.size)
            fixture.peer.blockDetect!!.complete(Unit)
            fixture.model.state.first { !it.busy && it.detect != null }
            settle()
            assertEquals(Step.DETECT, fixture.model.state.value.step)
            assertEquals(1, fixture.peer.closes.get())
        } finally { fixture.close() }
    }

    @Test fun permanentFragmentRemovalCancelsDrainsAndCannotPublishLateResult() = runTest {
        val fixture = fixture()
        try {
            fillAccess(fixture)
            val password = fixture.model.draft.password
            fixture.peer.blockDetect = CompletableDeferred()
            click(fixture, R.string.vpn0wn_continue)
            fixture.peer.detectEntered.await()
            fixture.activity.supportFragmentManager.beginTransaction().remove(fixture.fragment).commitNow()
            fixture.model.state.first { !it.busy }
            fixture.peer.blockDetect!!.complete(Unit)
            assertNull(fixture.model.state.value.detect)
            assertTrue(password.all { it == '\u0000' })
            assertTrue((fixture.peer.connections.single().credential as VpsCredential.Password).value.all { it == '\u0000' })
            assertTrue(Vpn0wnApplication.getVpsServerStore().servers.first().isEmpty())
            assertEquals(1, fixture.peer.closes.get())
        } finally { fixture.close() }
    }

    @Test fun recreatedDraftRejectsDetachedEditorCallbacks() = runTest {
        val fixture = fixture()
        try {
            fillAccess(fixture)
            val oldHost = input(fixture.root, fixture.activity.getString(R.string.vpn0wn_host_placeholder))
            fixture.controller.recreate()
            settle()
            oldHost.setText("203.0.113.9")
            assertEquals("198.51.100.42", fixture.model.draft.host)
            assertEquals("198.51.100.42", input(fixture.root, fixture.activity.getString(R.string.vpn0wn_host_placeholder)).text.toString())
            assertTrue(fixture.peer.connections.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun savedAccessHasRealReuseAndConfirmedClearCallers() = runTest {
        val fixture = fixture()
        try {
            fillAccess(fixture)
            click(fixture, R.string.vpn0wn_continue)
            fixture.model.state.first { !it.busy && it.detect != null }
            fixture.model.back()
            fixture.model.back()
            settle()
            clickServer(fixture)
            click(fixture, R.string.vpn0wn_reuse_saved_ssh)
            fixture.model.state.first { !it.busy && it.detect != null && it.usingSavedAccess }
            assertTrue(fixture.model.draft.password.isEmpty())
            fixture.model.back()
            fixture.model.back()
            settle()
            clickServer(fixture)
            val saved = Vpn0wnApplication.getVpsServerStore().servers.first().single()
            click(fixture, R.string.vpn0wn_clear_saved_ssh)
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            settle()
            requireNotNull(Vpn0wnApplication.getVpsServerStore().loadConnection(saved)).let {
                (it.credential as VpsCredential.Password).clear()
            }
            click(fixture, R.string.vpn0wn_clear_saved_ssh)
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            settle()
            fixture.model.state.first { !it.busy && it.savedAccess == VpsProvisioningViewModel.SavedAccess.CLEARED }
            assertNull(Vpn0wnApplication.getVpsServerStore().loadConnection(saved))
            assertEquals(listOf(saved), Vpn0wnApplication.getVpsServerStore().servers.first())
            assertEquals(2, fixture.peer.connections.size)
        } finally { fixture.close() }
    }

    @Test fun failedDetectCorrectionKeepsTheNativeEditorFocusCursorAndInputConnection() = runTest {
        val fixture = fixture()
        try {
            fillAccess(fixture)
            fixture.peer.failure = ProvisioningException("synthetic-authentication-failure")
            click(fixture, R.string.vpn0wn_continue)
            fixture.model.state.first { !it.busy && it.problem != null }
            settle()
            val host = fixture.root.findViewById<EditText>(R.id.vpn0wn_vps_ssh_host)
            assertTrue(host.requestFocus())
            host.setSelection(0, 3)
            val inputConnection = requireNotNull(host.onCreateInputConnection(EditorInfo()))
            assertTrue(inputConnection.commitText("203", 1))
            settle()
            assertSame(host, fixture.root.findViewById<EditText>(R.id.vpn0wn_vps_ssh_host))
            assertTrue(host.isAttachedToWindow)
            assertTrue(host.hasFocus())
            assertEquals(3, host.selectionStart)
            assertEquals(3, host.selectionEnd)
            assertEquals("203.51.100.42", fixture.model.draft.host)
            assertNull(fixture.model.state.value.problem)
            assertNull(fixture.root.findViewById<View>(R.id.vpn0wn_vps_operation_problem))
            host.setSelection(host.length() - 1, host.length())
            assertTrue(inputConnection.commitText("9", 1))
            settle()
            assertEquals("203.51.100.49", host.text.toString())
            assertEquals(host.length(), host.selectionStart)
            assertTrue(host.hasFocus())
        } finally { fixture.close() }
    }

    @Test fun editingSavedSshSourceInvalidatesDetectionWithoutDetachingTheFocusedField() = runTest {
        val fixture = fixture()
        try {
            fillAccess(fixture)
            click(fixture, R.string.vpn0wn_continue)
            fixture.model.state.first { !it.busy && it.detect != null }
            val saved = Vpn0wnApplication.getVpsServerStore().servers.first().single()
            requireNotNull(fixture.model.reuse(saved)).join()
            fixture.model.back()
            settle()
            assertTrue(fixture.model.state.value.usingSavedAccess)
            val host = fixture.root.findViewById<EditText>(R.id.vpn0wn_vps_ssh_host)
            assertTrue(host.requestFocus())
            host.setSelection(host.length() - 1, host.length())
            val inputConnection = requireNotNull(host.onCreateInputConnection(EditorInfo()))
            assertTrue(inputConnection.commitText("9", 1))
            settle()
            assertSame(host, fixture.root.findViewById<EditText>(R.id.vpn0wn_vps_ssh_host))
            assertTrue(host.isAttachedToWindow)
            assertTrue(host.hasFocus())
            assertEquals(host.length(), host.selectionStart)
            assertNull(fixture.model.state.value.detect)
            assertNull(fixture.model.state.value.plan)
            assertFalse(fixture.model.state.value.usingSavedAccess)
            assertNotNull(fixture.root.findViewById<EditText>(R.id.vpn0wn_vps_ssh_private_key))
            assertNull(fixture.model.plan())
            assertEquals(2, fixture.peer.connections.size)
        } finally { fixture.close() }
    }

    @Test fun operationErrorHasPoliteSemanticsAndScrollsIntoViewWithoutTakingFocus() = runTest {
        val fixture = fixture()
        try {
            fillAccess(fixture)
            fixture.peer.failure = ProvisioningException("synthetic-authentication-failure")
            click(fixture, R.string.vpn0wn_continue)
            fixture.model.state.first { !it.busy && it.problem != null }
            runCurrent()
            measure(fixture.root, 320, 640)
            shadowOf(Looper.getMainLooper()).idle()
            val problem = fixture.root.findViewById<TextView>(R.id.vpn0wn_vps_operation_problem)
            val scroll = descendants(fixture.root).filterIsInstance<ScrollView>().single()
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, problem.accessibilityLiveRegion)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, problem.importantForAccessibility)
            assertFalse(problem.hasFocus())
            assertTrue(scroll.scrollY > 0)
            val problemPosition = IntArray(2)
            val viewportPosition = IntArray(2)
            problem.getLocationInWindow(problemPosition)
            scroll.getLocationInWindow(viewportPosition)
            assertTrue(problemPosition[1] >= viewportPosition[1] + scroll.paddingTop)
            assertTrue(problemPosition[1] + problem.height <= viewportPosition[1] + scroll.height - scroll.paddingBottom)
        } finally { fixture.close() }
    }

    @Test fun errorRevealWaitsForSettlementWhenMainCollectsBothFailureEmissions() = runTest {
        val fixture = fixture(immediateMain = true)
        val emissions = mutableListOf<VpsProvisioningViewModel.State>()
        var busyProblem: View? = null
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.model.state.collect {
                emissions.add(it)
                if (it.busy && it.problem != null)
                    busyProblem = fixture.root.findViewById(R.id.vpn0wn_vps_operation_problem)
            }
        }
        try {
            fillAccess(fixture)
            fixture.peer.failure = ProvisioningException("synthetic-authentication-failure")
            fixture.peer.blockDetect = CompletableDeferred()
            click(fixture, R.string.vpn0wn_continue)
            fixture.peer.detectEntered.await()
            fixture.peer.blockDetect!!.complete(Unit)
            fixture.model.state.first { !it.busy && it.problem != null }
            val busyFailure = emissions.indexOfFirst { it.busy && it.problem != null }
            assertTrue("Immediate Main must expose the IO failure before finally settles busy", busyFailure >= 0)
            val settled = emissions.drop(busyFailure + 1).first { !it.busy }
            assertEquals(emissions[busyFailure].problem, settled.problem)
            assertNotNull("The Fragment must render the busy failure before settlement", busyProblem)
            assertFalse(requireNotNull(busyProblem).isAttachedToWindow)
            assertEquals(1, fixture.peer.connections.size)
            measure(fixture.root, 320, 640)
            shadowOf(Looper.getMainLooper()).idle()
            val problem = fixture.root.findViewById<TextView>(R.id.vpn0wn_vps_operation_problem)
            val scroll = descendants(fixture.root).filterIsInstance<ScrollView>().single()
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, problem.accessibilityLiveRegion)
            assertFalse(problem.hasFocus())
            assertTrue("The settled error must be revealed even when the same problem was already seen while busy", scroll.scrollY > 0)
        } finally {
            try { observer.cancelAndJoin() } finally { fixture.close() }
        }
    }

    @Test fun sshAndNetworkInputsRetainNativeVisibleLabelsAndStableIds() = runTest {
        val fixture = fixture()
        try {
            fillAccess(fixture)
            val hostHint = fixture.root.findViewById<EditText>(R.id.vpn0wn_vps_ssh_host).hint.toString()
            assertEquals(fixture.activity.getString(R.string.vpn0wn_host_placeholder), hostHint)
            assertTrue(hostHint.contains("IPv4") && hostHint.contains("IPv6"))
            assertFalse(hostHint.contains("domain", ignoreCase = true))
            assertLabeledInputs(fixture.root)
            click(fixture, R.string.vpn0wn_auth_ssh_key)
            assertNotNull(fixture.root.findViewById<EditText>(R.id.vpn0wn_vps_ssh_private_key))
            assertNotNull(fixture.root.findViewById<EditText>(R.id.vpn0wn_vps_ssh_passphrase))
            assertLabeledInputs(fixture.root)
            click(fixture, R.string.vpn0wn_auth_password)
            fixture.root.findViewById<EditText>(R.id.vpn0wn_vps_ssh_password).setText(PASSWORD)
            click(fixture, R.string.vpn0wn_continue)
            fixture.model.state.first { !it.busy && it.detect != null }
            settle()
            assertNotNull(fixture.root.findViewById<EditText>(R.id.vpn0wn_vps_vpn_endpoint))
            assertNotNull(fixture.root.findViewById<EditText>(R.id.vpn0wn_vps_dns))
            assertLabeledInputs(fixture.root)
        } finally { fixture.close() }
    }

    @Test fun manualStatusKeepsPeersAcrossStoreEmissionsAndRecreation() = runTest {
        val fixture = fixture()
        try {
            fillAccess(fixture)
            click(fixture, R.string.vpn0wn_continue)
            fixture.model.state.first { !it.busy && it.detect != null }
            fixture.model.leave()
            settle()
            clickServer(fixture)
            assertTrue(text(fixture.root, fixture.activity.getString(R.string.vpn0wn_peers_unavailable)).isShown)
            fixture.root.findViewById<View>(R.id.vpn0wn_vps_refresh_status).performClick()
            fixture.model.state.first { !it.busy && it.statusSnapshots.isNotEmpty() }
            settle()
            assertTrue(text(fixture.root, "phone").isShown)
            val saved = Vpn0wnApplication.getVpsServerStore().servers.first().single()
            Vpn0wnApplication.getVpsServerStore().upsert(saved.copy(id = "unrelated", name = "unrelated"))
            settle()
            assertTrue(text(fixture.root, "phone").isShown)
            val snapshot = fixture.model.state.value.statusSnapshots.getValue(saved.id)
            fixture.controller.recreate()
            settle()
            clickServer(fixture)
            assertTrue(text(fixture.root, "phone").isShown)
            assertSame(snapshot, fixture.model.state.value.statusSnapshots.getValue(saved.id))
            assertEquals(2, fixture.peer.connections.size)
            assertEquals(2, fixture.peer.closes.get())
        } finally { fixture.close() }
    }

    @Test fun busyStatusCanCancelAndARealPeerCannotExportAPlaceholderProfile() = runTest {
        val fixture = fixture()
        try {
            fillAccess(fixture)
            click(fixture, R.string.vpn0wn_continue)
            fixture.model.state.first { !it.busy && it.detect != null }
            fixture.model.leave()
            settle()
            clickServer(fixture)
            fixture.peer.blockStatus = CompletableDeferred()
            fixture.root.findViewById<View>(R.id.vpn0wn_vps_refresh_status).performClick()
            fixture.peer.statusEntered.await()
            settle()
            assertTrue(text(fixture.root, fixture.activity.getString(R.string.vpn0wn_cancel_check)).isShown)
            fixture.root.findViewById<View>(R.id.vpn0wn_vps_refresh_status).performClick()
            fixture.model.state.first { !it.busy }
            fixture.peer.blockStatus = null
            settle()
            fixture.root.findViewById<View>(R.id.vpn0wn_vps_refresh_status).performClick()
            fixture.model.state.first { !it.busy && it.statusSnapshots.isNotEmpty() }
            settle()
            clickableAncestor(text(fixture.root, "phone")).performClick()
            settle()
            assertNotNull(text(fixture.root, "synthetic-peer-public-key"))
            click(fixture, R.string.vpn0wn_action_show_qr)
            assertNotNull(text(fixture.activity.window.decorView, fixture.activity.getString(R.string.vpn0wn_profile_unavailable)))
            assertFalse(fixture.peer.commands.any { it.contains("./'add-peer.sh'") || it.contains("./'backup.sh'") })
        } finally { fixture.close() }
    }

    // A failed setup must drain Android, ViewModel and DataStore owners before resetting Main.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun TestScope.fixture(immediateMain: Boolean = false): Fixture {
        val scheduled = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(if (immediateMain) ThreadBoundMainDispatcher(Thread.currentThread(), scheduled) else scheduled)
        val environment = TunnelTestEnvironment(folder.newFolder(), backgroundScope)
        val controller = Robolectric.buildActivity(HostActivity::class.java)
        val peer = VpsPlanningTestPeer()
        var modelJob: Job? = null
        try {
            Vpn0wnApplication.getVpsServerStore().servers.first()
            controller.get().setTheme(R.style.AppTheme)
            controller.setup()
            val model = VpsProvisioningViewModel(controller.get().application, Vpn0wnApplication.getVpsServerStore(), peer)
            val createdModelJob = requireNotNull(model.viewModelScope.coroutineContext[Job])
            modelJob = createdModelJob
            controller.get().supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentPreCreated(fm: FragmentManager, fragment: Fragment, savedInstanceState: Bundle?) {
                    if (fragment is VpsProvisioningFragment) ViewModelProvider(fragment, object : ViewModelProvider.Factory {
                        override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(model)!!
                    })[VpsProvisioningViewModel::class.java]
                }
            }, false)
            val fragment = VpsProvisioningFragment()
            controller.get().supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
            settle()
            return Fixture(environment, controller, fragment, peer, model, createdModelJob)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                peer.blockDetect?.complete(Unit)
                peer.blockStatus?.complete(Unit)
                peer.blockInstall?.complete(Unit)
                try { controller.pause().stop().destroy() } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
                try { modelJob?.cancelAndJoin() } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
                try { environment.close() } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
            }
            throw failure
        }
    }

    private fun TestScope.fillAccess(fixture: Fixture) {
        val add = text(fixture.root, fixture.activity.getString(R.string.vpn0wn_add_server).removePrefix("+ ").trim())
        clickableAncestor(add).performClick()
        settle()
        input(fixture.root, fixture.activity.getString(R.string.vpn0wn_host_placeholder)).setText("198.51.100.42")
        input(fixture.root, "SHA256:…").setText("SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() }))
        click(fixture, R.string.vpn0wn_auth_password)
        input(fixture.root, fixture.activity.getString(R.string.vpn0wn_password_placeholder)).setText(PASSWORD)
    }

    private fun TestScope.clickServer(fixture: Fixture) {
        clickableAncestor(text(fixture.root, "198.51.100.42")).performClick()
        settle()
    }

    private fun TestScope.click(fixture: Fixture, resource: Int) {
        clickableAncestor(text(fixture.root, fixture.activity.getString(resource))).performClick()
        settle()
    }

    private fun TestScope.settle() {
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun input(root: View, hint: String): EditText = descendants(root).filterIsInstance<EditText>().single { it.hint.toString() == hint }
    private fun assertLabeledInputs(root: View) {
        val labels = descendants(root).filterIsInstance<TextView>().filter { it !is EditText }.toList()
        val inputs = descendants(root).filterIsInstance<EditText>().toList()
        assertFalse(inputs.isEmpty())
        assertEquals(inputs.size, inputs.map { it.id }.distinct().size)
        inputs.forEach { input ->
            assertNotEquals(View.NO_ID, input.id)
            assertEquals(1, labels.count { it.labelFor == input.id && it.text.isNotBlank() })
        }
    }
    private fun measure(view: View, width: Int, height: Int) {
        val density = view.resources.displayMetrics.density
        view.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
    private fun text(root: View, value: String, clickable: Boolean = false): TextView = descendants(root).filterIsInstance<TextView>().first {
        it.text.toString() == value && (!clickable || it.isClickable)
    }
    private fun clickableAncestor(view: View): View = if (view.isClickable) view else clickableAncestor(view.parent as View)
    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }

    private class Fixture(
        val environment: TunnelTestEnvironment,
        val controller: ActivityController<HostActivity>,
        val fragment: VpsProvisioningFragment,
        val peer: VpsPlanningTestPeer,
        val model: VpsProvisioningViewModel,
        private val modelJob: Job,
    ) {
        val activity get() = controller.get()
        val root get() = activity.supportFragmentManager.fragments.filterIsInstance<VpsProvisioningFragment>().single().requireView()
        suspend fun close() = withContext(NonCancellable) {
            peer.blockDetect?.complete(Unit)
            peer.blockStatus?.complete(Unit)
            peer.blockInstall?.complete(Unit)
            try { controller.pause().stop().destroy() } finally {
                try { modelJob.cancelAndJoin() } finally { environment.close() }
            }
        }
    }

    class HostActivity : AppCompatActivity()

    // Match Android thread affinity; an always-unconfined Main conflates the nested error emissions.
    private class ThreadBoundMainDispatcher(
        private val mainThread: Thread,
        private val scheduled: CoroutineDispatcher,
    ) : MainCoroutineDispatcher() {
        override val immediate get() = this
        override fun isDispatchNeeded(context: CoroutineContext) = Thread.currentThread() !== mainThread
        override fun dispatch(context: CoroutineContext, block: Runnable) = scheduled.dispatch(context, block)
    }

    private companion object {
        const val PASSWORD = "synthetic-rotation-password"
    }
}
