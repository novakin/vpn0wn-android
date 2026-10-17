/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.Problem
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.UserRotation
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
class VpsUserRotationTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var preferences: DataStore<Preferences>
    private lateinit var scope: CoroutineScope
    private lateinit var store: VpsServerStore
    private lateinit var profileFile: File
    private lateinit var profileKey: SecretKey
    private val owners = mutableListOf<ViewModelStore>()
    private val jobs = mutableListOf<Job>()
    private val peer = VpsPlanningTestPeer()
    private var failProfileSync = false
    private var profileWrites = 0
    private var failProfileWriteAt: Int? = null

    @Before fun setUp() = runBlocking {
        Dispatchers.setMain(StandardTestDispatcher())
        VpsUserProfileFileTest.resetNativeFiles()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }
        store = VpsServerStore(preferences, credentialFileForTest(File(folder.root, "credentials")))
        profileFile = File(folder.root, "profiles")
        profileKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        store.upsert(VpsProfileFixture.server)
        store.saveCredential(VpsProfileFixture.server, PIN, VpsCredential.Password("synthetic-password".toCharArray()))
        profiles().saveGenerated(VpsProfileFixture.server, SERVER_KEY, VpsProfileFixture.request, VpsProfileFixture.result())
        peer.statusOutput = status()
    }

    @After fun tearDown() = runTest {
        withContext(NonCancellable) {
            peer.blockRotate?.complete(Unit)
            peer.blockVerifyRotation?.complete(Unit)
            peer.blockUpload?.complete(Unit)
            peer.blockStatus?.complete(Unit)
            owners.forEach(ViewModelStore::clear)
            try { jobs.forEach { it.cancelAndJoin() } } finally {
                try { scope.coroutineContext[Job]!!.cancelAndJoin() } finally { Dispatchers.resetMain() }
            }
        }
    }

    @Test fun rotationReplacesOnlyClientKeyAfterSeparateVerificationAndDropsOldSnapshot() = runTest {
        val old = requireNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        val unrelated = server().copy(id = "unrelated")
        profiles().saveGenerated(unrelated, SERVER_KEY, VpsProfileFixture.request, VpsProfileFixture.result())
        val model = ready()
        requireNotNull(model.confirmRotateUser(prepare(model))).join()
        val current = server()
        val rotated = requireNotNull(profiles().load(current, SERVER_KEY, requireNotNull(peer.rotatedPublicKey)))
        assertTrue(old.preservesSettings(rotated))
        assertNotEquals(old.config.`interface`.keyPair.privateKey, rotated.config.`interface`.keyPair.privateKey)
        assertNull(profiles().load(current, SERVER_KEY, CLIENT_KEY))
        assertNotNull(profiles().load(unrelated, SERVER_KEY, CLIENT_KEY))
        assertNull(store.pendingRotation(current))
        assertEquals(UserRotation.ROTATED, model.state.value.rotation)
        assertFalse(model.state.value.pendingRotation)
        assertFalse(model.state.value.statusSnapshots.containsKey(current.id))
        assertEquals(1, rotates().size)
        assertEquals(1, verifies().size)
        assertEquals(true, current.running)
    }

    @Test fun knownStoppedRotationKeepsStoppedState() = runTest {
        peer.statusOutput = status(running = false)
        peer.rotationRunning = false
        val model = ready()
        requireNotNull(model.confirmRotateUser(prepare(model))).join()
        assertEquals(UserRotation.ROTATED, model.state.value.rotation)
        assertEquals(false, server().running)
    }

    @Test fun missingOrDriftedPrivateProfileCannotCreateIntentOrDispatch() = runTest {
        val model = ready()
        peer.statusOutput = status(address = "10.8.0.9/32")
        requireNotNull(model.requestRotateUserConfirmation(server(), "phone", CLIENT_KEY)).join()
        assertEquals(Problem.PROFILE_UNAVAILABLE, model.state.value.problem)
        assertNull(model.state.value.rotateConfirmation)
        peer.statusOutput = status()
        profiles().delete(requireNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY)))
        requireNotNull(model.requestRotateUserConfirmation(server(), "phone", CLIENT_KEY)).join()
        assertEquals(Problem.PROFILE_UNAVAILABLE, model.state.value.problem)
        assertNull(store.pendingRotation(server()))
        assertTrue(rotates().isEmpty())
    }

    @Test fun copiedDismissedAndConsumedConfirmationsCannotDispatch() = runTest {
        val model = ready()
        val confirmation = prepare(model)
        assertNull(model.confirmRotateUser(confirmation.copy()))
        model.dismissRotateUserConfirmation()
        assertNull(model.confirmRotateUser(confirmation))
        val accepted = prepare(model)
        val rotating = requireNotNull(model.confirmRotateUser(accepted))
        assertNull(model.confirmRotateUser(accepted))
        rotating.join()
        assertEquals(1, rotates().size)
    }

    @Test fun changedPinAfterConfirmationFailsBeforeStageOrNetwork() = runTest {
        val model = ready()
        val confirmation = prepare(model)
        store.saveCredential(server(), OTHER_PIN, VpsCredential.Password(charArrayOf()))
        requireNotNull(model.confirmRotateUser(confirmation)).join()
        assertEquals(Problem.CONNECTION_CHANGED, model.state.value.problem)
        assertNull(store.pendingRotation(server()))
        assertTrue(rotates().isEmpty())
        assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
    }

    @Test fun cancelledUploadRetiresPreparedProposalWithoutRemoteDispatch() = runTest {
        val model = ready()
        val confirmation = prepare(model)
        peer.uploadEntered = CompletableDeferred()
        peer.blockUpload = CompletableDeferred()
        val rotating = requireNotNull(model.confirmRotateUser(confirmation))
        peer.uploadEntered.await()
        assertEquals(PendingVpsRotate.Phase.PREPARED, store.pendingRotation(server())?.phase)
        rotating.cancelAndJoin()
        assertNull(store.pendingRotation(server()))
        assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        assertTrue(rotates().isEmpty())
        assertTrue(verifies().isEmpty())
    }

    @Test fun reconstructedPreparedIntentRetiresLocallyAndRejectsLateDispatch() = runTest {
        val original = server()
        val old = requireNotNull(profiles().load(original, SERVER_KEY, CLIENT_KEY))
        val attempt = store.admitRotation(original, PIN, old, profiles())
        val recovered = model()
        requireNotNull(recovered.reconcileUserRotation(original)).join()
        assertEquals(UserRotation.FAILED, recovered.state.value.rotation)
        assertNull(store.pendingRotation(original))
        assertTrue(runCatching { store.dispatchRotation(original, attempt, profiles()) }.isFailure)
        assertTrue(rotates().isEmpty())
        assertTrue(verifies().isEmpty())
    }

    @Test fun lostMutationReplyRetainsProposalThenRecreationVerifiesWithoutResendOrRegeneration() = runTest {
        val model = ready()
        peer.rotateFailure = ProvisioningException("synthetic lost reply")
        requireNotNull(model.confirmRotateUser(prepare(model))).join()
        val attempt = requireNotNull(store.pendingRotation(server()))
        assertEquals(PendingVpsRotate.Phase.DISPATCHED, attempt.phase)
        assertEquals(UserRotation.UNCERTAIN, model.state.value.rotation)
        assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        assertNull(profiles().load(server(), SERVER_KEY, attempt.newClientPublicKey))
        val proposed = profiles().loadRotation(attempt)
        val recovered = model()
        requireNotNull(recovered.reconcileUserRotation(server())).join()
        val retained = requireNotNull(profiles().load(server(), SERVER_KEY, attempt.newClientPublicKey))
        assertTrue(proposed.sameContent(retained))
        assertEquals(UserRotation.OBSERVED, recovered.state.value.rotation)
        assertEquals(1, rotates().size)
        assertEquals(1, verifies().size)
    }

    @Test fun malformedReplyAndUnknownRollbackRemainUncertain() = runTest {
        for (output in listOf("not-json", failure("apply_failed", "incomplete"))) {
            val model = ready()
            peer.rotateOutput = output
            peer.rotateExitCode = if (output == "not-json") 0 else 74
            requireNotNull(model.confirmRotateUser(prepare(model))).join()
            assertEquals(UserRotation.UNCERTAIN, model.state.value.rotation)
            assertEquals(PendingVpsRotate.Phase.DISPATCHED, store.pendingRotation(server())?.phase)
            assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
            requireNotNull(model().reconcileUserRotation(server())).join()
            restoreFixture()
        }
        assertEquals(2, rotates().size)
    }

    @Test fun actualMutationRejectionAndCheckedRollbackRetireOnlyProposedProfile() = runTest {
        for ((code, recovery) in listOf("new_peer_exists" to null, "apply_failed" to "unchanged", "apply_failed" to "restored")) {
            val model = ready()
            peer.rotateOutput = failure(code, recovery)
            peer.rotateExitCode = 74
            requireNotNull(model.confirmRotateUser(prepare(model))).join()
            assertEquals(UserRotation.FAILED, model.state.value.rotation)
            assertFalse(model.state.value.pendingRotation)
            assertNull(store.pendingRotation(server()))
            assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        }
        assertEquals(3, rotates().size)
        assertTrue(verifies().isEmpty())
    }

    @Test fun verifyFailureCannotBorrowRollbackOrRejectionProvenance() = runTest {
        for (failure in listOf(
            ProvisioningException("synthetic old-only state", category = ProvisioningFailureCategory.CONFLICT, remoteCode = "rotation_mismatch"),
            ProvisioningException("synthetic verify failure", category = ProvisioningFailureCategory.MUTATION_FAILED, recovery = ProvisioningRecovery.RESTORED),
        )) {
            val model = ready()
            peer.verifyRotationFailure = failure
            requireNotNull(model.confirmRotateUser(prepare(model))).join()
            val attempt = requireNotNull(store.pendingRotation(server()))
            assertEquals(PendingVpsRotate.Phase.DISPATCHED, attempt.phase)
            requireNotNull(model().reconcileUserRotation(server())).join()
            assertEquals(attempt, store.pendingRotation(server()))
            assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
            peer.verifyRotationFailure = null
            requireNotNull(model().reconcileUserRotation(server())).join()
            restoreFixture()
        }
        assertEquals(2, rotates().size)
    }

    @Test fun successfulReceiptThenCloseFailureRemainsDispatchedUntilReadOnlyRecovery() = runTest {
        val model = ready()
        val confirmation = prepare(model)
        peer.closeFailure = ProvisioningException("synthetic late failure", category = ProvisioningFailureCategory.MUTATION_FAILED,
            recovery = ProvisioningRecovery.RESTORED)
        requireNotNull(model.confirmRotateUser(confirmation)).join()
        assertEquals(PendingVpsRotate.Phase.DISPATCHED, store.pendingRotation(server())?.phase)
        assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        assertTrue(verifies().isEmpty())
        peer.closeFailure = null
        requireNotNull(model().reconcileUserRotation(server())).join()
        assertEquals(1, rotates().size)
        assertEquals(1, verifies().size)
    }

    @Test fun failedVaultSettlementPreservesBothProfilesAndCanRecover() = runTest {
        val model = ready()
        peer.afterRotate = { failProfileSync = true }
        requireNotNull(model.confirmRotateUser(prepare(model))).join()
        val attempt = requireNotNull(store.pendingRotation(server()))
        assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        assertEquals(attempt.newClientPublicKey, profiles().loadRotation(attempt).identity.clientPublicKey)
        failProfileSync = false
        requireNotNull(model().reconcileUserRotation(server())).join()
        assertNull(store.pendingRotation(server()))
        assertNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
    }

    @Test fun cleanupFailureAfterCommittedSettlementRetiresStaleFeedbackWithoutExtraNetwork() = runTest {
        val model = ready()
        peer.afterRotate = { failProfileWriteAt = profileWrites + 2 }
        requireNotNull(model.confirmRotateUser(prepare(model))).join()
        val current = server()
        val newKey = requireNotNull(peer.rotatedPublicKey)
        assertEquals(UserRotation.UNCERTAIN, model.state.value.rotation)
        assertTrue(model.state.value.pendingRotation)
        assertNull(store.pendingRotation(current))
        assertNull(profiles().load(current, SERVER_KEY, CLIENT_KEY))
        assertNotNull(requireNotNull(profiles().load(current, SERVER_KEY, newKey)).rotation)
        failProfileWriteAt = null
        requireNotNull(model.reconcileUserRotation(current)).join()
        assertEquals(UserRotation.NONE, model.state.value.rotation)
        assertFalse(model.state.value.pendingRotation)
        assertNull(model.state.value.rotationOwner)
        assertNull(model.state.value.rotationAttempt)
        assertNull(requireNotNull(profiles().load(current, SERVER_KEY, newKey)).rotation)
        assertFalse(model.state.value.statusSnapshots.containsKey(current.id))
        assertEquals(1, rotates().size)
        assertEquals(1, verifies().size)
    }

    @Test fun reconstructionAfterVaultCommitNeedsNoOldPrivateProfile() = runTest {
        val original = server()
        val old = requireNotNull(profiles().load(original, SERVER_KEY, CLIENT_KEY))
        val staged = store.admitRotation(original, PIN, old, profiles())
        val attempt = store.dispatchRotation(original, staged, profiles())
        profiles().settleRotation(attempt)
        assertNull(profiles().load(original, SERVER_KEY, CLIENT_KEY))
        val recovered = model()
        requireNotNull(recovered.reconcileUserRotation(original)).join()
        assertEquals(UserRotation.OBSERVED, recovered.state.value.rotation)
        assertNull(store.pendingRotation(server()))
        assertTrue(rotates().isEmpty())
        assertEquals(1, verifies().size)
    }

    @Test fun sameOwnerCredentialRefreshPreservesRecoveryButForeignOwnerGetsNoPublicIntent() = runTest {
        val original = server()
        val staged = store.admitRotation(original, PIN, requireNotNull(profiles().load(original, SERVER_KEY, CLIENT_KEY)), profiles())
        val attempt = store.dispatchRotation(original, staged, profiles())
        store.saveCredential(original, PIN, VpsCredential.Password("refreshed".toCharArray()))
        val foreign = original.copy(id = "foreign-owner")
        preferences.edit { it[VpsServerStore.SERVERS] = VpsServerStore.encodeServers(listOf(foreign)) }
        val recovered = model()
        requireNotNull(recovered.reconcileUserRotation(foreign)).join()
        assertEquals(Problem.CONNECTION_CHANGED, recovered.state.value.problem)
        assertTrue(recovered.state.value.pendingRotation)
        assertNull(recovered.state.value.rotationOwner)
        assertNull(recovered.state.value.rotationAttempt)
        assertEquals(attempt, store.pendingRotation(foreign))
        assertTrue(verifies().isEmpty())
        assertTrue(rotates().isEmpty())
        val commands = peer.commands.size
        requireNotNull(recovered.syncStatus(foreign)).join()
        assertEquals(Problem.CONNECTION_CHANGED, recovered.state.value.problem)
        assertNull(recovered.state.value.rotationOwner)
        assertNull(recovered.state.value.rotationAttempt)
        assertFalse(recovered.state.value.statusSnapshots.containsKey(foreign.id))
        assertEquals(commands, peer.commands.size)
        assertEquals(attempt, store.pendingRotation(foreign))
    }

    private suspend fun restoreFixture() {
        profiles().load(server(), SERVER_KEY, requireNotNull(peer.rotatedPublicKey))?.let { profiles().delete(it) }
        profiles().saveGenerated(server(), SERVER_KEY, VpsProfileFixture.request, VpsProfileFixture.result())
        peer.statusOutput = status()
    }
    private suspend fun ready() = model().also { requireNotNull(it.syncStatus(server())).join() }
    private fun model() = VpsProvisioningViewModel(ApplicationProvider.getApplicationContext(), store, peer, { OBSERVED_AT }, profiles()).also {
        jobs.add(requireNotNull(it.viewModelScope.coroutineContext[Job]))
        owners.add(ViewModelStore().apply { put("owner", it) })
    }
    private suspend fun prepare(model: VpsProvisioningViewModel): VpsProvisioningViewModel.RotateUserConfirmation {
        requireNotNull(model.requestRotateUserConfirmation(server(), "phone", CLIENT_KEY)).join()
        return requireNotNull(model.state.value.rotateConfirmation)
    }
    private suspend fun server() = store.servers.first().single()
    private fun profiles() = VpsUserProfileStore(VpsUserProfileFile(profileFile, { profileKey }, sync = { stream ->
        profileWrites++
        if (failProfileSync || profileWrites == failProfileWriteAt) throw IOException("synthetic profile sync failure")
        stream.fd.sync()
    }))
    private fun rotates() = peer.commands.filter { it.contains("./'rotate-peer.sh'") && !it.contains("'--verify'") }
    private fun verifies() = peer.commands.filter { it.contains("./'rotate-peer.sh'") && it.contains("'--verify'") }
    private fun failure(code: String, recovery: String?) = JSONObject().put("ok", false).put("error", code).put("message", "synthetic failure")
        .apply { recovery?.let { put("recovery", it) } }.toString()
    private fun status(running: Boolean = true, address: String = "10.8.0.2/32") = JSONObject()
        .put("ok", true).put("interface", "wg0").put("running", running).put("endpoint", VpsProfileFixture.server.endpoint)
        .put("listenPort", "51820").put("cidr", "10.8.0.0/24").put("dns", "1.1.1.1").put("serverPublicKey", SERVER_KEY)
        .put("runtimePeerPublicKeys", JSONArray(if (running) listOf(CLIENT_KEY) else emptyList<String>()))
        .put("configuredPeerPublicKeys", JSONArray(listOf(CLIENT_KEY))).put("revokedArchives", JSONArray())
        .put("peers", JSONArray().put(JSONObject().put("name", "phone").put("publicKey", CLIENT_KEY).put("allowedIps", address)
            .put("latestHandshake", JSONObject.NULL).put("rxBytes", JSONObject.NULL).put("txBytes", JSONObject.NULL))).toString()

    private companion object {
        const val OBSERVED_AT = 1_710_000_012L
        val SERVER_KEY = VpsProfileFixture.serverKey.publicKey.toBase64()
        val CLIENT_KEY = VpsProfileFixture.client.publicKey.toBase64()
        val PIN = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        val OTHER_PIN = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() })
    }
}
