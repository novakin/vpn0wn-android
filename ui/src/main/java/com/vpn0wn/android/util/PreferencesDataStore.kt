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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun createPreferencesDataStore(
    file: File,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    storage = PreferencesStorage(file),
    scope = scope,
)

internal class PreferencesStorage(file: File) : Storage<Preferences> {
    private val storage = FileStorage(PreferencesFileSerializer, produceFile = { file })

    override fun createConnection(): StorageConnection<Preferences> {
        val connection = storage.createConnection()
        val access = Mutex()
        return object : StorageConnection<Preferences> by connection {
            // FileStorage admits unlocked reads during replacement; wait until its write scope settles.
            override suspend fun <R> readScope(block: suspend ReadScope<Preferences>.(Boolean) -> R): R =
                access.withLock { connection.readScope(block) }

            override suspend fun writeScope(block: suspend WriteScope<Preferences>.() -> Unit) =
                access.withLock { connection.writeScope(block) }
        }
    }
}
