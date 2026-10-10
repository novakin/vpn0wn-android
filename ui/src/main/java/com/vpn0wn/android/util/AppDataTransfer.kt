/*
 * Copyright © 2026 vpn0wn contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.util

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.provisioning.StoredVpsServer
import com.vpn0wn.android.provisioning.VpsServerStore
import com.vpn0wn.config.BadConfigException
import com.vpn0wn.config.Config
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

internal object AppDataTransfer {
    const val MANIFEST = "migration.json"
    private const val FORMAT = "vpn0wn-transfer"
    private const val VERSION = 1
    private const val PROFILE_DIRECTORY = "profiles/"
    private const val PROFILE_SUFFIX = ".conf"
    private const val MAX_PROFILES = 256
    private const val MAX_ENTRY_BYTES = 1_048_576
    private const val MAX_EXPANDED_BYTES = 16_777_216
    private const val COPY_BUFFER_BYTES = 8192

    data class Archive(
        val profiles: Map<String, Config>,
        val settings: Map<String, Boolean>,
        val lastUsedTunnel: String?,
        val servers: List<StoredVpsServer>,
    )

    class InvalidArchiveException : IllegalArgumentException("Invalid app transfer archive")
    class ConflictException : IllegalArgumentException("App transfer conflicts with existing data")
    class IncompleteRecoveryException : IllegalStateException("App transfer failed; some new profiles remain")

    fun snapshot(profiles: Map<String, Config>, preferences: Preferences): Archive {
        val settings = buildMap {
            for (key in UserKnobs.transferBooleanKeys) {
                val value = preferences.asMap()[key] ?: continue
                if (value !is Boolean) invalidArchive()
                put(key.name, value)
            }
        }
        val lastUsed = preferences[UserKnobs.LAST_USED_TUNNEL]?.takeIf { it in profiles }
        return Archive(profiles, settings, lastUsed, VpsServerStore.decodeStoredServersForTransfer(preferences[VpsServerStore.SERVERS].orEmpty()))
    }

    fun validateMetadata(archive: Archive, preferences: Preferences): List<StoredVpsServer> {
        val existing = VpsServerStore.decodeStoredServersForTransfer(preferences[VpsServerStore.SERVERS].orEmpty())
        val byId = existing.associateBy { it.id }
        for (server in archive.servers) {
            val previous = byId[server.id]
            if (previous != null && previous != server) throw ConflictException()
        }
        return existing + archive.servers.filter { it.id !in byId }
    }

    fun applyMetadata(archive: Archive, preferences: MutablePreferences, servers: List<StoredVpsServer>, newNames: Set<String>) {
        for (key in UserKnobs.transferBooleanKeys) {
            archive.settings[key.name]?.let { preferences[key] = it }
        }
        if (archive.lastUsedTunnel != null) preferences[UserKnobs.LAST_USED_TUNNEL] = archive.lastUsedTunnel
        preferences[VpsServerStore.SERVERS] = VpsServerStore.encodeServers(servers)
        clearRestoreMarkers(preferences, newNames)
    }

    fun clearRestoreMarkers(preferences: MutablePreferences, newNames: Set<String>) {
        preferences[UserKnobs.RUNNING_TUNNELS]?.let { running ->
            val retained = running - newNames
            if (retained.isEmpty()) preferences.remove(UserKnobs.RUNNING_TUNNELS)
            else preferences[UserKnobs.RUNNING_TUNNELS] = retained
        }
    }

    fun validate(archive: Archive) {
        if (archive.profiles.size > MAX_PROFILES || archive.profiles.keys.any(Tunnel::isNameInvalid)) invalidArchive()
        if (archive.settings.keys.any { name -> UserKnobs.transferBooleanKeys.none { it.name == name } }) invalidArchive()
        if (archive.lastUsedTunnel != null && Tunnel.isNameInvalid(archive.lastUsedTunnel)) invalidArchive()
        VpsServerStore.decodeTransferServers(VpsServerStore.encodeServers(archive.servers))
        val contents = encodedEntries(archive)
        if (contents.any { it.second.size > MAX_ENTRY_BYTES } || contents.sumOf { it.second.size.toLong() } > MAX_EXPANDED_BYTES) {
            invalidArchive()
        }
    }

    fun write(archive: Archive, output: OutputStream) {
        validate(archive)
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in encodedEntries(archive)) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    fun read(input: InputStream): Archive {
        val profiles = linkedMapOf<String, Config>()
        var manifest: JSONObject? = null
        var expanded = 0
        val names = mutableSetOf<String>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!names.add(entry.name) || names.size > MAX_PROFILES + 1 || entry.isDirectory) invalidArchive()
                val bytes = readEntry(zip, MAX_EXPANDED_BYTES - expanded)
                expanded += bytes.size
                if (entry.name == MANIFEST) {
                    manifest = parseManifest(bytes)
                } else {
                    val name = entry.name.removePrefix(PROFILE_DIRECTORY).removeSuffix(PROFILE_SUFFIX)
                    if (entry.name != "$PROFILE_DIRECTORY$name$PROFILE_SUFFIX" || Tunnel.isNameInvalid(name)) invalidArchive()
                    profiles[name] = parseProfile(bytes)
                }
                entry = zip.nextEntry
            }
        }
        val archive = decodeManifest(manifest ?: invalidArchive(), profiles)
        validate(archive)
        return archive
    }

    private fun readEntry(input: InputStream, remaining: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var count = input.read(buffer)
        while (count != -1) {
            if (output.size() + count > minOf(MAX_ENTRY_BYTES, remaining)) invalidArchive()
            output.write(buffer, 0, count)
            count = input.read(buffer)
        }
        return output.toByteArray()
    }

    private fun decodeText(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        invalidArchive()
    }

    private fun parseManifest(bytes: ByteArray): JSONObject = try {
        val tokener = JSONTokener(decodeText(bytes))
        val json = tokener.nextValue() as? JSONObject ?: invalidArchive()
        if (tokener.nextClean() != '\u0000') invalidArchive()
        json
    } catch (_: JSONException) {
        invalidArchive()
    }

    private fun parseProfile(bytes: ByteArray): Config = try {
        Config.parse(decodeText(bytes).byteInputStream(StandardCharsets.UTF_8))
    } catch (_: BadConfigException) {
        invalidArchive()
    } catch (_: IOException) {
        invalidArchive()
    }

    private fun decodeManifest(json: JSONObject, profiles: Map<String, Config>): Archive {
        if (json.keys().asSequence().toSet() != setOf("format", "version", "settings", "servers", "profiles")) invalidArchive()
        if (json.opt("format") != FORMAT || json.opt("version") != VERSION) invalidArchive()
        val inventory = json.optJSONArray("profiles") ?: invalidArchive()
        if (inventory.length() > MAX_PROFILES) invalidArchive()
        val names = (0 until inventory.length()).map { inventory.opt(it) as? String ?: invalidArchive() }
        if (names.any(Tunnel::isNameInvalid) || names.toSet().size != names.size || names.toSet() != profiles.keys) invalidArchive()
        val settings = json.optJSONObject("settings") ?: invalidArchive()
        val booleans = buildMap {
            for (name in settings.keys()) {
                if (name == UserKnobs.LAST_USED_TUNNEL.name) continue
                val value = settings.opt(name)
                if (UserKnobs.transferBooleanKeys.none { it.name == name } || value !is Boolean) invalidArchive()
                put(name, value)
            }
        }
        val lastUsed = if (settings.has(UserKnobs.LAST_USED_TUNNEL.name)) {
            settings.opt(UserKnobs.LAST_USED_TUNNEL.name) as? String ?: invalidArchive()
        } else null
        val servers = json.optJSONArray("servers") ?: invalidArchive()
        return Archive(profiles, booleans, lastUsed, VpsServerStore.decodeTransferServers(servers.toString()))
    }

    private fun invalidArchive(): Nothing = throw InvalidArchiveException()

    private fun encodedEntries(archive: Archive): List<Pair<String, ByteArray>> {
        val settings = JSONObject()
        for ((name, value) in archive.settings) settings.put(name, value)
        if (archive.lastUsedTunnel != null) settings.put(UserKnobs.LAST_USED_TUNNEL.name, archive.lastUsedTunnel)
        val manifest = JSONObject().put("format", FORMAT).put("version", VERSION).put("settings", settings)
            .put("servers", org.json.JSONArray(VpsServerStore.encodeServers(archive.servers)))
            .put("profiles", org.json.JSONArray(archive.profiles.keys.toList()))
        return listOf(MANIFEST to manifest.toString().toByteArray(StandardCharsets.UTF_8)) + archive.profiles.map { (name, config) ->
            "$PROFILE_DIRECTORY$name$PROFILE_SUFFIX" to config.toWgQuickString().toByteArray(StandardCharsets.UTF_8)
        }
    }
}
