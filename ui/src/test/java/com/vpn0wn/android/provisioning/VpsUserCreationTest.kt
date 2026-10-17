/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.Problem
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.UserCreation
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.selects.select
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
class VpsUserCreationTest {
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
    private var syncEntered: CountDownLatch? = null
    private var releaseSync: CountDownLatch? = null

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(StandardTestDispatcher())
        VpsUserProfileFileTest.resetNativeFiles()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }
        credentialFile = credentialFileForTest(File(folder.root, "credentials"))
        store = VpsServerStore(preferences, credentialFile)
        profileFile = File(folder.root, "user-profiles")
        profileKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        store.upsert(VpsProfileFixture.server)
        store.saveCredential(VpsProfileFixture.server, PIN, VpsCredential.Password("synthetic-password".toCharArray()))
        peer.statusOutput = status(false)
        peer.addOutput = result()
        peer.afterAdd = { peer.statusOutput = status(true) }
    }

    @After
    fun tearDown() = runTest {
        withContext(NonCancellable) {
            releaseSync?.countDown()
            peer.blockAdd?.complete(Unit)
            peer.blockStatus?.complete(Unit)
            owners.forEach(ViewModelStore::clear)
            try { jobs.forEach { it.cancelAndJoin() } } finally {
                try { scope.coroutineContext[Job]!!.cancelAndJoin() } finally { Dispatchers.resetMain() }
            }
        }
    }

    @Test
    fun confirmedCreateUsesGuardRetainsFullProfileAndPublishesOnlyVerifiedStatus() = runTest {
        val model = ready()
        val confirmation = prepare(model)
        assertNull(model.confirmAddUser(confirmation.copy()))
        val creating = requireNotNull(model.confirmAddUser(confirmation))
        assertNull(model.confirmAddUser(confirmation))
        creating.join()
        assertEquals(UserCreation.CREATED, model.state.value.creation)
        assertNull(model.state.value.problem)
        assertFalse(model.state.value.pendingAdd)
        val saved = server()
        assertNull(store.pendingAdd(saved))
        assertEquals(VpsProfileFixture.client.publicKey.toBase64(),
            model.state.value.statusSnapshots.getValue(saved.id).status.peers.single().publicKey)
        val profile = requireNotNull(profiles().load(saved, SERVER_KEY, CLIENT_KEY))
        assertEquals(VpsProfileFixture.profile().payload(), profile.payload())
        assertFalse(profileFile.readBytes().toString(Charsets.ISO_8859_1).contains(VpsProfileFixture.client.privateKey.toBase64()))
        assertFalse(model.state.value.toString().contains(VpsProfileFixture.client.privateKey.toBase64()))
        assertEquals(1, addCommands().size)
        assertTrue(addCommands().single().contains("'--expected-server-public-key' '$SERVER_KEY'"))
        assertTrue(peer.connections.all { (it.credential as VpsCredential.Password).value.all { character -> character == '\u0000' } })
    }

    @Test
    fun missingIdentityAndInvalidNamesCannotRequestConfirmationOrSendAdd() = runTest {
        peer.statusOutput = JSONObject(status(false)).apply { remove("serverPublicKey") }.toString()
        val model = ready()
        requireNotNull(model.requestAddUserConfirmation(server(), "phone")).join()
        assertEquals(Problem.IDENTITY_UNAVAILABLE, model.state.value.problem)
        assertNull(model.state.value.addConfirmation)
        for (name in listOf("", "invalid name", "phone\nother", "x".repeat(33))) {
            requireNotNull(model.requestAddUserConfirmation(server(), name)).join()
            assertEquals(Problem.INVALID_INPUT, model.state.value.problem)
        }
        assertTrue(addCommands().isEmpty())
        assertTrue(pending().isEmpty())
    }

    @Test
    fun observedDuplicateNameIsRejectedBeforeAdmissionAndKnownScriptRejectionDoesNotBecomeLostReply() = runTest {
        peer.statusOutput = status(true)
        val model = ready()
        requireNotNull(model.requestAddUserConfirmation(server(), "phone")).join()
        assertEquals(Problem.INVALID_INPUT, model.state.value.problem)
        assertTrue(addCommands().isEmpty())
        peer.statusOutput = status(false)
        requireNotNull(model.syncStatus(server())).join()
        val confirmation = prepare(model)
        peer.addExitCode = 73
        peer.addOutput = JSONObject().put("ok", false).put("error", "peer_exists").put("message", "synthetic").toString()
        requireNotNull(model.confirmAddUser(confirmation)).join()
        assertTrue(pending().isEmpty())
        assertFalse(model.state.value.pendingAdd)
        assertEquals(1, addCommands().size)
    }

    @Test
    fun changedPinOrFullServerRecordBeforeAdmissionBlocksRemoteMutation() = runTest {
        val model = ready()
        val confirmation = prepare(model)
        store.saveCredential(server(), OTHER_PIN, VpsCredential.Password("replacement".toCharArray()))
        requireNotNull(model.confirmAddUser(confirmation)).join()
        assertEquals(Problem.CONNECTION_CHANGED, model.state.value.problem)
        assertTrue(addCommands().isEmpty())
        assertTrue(pending().isEmpty())
        store.saveCredential(server(), PIN, VpsCredential.Password("restored".toCharArray()))
        val next = prepare(model)
        store.upsert(server().copy(name = "Changed"))
        requireNotNull(model.confirmAddUser(next)).join()
        assertEquals(Problem.CONNECTION_CHANGED, model.state.value.problem)
        assertTrue(addCommands().isEmpty())
        assertTrue(pending().isEmpty())
    }

    @Test
    fun lostReplyStaysFencedAcrossOwnerRecreationAndCannotReconstructPrivateProfile() = runTest {
        val model = ready()
        peer.addFailure = ProvisioningException("synthetic lost reply")
        requireNotNull(model.confirmAddUser(prepare(model))).join()
        assertTrue(model.state.value.pendingAdd)
        assertEquals(UserCreation.UNCERTAIN, model.state.value.creation)
        assertNull(pending().single().clientPublicKey)
        assertFalse(profileFile.exists())
        val recreated = ready()
        requireNotNull(recreated.requestAddUserConfirmation(server(), "second")).join()
        assertEquals(Problem.ADD_PENDING, recreated.state.value.problem)
        requireNotNull(recreated.reconcileAddUser(server())).join()
        assertEquals(Problem.ADD_PENDING, recreated.state.value.problem)
        assertTrue(recreated.state.value.pendingAdd)
        assertFalse(profileFile.exists())
        assertEquals(1, addCommands().size)
    }

    @Test
    fun malformedPrivateReceiptCannotClearDurableAdmissionOrCreateAProfile() = runTest {
        val model = ready()
        peer.addOutput = JSONObject(result()).put("config", "synthetic-private-invalid").toString()
        requireNotNull(model.confirmAddUser(prepare(model))).join()
        assertEquals(Problem.INVALID_RESPONSE, model.state.value.problem)
        assertTrue(model.state.value.pendingAdd)
        assertEquals(UserCreation.UNCERTAIN, model.state.value.creation)
        assertFalse(profileFile.exists())
        assertEquals(1, pending().size)
    }

    @Test
    fun receivedValidProfileWithLocalFailureCanRetryOnlyLocalRetention() = runTest {
        val model = ready()
        failProfileSync = true
        requireNotNull(model.confirmAddUser(prepare(model))).join()
        assertEquals(UserCreation.UNSAVED, model.state.value.creation)
        assertEquals(Problem.STORAGE_FAILED, model.state.value.problem)
        assertEquals(1, addCommands().size)
        failProfileSync = false
        requireNotNull(model.reconcileAddUser(server())).join()
        assertEquals(UserCreation.CREATED, model.state.value.creation)
        assertFalse(model.state.value.pendingAdd)
        assertEquals(1, addCommands().size)
        assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
    }

    @Test
    fun encryptedReceiptSurvivesSeparateFenceWriteFailureAndReconcilesWithoutAnotherAdd() = runTest {
        var rejectReceiptWrite = true
        val interruptedPreferences = object : DataStore<Preferences> {
            override val data = preferences.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                preferences.updateData { current ->
                    val updated = transform(current)
                    val attempts = PendingVpsAdd.decode(updated[stringPreferencesKey("vps_pending_adds")].orEmpty())
                    if (rejectReceiptWrite && attempts.any { it.clientPublicKey != null }) {
                        rejectReceiptWrite = false
                        throw IOException("synthetic receipt metadata failure")
                    }
                    updated
                }
        }
        val interruptedStore = VpsServerStore(interruptedPreferences, credentialFile)
        val model = ready(interruptedStore)
        requireNotNull(model.confirmAddUser(prepare(model))).join()
        assertEquals(Problem.STORAGE_FAILED, model.state.value.problem)
        assertEquals(UserCreation.RETAINED, model.state.value.creation)
        assertNull(pending().single().clientPublicKey)
        assertNull(model.state.value.creationOwner)
        assertFalse(model.state.value.statusSnapshots.containsKey(server().id))
        assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        val recreated = model()
        requireNotNull(recreated.reconcileAddUser(server())).join()
        assertEquals(UserCreation.CREATED, recreated.state.value.creation)
        assertEquals(1, addCommands().size)
    }

    @Test
    fun uncertainReceiptRetiresTargetAliasesWithoutDiscardingOtherServersStatus() = runTest {
        val original = server()
        val alias = original.copy(id = "alias", name = "Alias")
        val unrelated = original.copy(id = "unrelated", name = "Unrelated", host = "203.0.113.11")
        for (saved in listOf(alias, unrelated)) {
            store.upsert(saved)
            store.saveCredential(saved, PIN, VpsCredential.Password("synthetic-password".toCharArray()))
        }
        val model = model()
        for (saved in listOf(alias, unrelated, original)) requireNotNull(model.syncStatus(saved)).join()
        val current = store.servers.first().single { it.id == original.id }
        requireNotNull(model.requestAddUserConfirmation(current, "phone")).join()
        peer.blockAdd = CompletableDeferred()
        val creating = requireNotNull(model.confirmAddUser(requireNotNull(model.state.value.addConfirmation)))
        peer.addEntered.await()
        store.saveCredential(current, OTHER_PIN, VpsCredential.Password("replacement".toCharArray()))
        peer.blockAdd!!.complete(Unit)
        creating.join()
        assertEquals(Problem.CONNECTION_CHANGED, model.state.value.problem)
        assertTrue(model.state.value.pendingAdd)
        assertNull(model.state.value.creationOwner)
        assertEquals(setOf(unrelated.id), model.state.value.statusSnapshots.keys)
        assertNotNull(profiles().load(current, SERVER_KEY, CLIENT_KEY))
        assertEquals(CLIENT_KEY, pending().single().clientPublicKey)
        assertEquals(1, addCommands().size)
    }

    @Test
    fun unknownPendingPayloadsRetireSelectedTrustWithoutInventingMutationOwners() = runTest {
        val failures = listOf(VpsServerStore.InstallPendingException(), VpsServerStore.AddPendingException(),
            VpsServerStore.RemovePendingException(), VpsServerStore.RotatePendingException(), VpsServerStore.RestorePendingException())
        for (failure in failures) {
            val interrupted = object : DataStore<Preferences> {
                override val data = preferences.data
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                    preferences.updateData { current ->
                        val updated = transform(current)
                        if (PendingVpsAdd.decode(updated[stringPreferencesKey("vps_pending_adds")].orEmpty()).isNotEmpty()) throw failure
                        updated
                    }
            }
            val model = ready(VpsServerStore(interrupted, credentialFile))
            requireNotNull(model.confirmAddUser(prepare(model))).join()
            assertFalse(failure.javaClass.simpleName, model.state.value.statusSnapshots.containsKey(server().id))
            when (failure) {
                is VpsServerStore.InstallPendingException -> assertNull(model.state.value.installationOwner)
                is VpsServerStore.AddPendingException -> assertNull(model.state.value.creationOwner)
                is VpsServerStore.RemovePendingException -> assertNull(model.state.value.removalOwner)
                is VpsServerStore.RotatePendingException -> assertNull(model.state.value.rotationOwner)
                is VpsServerStore.RestorePendingException -> assertNull(model.state.value.restore?.attempt)
            }
            assertTrue(pending().isEmpty())
        }
        assertTrue(addCommands().isEmpty())
    }

    @Test
    fun changingPendingOwnerRetiresOriginalAndSshAliasTrustButKeepsFreshIdenticalStatus() = runTest {
        val original = server()
        val alias = original.copy(id = "alias", name = "Alias")
        val unrelated = original.copy(id = "unrelated", name = "Unrelated", host = "203.0.113.11")
        for (saved in listOf(alias, unrelated)) {
            store.upsert(saved)
            store.saveCredential(saved, PIN, VpsCredential.Password("synthetic-password".toCharArray()))
        }
        val model = model()
        for (saved in listOf(alias, unrelated, original)) requireNotNull(model.syncStatus(saved)).join()
        val current = store.servers.first().single { it.id == original.id }
        val attempt = store.admitAdd(current, PIN, SERVER_KEY, VpsProfileFixture.request.copy(dns = current.dns))
        requireNotNull(model.syncStatus(current)).join()
        assertTrue(model.state.value.pendingAdd)
        val before = model.state.value.statusSnapshots.getValue(unrelated.id)
        val other = store.servers.first().single { it.id == unrelated.id }
        requireNotNull(model.syncStatus(other)).join()
        assertFalse(model.state.value.pendingAdd)
        assertEquals(setOf(unrelated.id), model.state.value.statusSnapshots.keys)
        val after = model.state.value.statusSnapshots.getValue(unrelated.id)
        assertEquals(before.server, after.server)
        assertEquals(before.status, after.status)
        assertEquals(before.observedAtEpochSeconds, after.observedAtEpochSeconds)
        assertNotSame(before, after)
        assertEquals(attempt, store.pendingAdd(current))
        assertTrue(addCommands().isEmpty())
    }

    @Test
    fun sameTargetSettledElsewhereRetainsFreshEqualObservationWithNewIdentity() = runTest {
        val model = ready()
        val expected = server()
        val attempt = store.admitAdd(expected, PIN, SERVER_KEY, VpsProfileFixture.request.copy(dns = expected.dns))
        requireNotNull(model.syncStatus(expected)).join()
        val before = model.state.value.statusSnapshots.getValue(expected.id)
        store.clearRejectedAdd(expected, attempt)
        requireNotNull(model.syncStatus(expected)).join()
        val after = model.state.value.statusSnapshots.getValue(expected.id)
        assertFalse(model.state.value.pendingAdd)
        assertEquals(before.server, after.server)
        assertEquals(before.status, after.status)
        assertEquals(before.observedAtEpochSeconds, after.observedAtEpochSeconds)
        assertNotSame(before, after)
        assertNull(store.pendingAdd(expected))
        assertTrue(addCommands().isEmpty())
    }

    @Test fun pendingRemovalOwnerLookupFailureRetiresAliasTrustBeforeSuspension() = runTest {
        assertPendingOwnerLookup(rotation = false, cancel = false)
    }

    @Test fun pendingRemovalOwnerLookupCancellationRetainsFenceAndHidesForeignReceipt() = runTest {
        assertPendingOwnerLookup(rotation = false, cancel = true)
    }

    @Test fun pendingRotationOwnerLookupFailureRetiresAliasTrustBeforeSuspension() = runTest {
        assertPendingOwnerLookup(rotation = true, cancel = false)
    }

    @Test fun pendingRotationOwnerLookupCancellationRetainsFenceAndHidesForeignReceipt() = runTest {
        assertPendingOwnerLookup(rotation = true, cancel = true)
    }

    @Test fun sameOwnerRotationRemainsFencedAfterSavedAccessDetectionWithMatchingPin() = runTest {
        val model = ready()
        val expected = server()
        val profile = profiles().saveGenerated(expected, SERVER_KEY, VpsProfileFixture.request, VpsProfileFixture.result())
        val attempt = store.admitRotation(expected, PIN, profile, profiles())
        requireNotNull(model.reuse(expected)).join()
        assertTrue(model.state.value.pendingRotation)
        assertEquals(expected, model.state.value.rotationOwner)
        assertEquals(attempt, model.state.value.rotationAttempt)
        assertFalse(model.state.value.statusSnapshots.containsKey(expected.id))
        assertFalse(model.canInstall)
        assertEquals(attempt, store.pendingRotation(expected))
        assertFalse(peer.commands.any { it.contains("./'rotate-peer.sh'") || it.contains("./'verify-rotation.sh'") })
    }

    @Test fun sameOwnerNewRevocationReplacesOldOperationReceiptDuringPreflight() = runTest {
        assertRemovalReceiptRefresh(PendingVpsRemove.Action.REVOKE)
    }

    @Test fun sameOwnerRemovalNeverRetainsOldRevocationReceiptDuringPreflight() = runTest {
        assertRemovalReceiptRefresh(PendingVpsRemove.Action.REMOVE)
    }

    private suspend fun assertRemovalReceiptRefresh(action: PendingVpsRemove.Action) {
        val model = ready()
        val expected = server()
        val original = PendingVpsRemove(expected.id, VpsEndpoint(expected.host, expected.sshPort, expected.sshUser),
            PIN, SERVER_KEY, "phone", CLIENT_KEY, PendingVpsRemove.Action.REVOKE, VpsRestoreFixture.attempt.operationId)
        store.admitRemove(expected, original)
        requireNotNull(model.syncStatus(expected)).join()
        assertEquals(original, model.state.value.removalAttempt)
        val current = original.copy(action = action, operationId = VpsRestoreFixture.OTHER_OPERATION.takeIf { action == PendingVpsRemove.Action.REVOKE })
        store.clearRejectedRemove(expected, original)
        store.admitRemove(expected, current)
        val commands = peer.commands.size
        requireNotNull(model.requestAddUserConfirmation(expected, "second")).join()
        assertEquals(Problem.REMOVE_PENDING, model.state.value.problem)
        assertEquals(action, model.state.value.removalAction)
        assertEquals(expected, model.state.value.removalOwner)
        assertEquals(current, model.state.value.removalAttempt)
        assertFalse(model.state.value.toString().contains(requireNotNull(original.operationId)))
        assertEquals(current, store.pendingRemove(expected))
        assertEquals(commands, peer.commands.size)
    }

    private suspend fun assertPendingOwnerLookup(rotation: Boolean, cancel: Boolean) {
        val original = server()
        val alias = original.copy(id = "alias", name = "Alias")
        val unrelated = original.copy(id = "unrelated", name = "Unrelated", host = "203.0.113.11")
        for (saved in listOf(alias, unrelated)) {
            store.upsert(saved)
            store.saveCredential(saved, PIN, VpsCredential.Password("synthetic-password".toCharArray()))
        }
        lateinit var observedModel: VpsProvisioningViewModel
        var interruptLookup = false
        val entered = CompletableDeferred<Unit>()
        val interrupted = object : DataStore<Preferences> {
            override val data = preferences.data.onEach {
                val state = if (interruptLookup) observedModel.state.value else null
                if (state != null && state.serverId == alias.id && (if (rotation) state.pendingRotation else state.pendingRemove)) {
                    entered.complete(Unit)
                    if (cancel) awaitCancellation() else throw IOException("synthetic foreign owner inventory failure")
                }
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = preferences.updateData(transform)
        }
        observedModel = model(VpsServerStore(interrupted, credentialFile))
        for (saved in listOf(original, alias, unrelated)) requireNotNull(observedModel.syncStatus(saved)).join()
        val current = store.servers.first().single { it.id == original.id }
        val currentAlias = store.servers.first().single { it.id == alias.id }
        val removal = if (!rotation) store.admitRemove(current, PendingVpsRemove(current.id,
            VpsEndpoint(current.host, current.sshPort, current.sshUser), PIN, SERVER_KEY, "phone", CLIENT_KEY,
            PendingVpsRemove.Action.REVOKE, VpsRestoreFixture.OTHER_OPERATION)) else null
        val rotate = if (rotation) {
            val profile = profiles().saveGenerated(current, SERVER_KEY, VpsProfileFixture.request, VpsProfileFixture.result())
            store.admitRotation(current, PIN, profile, profiles())
        } else null
        val commands = peer.commands.size
        interruptLookup = true
        val checking = requireNotNull(observedModel.requestAddUserConfirmation(currentAlias, "second"))
        val reachedLookup = select {
            entered.onAwait { true }
            checking.onJoin { false }
        }
        assertTrue("Known marker publishes its fence before foreign inventory access", reachedLookup)
        if (cancel) checking.cancelAndJoin() else checking.join()
        val state = observedModel.state.value
        assertFalse(state.busy)
        assertEquals(if (cancel) Problem.CANCELLED else Problem.STORAGE_FAILED, state.problem)
        assertEquals(setOf(unrelated.id), state.statusSnapshots.keys)
        if (rotation) {
            assertTrue(state.pendingRotation)
            assertNull(state.rotationOwner)
            assertNull(state.rotationAttempt)
            assertEquals(rotate, store.pendingRotation(current))
        } else {
            assertTrue(state.pendingRemove)
            assertEquals(PendingVpsRemove.Action.REVOKE, state.removalAction)
            assertNull(state.removalOwner)
            assertNull(state.removalAttempt)
            assertEquals(removal, store.pendingRemove(current))
        }
        assertEquals(commands, peer.commands.size)
        assertTrue(addCommands().isEmpty())
    }

    @Test
    fun cancellationDuringReceivedReceiptSettlesEncryptedPersistenceBeforeReturning() = runTest {
        val model = ready()
        val confirmation = prepare(model)
        syncEntered = CountDownLatch(1)
        releaseSync = CountDownLatch(1)
        val creating = requireNotNull(model.confirmAddUser(confirmation))
        try {
            assertTrue(withContext(Dispatchers.IO) { syncEntered!!.await(WAIT_SECONDS, TimeUnit.SECONDS) })
            creating.cancel()
            assertFalse(creating.isCompleted)
        } finally {
            releaseSync!!.countDown()
        }
        creating.join()
        assertTrue(creating.isCancelled)
        assertTrue(model.state.value.pendingAdd)
        assertEquals(CLIENT_KEY, pending().single().clientPublicKey)
        assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
        val recreated = model()
        requireNotNull(recreated.reconcileAddUser(server())).join()
        assertEquals(UserCreation.CREATED, recreated.state.value.creation)
        assertEquals(1, addCommands().size)
    }

    @Test
    fun admittedCancellationBeforeReplyPreservesFenceAndDrainsOwnedConnection() = runTest {
        val model = ready()
        peer.blockAdd = CompletableDeferred()
        val creating = requireNotNull(model.confirmAddUser(prepare(model)))
        peer.addEntered.await()
        creating.cancelAndJoin()
        assertTrue(model.state.value.pendingAdd)
        assertNull(pending().single().clientPublicKey)
        assertFalse(profileFile.exists())
        assertEquals(peer.connections.size, peer.closes.get())
        assertTrue((peer.connections.last().credential as VpsCredential.Password).value.all { it == '\u0000' })
    }

    @Test
    fun pinChangedAfterAdmissionRetainsOriginalProfileButWithholdsCurrentOwnerPublication() = runTest {
        val model = ready()
        peer.blockAdd = CompletableDeferred()
        val creating = requireNotNull(model.confirmAddUser(prepare(model)))
        peer.addEntered.await()
        val original = server()
        store.saveCredential(original, OTHER_PIN, VpsCredential.Password("replacement".toCharArray()))
        peer.blockAdd!!.complete(Unit)
        creating.join()
        assertEquals(Problem.CONNECTION_CHANGED, model.state.value.problem)
        assertTrue(model.state.value.pendingAdd)
        assertNotNull(profiles().load(original, SERVER_KEY, CLIENT_KEY))
        assertEquals(CLIENT_KEY, pending().single().clientPublicKey)
        assertEquals(original, server())
        val current = requireNotNull(store.loadConnection(original))
        try { assertEquals(OTHER_PIN, current.hostKeyFingerprint) } finally {
            (current.credential as VpsCredential.Password).clear()
        }
    }

    @Test
    fun replacedRecordAfterAdmissionReceivesNoOriginalTargetRetentionClaim() = runTest {
        val model = ready()
        peer.blockAdd = CompletableDeferred()
        val creating = requireNotNull(model.confirmAddUser(prepare(model)))
        peer.addEntered.await()
        val original = server()
        val replacement = original.copy(name = "Replacement")
        store.upsert(replacement)
        peer.blockAdd!!.complete(Unit)
        creating.join()
        assertEquals(Problem.CONNECTION_CHANGED, model.state.value.problem)
        assertNull(model.state.value.creationOwner)
        assertNotEquals(replacement, model.state.value.creationOwner)
        assertEquals(replacement, server())
        assertNotNull(profiles().load(original, SERVER_KEY, CLIENT_KEY))
        assertTrue(model.state.value.pendingAdd)
    }

    @Test
    fun statusKeyOrPeerMismatchAfterRetentionCannotReportCreationComplete() = runTest {
        val model = ready()
        peer.afterAdd = { peer.statusOutput = JSONObject(status(true)).put("serverPublicKey", VpsProfileFixture.client.publicKey.toBase64()).toString() }
        requireNotNull(model.confirmAddUser(prepare(model))).join()
        assertEquals(Problem.INVALID_RESPONSE, model.state.value.problem)
        assertEquals(UserCreation.RETAINED, model.state.value.creation)
        assertTrue(model.state.value.pendingAdd)
        assertNotNull(profiles().load(server(), SERVER_KEY, CLIENT_KEY))
    }

    @Test
    fun durableIntentBlocksInstallSameTargetAndStrictCodecPreservesReceiptIdentity() = runTest {
        val expected = server()
        val attempt = store.admitAdd(expected, PIN, SERVER_KEY, VpsProfileFixture.request.copy(dns = expected.dns))
        val recorded = store.recordAddReceipt(attempt, CLIENT_KEY)
        assertEquals(recorded, PendingVpsAdd.decode(PendingVpsAdd.encode(listOf(recorded))).single())
        val failed = runCatching { store.admitInstall(expected, VpsEndpoint(expected.host), VpsInstallRequest(expected.endpoint, expected.cidr, expected.dns)) }.exceptionOrNull()
        assertTrue(failed is VpsServerStore.AddPendingException)
        assertNull(store.pendingInstall(expected))
        assertThrows(IOException::class.java) {
            PendingVpsAdd.decode(JSONArray(PendingVpsAdd.encode(listOf(recorded))).apply {
                getJSONObject(0).put("privateKey", "synthetic-secret")
            }.toString())
        }
        assertFalse(PendingVpsAdd.encode(listOf(recorded)).contains("synthetic-password"))
    }

    private suspend fun ready(serverStore: VpsServerStore = store): VpsProvisioningViewModel =
        model(serverStore).also { requireNotNull(it.syncStatus(server())).join() }

    private fun model(serverStore: VpsServerStore = store) = VpsProvisioningViewModel(
        ApplicationProvider.getApplicationContext(), serverStore, peer, { OBSERVED_AT }, profiles(),
    ).also {
        jobs.add(requireNotNull(it.viewModelScope.coroutineContext[Job]))
        owners.add(ViewModelStore().apply { put("owner", it) })
    }

    private suspend fun prepare(model: VpsProvisioningViewModel): VpsProvisioningViewModel.AddUserConfirmation {
        requireNotNull(model.requestAddUserConfirmation(server(), "phone")).join()
        return requireNotNull(model.state.value.addConfirmation)
    }

    private fun profiles() = VpsUserProfileStore(VpsUserProfileFile(profileFile, { profileKey }, sync = { stream ->
        syncEntered?.countDown()
        releaseSync?.let { check(it.await(WAIT_SECONDS, TimeUnit.SECONDS)) }
        if (failProfileSync) throw IOException("synthetic profile sync failure")
        stream.fd.sync()
    }))

    private suspend fun server() = store.servers.first().single()

    private suspend fun pending() = PendingVpsAdd.decode(preferences.data.first()[stringPreferencesKey("vps_pending_adds")].orEmpty())

    private fun addCommands() = peer.commands.filter { it.contains("./'add-peer.sh'") }

    private fun result(): String = VpsProfileFixture.result().let {
        JSONObject().put("ok", true).put("name", it.name).put("address", it.address).put("publicKey", it.publicKey).put("config", it.config).toString()
    }

    private fun status(hasPeer: Boolean): String = JSONObject().put("ok", true).put("interface", "wg0").put("running", true)
        .put("endpoint", VpsProfileFixture.server.endpoint).put("listenPort", "51820").put("cidr", "10.8.0.0/24")
        .put("dns", "1.1.1.1").put("serverPublicKey", SERVER_KEY)
        .put("peers", JSONArray().apply {
            if (hasPeer) put(JSONObject().put("name", "phone").put("publicKey", CLIENT_KEY).put("allowedIps", "10.8.0.2/32")
                .put("latestHandshake", JSONObject.NULL).put("rxBytes", JSONObject.NULL).put("txBytes", JSONObject.NULL))
        }).toString()

    private companion object {
        const val OBSERVED_AT = 1_710_000_012L
        const val WAIT_SECONDS = 10L
        val SERVER_KEY = VpsProfileFixture.serverKey.publicKey.toBase64()
        val CLIENT_KEY = VpsProfileFixture.client.publicKey.toBase64()
        val PIN = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        val OTHER_PIN = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() })
    }
}
