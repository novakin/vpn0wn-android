/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.util

import androidx.datastore.core.DataStore
import androidx.datastore.core.FileStorage
import androidx.datastore.core.ReadScope
import androidx.datastore.core.Storage
import androidx.datastore.core.StorageConnection
import androidx.datastore.core.WriteScope
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal fun createPreferencesDataStore(
    file: File,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
): DataStore<Preferences> = PreferencesDataStore(
    PreferenceDataStoreFactory.create(storage = PreferencesStorage(file), scope = scope),
    scope.coroutineContext.job,
)

internal class PreferencesStorage(private val storage: Storage<Preferences>) : Storage<Preferences> {
    constructor(file: File) : this(FileStorage(PreferencesFileSerializer, produceFile = { file }))

    override fun createConnection(): StorageConnection<Preferences> {
        val connection = storage.createConnection()
        val access = Mutex()
        return object : StorageConnection<Preferences> by connection {
            // FileStorage admits unlocked reads during replacement; wait until its write scope settles.
            override suspend fun <R> readScope(block: suspend ReadScope<Preferences>.(Boolean) -> R): R =
                access.withLock { connection.readScope(block) }

            // Cache publication precedes final replacement; invalidate it before the writer releases coordination.
            @Suppress("TooGenericExceptionCaught")
            override suspend fun writeScope(block: suspend WriteScope<Preferences>.() -> Unit) = access.withLock {
                try {
                    connection.writeScope(block)
                } catch (failure: Throwable) {
                    try {
                        withContext(NonCancellable) { connection.coordinator.incrementAndGetVersion() }
                    } catch (recovery: Throwable) {
                        addRecoveryFailure(failure, recovery)
                    }
                    throw failure
                }
            }
        }
    }
}

internal class PreferencesDataStore(
    private val delegate: DataStore<Preferences>,
    private val owner: Job,
) : DataStore<Preferences> {
    private val updates = Mutex()
    private val updateKey = object : CoroutineContext.Key<UpdateContext> {}
    override val data get() = delegate.data

    // Rollback survives caller cancellation, but must release owner shutdown rather than await its actor forever.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        if (currentCoroutineContext()[updateKey] != null) return delegate.updateData(transform)
        return updates.withLock {
            withContext(UpdateContext(updateKey)) {
                try {
                    delegate.updateData(transform)
                } catch (failure: Throwable) {
                    try {
                        withContext(NonCancellable) {
                            withContext(owner) {
                                delegate.updateData { it }
                                delegate.data.first()
                            }
                        }
                    } catch (recovery: Throwable) {
                        addRecoveryFailure(failure, recovery)
                    }
                    throw failure
                }
            }
        }
    }

    private class UpdateContext(override val key: CoroutineContext.Key<*>) : CoroutineContext.Element
}

private fun addRecoveryFailure(failure: Throwable, recovery: Throwable) {
    if (failure === recovery) return
    failure.addSuppressed(recovery)
    // Coroutine stack recovery may unwrap a same-class copy at the next suspension boundary.
    val cause = failure.cause
    if (cause != null && cause.javaClass == failure.javaClass && cause !== recovery) cause.addSuppressed(recovery)
}
