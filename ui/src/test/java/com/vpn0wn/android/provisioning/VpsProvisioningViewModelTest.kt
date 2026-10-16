/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.AuthMode
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.Problem
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.SavedAccess
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.Step
import java.io.File
import java.io.IOException
import java.util.Base64
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
@Config(application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class VpsProvisioningViewModelTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var preferences: DataStore<Preferences>
    private lateinit var scope: CoroutineScope
    private lateinit var credentialFile: File
    private lateinit var store: VpsServerStore
    private val owners = mutableListOf<ViewModelStore>()
    private val modelJobs = mutableListOf<Job>()
    private val peer = VpsPlanningTestPeer()
    private val pin = fingerprint(0)

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }
        credentialFile = File(folder.root, "credentials")
        store = VpsServerStore(preferences, credentialFileForTest(credentialFile))
    }

    @After fun tearDown() = runTest {
        try {
            peer.blockDetect?.complete(Unit)
            peer.blockStatus?.complete(Unit)
            owners.forEach(ViewModelStore::clear)
        } finally {
            withContext(NonCancellable) {
                try {
                    modelJobs.forEach { it.cancelAndJoin() }
                } finally {
                    try { scope.coroutineContext[Job]!!.cancelAndJoin() } finally { Dispatchers.resetMain() }
                }
            }
        }
    }

    @Test fun savesEncryptedAccessOnlyAfterAuthenticatedResultAndDrainedClient() = runTest {
        val model = model()
        ready(model)
        peer.blockDetect = CompletableDeferred()
        val check = requireNotNull(model.detect())
        peer.detectEntered.await()
        assertTrue(store.servers.first().isEmpty())
        assertFalse(credentialFile.exists())
        peer.blockDetect!!.complete(Unit)
        check.join()

        assertEquals(Step.DETECT, model.state.value.step)
        assertEquals(SavedAccess.SAVED, model.state.value.savedAccess)
        assertEquals("debian", model.state.value.detect?.os)
        assertEquals(1, peer.closes.get())
        assertTrue((peer.connections.single().credential as VpsCredential.Password).value.all { it == '\u0000' })
        val saved = store.servers.first().single()
        assertNull(saved.running)
        assertEquals(0L, saved.lastSyncEpochSeconds)
        val connection = requireNotNull(store.loadConnection(saved))
        assertEquals("synthetic-password", (connection.credential as VpsCredential.Password).value.concatToString())
        (connection.credential as VpsCredential.Password).clear()
        assertFalse(credentialFile.readBytes().toString(Charsets.ISO_8859_1).contains("synthetic-password"))
        assertFalse(saved.toString().contains("synthetic-password"))
        assertEquals("Draft(redacted)", model.draft.toString())
    }

    @Test fun rejectedHostOrAuthenticationDoesNotSaveOrAdvance() = runTest {
        val model = model()
        ready(model)
        peer.failure = ProvisioningException("synthetic-secret-failure")
        requireNotNull(model.detect()).join()
        assertEquals(Problem.CONNECTION_FAILED, model.state.value.problem)
        assertEquals(Step.SSH, model.state.value.step)
        assertNull(model.state.value.detect)
        assertNull(model.plan())
        assertTrue(store.servers.first().isEmpty())
        assertFalse(credentialFile.exists())
        assertFalse(model.state.value.toString().contains("synthetic-secret-failure"))
        assertEquals(1, peer.closes.get())
    }

    @Test fun malformedActualDetectResponseDoesNotSaveOrExposePayload() = runTest {
        val model = model()
        ready(model)
        peer.detectOutput = "synthetic-secret-malformed-json"
        requireNotNull(model.detect()).join()
        assertEquals(Problem.INVALID_RESPONSE, model.state.value.problem)
        assertNull(model.state.value.detect)
        assertTrue(store.servers.first().isEmpty())
        assertFalse(model.state.value.toString().contains("synthetic-secret-malformed-json"))
        assertEquals(1, peer.closes.get())
    }

    @Test fun capturesKeyPassphraseAndPinInProtectedRecord() = runTest {
        val model = model()
        model.begin()
        model.edit(model.draft.copy(host = "198.51.100.42", fingerprint = pin,
            privateKey = "synthetic-private-key", passphrase = "synthetic-key-passphrase"))
        requireNotNull(model.detect()).join()
        val observed = peer.connections.single().credential as VpsCredential.PrivateKey
        assertEquals("synthetic-private-key", observed.pem)
        assertEquals("synthetic-key-passphrase", observed.passphrase)
        val loaded = requireNotNull(store.loadConnection(store.servers.first().single()))
        assertEquals(pin, loaded.hostKeyFingerprint)
        assertEquals(observed, loaded.credential)
        assertFalse(credentialFile.readBytes().toString(Charsets.ISO_8859_1).contains("synthetic-key-passphrase"))
    }

    @Test fun missingNoncanonicalPinHostnameAndInvalidPortsRejectBeforeTransport() = runTest {
        for (change in listOf<(VpsProvisioningViewModel.Draft) -> VpsProvisioningViewModel.Draft>(
            { it.copy(fingerprint = "") }, { it.copy(fingerprint = pin + "=") },
            { it.copy(host = "vpn.example") }, { it.copy(sshPort = "0") }, { it.copy(sshPort = "65536") },
        )) {
            val model = model()
            ready(model)
            model.edit(change(model.draft))
            requireNotNull(model.detect()).join()
            assertEquals(Problem.INVALID_INPUT, model.state.value.problem)
        }
        assertTrue(peer.connections.isEmpty())
        assertTrue(store.servers.first().isEmpty())
    }

    @Test fun planUsesFrozenFieldsAndNeverInstalls() = runTest {
        val model = detected()
        model.edit(model.draft.copy(endpoint = "203.0.113.9", port = "51821", cidr = "10.9.0.0/24", dns = "9.9.9.9"))
        peer.planRequest = VpsInstallRequest("203.0.113.9", "10.9.0.0/24", "9.9.9.9", 51821)
        requireNotNull(model.plan()).join()
        assertEquals(Step.PLAN, model.state.value.step)
        assertEquals("203.0.113.9", model.state.value.plan?.endpoint)
        val command = peer.commands.single { it.contains("./'plan.sh'") }
        for (value in listOf("203.0.113.9", "51821", "10.9.0.0/24", "9.9.9.9")) assertTrue(command.contains(value.shellQuote()))
        model.reviewApply()
        assertEquals(Step.APPLY, model.state.value.step)
        assertFalse(peer.commands.any { it.contains("./'install.sh'") || it.contains("./'add-peer.sh'") })
        assertEquals(2, peer.connections.size)
    }

    @Test fun mismatchedPlanCannotAdvanceOrBecomeApply() = runTest {
        val model = detected()
        peer.planRequest = peer.planRequest.copy(port = 51821)
        requireNotNull(model.plan()).join()
        assertEquals(Problem.INVALID_RESPONSE, model.state.value.problem)
        assertEquals(Step.DETECT, model.state.value.step)
        assertNull(model.state.value.plan)
        model.reviewApply()
        assertEquals(Step.DETECT, model.state.value.step)
    }

    @Test fun defaultIpv6EndpointMatchesActualScriptContractAndPlans() = runTest {
        val model = model()
        ready(model)
        model.edit(model.draft.copy(host = "2001:db8::42"))
        requireNotNull(model.detect()).join()
        val endpoint = model.draft.endpoint
        assertEquals("[2001:db8::42]", endpoint)

        val common = folder.newFile("common.sh")
        ApplicationProvider.getApplicationContext<Application>().assets.open("common.sh").use { input ->
            common.outputStream().use { output -> input.copyTo(output) }
        }
        for ((candidate, expectedExit) in listOf(endpoint to 0, "2001:db8::42" to 64)) {
            val process = ProcessBuilder("bash", "-c", ". ${common.path.shellQuote()}\nrequire_endpoint \"\$1\"", "endpoint-test", candidate).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val error = process.errorStream.bufferedReader().use { it.readText() }
            assertEquals("Endpoint contract rejected $candidate: $output$error", expectedExit, process.waitFor())
        }

        peer.planRequest = peer.planRequest.copy(endpoint = endpoint)
        requireNotNull(model.plan()).join()
        assertEquals(Step.PLAN, model.state.value.step)
        assertEquals(endpoint, model.state.value.plan?.endpoint)
        assertTrue(peer.commands.single { it.contains("./'plan.sh'") }.contains(endpoint.shellQuote()))
    }

    @Test fun unsupportedDetectStopsBeforePlanWithoutInventedHealth() = runTest {
        peer.supported = false
        val model = detected()
        assertEquals(false, model.state.value.detect?.supported)
        assertNull(model.plan())
        assertEquals(1, peer.connections.size)
        assertNull(store.servers.first().single().running)
    }

    @Test fun generatedEndpointFollowsChangedSshHostOnFreshDetect() = runTest {
        val model = detected()
        assertEquals("198.51.100.42", model.draft.endpoint)
        model.back()
        model.edit(model.draft.copy(host = "203.0.113.9"))
        requireNotNull(model.detect()).join()
        assertEquals("203.0.113.9", model.draft.endpoint)
        peer.planRequest = peer.planRequest.copy(endpoint = "203.0.113.9")
        requireNotNull(model.plan()).join()
        assertEquals("203.0.113.9", model.state.value.plan?.endpoint)
        assertTrue(peer.commands.single { it.contains("./'plan.sh'") }.contains("203.0.113.9".shellQuote()))
    }

    @Test fun freshDetectedEndpointReplacesPreviousGeneratedDefault() = runTest {
        val model = detected()
        model.back()
        peer.detectedEndpoint = "vpn.example"
        requireNotNull(model.detect()).join()
        assertEquals("vpn.example", model.draft.endpoint)
    }

    @Test fun explicitEndpointSurvivesHostAndDetectedEndpointChangesUntilCleared() = runTest {
        val model = detected()
        model.edit(model.draft.copy(endpoint = "chosen.example"))
        model.back()
        model.edit(model.draft.copy(host = "203.0.113.9"))
        peer.detectedEndpoint = "server.example"
        requireNotNull(model.detect()).join()
        assertEquals("chosen.example", model.draft.endpoint)
        model.back()
        model.edit(model.draft.copy(endpoint = ""))
        requireNotNull(model.detect()).join()
        assertEquals("server.example", model.draft.endpoint)
    }

    @Test fun rejectedBusyEndpointEditCannotFreezeAnEarlierGeneratedDefault() = runTest {
        val model = detected()
        model.back()
        val repeat = requireNotNull(model.detect())
        model.edit(model.draft.copy(endpoint = "ignored.example"))
        repeat.join()
        model.back()
        model.edit(model.draft.copy(host = "203.0.113.9"))
        requireNotNull(model.detect()).join()
        assertEquals("203.0.113.9", model.draft.endpoint)
    }

    @Test fun pinEditInvalidatesDetectAndPlan() = runTest {
        val model = detected()
        model.back()
        model.edit(model.draft.copy(fingerprint = fingerprint(1)))
        assertNull(model.state.value.detect)
        assertNull(model.state.value.plan)
        assertNull(model.plan())
        assertEquals(1, peer.connections.size)
    }

    @Test fun planFieldEditRequiresNewPlanButKeepsDetection() = runTest {
        val model = detected()
        requireNotNull(model.plan()).join()
        model.back()
        val detection = model.state.value.detect
        model.edit(model.draft.copy(dns = "9.9.9.9"))
        assertSame(detection, model.state.value.detect)
        assertNull(model.state.value.plan)
        model.reviewApply()
        assertEquals(Step.DETECT, model.state.value.step)
    }

    @Test fun failedRepeatDetectCannotReuseEarlierSuccess() = runTest {
        val model = detected()
        model.back()
        peer.failure = ProvisioningException("repeat-detect-failed")
        requireNotNull(model.detect()).join()
        assertNull(model.state.value.detect)
        assertNull(model.state.value.plan)
        assertNull(model.plan())
        assertEquals(Step.SSH, model.state.value.step)
    }

    @Test fun failedRepeatPlanCannotConfirmEarlierPlan() = runTest {
        val model = detected()
        requireNotNull(model.plan()).join()
        model.back()
        peer.failure = ProvisioningException("repeat-plan-failed")
        requireNotNull(model.plan()).join()
        assertNull(model.state.value.plan)
        model.reviewApply()
        assertEquals(Step.DETECT, model.state.value.step)
    }

    @Test fun savedReuseDecryptsOnlyForOperationWithoutDisplayingOrRewritingSecret() = runTest {
        val original = detected()
        val saved = store.servers.first().single()
        val ciphertext = credentialFile.readBytes()
        original.leave()
        val model = model()
        requireNotNull(model.reuse(saved)).join()
        assertTrue(model.state.value.usingSavedAccess)
        assertTrue(model.draft.password.isEmpty())
        assertTrue(model.draft.privateKey.isEmpty())
        assertEquals(pin, model.draft.fingerprint)
        assertArrayEquals(ciphertext, credentialFile.readBytes())
        assertTrue((peer.connections.last().credential as VpsCredential.Password).value.all { it == '\u0000' })
    }

    @Test fun explicitClearRemovesOnlySavedAccessAndPreservesServerMetadata() = runTest {
        val model = detected()
        val saved = store.servers.first().single()
        model.leave()
        requireNotNull(model.clearSavedAccess(saved)).join()
        assertEquals(listOf(saved), store.servers.first())
        assertNull(store.loadConnection(saved))
        assertEquals(SavedAccess.CLEARED, model.state.value.savedAccess)
        assertTrue(model.draft.password.isEmpty())
        assertEquals(1, peer.connections.size)
    }

    @Test fun changedSavedPinBetweenDetectionAndPlanRequiresNewDetect() = runTest {
        val model = detected()
        val saved = store.servers.first().single()
        val reuse = model()
        requireNotNull(reuse.reuse(saved)).join()
        store.saveCredential(saved, fingerprint(1), VpsCredential.Password("replacement".toCharArray()))
        requireNotNull(reuse.plan()).join()
        assertEquals(Problem.CONNECTION_CHANGED, reuse.state.value.problem)
        assertEquals(Step.SSH, reuse.state.value.step)
        assertNull(reuse.state.value.detect)
        assertEquals(2, peer.connections.size)
        model.leave()
    }

    @Test fun missingSavedRecordCannotContactRemoteOrReuseStaleDetection() = runTest {
        detected()
        val saved = store.servers.first().single()
        store.clearCredential(saved.id)
        val model = model()
        requireNotNull(model.reuse(saved)).join()
        assertEquals(Problem.ACCESS_MISSING, model.state.value.problem)
        assertNull(model.state.value.detect)
        assertEquals(1, peer.connections.size)
    }

    @Test fun failedCredentialWriteRetainsActualDetectAndReportsMetadataOnly() = runTest {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val failingFile = VpsCredentialFile(credentialFile, { key }, output = { throw IOException("synthetic-storage-secret") }, stat = { it.exists() })
        val model = model(VpsServerStore(preferences, failingFile))
        ready(model)
        requireNotNull(model.detect()).join()
        assertEquals(Step.DETECT, model.state.value.step)
        assertNotNull(model.state.value.detect)
        assertEquals(SavedAccess.METADATA_ONLY, model.state.value.savedAccess)
        assertEquals(Problem.STORAGE_FAILED, model.state.value.problem)
        assertEquals(1, store.servers.first().size)
        assertFalse(credentialFile.exists())
        assertFalse(model.state.value.toString().contains("synthetic-storage-secret"))
    }

    @Test fun failedMetadataWriteReportsUnverifiedSaveWithoutDiscardingAuthenticatedResult() = runTest {
        val failingPreferences = object : DataStore<Preferences> {
            override val data = preferences.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw IOException("synthetic-metadata-secret")
        }
        val model = model(VpsServerStore(failingPreferences, credentialFileForTest(credentialFile)))
        ready(model)
        requireNotNull(model.detect()).join()
        assertNotNull(model.state.value.detect)
        assertEquals(SavedAccess.UNKNOWN, model.state.value.savedAccess)
        assertEquals(Problem.STORAGE_FAILED, model.state.value.problem)
        assertTrue(store.servers.first().isEmpty())
        assertFalse(credentialFile.exists())
    }

    @Test fun cancellationDrainsRemoteAndRejectsLateResultWithoutSaving() = runTest {
        val model = model()
        ready(model)
        val originalPassword = model.draft.password
        peer.blockDetect = CompletableDeferred()
        val check = requireNotNull(model.detect())
        peer.detectEntered.await()
        assertNull(model.detect())
        model.edit(model.draft.copy(host = "203.0.113.9"))
        assertEquals("198.51.100.42", model.draft.host)
        model.leave()
        check.join()
        peer.blockDetect!!.complete(Unit)
        assertFalse(model.state.value.busy)
        assertFalse(model.state.value.active)
        assertEquals(Problem.CANCELLED, model.state.value.problem)
        assertNull(model.state.value.detect)
        assertTrue(originalPassword.all { it == '\u0000' })
        assertTrue((peer.connections.single().credential as VpsCredential.Password).value.all { it == '\u0000' })
        assertEquals(1, peer.closes.get())
        assertTrue(store.servers.first().isEmpty())
    }

    @Test fun cancellationDuringAdmittedLocalSaveSettlesCredentialAndReportsItsOutcome() = runTest {
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var firstWrite = true
        val blockingPreferences = object : DataStore<Preferences> {
            override val data = preferences.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                val updated = preferences.updateData(transform)
                if (firstWrite) {
                    firstWrite = false
                    committed.complete(Unit)
                    release.await()
                }
                return updated
            }
        }
        val blockingStore = VpsServerStore(blockingPreferences, credentialFileForTest(credentialFile))
        val model = model(blockingStore)
        ready(model)
        val check = requireNotNull(model.detect())
        try {
            committed.await()
            model.leave()
            assertTrue(model.state.value.busy)
        } finally {
            release.complete(Unit)
            withContext(NonCancellable) { check.join() }
        }
        assertEquals(SavedAccess.SAVED, model.state.value.savedAccess)
        assertEquals(Problem.CANCELLED, model.state.value.problem)
        assertFalse(model.state.value.active)
        assertNull(model.state.value.detect)
        val loaded = requireNotNull(blockingStore.loadConnection(store.servers.first().single()))
        assertEquals("synthetic-password", (loaded.credential as VpsCredential.Password).value.concatToString())
        (loaded.credential as VpsCredential.Password).clear()
    }

    @Test fun switchingAuthenticationDropsUnusedSecrets() = runTest {
        val model = model()
        ready(model)
        val password = model.draft.password
        model.edit(model.draft.copy(authMode = AuthMode.KEY))
        assertTrue(password.all { it == '\u0000' })
        model.edit(model.draft.copy(privateKey = "synthetic-key", passphrase = "synthetic-passphrase"))
        model.edit(model.draft.copy(authMode = AuthMode.PASSWORD))
        assertTrue(model.draft.privateKey.isEmpty())
        assertTrue(model.draft.passphrase.isEmpty())
    }

    @Test fun manualStatusCommitsObservedMetadataAndRetainsPeersWithoutRefreshingTime() = runTest {
        val model = detected()
        model.leave()
        val saved = store.servers.first().single()
        requireNotNull(model.syncStatus(saved)).join()
        val current = store.servers.first().single()
        val snapshot = model.state.value.statusSnapshots.getValue(saved.id)
        assertFalse(model.state.value.active)
        assertEquals(1_710_000_012L, current.lastSyncEpochSeconds)
        assertEquals(current, snapshot.server)
        assertEquals(1_710_000_012L, snapshot.observedAtEpochSeconds)
        assertEquals("phone", snapshot.status.peers.single().name)
        model.retainCurrentServers(listOf(current, current.copy(id = "unrelated")))
        assertSame(snapshot, model.state.value.statusSnapshots.getValue(saved.id))
        assertEquals(2, peer.closes.get())
        assertTrue((peer.connections.last().credential as VpsCredential.Password).value.all { it == '\u0000' })
        assertFalse(peer.commands.any { it.contains("./'install.sh'") || it.contains("./'add-peer.sh'") })
    }

    @Test fun failedRefreshPreservesEarlierObservationWithoutClaimingNewSuccess() = runTest {
        val model = detected()
        model.leave()
        requireNotNull(model.syncStatus(store.servers.first().single())).join()
        val before = store.servers.first().single()
        val snapshot = model.state.value.statusSnapshots.getValue(before.id)
        peer.failure = ProvisioningException("synthetic-secret-failure")
        requireNotNull(model.syncStatus(before)).join()
        assertEquals(Problem.CONNECTION_FAILED, model.state.value.problem)
        assertEquals(before, store.servers.first().single())
        assertSame(snapshot, model.state.value.statusSnapshots.getValue(before.id))
        assertEquals(3, peer.closes.get())
    }

    @Test fun missingSavedAccessCannotStartStatusConnection() = runTest {
        val model = detected()
        model.leave()
        val saved = store.servers.first().single()
        store.clearCredential(saved.id)
        requireNotNull(model.syncStatus(saved)).join()
        assertEquals(Problem.ACCESS_MISSING, model.state.value.problem)
        assertTrue(model.state.value.statusSnapshots.isEmpty())
        assertEquals(1, peer.connections.size)
        assertEquals(saved, store.servers.first().single())
    }

    @Test fun removedOrReplacedServerRejectsDelayedStatusWithoutRepublishingPeers() = runTest {
        for (replacement in listOf(false, true)) {
            val model = detected()
            model.leave()
            val saved = store.servers.first().last()
            peer.statusEntered = CompletableDeferred()
            peer.blockStatus = CompletableDeferred()
            val refresh = requireNotNull(model.syncStatus(saved))
            peer.statusEntered.await()
            store.remove(saved.id)
            if (replacement) store.upsert(saved.copy(host = "198.51.100.99"))
            model.retainCurrentServers(store.servers.first())
            peer.blockStatus!!.complete(Unit)
            refresh.join()
            assertEquals(Problem.CONNECTION_CHANGED, model.state.value.problem)
            assertFalse(saved.id in model.state.value.statusSnapshots)
            assertFalse(store.servers.first().any { it.id == saved.id && it.lastSyncEpochSeconds > 0L })
        }
    }

    @Test fun cancelledStatusDrainsClientAndLeavesMetadataUnchanged() = runTest {
        val model = detected()
        model.leave()
        val saved = store.servers.first().single()
        peer.blockStatus = CompletableDeferred()
        val refresh = requireNotNull(model.syncStatus(saved))
        peer.statusEntered.await()
        assertNull(model.syncStatus(saved))
        model.cancelOperation()
        refresh.join()
        assertEquals(Problem.CANCELLED, model.state.value.problem)
        assertFalse(model.state.value.busy)
        assertEquals(saved, store.servers.first().single())
        assertTrue(model.state.value.statusSnapshots.isEmpty())
        assertEquals(2, peer.closes.get())
        assertTrue((peer.connections.last().credential as VpsCredential.Password).value.all { it == '\u0000' })
    }

    @Test fun snapshotInvalidationDropsRemovedAndChangedSshIdentities() = runTest {
        val model = detected()
        model.leave()
        requireNotNull(model.syncStatus(store.servers.first().single())).join()
        val saved = store.servers.first().single()
        model.retainCurrentServers(listOf(saved.copy(host = "198.51.100.99")))
        assertTrue(model.state.value.statusSnapshots.isEmpty())
        requireNotNull(model.syncStatus(saved)).join()
        model.retainCurrentServers(emptyList())
        assertTrue(model.state.value.statusSnapshots.isEmpty())
    }

    @Test fun newerDetectOnTheSameSshHostInvalidatesTheOlderPeerSnapshot() = runTest {
        val model = detected()
        model.leave()
        requireNotNull(model.syncStatus(store.servers.first().single())).join()
        val observed = store.servers.first().single()
        val snapshot = model.state.value.statusSnapshots.getValue(observed.id)
        peer.detectedEndpoint = "198.51.100.77"
        requireNotNull(model.reuse(observed)).join()
        val detected = store.servers.first().single()
        assertEquals(observed.host, detected.host)
        assertEquals("198.51.100.77", detected.endpoint)
        assertFalse(snapshot.matches(detected))
        model.retainCurrentServers(listOf(detected))
        assertTrue(model.state.value.statusSnapshots.isEmpty())
    }

    @Test fun statusFeedbackDoesNotCarryAnotherServersSavedOrClearedNotice() = runTest {
        val model = detected()
        model.leave()
        val first = store.servers.first().single()
        val second = first.copy(id = "server-b", name = "second server")
        store.upsert(second)
        assertEquals(SavedAccess.SAVED, model.state.value.savedAccess)
        requireNotNull(model.syncStatus(second)).join()
        assertEquals(Problem.ACCESS_MISSING, model.state.value.problem)
        assertEquals(SavedAccess.NONE, model.state.value.savedAccess)
        store.saveCredential(second, pin, VpsCredential.Password("synthetic-b-password".toCharArray()))
        requireNotNull(model.clearSavedAccess(first)).join()
        assertEquals(SavedAccess.CLEARED, model.state.value.savedAccess)
        requireNotNull(model.syncStatus(second)).join()
        assertNull(model.state.value.problem)
        assertEquals(second.id, model.state.value.serverId)
        assertEquals(SavedAccess.NONE, model.state.value.savedAccess)
        assertTrue(second.id in model.state.value.statusSnapshots)
    }

    @Test fun cancelledStatusWaitingForStoreAdmissionNeverWritesAfterTheGateOpens() = runTest {
        detected().leave()
        val saved = store.servers.first().single()
        val blockerEntered = CompletableDeferred<Unit>()
        val releaseBlocker = CompletableDeferred<Unit>()
        val statusObserved = CompletableDeferred<Unit>()
        var holdNextEdit = false
        val blockedPreferences = object : DataStore<Preferences> {
            override val data = preferences.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (holdNextEdit) {
                    holdNextEdit = false
                    blockerEntered.complete(Unit)
                    releaseBlocker.await()
                }
                return preferences.updateData(transform)
            }
        }
        val blockedStore = VpsServerStore(blockedPreferences, credentialFileForTest(credentialFile))
        val model = model(blockedStore) { statusObserved.complete(Unit); 1_710_000_012L }
        peer.blockStatus = CompletableDeferred()
        val refresh = requireNotNull(model.syncStatus(saved))
        peer.statusEntered.await()
        holdNextEdit = true
        val blocker = launch { blockedStore.upsert(saved.copy(id = "unrelated")) }
        try {
            blockerEntered.await()
            peer.blockStatus!!.complete(Unit)
            statusObserved.await()
            model.cancelOperation()
            runCurrent()
            assertTrue("Cancellation must settle without waiting for unrelated store admission", refresh.isCompleted)
            assertTrue(model.state.value.statusSnapshots.isEmpty())
        } finally {
            releaseBlocker.complete(Unit)
            withContext(NonCancellable) { blocker.join(); refresh.join() }
        }
        assertEquals(saved, store.servers.first().first { it.id == saved.id })
        assertEquals(Problem.CANCELLED, model.state.value.problem)
        assertTrue((peer.connections.last().credential as VpsCredential.Password).value.all { it == '\u0000' })
    }

    @Test fun cancellationAfterStatusCommitAdmissionSettlesMetadataWithoutInventingPeerPublication() = runTest {
        detected().leave()
        val saved = store.servers.first().single()
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blockedPreferences = object : DataStore<Preferences> {
            override val data = preferences.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                val updated = preferences.updateData(transform)
                committed.complete(Unit)
                release.await()
                return updated
            }
        }
        val blockedStore = VpsServerStore(blockedPreferences, credentialFileForTest(credentialFile))
        val model = model(blockedStore)
        val refresh = requireNotNull(model.syncStatus(saved))
        try {
            committed.await()
            model.cancelOperation()
            runCurrent()
            assertTrue(model.state.value.busy)
            assertEquals(1_710_000_012L, store.servers.first().single().lastSyncEpochSeconds)
        } finally {
            release.complete(Unit)
            withContext(NonCancellable) { refresh.join() }
        }
        assertFalse(model.state.value.busy)
        assertEquals(Problem.CANCELLED, model.state.value.problem)
        assertTrue(model.state.value.statusSnapshots.isEmpty())
        assertTrue((peer.connections.last().credential as VpsCredential.Password).value.all { it == '\u0000' })
    }

    private suspend fun detected(): VpsProvisioningViewModel = model().also { ready(it); requireNotNull(it.detect()).join() }

    private fun model(
        serverStore: VpsServerStore = store,
        now: () -> Long = { 1_710_000_012L },
    ): VpsProvisioningViewModel = VpsProvisioningViewModel(
        ApplicationProvider.getApplicationContext(), serverStore, peer, now,
    ).also { model ->
        modelJobs.add(requireNotNull(model.viewModelScope.coroutineContext[Job]))
        owners.add(ViewModelStore().apply { put("owner", model) })
    }

    private fun ready(model: VpsProvisioningViewModel) {
        model.begin()
        model.edit(model.draft.copy(host = "198.51.100.42", fingerprint = pin, authMode = AuthMode.PASSWORD))
        model.edit(model.draft.copy(password = "synthetic-password".toCharArray()))
    }

    private fun fingerprint(seed: Int): String = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + seed).toByte() })
}
