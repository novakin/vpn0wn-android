/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.AuthMode
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.Problem
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.RestoreOutcome
import java.io.File
import java.security.MessageDigest
import javax.crypto.KeyGenerator
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
class VpsFreshRestoreWorkflowTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var scope: CoroutineScope
    private lateinit var store: VpsServerStore
    private lateinit var profiles: VpsUserProfileStore
    private lateinit var profileFile: File
    private lateinit var archive: File
    private lateinit var provider: VpsBackupInputProvider
    private val owners = mutableListOf<ViewModelStore>()
    private val jobs = mutableListOf<Job>()
    private val peer = VpsPlanningTestPeer()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setup() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        VpsUserProfileFileTest.resetNativeFiles()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }
        store = VpsServerStore(preferences, credentialFileForTest(File(folder.root, "credentials")))
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        profileFile = File(folder.root, "profiles")
        profiles = VpsUserProfileStore(VpsUserProfileFile(profileFile, { key }))
        archive = File(folder.root, "server.vpn0wn")
        val bytes = VpsBackupArchive().encrypt(SNAPSHOT, PASSWORD.toCharArray())
        try { archive.writeBytes(bytes) } finally { bytes.fill(0) }
        provider = VpsBackupInputProvider(archive).also { it.register(context) }
        peer.restorePlan = PLAN
    }

    @After fun teardown() = runTest {
        withContext(NonCancellable) {
            peer.blockUpload?.complete(Unit)
            peer.blockRestoreCheck?.complete(Unit)
            peer.blockRestore?.complete(Unit)
            peer.blockVerifyRestore?.complete(Unit)
            owners.forEach(ViewModelStore::clear)
            try { jobs.forEach { it.cancelAndJoin() } } finally {
                try { scope.coroutineContext[Job]!!.cancelAndJoin() } finally { Dispatchers.resetMain() }
            }
        }
    }

    @Test fun inspectionAndCheckExposePublicPlanWithoutAdmittingMutation() = runTest {
        val model = ready()
        val saved = store.servers.first().single()
        assertEquals(RestoreOutcome.READY, model.state.value.restore?.outcome)
        assertEquals(PLAN, model.state.value.restore?.plan)
        assertNull(store.pendingRestore(saved))
        assertTrue(peer.commands.any(::isCheck))
        assertFalse(peer.commands.any(::isConfirm))
        assertFalse(peer.commands.any(::isVerify))
        assertEquals(0, provider.queries)
        assertEquals(0, provider.mutations)
        assertTrue(provider.closed)
        assertFalse(profileFile.exists())
        assertPublic(model)
    }

    @Test fun exactConfirmationRunsOnceThenAuthoritativeVerifySettlesDestinationMetadata() = runTest {
        val model = ready()
        val confirmation = requireNotNull(model.requestRestoreConfirmation())
        assertNull(model.confirmRestore(confirmation.copy()))
        val run = requireNotNull(model.confirmRestore(confirmation))
        assertNull(model.confirmRestore(confirmation))
        run.join()
        val saved = store.servers.first().single()
        assertEquals(RestoreOutcome.VERIFIED, model.state.value.restore?.outcome)
        assertNull(model.state.value.problem)
        assertNull(store.pendingRestore(saved))
        assertEquals(PLAN.endpoint, saved.endpoint)
        assertEquals(PLAN.port, saved.listenPort)
        assertEquals(PLAN.cidr, saved.cidr)
        assertEquals(PLAN.dns, saved.dns)
        assertEquals(true, saved.running)
        assertEquals(OBSERVED_AT, saved.lastSyncEpochSeconds)
        assertEquals(1, peer.commands.count(::isConfirm))
        assertEquals(1, peer.commands.count(::isVerify))
        assertTrue(peer.commands.indexOfFirst(::isConfirm) < peer.commands.indexOfFirst(::isVerify))
        assertFalse(peer.commands.any { it.contains("./'install.sh'") || it.contains("./'add-peer.sh'") })
        assertFalse(profileFile.exists())
        assertTrue(peer.connections.all { (it.credential as VpsCredential.Password).value.all { value -> value == '\u0000' } })
        assertFalse(model.canRestore)
        assertPublic(model)
    }

    @Test fun restorePreservesExistingPrivateProfilesAndUnrelatedFleetRecord() = runTest {
        val unrelated = VpsProfileFixture.server
        store.upsert(unrelated)
        val original = profiles.saveGenerated(unrelated, VpsProfileFixture.serverKey.publicKey.toBase64(),
            VpsProfileFixture.request, VpsProfileFixture.result())
        val ciphertext = profileFile.readBytes()
        val model = ready()
        apply(model).join()
        assertEquals(RestoreOutcome.VERIFIED, model.state.value.restore?.outcome)
        assertArrayEquals(ciphertext, profileFile.readBytes())
        assertEquals(unrelated, store.servers.first().single { it.id == unrelated.id })
        assertTrue(original.sameContent(requireNotNull(profiles.load(unrelated, original.identity.serverPublicKey, original.identity.clientPublicKey))))
    }

    @Test fun dismissedConfirmationCannotDispatchOrRetireAnotherCurrentConfirmation() = runTest {
        val model = ready()
        val stale = requireNotNull(model.requestRestoreConfirmation())
        model.dismissRestoreConfirmation()
        assertNull(model.confirmRestore(stale))
        val current = requireNotNull(model.requestRestoreConfirmation())
        assertNull(model.confirmRestore(stale))
        requireNotNull(model.confirmRestore(current)).join()
        assertEquals(1, peer.commands.count(::isConfirm))
        assertEquals(RestoreOutcome.VERIFIED, model.state.value.restore?.outcome)
    }

    @Test fun destinationEditsInvalidatePlanAndCapturedConfirmation() = runTest {
        val model = ready()
        val stale = requireNotNull(model.requestRestoreConfirmation())
        model.edit(model.draft.copy(endpoint = "changed.example", port = "51822"))
        assertFalse(model.canRestore)
        assertNull(model.state.value.restore?.plan)
        assertNull(model.confirmRestore(stale))
        assertFalse(peer.commands.any(::isConfirm))
        peer.restorePlan = PLAN.copy(endpoint = "changed.example", port = "51822")
        requireNotNull(model.checkRestore()).join()
        model.reviewApply()
        requireNotNull(model.confirmRestore(requireNotNull(model.requestRestoreConfirmation()))).join()
        assertEquals("changed.example", store.servers.first().single().endpoint)
        assertEquals("51822", store.servers.first().single().listenPort)
    }

    @Test fun leavingOrStartingAnotherSourceRetiresCapturedConfirmation() = runTest {
        val model = ready()
        val stale = requireNotNull(model.requestRestoreConfirmation())
        model.leave()
        assertNull(model.confirmRestore(stale))
        assertNull(model.checkRestore())
        assertFalse(model.beginRestoreFromInspection())
        unlock(model)
        assertTrue(model.beginRestoreFromInspection())
        assertNull(model.confirmRestore(stale))
        assertFalse(peer.commands.any(::isConfirm))
    }

    @Test fun changedSavedDestinationRejectsCapturedConfirmationBeforeAdmission() = runTest {
        val model = ready()
        val confirmation = requireNotNull(model.requestRestoreConfirmation())
        val saved = store.servers.first().single()
        val changed = saved.copy(name = "changed while confirmation was open")
        store.upsert(changed)
        requireNotNull(model.confirmRestore(confirmation)).join()
        assertEquals(Problem.CONNECTION_CHANGED, model.state.value.problem)
        assertFalse(peer.commands.any(::isConfirm))
        assertNull(store.pendingRestore(changed))
    }

    @Test fun cancellationDuringPrivateUploadLeavesNoIntentAndNeverDispatches() = runTest {
        val model = ready()
        peer.uploadEntered = CompletableDeferred()
        peer.blockUpload = CompletableDeferred()
        val run = apply(model)
        peer.uploadEntered.await()
        run.cancelAndJoin()
        assertEquals(Problem.CANCELLED, model.state.value.problem)
        assertNull(store.pendingRestore(store.servers.first().single()))
        assertFalse(peer.commands.any(::isConfirm))
        assertFalse(peer.commands.any(::isVerify))
    }

    @Test fun cancellationAfterDispatchKeepsExactIntentAndDoesNotVerifyOrRetry() = runTest {
        val model = ready()
        peer.blockRestore = CompletableDeferred()
        val run = apply(model)
        peer.restoreEntered.await()
        val saved = store.servers.first().single()
        val attempt = requireNotNull(store.pendingRestore(saved))
        assertEquals(RestoreOutcome.RUNNING, model.state.value.restore?.outcome)
        run.cancelAndJoin()
        assertEquals(Problem.CANCELLED, model.state.value.problem)
        assertEquals(RestoreOutcome.UNCERTAIN, model.state.value.restore?.outcome)
        assertEquals(attempt, store.pendingRestore(saved))
        assertEquals(digest(archive.readBytes()), attempt.ciphertextSha256)
        assertNull(model.requestRestoreConfirmation())
        assertFalse(peer.commands.any(::isVerify))
        assertEquals(1, peer.commands.count(::isConfirm))
    }

    @Test fun lostConfirmedResponseReimportsSameFileAndReconcilesWithoutMutationReplay() = runTest {
        val first = ready()
        peer.restoreFailure = ProvisioningException("synthetic lost confirmed response")
        apply(first).join()
        val saved = store.servers.first().single()
        val pending = requireNotNull(store.pendingRestore(saved))
        assertEquals(RestoreOutcome.UNCERTAIN, first.state.value.restore?.outcome)
        peer.restoreFailure = null
        val next = model()
        requireNotNull(next.loadPendingRestore(saved)).join()
        assertEquals(pending, next.state.value.restore?.attempt)
        assertTrue(peer.commands.none(::isVerify))
        unlock(next)
        requireNotNull(next.reconcileRestore(saved, pending)).join()
        assertEquals(RestoreOutcome.RECONCILED, next.state.value.restore?.outcome)
        assertNull(next.state.value.problem)
        assertNull(store.pendingRestore(store.servers.first().single()))
        assertEquals(1, peer.commands.count(::isConfirm))
        assertEquals(1, peer.commands.count(::isVerify))
        assertFalse(profileFile.exists())
        assertPublic(next)
    }

    @Test fun newEncryptedFileWithIdenticalPublicSnapshotIsRejectedBeforeAnyTransport() = runTest {
        val first = ready()
        peer.restoreFailure = ProvisioningException("synthetic lost reply")
        apply(first).join()
        val saved = store.servers.first().single()
        val pending = requireNotNull(store.pendingRestore(saved))
        val replacement = VpsBackupArchive().encrypt(SNAPSHOT, PASSWORD.toCharArray())
        try { archive.writeBytes(replacement) } finally { replacement.fill(0) }
        val next = model()
        unlock(next)
        val commandCount = peer.commands.size
        val connectionCount = peer.connections.size
        assertNull(next.reconcileRestore(saved, pending))
        assertEquals(Problem.INVALID_INPUT, next.state.value.problem)
        assertEquals(commandCount, peer.commands.size)
        assertEquals(connectionCount, peer.connections.size)
        assertEquals(pending, store.pendingRestore(saved))
    }

    @Test fun wrongPasswordCannotProvideSourceForReconciliation() = runTest {
        val first = ready()
        peer.restoreFailure = ProvisioningException("synthetic lost reply")
        apply(first).join()
        val saved = store.servers.first().single()
        val pending = requireNotNull(store.pendingRestore(saved))
        val next = model()
        requireNotNull(next.inspectBackup(document(), "wrong longer password".toCharArray())).join()
        assertEquals(VpsProvisioningViewModel.BackupFailure.AUTHENTICATION, next.state.value.backupFailure)
        val commands = peer.commands.size
        assertNull(next.reconcileRestore(saved, pending))
        assertEquals(commands, peer.commands.size)
        assertEquals(pending, store.pendingRestore(saved))
    }

    @Test fun typedRolledBackResponseStillKeepsIntentAndNeverAutomaticallyVerifies() = runTest { assertRejectedIntent("restored") }

    @Test fun typedUnchangedResponseStillKeepsIntentAndNeverAutomaticallyVerifies() = runTest { assertRejectedIntent("unchanged") }

    @Test fun invalidConfirmedReceiptKeepsIntentWithoutRunningFollowupVerification() = runTest {
        val model = ready()
        peer.restoreOutput = "{\"ok\":true}"
        apply(model).join()
        assertEquals(Problem.INVALID_RESPONSE, model.state.value.problem)
        assertEquals(RestoreOutcome.UNCERTAIN, model.state.value.restore?.outcome)
        assertNotNull(store.pendingRestore(store.servers.first().single()))
        assertFalse(peer.commands.any(::isVerify))
    }

    @Test fun authoritativeVerificationFailureKeepsIntentAndOriginalMetadata() = runTest {
        val model = ready()
        val saved = store.servers.first().single()
        peer.verifyRestoreFailure = ProvisioningException("synthetic unknown runtime", category = ProvisioningFailureCategory.RUNTIME_UNKNOWN)
        apply(model).join()
        assertEquals(RestoreOutcome.UNCERTAIN, model.state.value.restore?.outcome)
        assertNotNull(store.pendingRestore(saved))
        assertEquals(saved, store.servers.first().single())
        assertEquals(1, peer.commands.count(::isVerify))
        assertFalse(model.canRestore)
    }

    @Test fun retainedRecoveryDirectoryKeepsIntentUntilExplicitVerificationWithoutClearingItsWarning() = runTest {
        val model = ready()
        val saved = store.servers.first().single()
        peer.restoreCleanupPath = "/etc/wireguard/tunnl/recovery/restore.synthetic"
        apply(model).join()
        val attempt = requireNotNull(store.pendingRestore(saved))
        assertEquals(RestoreOutcome.UNCERTAIN, model.state.value.restore?.outcome)
        assertTrue(model.state.value.cleanupRequired)
        assertEquals(saved, model.state.value.cleanupOwner)
        assertEquals(saved, store.servers.first().single())
        assertFalse(peer.commands.any(::isVerify))
        assertNull(model.requestRestoreConfirmation())
        unlock(model)
        requireNotNull(model.reconcileRestore(saved, attempt)).join()
        assertEquals(RestoreOutcome.RECONCILED, model.state.value.restore?.outcome)
        assertNull(store.pendingRestore(store.servers.first().single()))
        assertTrue(model.state.value.cleanupRequired)
        assertEquals(saved, model.state.value.cleanupOwner)
        assertEquals(1, peer.commands.count(::isConfirm))
        assertEquals(1, peer.commands.count(::isVerify))
        assertFalse(model.state.value.toString().contains(peer.restoreCleanupPath))
        assertPublic(model)
    }

    @Test fun cancellationDuringIndependentVerificationRetainsIntentAndOriginalMetadata() = runTest {
        val model = ready()
        val saved = store.servers.first().single()
        peer.blockVerifyRestore = CompletableDeferred()
        val run = apply(model)
        peer.verifyRestoreEntered.await()
        val pending = requireNotNull(store.pendingRestore(saved))
        run.cancelAndJoin()
        assertEquals(RestoreOutcome.UNCERTAIN, model.state.value.restore?.outcome)
        assertEquals(Problem.CANCELLED, model.state.value.problem)
        assertEquals(pending, store.pendingRestore(saved))
        assertEquals(saved, store.servers.first().single())
        assertEquals(1, peer.commands.count(::isConfirm))
    }

    @Test fun invalidVerificationCannotClearAdmittedIntent() = runTest {
        val model = ready()
        val saved = store.servers.first().single()
        peer.verifyRestoreOutput = "{\"ok\":true,\"verifyOnly\":true,\"verified\":true,\"running\":false,\"serviceEnabled\":true}"
        apply(model).join()
        assertEquals(Problem.INVALID_RESPONSE, model.state.value.problem)
        assertEquals(RestoreOutcome.UNCERTAIN, model.state.value.restore?.outcome)
        assertNotNull(store.pendingRestore(saved))
        assertEquals(saved, store.servers.first().single())
    }

    @Test fun staleOperationIdentityCannotReconcileAnExactSameFile() = runTest {
        val first = ready()
        peer.restoreFailure = ProvisioningException("synthetic lost reply")
        apply(first).join()
        val saved = store.servers.first().single()
        val pending = requireNotNull(store.pendingRestore(saved))
        val next = model()
        unlock(next)
        val commands = peer.commands.size
        requireNotNull(next.reconcileRestore(saved, pending.copy(operationId = VpsRestoreFixture.OTHER_OPERATION))).join()
        assertEquals(Problem.RESTORE_PENDING, next.state.value.problem)
        assertEquals(commands, peer.commands.size)
        assertEquals(pending, store.pendingRestore(saved))
    }

    @Test fun corruptSavedCredentialAcquisitionKeepsUncertaintyWithoutDispatchingVerification() = runTest {
        val first = ready()
        peer.restoreFailure = ProvisioningException("synthetic lost confirmed response")
        apply(first).join()
        val saved = store.servers.first().single()
        val pending = requireNotNull(store.pendingRestore(saved))
        val next = model()
        requireNotNull(next.loadPendingRestore(saved)).join()
        unlock(next)
        File(folder.root, "credentials").writeText("synthetic corrupt encrypted credential file")
        val commands = peer.commands.size
        requireNotNull(next.reconcileRestore(saved, pending)).join()
        assertEquals(Problem.STORAGE_FAILED, next.state.value.problem)
        assertEquals(RestoreOutcome.UNCERTAIN, next.state.value.restore?.outcome)
        assertFalse(next.state.value.busy)
        assertEquals(pending, store.pendingRestore(saved))
        assertEquals(commands, peer.commands.size)
        assertFalse(peer.commands.any(::isVerify))
        assertEquals(saved, store.servers.first().single())
    }

    private fun model(): VpsProvisioningViewModel = VpsProvisioningViewModel(
        ApplicationProvider.getApplicationContext(), store, peer, { OBSERVED_AT }, profiles,
    ).also { model ->
        jobs.add(requireNotNull(model.viewModelScope.coroutineContext[Job]))
        owners.add(ViewModelStore().apply { put("owner", model) })
    }

    private suspend fun ready(): VpsProvisioningViewModel = model().also { model ->
        unlock(model)
        assertTrue(model.beginRestoreFromInspection())
        model.edit(model.draft.copy(host = "198.51.100.42", endpoint = PLAN.endpoint, port = PLAN.port,
            fingerprint = VpsRestoreFixture.pin, authMode = AuthMode.PASSWORD))
        model.edit(model.draft.copy(password = "synthetic-ssh-password".toCharArray()))
        requireNotNull(model.detect()).join()
        requireNotNull(model.checkRestore()).join()
        model.reviewApply()
        assertTrue(model.canRestore)
    }

    private suspend fun unlock(model: VpsProvisioningViewModel) {
        requireNotNull(model.inspectBackup(document(), PASSWORD.toCharArray())).join()
        assertNull(model.state.value.backupFailure)
        assertNotNull(model.state.value.backupSummary)
    }

    private fun apply(model: VpsProvisioningViewModel): Job =
        requireNotNull(model.confirmRestore(requireNotNull(model.requestRestoreConfirmation())))

    private suspend fun assertRejectedIntent(recovery: String) {
        val model = ready()
        peer.restoreExitCode = 74
        peer.restoreOutput = JSONObject().put("ok", false).put("error", "apply_failed")
            .put("message", "synthetic failure").put("recovery", recovery).toString()
        apply(model).join()
        assertEquals(RestoreOutcome.UNCERTAIN, model.state.value.restore?.outcome)
        assertNotNull(store.pendingRestore(store.servers.first().single()))
        assertFalse(peer.commands.any(::isVerify))
        assertNull(model.requestRestoreConfirmation())
    }

    private fun document() = VpsBackupDocument(context.contentResolver, VpsBackupInputProvider.URI)
    private fun digest(bytes: ByteArray) = try {
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    } finally { bytes.fill(0) }

    private fun assertPublic(model: VpsProvisioningViewModel) {
        val public = model.state.value.toString()
        for (secret in listOf(PASSWORD, "synthetic-ssh-password", SNAPSHOT.config, VpsProfileFixture.serverKey.privateKey.toBase64(),
            VpsProfileFixture.psk.toBase64(), SNAPSHOT.revoked.single().config)) assertFalse(public.contains(secret))
    }

    private fun isCheck(command: String) = command.contains("./'restore.sh'") && command.contains("'--check'")
    private fun isConfirm(command: String) = command.contains("./'restore.sh'") && command.contains("'--confirm'")
    private fun isVerify(command: String) = command.contains("./'restore.sh'") && command.contains("'--verify'")

    internal companion object {
        const val PASSWORD = "synthetic recovery password"
        const val OBSERVED_AT = 1_710_000_012L
        val PLAN = VpsRestoreCheckPlan("wg0", "destination.example", "51821", "10.8.0.0/24", "1.1.1.1",
            VpsProfileFixture.serverKey.publicKey.toBase64(), 1, 1)
        val SNAPSHOT = VpsBackupResult("wg0", """
            # TUNNL_MANAGED 1
            # ENDPOINT source.example
            # TUNNL_CIDR 10.8.0.0/24
            # TUNNL_DNS 1.1.1.1
            [Interface]
            Address = 10.8.0.1/24
            PrivateKey = ${VpsProfileFixture.serverKey.privateKey.toBase64()}
            ListenPort = 51820
            SaveConfig = false
            PostUp = iptables -t nat -A POSTROUTING -s 10.8.0.0/24 ! -d 10.8.0.0/24 -j MASQUERADE
            PostDown = iptables -t nat -D POSTROUTING -s 10.8.0.0/24 ! -d 10.8.0.0/24 -j MASQUERADE

            # BEGIN_PEER phone
            [Peer]
            PublicKey = ${VpsProfileFixture.client.publicKey.toBase64()}
            PresharedKey = ${VpsProfileFixture.psk.toBase64()}
            AllowedIPs = 10.8.0.2/32
            # END_PEER phone
        """.trimIndent() + "\n", 1, 1, "2026-10-17T07:00:00Z", listOf(VpsRevokedBackup("retired.conf.ABC123",
            "# BEGIN_PEER retired\n[Peer]\nPublicKey = ${com.vpn0wn.crypto.KeyPair().publicKey.toBase64()}\nAllowedIPs = 10.8.0.3/32\n# END_PEER retired\n")))
    }
}
