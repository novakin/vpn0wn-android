/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.util

import androidx.datastore.core.FileStorage
import androidx.datastore.core.StorageConnection
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class PreferencesDataStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val owners = mutableListOf<CoroutineScope>()
    private val connections = mutableListOf<StorageConnection<Preferences>>()

    @After
    fun closeStores() = runBlocking {
        for (owner in owners) owner.coroutineContext[Job]!!.cancelAndJoin()
        for (connection in connections) connection.close()
    }

    @Test
    fun readerWaitsForCompletedReplacementAndSeesNewPreferences() = runTest {
        val connection = connection()
        connection.writeScope { writeData(preferences("old-tunnel")) }
        val writeEntered = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val writer = launch {
            connection.writeScope {
                writeData(preferences("new-tunnel"))
                writeEntered.complete(Unit)
                releaseWrite.await()
            }
        }
        try {
            runCurrent()
            assertTrue(writeEntered.isCompleted)
            var readEntered = false
            val reader = async {
                connection.readScope {
                    readEntered = true
                    readData()
                }
            }
            runCurrent()
            assertFalse(reader.isCompleted)
            assertFalse(readEntered)
            releaseWrite.complete(Unit)
            writer.join()
            assertEquals(preferences("new-tunnel").asMap(), reader.await().asMap())
        } finally {
            releaseWrite.complete(Unit)
            writer.cancelAndJoin()
        }
    }

    @Test
    fun unguardedFileStorageAdmitsReadBeforeReplacementCompletes() = runTest {
        val file = File(folder.root, "unguarded.preferences_pb")
        val connection = FileStorage(PreferencesFileSerializer, produceFile = { file })
            .createConnection().also(connections::add)
        connection.writeScope { writeData(preferences("old-tunnel")) }
        val writeEntered = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val writer = launch {
            connection.writeScope {
                writeData(preferences("new-tunnel"))
                writeEntered.complete(Unit)
                releaseWrite.await()
            }
        }
        try {
            runCurrent()
            assertTrue(writeEntered.isCompleted)
            val reader = async {
                connection.readScope { locked ->
                    assertFalse(locked)
                    readData()
                }
            }
            runCurrent()
            assertTrue(reader.isCompleted)
            assertEquals(preferences("old-tunnel").asMap(), reader.await().asMap())
            assertFalse(writer.isCompleted)
            releaseWrite.complete(Unit)
            writer.join()
            assertEquals(preferences("new-tunnel").asMap(), connection.readScope { readData() }.asMap())
        } finally {
            releaseWrite.complete(Unit)
            writer.cancelAndJoin()
        }
    }

    @Test
    fun failedWriteCallbackPreservesCommittedFileAndReleasesAdmission() = runTest {
        val connection = connection()
        connection.writeScope { writeData(preferences("old-tunnel")) }
        var failure: IOException? = null
        try {
            connection.writeScope {
                writeData(preferences("uncommitted-tunnel"))
                throw IOException("Injected write callback failure")
            }
        } catch (error: IOException) {
            failure = error
        }
        assertNotNull("Write failure must reach its caller", failure)
        assertEquals("Injected write callback failure", failure?.message)
        assertEquals(preferences("old-tunnel").asMap(), connection.readScope { readData() }.asMap())
        connection.writeScope { writeData(preferences("new-tunnel")) }
        assertEquals(preferences("new-tunnel").asMap(), connection.readScope { readData() }.asMap())
    }

    @Test
    fun cancelledWriterReleasesWaitingReaderWithoutPublishingScratch() = runTest {
        val connection = connection()
        connection.writeScope { writeData(preferences("old-tunnel")) }
        val writeEntered = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val writer = launch {
            connection.writeScope {
                writeData(preferences("cancelled-tunnel"))
                writeEntered.complete(Unit)
                releaseWrite.await()
            }
        }
        try {
            runCurrent()
            assertTrue(writeEntered.isCompleted)
            val reader = async { connection.readScope { readData() } }
            runCurrent()
            assertFalse(reader.isCompleted)
            writer.cancelAndJoin()
            assertEquals(preferences("old-tunnel").asMap(), reader.await().asMap())
            connection.writeScope { writeData(preferences("new-tunnel")) }
            assertEquals(preferences("new-tunnel").asMap(), connection.readScope { readData() }.asMap())
        } finally {
            releaseWrite.complete(Unit)
            writer.cancelAndJoin()
        }
    }

    @Test
    fun cancelledWaitingReaderDoesNotEnterReadScope() = runTest {
        val connection = connection()
        connection.writeScope { writeData(preferences("old-tunnel")) }
        val writeEntered = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val writer = launch {
            connection.writeScope {
                writeData(preferences("new-tunnel"))
                writeEntered.complete(Unit)
                releaseWrite.await()
            }
        }
        try {
            runCurrent()
            assertTrue(writeEntered.isCompleted)
            var readEntered = false
            val reader = launch {
                connection.readScope {
                    readEntered = true
                    readData()
                }
            }
            runCurrent()
            assertFalse(reader.isCompleted)
            reader.cancelAndJoin()
            assertFalse(readEntered)
            releaseWrite.complete(Unit)
            writer.join()
            assertEquals(preferences("new-tunnel").asMap(), connection.readScope { readData() }.asMap())
        } finally {
            releaseWrite.complete(Unit)
            writer.cancelAndJoin()
        }
    }

    @Test
    fun existingPreferencesReopenWithGuardedAndDefaultFileStorage() = runBlocking {
        val file = File(folder.root, "settings.preferences_pb")
        val legacyOwner = newOwner()
        val legacyStore = PreferenceDataStoreFactory.create(scope = legacyOwner) { file }
        legacyStore.updateData { preferences("old-tunnel") }
        val before = legacyStore.data.first().asMap()
        legacyOwner.coroutineContext[Job]!!.cancelAndJoin()

        val guardedOwner = newOwner()
        val guardedStore = createPreferencesDataStore(file, guardedOwner)
        assertEquals(before, guardedStore.data.first().asMap())
        guardedStore.edit { it[LAST_USED] = "new-tunnel" }
        val after = guardedStore.data.first().asMap()
        guardedOwner.coroutineContext[Job]!!.cancelAndJoin()

        val reopened = PreferenceDataStoreFactory.create(scope = newOwner()) { file }
        assertEquals(after, reopened.data.first().asMap())
        assertEquals("new-tunnel", reopened.data.first()[LAST_USED])
    }

    private fun connection(): StorageConnection<Preferences> =
        PreferencesStorage(File(folder.root, "settings.preferences_pb")).createConnection().also(connections::add)

    private fun newOwner(): CoroutineScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob()).also(owners::add)

    private fun preferences(lastUsed: String) = preferencesOf(
        LAST_USED to lastUsed,
        RESTORE_ON_BOOT to true,
        RUNNING_TUNNELS to setOf("old-tunnel", "unrelated-tunnel"),
    )

    companion object {
        private val LAST_USED = stringPreferencesKey("last_used_tunnel")
        private val RESTORE_ON_BOOT = booleanPreferencesKey("restore_on_boot")
        private val RUNNING_TUNNELS = stringSetPreferencesKey("enabled_configs")
    }
}
