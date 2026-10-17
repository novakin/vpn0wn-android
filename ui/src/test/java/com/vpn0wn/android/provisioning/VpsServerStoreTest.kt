/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.provisioning

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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
        store = VpsServerStore(dataStore, credentialFileForTest(File(file.parentFile, file.name + ".credentials")))
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
            expected = server,
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
    fun delayedStatusCannotRestoreRemovedOrReplaceChangedServer() = runBlocking {
        val expected = storedServer(id = "ams-01")
        val unrelated = storedServer(id = "unrelated")
        store.upsert(expected)
        store.upsert(unrelated)
        store.remove(expected.id)
        expectChanged { store.updateStatus(expected, status("203.0.113.20"), 1710000000L) }
        assertEquals(listOf(unrelated), store.servers.first())
        val replacement = expected.copy(host = "198.51.100.99")
        store.upsert(replacement)
        expectChanged { store.updateStatus(expected, status("203.0.113.20"), 1710000000L) }
        assertEquals(setOf(unrelated, replacement), store.servers.first().toSet())
    }

    @Test
    fun emptyStoppedObservationClearsOldVpnMetadataWithoutChangingSshIdentity() = runBlocking {
        val expected = storedServer(id = "ams-01", endpoint = "vpn.example", listenPort = "51821")
        val unrelated = storedServer(id = "unrelated")
        store.upsert(expected)
        store.upsert(unrelated)
        val observed = status("").copy(running = false, listenPort = "")

        store.updateStatus(expected, observed, 1710000001L)

        val saved = store.servers.first().single { it.id == expected.id }
        assertEquals("", saved.endpoint)
        assertEquals("", saved.listenPort)
        assertEquals(expected.host, saved.host)
        assertEquals(expected.sshPort, saved.sshPort)
        assertEquals(expected.sshUser, saved.sshUser)
        assertEquals(false, saved.running)
        assertEquals(unrelated, store.servers.first().single { it.id == unrelated.id })
    }

    @Test
    fun statusCommitRejectsNewerObservationAndPreservesCurrentMetadata() = runBlocking {
        val expected = storedServer(id = "ams-01")
        store.upsert(expected)
        val committed = store.updateStatus(expected, status("203.0.113.20"), 1710000001L)
        expectChanged { store.updateStatus(expected, status("203.0.113.21"), 1710000000L) }
        assertEquals(committed, store.servers.first().single())
        store.upsert(committed.copy(name = "updated display"))
        expectChanged { store.updateStatus(committed, status("203.0.113.22"), 1710000002L) }
        assertEquals("updated display", store.servers.first().single().name)
        assertEquals("203.0.113.20", store.servers.first().single().endpoint)
    }

    private suspend fun expectChanged(action: suspend () -> Unit) {
        try {
            action()
            throw AssertionError("Expected current server conflict")
        } catch (_: VpsServerStore.ServerChangedException) {
            Unit
        }
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
            launch { store.updateStatus(base, status(endpoint = "203.0.113.99"), 1710000001L) },
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
            store.updateStatus(store.servers.first().single(), status("203.0.113.10").copy(running = running), 1710000000L)
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

    @Test
    fun transferRoundTripPreservesEveryFieldAndNullableRunning() {
        val servers = listOf(
            storedServer(id = "ams-01").copy(
                name = "  Amsterdam \"office\"\nVPN  ",
                host = "vpn.example",
                sshPort = 1,
                sshUser = "operator",
                endpoint = "vpn.example:51820",
                listenPort = "51820",
                cidr = "10.8.0.0/24",
                dns = "1.1.1.1, 2606:4700:4700::1111",
                os = "Ubuntu",
                version = "24.04",
                provider = "Synthetic provider",
                region = "Île-de-France",
                engine = "kernel",
                lastSyncEpochSeconds = Long.MAX_VALUE,
                running = true,
            ),
            storedServer(id = "stopped").copy(sshPort = 65_535, running = false),
            storedServer(id = "unknown").copy(running = null),
        )

        val encoded = VpsServerStore.encodeServers(servers)

        assertEquals(servers, VpsServerStore.decodeTransferServers(encoded))
        val array = JSONArray(encoded)
        assertEquals(JSONObject.NULL, array.getJSONObject(2).get("running"))
        for (index in 0 until array.length()) {
            assertEquals(16, array.getJSONObject(index).length())
        }
    }

    @Test
    fun transferAcceptsEmptyStoreWithoutDefaultingCorruptInput() {
        assertTrue(VpsServerStore.decodeTransferServers("").isEmpty())
        assertTrue(VpsServerStore.decodeTransferServers(" \n[]\t ").isEmpty())
        assertEquals("[]", VpsServerStore.encodeServers(emptyList()))
        assertInvalidTransfer(" \n\t ")
    }

    @Test
    fun transferRejectsCorruptJsonAndWrongTopLevelTypesWithoutPayloads() {
        for (value in listOf("{secret-marker", "{}", "null", "true", "\"secret-marker\"", "[] secret-marker")) {
            assertInvalidTransfer(value)
        }
    }

    @Test
    fun transferRequiresEveryKnownField() {
        val valid = transferServerJson()
        for (field in valid.keys().asSequence().toList()) {
            val missing = JSONObject(valid.toString()).apply { remove(field) }
            assertInvalidTransfer(JSONArray().put(missing).toString())
        }
    }

    @Test
    fun transferRejectsStringFieldCoercionAndNulls() {
        val fields = listOf("id", "name", "host", "sshUser", "endpoint", "listenPort", "cidr", "dns", "os", "version", "provider", "region", "engine")
        val values = listOf(JSONObject.NULL, true, 7, JSONObject(), JSONArray())
        for (field in fields) {
            for (value in values) {
                val server = transferServerJson().put(field, value)
                assertInvalidTransfer(JSONArray().put(server).toString())
            }
        }
    }

    @Test
    fun transferRejectsIntegerCoercionFractionalValuesAndOverflow() {
        for (field in listOf("sshPort", "lastSyncEpochSeconds")) {
            for (value in listOf(JSONObject.NULL, "22", true, 22.5, JSONObject(), JSONArray())) {
                val server = transferServerJson().put(field, value)
                assertInvalidTransfer(JSONArray().put(server).toString())
            }
            val base = transferServerJson().toString()
            val original = if (field == "sshPort") "22" else "0"
            for (value in listOf("22.0", "2.2e1", "9223372036854775808")) {
                assertInvalidTransfer("[${base.replace("\"$field\":$original", "\"$field\":$value")}]")
            }
        }
    }

    @Test
    fun transferRejectsBlankConnectionFieldsAndInvalidNumericBounds() {
        for (field in listOf("id", "host", "sshUser")) {
            for (value in listOf("", " \n\t ")) {
                assertInvalidTransfer(JSONArray().put(transferServerJson().put(field, value)).toString())
            }
        }
        for (port in listOf(-1, 0, 65_536, Long.MAX_VALUE)) {
            assertInvalidTransfer(JSONArray().put(transferServerJson().put("sshPort", port)).toString())
        }
        assertInvalidTransfer(JSONArray().put(transferServerJson().put("lastSyncEpochSeconds", -1)).toString())
    }

    @Test
    fun transferRequiresBooleanOrExplicitNullRunningObservation() {
        for (value in listOf("true", 1, JSONObject(), JSONArray())) {
            assertInvalidTransfer(JSONArray().put(transferServerJson().put("running", value)).toString())
        }
    }

    @Test
    fun transferRejectsMalformedRowsRatherThanDroppingThem() {
        for (value in listOf(JSONObject.NULL, "secret-marker", 1, true, JSONArray(), JSONObject())) {
            assertInvalidTransfer(JSONArray().put(transferServerJson()).put(value).toString())
        }
    }

    @Test
    fun transferRejectsDuplicateIdsWithoutChangingLegacyReader() = runBlocking {
        val first = storedServer(id = "duplicate")
        val second = first.copy(host = "other.example")
        val encoded = VpsServerStore.encodeServers(listOf(first, second))

        assertInvalidTransfer(encoded)
        writeRawStore(encoded)

        assertEquals(listOf(first, second), store.servers.first())
    }

    @Test
    fun transferRejectsUnknownFieldsRatherThanDroppingDestinationMetadata() {
        assertInvalidTransfer(JSONArray().put(transferServerJson().put("unknown", "fixture-private-value")).toString())
    }

    @Test
    fun transferReadsOriginalStoredShapeWithoutRelaxingArchiveSchema() {
        val legacy = transferServerJson().apply { remove("running") }
        val payload = JSONArray().put(legacy).toString()
        assertEquals(null, VpsServerStore.decodeStoredServersForTransfer(payload).single().running)
        assertInvalidTransfer(payload)
        legacy.remove("host")
        assertThrows(IllegalArgumentException::class.java) {
            VpsServerStore.decodeStoredServersForTransfer(JSONArray().put(legacy).toString())
        }
    }

    private fun transferServerJson(): JSONObject =
        JSONArray(VpsServerStore.encodeServers(listOf(storedServer(id = "ams-01")))).getJSONObject(0)

    private fun assertInvalidTransfer(value: String) {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            VpsServerStore.decodeTransferServers(value)
        }
        assertEquals("Invalid VPS fleet metadata", failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
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
