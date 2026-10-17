/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vpn0wn.crypto.KeyPair
import java.io.File
import java.io.IOException
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
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
class VpsUserRotationStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var scope: CoroutineScope
    private lateinit var preferences: DataStore<Preferences>
    private lateinit var store: VpsServerStore
    private lateinit var vault: File
    private lateinit var key: SecretKey
    private lateinit var old: VpsUserProfile
    private var failPreferenceWrite = 0
    private var preferenceWrites = 0
    private var failProfileWrite = false
    private var profileWrites = 0
    private var beforeProfileWrite: (() -> Unit)? = null

    @Before fun setup() = runBlocking {
        VpsUserProfileFileTest.resetNativeFiles()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val delegate = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }
        preferences = object : DataStore<Preferences> {
            override val data = delegate.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = delegate.updateData {
                val next = transform(it)
                preferenceWrites++
                if (preferenceWrites == failPreferenceWrite) throw IOException("synthetic preference failure")
                next
            }
        }
        store = VpsServerStore(preferences, credentialFileForTest(File(folder.root, "credentials")))
        vault = File(folder.root, "profiles")
        key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        store.upsert(SERVER)
        store.saveCredential(SERVER, PIN, VpsCredential.Password("synthetic-password".toCharArray()))
        old = profiles().saveGenerated(SERVER, SERVER_KEY, VpsProfileFixture.request, VpsProfileFixture.result())
        preferenceWrites = 0
        profileWrites = 0
    }

    @After fun teardown() = runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }

    @Test fun rotationChangesOnlyKeyPairIncludingOptionalInterfaceSettings() {
        val payload = old.payload().replace("[Interface]\n", "[Interface]\nMTU = 1380\nListenPort = 12345\nIncludedApplications = synthetic.app\n")
        val original = VpsUserProfile.decode(old.identity, old.name, payload)
        val proposed = original.rotated(KeyPair(), OPERATION)
        assertTrue(proposed.preservesSettings(original))
        assertEquals(original.config.peers, proposed.config.peers)
        assertEquals(original.config.`interface`.addresses, proposed.config.`interface`.addresses)
        assertEquals(original.config.`interface`.includedApplications, proposed.config.`interface`.includedApplications)
        assertEquals(original.config.`interface`.mtu, proposed.config.`interface`.mtu)
        assertNotEquals(original.identity.clientPublicKey, proposed.identity.clientPublicKey)
        assertFalse(proposed.toString().contains(proposed.config.`interface`.keyPair.privateKey.toBase64()))
    }

    @Test fun encryptedProposalCommitsBeforePublicPreparedIntentAndCannotBeDisclosed() = runBlocking {
        beforeProfileWrite = { assertFalse(runBlocking { preferences.data.first().contains(ROTATIONS) }) }
        val attempt = admit()
        beforeProfileWrite = null
        val proposed = profiles().loadRotation(attempt)
        assertEquals(PendingVpsRotate.Phase.PREPARED, attempt.phase)
        assertEquals(attempt, store.pendingRotation(SERVER))
        assertTrue(proposed.preservesSettings(old))
        assertTrue(old.sameContent(loadOld()!!))
        assertNull(profiles().load(SERVER, SERVER_KEY, attempt.newClientPublicKey))
        assertFalse(vault.readBytes().toString(Charsets.ISO_8859_1).contains(proposed.config.`interface`.keyPair.privateKey.toBase64()))
        assertFalse(preferences.data.first()[ROTATIONS]!!.contains(VpsProfileFixture.psk.toBase64()))
    }

    @Test fun proposalWriteFailurePreservesOldAndAdmitsNoIntent() = runBlocking {
        val original = vault.readBytes()
        failProfileWrite = true
        assertTrue(runCatching { admit() }.exceptionOrNull() is VpsUserProfileException)
        assertArrayEquals(original, vault.readBytes())
        assertNull(store.pendingRotation(SERVER))
        assertTrue(old.sameContent(loadOld()!!))
    }

    @Test fun journalWriteFailureCleansOnlyExactOrphanProposal() = runBlocking {
        failPreferenceWrite = 1
        assertTrue(runCatching { admit() }.exceptionOrNull() is IOException)
        assertNull(store.pendingRotation(SERVER))
        assertEquals(1, records().size)
        assertTrue(old.sameContent(loadOld()!!))
    }

    @Test fun preparedReconstructionRetiresLocallyAndLateDispatchCannotStart() = runBlocking {
        val prepared = admit()
        store.abandonPreparedRotation(SERVER, requireNotNull(store.pendingRotation(SERVER)), profiles())
        assertNull(store.pendingRotation(SERVER))
        assertEquals(1, records().size)
        assertTrue(old.sameContent(loadOld()!!))
        assertTrue(runCatching { store.dispatchRotation(SERVER, prepared, profiles()) }.exceptionOrNull() is VpsServerStore.RotatePendingException)
    }

    @Test fun dispatchedIntentExcludesAllConflictingOwnersAndOrdinaryVaultMutation() = runBlocking {
        val attempt = dispatch(admit())
        assertTrue(runCatching { store.abandonPreparedRotation(SERVER, attempt, profiles()) }.exceptionOrNull() is VpsServerStore.RotatePendingException)
        val failures = listOf(
            runCatching { store.upsert(SERVER.copy(name = "changed")) }.exceptionOrNull(),
            runCatching { store.remove(SERVER.id) }.exceptionOrNull(),
            runCatching { store.clearCredential(SERVER.id) }.exceptionOrNull(),
            runCatching { store.updateStatus(SERVER, ProvisioningJson.parseStatus(VpsUserRotationStoreTest::class.java.getResource("/provisioning/status-running.json")!!.readText()), 10L) }.exceptionOrNull(),
            runCatching { store.admitInstall(SERVER, old.identity.ssh, VpsInstallRequest(SERVER.endpoint, SERVER.cidr, SERVER.dns)) }.exceptionOrNull(),
            runCatching { store.admitAdd(SERVER, PIN, SERVER_KEY, VpsProfileFixture.request.copy(dns = SERVER.dns)) }.exceptionOrNull(),
            runCatching { store.admitRemove(SERVER, PendingVpsRemove(SERVER.id, old.identity.ssh, PIN, SERVER_KEY, old.name, old.identity.clientPublicKey)) }.exceptionOrNull(),
            runCatching { store.withImportAdmission(listOf(SERVER.copy(id = "alias"))) { Unit } }.exceptionOrNull(),
            runCatching { store.checkProfileOwner(SERVER, PIN) }.exceptionOrNull(),
        )
        failures.forEachIndexed { index, failure -> assertTrue("Conflict $index: ${failure?.javaClass?.simpleName}", failure is VpsServerStore.RotatePendingException) }
        assertEquals(VpsUserProfileException.Category.CONFLICT, (runCatching { profiles().delete(old) }.exceptionOrNull() as VpsUserProfileException).category)
        assertTrue(runCatching { profiles().saveGenerated(SERVER, SERVER_KEY, VpsProfileFixture.request, VpsProfileFixture.result()) }.exceptionOrNull() is VpsUserProfileException)
        store.saveCredential(SERVER, PIN, VpsCredential.Password("replacement-synthetic".toCharArray()))
        assertEquals(attempt, store.pendingRotation(SERVER))
    }

    @Test fun successfulVaultReplacementIsOneRewriteAndDataStoreCrashIsIdempotent() = runBlocking {
        val attempt = dispatch(admit())
        profileWrites = 0
        failPreferenceWrite = preferenceWrites + 1
        assertTrue(runCatching { store.settleRotation(SERVER, attempt, proof(attempt), 10L, profiles()) }.exceptionOrNull() is IOException)
        assertEquals(1, profileWrites)
        assertNull(loadOld())
        val recovered = profiles().loadRotation(attempt)
        assertTrue(requireNotNull(recovered.rotation).settled)
        assertTrue(recovered.preservesSettings(old))
        assertEquals(attempt, store.pendingRotation(SERVER))
        val updated = store.settleRotation(SERVER, attempt, proof(attempt), 20L, profiles())
        assertNull(store.pendingRotation(updated))
        assertTrue(recovered.sameContent(profiles().load(updated, SERVER_KEY, attempt.newClientPublicKey)!!))
        assertNull(profiles().loadRotationOrNullForTest(attempt))
        assertEquals(2, profileWrites)
    }

    @Test fun mutationReceiptOrWrongCorrelationCannotAuthorizeVaultReplacement() = runBlocking {
        val attempt = dispatch(admit())
        val original = vault.readBytes()
        for (invalid in listOf(proof(attempt).copy(mode = VpsPeerRotationResult.Mode.ROTATE), proof(attempt).copy(operationId = OTHER_OPERATION),
            proof(attempt).copy(newPublicKey = old.identity.clientPublicKey))) {
            assertTrue(runCatching { store.settleRotation(SERVER, attempt, invalid, 10L, profiles()) }.exceptionOrNull() is VpsServerStore.RotatePendingException)
        }
        assertArrayEquals(original, vault.readBytes())
        assertTrue(old.sameContent(loadOld()!!))
    }

    @Test fun rejectedCleanupCrashRetainsDurableFenceAndReconstructsWithoutProposal() = runBlocking {
        val attempt = dispatch(admit())
        failPreferenceWrite = preferenceWrites + 2
        assertTrue(runCatching { store.failRotation(SERVER, attempt, profiles()) }.exceptionOrNull() is IOException)
        val rejected = requireNotNull(store.pendingRotation(SERVER))
        assertEquals(PendingVpsRotate.Phase.REJECTED, rejected.phase)
        assertEquals(1, records().size)
        assertTrue(old.sameContent(loadOld()!!))
        store.failRotation(SERVER, rejected, profiles())
        assertNull(store.pendingRotation(SERVER))
        assertTrue(old.sameContent(loadOld()!!))
    }

    @Test fun lateFailureCannotReclassifySettledVaultAfterDataStoreCrash() = runBlocking {
        val attempt = dispatch(admit())
        failPreferenceWrite = preferenceWrites + 1
        assertTrue(runCatching { store.settleRotation(SERVER, attempt, proof(attempt), 10L, profiles()) }.exceptionOrNull() is IOException)
        val previous = vault.readBytes()
        assertTrue(runCatching { store.failRotation(SERVER, attempt, profiles()) }.exceptionOrNull() is VpsServerStore.RotatePendingException)
        assertEquals(attempt, store.pendingRotation(SERVER))
        assertArrayEquals(previous, vault.readBytes())
        assertTrue(requireNotNull(profiles().loadRotation(attempt).rotation).settled)
    }

    @Test fun capacityCountsSimultaneousOldAndNewAndPreservesEveryUnrelatedProfile() = runBlocking {
        val originals = (0 until 256).map { index ->
            if (index == 0) old else VpsUserProfile.decode(old.identity.copy(serverId = "unrelated-$index"), old.name, old.payload())
        }
        file().write(VpsUserProfileRecords.encode(originals))
        val previous = vault.readBytes()
        val failure = runCatching { admit() }.exceptionOrNull() as VpsUserProfileException
        assertEquals(VpsUserProfileException.Category.LIMIT, failure.category)
        assertNull(store.pendingRotation(SERVER))
        assertArrayEquals(previous, vault.readBytes())
        assertEquals(256, records().size)
    }

    @Test fun corruptJournalCannotBeTreatedAsEmptyDuringOrphanCleanup() = runBlocking {
        profiles().stageRotation(old, OPERATION)
        preferences.edit { it[ROTATIONS] = "{" }
        val previous = vault.readBytes()
        assertTrue(runCatching { store.cleanupRotationProfiles(profiles()) }.exceptionOrNull() is IOException)
        assertArrayEquals(previous, vault.readBytes())
        assertTrue(old.sameContent(loadOld()!!))
    }

    @Test fun orphanCleanupRemovesOnlyProposedButPreservesSettledNewProfile() = runBlocking {
        profiles().stageRotation(old, OPERATION)
        store.cleanupRotationProfiles(profiles())
        assertEquals(1, records().size)
        val proposed = profiles().stageRotation(old, OTHER_OPERATION)
        val attempt = PendingVpsRotate(SERVER.id, old.identity.ssh, PIN, SERVER_KEY, old.name, old.identity.clientPublicKey,
            proposed.identity.clientPublicKey, OTHER_OPERATION, PendingVpsRotate.Phase.DISPATCHED)
        profiles().settleRotation(attempt)
        store.cleanupRotationProfiles(profiles())
        assertNull(loadOld())
        val retained = profiles().load(SERVER, SERVER_KEY, attempt.newClientPublicKey)!!
        assertTrue(retained.preservesSettings(old))
        assertNull(retained.rotation)
    }

    @Test fun strictJournalRoundTripRejectsUnknownPhasesTypesAndFields() = runBlocking {
        val attempt = admit()
        assertEquals(listOf(attempt), PendingVpsRotate.decode(PendingVpsRotate.encode(listOf(attempt))))
        val encoded = PendingVpsRotate.encode(listOf(attempt))
        for (invalid in listOf(
            JSONArray(encoded).apply { getJSONObject(0).put("phase", "unknown") },
            JSONArray(encoded).apply { getJSONObject(0).put("phase", true) },
            JSONArray(encoded).apply { getJSONObject(0).put("presharedKey", "synthetic-secret") },
            JSONArray(encoded).apply { getJSONObject(0).put("operationId", OTHER_OPERATION.uppercase()) },
        )) assertTrue(runCatching { PendingVpsRotate.decode(invalid.toString()) }.exceptionOrNull() is IOException)
    }

    private fun file() = VpsUserProfileFile(vault, { key }, sync = {
        beforeProfileWrite?.invoke()
        if (failProfileWrite) throw IOException("synthetic vault write failure")
        profileWrites++
    })
    private fun profiles() = VpsUserProfileStore(file())
    private suspend fun loadOld() = profiles().load(SERVER, SERVER_KEY, old.identity.clientPublicKey)
    private fun records(): Map<VpsUserProfileIdentity, VpsUserProfile> = file().read()!!.let { bytes ->
        try { VpsUserProfileRecords.decode(bytes) } finally { bytes.fill(0) }
    }
    private suspend fun admit() = store.admitRotation(SERVER, PIN, old, profiles())
    private suspend fun dispatch(attempt: PendingVpsRotate) = store.dispatchRotation(SERVER, attempt, profiles())
    private fun proof(attempt: PendingVpsRotate) = VpsPeerRotationResult(attempt.operationId, attempt.name, attempt.serverPublicKey,
        attempt.oldClientPublicKey, attempt.newClientPublicKey, false, VpsPeerRotationResult.Mode.VERIFY, "")
    private suspend fun VpsUserProfileStore.loadRotationOrNullForTest(attempt: PendingVpsRotate): VpsUserProfile? =
        try { loadRotation(attempt) } catch (_: VpsUserProfileException) { null }

    private companion object {
        val SERVER = VpsProfileFixture.server
        val SERVER_KEY = VpsProfileFixture.serverKey.publicKey.toBase64()
        val PIN = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { 5 })
        val ROTATIONS = stringPreferencesKey("vps_pending_rotations")
        const val OPERATION = "11111111-2222-4333-8444-555555555555"
        const val OTHER_OPERATION = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    }
}
