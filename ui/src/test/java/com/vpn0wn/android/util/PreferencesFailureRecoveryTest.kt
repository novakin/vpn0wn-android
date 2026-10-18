/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.util

import androidx.datastore.core.FileStorage
import androidx.datastore.core.DataStore
import androidx.datastore.core.ReadScope
import androidx.datastore.core.Storage
import androidx.datastore.core.StorageConnection
import androidx.datastore.core.WriteScope
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class PreferencesFailureRecoveryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun failedCommitRevalidatesBeforeQueuedNoOpCanEnter() = runTest {
        val fixture = fixture()
        fixture.store.edit { it[VALUE] = "committed" }
        val committedBytes = fixture.file.readBytes()
        val failedWriteEntered = CompletableDeferred<Unit>()
        val releaseFailure = CompletableDeferred<Unit>()
        val recoveryReadEntered = CompletableDeferred<Unit>()
        val releaseRecovery = CompletableDeferred<Unit>()
        fixture.storage.afterWrite = {
            failedWriteEntered.complete(Unit)
            releaseFailure.await()
            fixture.storage.beforeRead = {
                recoveryReadEntered.complete(Unit)
                releaseRecovery.await()
            }
            throw IOException("Injected commit failure")
        }
        val failing = async { runCatching { fixture.store.edit { it[VALUE] = "uncommitted" } }.exceptionOrNull() }
        try {
            runCurrent()
            assertTrue(failedWriteEntered.isCompleted)
            var queuedTransformEntered = false
            val queued = async {
                fixture.store.updateData {
                    queuedTransformEntered = true
                    it
                }
            }
            runCurrent()
            assertFalse(queuedTransformEntered)
            releaseFailure.complete(Unit)
            runCurrent()
            assertTrue(recoveryReadEntered.isCompleted)
            assertFalse(queuedTransformEntered)
            assertFalse(failing.isCompleted)
            releaseRecovery.complete(Unit)
            assertEquals("Injected commit failure", failing.await()?.message)
            assertEquals("committed", queued.await()[VALUE])
            assertEquals("committed", fixture.store.data.first()[VALUE])
            assertArrayEquals(committedBytes, fixture.file.readBytes())
        } finally {
            releaseFailure.complete(Unit)
            releaseRecovery.complete(Unit)
        }
    }

    @Test
    fun queuedMutationUsesCommittedSnapshotAfterFailure() = runTest {
        val fixture = fixture()
        fixture.store.edit { it[VALUE] = "committed" }
        val writeEntered = CompletableDeferred<Unit>()
        val releaseFailure = CompletableDeferred<Unit>()
        fixture.storage.afterWrite = {
            writeEntered.complete(Unit)
            releaseFailure.await()
            throw IOException("Injected commit failure")
        }
        val failing = async { runCatching { fixture.store.edit { it[VALUE] = "uncommitted" } }.exceptionOrNull() }
        try {
            runCurrent()
            assertTrue(writeEntered.isCompleted)
            var queuedSnapshot: String? = null
            val queued = async {
                fixture.store.edit {
                    queuedSnapshot = it[VALUE]
                    it[UNRELATED] = "queued"
                }
            }
            runCurrent()
            assertFalse(queued.isCompleted)
            releaseFailure.complete(Unit)
            assertEquals("Injected commit failure", failing.await()?.message)
            queued.await()
            assertEquals("committed", queuedSnapshot)
            assertEquals("committed", fixture.store.data.first()[VALUE])
            assertEquals("queued", fixture.store.data.first()[UNRELATED])
        } finally {
            releaseFailure.complete(Unit)
        }
    }

    @Test
    fun cancellingBeforeAdmissionDoesNotSubmitTransformOrRecovery() = runTest {
        val fixture = fixture()
        fixture.store.edit { it[VALUE] = "committed" }
        val writeEntered = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        fixture.storage.afterWrite = {
            writeEntered.complete(Unit)
            releaseWrite.await()
        }
        val first = launch { fixture.store.edit { it[VALUE] = "updated" } }
        try {
            runCurrent()
            assertTrue(writeEntered.isCompleted)
            val readsBefore = fixture.storage.reads
            val writesBefore = fixture.storage.writes
            var transformEntered = false
            val waiting = launch {
                fixture.store.edit {
                    transformEntered = true
                    it[UNRELATED] = "cancelled"
                }
            }
            runCurrent()
            waiting.cancelAndJoin()
            assertFalse(transformEntered)
            assertEquals(readsBefore, fixture.storage.reads)
            assertEquals(writesBefore, fixture.storage.writes)
            releaseWrite.complete(Unit)
            first.join()
            assertEquals("updated", fixture.store.data.first()[VALUE])
            assertEquals(null, fixture.store.data.first()[UNRELATED])
        } finally {
            releaseWrite.complete(Unit)
            first.cancelAndJoin()
        }
    }

    @Test
    fun cancellingAdmittedTransformSettlesBeforeNextUpdate() = runTest {
        val fixture = fixture()
        fixture.store.edit { it[VALUE] = "committed" }
        val transformEntered = CompletableDeferred<Unit>()
        val holdTransform = CompletableDeferred<Unit>()
        val update = launch {
            fixture.store.edit {
                it[VALUE] = "cancelled"
                transformEntered.complete(Unit)
                holdTransform.await()
            }
        }
        try {
            runCurrent()
            assertTrue(transformEntered.isCompleted)
            update.cancelAndJoin()
            assertEquals("committed", fixture.store.data.first()[VALUE])
            fixture.store.edit { it[UNRELATED] = "next" }
            assertEquals("committed", fixture.store.data.first()[VALUE])
            assertEquals("next", fixture.store.data.first()[UNRELATED])
        } finally {
            holdTransform.complete(Unit)
            update.cancelAndJoin()
        }
    }

    @Test
    fun cancellationWaitsForAlreadyAdmittedSuccessfulCommit() = runTest {
        val fixture = fixture()
        fixture.store.edit { it[VALUE] = "committed" }
        val writeEntered = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        fixture.storage.afterWrite = {
            writeEntered.complete(Unit)
            releaseWrite.await()
        }
        val update = launch { fixture.store.edit { it[VALUE] = "updated" } }
        try {
            runCurrent()
            assertTrue(writeEntered.isCompleted)
            update.cancel()
            runCurrent()
            assertFalse("Caller must drain its admitted commit", update.isCompleted)
            var queuedEntered = false
            val queued = async {
                fixture.store.updateData {
                    queuedEntered = true
                    it
                }
            }
            runCurrent()
            assertFalse(queuedEntered)
            releaseWrite.complete(Unit)
            update.join()
            assertTrue(update.isCancelled)
            assertEquals("updated", queued.await()[VALUE])
            assertEquals("updated", fixture.readDisk()[VALUE])
        } finally {
            releaseWrite.complete(Unit)
            update.cancelAndJoin()
        }
    }

    @Test
    fun cancellationDrainsFailedCommitAndRevalidatesBeforeQueuedWriter() = runTest {
        val fixture = fixture()
        fixture.store.edit { it[VALUE] = "committed" }
        val writeEntered = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        fixture.storage.afterWrite = {
            writeEntered.complete(Unit)
            releaseWrite.await()
            throw IOException("Injected cancelled commit failure")
        }
        val update = launch { fixture.store.edit { it[VALUE] = "uncommitted" } }
        try {
            runCurrent()
            assertTrue(writeEntered.isCompleted)
            update.cancel()
            runCurrent()
            assertFalse(update.isCompleted)
            var queuedSnapshot: String? = null
            val queued = async {
                fixture.store.edit {
                    queuedSnapshot = it[VALUE]
                    it[UNRELATED] = "queued"
                }
            }
            runCurrent()
            assertFalse(queued.isCompleted)
            releaseWrite.complete(Unit)
            update.join()
            queued.await()
            assertEquals("committed", queuedSnapshot)
            assertEquals("committed", fixture.store.data.first()[VALUE])
            assertEquals("committed", fixture.readDisk()[VALUE])
            assertEquals("queued", fixture.readDisk()[UNRELATED])
        } finally {
            releaseWrite.complete(Unit)
            update.cancelAndJoin()
        }
    }

    @Test
    fun nestedSameStoreUpdateRetainsAndroidXFailFast() = runTest {
        val fixture = fixture()
        fixture.store.edit { it[VALUE] = "committed" }
        val failure = runCatching {
            fixture.store.updateData { fixture.store.updateData { it } }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("Calling updateData inside updateData"))
        assertEquals("committed", fixture.store.data.first()[VALUE])
        fixture.store.edit { it[VALUE] = "next" }
        assertEquals("next", fixture.store.data.first()[VALUE])
    }

    @Test
    fun nestedDifferentStoresKeepIndependentAdmission() = runTest {
        val first = fixture()
        val second = fixture()
        first.store.edit { it[VALUE] = "first" }
        second.store.edit { it[VALUE] = "second" }
        first.store.edit { firstValues ->
            second.store.edit { it[VALUE] = "second-updated" }
            firstValues[VALUE] = "first-updated"
        }
        assertEquals("first-updated", first.store.data.first()[VALUE])
        assertEquals("second-updated", second.store.data.first()[VALUE])

        val failure = runCatching {
            first.store.updateData {
                second.store.updateData { first.store.updateData { it } }
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("Calling updateData inside updateData"))
        assertEquals("first-updated", first.store.data.first()[VALUE])
        assertEquals("second-updated", second.store.data.first()[VALUE])
    }

    @Test
    fun cleanupReadFailureIsSuppressedOnOriginalCommitFailure() = runTest {
        val fixture = fixture()
        fixture.store.edit { it[VALUE] = "committed" }
        val committedBytes = fixture.file.readBytes()
        val original = IOException("Injected original commit failure")
        val cleanup = IOException("Injected cleanup read failure")
        fixture.storage.afterWrite = {
            fixture.storage.beforeRead = { throw cleanup }
            throw original
        }
        val failure = runCatching { fixture.store.edit { it[VALUE] = "uncommitted" } }.exceptionOrNull()
        assertOriginalFailure(original, failure)
        assertRecoveryFailure(cleanup, failure)
        assertArrayEquals(committedBytes, fixture.file.readBytes())
        assertEquals("committed", fixture.store.data.first()[VALUE])
        fixture.store.edit { it[UNRELATED] = "next" }
        assertEquals("committed", fixture.readDisk()[VALUE])
        assertEquals("next", fixture.readDisk()[UNRELATED])
    }

    @Test
    fun cleanupFailureDoesNotReplaceOriginalThrowable() = runTest {
        val failures = listOf(
            IOException("Original IO failure"),
            CancellationException("Original cancellation"),
            IllegalStateException("Original programming defect"),
            AssertionError("Original error"),
        )
        for (original in failures) {
            val cleanup = IOException("Cleanup read failure")
            val rawStore = object : DataStore<Preferences> {
                private var failed = false
                override val data = flow<Preferences> { throw cleanup }

                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    if (!failed) {
                        failed = true
                        throw original
                    }
                    return transform(emptyPreferences())
                }
            }
            val store = PreferencesDataStore(rawStore, newOwner().coroutineContext.job)
            val failure = runCatching { store.updateData { it } }.exceptionOrNull()
            assertOriginalFailure(original, failure)
            assertRecoveryFailure(cleanup, failure)
        }
    }

    @Test
    fun ownerCancellationSettlesItsCallerAndPreservesCommittedFile() = runTest {
        val fixture = fixture()
        fixture.store.edit { it[VALUE] = "committed" }
        val committedBytes = fixture.file.readBytes()
        val writesBefore = fixture.storage.writes
        val transformEntered = CompletableDeferred<Unit>()
        val holdTransform = CompletableDeferred<Unit>()
        val observedFailure = CompletableDeferred<Throwable?>()
        val cancellation = CancellationException("DataStore owner stopped")
        val owner = fixture.owner.coroutineContext.job
        val update = fixture.owner.launch {
            observedFailure.complete(runCatching {
                fixture.store.edit {
                    it[VALUE] = "uncommitted"
                    transformEntered.complete(Unit)
                    holdTransform.await()
                }
            }.exceptionOrNull())
        }
        try {
            runCurrent()
            assertTrue(transformEntered.isCompleted)
            owner.cancel(cancellation)
            runCurrent()
            assertTrue("Owner shutdown must not wait for its caller's recovery", owner.isCompleted)
            assertTrue(update.isCompleted)
            assertOriginalFailure(cancellation, observedFailure.await())
            assertEquals(writesBefore, fixture.storage.writes)
            assertArrayEquals(committedBytes, fixture.file.readBytes())
            val reopened = createPreferencesDataStore(fixture.file, newOwner())
            assertEquals("committed", reopened.data.first()[VALUE])
        } finally {
            holdTransform.complete(Unit)
            update.cancelAndJoin()
        }
    }

    @Test
    fun ownerCancellationInterruptsRecoveryBarrierWithoutReplacingOriginalFailure() = runTest {
        val owner = newOwner().coroutineContext.job
        val original = IOException("Original commit failure")
        val cancellation = CancellationException("DataStore owner stopped during recovery barrier")
        val barrierEntered = CompletableDeferred<Unit>()
        val holdBarrier = CompletableDeferred<Unit>()
        var updates = 0
        var recoveryRead = false
        val rawStore = object : DataStore<Preferences> {
            override val data = flow {
                recoveryRead = true
                emit(emptyPreferences())
            }

            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (++updates == 1) throw original
                barrierEntered.complete(Unit)
                holdBarrier.await()
                return transform(emptyPreferences())
            }
        }
        val store = PreferencesDataStore(rawStore, owner)
        val update = async { runCatching { store.updateData { it } }.exceptionOrNull() }
        try {
            runCurrent()
            assertTrue(barrierEntered.isCompleted)
            owner.cancel(cancellation)
            runCurrent()
            assertTrue("Recovery barrier must stop with its owner", update.isCompleted)
            assertTrue(owner.isCompleted)
            val failure = update.await()
            assertOriginalFailure(original, failure)
            assertRecoveryFailure(cancellation, failure)
            assertEquals(2, updates)
            assertFalse(recoveryRead)
        } finally {
            holdBarrier.complete(Unit)
            update.cancelAndJoin()
        }
    }

    @Test
    fun ownerCancellationInterruptsRecoveryReadWithoutReplacingOriginalFailure() = runTest {
        val owner = newOwner().coroutineContext.job
        val original = IOException("Original commit failure")
        val cancellation = CancellationException("DataStore owner stopped during recovery read")
        val readEntered = CompletableDeferred<Unit>()
        val holdRead = CompletableDeferred<Unit>()
        var updates = 0
        val rawStore = object : DataStore<Preferences> {
            override val data = flow {
                readEntered.complete(Unit)
                holdRead.await()
                emit(emptyPreferences())
            }

            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (++updates == 1) throw original
                return transform(emptyPreferences())
            }
        }
        val store = PreferencesDataStore(rawStore, owner)
        val update = async { runCatching { store.updateData { it } }.exceptionOrNull() }
        try {
            runCurrent()
            assertTrue(readEntered.isCompleted)
            owner.cancel(cancellation)
            runCurrent()
            assertTrue("Recovery read must stop with its owner", update.isCompleted)
            assertTrue(owner.isCompleted)
            val failure = update.await()
            assertOriginalFailure(original, failure)
            assertRecoveryFailure(cancellation, failure)
            assertEquals(2, updates)
        } finally {
            holdRead.complete(Unit)
            update.cancelAndJoin()
        }
    }

    @Test
    fun closedOwnerFailsWithoutWritingAndRetainsReopenCompatibility() = runTest {
        val fixture = fixture()
        fixture.store.edit { it[VALUE] = "committed" }
        val committedBytes = fixture.file.readBytes()
        val writesBefore = fixture.storage.writes
        fixture.owner.coroutineContext[Job]!!.cancelAndJoin()
        val failure = runCatching { fixture.store.edit { it[VALUE] = "uncommitted" } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(writesBefore, fixture.storage.writes)
        assertArrayEquals(committedBytes, fixture.file.readBytes())
        val reopenedOwner = newOwner()
        val reopened = createPreferencesDataStore(fixture.file, reopenedOwner)
        assertEquals("committed", reopened.data.first()[VALUE])
    }

    private fun assertOriginalFailure(expected: Throwable, actual: Throwable?) {
        assertEquals(expected.javaClass, actual?.javaClass)
        assertEquals(expected.message, actual?.message)
        assertSame(expected, generateSequence(actual) { it.cause }.last())
    }

    private fun assertRecoveryFailure(expected: Throwable, actual: Throwable?) {
        assertTrue("Cleanup failure was not retained on original failure", generateSequence(actual) { it.cause }.any { cause ->
            cause.suppressed.any { suppressed -> generateSequence(suppressed) { it.cause }.last() === expected }
        })
    }

    private fun TestScope.fixture(): Fixture {
        val file = File(folder.newFolder(), "settings.preferences_pb")
        val storage = ControlledStorage(file)
        val owner = newOwner()
        val rawStore = PreferenceDataStoreFactory.create(storage = PreferencesStorage(storage), scope = owner)
        return Fixture(file, storage, owner, PreferencesDataStore(rawStore, owner.coroutineContext.job))
    }

    private fun TestScope.newOwner(): CoroutineScope = CoroutineScope(
        backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]) + StandardTestDispatcher(testScheduler),
    )

    private class Fixture(
        val file: File,
        val storage: ControlledStorage,
        val owner: CoroutineScope,
        val store: PreferencesDataStore,
    ) {
        suspend fun readDisk(): Preferences = file.inputStream().use { PreferencesFileSerializer.readFrom(it) }
    }

    private class ControlledStorage(file: File) : Storage<Preferences> {
        private val storage = FileStorage(PreferencesFileSerializer, produceFile = { file })
        var afterWrite: (suspend () -> Unit)? = null
        var beforeRead: (suspend () -> Unit)? = null
        var reads = 0
        var writes = 0

        override fun createConnection(): StorageConnection<Preferences> {
            val connection = storage.createConnection()
            return object : StorageConnection<Preferences> by connection {
                override suspend fun <R> readScope(block: suspend ReadScope<Preferences>.(Boolean) -> R): R {
                    reads++
                    val action = beforeRead
                    beforeRead = null
                    action?.invoke()
                    return connection.readScope(block)
                }

                override suspend fun writeScope(block: suspend WriteScope<Preferences>.() -> Unit) {
                    writes++
                    connection.writeScope {
                        block()
                        val action = afterWrite
                        afterWrite = null
                        action?.invoke()
                    }
                }
            }
        }
    }

    companion object {
        private val VALUE = stringPreferencesKey("last_used_tunnel")
        private val UNRELATED = stringPreferencesKey("unrelated")
    }
}
