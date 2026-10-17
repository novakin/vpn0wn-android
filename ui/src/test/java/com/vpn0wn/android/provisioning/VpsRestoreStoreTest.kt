/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vpn0wn.android.util.AppDataTransfer
import java.io.File
import java.io.IOException
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
class VpsRestoreStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var scope: CoroutineScope
    private lateinit var preferences: DataStore<Preferences>
    private lateinit var store: VpsServerStore
    private lateinit var credentials: VpsCredentialFile
    private var failBeforeWrite = false
    private var failAfterWrite = false
    private var beforeWrite: (() -> Unit)? = null

    @Before fun setup() = runBlocking {
        VpsUserProfileFileTest.resetNativeFiles()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val delegate = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }
        preferences = object : DataStore<Preferences> {
            override val data = delegate.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                val next = delegate.updateData { current ->
                    val updated = transform(current)
                    beforeWrite?.invoke()
                    if (failBeforeWrite) throw IOException("synthetic write failure")
                    updated
                }
                if (failAfterWrite) throw IOException("synthetic lost write receipt")
                return next
            }
        }
        credentials = credentialFileForTest(File(folder.root, "credentials"))
        store = VpsServerStore(preferences, credentials)
        store.upsert(SERVER)
        store.saveCredential(SERVER, PIN, VpsCredential.Password("synthetic-password".toCharArray()))
    }

    @After fun teardown() = runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }

    @Test fun admissionPersistsExactIntentAcrossStoreReconstructionWithoutChangingFleet() = runBlocking {
        assertEquals(ATTEMPT, store.admitRestore(SERVER, ATTEMPT))
        assertEquals(ATTEMPT, VpsServerStore(preferences, credentials).pendingRestore(SERVER))
        assertEquals(listOf(SERVER), store.servers.first())
        assertEquals(ATTEMPT, PendingVpsRestore.decode(preferences.data.first()[RESTORES]!!).single())
    }

    @Test fun failedAdmissionDoesNotLeaveIntentOrAlterMetadata() = runBlocking {
        failBeforeWrite = true
        assertTrue(runCatching { store.admitRestore(SERVER, ATTEMPT) }.exceptionOrNull() is IOException)
        assertNull(store.pendingRestore(SERVER))
        assertEquals(listOf(SERVER), store.servers.first())
    }

    @Test fun lostAdmissionWriteReceiptKeepsDurableIntentAndRejectsRetry() = runBlocking {
        failAfterWrite = true
        assertTrue(runCatching { store.admitRestore(SERVER, ATTEMPT) }.exceptionOrNull() is IOException)
        failAfterWrite = false
        assertEquals(ATTEMPT, store.pendingRestore(SERVER))
        assertRestorePending { store.admitRestore(SERVER, ATTEMPT) }
    }

    @Test fun cancellationAfterAdmissionRetainsIntentWithoutReturningSuccess() = runBlocking {
        val operation = Job()
        beforeWrite = { operation.cancel() }
        var returnedSuccess = false
        var failure: Throwable? = null
        val child = launch(operation) {
            try {
                store.admitRestore(SERVER, ATTEMPT)
                returnedSuccess = true
            } catch (caught: Throwable) { failure = caught; throw caught }
        }
        child.join()
        beforeWrite = null
        assertFalse(returnedSuccess)
        assertTrue(failure is CancellationException)
        assertTrue(child.isCancelled)
        assertEquals(ATTEMPT, store.pendingRestore(SERVER))
    }

    @Test fun exactVerificationAtomicallyUpdatesOnlyDestinationMetadataAndRemovesMarker() = runBlocking {
        val other = SERVER.copy(id = "other", host = "198.51.100.2")
        store.upsert(other)
        store.admitRestore(SERVER, ATTEMPT)
        val updated = store.settleRestore(SERVER, ATTEMPT, PROOF, 123L)
        assertEquals(SERVER.copy(endpoint = PROOF.endpoint, listenPort = PROOF.port, cidr = PROOF.cidr, dns = PROOF.dns,
            running = true, lastSyncEpochSeconds = 123L), updated)
        assertEquals(setOf(updated, other), store.servers.first().toSet())
        assertNull(store.pendingRestore(updated))
        val connection = requireNotNull(store.loadConnection(updated))
        try { assertEquals(PIN, connection.hostKeyFingerprint) } finally { (connection.credential as VpsCredential.Password).clear() }
    }

    @Test fun failedSettlementPreservesBothOldMetadataAndIntentForLaterVerification() = runBlocking {
        store.admitRestore(SERVER, ATTEMPT)
        failBeforeWrite = true
        assertTrue(runCatching { store.settleRestore(SERVER, ATTEMPT, PROOF, 123L) }.exceptionOrNull() is IOException)
        assertEquals(listOf(SERVER), store.servers.first())
        assertEquals(ATTEMPT, store.pendingRestore(SERVER))
        failBeforeWrite = false
        assertNull(store.pendingRestore(store.settleRestore(SERVER, ATTEMPT, PROOF, 124L)))
    }

    @Test fun cancellationAfterSettlementCannotSplitMetadataFromMarkerRemoval() = runBlocking {
        store.admitRestore(SERVER, ATTEMPT)
        val operation = Job()
        beforeWrite = { operation.cancel() }
        var returnedSuccess = false
        var failure: Throwable? = null
        val child = launch(operation) {
            try {
                store.settleRestore(SERVER, ATTEMPT, PROOF, 123L)
                returnedSuccess = true
            } catch (caught: Throwable) { failure = caught; throw caught }
        }
        child.join()
        beforeWrite = null
        assertFalse(returnedSuccess)
        assertTrue(failure is CancellationException)
        assertTrue(child.isCancelled)
        val updated = store.servers.first().single()
        assertEquals(PROOF.endpoint, updated.endpoint)
        assertEquals(123L, updated.lastSyncEpochSeconds)
        assertNull(store.pendingRestore(updated))
    }

    @Test fun previousIdenticalSnapshotAttemptCannotSettleNewAttempt() = runBlocking {
        store.admitRestore(SERVER, ATTEMPT)
        val restored = store.settleRestore(SERVER, ATTEMPT, PROOF, 123L)
        val next = ATTEMPT.copy(operationId = VpsRestoreFixture.OTHER_OPERATION)
        store.admitRestore(restored, next)
        val failure = restorePendingFailure { store.settleRestore(restored, ATTEMPT, PROOF, 124L) }
        assertEquals(next, failure.attempt)
        assertEquals(restored, failure.target)
        assertEquals(next, store.pendingRestore(restored))
    }

    @Test fun stoppedDisabledOrMismatchedVerificationNeverSettles() = runBlocking {
        store.admitRestore(SERVER, ATTEMPT)
        for (proof in listOf(PROOF.copy(running = false), PROOF.copy(serviceEnabled = false), PROOF.copy(endpoint = "other.test"),
            PROOF.copy(dns = "1.1.1.1"), PROOF.copy(revokedCount = 0), PROOF.copy(serverPublicKey = "other"))) {
            assertRestorePending { store.settleRestore(SERVER, ATTEMPT, proof, 123L) }
        }
        assertEquals(listOf(SERVER), store.servers.first())
        assertEquals(ATTEMPT, store.pendingRestore(SERVER))
    }

    @Test fun oldAttemptDigestPlanPinOrSshCannotSettleCurrentIntent() = runBlocking {
        store.admitRestore(SERVER, ATTEMPT)
        for (attempt in listOf(ATTEMPT.copy(operationId = VpsRestoreFixture.OTHER_OPERATION), ATTEMPT.copy(ciphertextSha256 = "cd".repeat(32)),
            ATTEMPT.copy(plan = ATTEMPT.plan.copy(peerCount = 2)), ATTEMPT.copy(ssh = ATTEMPT.ssh.copy(user = "other")))) {
            assertRestorePending { store.settleRestore(SERVER, attempt, PROOF, 123L) }
        }
        val otherPin = "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { 6 })
        assertTrue(runCatching { store.settleRestore(SERVER, ATTEMPT.copy(fingerprint = otherPin), PROOF, 123L) }.exceptionOrNull()
            is VpsServerStore.CredentialConflictException)
        assertEquals(ATTEMPT, store.pendingRestore(SERVER))
    }

    @Test fun pendingRestoreBlocksEveryCompetingWriterButRetainsReadAccessAndExactNoOp() = runBlocking {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val profileFile = VpsUserProfileFile(File(folder.root, "profiles"), { key })
        val profiles = VpsUserProfileStore(profileFile)
        val profile = profiles.saveGenerated(SERVER, SERVER_KEY, VpsProfileFixture.request, VpsProfileFixture.result())
        store.admitRestore(SERVER, ATTEMPT)
        val previousProfileBytes = File(folder.root, "profiles").readBytes()
        val operations: List<suspend () -> Unit> = listOf(
            { store.upsert(SERVER.copy(name = "changed")) }, { store.upsert(SERVER.copy(host = "198.51.100.9")) },
            { store.remove(SERVER.id) }, { store.updateStatus(SERVER, status(), 123L) },
            { store.saveCredential(SERVER, PIN, VpsCredential.Password("replacement".toCharArray())) }, { store.clearCredential(SERVER.id) },
            { store.admitInstall(SERVER, ATTEMPT.ssh, VpsInstallRequest(SERVER.endpoint, SERVER.cidr, SERVER.dns)) },
            { store.admitAdd(SERVER, PIN, SERVER_KEY, VpsProfileFixture.request.copy(dns = SERVER.dns)) },
            { store.admitRemove(SERVER, PendingVpsRemove(SERVER.id, ATTEMPT.ssh, PIN, SERVER_KEY, profile.name, profile.identity.clientPublicKey)) },
            { store.admitRemove(SERVER, PendingVpsRemove(SERVER.id, ATTEMPT.ssh, PIN, SERVER_KEY, profile.name, profile.identity.clientPublicKey,
                PendingVpsRemove.Action.REVOKE, VpsRestoreFixture.OTHER_OPERATION)) },
            { store.admitRotation(SERVER, PIN, profile, profiles) },
            { store.withImportAdmission(listOf(SERVER.copy(name = "changed"))) { fail("Import was admitted") } },
        )
        for (operation in operations) {
            val failure = restorePendingFailure(operation)
            assertEquals(ATTEMPT, failure.attempt)
            assertEquals(SERVER, failure.target)
        }
        assertArrayEquals(previousProfileBytes, File(folder.root, "profiles").readBytes())
        store.upsert(SERVER)
        store.withImportAdmission(listOf(SERVER)) { Unit }
        val connection = requireNotNull(store.loadConnection(SERVER))
        (connection.credential as VpsCredential.Password).clear()
        store.checkRestoreOwner(SERVER, ATTEMPT)
        assertEquals(ATTEMPT, store.pendingRestore(SERVER))
    }

    @Test fun sshAliasesCannotMutateOrMoveAwayFromFencedOwner() = runBlocking {
        val alias = SERVER.copy(id = "alias")
        store.upsert(alias)
        store.saveCredential(alias, PIN, VpsCredential.Password("alias-password".toCharArray()))
        store.admitRestore(SERVER, ATTEMPT)
        val operations: List<suspend () -> Unit> = listOf(
            { store.upsert(alias.copy(host = "198.51.100.9")) }, { store.remove(alias.id) }, { store.clearCredential(alias.id) },
            { store.saveCredential(alias, PIN, VpsCredential.Password("replacement".toCharArray())) },
            { store.withImportAdmission(listOf(alias.copy(host = "198.51.100.9"))) { fail("Alias import admitted") } },
            { store.upsert(alias.copy(id = "new-alias")) },
        )
        for ((index, operation) in operations.withIndex()) {
            val failure = restorePendingFailure(operation)
            assertEquals(ATTEMPT, failure.attempt)
            assertEquals(if (index == operations.lastIndex) alias.copy(id = "new-alias") else alias, failure.target)
        }
        assertEquals(ATTEMPT, store.pendingRestore(alias))
        assertRestorePending { store.admitRestore(alias, ATTEMPT.copy(serverId = alias.id, operationId = VpsRestoreFixture.OTHER_OPERATION)) }
    }

    @Test fun restoreAdmissionRejectsEveryOlderDurableActorIncludingSshAliases() = runBlocking {
        val actors = listOf(
            "vps_pending_installs" to PendingVpsInstall.encode(listOf(PendingVpsInstall("alias", ATTEMPT.ssh, VpsInstallRequest(SERVER.endpoint, SERVER.cidr, SERVER.dns)))),
            "vps_pending_adds" to PendingVpsAdd.encode(listOf(PendingVpsAdd("alias", ATTEMPT.ssh, PIN, SERVER_KEY, VpsProfileFixture.request.copy(dns = SERVER.dns)))),
            "vps_pending_removals" to PendingVpsRemove.encode(listOf(PendingVpsRemove("alias", ATTEMPT.ssh, PIN, SERVER_KEY, "test", VpsProfileFixture.client.publicKey.toBase64()))),
            "vps_pending_rotations" to PendingVpsRotate.encode(listOf(PendingVpsRotate("alias", ATTEMPT.ssh, PIN, SERVER_KEY, "test",
                VpsProfileFixture.client.publicKey.toBase64(), com.vpn0wn.crypto.KeyPair().publicKey.toBase64(), VpsRestoreFixture.OTHER_OPERATION))),
        )
        for ((name, value) in actors) {
            val key = stringPreferencesKey(name)
            preferences.edit { it[key] = value }
            val failure = runCatching { store.admitRestore(SERVER, ATTEMPT) }.exceptionOrNull()
            assertTrue("Existing $name was admitted", failure is IllegalStateException)
            assertNull(store.pendingRestore(SERVER))
            preferences.edit { it.remove(key) }
        }
    }

    @Test fun competingRevocationPublishesItsActualActionAndOriginalOwner() = runBlocking {
        val removal = PendingVpsRemove(SERVER.id, ATTEMPT.ssh, PIN, SERVER_KEY, "phone", VpsProfileFixture.client.publicKey.toBase64(),
            PendingVpsRemove.Action.REVOKE, VpsRestoreFixture.OTHER_OPERATION)
        store.admitRemove(SERVER, removal)
        val failure = runCatching { store.admitRestore(SERVER, ATTEMPT) }.exceptionOrNull()
        assertTrue(failure is VpsServerStore.RemovePendingException)
        failure as VpsServerStore.RemovePendingException
        assertEquals(SERVER, failure.owner)
        assertEquals(PendingVpsRemove.Action.REVOKE, failure.action)
        assertEquals(removal, failure.attempt)
        assertEquals(removal, store.pendingRemove(SERVER))
        assertNull(store.pendingRestore(SERVER))
    }

    @Test fun removalWriterFencesCarryActualOriginalOwnerWhenAliasIsSelected() = runBlocking {
        val alias = savedAlias()
        val attempt = removal()
        store.admitRemove(SERVER, attempt)
        assertWriterFences(alias) { failure ->
            assertTrue(failure is VpsServerStore.RemovePendingException)
            failure as VpsServerStore.RemovePendingException
            assertEquals(SERVER.id, failure.ownerServerId)
            assertEquals(SERVER, failure.owner)
            assertEquals(attempt, failure.attempt)
            assertEquals(attempt.action, failure.action)
        }
    }

    @Test fun orphanRemovalWriterFencesRetainMarkerWithoutInventingAliasOwner() = runBlocking {
        val alias = savedAlias()
        val attempt = removal()
        store.admitRemove(SERVER, attempt)
        preferences.edit { it[stringPreferencesKey("vps_servers")] = VpsServerStore.encodeServers(listOf(alias)) }
        assertWriterFences(alias) { failure ->
            assertTrue(failure is VpsServerStore.RemovePendingException)
            failure as VpsServerStore.RemovePendingException
            assertEquals(SERVER.id, failure.ownerServerId)
            assertNull(failure.owner)
            assertEquals(attempt, failure.attempt)
            assertEquals(attempt.action, failure.action)
        }
    }

    @Test fun rotationWriterFencesCarryActualOriginalOwnerWhenAliasIsSelected() = runBlocking {
        val alias = savedAlias()
        val profiles = profileStore()
        val old = profiles.saveGenerated(SERVER, SERVER_KEY, VpsProfileFixture.request, VpsProfileFixture.result())
        val attempt = store.admitRotation(SERVER, PIN, old, profiles)
        val profileBytes = File(folder.root, "profiles").readBytes()
        assertWriterFences(alias) { failure ->
            assertTrue(failure is VpsServerStore.RotatePendingException)
            failure as VpsServerStore.RotatePendingException
            assertEquals(SERVER.id, failure.ownerServerId)
            assertEquals(SERVER, failure.owner)
            assertEquals(attempt, failure.attempt)
        }
        assertArrayEquals(profileBytes, File(folder.root, "profiles").readBytes())
    }

    @Test fun orphanRotationWriterFencesRetainMarkerWithoutInventingAliasOwner() = runBlocking {
        val alias = savedAlias()
        val profiles = profileStore()
        val old = profiles.saveGenerated(SERVER, SERVER_KEY, VpsProfileFixture.request, VpsProfileFixture.result())
        val attempt = store.admitRotation(SERVER, PIN, old, profiles)
        preferences.edit { it[stringPreferencesKey("vps_servers")] = VpsServerStore.encodeServers(listOf(alias)) }
        val profileBytes = File(folder.root, "profiles").readBytes()
        assertWriterFences(alias) { failure ->
            assertTrue(failure is VpsServerStore.RotatePendingException)
            failure as VpsServerStore.RotatePendingException
            assertEquals(SERVER.id, failure.ownerServerId)
            assertNull(failure.owner)
            assertEquals(attempt, failure.attempt)
        }
        assertArrayEquals(profileBytes, File(folder.root, "profiles").readBytes())
    }

    @Test fun replacedRemovalUuidWriterFenceCarriesCurrentMarkerInsteadOfPreviousReceipt() = runBlocking {
        val original = removal()
        store.admitRemove(SERVER, original)
        store.clearRejectedRemove(SERVER, original)
        val current = original.copy(operationId = ATTEMPT.operationId)
        store.admitRemove(SERVER, current)
        val failure = runCatching { store.upsert(SERVER.copy(name = "changed")) }.exceptionOrNull()
        assertTrue(failure is VpsServerStore.RemovePendingException)
        failure as VpsServerStore.RemovePendingException
        assertEquals(SERVER, failure.owner)
        assertEquals(current, failure.attempt)
        assertNotEquals(original, failure.attempt)
    }

    @Test fun replacedRotationUuidWriterFenceCarriesCurrentMarkerInsteadOfPreviousReceipt() = runBlocking {
        val profiles = profileStore()
        val old = profiles.saveGenerated(SERVER, SERVER_KEY, VpsProfileFixture.request, VpsProfileFixture.result())
        val original = store.admitRotation(SERVER, PIN, old, profiles)
        store.abandonPreparedRotation(SERVER, original, profiles)
        val current = store.admitRotation(SERVER, PIN, old, profiles)
        assertNotEquals(original.operationId, current.operationId)
        val failure = runCatching { store.upsert(SERVER.copy(name = "changed")) }.exceptionOrNull()
        assertTrue(failure is VpsServerStore.RotatePendingException)
        failure as VpsServerStore.RotatePendingException
        assertEquals(SERVER, failure.owner)
        assertEquals(current, failure.attempt)
        assertNotEquals(original, failure.attempt)
    }

    @Test fun staleRecordOrMissingPinCannotAdmit() = runBlocking {
        assertTrue(runCatching { store.admitRestore(SERVER.copy(name = "stale"), ATTEMPT) }.exceptionOrNull() is VpsServerStore.ServerChangedException)
        store.clearCredential(SERVER.id)
        assertTrue(runCatching { store.admitRestore(SERVER, ATTEMPT) }.exceptionOrNull() is VpsServerStore.CredentialConflictException)
        assertNull(store.pendingRestore(SERVER))
    }

    @Test fun corruptJournalBlocksMutationInsteadOfBeingTreatedAsEmpty() = runBlocking {
        preferences.edit { it[RESTORES] = "{" }
        assertTrue(runCatching { store.upsert(SERVER.copy(name = "changed")) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { store.admitInstall(SERVER, ATTEMPT.ssh, VpsInstallRequest(SERVER.endpoint, SERVER.cidr, SERVER.dns)) }.exceptionOrNull() is IOException)
        assertEquals(listOf(SERVER), store.servers.first())
    }

    @Test fun transferContainsFleetOnlyAndCannotRemoveLocalRestoreIntent() = runBlocking {
        store.admitRestore(SERVER, ATTEMPT)
        val before = preferences.data.first()
        val archive = AppDataTransfer.snapshot(emptyMap(), before)
        val output = java.io.ByteArrayOutputStream()
        AppDataTransfer.write(archive, output)
        java.util.zip.ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            assertEquals(AppDataTransfer.MANIFEST, zip.nextEntry.name)
            val manifest = zip.readBytes().toString(Charsets.UTF_8)
            assertFalse(manifest.contains("vps_pending_restores"))
            assertFalse(manifest.contains(ATTEMPT.ciphertextSha256))
            assertNull(zip.nextEntry)
        }
        val restored = AppDataTransfer.read(output.toByteArray().inputStream())
        store.withImportAdmission(restored.servers) {
            preferences.edit { AppDataTransfer.applyMetadata(restored, it, restored.servers, emptySet()) }
        }
        assertEquals(ATTEMPT, store.pendingRestore(SERVER))
    }

    private suspend fun assertRestorePending(operation: suspend () -> Unit) {
        restorePendingFailure(operation)
    }

    private suspend fun savedAlias(): StoredVpsServer = SERVER.copy(id = "alias", name = "Alias").also {
        store.upsert(it)
        store.saveCredential(it, PIN, VpsCredential.Password("alias-password".toCharArray()))
    }

    private fun removal() = PendingVpsRemove(SERVER.id, ATTEMPT.ssh, PIN, SERVER_KEY, "phone",
        VpsProfileFixture.client.publicKey.toBase64(), PendingVpsRemove.Action.REVOKE, VpsRestoreFixture.OTHER_OPERATION)

    private fun profileStore(): VpsUserProfileStore {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return VpsUserProfileStore(VpsUserProfileFile(File(folder.root, "profiles"), { key }))
    }

    private suspend fun assertWriterFences(alias: StoredVpsServer, assertFailure: (Throwable?) -> Unit) {
        val previous = preferences.data.first().asMap()
        val credentialBytes = File(folder.root, "credentials").readBytes()
        val operations: List<suspend () -> Unit> = listOf(
            { store.upsert(alias.copy(name = "changed")) },
            { store.remove(SERVER.id) },
            { store.saveCredential(alias, PIN, VpsCredential.Password("replacement".toCharArray())) },
            { store.clearCredential(SERVER.id) },
            { store.withImportAdmission(listOf(alias.copy(name = "changed"))) { fail("Conflicting import was admitted") } },
        )
        for (operation in operations) {
            assertFailure(runCatching { operation() }.exceptionOrNull())
            assertEquals(previous, preferences.data.first().asMap())
            assertArrayEquals(credentialBytes, File(folder.root, "credentials").readBytes())
        }
    }

    private suspend fun restorePendingFailure(operation: suspend () -> Unit): VpsServerStore.RestorePendingException {
        val failure = runCatching { operation() }.exceptionOrNull()
        assertTrue("Expected restore fence, saw ${failure?.javaClass?.simpleName}", failure is VpsServerStore.RestorePendingException)
        assertEquals(SERVER.id, (failure as VpsServerStore.RestorePendingException).ownerServerId)
        return failure
    }

    private fun status() = VpsServerStatus("wg0", true, SERVER.endpoint, SERVER.listenPort, SERVER.cidr, SERVER.dns, emptyList())

    private companion object {
        val SERVER = VpsRestoreFixture.server
        val ATTEMPT = VpsRestoreFixture.attempt
        val PIN = VpsRestoreFixture.pin
        val SERVER_KEY = VpsRestoreFixture.plan.serverPublicKey
        val PROOF = VpsRestoreFixture.verification
        val RESTORES = stringPreferencesKey("vps_pending_restores")
    }
}
