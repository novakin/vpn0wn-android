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
import com.vpn0wn.android.provisioning.VpsBackupArchive
import com.vpn0wn.android.provisioning.VpsBackupDocument
import com.vpn0wn.android.provisioning.VpsBackupExportFixture
import com.vpn0wn.android.provisioning.VpsBackupInputProvider
import com.vpn0wn.android.provisioning.VpsCredentialFileTest
import com.vpn0wn.android.provisioning.PendingVpsRestore
import com.vpn0wn.android.provisioning.PendingVpsRemove
import com.vpn0wn.android.provisioning.StoredVpsServer
import com.vpn0wn.android.provisioning.VpsCredential
import com.vpn0wn.android.provisioning.VpsEndpoint
import com.vpn0wn.android.provisioning.VpsInstallRequest
import com.vpn0wn.android.provisioning.ProvisioningException
import com.vpn0wn.android.provisioning.ProvisioningFailureCategory
import com.vpn0wn.android.provisioning.VpsFreshRestoreWorkflowTest
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel
import com.vpn0wn.android.provisioning.VpsProfileFixture
import com.vpn0wn.android.provisioning.VpsRestoreFixture
import com.vpn0wn.android.provisioning.VpsUserProfileFileTest
import com.vpn0wn.android.util.BiometricAuthenticator
import java.security.MessageDigest
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
class VpsFreshRestoreFragmentTest {
    @get:Rule val folder = TemporaryFolder()

    @Before fun resetAuthentication() {
        VpsUserProfileFileTest.resetNativeFiles()
        AppDataTransferSettingsTest.Authentication.calls = 0
        AppDataTransferSettingsTest.Authentication.autoCancel = false
        AppDataTransferSettingsTest.Authentication.expectedTitleRes = R.string.vpn0wn_backup_import_authenticate
        AppDataTransferSettingsTest.Authentication.callbacks.clear()
    }

    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun realSummaryRestoreTransfersSensitiveSourceAcrossDismissAndRender() = runTest {
        val fixture = fixture()
        try {
            val summary = openSummary(fixture)
            summary.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            settle()
            assertFalse(summary.isShowing)
            assertNull(importDialog(fixture))
            assertNull(fixture.model.state.value.backupSummary)
            assertNotNull(fixture.model.state.value.restore?.summary)
            assertTrue(fixture.model.state.value.active)
            invoke(fixture, "render")
            settle()
            prepareTarget(fixture)
            assertTrue(fixture.model.canRestore)
            assertEquals(VpsFreshRestoreWorkflowTest.PLAN, fixture.model.state.value.restore?.plan)
            assertTrue(fixture.peer.commands.any { it.contains("'--check'") })
            assertFalse(fixture.peer.commands.any { it.contains("'--confirm'") })
        } finally { fixture.close() }
    }

    @Test fun dismissedAndStaleSummaryButtonsCannotConsumeLaterInspection() = runTest {
        val fixture = fixture()
        try {
            val stale = openSummary(fixture)
            stale.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            settle()
            stale.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            settle()
            assertNull(fixture.model.state.value.restore)
            val current = openSummary(fixture)
            stale.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            settle()
            assertSame(current, importDialog(fixture))
            assertNull(fixture.model.state.value.restore)
            current.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            settle()
            assertNotNull(fixture.model.state.value.restore?.summary)
            assertTrue(fixture.peer.commands.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun stoppingRestorationRetiresSourceAndRequiresAnotherAuthenticatedImport() = runTest {
        val fixture = fixture()
        try {
            handoff(fixture)
            fixture.controller.pause().stop()
            settle()
            assertFalse(fixture.model.state.value.active)
            assertNull(fixture.model.state.value.restore)
            assertNull(fixture.model.checkRestore())
            assertFalse(fixture.model.beginRestoreFromInspection())
            fixture.controller.start().resume()
            settle()
            assertNull(fixture.model.requestRestoreConfirmation())
            assertTrue(fixture.peer.commands.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun destroyedViewRetiresSourceAndOldSummaryCannotStartOnReplacementView() = runTest {
        val fixture = fixture()
        try {
            val summary = openSummary(fixture)
            fixture.activity.supportFragmentManager.beginTransaction().detach(fixture.fragment).commitNow()
            fixture.activity.supportFragmentManager.beginTransaction().attach(fixture.fragment).commitNow()
            settle()
            summary.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            settle()
            assertNull(fixture.model.state.value.restore)
            assertFalse(fixture.model.beginRestoreFromInspection())
            assertTrue(fixture.peer.commands.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun configuredAuthenticationWaitsForSameViewRestartAndConsumesOnlyFirstTerminalResult() = runTest {
        val fixture = fixture()
        try {
            ready(fixture)
            val callback = requestRestoreAuthentication(fixture)
            fixture.controller.pause().stop()
            callback(BiometricAuthenticator.Result.Success(null))
            callback(BiometricAuthenticator.Result.Cancelled)
            settle()
            assertNull(restoreDialog(fixture))
            assertTrue(fixture.model.state.value.active)
            assertFalse(fixture.peer.commands.any { it.contains("'--confirm'") })
            fixture.controller.start().resume()
            settle()
            val dialog = requireNotNull(restoreDialog(fixture))
            callback(BiometricAuthenticator.Result.Success(null))
            settle()
            assertSame(dialog, restoreDialog(fixture))
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            finishModel(fixture)
            assertEquals(VpsProvisioningViewModel.RestoreOutcome.VERIFIED, fixture.model.state.value.restore?.outcome)
            assertEquals(1, fixture.peer.commands.count { it.contains("'--confirm'") })
            assertNull(Vpn0wnApplication.getVpsServerStore().pendingRestore(Vpn0wnApplication.getVpsServerStore().servers.first().single()))
        } finally { fixture.close() }
    }

    @Test fun cancelledAuthenticationAndDismissedNativeConfirmationNeverAdmit() = runTest {
        val fixture = fixture()
        try {
            ready(fixture)
            val denied = requestRestoreAuthentication(fixture)
            denied(BiometricAuthenticator.Result.Cancelled)
            denied(BiometricAuthenticator.Result.Success(null))
            settle()
            assertNull(restoreDialog(fixture))
            val accepted = requestRestoreAuthentication(fixture)
            accepted(BiometricAuthenticator.Result.Success(null))
            settle()
            val dialog = requireNotNull(restoreDialog(fixture))
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            settle()
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            settle()
            assertNull(restoreDialog(fixture))
            assertFalse(fixture.peer.commands.any { it.contains("'--confirm'") })
            assertNull(Vpn0wnApplication.getVpsServerStore().pendingRestore(Vpn0wnApplication.getVpsServerStore().servers.first().single()))
        } finally { fixture.close() }
    }

    @Test fun lateAuthenticationAfterViewReplacementCannotShowConfirmationOrDispatch() = runTest {
        val fixture = fixture()
        try {
            ready(fixture)
            val callback = requestRestoreAuthentication(fixture)
            fixture.activity.supportFragmentManager.beginTransaction().detach(fixture.fragment).commitNow()
            fixture.activity.supportFragmentManager.beginTransaction().attach(fixture.fragment).commitNow()
            settle()
            callback(BiometricAuthenticator.Result.Success(null))
            settle()
            assertNull(restoreDialog(fixture))
            assertFalse(fixture.model.canRestore)
            assertFalse(fixture.peer.commands.any { it.contains("'--confirm'") })
        } finally { fixture.close() }
    }

    @Test fun oldAuthenticationCannotReplaceSameViewReviewOrClearItsNewerAuthentication() = runTest {
        val fixture = fixture()
        try {
            ready(fixture)
            val old = requestRestoreAuthentication(fixture)
            val current = requestRestoreAuthentication(fixture)
            val slot = VpsProvisioningFragment::class.java.getDeclaredField("restoreAuthentication").apply { isAccessible = true }
            val expected = slot.get(fixture.fragment)
            old(BiometricAuthenticator.Result.Success(null))
            settle()
            assertNull(restoreDialog(fixture))
            assertSame(expected, slot.get(fixture.fragment))
            old(BiometricAuthenticator.Result.Cancelled)
            settle()
            assertSame(expected, slot.get(fixture.fragment))
            current(BiometricAuthenticator.Result.Success(null))
            settle()
            val dialog = requireNotNull(restoreDialog(fixture))
            old(BiometricAuthenticator.Result.Success(null))
            settle()
            assertSame(dialog, restoreDialog(fixture))
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            finishModel(fixture)
            assertEquals(VpsProvisioningViewModel.RestoreOutcome.VERIFIED, fixture.model.state.value.restore?.outcome)
            assertEquals(1, fixture.peer.commands.count { it.contains("'--confirm'") })
        } finally { fixture.close() }
    }

    @Test fun aliasShowsOriginalOwnerGuidanceWithoutAnImpossibleReconciliationAction() = runTest {
        val fixture = fixture()
        try {
            ready(fixture)
            val store = Vpn0wnApplication.getVpsServerStore()
            val owner = store.servers.first().single()
            val alias = owner.copy(id = "restore-alias", name = "Restore alias")
            store.upsert(alias)
            val attempt = pending(owner)
            store.admitRestore(owner, attempt)
            fixture.model.leave()
            awaitFleet(fixture, alias)
            serverCard(fixture, alias).performClick()
            finishModel(fixture)
            assertEquals(attempt, fixture.model.state.value.restore?.attempt)
            assertTrue(descendants(fixture.root).filterIsInstance<TextView>().any {
                it.text.toString() == fixture.activity.getString(R.string.vpn0wn_restore_foreign)
            })
            assertFalse(descendants(fixture.root).filterIsInstance<TextView>().any {
                it.text.toString() == fixture.activity.getString(R.string.vpn0wn_restore_reconcile)
            })
            assertEquals(attempt, store.pendingRestore(alias))
            assertFalse(fixture.peer.commands.any { it.contains("'--confirm'") || it.contains("'--verify'") })
        } finally { fixture.close() }
    }

    @Test fun busyOperationDisablesServerEntryUntilPendingDestinationCanBeDiscovered() = runTest {
        val fixture = fixture()
        try {
            ready(fixture)
            val store = Vpn0wnApplication.getVpsServerStore()
            val first = store.servers.first().single().copy(dns = VpsFreshRestoreWorkflowTest.PLAN.dns,
                cidr = VpsFreshRestoreWorkflowTest.PLAN.cidr)
            store.upsert(first)
            val second = first.copy(id = "pending-destination", name = "Pending destination", host = "203.0.113.19")
            store.upsert(second)
            store.saveCredential(second, VpsRestoreFixture.pin, VpsCredential.Password("synthetic-second-access".toCharArray()))
            val attempt = pending(second)
            store.admitRestore(second, attempt)
            val legacy = store.admitAdd(first, VpsRestoreFixture.pin, VpsFreshRestoreWorkflowTest.PLAN.serverPublicKey,
                VpsProfileFixture.request.copy(dns = first.dns))
            fixture.model.leave()
            awaitFleet(fixture, second)
            serverCard(fixture, first).performClick()
            finishModel(fixture)
            fixture.peer.blockStatus = CompletableDeferred()
            val active = requireNotNull(fixture.model.syncStatus(first))
            fixture.peer.statusEntered.await()
            invoke(fixture, "goBack")
            settle()
            val blocked = serverCard(fixture, second)
            assertFalse(blocked.isEnabled)
            val node = blocked.createAccessibilityNodeInfo()
            try { assertFalse(node.isEnabled) } finally { node.recycle() }
            blocked.performClick()
            settle()
            assertNotNull(fixture.root.findViewById<View>(R.id.vpn0wn_vps_import_backup))
            assertEquals(first.id, fixture.model.state.value.serverId)
            fixture.peer.blockStatus!!.complete(Unit)
            active.join()
            settle()
            val available = serverCard(fixture, second)
            assertTrue(available.isEnabled)
            available.performClick()
            finishModel(fixture)
            assertEquals(second.id, fixture.model.state.value.serverId)
            assertEquals(second, fixture.model.state.value.restore?.owner)
            assertTrue(fixture.model.state.value.pendingAdd)
            assertEquals(first.id, fixture.model.state.value.creationOwner?.id)
            assertEquals(attempt, fixture.model.state.value.restore?.attempt)
            assertTrue(descendants(fixture.root).filterIsInstance<TextView>().any {
                it.text.toString() == fixture.activity.getString(R.string.vpn0wn_restore_reconcile)
            })
            assertEquals(attempt, store.pendingRestore(second))
            assertEquals(legacy, store.pendingAdd(store.servers.first().single { it.id == first.id }))
        } finally { fixture.peer.blockStatus?.complete(Unit); fixture.close() }
    }

    @Test fun restoreReconciliationPreservesOtherServersPendingInstallationAcrossFailureAndSuccess() = runTest {
        assertRestoreCoexistenceWithLegacyIntent(installation = true)
    }

    @Test fun restoreAdmittedAfterUserReviewPublishesActualFenceAndDisablesStaleActions() = runTest {
        val fixture = fixture()
        try {
            ready(fixture)
            fixture.model.leave()
            val store = Vpn0wnApplication.getVpsServerStore()
            val selected = store.servers.first().single()
            fixture.peer.statusOutput = serverStatus(selected)
            requireNotNull(fixture.model.syncStatus(selected)).join()
            val current = store.servers.first().single()
            awaitFleet(fixture, current)
            serverCard(fixture, current).performClick()
            finishModel(fixture)
            assertTrue(fixture.root.findViewById<View>(R.id.vpn0wn_vps_add_user).isEnabled)
            fixture.root.findViewById<View>(R.id.vpn0wn_vps_add_user).performClick()
            settle()
            val naming = requireNotNull(dialog(fixture, "addUserDialog"))
            naming.findViewById<EditText>(R.id.vpn0wn_vps_peer_name)!!.setText("restore-race")
            naming.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            finishModel(fixture)
            assertNull("Native Add review preparation succeeds", fixture.model.state.value.problem)
            val review = requireNotNull(dialog(fixture, "addUserDialog"))
            assertNotNull(fixture.model.state.value.addConfirmation)
            fixture.peer.uploadEntered = CompletableDeferred()
            fixture.peer.blockUpload = CompletableDeferred()
            review.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            val creating = VpsProvisioningViewModel::class.java.getDeclaredField("operation").apply { isAccessible = true }
                .get(fixture.model) as Job
            fixture.peer.uploadEntered.await()
            val attempt = pending(current)
            store.admitRestore(current, attempt)
            fixture.peer.blockUpload!!.complete(Unit)
            creating.join()
            settle()
            assertEquals(VpsProvisioningViewModel.Problem.RESTORE_PENDING, fixture.model.state.value.problem)
            assertEquals(current, fixture.model.state.value.restore?.owner)
            assertEquals(attempt, fixture.model.state.value.restore?.attempt)
            assertEquals(VpsProvisioningViewModel.RestoreOutcome.UNCERTAIN, fixture.model.state.value.restore?.outcome)
            assertTrue(fixture.model.state.value.statusSnapshots.containsKey(current.id))
            assertFalse(fixture.root.findViewById<View>(R.id.vpn0wn_vps_add_user).isEnabled)
            assertEquals(attempt, store.pendingRestore(current))
            assertNull(store.pendingAdd(current))
            assertFalse(fixture.peer.commands.any { it.contains("./'add-peer.sh'") || it.contains("'--confirm'") })
        } finally { fixture.peer.blockUpload?.complete(Unit); fixture.close() }
    }

    @Test fun restoreReconciliationPreservesOtherServersPendingUserReceiptAcrossFailureAndSuccess() = runTest {
        assertRestoreCoexistenceWithLegacyIntent(installation = false)
    }

    @Test fun originalAliasOriginalNavigationPreservesActualPendingRemovalActionAndHidesForeignReceipts() = runTest {
        for (action in PendingVpsRemove.Action.entries) {
            val fixture = fixture()
            try {
                ready(fixture)
                fixture.model.leave()
                val store = Vpn0wnApplication.getVpsServerStore()
                val original = store.servers.first().single()
                val alias = original.copy(id = "removal-alias", name = "Removal alias")
                store.upsert(alias)
                store.saveCredential(alias, VpsRestoreFixture.pin, VpsCredential.Password("synthetic-alias-access".toCharArray()))
                val pending = PendingVpsRemove(original.id, VpsEndpoint(original.host, original.sshPort, original.sshUser),
                    VpsRestoreFixture.pin, VpsFreshRestoreWorkflowTest.PLAN.serverPublicKey, "phone",
                    VpsProfileFixture.client.publicKey.toBase64(), action,
                    VpsRestoreFixture.OTHER_OPERATION.takeIf { action == PendingVpsRemove.Action.REVOKE })
                store.admitRemove(original, pending)
                fixture.peer.statusOutput = serverStatus(alias, hasPeer = true)
                requireNotNull(fixture.model.syncStatus(alias)).join()
                assertNull("Known live peer status is valid", fixture.model.state.value.problem)
                val currentAlias = store.servers.first().single { it.id == alias.id }
                if (action == PendingVpsRemove.Action.REVOKE) requireNotNull(fixture.model.requestRemoveUserConfirmation(
                    currentAlias, pending.name, pending.clientPublicKey)).join()
                else requireNotNull(fixture.model.requestRevokeUserConfirmation(currentAlias, pending.name, pending.clientPublicKey)).join()
                assertEquals(action, fixture.model.state.value.removalAction)
                assertEquals(original, fixture.model.state.value.removalOwner)
                assertNull(fixture.model.state.value.removalAttempt)
                awaitFleet(fixture, currentAlias)
                serverCard(fixture, currentAlias).performClick()
                finishModel(fixture)
                assertNull(fixture.root.findViewById<View>(R.id.vpn0wn_vps_user_removal_status))
                assertNull(fixture.root.findViewById<View>(R.id.vpn0wn_vps_reconcile_removal))
                requireNotNull(fixture.model.reconcileRemoveUser(currentAlias)).join()
                assertEquals(action, fixture.model.state.value.removalAction)
                assertNull(fixture.model.state.value.removalAttempt)
                invoke(fixture, "goBack")
                settle()
                val commands = fixture.peer.commands.size
                serverCard(fixture, original).performClick()
                finishModel(fixture)
                assertEquals(commands, fixture.peer.commands.size)
                val expected = if (action == PendingVpsRemove.Action.REVOKE) R.string.vpn0wn_user_revoke_reconcile else R.string.vpn0wn_user_remove_reconcile
                val reconciliation = fixture.root.findViewById<View>(R.id.vpn0wn_vps_reconcile_removal)
                assertEquals(fixture.activity.getString(expected),
                    descendants(reconciliation).filterIsInstance<TextView>().single().text.toString())
                assertEquals(action, fixture.model.state.value.removalAction)
                assertEquals(pending, store.pendingRemove(original))
                assertFalse(fixture.peer.commands.any { it.contains("./'remove-peer.sh'") || it.contains("./'revoke-peer.sh'") })
            } finally { fixture.close() }
        }
    }

    private suspend fun TestScope.assertRestoreCoexistenceWithLegacyIntent(installation: Boolean) {
        val fixture = fixture()
        try {
            ready(fixture)
            val store = Vpn0wnApplication.getVpsServerStore()
            val destination = store.servers.first().single()
            val legacy = destination.copy(id = "legacy-owner", name = "Legacy owner", host = "203.0.113.29",
                endpoint = "legacy.example", listenPort = "51820", cidr = "10.8.0.0/24", dns = VpsProfileFixture.server.dns)
            store.upsert(legacy)
            store.saveCredential(legacy, VpsRestoreFixture.pin, VpsCredential.Password("synthetic-legacy-access".toCharArray()))
            val document = VpsBackupDocument(fixture.activity.contentResolver, VpsBackupInputProvider.URI)
            val ciphertext = document.read()
            val digest = try { MessageDigest.getInstance("SHA-256").digest(ciphertext).joinToString("") { "%02x".format(it) } }
            finally { ciphertext.fill(0) }
            val restore = pending(destination).copy(ciphertextSha256 = digest)
            store.admitRestore(destination, restore)
            val install = if (installation) store.admitInstall(legacy,
                VpsEndpoint(legacy.host, legacy.sshPort, legacy.sshUser), VpsInstallRequest("legacy.example", "10.9.0.0/24", "9.9.9.9")) else null
            val add = if (!installation) store.admitAdd(legacy, VpsRestoreFixture.pin,
                VpsFreshRestoreWorkflowTest.PLAN.serverPublicKey, VpsProfileFixture.request.copy(dns = legacy.dns)) else null
            if (installation) requireNotNull(fixture.model.reuse(legacy)).join()
            else {
                fixture.model.leave()
                fixture.peer.statusOutput = serverStatus(legacy)
                requireNotNull(fixture.model.syncStatus(legacy)).join()
            }
            fixture.model.leave()
            awaitFleet(fixture, destination)
            serverCard(fixture, destination).performClick()
            finishModel(fixture)
            assertEquals(destination.id, fixture.model.state.value.serverId)
            assertEquals(restore, fixture.model.state.value.restore?.attempt)
            requireNotNull(fixture.model.inspectBackup(document, VpsFreshRestoreWorkflowTest.PASSWORD.toCharArray())).join()
            fixture.peer.verifyRestoreFailure = ProvisioningException("synthetic invalid verification", category = ProvisioningFailureCategory.INVALID_RESPONSE)
            requireNotNull(fixture.model.reconcileRestore(destination, restore)).join()
            settle()
            assertEquals(destination.id, fixture.model.state.value.serverId)
            assertEquals(VpsProvisioningViewModel.Problem.INVALID_RESPONSE, fixture.model.state.value.problem)
            assertEquals(fixture.activity.getString(R.string.vpn0wn_ssh_invalid_response),
                fixture.root.findViewById<TextView>(R.id.vpn0wn_vps_operation_problem).text.toString())
            assertFalse(fixture.root.findViewById<View>(R.id.vpn0wn_vps_add_user).isEnabled)
            assertEquals(restore, store.pendingRestore(destination))
            if (installation) {
                assertTrue(fixture.model.state.value.pendingInstall)
                assertEquals(legacy.id, fixture.model.state.value.installationOwner?.id)
            } else {
                assertTrue(fixture.model.state.value.pendingAdd)
                assertEquals(legacy.id, fixture.model.state.value.creationOwner?.id)
            }
            requireNotNull(fixture.model.inspectBackup(document, VpsFreshRestoreWorkflowTest.PASSWORD.toCharArray())).join()
            fixture.peer.verifyRestoreFailure = null
            requireNotNull(fixture.model.reconcileRestore(destination, restore)).join()
            val restored = store.servers.first().single { it.id == destination.id }
            fixture.peer.statusOutput = serverStatus(restored)
            requireNotNull(fixture.model.syncStatus(restored)).join()
            settle()
            assertEquals(destination.id, fixture.model.state.value.serverId)
            assertNull(store.pendingRestore(restored))
            assertTrue(fixture.root.findViewById<View>(R.id.vpn0wn_vps_add_user).isEnabled)
            val retained = store.servers.first().single { it.id == legacy.id }
            if (installation) {
                assertTrue(fixture.model.state.value.pendingInstall)
                assertEquals(legacy.id, fixture.model.state.value.installationOwner?.id)
                assertEquals(install, store.pendingInstall(retained))
            } else {
                assertEquals(add, store.pendingAdd(retained))
                assertFalse(fixture.model.state.value.statusSnapshots.containsKey(legacy.id))
            }
            assertEquals(2, fixture.peer.commands.count { it.contains("'--verify'") })
            assertFalse(fixture.peer.commands.any { it.contains("'--confirm'") || it.contains("./'install.sh'") || it.contains("./'add-peer.sh'") })
        } finally { fixture.close() }
    }

    private fun serverStatus(server: StoredVpsServer, hasPeer: Boolean = false): String = JSONObject(VpsBackupExportFixture.status(hasPeer))
        .put("endpoint", server.endpoint.ifBlank { VpsFreshRestoreWorkflowTest.PLAN.endpoint })
        .put("listenPort", server.listenPort.ifBlank { VpsFreshRestoreWorkflowTest.PLAN.port })
        .put("cidr", server.cidr.ifBlank { VpsFreshRestoreWorkflowTest.PLAN.cidr })
        .put("dns", server.dns.ifBlank { VpsFreshRestoreWorkflowTest.PLAN.dns })
        .put("serverPublicKey", VpsFreshRestoreWorkflowTest.PLAN.serverPublicKey).toString()

    private suspend fun TestScope.fixture(): VpsProvisioningLayoutFixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return provisioningLayoutFixture(folder, View.LAYOUT_DIRECTION_LTR)
    }

    private suspend fun TestScope.openSummary(fixture: VpsProvisioningLayoutFixture): AlertDialog {
        AppDataTransferSettingsTest.Authentication.expectedTitleRes = R.string.vpn0wn_backup_import_authenticate
        val bytes = VpsBackupArchive().encrypt(VpsFreshRestoreWorkflowTest.SNAPSHOT, VpsFreshRestoreWorkflowTest.PASSWORD.toCharArray())
        try { VpsBackupInputProvider(folder.newFile().apply { writeBytes(bytes) }).register(fixture.activity) } finally { bytes.fill(0) }
        val entry = fixture.root.findViewById<View>(R.id.vpn0wn_vps_import_backup)
        assertTrue(entry.performClick())
        settle()
        val request = requireNotNull(shadowOf(fixture.activity).nextStartedActivityForResult)
        fixture.activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent().setData(VpsBackupInputProvider.URI))
        settle()
        AppDataTransferSettingsTest.Authentication.callbacks.last()(BiometricAuthenticator.Result.Success(null))
        settle()
        val password = requireNotNull(importDialog(fixture))
        password.findViewById<EditText>(R.id.vpn0wn_backup_import_password)!!.setText(VpsFreshRestoreWorkflowTest.PASSWORD)
        password.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle()
        val job = VpsProvisioningFragment::class.java.getDeclaredField("backupImportJob").apply { isAccessible = true }
            .get(fixture.fragment) as? Job
        job?.join()
        settle()
        assertNull(fixture.model.state.value.backupFailure)
        return requireNotNull(importDialog(fixture))
    }

    private suspend fun TestScope.handoff(fixture: VpsProvisioningLayoutFixture) {
        openSummary(fixture).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle()
    }

    private suspend fun TestScope.ready(fixture: VpsProvisioningLayoutFixture) { handoff(fixture); prepareTarget(fixture) }

    private suspend fun TestScope.prepareTarget(fixture: VpsProvisioningLayoutFixture) {
        val plan = VpsFreshRestoreWorkflowTest.PLAN
        fixture.peer.restorePlan = plan
        fixture.model.edit(fixture.model.draft.copy(host = "198.51.100.42", endpoint = plan.endpoint, port = plan.port,
            fingerprint = VpsRestoreFixture.pin, authMode = VpsProvisioningViewModel.AuthMode.PASSWORD))
        fixture.model.edit(fixture.model.draft.copy(password = "synthetic-ssh-password".toCharArray()))
        requireNotNull(fixture.model.detect()).join()
        requireNotNull(fixture.model.checkRestore()).join()
        fixture.model.reviewApply()
        settle()
        assertTrue(fixture.model.canRestore)
    }

    private fun requestRestoreAuthentication(fixture: VpsProvisioningLayoutFixture): (BiometricAuthenticator.Result) -> Unit {
        AppDataTransferSettingsTest.Authentication.expectedTitleRes = R.string.vpn0wn_restore_authenticate
        invoke(fixture, "confirmRestoration")
        return AppDataTransferSettingsTest.Authentication.callbacks.last()
    }

    private suspend fun TestScope.finishModel(fixture: VpsProvisioningLayoutFixture) {
        settle()
        (VpsProvisioningViewModel::class.java.getDeclaredField("operation").apply { isAccessible = true }.get(fixture.model) as? Job)?.join()
        settle()
    }

    private fun pending(server: StoredVpsServer) = PendingVpsRestore(VpsRestoreFixture.attempt.operationId, server.id,
        VpsEndpoint(server.host, server.sshPort, server.sshUser), VpsRestoreFixture.pin, "a".repeat(64), VpsFreshRestoreWorkflowTest.PLAN)

    private suspend fun TestScope.awaitFleet(fixture: VpsProvisioningLayoutFixture, expected: StoredVpsServer) {
        val root = fixture.root as ViewGroup
        val emitted = CompletableDeferred<Unit>()
        fun observe() {
            if (descendants(root).filterIsInstance<TextView>().any { it.text.toString() == expected.name }) emitted.complete(Unit)
        }
        root.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) = observe()
            override fun onChildViewRemoved(parent: View?, child: View?) = Unit
        })
        try {
            observe()
            settle()
            withContext(Dispatchers.Default) { withTimeout(10_000) { emitted.await() } }
            settle()
        } finally { root.setOnHierarchyChangeListener(null) }
    }

    private fun serverCard(fixture: VpsProvisioningLayoutFixture, server: StoredVpsServer): View {
        var candidate: View = descendants(fixture.root).filterIsInstance<TextView>().single { it.text.toString() == server.name }
        while (!candidate.hasOnClickListeners()) candidate = candidate.parent as View
        return candidate
    }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }

    private fun invoke(fixture: VpsProvisioningLayoutFixture, method: String) = VpsProvisioningFragment::class.java
        .getDeclaredMethod(method).apply { isAccessible = true }.invoke(fixture.fragment)
    private fun importDialog(fixture: VpsProvisioningLayoutFixture) = dialog(fixture, "backupImportDialog")
    private fun restoreDialog(fixture: VpsProvisioningLayoutFixture) = dialog(fixture, "restoreDialog")
    private fun dialog(fixture: VpsProvisioningLayoutFixture, name: String) = VpsProvisioningFragment::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(fixture.fragment) as? AlertDialog
    private fun TestScope.settle() { runCurrent(); shadowOf(Looper.getMainLooper()).idle() }
}
