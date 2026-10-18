/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.app.Application
import androidx.datastore.core.FileStorage
import androidx.datastore.core.Storage
import androidx.datastore.core.StorageConnection
import androidx.datastore.core.WriteScope
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vpn0wn.android.util.PreferencesDataStore
import com.vpn0wn.android.util.PreferencesStorage
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class VpsServerFailedCommitRecoveryTest {
    @get:Rule val folder = TemporaryFolder()
    private val owners = mutableListOf<CoroutineScope>()

    @After
    fun closeStores() = runBlocking {
        for (owner in owners) owner.coroutineContext[Job]!!.cancelAndJoin()
    }

    @Test
    fun failedCommitPreservesCredentialMatchingSameInstanceAndReopenedFleet() = runBlocking {
        val metadataFile = File(folder.root, "settings.preferences_pb")
        val failingStorage = FailedCommitStorage(metadataFile)
        val initialOwner = newOwner()
        val preferences = PreferencesDataStore(
            PreferenceDataStoreFactory.create(storage = PreferencesStorage(failingStorage), scope = initialOwner),
            initialOwner.coroutineContext.job,
        )
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val credentials = credentialFileForTest(File(folder.root, "credentials"), key)
        val store = VpsServerStore(preferences, credentials)
        val first = server("first")
        val second = server("second")
        val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32))
        store.upsert(first)
        store.upsert(second)
        store.saveCredential(first, fingerprint, VpsCredential.Password("first-password".toCharArray()))
        store.saveCredential(second, fingerprint, VpsCredential.Password("second-password".toCharArray()))
        val unrelated = stringPreferencesKey("unrelated")
        preferences.edit { it[unrelated] = "retained" }
        val committedBytes = metadataFile.readBytes()
        val committedPreferences = readPreferences(metadataFile)

        failingStorage.failNextCommit.set(true)
        val failure = runCatching { store.remove(first.id) }.exceptionOrNull()
        val sameInstanceFleet = store.servers.first()
        val afterFailureBytes = metadataFile.readBytes()
        val afterFailurePreferences = readPreferences(metadataFile)
        val afterFailureCredentialIds = credentialIds(credentials)
        initialOwner.coroutineContext[Job]!!.cancelAndJoin()

        val reopenedPreferences = PreferenceDataStoreFactory.create(scope = newOwner()) { metadataFile }
        val reopenedStore = VpsServerStore(reopenedPreferences, credentials)
        val reopenedFleet = reopenedStore.servers.first()
        val reopenedFirst = reopenedStore.loadConnection(first)
        val reopenedSecond = reopenedStore.loadConnection(second)
        try {
            assertEquals(IOException::class.java, failure?.javaClass)
            assertEquals(failingStorage.failure.message, failure?.message)
            assertEquals(listOf(first, second), sameInstanceFleet)
            assertEquals(setOf(first.id, second.id), afterFailureCredentialIds)
            assertArrayEquals(committedBytes, afterFailureBytes)
            assertEquals(committedPreferences.asMap(), afterFailurePreferences.asMap())
            assertEquals(committedPreferences.asMap(), reopenedPreferences.data.first().asMap())
            assertEquals(listOf(first, second), reopenedFleet)
            assertEquals("retained", reopenedPreferences.data.first()[unrelated])
            assertNotNull("Unrelated credential must survive", reopenedSecond)
            assertArrayEquals("second-password".toCharArray(), (reopenedSecond!!.credential as VpsCredential.Password).value)
            assertNotNull("Failed metadata removal must restore the credential matching retained disk metadata", reopenedFirst)
            assertArrayEquals("first-password".toCharArray(), (reopenedFirst!!.credential as VpsCredential.Password).value)
        } finally {
            (reopenedFirst?.credential as? VpsCredential.Password)?.clear()
            (reopenedSecond?.credential as? VpsCredential.Password)?.clear()
        }
    }

    private fun newOwner(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO).also(owners::add)

    private suspend fun readPreferences(file: File): Preferences =
        file.inputStream().use { PreferencesFileSerializer.readFrom(it) }

    private fun credentialIds(file: VpsCredentialFile): Set<String> {
        val bytes = requireNotNull(file.read())
        val records = try { VpsCredentialRecords.decode(bytes) } finally { bytes.fill(0) }
        return try { records.keys.toSet() } finally { VpsCredentialRecords.clear(records) }
    }

    private fun server(id: String) = StoredVpsServer(
        id = id, name = id, host = "192.0.2.1", sshPort = 22, sshUser = "root",
        endpoint = "192.0.2.1", listenPort = "51820", cidr = "10.0.0.0/24", dns = "1.1.1.1",
    )

    private class FailedCommitStorage(file: File) : Storage<Preferences> {
        private val delegate = FileStorage(PreferencesFileSerializer, produceFile = { file })
        val failNextCommit = AtomicBoolean(false)
        val failure = IOException("synthetic default-storage final commit failure")

        override fun createConnection(): StorageConnection<Preferences> {
            val connection = delegate.createConnection()
            return object : StorageConnection<Preferences> by connection {
                override suspend fun writeScope(block: suspend WriteScope<Preferences>.() -> Unit) {
                    connection.writeScope {
                        block()
                        if (failNextCommit.compareAndSet(true, false)) throw failure
                    }
                }
            }
        }
    }
}
