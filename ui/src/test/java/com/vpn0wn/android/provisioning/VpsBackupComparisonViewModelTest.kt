/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [VpsCredentialFileTest.NativeFiles::class])
@OptIn(ExperimentalCoroutinesApi::class)
class VpsBackupComparisonViewModelTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var scope: CoroutineScope
    private lateinit var preferences: DataStore<Preferences>
    private lateinit var store: VpsServerStore
    private lateinit var model: VpsProvisioningViewModel
    private val owner = ViewModelStore()
    private val peer = VpsPlanningTestPeer()
    private val server = VpsProfileFixture.server

    @Before fun setUp() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        VpsUserProfileFileTest.resetNativeFiles()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }
        store = VpsServerStore(preferences, credentialFileForTest(File(folder.root, "credentials")))
        store.upsert(server)
        store.saveCredential(server, PIN, VpsCredential.Password("synthetic-ssh".toCharArray()))
        peer.backupOutput = ProvisioningJson.encodeBackup(VpsBackupExportFixture.snapshot).toString(Charsets.UTF_8)
        model = VpsProvisioningViewModel(ApplicationProvider.getApplicationContext(), store, peer)
        owner.put("comparison", model)
    }

    @After fun tearDown() = runTest {
        peer.blockBackup?.complete(Unit)
        try {
            val job = if (::model.isInitialized) model.viewModelScope.coroutineContext[Job] else null
            owner.clear()
            job?.cancelAndJoin()
        } finally {
            try { if (::scope.isInitialized) scope.coroutineContext[Job]!!.cancelAndJoin() } finally { Dispatchers.resetMain() }
        }
    }

    @Test fun oneFreshBackupReadPublishesOnlySafeComparisonWithoutStatusOrPersistenceWrites() = runTest {
        inspect()
        val before = preferences.data.first()
        var ownerChecks = 0
        requireNotNull(model.compareBackup(server) { ownerChecks++ }).join()
        val result = requireNotNull(model.state.value.backupComparison)
        assertTrue(result.configTextMatches)
        assertTrue(result.onlyInBackup.isEmpty())
        assertEquals(3, ownerChecks)
        assertEquals(before, preferences.data.first())
        assertTrue(model.state.value.statusSnapshots.isEmpty())
        assertNull(model.state.value.restore)
        assertFalse(model.canRestore)
        assertFalse(model.state.value.active)
        assertNull(model.state.value.problem)
        assertEquals(1, backupCalls())
        assertFalse(peer.commands.any { it.contains("sync-status.sh") || it.contains("restore.sh") })
        assertEquals(peer.connections.size, peer.closes.get())
        assertTrue(peer.connections.all { (it.credential as VpsCredential.Password).value.all { char -> char == '\u0000' } })
        assertFalse(model.state.value.toString().contains(VpsBackupExportFixture.snapshot.config))
        assertFalse(model.state.value.toString().contains(VpsProfileFixture.serverKey.privateKey.toBase64()))
        model.clearBackupInspection()
        assertNull(model.state.value.backupComparison)
        assertNull(model.state.value.backupSummary)
        assertNull(model.compareBackup(server) {})
    }

    @Test fun comparisonDoesNotClearOrAuthoriseAnExistingUncertainMutation() = runTest {
        val pending = PendingVpsRemove(server.id, VpsEndpoint(server.host, server.sshPort, server.sshUser), PIN,
            VpsProfileFixture.serverKey.publicKey.toBase64(), "phone", VpsProfileFixture.client.publicKey.toBase64())
        store.admitRemove(server, pending)
        inspect()
        val before = preferences.data.first()
        requireNotNull(model.compareBackup(server) {}).join()
        assertNotNull(model.state.value.backupComparison)
        assertEquals(before, preferences.data.first())
        assertEquals(pending, store.pendingRemove(server))
        assertFalse(model.canRestore)
        assertFalse(peer.commands.any { it.contains("remove-peer.sh") || it.contains("restore.sh") })
    }

    @Test fun missingInspectionOrMissingSavedAccessNeverReadTheServer() = runTest {
        assertNull(model.compareBackup(server) {})
        inspect()
        store.clearCredential(server.id)
        requireNotNull(model.compareBackup(server) {}).join()
        assertEquals(VpsProvisioningViewModel.Problem.ACCESS_MISSING, model.state.value.problem)
        assertNull(model.state.value.backupComparison)
        assertEquals(0, backupCalls())
    }

    @Test fun onlyWg0WithTheInspectedDerivedServerIdentityCanPublish() = runTest {
        inspect()
        for (changed in listOf(
            VpsBackupExportFixture.snapshot.copy(interfaceName = "wg1"),
            VpsBackupExportFixture.snapshot.copy(config = VpsBackupExportFixture.snapshot.config.replace(
                VpsProfileFixture.serverKey.privateKey.toBase64(), VpsProfileFixture.client.privateKey.toBase64())),
            VpsBackupExportFixture.snapshot.copy(config = "# TUNNL_MANAGED 1\n[Interface]\n"),
            VpsBackupExportFixture.snapshot.copy(config = VpsBackupExportFixture.snapshot.config + "PrivateKey = duplicate\n"),
        )) {
            peer.backupOutput = ProvisioningJson.encodeBackup(changed).toString(Charsets.UTF_8)
            requireNotNull(model.compareBackup(server) {}).join()
            assertEquals(VpsProvisioningViewModel.Problem.INVALID_RESPONSE, model.state.value.problem)
            assertNull(model.state.value.backupComparison)
        }
        model.clearBackupInspection()
        inspect(VpsBackupExportFixture.snapshot.copy(interfaceName = "wg1"))
        val before = backupCalls()
        requireNotNull(model.compareBackup(server) {}).join()
        assertEquals(before, backupCalls())
        assertEquals(VpsProvisioningViewModel.Problem.INVALID_RESPONSE, model.state.value.problem)
    }

    @Test fun completeRecordChangesBeforeAdmissionAndDuringReadCannotPublish() = runTest {
        inspect()
        store.upsert(server.copy(name = "changed record"))
        requireNotNull(model.compareBackup(server) {}).join()
        assertEquals(VpsProvisioningViewModel.Problem.CONNECTION_CHANGED, model.state.value.problem)
        assertEquals(0, backupCalls())
        store.upsert(server)
        peer.blockBackup = CompletableDeferred()
        val operation = requireNotNull(model.compareBackup(server) {})
        peer.backupEntered.await()
        store.upsert(server.copy(lastSyncEpochSeconds = server.lastSyncEpochSeconds + 1))
        peer.blockBackup!!.complete(Unit)
        operation.join()
        assertEquals(VpsProvisioningViewModel.Problem.CONNECTION_CHANGED, model.state.value.problem)
        assertNull(model.state.value.backupComparison)
        assertEquals(peer.connections.size, peer.closes.get())
    }

    @Test fun clearedAccessDuringCaptureRejectsTheResultAndClearsCopiedCredentials() = runTest {
        inspect()
        peer.blockBackup = CompletableDeferred()
        val operation = requireNotNull(model.compareBackup(server) {})
        peer.backupEntered.await()
        store.clearCredential(server.id)
        assertEquals(PIN, store.trustedHostFingerprint(server.host, server.sshPort))
        peer.blockBackup!!.complete(Unit)
        operation.join()
        assertEquals(VpsProvisioningViewModel.Problem.CONNECTION_CHANGED, model.state.value.problem)
        assertNull(model.state.value.backupComparison)
        assertEquals(peer.connections.size, peer.closes.get())
        assertTrue(peer.connections.all { (it.credential as VpsCredential.Password).value.all { char -> char == '\u0000' } })
    }

    @Test fun clearingTheExactPrivateInspectionDuringCaptureRejectsLatePublication() = runTest {
        inspect()
        peer.blockBackup = CompletableDeferred()
        val operation = requireNotNull(model.compareBackup(server) {})
        peer.backupEntered.await()
        model.clearBackupInspection()
        peer.blockBackup!!.complete(Unit)
        operation.join()
        assertNull(model.state.value.backupSummary)
        assertNull(model.state.value.backupComparison)
        assertEquals(VpsProvisioningViewModel.Problem.CONNECTION_CHANGED, model.state.value.problem)
    }

    @Test fun actionOwnershipIsCheckedBeforeRemoteReadAndAgainBeforePublication() = runTest {
        inspect()
        requireNotNull(model.compareBackup(server) { throw VpsServerStore.ServerChangedException() }).join()
        assertEquals(0, backupCalls())
        var owns = true
        peer.blockBackup = CompletableDeferred()
        val operation = requireNotNull(model.compareBackup(server) { if (!owns) throw VpsServerStore.ServerChangedException() })
        peer.backupEntered.await()
        owns = false
        peer.blockBackup!!.complete(Unit)
        operation.join()
        assertNull(model.state.value.backupComparison)
        assertEquals(VpsProvisioningViewModel.Problem.CONNECTION_CHANGED, model.state.value.problem)
    }

    @Test fun cancellationDrainsTheOwnedRemoteReadAndBlocksDuplicateAdmission() = runTest {
        inspect()
        peer.blockBackup = CompletableDeferred()
        val operation = requireNotNull(model.compareBackup(server) {})
        peer.backupEntered.await()
        assertNull(model.compareBackup(server) {})
        operation.cancelAndJoin()
        assertFalse(model.state.value.busy)
        assertNull(model.state.value.backupComparison)
        assertEquals(VpsProvisioningViewModel.Problem.CANCELLED, model.state.value.problem)
        assertEquals(peer.connections.size, peer.closes.get())
    }

    @Test fun malformedRemoteEnvelopeCannotBecomeAnEmptyOrSuccessfulComparison() = runTest {
        inspect()
        peer.backupOutput = JSONObject(ProvisioningJson.encodeBackup(VpsBackupExportFixture.snapshot).toString(Charsets.UTF_8))
            .put("revokedCount", "0").toString()
        requireNotNull(model.compareBackup(server) {}).join()
        assertNull(model.state.value.backupComparison)
        assertEquals(VpsProvisioningViewModel.Problem.INVALID_RESPONSE, model.state.value.problem)
    }

    private suspend fun inspect(snapshot: VpsBackupResult = VpsBackupExportFixture.snapshot) {
        val password = VpsBackupExportFixture.PASSWORD.toCharArray()
        val bytes = try { VpsBackupArchive().encrypt(snapshot, password) } finally { password.fill('\u0000') }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        VpsBackupInputProvider(folder.newFile().apply { writeBytes(bytes) }).register(context)
        bytes.fill(0)
        requireNotNull(model.inspectBackup(VpsBackupDocument(context.contentResolver, VpsBackupInputProvider.URI),
            VpsBackupExportFixture.PASSWORD.toCharArray())).join()
        assertNotNull(model.state.value.backupSummary)
    }

    private fun backupCalls() = peer.commands.count { it.contains("./'backup.sh'") }

    private companion object {
        val PIN = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    }
}
