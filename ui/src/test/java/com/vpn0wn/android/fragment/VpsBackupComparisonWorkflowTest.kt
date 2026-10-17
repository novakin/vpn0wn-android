/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.fragment

import android.app.Activity
import android.app.Application
import android.content.DialogInterface
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.vpn0wn.android.Application as Vpn0wnApplication
import com.vpn0wn.android.R
import com.vpn0wn.android.activity.AppDataTransferSettingsTest
import com.vpn0wn.android.provisioning.ProvisioningJson
import com.vpn0wn.android.provisioning.StoredVpsServer
import com.vpn0wn.android.provisioning.VpsBackupArchive
import com.vpn0wn.android.provisioning.VpsBackupExportFixture
import com.vpn0wn.android.provisioning.VpsBackupInputProvider
import com.vpn0wn.android.provisioning.VpsBackupResult
import com.vpn0wn.android.provisioning.VpsCredential
import com.vpn0wn.android.provisioning.VpsCredentialFileTest
import com.vpn0wn.android.provisioning.VpsProfileFixture
import com.vpn0wn.android.provisioning.VpsRevokedBackup
import com.vpn0wn.android.provisioning.VpsUserProfileFileTest
import com.vpn0wn.android.util.BiometricAuthenticator
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28],
    shadows = [VpsCredentialFileTest.NativeFiles::class, AppDataTransferSettingsTest.Authentication::class])
@OptIn(ExperimentalCoroutinesApi::class)
class VpsBackupComparisonWorkflowTest {
    @get:Rule val folder = TemporaryFolder()

    @Before fun resetAuthentication() {
        VpsUserProfileFileTest.resetNativeFiles()
        AppDataTransferSettingsTest.Authentication.calls = 0
        AppDataTransferSettingsTest.Authentication.autoCancel = false
        AppDataTransferSettingsTest.Authentication.expectedTitleRes = R.string.vpn0wn_backup_import_authenticate
        AppDataTransferSettingsTest.Authentication.callbacks.clear()
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun savedServerConsumerUsesPickerAuthenticationAndOneReadForCompleteSafeComparison() = runTest {
        val fixture = fixture()
        try {
            val selected = openBackupComparisonServer(fixture)
            val backup = VpsBackupExportFixture.snapshot.copy(revokedCount = 2, revoked = listOf(
                VpsRevokedBackup("old.conf.ABC123", "synthetic private old\n"),
                VpsRevokedBackup("same.conf", "synthetic private original\n"),
            ))
            val observed = backup.copy(capturedAtUtc = "2026-10-18T12:00:00Z", config = backup.config + "# note\n",
                revoked = listOf(VpsRevokedBackup("same.conf", "synthetic private different\n"),
                    VpsRevokedBackup("current.conf.DEF456", "synthetic private current\n")))
            val provider = input(fixture, backup)
            fixture.peer.backupOutput = ProvisioningJson.encodeBackup(observed).toString(Charsets.UTF_8)
            val summary = unlock(fixture)
            assertEquals(fixture.activity.getString(R.string.vpn0wn_backup_compare_action),
                summary.getButton(DialogInterface.BUTTON_POSITIVE).text.toString())
            assertEquals(1, AppDataTransferSettingsTest.Authentication.calls)
            assertTrue(fixture.peer.commands.isEmpty())
            summary.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            finish(fixture)
            val result = requireNotNull(dialog(fixture))
            assertNotNull(result.findViewById<View>(R.id.vpn0wn_backup_comparison_result))
            val visible = text(result)
            for (value in listOf(selected.name, backup.capturedAtUtc, observed.capturedAtUtc, "old.conf.ABC123",
                "current.conf.DEF456", "same.conf", fixture.activity.getString(R.string.vpn0wn_backup_comparison_different),
                fixture.activity.getString(R.string.vpn0wn_backup_comparison_note))) assertTrue(visible.contains(value))
            assertFalse(visible.contains("synthetic private"))
            assertFalse(visible.contains(VpsProfileFixture.serverKey.privateKey.toBase64()))
            assertEquals(1, backupCalls(fixture))
            assertFalse(fixture.peer.commands.any { it.contains("sync-status.sh") || it.contains("restore.sh") })
            assertEquals(listOf(selected), Vpn0wnApplication.getVpsServerStore().servers.first())
            assertEquals(0, provider.mutations)
            assertFalse(fixture.model.canRestore)
            assertNull(fixture.model.state.value.restore)
            result.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            assertNull(dialog(fixture))
            assertNull(fixture.model.state.value.backupComparison)
            assertNull(fixture.model.state.value.backupSummary)
            summary.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            assertFalse(fixture.model.state.value.busy)
            assertEquals(1, backupCalls(fixture))
        } finally { fixture.close() }
    }

    @Test fun unlockCancelAndDismissRejectStalePositiveCallbacksWithoutALooperDrain() = runTest {
        val fixture = fixture()
        try {
            openBackupComparisonServer(fixture)
            val provider = input(fixture)
            for (cancel in listOf(true, false)) {
                val form = openPassword(fixture)
                form.findViewById<EditText>(R.id.vpn0wn_backup_import_password)!!.setText(VpsBackupExportFixture.PASSWORD)
                if (cancel) form.getButton(DialogInterface.BUTTON_NEGATIVE).performClick() else form.dismiss()
                form.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
                assertFalse(fixture.model.state.value.busy)
                settle()
                assertEquals(0, provider.opens)
                assertNull(dialog(fixture))
                assertNull(fixture.model.state.value.backupSummary)
            }
            assertTrue(fixture.peer.commands.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun summaryCloseAndDismissRejectStaleCompareWithoutALooperDrain() = runTest {
        val fixture = fixture()
        try {
            openBackupComparisonServer(fixture)
            input(fixture)
            for (close in listOf(true, false)) {
                val summary = unlock(fixture)
                if (close) summary.getButton(DialogInterface.BUTTON_NEGATIVE).performClick() else summary.dismiss()
                summary.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
                assertFalse(fixture.model.state.value.busy)
                settle()
                assertNull(dialog(fixture))
                assertNull(fixture.model.state.value.backupSummary)
                assertNull(fixture.model.state.value.backupComparison)
            }
            assertTrue(fixture.peer.commands.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun fleetSummaryCloseCannotAdmitStaleRestoreWithoutALooperDrain() = runTest {
        val fixture = fixture()
        try {
            input(fixture)
            val summary = unlock(fixture, R.id.vpn0wn_vps_import_backup)
            summary.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            summary.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            assertFalse(fixture.model.state.value.active)
            assertNull(fixture.model.state.value.restore)
            assertNull(fixture.model.state.value.backupSummary)
            settle()
            assertNull(dialog(fixture))
            assertTrue(fixture.peer.commands.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun completeRecordChangesRetirePickerAndAuthenticationCallbacks() = runTest {
        val fixture = fixture()
        try {
            var selected = openBackupComparisonServer(fixture)
            val provider = input(fixture)
            var request = request(fixture)
            selected = selected.copy(name = "record changed while picking")
            Vpn0wnApplication.getVpsServerStore().upsert(selected)
            awaitBackupComparisonRecord(fixture, selected)
            deliver(fixture, request.requestCode)
            settle()
            assertEquals(0, AppDataTransferSettingsTest.Authentication.calls)
            request = request(fixture)
            deliver(fixture, request.requestCode)
            settle()
            val callback = AppDataTransferSettingsTest.Authentication.callbacks.single()
            selected = selected.copy(name = "record changed while authenticating")
            Vpn0wnApplication.getVpsServerStore().upsert(selected)
            awaitBackupComparisonRecord(fixture, selected)
            callback(BiometricAuthenticator.Result.Success(null))
            settle()
            assertNull(dialog(fixture))
            assertEquals(0, provider.opens)
            assertTrue(fixture.peer.commands.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun clearedInspectionRejectsItsOldSummaryBeforeRenderCanRetireTheDialog() = runTest {
        val fixture = fixture()
        try {
            openBackupComparisonServer(fixture)
            input(fixture)
            val summary = unlock(fixture)
            fixture.model.clearBackupInspection()
            summary.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            assertFalse(fixture.model.state.value.busy)
            assertNull(fixture.model.state.value.backupComparison)
            settle()
            assertNull(dialog(fixture))
            assertTrue(fixture.peer.commands.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun closeDuringReadDrainsTheOwnedClientAndNeverPublishesLateResult() = runTest {
        val fixture = fixture()
        var pending: Job? = null
        try {
            openBackupComparisonServer(fixture)
            input(fixture)
            val summary = unlock(fixture)
            fixture.peer.blockBackup = CompletableDeferred()
            summary.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            settle()
            fixture.peer.backupEntered.await()
            pending = job(fixture)
            assertTrue(fixture.model.state.value.busy)
            summary.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            summary.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            assertNull(dialog(fixture))
            pending?.join()
            settle()
            assertNull(fixture.model.state.value.backupComparison)
            assertNull(fixture.model.state.value.backupSummary)
            assertFalse(fixture.model.state.value.busy)
            assertEquals(fixture.peer.connections.size, fixture.peer.closes.get())
            assertEquals(1, backupCalls(fixture))
        } finally {
            fixture.peer.blockBackup?.complete(Unit)
            withContext(NonCancellable) { pending?.cancelAndJoin() }
            fixture.close()
        }
    }

    @Test fun stoppedViewRetiresComparisonAndChangedIdentityStaysAnInlineFailure() = runTest {
        val fixture = fixture()
        try {
            openBackupComparisonServer(fixture)
            input(fixture)
            val summary = unlock(fixture)
            fixture.peer.backupOutput = ProvisioningJson.encodeBackup(VpsBackupExportFixture.snapshot.copy(config =
                VpsBackupExportFixture.snapshot.config.replace(VpsProfileFixture.serverKey.privateKey.toBase64(),
                    VpsProfileFixture.client.privateKey.toBase64()))).toString(Charsets.UTF_8)
            summary.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            finish(fixture)
            assertSame(summary, dialog(fixture))
            assertNull(fixture.model.state.value.backupComparison)
            assertTrue(summary.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled)
            assertFalse(text(summary).contains(VpsProfileFixture.client.privateKey.toBase64()))
            fixture.controller.pause().stop()
            summary.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            assertNull(dialog(fixture))
            assertNull(fixture.model.state.value.backupSummary)
            fixture.controller.start().resume()
            settle()
            assertNull(dialog(fixture))
        } finally { fixture.close() }
    }

    private suspend fun TestScope.fixture(): VpsProvisioningLayoutFixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return provisioningLayoutFixture(folder, View.LAYOUT_DIRECTION_LTR)
    }

    private suspend fun input(fixture: VpsProvisioningLayoutFixture, snapshot: VpsBackupResult = VpsBackupExportFixture.snapshot): VpsBackupInputProvider {
        val password = VpsBackupExportFixture.PASSWORD.toCharArray()
        val bytes = try { VpsBackupArchive().encrypt(snapshot, password) } finally { password.fill('\u0000') }
        return VpsBackupInputProvider(folder.newFile().apply { writeBytes(bytes) }).also {
            bytes.fill(0)
            it.register(fixture.activity)
        }
    }

    private fun TestScope.request(fixture: VpsProvisioningLayoutFixture, entry: Int = R.id.vpn0wn_vps_compare_backup):
        org.robolectric.shadows.ShadowActivity.IntentForResult {
        val button = fixture.root.findViewById<View>(entry)
        assertTrue(button.isEnabled)
        button.performClick()
        settle()
        return requireNotNull(shadowOf(fixture.activity).nextStartedActivityForResult)
    }

    private fun deliver(fixture: VpsProvisioningLayoutFixture, requestCode: Int) {
        fixture.activity.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(VpsBackupInputProvider.URI))
    }

    private fun TestScope.openPassword(fixture: VpsProvisioningLayoutFixture, entry: Int = R.id.vpn0wn_vps_compare_backup): AlertDialog {
        val request = request(fixture, entry)
        deliver(fixture, request.requestCode)
        settle()
        AppDataTransferSettingsTest.Authentication.callbacks.last()(BiometricAuthenticator.Result.Success(null))
        settle()
        return requireNotNull(dialog(fixture))
    }

    private suspend fun TestScope.unlock(fixture: VpsProvisioningLayoutFixture, entry: Int = R.id.vpn0wn_vps_compare_backup): AlertDialog {
        val form = openPassword(fixture, entry)
        form.findViewById<EditText>(R.id.vpn0wn_backup_import_password)!!.setText(VpsBackupExportFixture.PASSWORD)
        form.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        finish(fixture)
        return requireNotNull(dialog(fixture))
    }

    private fun dialog(fixture: VpsProvisioningLayoutFixture) = VpsProvisioningFragment::class.java.getDeclaredField("backupImportDialog")
        .apply { isAccessible = true }.get(fixture.fragment) as? AlertDialog
    private fun job(fixture: VpsProvisioningLayoutFixture) = VpsProvisioningFragment::class.java.getDeclaredField("backupImportJob")
        .apply { isAccessible = true }.get(fixture.fragment) as? Job
    private suspend fun TestScope.finish(fixture: VpsProvisioningLayoutFixture) { settle(); job(fixture)?.join(); settle() }
    private fun TestScope.settle() { runCurrent(); shadowOf(Looper.getMainLooper()).idle() }
    private fun text(dialog: AlertDialog) = comparisonDescendants(dialog.window!!.decorView).filterIsInstance<TextView>().joinToString("\n") { it.text }
    private fun backupCalls(fixture: VpsProvisioningLayoutFixture) = fixture.peer.commands.count { it.contains("./'backup.sh'") }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.openBackupComparisonServer(fixture: VpsProvisioningLayoutFixture,
    stored: StoredVpsServer = VpsProfileFixture.server): StoredVpsServer {
    val store = Vpn0wnApplication.getVpsServerStore()
    store.upsert(stored)
    val pin = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    store.saveCredential(stored, pin, VpsCredential.Password("synthetic-comparison".toCharArray()))
    fixture.peer.backupOutput = ProvisioningJson.encodeBackup(VpsBackupExportFixture.snapshot).toString(Charsets.UTF_8)
    awaitBackupComparisonRecord(fixture, stored)
    val name = comparisonDescendants(fixture.root).filterIsInstance<TextView>().first { it.text.toString() == stored.name }
    fun clickable(view: View): View = if (view.isClickable) view else clickable(view.parent as View)
    clickable(name).performClick()
    runCurrent()
    shadowOf(Looper.getMainLooper()).idle()
    fixture.model.state.first { !it.busy }
    runCurrent()
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(fixture.root.findViewById<View>(R.id.vpn0wn_vps_compare_backup).isEnabled)
    return stored
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.awaitBackupComparisonRecord(fixture: VpsProvisioningLayoutFixture, stored: StoredVpsServer) {
    val rendered = CompletableDeferred<Unit>()
    val projection = VpsProvisioningFragment::class.java.getDeclaredField("storedServers").apply { isAccessible = true }
    fun observe() {
        val current = projection.get(fixture.fragment) as List<*>
        if (current.singleOrNull() == stored &&
            comparisonDescendants(fixture.root).filterIsInstance<TextView>().any { it.text.toString() == stored.name }) rendered.complete(Unit)
    }
    fixture.root.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
        override fun onChildViewAdded(parent: View?, child: View?) = observe()
        override fun onChildViewRemoved(parent: View?, child: View?) = Unit
    })
    try {
        observe()
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        withContext(Dispatchers.Default) { withTimeout(10_000) { rendered.await() } }
    } finally { fixture.root.setOnHierarchyChangeListener(null) }
}

private fun comparisonDescendants(view: View): Sequence<View> = sequence {
    yield(view)
    if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(comparisonDescendants(view.getChildAt(index)))
}
