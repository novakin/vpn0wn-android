/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.provisioning

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VpsServerStoreTest {
    private lateinit var scope: CoroutineScope
    private lateinit var store: VpsServerStore

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val file = File(System.getProperty("java.io.tmpdir"), "vps-store-${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        store = VpsServerStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun emptyStoreReturnsEmptyServerList() = runBlocking {
        assertTrue(store.servers.first().isEmpty())
    }

    @Test
    fun upsertInsertsServer() = runBlocking {
        val server = storedServer(id = "ams-01")

        store.upsert(server)

        assertEquals(listOf(server), store.servers.first())
    }

    @Test
    fun upsertPersistsDisplayMetadata() = runBlocking {
        val server = storedServer(id = "ams-01").copy(
            provider = "Hetzner",
            region = "Amsterdam",
            engine = "kernel",
        )

        store.upsert(server)

        assertEquals(server, store.servers.first().single())
    }

    @Test
    fun upsertReplacesExistingServerById() = runBlocking {
        store.upsert(storedServer(id = "ams-01", endpoint = "203.0.113.1"))
        val replacement = storedServer(id = "ams-01", endpoint = "203.0.113.2", os = "Debian 12")

        store.upsert(replacement)

        assertEquals(listOf(replacement), store.servers.first())
    }

    @Test
    fun removeDeletesOnlyMatchingServer() = runBlocking {
        val ams = storedServer(id = "ams-01")
        val fra = storedServer(id = "fra-01")
        store.upsert(ams)
        store.upsert(fra)

        store.remove("ams-01")

        assertEquals(listOf(fra), store.servers.first())
    }

    @Test
    fun updateStatusRefreshesRuntimeFields() = runBlocking {
        val server = storedServer(id = "ams-01", endpoint = "203.0.113.1", listenPort = "51820")
        store.upsert(server)

        store.updateStatus(
            id = "ams-01",
            status = VpsServerStatus(
                interfaceName = "wg0",
                running = true,
                endpoint = "203.0.113.2",
                listenPort = "51821",
                cidr = "10.9.0.0/24",
                dns = "9.9.9.9",
                peers = emptyList(),
            ),
            syncedAtEpochSeconds = 1710000000L,
        )

        assertEquals(
            server.copy(
                endpoint = "203.0.113.2",
                listenPort = "51821",
                cidr = "10.9.0.0/24",
                dns = "9.9.9.9",
                lastSyncEpochSeconds = 1710000000L,
                running = true,
            ),
            store.servers.first().single(),
        )
    }

    @Test
    fun corruptJsonFallsBackToEmptyList() = runBlocking {
        writeRawStore("{not-json")

        assertTrue(store.servers.first().isEmpty())
    }

    @Test
    fun decodeClampsCorruptSshPort() = runBlocking {
        writeRawStore(
            """
            [{
              "id": "ams-01",
              "name": "ams-01",
              "host": "198.51.100.10",
              "sshPort": 99999,
              "sshUser": "root",
              "endpoint": "203.0.113.10"
            }]
            """.trimIndent(),
        )

        assertEquals(65_535, store.servers.first().single().sshPort)
    }

    @Test
    fun decodeDropsServersWithoutConnectionFields() = runBlocking {
        writeRawStore(
            """
            [
              {"id":"blank-host","name":"blank-host","host":"","sshPort":22,"sshUser":"root"},
              {"id":"blank-user","name":"blank-user","host":"198.51.100.10","sshPort":22,"sshUser":""},
              {"id":"valid","name":"valid","host":"198.51.100.11","sshPort":22,"sshUser":"root"}
            ]
            """.trimIndent(),
        )

        assertEquals(listOf("valid"), store.servers.first().map { it.id })
    }

    @Test
    fun concurrentMutationsPreserveDataStoreTransactionSemantics() = runBlocking {
        val base = storedServer(id = "ams-01")
        store.upsert(base)

        val jobs = listOf(
            launch { store.upsert(storedServer(id = "fra-01")) },
            launch { store.updateStatus("ams-01", status(endpoint = "203.0.113.99"), 1710000001L) },
            launch { store.remove("missing") },
            launch { store.upsert(storedServer(id = "nyc-01")) },
        )
        jobs.forEach { it.join() }

        val servers = store.servers.first().associateBy { it.id }
        assertEquals(setOf("ams-01", "fra-01", "nyc-01"), servers.keys)
        assertEquals("203.0.113.99", servers.getValue("ams-01").endpoint)
        assertEquals(1710000001L, servers.getValue("ams-01").lastSyncEpochSeconds)
    }

    @Test
    fun runningStoppedAndUnknownObservationsRoundTripWithoutInventingHealth() = runBlocking {
        val server = storedServer(id = "ams-01")
        store.upsert(server)
        for (running in listOf(true, false, null)) {
            store.updateStatus(server.id, status("203.0.113.10").copy(running = running), 1710000000L)
            assertEquals(running, store.servers.first().single().running)
        }
        store.upsert(server.copy(id = "other", running = true))
        store.remove("missing")
        assertEquals(null, store.servers.first().first { it.id == server.id }.running)
        assertEquals(true, store.servers.first().first { it.id == "other" }.running)
    }

    @Test
    fun legacyAndMalformedRunningFieldsMigrateToUnknown() = runBlocking {
        for (field in listOf("", ""","running":null""", ""","running":"true"""", ""","running":{}""")) {
            writeRawStore("""[{"id":"legacy","name":"kept","host":"vpn.example","sshPort":22,"sshUser":"root","endpoint":"vpn.example","cidr":"10.8.0.0/24","lastSyncEpochSeconds":1710000000$field}]""")
            val server = store.servers.first().single()
            assertEquals("kept", server.name)
            assertEquals(1710000000L, server.lastSyncEpochSeconds)
            assertEquals(null, server.running)
            store.upsert(server.copy(provider = "kept provider"))
            assertEquals("kept provider", store.servers.first().single().provider)
            assertEquals(null, store.servers.first().single().running)
        }
    }

    private fun storedServer(
        id: String,
        endpoint: String = "203.0.113.10",
        listenPort: String = "51820",
        os: String = "Ubuntu 24.04",
    ) = StoredVpsServer(
        id = id,
        name = id,
        host = "198.51.100.10",
        sshPort = 22,
        sshUser = "root",
        endpoint = endpoint,
        listenPort = listenPort,
        cidr = "10.8.0.0/24",
        dns = "1.1.1.1",
        os = os,
        version = "wireguard-tools 1.0",
        lastSyncEpochSeconds = 0L,
    )

    private fun status(endpoint: String) = VpsServerStatus(
        interfaceName = "wg0",
        running = true,
        endpoint = endpoint,
        listenPort = "51820",
        cidr = "10.8.0.0/24",
        dns = "1.1.1.1",
        peers = emptyList(),
    )

    private suspend fun writeRawStore(value: String) {
        val dataStoreField = VpsServerStore::class.java.getDeclaredField("dataStore").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val dataStore = dataStoreField.get(store) as androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("vps_servers")] = value
        }
    }
}
