/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.app.Application
import com.vpn0wn.crypto.KeyPair
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
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
class VpsUserProfileStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var file: File
    private lateinit var key: SecretKey

    @Before
    fun setUp() {
        file = File(folder.root, "profiles")
        key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        VpsUserProfileFileTest.resetNativeFiles()
    }

    @Test
    fun reopenPreservesFullProfileAndIdenticalSaveDoesNotRewriteCiphertext() = runBlocking {
        val profile = save(storage())
        val ciphertext = file.readBytes()
        val reopened = storage()
        assertTrue(profile.sameContent(load(reopened)!!))
        assertTrue(profile.sameContent(save(reopened)))
        assertArrayEquals(ciphertext, file.readBytes())
        assertFalse(ciphertext.toString(Charsets.ISO_8859_1).contains(VpsProfileFixture.client.privateKey.toBase64()))
    }

    @Test
    fun identityUsesFleetIdExactSshTupleAndBothWireguardKeysButNotLabelsOrHealth() = runBlocking {
        val store = storage()
        val profile = save(store)
        val server = VpsProfileFixture.server
        for (changed in listOf(
            server.copy(id = "other"),
            server.copy(host = "203.0.113.11"),
            server.copy(sshPort = 2222),
            server.copy(sshUser = "operator"),
        )) assertNull(load(store, server = changed))
        assertNull(load(store, serverKey = KeyPair().publicKey.toBase64()))
        assertNull(load(store, clientKey = KeyPair().publicKey.toBase64()))
        assertTrue(profile.sameContent(load(store, server = server.copy(name = "Renamed", lastSyncEpochSeconds = 123L, running = true))!!))
        val other = save(store, server = server.copy(id = "other"))
        assertTrue(other.sameContent(load(storage(), server = server.copy(id = "other"))!!))
        assertTrue(profile.sameContent(load(storage())!!))
    }

    @Test
    fun conflictingSaveAndStaleDeleteCannotChangeAnotherRetainedRecord() = runBlocking {
        val store = storage()
        val profile = save(store)
        val previous = file.readBytes()
        val request = VpsProfileFixture.request.copy(name = "renamed")
        val result = VpsProfileFixture.result().copy(name = "renamed")
        val different = VpsProfileFixture.profile(request = request, result = result)
        val failedSave = runCatching { save(store, request = request, result = result) }.exceptionOrNull()
        assertCategory(VpsUserProfileException.Category.CONFLICT, failedSave)
        val failedDelete = runCatching { store.delete(different) }.exceptionOrNull()
        assertCategory(VpsUserProfileException.Category.CONFLICT, failedDelete)
        assertArrayEquals(previous, file.readBytes())
        assertTrue(profile.sameContent(load(storage())!!))
    }

    @Test
    fun explicitCheckedOrphanDeletionPreservesOtherProfilesAndIsIdempotent() = runBlocking {
        val store = storage()
        val profile = save(store)
        val otherServer = VpsProfileFixture.server.copy(id = "other")
        val other = save(store, server = otherServer)
        assertTrue(store.delete(profile))
        assertFalse(store.delete(profile))
        assertNull(load(storage()))
        assertTrue(other.sameContent(load(storage(), server = otherServer)!!))
        assertTrue(store.delete(other))
        assertTrue(file.exists())
        assertNull(load(storage(), server = otherServer))
    }

    @Test
    fun malformedEncryptedRecordsFailClosedAndCannotBeOverwritten() = runBlocking {
        val invalid = JSONObject(VpsUserProfileRecords.encode(listOf(VpsProfileFixture.profile())).toString(Charsets.UTF_8))
            .put("version", 99).toString().toByteArray()
        fileStorage().write(invalid)
        val previous = file.readBytes()
        assertCategory(VpsUserProfileException.Category.CORRUPT, runCatching { load(storage()) }.exceptionOrNull())
        assertCategory(VpsUserProfileException.Category.CORRUPT, runCatching { save(storage()) }.exceptionOrNull())
        assertArrayEquals(previous, file.readBytes())
    }

    @Test
    fun recordsRejectUnknownTypesFieldsVersionsDuplicateIdentitiesAndInvalidUtf8() {
        val original = VpsUserProfileRecords.encode(listOf(VpsProfileFixture.profile())).toString(Charsets.UTF_8)
        val samples = listOf(
            JSONObject(original).put("version", "1"),
            JSONObject(original).put("extra", true),
            JSONObject(original).put("profiles", JSONObject()),
            JSONObject(original).apply { getJSONArray("profiles").getJSONObject(0).put("sshPort", "22") },
            JSONObject(original).apply { getJSONArray("profiles").getJSONObject(0).put("config", "synthetic secret") },
            JSONObject(original).apply { getJSONArray("profiles").getJSONObject(0).put("password", "synthetic secret") },
            JSONObject(original).apply {
                val record = getJSONArray("profiles").getJSONObject(0)
                put("profiles", JSONArray().put(record).put(record))
            },
        ).map { it.toString().toByteArray() } +
            listOf((original + "true").toByteArray(), byteArrayOf(0xc3.toByte(), 0x28))
        samples.forEach { bytes ->
            assertCategory(VpsUserProfileException.Category.CORRUPT,
                assertThrows(VpsUserProfileException::class.java) { VpsUserProfileRecords.decode(bytes) })
        }
        val failure = assertThrows(VpsUserProfileException::class.java) {
            VpsUserProfileRecords.encode(List(257) { VpsProfileFixture.profile() })
        }
        assertEquals(VpsUserProfileException.Category.LIMIT, failure.category)
    }

    @Test
    fun cancellationBeforeAdmissionOrWhileWaitingCannotPublishProfile() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = VpsUserProfileStore(fileStorage(sync = { stream ->
            entered.countDown()
            check(release.await(WAIT_SECONDS, TimeUnit.SECONDS))
            stream.fd.sync()
        }))
        val first = async(Dispatchers.Default) { save(store) }
        try {
            assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS))
            val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
                save(store, server = VpsProfileFixture.server.copy(id = "waiting"))
            }
            waiting.cancelAndJoin()
        } finally {
            release.countDown()
        }
        first.await()
        assertNull(load(storage(), server = VpsProfileFixture.server.copy(id = "waiting")))
        val cancelled = launch {
            currentCoroutineContext().cancel()
            save(store, server = VpsProfileFixture.server.copy(id = "cancelled"))
        }
        cancelled.cancelAndJoin()
        assertNull(load(storage(), server = VpsProfileFixture.server.copy(id = "cancelled")))
    }

    @Test
    fun admittedSaveSettlesBeforeCancelledCallerReturnsAndCanBeReopened() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = VpsUserProfileStore(fileStorage(sync = { stream ->
            entered.countDown()
            check(release.await(WAIT_SECONDS, TimeUnit.SECONDS))
            stream.fd.sync()
        }))
        var returned = false
        val saving = launch(Dispatchers.Default) {
            save(store)
            returned = true
        }
        try {
            assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS))
            saving.cancel()
            assertFalse(saving.isCompleted)
        } finally {
            release.countDown()
        }
        saving.join()
        assertTrue(saving.isCancelled)
        assertFalse(returned)
        assertNotNull(load(storage()))
    }

    private fun storage() = VpsUserProfileStore(fileStorage())

    private fun fileStorage(sync: (java.io.FileOutputStream) -> Unit = { it.fd.sync() }) =
        VpsUserProfileFile(file, { key }, sync = sync)

    private suspend fun save(
        store: VpsUserProfileStore,
        server: StoredVpsServer = VpsProfileFixture.server,
        request: VpsAddPeerRequest = VpsProfileFixture.request,
        result: VpsAddPeerResult = VpsProfileFixture.result(),
    ) = store.saveGenerated(server, VpsProfileFixture.serverKey.publicKey.toBase64(), request, result)

    private suspend fun load(
        store: VpsUserProfileStore,
        server: StoredVpsServer = VpsProfileFixture.server,
        serverKey: String = VpsProfileFixture.serverKey.publicKey.toBase64(),
        clientKey: String = VpsProfileFixture.client.publicKey.toBase64(),
    ) = store.load(server, serverKey, clientKey)

    private fun assertCategory(category: VpsUserProfileException.Category, failure: Throwable?) {
        assertTrue(failure is VpsUserProfileException)
        assertEquals(category, (failure as VpsUserProfileException).category)
        assertEquals(category.message, failure.message)
        assertNull(failure.cause)
    }

    companion object {
        private const val WAIT_SECONDS = 10L
    }
}
