/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
@Config(application = Application::class, sdk = [28])
class VpsServerCredentialTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var scope: CoroutineScope
    private lateinit var preferences: DataStore<Preferences>
    private lateinit var file: File
    private lateinit var key: SecretKey
    private lateinit var store: VpsServerStore
    private val pin = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }
        file = File(folder.root, "credentials")
        key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        store = VpsServerStore(preferences, credentialFileForTest(file, key))
    }

    @After
    fun tearDown() { scope.cancel() }

    @Test
    fun passwordKeyPassphraseAndPinRoundTripWithoutPlaintextPersistence() = runBlocking {
        val passwordServer = server("../password ' arbitrary")
        val keyServer = server("key")
        store.upsert(passwordServer)
        store.upsert(keyServer)
        val password = "synthetic-password-équivalent-42"
        val pem = "synthetic-private-key-unique-42"
        val passphrase = "synthetic-passphrase-unique-42"
        val original = VpsCredential.Password(password.toCharArray())
        store.saveCredential(passwordServer, pin, original)
        store.saveCredential(keyServer, pin, VpsCredential.PrivateKey(pem, passphrase))
        original.clear()

        val loaded = requireNotNull(store.loadConnection(passwordServer))
        assertEquals(password, (loaded.credential as VpsCredential.Password).value.concatToString())
        assertEquals(pin, loaded.hostKeyFingerprint)
        assertFalse(loaded.trustHostKey)
        loaded.credential.clear()
        val privateKey = requireNotNull(store.loadConnection(keyServer)).credential as VpsCredential.PrivateKey
        assertEquals(pem, privateKey.pem)
        assertEquals(passphrase, privateKey.passphrase)
        for (bytes in listOf(file.readBytes(), preferences.data.first()[VpsServerStore.SERVERS].orEmpty().toByteArray())) {
            val text = bytes.toString(Charsets.UTF_8)
            assertFalse(text.contains(password))
            assertFalse(text.contains(pem))
            assertFalse(text.contains(passphrase))
            assertFalse(text.contains(pin))
        }
        assertFalse(privateKey.toString().contains(pem))
    }

    @Test
    fun absentCredentialAndOptionalPassphraseRemainDistinct() = runBlocking {
        val server = server()
        store.upsert(server)
        assertNull(store.loadConnection(server))
        store.saveCredential(server, pin, VpsCredential.PrivateKey("synthetic-key"))
        assertNull((store.loadConnection(server)!!.credential as VpsCredential.PrivateKey).passphrase)
        store.clearCredential(server.id)
        assertNull(store.loadConnection(server))
        assertEquals(listOf(server), store.servers.first())
    }

    @Test
    fun suppliedSnapshotCannotLoadAnAbsentOrChangedCurrentServer() = runBlocking {
        val server = server()
        expect<VpsServerStore.CredentialConflictException> { store.loadConnection(server) }
        store.upsert(server)
        store.saveCredential(server, pin, VpsCredential.Password("original".toCharArray()))
        val before = file.readBytes()
        for (changed in listOf(server.copy(host = "198.51.100.2"), server.copy(sshPort = 2222), server.copy(sshUser = "admin"))) {
            expect<VpsServerStore.CredentialConflictException> { store.loadConnection(changed) }
            expect<VpsServerStore.CredentialConflictException> { store.saveCredential(changed, pin, VpsCredential.Password("new".toCharArray())) }
        }
        assertArrayEquals(before, file.readBytes())
        assertEquals(listOf(server), store.servers.first())
    }

    @Test
    fun changedSshTupleRequiresExplicitClearButDisplayChangePreservesCredential() = runBlocking {
        val server = server()
        store.upsert(server)
        store.saveCredential(server, pin, VpsCredential.Password("original".toCharArray()))
        val display = server.copy(name = "new display", provider = "provider")
        store.upsert(display)
        assertPassword("original", store.loadConnection(display))
        for (changed in listOf(display.copy(host = "198.51.100.2"), display.copy(sshPort = 2222), display.copy(sshUser = "admin"))) {
            expect<VpsServerStore.CredentialConflictException> { store.upsert(changed) }
        }
        assertEquals(listOf(display), store.servers.first())
        store.clearCredential(server.id)
        val changed = display.copy(host = "198.51.100.2")
        store.upsert(changed)
        assertNull(store.loadConnection(changed))
    }

    @Test
    fun orphanCannotBeLoadedReaddedOrImportedEvenWithTheSameTuple() = runBlocking {
        val server = server()
        store.upsert(server)
        store.saveCredential(server, pin, VpsCredential.Password("original".toCharArray()))
        preferences.edit { it[VpsServerStore.SERVERS] = "[]" }
        val before = file.readBytes()
        var entered = false
        expect<VpsServerStore.CredentialConflictException> { store.loadConnection(server) }
        expect<VpsServerStore.CredentialConflictException> { store.upsert(server) }
        expect<VpsServerStore.CredentialConflictException> { store.withImportAdmission(listOf(server)) { entered = true } }
        assertFalse(entered)
        assertArrayEquals(before, file.readBytes())
        assertEquals("[]", preferences.data.first()[VpsServerStore.SERVERS])
        store.clearCredential(server.id)
        store.upsert(server)
        assertNull(store.loadConnection(server))
    }

    @Test
    fun corruptCiphertextNeverResetsOrOverwritesExistingFleetOrCredentials() = runBlocking {
        val server = server()
        store.upsert(server)
        store.saveCredential(server, pin, VpsCredential.Password("original".toCharArray()))
        val tampered = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(tampered)
        for (action in listOf<suspend () -> Unit>(
            { store.loadConnection(server); Unit },
            { store.saveCredential(server, pin, VpsCredential.Password("replacement".toCharArray())) },
            { store.remove(server.id) },
        )) {
            val failure = expect<VpsCredentialStorageException> { action() }
            assertEquals(VpsCredentialStorageException.Category.CORRUPT, failure.category)
        }
        assertArrayEquals(tampered, file.readBytes())
        assertEquals(listOf(server), store.servers.first())
    }

    @Test
    fun missingKeyLeavesStorageIntactAndDoesNotBlockSafeDisplayUpdates() = runBlocking {
        val server = server()
        store.upsert(server)
        store.saveCredential(server, pin, VpsCredential.Password("original".toCharArray()))
        val before = file.readBytes()
        var attempts = 0
        val unavailable = VpsCredentialFile(file, {
            attempts++
            throw VpsCredentialStorageException(VpsCredentialStorageException.Category.KEY_UNAVAILABLE)
        }, stat = { it.exists() })
        val store = VpsServerStore(preferences, unavailable)
        val display = server.copy(name = "new display")
        store.upsert(display)
        val status = ProvisioningJson.parseStatus(javaClass.getResourceAsStream("/provisioning/status-running.json")!!.bufferedReader().use { it.readText() })
        store.updateStatus(server.id, status, 1234L)
        assertEquals(0, attempts)
        val failure = expect<VpsCredentialStorageException> { store.loadConnection(display) }
        assertEquals(VpsCredentialStorageException.Category.KEY_UNAVAILABLE, failure.category)
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun ambiguousOrCorruptFleetCannotAuthorizeCredentialMutation() = runBlocking {
        val server = server()
        store.upsert(server)
        store.saveCredential(server, pin, VpsCredential.Password("original".toCharArray()))
        val before = file.readBytes()
        for (payload in listOf("broken-json", VpsServerStore.encodeServers(listOf(server, server)))) {
            preferences.edit { it[VpsServerStore.SERVERS] = payload }
            expect<VpsServerStore.InvalidTransferMetadataException> { store.loadConnection(server) }
            expect<VpsServerStore.InvalidTransferMetadataException> { store.saveCredential(server, pin, VpsCredential.Password("new".toCharArray())) }
            expect<VpsServerStore.InvalidTransferMetadataException> { store.remove(server.id) }
            assertEquals(payload, preferences.data.first()[VpsServerStore.SERVERS])
            assertArrayEquals(before, file.readBytes())
        }
    }

    @Test
    fun successfulRemovalPreservesUnrelatedCredentialAndSettings() = runBlocking {
        val first = server()
        val second = server("second")
        store.upsert(first)
        store.upsert(second)
        store.saveCredential(first, pin, VpsCredential.Password("first-password".toCharArray()))
        store.saveCredential(second, pin, VpsCredential.Password("second-password".toCharArray()))
        val unrelated = stringPreferencesKey("unrelated")
        preferences.edit { it[unrelated] = "retained" }
        store.remove(first.id)
        assertEquals(listOf(second), store.servers.first())
        assertPassword("second-password", store.loadConnection(second))
        assertEquals("retained", preferences.data.first()[unrelated])
        store.upsert(first)
        assertNull(store.loadConnection(first))
    }

    @Test
    fun failedMetadataRemovalRestoresOnlyOriginalCredentialAndKeepsUnrelatedRecords() = runBlocking {
        val first = server()
        val second = server("second")
        store.upsert(first)
        store.upsert(second)
        store.saveCredential(first, pin, VpsCredential.Password("first-password".toCharArray()))
        store.saveCredential(second, pin, VpsCredential.Password("second-password".toCharArray()))
        val failure = IOException("metadata commit failed")
        val rejected = object : DataStore<Preferences> by preferences {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                preferences.updateData { current -> transform(current); throw failure }
        }
        val deleting = VpsServerStore(rejected, credentialFileForTest(file, key))
        expect<IOException> { deleting.remove(first.id) }
        assertEquals(listOf(first, second), store.servers.first())
        assertPassword("first-password", store.loadConnection(first))
        assertPassword("second-password", store.loadConnection(second))
    }

    @Test
    fun failedCredentialRestorationReportsIncompleteRecoveryWithoutLosingOtherCredentials() = runBlocking {
        val first = server()
        val second = server("second")
        store.upsert(first)
        store.upsert(second)
        store.saveCredential(first, pin, VpsCredential.Password("first-password".toCharArray()))
        store.saveCredential(second, pin, VpsCredential.Password("second-password".toCharArray()))
        var writes = 0
        val fault = VpsCredentialFile(file, { key }, rename = { source, target ->
            if (++writes == 2) throw IOException("restore rename failed")
            move(source, target)
        }, stat = { it.exists() })
        val rejected = object : DataStore<Preferences> by preferences {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                preferences.updateData { current -> transform(current); throw IOException("metadata commit failed") }
        }
        expect<VpsServerStore.IncompleteCredentialRecoveryException> { VpsServerStore(rejected, fault).remove(first.id) }
        assertEquals(listOf(first, second), store.servers.first())
        assertNull(store.loadConnection(first))
        assertPassword("second-password", store.loadConnection(second))
    }

    @Test
    fun failureAfterCommittedMetadataRemovalNeverRestoresAnOrphan() = runBlocking {
        val server = server()
        store.upsert(server)
        store.saveCredential(server, pin, VpsCredential.Password("original".toCharArray()))
        val uncertain = object : DataStore<Preferences> by preferences {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                preferences.updateData(transform)
                throw IOException("completion acknowledgement failed")
            }
        }
        expect<IOException> { VpsServerStore(uncertain, credentialFileForTest(file, key)).remove(server.id) }
        assertTrue(store.servers.first().isEmpty())
        store.upsert(server)
        assertNull(store.loadConnection(server))
    }

    @Test
    fun unreadablePostfailureFleetPreventsCredentialRestoration() = runBlocking {
        val server = server()
        store.upsert(server)
        store.saveCredential(server, pin, VpsCredential.Password("original".toCharArray()))
        var denyRead = false
        val uncertain = object : DataStore<Preferences> by preferences {
            override val data: Flow<Preferences> get() = flow {
                if (denyRead) throw IOException("metadata read failed")
                emitAll(preferences.data)
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                preferences.updateData { current ->
                    transform(current)
                    denyRead = true
                    throw IOException("metadata commit failed")
                }
        }
        expect<VpsServerStore.IncompleteCredentialRecoveryException> {
            VpsServerStore(uncertain, credentialFileForTest(file, key)).remove(server.id)
        }
        assertEquals(listOf(server), store.servers.first())
        assertNull(store.loadConnection(server))
    }

    @Test
    fun waitingCancellationNeverAdmitsCredentialWrite() = runBlocking {
        val server = server()
        store.upsert(server)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val admitted = launch(Dispatchers.Default) { store.withImportAdmission(emptyList()) { entered.complete(Unit); release.await() } }
        entered.await()
        try {
            val starting = CompletableDeferred<Unit>()
            val waiting = launch(Dispatchers.Default) {
                starting.complete(Unit)
                store.saveCredential(server, pin, VpsCredential.Password("never written".toCharArray()))
            }
            starting.await()
            waiting.cancelAndJoin()
            assertFalse(file.exists())
        } finally {
            release.complete(Unit)
            admitted.join()
        }
    }

    @Test
    fun admittedWriteSettlesBeforeCancellationReleasesItsCaller() = runBlocking {
        val server = server()
        store.upsert(server)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = VpsCredentialFile(file, { key }, output = {
            FileOutputStream(it).also {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }, rename = ::move, stat = { it.exists() })
        val store = VpsServerStore(preferences, blocking)
        val saving = launch(Dispatchers.Default) { store.saveCredential(server, pin, VpsCredential.Password("committed".toCharArray())) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            saving.cancel()
            assertFalse(saving.isCompleted)
        } finally {
            release.countDown()
            saving.join()
        }
        assertTrue(saving.isCancelled)
        assertPassword("committed", store.loadConnection(server))
    }

    @Test
    fun admittedLoadCancellationDoesNotPublishTheDecryptedPassword() = runBlocking {
        val server = server()
        store.upsert(server)
        store.saveCredential(server, pin, VpsCredential.Password("original".toCharArray()))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = VpsCredentialFile(file, {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            key
        }, stat = { it.exists() })
        val store = VpsServerStore(preferences, blocking)
        var published = false
        val loading = launch(Dispatchers.Default) { store.loadConnection(server); published = true }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            loading.cancel()
            assertFalse(loading.isCompleted)
        } finally {
            release.countDown()
            loading.join()
        }
        assertTrue(loading.isCancelled)
        assertFalse(published)
        assertPassword("original", store.loadConnection(server))
    }

    @Test
    fun deletionAndReadditionShareAdmissionSoOldCredentialsCannotResurrect() = runBlocking {
        val server = server()
        store.upsert(server)
        store.saveCredential(server, pin, VpsCredential.Password("original".toCharArray()))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocked = object : DataStore<Preferences> by preferences {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                preferences.updateData { current ->
                    val changed = transform(current)
                    entered.complete(Unit)
                    release.await()
                    changed
                }
        }
        val shared = VpsServerStore(blocked, credentialFileForTest(file, key))
        val deleting = async(Dispatchers.Default) { shared.remove(server.id) }
        entered.await()
        val starting = CompletableDeferred<Unit>()
        val adding = async(Dispatchers.Default) { starting.complete(Unit); shared.upsert(server) }
        try {
            starting.await()
            assertFalse(adding.isCompleted)
        } finally { release.complete(Unit) }
        deleting.await()
        adding.await()
        assertNull(shared.loadConnection(server))
    }

    @Test
    fun malformedRecordPayloadNeverBecomesAnEmptyCredentialSet() {
        val valid = VpsCredentialRecords.encode(mapOf("one" to SavedVpsCredential(
            VpsEndpoint("192.0.2.1"), pin, VpsCredential.Password("synthetic".toCharArray()),
        )))
        val first = JSONArray(valid.toString(Charsets.UTF_8)).getJSONObject(0)
        for (payload in listOf("bad-json", JSONArray().put(first).put(first).toString(),
            JSONArray().put(first.put("unexpected", "field")).toString())) {
            val failure = assertThrows(VpsCredentialStorageException::class.java) {
                VpsCredentialRecords.decode(payload.toByteArray())
            }
            assertEquals(VpsCredentialStorageException.Category.CORRUPT, failure.category)
        }
    }

    @Test
    fun legacyDefaultsDoNotPermitCoercedOrAmbiguousSshIdentity() = runBlocking {
        val legacy = """[{"id":"one","host":"192.0.2.1","running":"unknown"}]"""
        preferences.edit { it[VpsServerStore.SERVERS] = legacy }
        store.saveCredential(server(), pin, VpsCredential.Password("original".toCharArray()))
        assertPassword("original", store.loadConnection(server()))
        val before = file.readBytes()
        for (payload in listOf(
            legacy.replace("\"id\":\"one\"", "\"id\":1"),
            legacy.replace("\"running\":\"unknown\"", "\"sshPort\":\"22\""),
            legacy.replace("\"running\":\"unknown\"", "\"sshPort\":null"),
            legacy.replace("\"running\":\"unknown\"", "\"sshUser\":null"),
            legacy.replace("\"running\":\"unknown\"", "\"sshUser\":{}"),
            legacy.replace("\"running\":\"unknown\"", "\"unexpected\":true"),
            legacy + "[]",
        )) {
            preferences.edit { it[VpsServerStore.SERVERS] = payload }
            expect<VpsServerStore.InvalidTransferMetadataException> { store.loadConnection(server()) }
            expect<VpsServerStore.InvalidTransferMetadataException> { store.upsert(server()) }
            assertEquals(payload, preferences.data.first()[VpsServerStore.SERVERS])
            assertArrayEquals(before, file.readBytes())
        }
    }

    private fun assertPassword(expected: String, connection: VpsConnection?) {
        val credential = requireNotNull(connection).credential as VpsCredential.Password
        try { assertEquals(expected, credential.value.concatToString()) } finally { credential.clear() }
    }

    private fun server(id: String = "one") = StoredVpsServer(
        id, "server", "192.0.2.1", 22, "root", "198.51.100.1", "51820", "10.8.0.0/24", "1.1.1.1",
    )

    private fun move(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private suspend inline fun <reified T : Throwable> expect(block: suspend () -> Unit): T {
        try { block() } catch (failure: Throwable) {
            assertTrue("Expected ${T::class.java.simpleName}, received ${failure.javaClass.simpleName}", failure is T)
            return failure as T
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
