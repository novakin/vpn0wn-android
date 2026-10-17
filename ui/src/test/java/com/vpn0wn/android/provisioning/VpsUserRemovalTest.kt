/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.Problem
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.UserRemoval
import java.io.File
import java.io.IOException
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
class VpsUserRemovalTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var preferences: DataStore<Preferences>
    private lateinit var scope: CoroutineScope
    private lateinit var store: VpsServerStore
    private lateinit var credentialFile: VpsCredentialFile
    private lateinit var profileFile: File
    private lateinit var profileKey: SecretKey
    private val owners = mutableListOf<ViewModelStore>()
    private val jobs = mutableListOf<Job>()
    private val peer = VpsPlanningTestPeer()
    private var failProfileSync = false
    private var beforeProfileSync: (() -> Unit)? = null

    @Before fun setUp() = runBlocking {
        Dispatchers.setMain(StandardTestDispatcher())
        VpsUserProfileFileTest.resetNativeFiles()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }
        credentialFile = credentialFileForTest(File(folder.root, "credentials"))
        store = VpsServerStore(preferences, credentialFile)
        profileFile = File(folder.root, "profiles")
        profileKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        store.upsert(VpsProfileFixture.server)
        store.saveCredential(VpsProfileFixture.server, PIN, VpsCredential.Password("synthetic-password".toCharArray()))
        profiles().saveGenerated(VpsProfileFixture.server, SERVER_KEY, VpsProfileFixture.request, VpsProfileFixture.result())
        peer.statusOutput = status(true)
        peer.removeOutput = JSONObject().put("ok", true).put("name", "phone").put("publicKey", CLIENT_KEY)
            .put("removed", true).put("revoked", false).toString()
        peer.afterRemove = { peer.statusOutput = status(false) }
    }

    @After fun tearDown() = runTest {
        withContext(NonCancellable) {
            peer.blockRemove?.complete(Unit)
            peer.blockRevoke?.complete(Unit)
            peer.blockUpload?.complete(Unit)
            peer.blockStatus?.complete(Unit)
            owners.forEach(ViewModelStore::clear)
            try { jobs.forEach { it.cancelAndJoin() } } finally {
                try { scope.coroutineContext[Job]!!.cancelAndJoin() } finally { Dispatchers.resetMain() }
            }
        }
    }

    @Test fun confirmedRemoveUsesBothGuardsDeletesOnlyExactProfileAndPublishesObservedAbsence() = runTest {
        val unrelated = VpsProfileFixture.server.copy(id = "unrelated")
        profiles().saveGenerated(unrelated, SERVER_KEY, VpsProfileFixture.request, VpsProfileFixture.result())
        val model = ready()
        val confirmation = prepare(model)
        assertNull(model.confirmRemoveUser(confirmation.copy()))
        val removing = requireNotNull(model.confirmRemoveUser(confirmation))
        assertNull(model.confirmRemoveUser(confirmation))
        removing.join()
        assertEquals(UserRemoval.REMOVED, model.state.value.removal)
        assertFalse(model.state.value.pendingRemove)
        assertNull(store.pendingRemove(server()))
        assertNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        assertNotNull(profiles().load(unrelated, SERVER_KEY, CLIENT_KEY))
        assertTrue(model.state.value.statusSnapshots.getValue(server().id).status.peers.isEmpty())
        val command = removeCommands().single()
        assertTrue(command.contains("'--expected-public-key' ${CLIENT_KEY.shellQuote()}"))
        assertTrue(command.contains("'--expected-server-public-key' ${SERVER_KEY.shellQuote()}"))
        assertTrue(peer.connections.all { (it.credential as VpsCredential.Password).value.all { character -> character == '\u0000' } })
        assertEquals(peer.connections.size, peer.closes.get())
    }

    @Test fun dismissedConfirmationSendsNoWriterAndKeepsProfile() = runTest {
        val model = ready()
        val confirmation = prepare(model)
        model.dismissRemoveUserConfirmation()
        assertNull(model.confirmRemoveUser(confirmation))
        assertTrue(removeCommands().isEmpty())
        assertNull(store.pendingRemove(server()))
        assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
    }

    @Test fun cancellationBeforeDispatchAdmissionKeepsIntentEmptyAndProfilePresent() = runTest {
        val model = ready()
        val confirmation = prepare(model)
        peer.uploadEntered = CompletableDeferred()
        peer.blockUpload = CompletableDeferred()
        val removing = requireNotNull(model.confirmRemoveUser(confirmation))
        peer.uploadEntered.await()
        removing.cancelAndJoin()
        assertTrue(removeCommands().isEmpty())
        assertNull(store.pendingRemove(server()))
        assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        assertEquals(peer.connections.size, peer.closes.get())
    }

    @Test fun malformedSelectedLegacyKeyRejectsReviewAsChangedPeer() = runTest {
        peer.statusOutput = status(true, clientKey = "legacy-invalid-key", runtimeKeys = null, configuredKeys = null)
        val model = ready()
        assertNotNull(model.state.value.statusSnapshots[server().id])
        requireNotNull(model.requestRemoveUserConfirmation(server(), "phone", "legacy-invalid-key")).join()
        assertNull(model.state.value.removeConfirmation)
        assertEquals(Problem.PEER_CHANGED, model.state.value.problem)
        assertTrue(removeCommands().isEmpty())
    }

    @Test fun freshServerOrPeerReplacementRejectsReviewWithoutMutation() = runTest {
        val model = ready()
        for (replacement in listOf(status(true, clientKey = OTHER_KEY), status(true, serverKey = OTHER_KEY))) {
            peer.statusOutput = replacement
            requireNotNull(model.requestRemoveUserConfirmation(server(), "phone", CLIENT_KEY)).join()
            assertNull(model.state.value.removeConfirmation)
            assertEquals(Problem.PEER_CHANGED, model.state.value.problem)
        }
        assertTrue(removeCommands().isEmpty())
        assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
    }

    @Test fun changedSavedPinAfterReviewRejectsBeforeWriterOrJournal() = runTest {
        val model = ready()
        val confirmation = prepare(model)
        store.saveCredential(server(), OTHER_PIN, VpsCredential.Password("replacement".toCharArray()))
        requireNotNull(model.confirmRemoveUser(confirmation)).join()
        assertEquals(Problem.CONNECTION_CHANGED, model.state.value.problem)
        assertTrue(removeCommands().isEmpty())
        assertNull(store.pendingRemove(server()))
        assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
    }

    @Test fun guardedServerOrPeerRejectionClearsOnlyItsUnmutatedIntent() = runTest {
        val model = ready()
        for (code in listOf("server_identity_mismatch", "peer_identity_mismatch", "peer_not_found")) {
            peer.statusOutput = status(true)
            requireNotNull(model.syncStatus(server())).join()
            val confirmation = prepare(model)
            peer.afterRemove = {}
            peer.removeExitCode = 73
            peer.removeOutput = JSONObject().put("ok", false).put("error", code).put("message", "synthetic rejection").toString()
            requireNotNull(model.confirmRemoveUser(confirmation)).join()
            assertEquals(Problem.PEER_CHANGED, model.state.value.problem)
            assertFalse(model.state.value.pendingRemove)
            assertNull(store.pendingRemove(server()))
            assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        }
    }

    @Test fun lostResponseSurvivesNewOwnerAndReconcilesWithoutAnotherWriter() = runTest {
        val model = ready()
        peer.removeFailure = ProvisioningException("synthetic lost response")
        requireNotNull(model.confirmRemoveUser(prepare(model))).join()
        assertTrue(model.state.value.pendingRemove)
        assertEquals(UserRemoval.UNCERTAIN, model.state.value.removal)
        assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        val recreated = model(VpsServerStore(preferences, credentialFile))
        requireNotNull(recreated.reconcileRemoveUser(server())).join()
        assertEquals(UserRemoval.OBSERVED, recreated.state.value.removal)
        assertFalse(recreated.state.value.pendingRemove)
        assertNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        assertEquals(1, removeCommands().size)
    }

    @Test fun liveOnlyLeftoverAndUnknownInventoryCannotProveRemoval() = runTest {
        val model = ready()
        peer.afterRemove = { peer.statusOutput = status(false, runtimeKeys = listOf(CLIENT_KEY)) }
        requireNotNull(model.confirmRemoveUser(prepare(model))).join()
        assertTrue(model.state.value.pendingRemove)
        for (observation in listOf(status(false, runtimeKeys = null), status(false, running = null, runtimeKeys = null), status(true))) {
            peer.statusOutput = observation
            requireNotNull(model.reconcileRemoveUser(server())).join()
            assertTrue(model.state.value.pendingRemove)
            assertEquals(UserRemoval.UNCERTAIN, model.state.value.removal)
            assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        }
        peer.statusOutput = status(false)
        requireNotNull(model.reconcileRemoveUser(server())).join()
        assertFalse(model.state.value.pendingRemove)
        assertEquals(1, removeCommands().size)
    }

    @Test fun knownStoppedAbsenceSettlesWithoutClaimingRunningRevocation() = runTest {
        val model = ready()
        peer.afterRemove = { peer.statusOutput = status(false, running = false, runtimeKeys = null) }
        requireNotNull(model.confirmRemoveUser(prepare(model))).join()
        assertEquals(UserRemoval.REMOVED, model.state.value.removal)
        assertEquals(false, model.state.value.statusSnapshots.getValue(server().id).status.running)
        assertFalse(model.state.value.pendingRemove)
        assertNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
    }

    @Test fun unmarkedDiskPeerAndUnknownDiskInventoryPreventRunningOrStoppedSettlement() = runTest {
        val model = ready()
        peer.afterRemove = { peer.statusOutput = status(false, configuredKeys = listOf(CLIENT_KEY)) }
        requireNotNull(model.confirmRemoveUser(prepare(model))).join()
        for (running in listOf(true, false)) for (keys in listOf(listOf(CLIENT_KEY), null)) {
            peer.statusOutput = status(false, running = running, configuredKeys = keys)
            requireNotNull(model.reconcileRemoveUser(server())).join()
            assertTrue(model.state.value.pendingRemove)
            assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        }
        peer.statusOutput = status(false, configuredKeys = listOf(OTHER_KEY))
        requireNotNull(model.reconcileRemoveUser(server())).join()
        assertEquals(UserRemoval.OBSERVED, model.state.value.removal)
        assertEquals(listOf(OTHER_KEY), model.state.value.statusSnapshots.getValue(server().id).status.configuredPeerPublicKeys)
        assertNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        assertEquals(1, removeCommands().size)
    }

    @Test fun failedStatusAfterReceiptRetainsIntentAndProfile() = runTest {
        val model = ready()
        peer.afterRemove = { peer.statusFailure = ProvisioningException("synthetic status unavailable") }
        requireNotNull(model.confirmRemoveUser(prepare(model))).join()
        assertTrue(model.state.value.pendingRemove)
        assertEquals(UserRemoval.UNCERTAIN, model.state.value.removal)
        assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        assertNotNull(store.pendingRemove(server()))
    }

    @Test fun replacementServerCannotSettleOldIntentEvenWithEmptyPeers() = runTest {
        val model = ready()
        peer.afterRemove = { peer.statusOutput = status(false, serverKey = OTHER_KEY) }
        requireNotNull(model.confirmRemoveUser(prepare(model))).join()
        assertTrue(model.state.value.pendingRemove)
        assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        requireNotNull(model.reconcileRemoveUser(server())).join()
        assertTrue(model.state.value.pendingRemove)
        assertEquals(1, removeCommands().size)
    }

    @Test fun replacementPeerSurvivesWhileOnlyCapturedAbsentKeyIsSettled() = runTest {
        val model = ready()
        peer.afterRemove = { peer.statusOutput = status(true, clientKey = OTHER_KEY) }
        requireNotNull(model.confirmRemoveUser(prepare(model))).join()
        assertFalse(model.state.value.pendingRemove)
        assertEquals(OTHER_KEY, model.state.value.statusSnapshots.getValue(server().id).status.peers.single().publicKey)
        assertNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
    }

    @Test fun renamedTargetKeyStillPreventsAbsenceSettlement() = runTest {
        val model = ready()
        peer.afterRemove = { peer.statusOutput = JSONObject(status(true)).apply { getJSONArray("peers").getJSONObject(0).put("name", "renamed") }.toString() }
        requireNotNull(model.confirmRemoveUser(prepare(model))).join()
        assertTrue(model.state.value.pendingRemove)
        assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
    }

    @Test fun vaultFailureRetainsIntentForIdempotentReadOnlySettlement() = runTest {
        val model = ready()
        val confirmation = prepare(model)
        failProfileSync = true
        requireNotNull(model.confirmRemoveUser(confirmation)).join()
        assertEquals(Problem.STORAGE_FAILED, model.state.value.problem)
        assertTrue(model.state.value.pendingRemove)
        assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        failProfileSync = false
        requireNotNull(model.reconcileRemoveUser(server())).join()
        assertEquals(UserRemoval.OBSERVED, model.state.value.removal)
        assertNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        assertEquals(1, removeCommands().size)
    }

    @Test fun changedLocalRecordCannotReceiveSettlementOrLoseItsProfile() = runTest {
        val model = ready()
        val original = server()
        peer.afterRemove = {
            runBlocking { preferences.edit { it[VpsServerStore.SERVERS] = VpsServerStore.encodeServers(listOf(original.copy(name = "new display name"))) } }
            peer.statusOutput = status(false)
        }
        requireNotNull(model.confirmRemoveUser(prepare(model))).join()
        assertEquals(Problem.CONNECTION_CHANGED, model.state.value.problem)
        assertNotNull(store.pendingRemove(server()))
        assertNotNull(profiles().load(original, SERVER_KEY, CLIENT_KEY))
    }

    @Test fun profileDeletionAndJournalCommitExcludeConcurrentMetadataMutation() = runTest {
        val model = ready()
        val original = server()
        val confirmation = prepare(model)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        beforeProfileSync = { entered.complete(Unit); runBlocking { release.await() } }
        val removing = requireNotNull(model.confirmRemoveUser(confirmation))
        try {
            entered.await()
            val editing = async { store.upsert(original.copy(name = "renamed after settlement")) }
            runCurrent()
            assertFalse(editing.isCompleted)
            assertEquals(original, server())
            release.complete(Unit)
            removing.join()
            editing.await()
            assertEquals(UserRemoval.REMOVED, model.state.value.removal)
            assertEquals("renamed after settlement", server().name)
            assertNull(store.pendingRemove(server()))
            assertNull(profiles().load(original, SERVER_KEY, CLIENT_KEY))
        } finally { release.complete(Unit) }
    }

    @Test fun deletedProfileWithFailedJournalCommitReconcilesIdempotentlyWithoutAnotherWriter() = runTest {
        var rejectSettlement = true
        val pendingKey = stringPreferencesKey("vps_pending_removals")
        val interruptedPreferences = object : DataStore<Preferences> {
            override val data = preferences.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                preferences.updateData { current ->
                    val updated = transform(current)
                    if (rejectSettlement && PendingVpsRemove.decode(current[pendingKey].orEmpty()).isNotEmpty() &&
                        PendingVpsRemove.decode(updated[pendingKey].orEmpty()).isEmpty()) {
                        rejectSettlement = false
                        throw IOException("synthetic removal settlement failure")
                    }
                    updated
                }
        }
        val model = model(VpsServerStore(interruptedPreferences, credentialFile))
        requireNotNull(model.syncStatus(server())).join()
        requireNotNull(model.confirmRemoveUser(prepare(model))).join()
        assertEquals(Problem.STORAGE_FAILED, model.state.value.problem)
        assertTrue(model.state.value.pendingRemove)
        assertNotNull(store.pendingRemove(server()))
        assertNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        val recreated = model()
        requireNotNull(recreated.reconcileRemoveUser(server())).join()
        assertEquals(UserRemoval.OBSERVED, recreated.state.value.removal)
        assertNull(store.pendingRemove(server()))
        assertEquals(1, removeCommands().size)
    }

    @Test fun anotherRecordSharingSshTargetCannotOwnRemovalFeedbackOrSettlement() = runTest {
        val model = ready()
        val original = server()
        val other = original.copy(id = "another-record")
        store.upsert(other)
        store.saveCredential(other, PIN, VpsCredential.Password("synthetic-password".toCharArray()))
        val attempt = PendingVpsRemove(original.id, VpsEndpoint(original.host, original.sshPort, original.sshUser), PIN,
            SERVER_KEY, "phone", CLIENT_KEY)
        store.admitRemove(original, attempt)
        requireNotNull(model.syncStatus(other)).join()
        assertTrue(model.state.value.pendingRemove)
        assertNull(model.state.value.removalOwner)
        val refreshed = store.servers.first().single { it.id == other.id }
        requireNotNull(model.reconcileRemoveUser(refreshed)).join()
        assertEquals(Problem.CONNECTION_CHANGED, model.state.value.problem)
        assertNull(model.state.value.removalOwner)
        assertEquals(attempt, store.pendingRemove(original))
        assertNotNull(profiles().load(original, SERVER_KEY, CLIENT_KEY))
        assertTrue(removeCommands().isEmpty())
    }

    @Test fun staleReconcileWithoutIntentCannotInventPendingRemoval() = runTest {
        val model = ready()
        requireNotNull(model.reconcileRemoveUser(server())).join()
        assertNull(model.state.value.problem)
        assertFalse(model.state.value.pendingRemove)
        assertEquals(UserRemoval.NONE, model.state.value.removal)
        assertTrue(removeCommands().isEmpty())
    }

    @Test fun foreignRemovalAdmittedAfterReviewCannotOwnOurFeedback() = runTest {
        val model = ready()
        val original = server()
        val other = original.copy(id = "another-record")
        store.upsert(other)
        store.saveCredential(other, PIN, VpsCredential.Password("synthetic-password".toCharArray()))
        requireNotNull(model.requestRemoveUserConfirmation(original, "phone", CLIENT_KEY)).join()
        val confirmation = requireNotNull(model.state.value.removeConfirmation)
        peer.uploadEntered = CompletableDeferred()
        peer.blockUpload = CompletableDeferred()
        val removing = requireNotNull(model.confirmRemoveUser(confirmation))
        peer.uploadEntered.await()
        val attempt = PendingVpsRemove(other.id, VpsEndpoint(other.host, other.sshPort, other.sshUser), PIN, SERVER_KEY, "phone", CLIENT_KEY)
        store.admitRemove(other, attempt)
        val addFailure = runCatching { store.admitAdd(original, PIN, SERVER_KEY, VpsProfileFixture.request.copy(dns = original.dns)) }.exceptionOrNull()
        assertEquals(other.id, (addFailure as VpsServerStore.RemovePendingException).ownerServerId)
        val installFailure = runCatching { store.admitInstall(original, attempt.ssh, VpsInstallRequest(original.endpoint, original.cidr, original.dns)) }.exceptionOrNull()
        assertEquals(other.id, (installFailure as VpsServerStore.RemovePendingException).ownerServerId)
        peer.blockUpload!!.complete(Unit)
        removing.join()
        assertTrue(model.state.value.pendingRemove)
        assertNull(model.state.value.removalOwner)
        assertTrue(removeCommands().isEmpty())
        assertEquals(attempt, store.pendingRemove(original))
        assertNotNull(profiles().load(original, SERVER_KEY, CLIENT_KEY))
    }

    @Test fun detectRefreshesOnlyMatchingPinnedCredentialDuringPendingRemovalDespiteMetadataDrift() = runTest {
        val model = ready()
        val original = server()
        val attempt = PendingVpsRemove(original.id, VpsEndpoint(original.host, original.sshPort, original.sshUser), PIN,
            SERVER_KEY, "phone", CLIENT_KEY)
        store.admitRemove(original, attempt)
        requireNotNull(model.reuse(original)).join()
        assertEquals(original, server())
        for (fingerprint in listOf(OTHER_PIN, PIN)) {
            model.enterCredentials()
            model.edit(model.draft.copy(fingerprint = fingerprint, authMode = VpsProvisioningViewModel.AuthMode.PASSWORD,
                password = "refreshed-password".toCharArray()))
            requireNotNull(model.detect()).join()
            assertEquals(original, server())
            assertEquals(attempt, store.pendingRemove(original))
            val connection = requireNotNull(store.loadConnection(original))
            try {
                val expected = if (fingerprint == PIN) "refreshed-password" else "synthetic-password"
                assertArrayEquals(expected.toCharArray(), (connection.credential as VpsCredential.Password).value)
                assertEquals(PIN, connection.hostKeyFingerprint)
            } finally { (connection.credential as VpsCredential.Password).clear() }
            if (fingerprint == PIN) assertEquals(VpsProvisioningViewModel.SavedAccess.SAVED, model.state.value.savedAccess)
            else assertEquals(Problem.REMOVE_PENDING, model.state.value.problem)
        }
        peer.statusOutput = status(false)
        requireNotNull(model.reconcileRemoveUser(original)).join()
        assertEquals(UserRemoval.OBSERVED, model.state.value.removal)
        assertNull(store.pendingRemove(server()))
        assertNull(profiles().load(original, SERVER_KEY, CLIENT_KEY))
        assertTrue(removeCommands().isEmpty())
    }

    @Test fun foreignRemoveAndRevokeIntentsNeverProjectTheirActionOntoSelectedServer() = runTest {
        val model = ready()
        val original = server()
        val other = original.copy(id = "other-record")
        store.upsert(other)
        store.saveCredential(other, PIN, VpsCredential.Password("synthetic-password".toCharArray()))
        val endpoint = VpsEndpoint(other.host, other.sshPort, other.sshUser)
        for (action in PendingVpsRemove.Action.entries) {
            val attempt = PendingVpsRemove(other.id, endpoint, PIN, SERVER_KEY, "phone", CLIENT_KEY, action,
                if (action == PendingVpsRemove.Action.REVOKE) "00112233-4455-4677-8899-aabbccddeeff" else null)
            store.admitRemove(other, attempt)
            requireNotNull(model.requestRevokeUserConfirmation(original, "phone", CLIENT_KEY)).join()
            assertEquals(Problem.REMOVE_PENDING, model.state.value.problem)
            assertNull(model.state.value.removalOwner)
            assertNull(model.state.value.removeConfirmation)
            requireNotNull(model.syncStatus(original)).join()
            assertNull(model.state.value.removalOwner)
            assertEquals(PendingVpsRemove.Action.REMOVE, model.state.value.removalAction)
            requireNotNull(model.reconcileRemoveUser(original)).join()
            assertNull(model.state.value.removalOwner)
            assertEquals(PendingVpsRemove.Action.REMOVE, model.state.value.removalAction)
            assertEquals(attempt, store.pendingRemove(original))
            store.clearRejectedRemove(other, attempt)
        }
        assertTrue(revokeCommands().isEmpty())
        assertTrue(removeCommands().isEmpty())
        assertNotNull(profiles().load(original, SERVER_KEY, CLIENT_KEY))
    }

    @Test fun cancellationAfterAdmissionRetainsDurableIntentAndDrainsOwnedConnection() = runTest {
        val model = ready()
        val confirmation = prepare(model)
        peer.blockRemove = CompletableDeferred()
        val removing = requireNotNull(model.confirmRemoveUser(confirmation))
        peer.removeEntered.await()
        assertNotNull(store.pendingRemove(server()))
        removing.cancel()
        peer.blockRemove!!.complete(Unit)
        removing.join()
        assertEquals(UserRemoval.UNCERTAIN, model.state.value.removal)
        assertTrue(model.state.value.pendingRemove)
        assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        assertEquals(peer.connections.size, peer.closes.get())
    }

    @Test fun durableRemovalExcludesExistingWritersAndStrictCodecRejectsForeignFields() = runTest {
        val current = server()
        val attempt = PendingVpsRemove(current.id, VpsEndpoint(current.host, current.sshPort, current.sshUser), PIN, SERVER_KEY, "phone", CLIENT_KEY)
        store.admitRemove(current, attempt)
        assertEquals(attempt, PendingVpsRemove.decode(PendingVpsRemove.encode(listOf(attempt))).single())
        assertTrue(runCatching { store.admitAdd(current, PIN, SERVER_KEY, VpsProfileFixture.request.copy(dns = current.dns)) }
            .exceptionOrNull() is VpsServerStore.RemovePendingException)
        assertTrue(runCatching { store.admitInstall(current, attempt.ssh, VpsInstallRequest(current.endpoint, current.cidr, current.dns)) }.exceptionOrNull() is VpsServerStore.RemovePendingException)
        assertTrue(runCatching { store.admitRemove(current, attempt) }.exceptionOrNull() is VpsServerStore.RemovePendingException)
        val modified = JSONArray(PendingVpsRemove.encode(listOf(attempt))).apply { getJSONObject(0).put("privateKey", "synthetic-secret") }
        assertThrows(IOException::class.java) { PendingVpsRemove.decode(modified.toString()) }
        assertThrows(IOException::class.java) { PendingVpsRemove.decode(PendingVpsRemove.encode(listOf(attempt, attempt))) }
        assertFalse(PendingVpsRemove.encode(listOf(attempt)).contains("synthetic-password"))
    }

    @Test fun incompleteRecoveryRemainsVisibleAndCannotClaimCompletion() = runTest {
        val model = ready()
        peer.afterRemove = {}
        peer.removeFailure = ProvisioningException("synthetic mutation failure", category = ProvisioningFailureCategory.MUTATION_FAILED,
            recovery = ProvisioningRecovery.INCOMPLETE)
        requireNotNull(model.confirmRemoveUser(prepare(model))).join()
        assertEquals(ProvisioningRecovery.INCOMPLETE, model.state.value.recovery)
        assertEquals(server(), model.state.value.recoveryOwner)
        assertTrue(model.state.value.pendingRemove)
        assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
    }

    @Test fun pendingOwnerCannotBeDeletedReplacedOrImportedUnderAnotherId() = runTest {
        val current = server()
        val attempt = PendingVpsRemove(current.id, VpsEndpoint(current.host, current.sshPort, current.sshUser), PIN, SERVER_KEY, "phone", CLIENT_KEY)
        store.admitRemove(current, attempt)
        assertTrue(runCatching { store.remove(current.id) }.exceptionOrNull() is VpsServerStore.RemovePendingException)
        assertTrue(runCatching { store.upsert(current.copy(host = "192.0.2.99")) }.exceptionOrNull() is VpsServerStore.RemovePendingException)
        assertTrue(runCatching { store.clearCredential(current.id) }.exceptionOrNull() is VpsServerStore.RemovePendingException)
        assertTrue(runCatching { store.saveCredential(current, OTHER_PIN, VpsCredential.Password("replacement".toCharArray())) }.exceptionOrNull() is VpsServerStore.RemovePendingException)
        assertTrue(runCatching { store.withImportAdmission(listOf(current.copy(id = "foreign"))) { error("Import must not run") } }.exceptionOrNull() is VpsServerStore.RemovePendingException)
        var admitted = false
        store.withImportAdmission(listOf(current)) { admitted = true }
        assertTrue(admitted)
        store.saveCredential(current, PIN, VpsCredential.Password("refreshed".toCharArray()))
        assertEquals(attempt, store.pendingRemove(current))
        assertEquals(current, server())
    }

    @Test fun confirmedRevokeRequiresArchiveAndAbsenceBeforeDeletingOnlyExactProfile() = runTest {
        val unrelated = VpsProfileFixture.server.copy(id = "unrelated")
        profiles().saveGenerated(unrelated, SERVER_KEY, VpsProfileFixture.request, VpsProfileFixture.result())
        for (running in listOf(true, false)) {
            profiles().saveGenerated(server(), SERVER_KEY, VpsProfileFixture.request, VpsProfileFixture.result())
            peer.statusOutput = status(true, running = running, runtimeKeys = if (running) listOf(CLIENT_KEY) else emptyList())
            val model = ready()
            val confirmation = prepareRevoke(model)
            val attempt = confirmation.attempt
            peer.afterRevoke = { peer.statusOutput = status(false, running = running, archives = archives(attempt)) }
            requireNotNull(model.confirmRemoveUser(confirmation)).join()
            assertEquals(UserRemoval.REMOVED, model.state.value.removal)
            assertEquals(PendingVpsRemove.Action.REVOKE, model.state.value.removalAction)
            assertFalse(model.state.value.pendingRemove)
            assertNull(store.pendingRemove(server()))
            assertNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
            assertNotNull(profiles().load(unrelated, SERVER_KEY, CLIENT_KEY))
            val command = revokeCommands().last()
            assertTrue(command.contains("'--expected-public-key' ${CLIENT_KEY.shellQuote()}"))
            assertTrue(command.contains("'--expected-server-public-key' ${SERVER_KEY.shellQuote()}"))
            assertTrue(command.contains("'--operation-id' ${requireNotNull(attempt.operationId).shellQuote()}"))
        }
        assertTrue(removeCommands().isEmpty())
        assertEquals(peer.connections.size, peer.closes.get())
    }

    @Test fun revokeDismissAndPreAdmissionCancellationKeepProfileWithoutJournalOrWriter() = runTest {
        val model = ready()
        val dismissed = prepareRevoke(model)
        model.dismissRemoveUserConfirmation()
        assertNull(model.confirmRemoveUser(dismissed))
        val confirmation = prepareRevoke(model)
        peer.uploadEntered = CompletableDeferred()
        peer.blockUpload = CompletableDeferred()
        val job = requireNotNull(model.confirmRemoveUser(confirmation))
        peer.uploadEntered.await()
        job.cancelAndJoin()
        assertTrue(revokeCommands().isEmpty())
        assertNull(store.pendingRemove(server()))
        assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
    }

    @Test fun unknownHistoryRejectsFreshRevokeWithoutInventingPendingIntent() = runTest {
        val model = ready()
        peer.statusOutput = status(true, archives = null)
        requireNotNull(model.requestRevokeUserConfirmation(server(), "phone", CLIENT_KEY)).join()
        assertEquals(Problem.ARCHIVE_UNAVAILABLE, model.state.value.problem)
        assertNull(model.state.value.removeConfirmation)
        assertFalse(model.state.value.pendingRemove)
        assertNull(store.pendingRemove(server()))
        assertTrue(revokeCommands().isEmpty())
    }

    @Test fun lostRevokeResponseRetainsOperationAcrossReconstructionAndReconcilesWithoutWriter() = runTest {
        val model = ready()
        val confirmation = prepareRevoke(model)
        peer.afterRevoke = { peer.statusOutput = status(false, archives = archives(confirmation.attempt)) }
        peer.revokeFailure = ProvisioningException("synthetic lost response")
        requireNotNull(model.confirmRemoveUser(confirmation)).join()
        assertEquals(confirmation.attempt, store.pendingRemove(server()))
        assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        val recreated = model(VpsServerStore(preferences, credentialFile))
        requireNotNull(recreated.syncStatus(server())).join()
        assertEquals(PendingVpsRemove.Action.REVOKE, recreated.state.value.removalAction)
        requireNotNull(recreated.reconcileRemoveUser(server())).join()
        assertEquals(UserRemoval.OBSERVED, recreated.state.value.removal)
        assertNull(store.pendingRemove(server()))
        assertNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        assertEquals(1, revokeCommands().size)
    }

    @Test fun revokeCannotSettleAbsentPeerWithoutExactCompleteSameOperationArchive() = runTest {
        val model = ready()
        val confirmation = prepareRevoke(model)
        val attempt = confirmation.attempt
        peer.afterRevoke = { peer.statusOutput = status(false) }
        requireNotNull(model.confirmRemoveUser(confirmation)).join()
        val wrongOperation = attempt.copy(operationId = "00112233-4455-4677-8899-aabbccddeeff")
        val wrongName = attempt.copy(name = "other")
        val wrongKey = attempt.copy(clientPublicKey = OTHER_KEY)
        val duplicate = archives(attempt).put(archives(attempt).getJSONObject(0))
        for (observation in listOf(
            status(false, archives = null), status(false), status(false, archives = archives(wrongOperation)),
            status(false, archives = archives(wrongName)), status(false, archives = archives(wrongKey)),
            status(false, archives = duplicate), status(true, archives = archives(attempt)),
            status(false, runtimeKeys = listOf(CLIENT_KEY), archives = archives(attempt)),
            status(false, configuredKeys = null, archives = archives(attempt)),
        )) {
            peer.statusOutput = observation
            requireNotNull(model.reconcileRemoveUser(server())).join()
            assertTrue(model.state.value.pendingRemove)
            assertEquals(UserRemoval.UNCERTAIN, model.state.value.removal)
            assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        }
        peer.statusOutput = status(false, archives = archives(attempt))
        requireNotNull(model.reconcileRemoveUser(server())).join()
        assertEquals(UserRemoval.OBSERVED, model.state.value.removal)
        assertNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        assertEquals(1, revokeCommands().size)
    }

    @Test fun admittedRevokeCancellationRetainsJournalAndProfileAndClosesConnection() = runTest {
        val model = ready()
        val confirmation = prepareRevoke(model)
        peer.blockRevoke = CompletableDeferred()
        val job = requireNotNull(model.confirmRemoveUser(confirmation))
        peer.revokeEntered.await()
        assertEquals(confirmation.attempt, store.pendingRemove(server()))
        job.cancel()
        peer.blockRevoke!!.complete(Unit)
        job.join()
        assertEquals(UserRemoval.UNCERTAIN, model.state.value.removal)
        assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        assertEquals(peer.connections.size, peer.closes.get())
    }

    @Test fun invalidRevokeReceiptCannotDeleteProfileEvenWhenLaterStatusWouldProveCompletion() = runTest {
        val model = ready()
        val confirmation = prepareRevoke(model)
        peer.revokeOutput = JSONObject(requireNotNull(peer.revokeOutput)).put("operationId", "00112233-4455-4677-8899-aabbccddeeff").toString()
        peer.afterRevoke = { peer.statusOutput = status(false, archives = archives(confirmation.attempt)) }
        requireNotNull(model.confirmRemoveUser(confirmation)).join()
        assertEquals(Problem.INVALID_RESPONSE, model.state.value.problem)
        assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        assertEquals(confirmation.attempt, store.pendingRemove(server()))
        requireNotNull(model.reconcileRemoveUser(server())).join()
        assertEquals(UserRemoval.OBSERVED, model.state.value.removal)
        assertEquals(1, revokeCommands().size)
    }

    @Test fun revokeProfileStorageFailureRetainsIntentForReadOnlySettlementRetry() = runTest {
        val model = ready()
        val confirmation = prepareRevoke(model)
        peer.afterRevoke = { peer.statusOutput = status(false, archives = archives(confirmation.attempt)) }
        failProfileSync = true
        requireNotNull(model.confirmRemoveUser(confirmation)).join()
        assertEquals(Problem.STORAGE_FAILED, model.state.value.problem)
        assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        assertNotNull(store.pendingRemove(server()))
        failProfileSync = false
        requireNotNull(model.reconcileRemoveUser(server())).join()
        assertNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        assertNull(store.pendingRemove(server()))
        assertEquals(1, revokeCommands().size)
    }

    @Test fun revokeJournalKeepsLegacyRemoveSchemaAndRejectsInvalidActionCorrelation() = runTest {
        val remove = PendingVpsRemove(server().id, VpsEndpoint(server().host, server().sshPort, server().sshUser), PIN, SERVER_KEY, "phone", CLIENT_KEY)
        val legacy = PendingVpsRemove.encode(listOf(remove))
        assertFalse(legacy.contains("operationId"))
        assertFalse(legacy.contains("action"))
        assertEquals(remove, PendingVpsRemove.decode(legacy).single())
        val revoke = remove.copy(action = PendingVpsRemove.Action.REVOKE, operationId = "00112233-4455-4677-8899-aabbccddeeff")
        val encoded = PendingVpsRemove.encode(listOf(revoke))
        assertEquals(revoke, PendingVpsRemove.decode(encoded).single())
        for (invalid in listOf(
            JSONArray(encoded).apply { getJSONObject(0).put("action", "remove") },
            JSONArray(encoded).apply { getJSONObject(0).put("operationId", revoke.operationId!!.uppercase()) },
            JSONArray(encoded).apply { getJSONObject(0).remove("operationId") },
            JSONArray(legacy).apply { getJSONObject(0).put("operationId", revoke.operationId) },
        )) assertThrows(IOException::class.java) { PendingVpsRemove.decode(invalid.toString()) }
        store.admitRemove(server(), revoke)
        var deleted = false
        assertTrue(runCatching {
            store.settleRemove(server(), revoke, ProvisioningJson.parseStatus(status(false)), OBSERVED_AT) { deleted = true }
        }.exceptionOrNull() is VpsServerStore.RemovePendingException)
        assertFalse(deleted)
        assertEquals(revoke, store.pendingRemove(server()))
    }

    @Test fun receivedTypedUnchangedOrRestoredPeerFailureSettlesFailedAndRequiresNewReviewForBothActions() = runTest {
        for (action in PendingVpsRemove.Action.entries) for (recovery in listOf("unchanged", "restored")) {
            peer.statusOutput = status(true)
            val model = ready()
            val confirmation = if (action == PendingVpsRemove.Action.REMOVE) prepare(model) else prepareRevoke(model)
            val failure = JSONObject().put("ok", false).put("error", "apply_failed").put("message", "synthetic failure")
                .put("recovery", recovery).toString()
            if (action == PendingVpsRemove.Action.REMOVE) {
                peer.afterRemove = {}
                peer.removeOutput = failure
                peer.removeExitCode = 74
            } else {
                peer.afterRevoke = {}
                peer.revokeOutput = failure
                peer.revokeExitCode = 74
            }
            requireNotNull(model.confirmRemoveUser(confirmation)).join()
            assertEquals(UserRemoval.FAILED, model.state.value.removal)
            assertFalse(model.state.value.pendingRemove)
            assertNull(store.pendingRemove(server()))
            assertNull(model.state.value.removalAttempt)
            assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
            assertFalse(model.state.value.statusSnapshots.containsKey(server().id))
            assertNull(model.confirmRemoveUser(confirmation))
            assertEquals(if (recovery == "unchanged") ProvisioningRecovery.UNCHANGED else ProvisioningRecovery.RESTORED,
                model.state.value.recovery)
            peer.removeExitCode = 0
            peer.revokeExitCode = 0
        }
        assertEquals(4, removeCommands().size + revokeCommands().size)
    }

    @Test fun typedCleanupFailureAfterValidReceiptCannotClearPeerIntentOrPublishRollbackForBothActions() = runTest {
        for (action in PendingVpsRemove.Action.entries) for (recovery in listOf(ProvisioningRecovery.UNCHANGED, ProvisioningRecovery.RESTORED)) {
            peer.statusOutput = status(true)
            val model = ready()
            val confirmation = if (action == PendingVpsRemove.Action.REMOVE) prepare(model) else prepareRevoke(model)
            peer.afterRemove = {}
            peer.afterRevoke = {}
            peer.removeOutput = JSONObject().put("ok", true).put("name", "phone").put("publicKey", CLIENT_KEY)
                .put("removed", true).put("revoked", false).toString()
            peer.closeFailure = ProvisioningException("synthetic close failure", category = ProvisioningFailureCategory.MUTATION_FAILED, recovery = recovery)
            requireNotNull(model.confirmRemoveUser(confirmation)).join()
            peer.closeFailure = null
            assertTrue(model.state.value.pendingRemove)
            assertEquals(UserRemoval.UNCERTAIN, model.state.value.removal)
            assertNull(model.state.value.recovery)
            assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
            assertEquals(confirmation.attempt, store.pendingRemove(server()))
            peer.statusOutput = if (action == PendingVpsRemove.Action.REVOKE) status(false, archives = archives(confirmation.attempt)) else status(false)
            requireNotNull(model.reconcileRemoveUser(server())).join()
            profiles().saveGenerated(server(), SERVER_KEY, VpsProfileFixture.request, VpsProfileFixture.result())
        }
        assertEquals(4, removeCommands().size + revokeCommands().size)
    }

    @Test fun typedLaterStatusFailureCannotBeMistakenForRollbackAfterReceipt() = runTest {
        for (action in PendingVpsRemove.Action.entries) {
            peer.statusOutput = status(true)
            val model = ready()
            val confirmation = if (action == PendingVpsRemove.Action.REMOVE) prepare(model) else prepareRevoke(model)
            val failure = ProvisioningException("synthetic later status failure", category = ProvisioningFailureCategory.MUTATION_FAILED,
                recovery = ProvisioningRecovery.RESTORED)
            if (action == PendingVpsRemove.Action.REMOVE) peer.afterRemove = { peer.statusFailure = failure }
            else peer.afterRevoke = { peer.statusFailure = failure }
            requireNotNull(model.confirmRemoveUser(confirmation)).join()
            assertTrue(model.state.value.pendingRemove)
            assertNull(model.state.value.recovery)
            assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
            assertEquals(confirmation.attempt, store.pendingRemove(server()))
            peer.statusFailure = null
            peer.statusOutput = if (action == PendingVpsRemove.Action.REVOKE) status(false, archives = archives(confirmation.attempt)) else status(false)
            requireNotNull(model.reconcileRemoveUser(server())).join()
            profiles().saveGenerated(server(), SERVER_KEY, VpsProfileFixture.request, VpsProfileFixture.result())
        }
    }

    @Test fun typedRollbackCannotClearChangedLocalOwnerOrLoseProfile() = runTest {
        val model = ready()
        val original = server()
        val confirmation = prepareRevoke(model)
        peer.afterRevoke = { runBlocking { preferences.edit { it[VpsServerStore.SERVERS] = VpsServerStore.encodeServers(listOf(original.copy(name = "changed"))) } } }
        peer.revokeOutput = JSONObject().put("ok", false).put("error", "apply_failed").put("message", "synthetic failure")
            .put("recovery", "restored").toString()
        peer.revokeExitCode = 74
        requireNotNull(model.confirmRemoveUser(confirmation)).join()
        assertEquals(Problem.CONNECTION_CHANGED, model.state.value.problem)
        assertTrue(model.state.value.pendingRemove)
        assertEquals(confirmation.attempt, store.pendingRemove(server()))
        assertNotNull(profiles().load(original, SERVER_KEY, CLIENT_KEY))
    }

    @Test fun absentUnknownOrIncompleteRollbackReceiptNeverClearsEitherActionIntent() = runTest {
        for (action in PendingVpsRemove.Action.entries) for (recovery in listOf(null, "unknown-recovery", "incomplete")) {
            peer.statusOutput = status(true)
            val model = ready()
            val confirmation = if (action == PendingVpsRemove.Action.REMOVE) prepare(model) else prepareRevoke(model)
            val failure = JSONObject().put("ok", false).put("error", "apply_failed").put("message", "synthetic failure")
            if (recovery != null) failure.put("recovery", recovery)
            if (action == PendingVpsRemove.Action.REMOVE) {
                peer.afterRemove = {}; peer.removeOutput = failure.toString(); peer.removeExitCode = 74
            } else {
                peer.afterRevoke = {}; peer.revokeOutput = failure.toString(); peer.revokeExitCode = 74
            }
            requireNotNull(model.confirmRemoveUser(confirmation)).join()
            assertTrue(model.state.value.pendingRemove)
            assertEquals(UserRemoval.UNCERTAIN, model.state.value.removal)
            assertEquals(confirmation.attempt, store.pendingRemove(server()))
            assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
            peer.statusOutput = if (action == PendingVpsRemove.Action.REVOKE) status(false, archives = archives(confirmation.attempt)) else status(false)
            requireNotNull(model.reconcileRemoveUser(server())).join()
            profiles().saveGenerated(server(), SERVER_KEY, VpsProfileFixture.request, VpsProfileFixture.result())
            peer.removeExitCode = 0; peer.revokeExitCode = 0
        }
        assertEquals(6, removeCommands().size + revokeCommands().size)
    }

    @Test fun knownArchivePretransactionRejectionsSettleOnlyActualRevokeDispatchAsFailed() = runTest {
        for (action in PendingVpsRemove.Action.entries) for (code in listOf("archive_inventory_unknown", "archive_limit", "operation_id_conflict")) {
            peer.statusOutput = status(true)
            val model = ready()
            val confirmation = if (action == PendingVpsRemove.Action.REMOVE) prepare(model) else prepareRevoke(model)
            val failure = JSONObject().put("ok", false).put("error", code).put("message", "synthetic rejection").toString()
            if (action == PendingVpsRemove.Action.REMOVE) {
                peer.afterRemove = {}; peer.removeOutput = failure; peer.removeExitCode = 73
            } else {
                peer.afterRevoke = {}; peer.revokeOutput = failure; peer.revokeExitCode = 73
            }
            requireNotNull(model.confirmRemoveUser(confirmation)).join()
            assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
            if (action == PendingVpsRemove.Action.REVOKE) {
                assertEquals(UserRemoval.FAILED, model.state.value.removal)
                assertFalse(model.state.value.pendingRemove)
                assertNull(store.pendingRemove(server()))
                assertFalse(model.state.value.statusSnapshots.containsKey(server().id))
            } else {
                assertEquals(UserRemoval.UNCERTAIN, model.state.value.removal)
                assertEquals(confirmation.attempt, store.pendingRemove(server()))
                peer.statusOutput = status(false)
                requireNotNull(model.reconcileRemoveUser(server())).join()
                profiles().saveGenerated(server(), SERVER_KEY, VpsProfileFixture.request, VpsProfileFixture.result())
            }
            peer.removeExitCode = 0; peer.revokeExitCode = 0
        }
        assertEquals(6, removeCommands().size + revokeCommands().size)
    }

    @Test fun archiveRejectionFromCleanupOrLaterStatusAfterReceiptCannotClearRevoke() = runTest {
        for (code in listOf("archive_inventory_unknown", "archive_limit", "operation_id_conflict")) for (cleanup in listOf(true, false)) {
            peer.statusOutput = status(true)
            val model = ready()
            val confirmation = prepareRevoke(model)
            val failure = ProvisioningException("synthetic late rejection", category = ProvisioningFailureCategory.CONFLICT, remoteCode = code)
            peer.afterRevoke = if (cleanup) ({}) else ({ peer.statusFailure = failure })
            if (cleanup) peer.closeFailure = failure
            requireNotNull(model.confirmRemoveUser(confirmation)).join()
            peer.closeFailure = null; peer.statusFailure = null
            assertTrue(model.state.value.pendingRemove)
            assertEquals(confirmation.attempt, store.pendingRemove(server()))
            assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
            peer.statusOutput = status(false, archives = archives(confirmation.attempt))
            requireNotNull(model.reconcileRemoveUser(server())).join()
            profiles().saveGenerated(server(), SERVER_KEY, VpsProfileFixture.request, VpsProfileFixture.result())
        }
        assertEquals(6, revokeCommands().size)
    }

    @Test fun fullKnownArchiveHistoryRejectsFreshRevokeBeforeIntent() = runTest {
        val model = ready()
        val full = JSONArray().apply {
            repeat(VpsBackupResult.MAX_REVOKED_FILES) { index -> put(JSONObject().put("archiveName", "old$index.conf")
                .put("name", "old$index").put("publicKey", CLIENT_KEY).put("operationId", JSONObject.NULL)) }
        }
        peer.statusOutput = status(true, archives = full)
        requireNotNull(model.requestRevokeUserConfirmation(server(), "phone", CLIENT_KEY)).join()
        assertEquals(Problem.ARCHIVE_FULL, model.state.value.problem)
        assertNull(model.state.value.removeConfirmation)
        assertFalse(model.state.value.pendingRemove)
        assertNull(store.pendingRemove(server()))
        assertTrue(revokeCommands().isEmpty())
    }

    private suspend fun prepareRevoke(model: VpsProvisioningViewModel): VpsProvisioningViewModel.RemoveUserConfirmation {
        requireNotNull(model.requestRevokeUserConfirmation(server(), "phone", CLIENT_KEY)).join()
        return requireNotNull(model.state.value.removeConfirmation).also { confirmation ->
            peer.revokeOutput = JSONObject().put("ok", true).put("name", "phone").put("publicKey", CLIENT_KEY)
                .put("removed", true).put("revoked", true).put("archiveName", confirmation.attempt.archiveName)
                .put("archive", "/fixture/revoked/${confirmation.attempt.archiveName}")
                .put("operationId", confirmation.attempt.operationId).toString()
        }
    }

    private fun archives(attempt: PendingVpsRemove): JSONArray = JSONArray().put(JSONObject()
        .put("archiveName", attempt.archiveName).put("name", attempt.name).put("publicKey", attempt.clientPublicKey).put("operationId", attempt.operationId))
    private fun revokeCommands() = peer.commands.filter { it.contains("./'revoke-peer.sh'") }
    private suspend fun ready() = model().also { requireNotNull(it.syncStatus(server())).join() }
    private fun model(serverStore: VpsServerStore = store) = VpsProvisioningViewModel(
        ApplicationProvider.getApplicationContext(), serverStore, peer, { OBSERVED_AT }, profiles(),
    ).also {
        jobs.add(requireNotNull(it.viewModelScope.coroutineContext[Job]))
        owners.add(ViewModelStore().apply { put("owner", it) })
    }
    private suspend fun prepare(model: VpsProvisioningViewModel): VpsProvisioningViewModel.RemoveUserConfirmation {
        requireNotNull(model.requestRemoveUserConfirmation(server(), "phone", CLIENT_KEY)).join()
        return requireNotNull(model.state.value.removeConfirmation)
    }
    private suspend fun server() = store.servers.first().single()
    private fun profiles() = VpsUserProfileStore(VpsUserProfileFile(profileFile, { profileKey }, sync = { stream ->
        beforeProfileSync?.invoke()
        if (failProfileSync) throw IOException("synthetic profile sync failure")
        stream.fd.sync()
    }))
    private fun removeCommands() = peer.commands.filter { it.contains("./'remove-peer.sh'") }
    private fun status(hasPeer: Boolean, running: Boolean? = true, clientKey: String = CLIENT_KEY, serverKey: String = SERVER_KEY,
        runtimeKeys: List<String>? = if (hasPeer) listOf(clientKey) else emptyList(),
        configuredKeys: List<String>? = if (hasPeer) listOf(clientKey) else emptyList(), archives: JSONArray? = JSONArray()): String = JSONObject()
        .put("ok", true).put("interface", "wg0").put("running", running ?: JSONObject.NULL)
        .put("endpoint", VpsProfileFixture.server.endpoint).put("listenPort", "51820").put("cidr", "10.8.0.0/24")
        .put("dns", "1.1.1.1").put("serverPublicKey", serverKey)
        .put("runtimePeerPublicKeys", runtimeKeys?.let(::JSONArray) ?: JSONObject.NULL)
        .put("configuredPeerPublicKeys", configuredKeys?.let(::JSONArray) ?: JSONObject.NULL)
        .put("revokedArchives", archives ?: JSONObject.NULL)
        .put("peers", JSONArray().apply {
            if (hasPeer) put(JSONObject().put("name", "phone").put("publicKey", clientKey).put("allowedIps", "10.8.0.2/32")
                .put("latestHandshake", JSONObject.NULL).put("rxBytes", JSONObject.NULL).put("txBytes", JSONObject.NULL))
        }).toString()

    private companion object {
        const val OBSERVED_AT = 1_710_000_012L
        val SERVER_KEY = VpsProfileFixture.serverKey.publicKey.toBase64()
        val CLIENT_KEY = VpsProfileFixture.client.publicKey.toBase64()
        val OTHER_KEY = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 20).toByte() })
        val PIN = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        val OTHER_PIN = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() })
    }
}
