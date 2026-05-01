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
import org.json.JSONObject

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
)

class VpsServerStore(private val dataStore: DataStore<Preferences>) {
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
        } catch (e: Throwable) {
            Log.w(TAG, "Ignoring corrupt VPS server store payload", e)
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
                        sshPort = json.optInt("sshPort", 22).coerceIn(1, 65_535),
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
                    ),
                )
            }
        }.filter { it.id.isNotBlank() && it.host.isNotBlank() && it.sshUser.isNotBlank() }
    }

    private fun encodeServers(servers: List<StoredVpsServer>): String {
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
                    .put("lastSyncEpochSeconds", server.lastSyncEpochSeconds),
            )
        }
        return array.toString()
    }

    companion object {
        private const val TAG = "WireGuard/VpsServerStore"
        private val SERVERS = stringPreferencesKey("vps_servers")
    }
}
