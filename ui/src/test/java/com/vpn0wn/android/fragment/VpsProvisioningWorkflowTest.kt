/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.fragment

import android.app.Application
import android.content.DialogInterface
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Button
import android.widget.TextView
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
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
import com.vpn0wn.android.provisioning.ProvisioningRecovery
import com.vpn0wn.android.provisioning.StoredVpsServer
import com.vpn0wn.android.provisioning.VpsCredentialFileTest
import com.vpn0wn.android.provisioning.VpsPlanningTestPeer
import com.vpn0wn.android.provisioning.VpsProfileFixture
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.Step
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.UserCreation
import com.vpn0wn.android.provisioning.VpsUserProfileFile
import com.vpn0wn.android.provisioning.VpsUserProfileFileTest
import com.vpn0wn.android.provisioning.VpsUserProfileStore
import java.io.File
import java.io.IOException
import java.util.Base64
import javax.crypto.KeyGenerator
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
import org.json.JSONArray
import org.json.JSONObject
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

    @Test
    @Config(sdk = [24, 28])
    fun technicalLabelsUseBundledRegularFontAcrossSupportedApis() = runTest {
        val fixture = fixture()
        try {
            val title = text(fixture.root, fixture.activity.getString(R.string.vpn0wn_servers_title).lowercase(java.util.Locale.ROOT))
            assertFalse("Existing title omits extra font padding", title.includeFontPadding)
            val add = text(fixture.root, fixture.activity.getString(R.string.vpn0wn_add_server).removePrefix("+ ").trim())
            assertTrue(clickableAncestor(add).performClick())
            settle()
            val label = text(fixture.root, fixture.activity.getString(R.string.vpn0wn_label_host).uppercase(java.util.Locale.ROOT))
            assertEquals("Bundled regular face", ResourcesCompat.getFont(fixture.activity, R.font.jetbrains_mono_regular), label.typeface)
            assertEquals("Shared mono features", "ss01, zero, tnum", label.fontFeatureSettings)
            assertFalse("Existing label omits extra font padding", label.includeFontPadding)
            assertTrue(fixture.peer.connections.isEmpty())
        } finally {
            fixture.close()
        }
    }

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
            assertEquals(fixture.activity.getString(R.string.vpn0wn_apply_install_button),
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).text.toString())
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

    @Test fun reconciliationShowsObservedNetworkRatherThanAnEditedAdvisoryPlan() = runTest {
        val fixture = fixture()
        try {
            fillAccess(fixture)
            requireNotNull(fixture.model.detect()).join()
            requireNotNull(fixture.model.plan()).join()
            fixture.model.reviewApply()
            fixture.peer.installFailure = ProvisioningException("lost reply")
            val confirmation = requireNotNull(fixture.model.requestInstallConfirmation())
            requireNotNull(fixture.model.confirmInstall(confirmation)).join()
            fixture.peer.installFailure = null
            val next = fixture.peer.planRequest.copy(endpoint = "203.0.113.9", port = 51821, cidr = "10.9.0.0/24", dns = "9.9.9.9")
            fixture.model.edit(fixture.model.draft.copy(endpoint = next.endpoint, port = next.port.toString(), cidr = next.cidr, dns = next.dns))
            fixture.peer.planRequest = next
            requireNotNull(fixture.model.plan()).join()
            fixture.model.reviewApply()
            settle()
            assertTrue(text(fixture.root, next.port.toString()).isShown)
            requireNotNull(fixture.model.reconcileInstall()).join()
            settle()
            assertTrue(text(fixture.root, fixture.activity.getString(R.string.vpn0wn_apply_observed)).isShown)
            for (actual in listOf("198.51.100.42", "51820", "10.8.0.0/24", "1.1.1.1, 1.0.0.1"))
                assertTrue(text(fixture.root, actual).isShown)
            val shownValues = descendants(fixture.root).filterIsInstance<TextView>().map { it.text.toString() }.toSet()
            for (advisory in listOf(next.endpoint, next.port.toString(), next.cidr, next.dns))
                assertFalse("Advisory value must not appear as observed: $advisory", advisory in shownValues)
            assertEquals(VpsProvisioningViewModel.Installation.OBSERVED, fixture.model.state.value.installation)
            assertEquals("51820", Vpn0wnApplication.getVpsServerStore().servers.first().single().listenPort)
            assertFalse(fixture.model.state.value.pendingInstall)
            assertEquals(1, fixture.peer.commands.count { it.contains("./'install.sh'") })
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

    @Config(sdk = [24, 28])
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
            val refresh = fixture.root.findViewById<View>(R.id.vpn0wn_vps_refresh_status)
            assertButtonAction(refresh)
            assertTrue(refresh.requestFocus())
            assertTrue(refresh.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
            assertTrue(refresh.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)))
            fixture.peer.statusEntered.await()
            settle()
            assertTrue(text(fixture.root, fixture.activity.getString(R.string.vpn0wn_cancel_check)).isShown)
            val cancel = fixture.root.findViewById<View>(R.id.vpn0wn_vps_refresh_status)
            assertButtonAction(cancel)
            cancel.performClick()
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

    @Test fun createNameValidationAndExactIdentityReviewDoNotMutateTheServer() = runTest {
        val fixture = fixture()
        try {
            prepareUserCreation(fixture)
            fixture.root.findViewById<View>(R.id.vpn0wn_vps_add_user).performClick()
            settle()
            val form = ShadowDialog.getLatestDialog() as AlertDialog
            val input = form.findViewById<EditText>(R.id.vpn0wn_vps_peer_name)!!
            assertFalse(input.isSaveEnabled)
            assertTrue(input.minimumHeight >= (48 * input.resources.displayMetrics.density).toInt())
            assertEquals(1, descendants(form.window!!.decorView).filterIsInstance<TextView>().count { it.labelFor == input.id })
            input.setText("bad name")
            form.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            assertTrue(form.isShowing)
            assertEquals(fixture.activity.getString(R.string.vpn0wn_user_name_invalid), input.error.toString())
            assertNull(fixture.model.state.value.addConfirmation)
            input.setText("phone-new")
            form.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            fixture.model.state.first { !it.busy && it.addConfirmation != null }
            settle()
            val confirmation = requireNotNull(fixture.model.state.value.addConfirmation)
            val review = ShadowDialog.getLatestDialog() as AlertDialog
            assertNotSame(form, review)
            assertTrue(review.isShowing)
            val message = review.findViewById<TextView>(android.R.id.message)!!.text.toString()
            for (value in listOf("phone-new", "root@198.51.100.42:22", confirmation.fingerprint,
                confirmation.serverPublicKey, "0.0.0.0/0", requireNotNull(confirmation.request.dns))) assertTrue(message.contains(value))
            assertFalse(fixture.peer.commands.any { it.contains("./'add-peer.sh'") })
            review.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            settle()
            assertNull(fixture.model.state.value.addConfirmation)
            assertFalse(fixture.peer.commands.any { it.contains("./'add-peer.sh'") })
        } finally { fixture.close() }
    }

    @Test fun rebuiltNameFormAndStoppedReviewCannotAuthorizeCreation() = runTest {
        val fixture = fixture()
        try {
            prepareUserCreation(fixture)
            fixture.root.findViewById<View>(R.id.vpn0wn_vps_add_user).performClick()
            settle()
            val oldForm = ShadowDialog.getLatestDialog() as AlertDialog
            oldForm.findViewById<EditText>(R.id.vpn0wn_vps_peer_name)!!.setText("stale-name")
            val saved = Vpn0wnApplication.getVpsServerStore().servers.first().single()
            Vpn0wnApplication.getVpsServerStore().upsert(saved.copy(id = "unrelated", name = "unrelated"))
            settle()
            assertFalse(oldForm.isShowing)
            oldForm.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            settle()
            assertNull(fixture.model.state.value.addConfirmation)
            fixture.root.findViewById<View>(R.id.vpn0wn_vps_add_user).performClick()
            settle()
            val form = ShadowDialog.getLatestDialog() as AlertDialog
            form.findViewById<EditText>(R.id.vpn0wn_vps_peer_name)!!.setText("fresh-name")
            form.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            fixture.model.state.first { !it.busy && it.addConfirmation != null }
            settle()
            val oldReview = ShadowDialog.getLatestDialog() as AlertDialog
            fixture.controller.pause().stop()
            assertFalse(oldReview.isShowing)
            oldReview.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            settle()
            assertNull(fixture.model.state.value.addConfirmation)
            assertFalse(fixture.peer.commands.any { it.contains("./'add-peer.sh'") })
            fixture.controller.start().resume()
            settle()
        } finally { fixture.close() }
    }

    @Test fun legacyStatusWithoutServerIdentityKeepsCreationDisabled() = runTest {
        val fixture = fixture()
        try {
            prepareUserCreation(fixture, confirmedIdentity = false)
            assertFalse(fixture.root.findViewById<View>(R.id.vpn0wn_vps_add_user).isEnabled)
            assertTrue(text(fixture.root, fixture.activity.getString(R.string.vpn0wn_user_identity_required)).isShown)
            fixture.root.findViewById<View>(R.id.vpn0wn_vps_add_user).performClick()
            settle()
            assertNull(fixture.model.state.value.addConfirmation)
            assertFalse(fixture.peer.commands.any { it.contains("./'add-peer.sh'") })
        } finally { fixture.close() }
    }

    @Test
    @Config(shadows = [VpsCredentialFileTest.NativeFiles::class])
    fun otherServerReviewShowsReceiptRetentionFailureOnTheSelectedServer() = runTest {
        VpsUserProfileFileTest.resetNativeFiles()
        var failSync = true
        val profiles = profileStore { if (failSync) throw IOException("synthetic profile sync failure") }
        val fixture = fixture(immediateMain = true, profiles = profiles)
        try {
            val (first, second) = prepareUnsavedUserAndSelectOtherServer(fixture)
            val store = Vpn0wnApplication.getVpsServerStore()
            val pending = requireNotNull(store.pendingAdd(first))
            val busyOwners = mutableListOf<String?>()
            val collecting = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                fixture.model.state.collect { if (it.busy) busyOwners.add(it.serverId) }
            }
            requestUserReview(fixture, "second-phone")
            fixture.model.state.first { !it.busy && it.problem == VpsProvisioningViewModel.Problem.STORAGE_FAILED }
            settle()
            collecting.cancelAndJoin()
            assertEquals(second.id, busyOwners.first())
            assertEquals(second.id, fixture.model.state.value.serverId)
            assertEquals(fixture.activity.getString(R.string.vpn0wn_saved_ssh_failed),
                fixture.root.findViewById<TextView>(R.id.vpn0wn_vps_operation_problem).text.toString())
            assertNull(fixture.model.state.value.addConfirmation)
            assertNull(fixture.root.findViewById<View>(R.id.vpn0wn_vps_user_creation_status))
            assertNull(fixture.root.findViewById<View>(R.id.vpn0wn_vps_reconcile_user))
            assertEquals(pending, store.pendingAdd(first))
            assertNull(store.pendingAdd(second))
            assertNull(profiles.load(first, PROFILE_SERVER_KEY, PROFILE_CLIENT_KEY))
            failSync = false
            fixture.peer.statusOutput = profileStatus(first, withPeer = true)
            requireNotNull(fixture.model.reconcileAddUser(first)).join()
            assertEquals(UserCreation.CREATED, fixture.model.state.value.creation)
            assertNotNull(profiles.load(first, PROFILE_SERVER_KEY, PROFILE_CLIENT_KEY))
            assertNull(profiles.load(second, PROFILE_SERVER_KEY, PROFILE_CLIENT_KEY))
            assertEquals(1, fixture.peer.commands.count { it.contains("./'add-peer.sh'") })
        } finally { fixture.close() }
    }

    @Test
    @Config(shadows = [VpsCredentialFileTest.NativeFiles::class])
    fun otherServerFirstReviewSurvivesRetainingThePreviousServersReceipt() = runTest {
        VpsUserProfileFileTest.resetNativeFiles()
        var failSync = true
        val profiles = profileStore { if (failSync) throw IOException("synthetic profile sync failure") }
        val fixture = fixture(immediateMain = true, profiles = profiles)
        try {
            val (first, second) = prepareUnsavedUserAndSelectOtherServer(fixture)
            failSync = false
            requestUserReview(fixture, "second-phone")
            fixture.model.state.first { !it.busy && it.addConfirmation != null }
            settle()
            val confirmation = requireNotNull(fixture.model.state.value.addConfirmation)
            val review = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue("First Review opens confirmation after the marker-only fleet write", review.isShowing)
            assertEquals(second, confirmation.server)
            assertEquals("second-phone", confirmation.request.name)
            val message = review.findViewById<TextView>(android.R.id.message)!!.text.toString()
            assertTrue(message.contains(second.host))
            assertTrue(message.contains("second-phone"))
            assertEquals(UserCreation.NONE, fixture.model.state.value.creation)
            assertNull(fixture.root.findViewById<View>(R.id.vpn0wn_vps_user_creation_status))
            assertEquals(VpsProfileFixture.profile().payload(),
                requireNotNull(profiles.load(first, PROFILE_SERVER_KEY, PROFILE_CLIENT_KEY)).payload())
            assertNull(profiles.load(second, PROFILE_SERVER_KEY, PROFILE_CLIENT_KEY))
            val store = Vpn0wnApplication.getVpsServerStore()
            assertEquals(PROFILE_CLIENT_KEY, requireNotNull(store.pendingAdd(first)).clientPublicKey)
            assertNull(store.pendingAdd(second))
            assertEquals(1, fixture.peer.commands.count { it.contains("./'add-peer.sh'") })
            review.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            settle()
            assertNull(fixture.model.state.value.addConfirmation)
            assertEquals(1, fixture.peer.commands.count { it.contains("./'add-peer.sh'") })
        } finally { fixture.close() }
    }

    @Test
    @Config(shadows = [VpsCredentialFileTest.NativeFiles::class])
    fun cleanupGuidanceStaysWithItsSshServerAcrossOtherServerRefreshAndReview() = runTest {
        VpsUserProfileFileTest.resetNativeFiles()
        val fixture = fixture(immediateMain = true, profiles = profileStore {})
        try {
            val (first, second) = prepareProfileServerPair(fixture)
            openServer(fixture, first)
            requestUserReview(fixture, "phone")
            fixture.model.state.first { !it.busy && it.addConfirmation != null }
            settle()
            fixture.peer.addOutput = profileReceipt(cleanup = true)
            fixture.peer.afterAdd = { fixture.peer.statusOutput = profileStatus(first, withPeer = true) }
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            fixture.model.state.first { !it.busy && it.creation == UserCreation.CREATED }
            settle()
            assertGuidance(fixture, R.string.vpn0wn_apply_cleanup, present = true)
            backToFleet(fixture)
            openServer(fixture, second)
            fixture.peer.statusOutput = profileStatus(second)
            fixture.root.findViewById<View>(R.id.vpn0wn_vps_refresh_status).performClick()
            fixture.model.state.first { !it.busy && it.serverId == second.id }
            settle()
            assertGuidance(fixture, R.string.vpn0wn_apply_cleanup, present = false)
            requestUserReview(fixture, "second-phone")
            fixture.model.state.first { !it.busy && it.addConfirmation != null }
            settle()
            assertGuidance(fixture, R.string.vpn0wn_apply_cleanup, present = false)
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            settle()
            backToFleet(fixture)
            openServer(fixture, first)
            assertGuidance(fixture, R.string.vpn0wn_apply_cleanup, present = true)
            val store = Vpn0wnApplication.getVpsServerStore()
            val current = store.servers.first().single { it.id == first.id }
            store.upsert(current.copy(provider = "Updated provider"))
            settle()
            assertGuidance(fixture, R.string.vpn0wn_apply_cleanup, present = true)
            store.clearCredential(first.id)
            store.upsert(current.copy(host = "203.0.113.12"))
            settle()
            assertGuidance(fixture, R.string.vpn0wn_apply_cleanup, present = false)
            assertEquals(1, fixture.peer.commands.count { it.contains("./'add-peer.sh'") })
        } finally { fixture.close() }
    }

    @Test fun installationRecoveryGuidanceDoesNotMoveToAnotherSavedServer() = runTest {
        for (recovery in listOf(ProvisioningRecovery.RESTORED, ProvisioningRecovery.INCOMPLETE)) {
            val fixture = fixture()
            try {
                failInstallWithRecovery(fixture, recovery)
                val store = Vpn0wnApplication.getVpsServerStore()
                val first = store.servers.first().single()
                val second = first.copy(id = "second", name = "Second server", host = "203.0.113.11", endpoint = "203.0.113.11",
                    listenPort = "51820", cidr = "10.8.0.0/24", dns = "1.1.1.1")
                store.upsert(second)
                store.saveCredential(second, PROFILE_PIN, VpsCredential.Password(PASSWORD.toCharArray()))
                fixture.model.leave()
                settle()
                openServer(fixture, first)
                val message = if (recovery == ProvisioningRecovery.RESTORED) R.string.vpn0wn_recovery_restored else R.string.vpn0wn_recovery_incomplete
                assertGuidance(fixture, message, present = true)
                backToFleet(fixture)
                openServer(fixture, second)
                fixture.peer.statusOutput = profileStatus(second)
                fixture.root.findViewById<View>(R.id.vpn0wn_vps_refresh_status).performClick()
                fixture.model.state.first { !it.busy && it.serverId == second.id }
                settle()
                assertGuidance(fixture, message, present = false)
                assertEquals(recovery, fixture.model.state.value.recovery)
                assertEquals(first, fixture.model.state.value.recoveryOwner)
                assertNotNull(store.pendingInstall(first))
                backToFleet(fixture)
                openServer(fixture, first)
                assertGuidance(fixture, message, present = true)
                store.upsert(first.copy(provider = "Updated provider"))
                settle()
                assertGuidance(fixture, message, present = true)
                store.clearCredential(first.id)
                store.upsert(first.copy(sshPort = 2222))
                settle()
                assertGuidance(fixture, message, present = false)
                assertEquals(1, fixture.peer.commands.count { it.contains("./'install.sh'") })
            } finally { fixture.close() }
        }
    }

    private suspend fun TestScope.failInstallWithRecovery(fixture: Fixture, recovery: ProvisioningRecovery) {
        fillAccess(fixture)
        click(fixture, R.string.vpn0wn_continue)
        fixture.model.state.first { !it.busy && it.detect != null }
        requireNotNull(fixture.model.plan()).join()
        fixture.model.reviewApply()
        settle()
        fixture.peer.installExitCode = 74
        fixture.peer.installOutput = JSONObject().put("ok", false).put("error", "apply_failed").put("message", "synthetic")
            .put("recovery", recovery.name.lowercase(java.util.Locale.ROOT)).toString()
        click(fixture, R.string.vpn0wn_apply_plan_button)
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        fixture.model.state.first { !it.busy && it.recovery == recovery }
        settle()
    }

    private fun assertGuidance(fixture: Fixture, resource: Int, present: Boolean) {
        assertEquals(present, descendants(fixture.root).filterIsInstance<TextView>().any {
            it.text.toString() == fixture.activity.getString(resource)
        })
    }

    private fun TestScope.openServer(fixture: Fixture, server: StoredVpsServer) {
        clickableAncestor(text(fixture.root, server.name)).performClick()
        settle()
    }

    private fun TestScope.backToFleet(fixture: Fixture) {
        fixture.activity.onBackPressedDispatcher.onBackPressed()
        settle()
    }

    private fun profileStore(beforeSync: () -> Unit): VpsUserProfileStore {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return VpsUserProfileStore(VpsUserProfileFile(File(folder.newFolder(), "profiles"), { key }, sync = {
            beforeSync()
            it.fd.sync()
        }))
    }

    private suspend fun TestScope.prepareUnsavedUserAndSelectOtherServer(fixture: Fixture): Pair<StoredVpsServer, StoredVpsServer> {
        val (first, second) = prepareProfileServerPair(fixture)
        openServer(fixture, first)
        requestUserReview(fixture, "phone")
        fixture.model.state.first { !it.busy && it.addConfirmation != null }
        settle()
        fixture.peer.addOutput = profileReceipt()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        fixture.model.state.first { !it.busy && it.creation == UserCreation.UNSAVED }
        settle()
        backToFleet(fixture)
        openServer(fixture, second)
        assertNull(fixture.root.findViewById<View>(R.id.vpn0wn_vps_user_creation_status))
        return first to second
    }

    private suspend fun TestScope.prepareProfileServerPair(fixture: Fixture): Pair<StoredVpsServer, StoredVpsServer> {
        val store = Vpn0wnApplication.getVpsServerStore()
        val first = VpsProfileFixture.server
        val second = first.copy(id = "server-2", name = "Second server", host = "203.0.113.11", endpoint = "203.0.113.11")
        for (server in listOf(first, second)) {
            store.upsert(server)
            store.saveCredential(server, PROFILE_PIN, VpsCredential.Password(PASSWORD.toCharArray()))
            fixture.peer.statusOutput = profileStatus(server)
            requireNotNull(fixture.model.syncStatus(server)).join()
        }
        settle()
        val servers = store.servers.first()
        return servers.single { it.id == first.id } to servers.single { it.id == second.id }
    }

    private fun profileReceipt(cleanup: Boolean = false): String = VpsProfileFixture.result().let {
        JSONObject().put("ok", true).put("name", it.name).put("address", it.address)
            .put("publicKey", it.publicKey).put("config", it.config)
            .put("cleanupPath", if (cleanup) "/tmp/vpn0wn-provisioning.test01" else "").toString()
    }

    private fun TestScope.requestUserReview(fixture: Fixture, name: String) {
        fixture.root.findViewById<View>(R.id.vpn0wn_vps_add_user).performClick()
        settle()
        val form = ShadowDialog.getLatestDialog() as AlertDialog
        form.findViewById<EditText>(R.id.vpn0wn_vps_peer_name)!!.setText(name)
        form.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
    }

    private fun profileStatus(server: StoredVpsServer, withPeer: Boolean = false): String = JSONObject()
        .put("ok", true).put("interface", "wg0").put("running", true)
        .put("endpoint", server.endpoint).put("listenPort", server.listenPort).put("cidr", server.cidr)
        .put("dns", server.dns).put("serverPublicKey", PROFILE_SERVER_KEY)
        .put("peers", JSONArray().apply {
            if (withPeer) put(JSONObject().put("name", "phone").put("publicKey", PROFILE_CLIENT_KEY).put("allowedIps", "10.8.0.2/32")
                .put("latestHandshake", JSONObject.NULL).put("rxBytes", JSONObject.NULL).put("txBytes", JSONObject.NULL))
        }).toString()

    private suspend fun TestScope.prepareUserCreation(fixture: Fixture, confirmedIdentity: Boolean = true) {
        fillAccess(fixture)
        click(fixture, R.string.vpn0wn_continue)
        fixture.model.state.first { !it.busy && it.detect != null }
        fixture.model.leave()
        settle()
        clickServer(fixture)
        if (confirmedIdentity) fixture.peer.statusOutput = requireNotNull(javaClass.getResourceAsStream("/provisioning/status-running.json"))
            .bufferedReader().use { it.readText() }
        fixture.root.findViewById<View>(R.id.vpn0wn_vps_refresh_status).performClick()
        fixture.model.state.first { !it.busy && it.statusSnapshots.isNotEmpty() }
        settle()
    }

    private fun assertButtonAction(view: View) {
        assertTrue(view.isFocusable)
        assertTrue(view.background != null && view.background.isStateful)
        assertNull(view.foreground)
        val node = view.createAccessibilityNodeInfo()
        try {
            assertEquals(Button::class.java.name, node.className.toString())
            assertTrue(node.isClickable)
            assertTrue(node.isEnabled)
        } finally {
            node.recycle()
        }
    }

    // A failed setup must drain Android, ViewModel and DataStore owners before resetting Main.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun TestScope.fixture(immediateMain: Boolean = false, profiles: VpsUserProfileStore? = null): Fixture {
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
            val model = VpsProvisioningViewModel(controller.get().application, Vpn0wnApplication.getVpsServerStore(), peer,
                profiles = profiles ?: VpsUserProfileStore(controller.get().application))
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
        val PROFILE_SERVER_KEY = VpsProfileFixture.serverKey.publicKey.toBase64()
        val PROFILE_CLIENT_KEY = VpsProfileFixture.client.publicKey.toBase64()
        val PROFILE_PIN = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    }
}
