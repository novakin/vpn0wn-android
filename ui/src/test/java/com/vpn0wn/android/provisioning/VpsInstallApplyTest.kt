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
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.AuthMode
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.Installation
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.Problem
import java.io.File
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
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
@Config(application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class VpsInstallApplyTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var preferences: DataStore<Preferences>
    private lateinit var scope: CoroutineScope
    private lateinit var store: VpsServerStore
    private val owners = mutableListOf<ViewModelStore>()
    private val jobs = mutableListOf<Job>()
    private val peer = VpsPlanningTestPeer()

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }
        store = VpsServerStore(preferences, credentialFileForTest(File(folder.root, "credentials")))
        peer.statusOutput = status()
    }

    @After fun tearDown() = runTest {
        withContext(NonCancellable) {
            peer.blockInstall?.complete(Unit)
            peer.blockStatus?.complete(Unit)
            owners.forEach(ViewModelStore::clear)
            try { jobs.forEach { it.cancelAndJoin() } } finally {
                try { scope.coroutineContext[Job]!!.cancelAndJoin() } finally { Dispatchers.resetMain() }
            }
        }
    }

    @Test fun planReviewAndConfirmationRequestCannotMutateWithoutExactConfirmation() = runTest {
        val model = planned()
        assertTrue(model.canInstall)
        val confirmation = requireNotNull(model.requestInstallConfirmation())
        assertNull(model.confirmInstall(confirmation.copy()))
        assertFalse(peer.commands.any(::isInstall))
        assertNull(store.pendingInstall(store.servers.first().single()))
        model.back()
        assertNull(model.confirmInstall(confirmation))
        assertFalse(peer.commands.any(::isInstall))
    }

    @Test fun confirmedFreshInstallVerifiesActualStatusAndCommitsWithoutForceOrPrivateProfiles() = runTest {
        val model = planned()
        val confirmation = requireNotNull(model.requestInstallConfirmation())
        val install = requireNotNull(model.confirmInstall(confirmation))
        assertNull(model.confirmInstall(confirmation))
        install.join()
        val saved = store.servers.first().single()
        assertEquals(Installation.INSTALLED, model.state.value.installation)
        assertNull(model.state.value.problem)
        assertNull(model.state.value.recovery)
        assertEquals(true, saved.running)
        assertEquals(OBSERVED_AT, saved.lastSyncEpochSeconds)
        assertFalse(model.state.value.pendingInstall)
        assertNull(store.pendingInstall(saved))
        assertTrue(model.state.value.statusSnapshots.getValue(saved.id).status.peers.isEmpty())
        val command = peer.commands.single(::isInstall)
        assertFalse(command.contains("--force"))
        assertTrue(command.contains("'--endpoint' '198.51.100.42'"))
        assertTrue(peer.commands.any { it.contains("./'sync-status.sh'") })
        assertFalse(peer.commands.any { it.contains("./'add-peer.sh'") })
        assertTrue((peer.connections.last().credential as VpsCredential.Password).value.all { it == '\u0000' })
        assertFalse(model.canInstall)
    }

    @Test fun editsInvalidateOldConfirmationAndRequireANewMatchingPlan() = runTest {
        val model = planned()
        val original = requireNotNull(model.requestInstallConfirmation())
        model.edit(model.draft.copy(port = "51821"))
        assertNull(model.confirmInstall(original))
        peer.planRequest = peer.planRequest.copy(port = 51821)
        requireNotNull(model.plan()).join()
        model.reviewApply()
        assertTrue(model.canInstall)
        assertNull(model.confirmInstall(original))
        val current = requireNotNull(model.requestInstallConfirmation())
        peer.statusOutput = status(port = "51821")
        requireNotNull(model.confirmInstall(current)).join()
        assertEquals(Installation.INSTALLED, model.state.value.installation)
        assertTrue(peer.commands.single(::isInstall).contains("'--port' '51821'"))
    }

    @Test fun existingConfigurationOrOverwritePlanNeverCreatesAnInstallAdmission() = runTest {
        for (existingDetected in listOf(true, false)) {
            peer.configExists = existingDetected
            peer.planOverwrites = !existingDetected
            val model = planned()
            assertFalse(model.canInstall)
            assertNull(model.requestInstallConfirmation())
            assertNull(store.pendingInstall(store.servers.first().last()))
        }
        assertFalse(peer.commands.any(::isInstall))
    }

    @Test fun changedServerAfterConfirmationIsRejectedBeforeTheMutationCommand() = runTest {
        val model = planned()
        val confirmation = requireNotNull(model.requestInstallConfirmation())
        val before = store.servers.first().single()
        store.upsert(before.copy(name = "changed while dialog was open"))
        requireNotNull(model.confirmInstall(confirmation)).join()
        assertEquals(Problem.CONNECTION_CHANGED, model.state.value.problem)
        assertFalse(peer.commands.any(::isInstall))
        assertNull(store.pendingInstall(store.servers.first().single()))
    }

    @Test fun lostResponseSurvivesNewOwnerAndReconciliationDoesNotInventInstallSuccess() = runTest {
        val model = planned()
        val defaultRequest = peer.planRequest
        val admittedRequest = defaultRequest.copy(endpoint = "203.0.113.9", port = 51821, cidr = "10.9.0.0/24", dns = "9.9.9.9")
        model.edit(model.draft.copy(endpoint = admittedRequest.endpoint, port = admittedRequest.port.toString(),
            cidr = admittedRequest.cidr, dns = admittedRequest.dns))
        peer.planRequest = admittedRequest
        requireNotNull(model.plan()).join()
        model.reviewApply()
        peer.installFailure = ProvisioningException("synthetic-secret-transport-failure")
        apply(model).join()
        val saved = store.servers.first().single()
        assertEquals(Installation.UNCERTAIN, model.state.value.installation)
        assertTrue(model.state.value.pendingInstall)
        assertNotNull(store.pendingInstall(saved))
        assertFalse(model.state.value.toString().contains("synthetic-secret-transport-failure"))
        peer.installFailure = null
        peer.planRequest = defaultRequest
        peer.statusOutput = JSONObject(status(port = admittedRequest.port.toString())).put("endpoint", admittedRequest.endpoint)
            .put("cidr", admittedRequest.cidr).put("dns", admittedRequest.dns).toString()
        val restored = model()
        requireNotNull(restored.reuse(saved)).join()
        assertTrue(restored.state.value.pendingInstall)
        requireNotNull(restored.plan()).join()
        restored.reviewApply()
        assertFalse(restored.canInstall)
        requireNotNull(restored.reconcileInstall()).join()
        assertEquals(Installation.OBSERVED, restored.state.value.installation)
        assertEquals(defaultRequest.port.toString(), restored.state.value.plan?.port)
        val observation = restored.state.value.statusSnapshots.getValue(saved.id)
        assertEquals(admittedRequest.endpoint, observation.status.endpoint)
        assertEquals(admittedRequest.port.toString(), observation.status.listenPort)
        assertEquals(admittedRequest.cidr, observation.status.cidr)
        assertEquals(admittedRequest.dns, observation.status.dns)
        assertEquals(admittedRequest.port.toString(), store.servers.first().single().listenPort)
        assertEquals(1, peer.commands.count(::isInstall))
        assertNull(store.pendingInstall(store.servers.first().single()))
        assertFalse(restored.canInstall)
    }

    @Test fun absentOrMismatchedStateCannotClearUnknownAdmissionOrTriggerARetry() = runTest {
        val model = planned()
        peer.installFailure = ProvisioningException("lost reply")
        apply(model).join()
        peer.installFailure = null
        peer.failure = ProvisioningException("absent", category = ProvisioningFailureCategory.NOT_INSTALLED)
        requireNotNull(model.reconcileInstall()).join()
        assertNotNull(store.pendingInstall(store.servers.first().single()))
        peer.failure = null
        peer.statusOutput = status(port = "51821")
        requireNotNull(model.reconcileInstall()).join()
        assertEquals(Problem.INVALID_RESPONSE, model.state.value.problem)
        assertTrue(model.state.value.pendingInstall)
        assertEquals(1, peer.commands.count(::isInstall))
    }

    @Test fun cancelledAdmittedInstallDrainsAndPreservesTheDurableFence() = runTest {
        val model = planned()
        peer.blockInstall = CompletableDeferred()
        val operation = apply(model)
        peer.installEntered.await()
        assertNotNull(store.pendingInstall(store.servers.first().single()))
        model.leave()
        operation.join()
        assertFalse(model.state.value.active)
        assertFalse(model.state.value.busy)
        assertEquals(Installation.UNCERTAIN, model.state.value.installation)
        assertTrue(model.state.value.pendingInstall)
        assertNotNull(store.pendingInstall(store.servers.first().single()))
        assertEquals(3, peer.closes.get())
        assertFalse(peer.commands.any { it.contains("./'sync-status.sh'") })
        assertTrue((peer.connections.last().credential as VpsCredential.Password).value.all { it == '\u0000' })
    }

    @Test fun leavingDuringDurableAdmissionCannotReopenTheWizardOrSendTheInstall() = runTest {
        val entered = CompletableDeferred<Thread>()
        val release = CompletableDeferred<Unit>()
        var blockAdmission = false
        val gatedPreferences = object : DataStore<Preferences> {
            override val data = preferences.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (blockAdmission) {
                    entered.complete(Thread.currentThread())
                    release.await()
                }
                return preferences.updateData(transform)
            }
        }
        val gatedStore = VpsServerStore(gatedPreferences, credentialFileForTest(File(folder.root, "admission-credentials")))
        val model = planned(gatedStore)
        val saved = gatedStore.servers.first().single()
        val ownerThread = Thread.currentThread()
        blockAdmission = true
        val operation = apply(model)
        try {
            assertSame(ownerThread, entered.await())
            model.leave()
            assertFalse(model.state.value.active)
            assertNull(model.state.value.plan)
        } finally {
            release.complete(Unit)
        }
        operation.join()
        assertFalse(model.state.value.active)
        assertFalse(model.state.value.busy)
        assertNull(model.state.value.detect)
        assertNull(model.state.value.plan)
        assertTrue(model.state.value.pendingInstall)
        assertEquals(Installation.UNCERTAIN, model.state.value.installation)
        assertNotNull(gatedStore.pendingInstall(saved))
        assertEquals(saved, gatedStore.servers.first().single())
        assertFalse(peer.commands.any(::isInstall))
        assertEquals(3, peer.closes.get())
        assertTrue((peer.connections.last().credential as VpsCredential.Password).value.all { it == '\u0000' })
    }

    @Test fun cancelledVerificationCannotPublishSuccessOrClearAdmission() = runTest {
        val model = planned()
        peer.blockStatus = CompletableDeferred()
        val operation = apply(model)
        peer.statusEntered.await()
        model.cancelOperation()
        operation.join()
        val saved = store.servers.first().single()
        assertNull(saved.running)
        assertTrue(model.state.value.statusSnapshots.isEmpty())
        assertEquals(Installation.UNCERTAIN, model.state.value.installation)
        assertNotNull(store.pendingInstall(saved))
        assertEquals(4, peer.closes.get())
    }

    @Test fun invalidInstallResponseNeverBecomesSuccessAndLeavesActualMetadataUntouched() = runTest {
        val model = planned()
        peer.installOutput = JSONObject().put("ok", true).put("interface", "wg0")
            .put("endpoint", "198.51.100.42").put("port", "51820").put("cidr", "10.8.0.0/24")
            .put("serverPublicKey", "synthetic-invalid-key").toString()
        apply(model).join()
        val saved = store.servers.first().single()
        assertNull(saved.running)
        assertEquals(0L, saved.lastSyncEpochSeconds)
        assertEquals(Installation.UNCERTAIN, model.state.value.installation)
        assertNotNull(store.pendingInstall(saved))
        assertFalse(peer.commands.any { it.contains("./'sync-status.sh'") })
    }

    @Test fun structuredRecoveryIsRetainedWithoutRemotePathsOrRawFailureText() = runTest {
        val model = planned()
        peer.installExitCode = 74
        peer.installOutput = JSONObject().put("ok", false).put("error", "apply_failed")
            .put("message", "synthetic-secret-diagnostic").put("recovery", "incomplete")
            .put("recoveryPath", "/private/synthetic-secret-path").toString()
        apply(model).join()
        assertEquals(ProvisioningRecovery.INCOMPLETE, model.state.value.recovery)
        assertEquals(Problem.INSTALL_FAILED, model.state.value.problem)
        assertEquals(Installation.UNCERTAIN, model.state.value.installation)
        assertFalse(model.state.value.toString().contains("synthetic-secret"))
        assertNotNull(store.pendingInstall(store.servers.first().single()))
    }

    @Test fun receivedRestorationAndFreshAbsentDetectPermitOnlyANewPlanAndConfirmation() = runTest {
        knownFailureRequiresFreshPlanAndConfirmation(ProvisioningRecovery.RESTORED)
    }

    @Test fun receivedUnchangedAndFreshAbsentDetectPermitOnlyANewPlanAndConfirmation() = runTest {
        knownFailureRequiresFreshPlanAndConfirmation(ProvisioningRecovery.UNCHANGED)
    }

    @Test fun settledRestorationCannotClearAnotherOwnersUnknownAdmission() = runTest {
        settledRecoveryCannotClearAnotherOwnersAdmission(ProvisioningRecovery.RESTORED)
    }

    @Test fun settledUnchangedCannotClearAnotherOwnersUnknownAdmission() = runTest {
        settledRecoveryCannotClearAnotherOwnersAdmission(ProvisioningRecovery.UNCHANGED)
    }

    @Test fun freshWizardDetectDiscardsAnUnsettledRecoveryReceipt() = runTest {
        val model = planned()
        failInstallWithRecovery(ProvisioningRecovery.RESTORED)
        apply(model).join()
        val saved = store.servers.first().single()
        val pending = requireNotNull(store.pendingInstall(saved))
        assertEquals(ProvisioningRecovery.RESTORED, model.state.value.recovery)
        requireNotNull(model.detect()).join()
        assertNull(model.state.value.recovery)
        peer.statusFailure = ProvisioningException("absent", category = ProvisioningFailureCategory.NOT_INSTALLED)
        val detects = peer.commands.count { it.contains("./'detect.sh'") }
        requireNotNull(model.reconcileInstall()).join()
        assertEquals(pending, store.pendingInstall(saved))
        assertEquals(Installation.UNCERTAIN, model.state.value.installation)
        assertEquals(detects, peer.commands.count { it.contains("./'detect.sh'") })
        assertEquals(1, peer.commands.count(::isInstall))
    }

    @Test fun observedSettlementConsumesTheEarlierRecoveryReceipt() = runTest {
        val model = planned()
        failInstallWithRecovery(ProvisioningRecovery.RESTORED)
        apply(model).join()
        assertEquals(ProvisioningRecovery.RESTORED, model.state.value.recovery)
        requireNotNull(model.reconcileInstall()).join()
        assertEquals(Installation.OBSERVED, model.state.value.installation)
        assertNull(model.state.value.recovery)
        assertFalse(model.state.value.pendingInstall)
        assertNull(store.pendingInstall(store.servers.first().single()))
        assertEquals(1, peer.commands.count(::isInstall))
    }

    @Test fun corruptPendingIntentFailsClosedInsteadOfEnablingAnotherInstall() = runTest {
        val model = planned()
        val confirmation = requireNotNull(model.requestInstallConfirmation())
        preferences.edit { it[stringPreferencesKey("vps_pending_installs")] = "synthetic-corrupt-json" }
        requireNotNull(model.confirmInstall(confirmation)).join()
        assertEquals(Problem.STORAGE_FAILED, model.state.value.problem)
        assertFalse(peer.commands.any(::isInstall))
    }

    @Test fun failedStatusCommitKeepsAdmissionWithoutOverwritingChangedServer() = runTest {
        val model = planned()
        peer.blockStatus = CompletableDeferred()
        val operation = apply(model)
        peer.statusEntered.await()
        val before = store.servers.first().single()
        store.upsert(before.copy(name = "keep this change"))
        peer.blockStatus!!.complete(Unit)
        operation.join()
        val saved = store.servers.first().single()
        assertEquals("keep this change", saved.name)
        assertNull(saved.running)
        assertEquals(Problem.CONNECTION_CHANGED, model.state.value.problem)
        assertEquals(Installation.UNCERTAIN, model.state.value.installation)
        assertTrue(model.state.value.statusSnapshots.isEmpty())
        assertNotNull(store.pendingInstall(saved))
    }

    @Test fun failedAdmissionPersistenceCannotSendTheMutationOrOfferBlindRetry() = runTest {
        var rejectWrites = false
        val failingPreferences = object : DataStore<Preferences> {
            override val data = preferences.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (rejectWrites) throw IOException("synthetic-private-store-error")
                return preferences.updateData(transform)
            }
        }
        val failingStore = VpsServerStore(failingPreferences, credentialFileForTest(File(folder.root, "failing-credentials")))
        val model = planned(failingStore)
        rejectWrites = true
        apply(model).join()
        assertEquals(Problem.STORAGE_FAILED, model.state.value.problem)
        assertTrue(model.state.value.pendingInstall)
        assertFalse(model.canInstall)
        assertFalse(peer.commands.any(::isInstall))
        assertFalse(model.state.value.toString().contains("synthetic-private-store-error"))
    }

    @Test fun pendingIntentIsExcludedFromOrdinaryTransferAndCannotBeRemovedWithFleetRecord() = runTest {
        val model = planned()
        peer.installFailure = ProvisioningException("lost reply")
        apply(model).join()
        val saved = store.servers.first().single()
        val encoded = VpsServerStore.encodeServers(store.servers.first())
        assertFalse(encoded.contains("pending"))
        store.remove(saved.id)
        store.upsert(saved.copy(id = "new-record"))
        assertNotNull(store.pendingInstall(store.servers.first().single()))
    }

    @Test fun failedSaveOfChangedSshTargetPreservesReadOnlyResultsButCannotAdmitAnotherServer() = runTest {
        val model = planned()
        val original = store.servers.first().single()
        model.edit(model.draft.copy(host = "203.0.113.9", fingerprint = replacementPin()))
        requireNotNull(model.detect()).join()
        assertNotNull(model.state.value.detect)
        assertEquals(Problem.STORAGE_CONFLICT, model.state.value.problem)
        assertEquals("203.0.113.9", model.draft.endpoint)
        assertEquals(original, store.servers.first().single())
        peer.planRequest = peer.planRequest.copy(endpoint = "203.0.113.9")
        requireNotNull(model.plan()).join()
        model.reviewApply()
        assertEquals("203.0.113.9", model.state.value.plan?.endpoint)
        assertFalse(model.canInstall)
        assertNull(model.requestInstallConfirmation())
        assertFalse(peer.commands.any(::isInstall))
        assertTrue(pendingAttempts().isEmpty())
    }

    @Test fun failedMetadataSaveOnAnExistingTargetCannotReuseItsPreviousAdmissionEligibility() = runTest {
        var rejectWrites = false
        val failingPreferences = object : DataStore<Preferences> {
            override val data = preferences.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (rejectWrites) throw IOException("synthetic-save-failure")
                return preferences.updateData(transform)
            }
        }
        val failingStore = VpsServerStore(failingPreferences, credentialFileForTest(File(folder.root, "metadata-failure-credentials")))
        val model = planned(failingStore)
        val original = failingStore.servers.first().single()
        rejectWrites = true
        requireNotNull(model.detect()).join()
        assertEquals(Problem.STORAGE_FAILED, model.state.value.problem)
        assertNotNull(model.state.value.detect)
        requireNotNull(model.plan()).join()
        model.reviewApply()
        assertNotNull(model.state.value.plan)
        assertFalse(model.canInstall)
        assertNull(model.requestInstallConfirmation())
        assertEquals(original, failingStore.servers.first().single())
        assertFalse(peer.commands.any(::isInstall))
        assertTrue(pendingAttempts().isEmpty())
    }

    @Test fun storeAdmissionRejectsAnActualSshTargetDifferentFromItsExpectedRecord() = runTest {
        planned()
        val original = store.servers.first().single()
        var rejected = false
        try {
            store.admitInstall(original, VpsEndpoint("203.0.113.9", original.sshPort, original.sshUser), peer.planRequest)
        } catch (_: VpsServerStore.ServerChangedException) {
            rejected = true
        }
        assertTrue(rejected)
        assertTrue(pendingAttempts().isEmpty())
        assertEquals(original, store.servers.first().single())
    }

    @Test fun successfulAdmissionBindsConfirmedRecordLiveSshPinAndRequestToOneTarget() = runTest {
        val model = planned()
        val confirmation = requireNotNull(model.requestInstallConfirmation())
        peer.blockInstall = CompletableDeferred()
        val operation = requireNotNull(model.confirmInstall(confirmation))
        peer.installEntered.await()
        val pending = pendingAttempts().single()
        val live = peer.connections.last()
        assertEquals(confirmation.server.id, pending.serverId)
        assertEquals(VpsEndpoint(confirmation.server.host, confirmation.server.sshPort, confirmation.server.sshUser), pending.ssh)
        assertEquals(confirmation.ssh, pending.ssh)
        assertEquals(live.endpoint, pending.ssh)
        assertEquals(confirmation.fingerprint, live.hostKeyFingerprint)
        assertEquals(peer.planRequest, pending.request)
        peer.blockInstall!!.complete(Unit)
        operation.join()
        assertEquals(Installation.INSTALLED, model.state.value.installation)
        assertEquals(confirmation.server.host, store.servers.first().single().host)
        assertTrue(pendingAttempts().isEmpty())
    }

    @Test fun removedRecordDuringInstallStatusSettlesAsConnectionChanged() = runTest {
        changeRecordDuringStatus(remove = true, reconcile = false)
    }

    @Test fun replacedSshRecordDuringInstallStatusSettlesAsConnectionChanged() = runTest {
        changeRecordDuringStatus(remove = false, reconcile = false)
    }

    @Test fun removedRecordDuringReconciliationStatusSettlesAsConnectionChanged() = runTest {
        changeRecordDuringStatus(remove = true, reconcile = true)
    }

    @Test fun replacedSshRecordDuringReconciliationStatusSettlesAsConnectionChanged() = runTest {
        changeRecordDuringStatus(remove = false, reconcile = true)
    }

    @Test fun removedRecordBeforeReconciliationLookupDoesNotEscapeOrContactTheServer() = runTest {
        staleReconciliationLookup(remove = true)
    }

    @Test fun replacedRecordBeforeReconciliationLookupDoesNotEscapeOrContactTheServer() = runTest {
        staleReconciliationLookup(remove = false)
    }

    private suspend fun changeRecordDuringStatus(remove: Boolean, reconcile: Boolean) {
        val model = planned()
        val original = store.servers.first().single()
        val operation = if (reconcile) {
            peer.installFailure = ProvisioningException("lost reply")
            apply(model).join()
            peer.installFailure = null
            peer.blockStatus = CompletableDeferred()
            requireNotNull(model.reconcileInstall())
        } else {
            peer.blockStatus = CompletableDeferred()
            apply(model)
        }
        peer.statusEntered.await()
        changeRecord(original, remove)
        peer.blockStatus!!.complete(Unit)
        operation.join()
        assertChangedInstallation(model, original, remove)
        assertEquals(4, peer.closes.get())
        assertTrue((peer.connections.last().credential as VpsCredential.Password).value.all { it == '\u0000' })
    }

    private suspend fun staleReconciliationLookup(remove: Boolean) {
        val model = planned()
        val original = store.servers.first().single()
        peer.installFailure = ProvisioningException("lost reply")
        apply(model).join()
        changeRecord(original, remove)
        val connections = peer.connections.size
        requireNotNull(model.reconcileInstall()).join()
        assertChangedInstallation(model, original, remove)
        assertEquals(connections, peer.connections.size)
    }

    private suspend fun changeRecord(original: StoredVpsServer, remove: Boolean) {
        if (remove) {
            store.remove(original.id)
        } else {
            store.clearCredential(original.id)
            store.upsert(original.copy(host = "203.0.113.9"))
        }
    }

    private suspend fun assertChangedInstallation(model: VpsProvisioningViewModel, original: StoredVpsServer, removed: Boolean) {
        assertEquals(Problem.CONNECTION_CHANGED, model.state.value.problem)
        assertEquals(Installation.UNCERTAIN, model.state.value.installation)
        assertFalse(model.state.value.busy)
        assertTrue(model.state.value.pendingInstall)
        assertTrue(model.state.value.statusSnapshots.isEmpty())
        val pending = pendingAttempts().single()
        assertEquals(original.id, pending.serverId)
        assertEquals(original.host, pending.ssh.host)
        if (removed) assertTrue(store.servers.first().isEmpty())
        else assertEquals(original.copy(host = "203.0.113.9"), store.servers.first().single())
    }

    private suspend fun pendingAttempts(): List<PendingVpsInstall> =
        PendingVpsInstall.decode(preferences.data.first()[stringPreferencesKey("vps_pending_installs")].orEmpty())

    private suspend fun knownFailureRequiresFreshPlanAndConfirmation(recovery: ProvisioningRecovery) {
        val model = planned()
        failInstallWithRecovery(recovery)
        apply(model).join()
        assertEquals(recovery, model.state.value.recovery)
        assertEquals(store.servers.first().single(), model.state.value.recoveryOwner)
        peer.statusFailure = ProvisioningException("absent", category = ProvisioningFailureCategory.NOT_INSTALLED)
        requireNotNull(model.reconcileInstall()).join()
        assertFalse(model.state.value.pendingInstall)
        assertNull(model.state.value.recovery)
        assertNull(model.state.value.recoveryOwner)
        assertNull(model.state.value.plan)
        assertFalse(model.canInstall)
        assertNull(model.requestInstallConfirmation())
        assertNull(store.pendingInstall(store.servers.first().single()))
        assertEquals(1, peer.commands.count(::isInstall))
        peer.statusFailure = null
        peer.installOutput = null
        peer.installExitCode = 0
        requireNotNull(model.plan()).join()
        model.reviewApply()
        apply(model).join()
        assertEquals(Installation.INSTALLED, model.state.value.installation)
        assertNull(model.state.value.recovery)
        assertEquals(2, peer.commands.count(::isInstall))
    }

    private suspend fun settledRecoveryCannotClearAnotherOwnersAdmission(recovery: ProvisioningRecovery) {
        val first = planned()
        val saved = store.servers.first().single()
        failInstallWithRecovery(recovery)
        apply(first).join()
        assertEquals(recovery, first.state.value.recovery)
        peer.statusFailure = ProvisioningException("absent", category = ProvisioningFailureCategory.NOT_INSTALLED)
        requireNotNull(first.reconcileInstall()).join()
        assertNull(first.state.value.recovery)
        assertNull(store.pendingInstall(saved))
        peer.statusFailure = null
        peer.installOutput = null
        peer.installExitCode = 0
        val second = model()
        requireNotNull(second.reuse(saved)).join()
        requireNotNull(second.plan()).join()
        second.reviewApply()
        peer.installFailure = ProvisioningException("lost reply")
        apply(second).join()
        peer.installFailure = null
        val unknown = requireNotNull(store.pendingInstall(saved))
        assertEquals(Installation.UNCERTAIN, second.state.value.installation)
        assertNull(second.state.value.recovery)
        requireNotNull(first.detect()).join()
        assertNull(first.state.value.recovery)
        peer.statusFailure = ProvisioningException("absent", category = ProvisioningFailureCategory.NOT_INSTALLED)
        val detects = peer.commands.count { it.contains("./'detect.sh'") }
        requireNotNull(first.reconcileInstall()).join()
        assertEquals(unknown, store.pendingInstall(saved))
        assertTrue(first.state.value.pendingInstall)
        assertEquals(Installation.UNCERTAIN, first.state.value.installation)
        assertFalse(first.canInstall)
        assertEquals(detects, peer.commands.count { it.contains("./'detect.sh'") })
        assertEquals(2, peer.commands.count(::isInstall))
        assertEquals(saved, store.servers.first().single())
    }

    private fun failInstallWithRecovery(recovery: ProvisioningRecovery) {
        peer.installExitCode = 74
        peer.installOutput = JSONObject().put("ok", false).put("error", "apply_failed")
            .put("message", "synthetic").put("recovery", recovery.name.lowercase(java.util.Locale.ROOT)).toString()
    }

    private fun replacementPin(): String = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() })

    private fun model(serverStore: VpsServerStore = store): VpsProvisioningViewModel = VpsProvisioningViewModel(
        ApplicationProvider.getApplicationContext(), serverStore, peer, { OBSERVED_AT },
    ).also { model ->
        jobs.add(requireNotNull(model.viewModelScope.coroutineContext[Job]))
        owners.add(ViewModelStore().apply { put("owner", model) })
    }

    private suspend fun planned(serverStore: VpsServerStore = store): VpsProvisioningViewModel = model(serverStore).also { model ->
        model.begin()
        model.edit(model.draft.copy(host = "198.51.100.42", fingerprint = PIN, authMode = AuthMode.PASSWORD))
        model.edit(model.draft.copy(password = "synthetic-password".toCharArray()))
        requireNotNull(model.detect()).join()
        requireNotNull(model.plan()).join()
        model.reviewApply()
    }

    private fun apply(model: VpsProvisioningViewModel): Job = requireNotNull(model.confirmInstall(requireNotNull(model.requestInstallConfirmation())))

    private fun status(port: String = "51820"): String = JSONObject().put("ok", true).put("interface", "wg0").put("running", true)
        .put("endpoint", "198.51.100.42").put("listenPort", port).put("cidr", "10.8.0.0/24")
        .put("dns", "1.1.1.1, 1.0.0.1").put("peers", JSONArray()).toString()

    private fun isInstall(command: String): Boolean = command.contains("./'install.sh'")

    private companion object {
        const val OBSERVED_AT = 1_710_000_012L
        val PIN = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    }
}
