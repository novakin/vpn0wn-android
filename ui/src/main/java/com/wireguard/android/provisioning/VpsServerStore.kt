/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.provisioning

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

data class StoredVpsServer(
    val id: String,
    val name: String,
    val host: String,
    val sshPort: Int,
    val sshUser: String,
    val endpoint: String,
    val listenPort: String,
    val cidr: String,
    val dns: String,
    val os: String = "",
    val version: String = "",
    val provider: String = "",
    val region: String = "",
    val engine: String = "",
    val lastSyncEpochSeconds: Long = 0L,
    val running: Boolean? = null,
)

class VpsServerStore(private val dataStore: DataStore<Preferences>) {
    internal class InvalidTransferMetadataException : IllegalArgumentException("Invalid VPS fleet metadata")

    val servers: Flow<List<StoredVpsServer>> = dataStore.data.map { preferences ->
        decodeServers(preferences[SERVERS].orEmpty())
    }

    suspend fun upsert(server: StoredVpsServer) {
        dataStore.edit { preferences ->
            val servers = decodeServers(preferences[SERVERS].orEmpty())
                .filterNot { it.id == server.id } + server
            preferences[SERVERS] = encodeServers(servers)
        }
    }

    suspend fun remove(id: String) {
        dataStore.edit { preferences ->
            val servers = decodeServers(preferences[SERVERS].orEmpty()).filterNot { it.id == id }
            preferences[SERVERS] = encodeServers(servers)
        }
    }

    suspend fun updateStatus(id: String, status: VpsServerStatus, syncedAtEpochSeconds: Long) {
        dataStore.edit { preferences ->
            val servers = decodeServers(preferences[SERVERS].orEmpty()).map { server ->
                if (server.id != id) {
                    server
                } else {
                    server.copy(
                        endpoint = status.endpoint,
                        listenPort = status.listenPort,
                        cidr = status.cidr,
                        dns = status.dns,
                        lastSyncEpochSeconds = syncedAtEpochSeconds,
                        running = status.running,
                    )
                }
            }
            preferences[SERVERS] = encodeServers(servers)
        }
    }

    private fun decodeServers(value: String): List<StoredVpsServer> {
        if (value.isBlank()) return emptyList()
        val array = try {
            JSONArray(value)
        } catch (_: JSONException) {
            Log.w(TAG, "Ignoring corrupt VPS server store payload")
            return emptyList()
        }
        return buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                add(
                    StoredVpsServer(
                        id = json.optString("id"),
                        name = json.optString("name"),
                        host = json.optString("host"),
                        sshPort = json.optInt("sshPort", DEFAULT_SSH_PORT).coerceIn(1, MAX_PORT_NUMBER),
                        sshUser = json.optString("sshUser", "root"),
                        endpoint = json.optString("endpoint"),
                        listenPort = json.optString("listenPort"),
                        cidr = json.optString("cidr"),
                        dns = json.optString("dns"),
                        os = json.optString("os"),
                        version = json.optString("version"),
                        provider = json.optString("provider"),
                        region = json.optString("region"),
                        engine = json.optString("engine"),
                        lastSyncEpochSeconds = json.optLong("lastSyncEpochSeconds"),
                        running = json.opt("running") as? Boolean,
                    ),
                )
            }
        }.filter { it.id.isNotBlank() && it.host.isNotBlank() && it.sshUser.isNotBlank() }
    }

    companion object {
        private const val DEFAULT_SSH_PORT = 22
        private const val MAX_PORT_NUMBER = 65_535
        private const val TRANSFER_FIELD_COUNT = 16
        private const val TAG = "WireGuard/VpsServerStore"
        internal val SERVERS = stringPreferencesKey("vps_servers")

        internal fun encodeServers(servers: List<StoredVpsServer>): String {
            val array = JSONArray()
            for (server in servers) {
                array.put(
                    JSONObject()
                        .put("id", server.id)
                        .put("name", server.name)
                        .put("host", server.host)
                        .put("sshPort", server.sshPort)
                        .put("sshUser", server.sshUser)
                        .put("endpoint", server.endpoint)
                        .put("listenPort", server.listenPort)
                        .put("cidr", server.cidr)
                        .put("dns", server.dns)
                        .put("os", server.os)
                        .put("version", server.version)
                        .put("provider", server.provider)
                        .put("region", server.region)
                        .put("engine", server.engine)
                        .put("lastSyncEpochSeconds", server.lastSyncEpochSeconds)
                        .put("running", server.running ?: JSONObject.NULL),
                )
            }
            return array.toString()
        }

        internal fun decodeTransferServers(value: String): List<StoredVpsServer> = decodeTransferServers(value, false)

        internal fun decodeStoredServersForTransfer(value: String): List<StoredVpsServer> = decodeTransferServers(value, true)

        private fun decodeTransferServers(value: String, allowLegacyRunning: Boolean): List<StoredVpsServer> {
            if (value.isEmpty()) return emptyList()
            try {
                val tokener = JSONTokener(value)
                val array = tokener.nextValue() as? JSONArray ?: invalidTransferServers()
                if (tokener.nextClean() != '\u0000') invalidTransferServers()
                val ids = mutableSetOf<String>()
                return buildList {
                    for (index in 0 until array.length()) {
                        val json = array.get(index) as? JSONObject ?: invalidTransferServers()
                        // The original store writer preceded running observations; its other fields were complete.
                        if (allowLegacyRunning && json.length() == TRANSFER_FIELD_COUNT - 1 && !json.has("running")) {
                            json.put("running", JSONObject.NULL)
                        }
                        val server = decodeTransferServer(json)
                        if (!ids.add(server.id)) invalidTransferServers()
                        add(server)
                    }
                }
            } catch (_: JSONException) {
                invalidTransferServers()
            }
        }

        private fun decodeTransferServer(json: JSONObject): StoredVpsServer {
            if (json.length() != TRANSFER_FIELD_COUNT) invalidTransferServers()
            val id = json.transferString("id")
            val host = json.transferString("host")
            val sshUser = json.transferString("sshUser")
            val sshPort = json.transferLong("sshPort")
            val lastSync = json.transferLong("lastSyncEpochSeconds")
            if (id.isBlank() || host.isBlank() || sshUser.isBlank() ||
                sshPort !in 1..MAX_PORT_NUMBER || lastSync < 0L
            ) invalidTransferServers()
            return StoredVpsServer(
                id = id,
                name = json.transferString("name"),
                host = host,
                sshPort = sshPort.toInt(),
                sshUser = sshUser,
                endpoint = json.transferString("endpoint"),
                listenPort = json.transferString("listenPort"),
                cidr = json.transferString("cidr"),
                dns = json.transferString("dns"),
                os = json.transferString("os"),
                version = json.transferString("version"),
                provider = json.transferString("provider"),
                region = json.transferString("region"),
                engine = json.transferString("engine"),
                lastSyncEpochSeconds = lastSync,
                running = when (val running = json.get("running")) {
                    JSONObject.NULL -> null
                    is Boolean -> running
                    else -> invalidTransferServers()
                },
            )
        }

        private fun JSONObject.transferString(name: String): String =
            get(name) as? String ?: invalidTransferServers()

        private fun JSONObject.transferLong(name: String): Long = when (val value = get(name)) {
            is Int -> value.toLong()
            is Long -> value
            else -> invalidTransferServers()
        }

        private fun invalidTransferServers(): Nothing =
            throw InvalidTransferMetadataException()
    }
}
