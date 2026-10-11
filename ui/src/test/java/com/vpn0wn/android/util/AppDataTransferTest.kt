package com.vpn0wn.android.util

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.android.provisioning.StoredVpsServer
import com.vpn0wn.android.provisioning.VpsServerStore
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppDataTransferTest {
    private val profile = TunnelTestEnvironment.profile()
    private val server = StoredVpsServer(
        "one", "Test server", "192.0.2.1", 22, "root", "192.0.2.1", "51820", "10.0.0.0/24", "1.1.1.1",
        lastSyncEpochSeconds = 42, running = null,
    )

    private fun archive() = AppDataTransfer.Archive(
        linkedMapOf("one" to profile), mapOf("dark_theme" to false, "restore_on_boot" to true), "one", listOf(server),
    )

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun manifest(settings: JSONObject = JSONObject(), profiles: List<String> = emptyList()): JSONObject = JSONObject()
        .put("format", "vpn0wn-transfer").put("version", 1).put("settings", settings).put("servers", org.json.JSONArray())
        .put("profiles", org.json.JSONArray(profiles))

    private fun input(json: JSONObject, extra: List<Pair<String, ByteArray>> = emptyList()) = zip(
        listOf(AppDataTransfer.MANIFEST to json.toString().toByteArray()) + extra,
    ).inputStream()

    @Test
    fun fullArchiveRoundTripsConfigSettingsAndUnknownServerHealth() {
        val original = archive()
        val output = ByteArrayOutputStream()
        AppDataTransfer.write(original, output)
        val restored = AppDataTransfer.read(output.toByteArray().inputStream())
        assertEquals(original.profiles.mapValues { it.value.toWgQuickString() }, restored.profiles.mapValues { it.value.toWgQuickString() })
        assertEquals(original.settings, restored.settings)
        assertEquals(original.lastUsedTunnel, restored.lastUsedTunnel)
        assertEquals(original.servers, restored.servers)
    }

    @Test
    fun emptyProfilesAndSettingsOnlyAreValid() {
        val original = AppDataTransfer.Archive(emptyMap(), mapOf("dark_theme" to true), null, emptyList())
        val output = ByteArrayOutputStream()
        AppDataTransfer.write(original, output)
        assertEquals(original, AppDataTransfer.read(output.toByteArray().inputStream()))
    }

    @Test
    fun snapshotIncludesOnlyPresentPortableSettingsAndResolvedSelection() {
        val preferences = mutablePreferencesOf(
            booleanPreferencesKey("dark_theme") to false,
            booleanPreferencesKey("enable_kernel_module") to true,
            booleanPreferencesKey("quick_tile_added") to true,
            stringPreferencesKey("unrelated_private_value") to "fixture-secret",
            UserKnobs.LAST_USED_TUNNEL to "missing",
            VpsServerStore.SERVERS to VpsServerStore.encodeServers(listOf(server)),
        )
        val result = AppDataTransfer.snapshot(mapOf("one" to profile), preferences)
        assertEquals(mapOf("dark_theme" to false), result.settings)
        assertNull(result.lastUsedTunnel)
        assertEquals(listOf(server), result.servers)
    }

    @Test
    fun corruptKnownPreferenceTypeFailsWithoutExportingItsValue() {
        val preferences = mutablePreferencesOf(stringPreferencesKey("dark_theme") to "fixture-secret")
        val failure = assertThrows(AppDataTransfer.InvalidArchiveException::class.java) {
            AppDataTransfer.snapshot(emptyMap(), preferences)
        }
        assertFalse(failure.message.orEmpty().contains("fixture-secret"))
        assertNull(failure.cause)
    }

    @Test
    fun legacyStoredFleetRemainsTransferableAsUnknownHealth() {
        val legacy = org.json.JSONArray(VpsServerStore.encodeServers(listOf(server))).apply {
            getJSONObject(0).remove("running")
        }
        val preferences = mutablePreferencesOf(VpsServerStore.SERVERS to legacy.toString())
        val snapshot = AppDataTransfer.snapshot(emptyMap(), preferences)
        assertEquals(listOf(server), snapshot.servers)
        assertEquals(listOf(server), AppDataTransfer.validateMetadata(snapshot, preferences))
        val output = ByteArrayOutputStream()
        AppDataTransfer.write(snapshot, output)
        assertEquals(snapshot, AppDataTransfer.read(output.toByteArray().inputStream()))
    }

    @Test
    fun runtimeConsentAndUnknownSettingsAreRejectedBeforeMutation() {
        for (key in listOf("enable_kernel_module", "enabled_configs", "quick_tile_added", "updater_newer_version_consented", "unknown")) {
            assertThrows(AppDataTransfer.InvalidArchiveException::class.java) {
                AppDataTransfer.read(input(manifest(JSONObject().put(key, true))))
            }
        }
    }

    @Test
    fun knownSettingTypeAndUnresolvedSelectionTypesAreRejected() {
        for (value in listOf("true", 1, JSONObject.NULL)) {
            assertThrows(AppDataTransfer.InvalidArchiveException::class.java) {
                AppDataTransfer.read(input(manifest(JSONObject().put("dark_theme", value))))
            }
        }
        assertThrows(AppDataTransfer.InvalidArchiveException::class.java) {
            AppDataTransfer.read(input(manifest(JSONObject().put("last_used_tunnel", true))))
        }
    }

    @Test
    fun manifestMustHaveSupportedIdentityVersionAndCompleteShape() {
        val malformed = listOf(
            manifest().put("version", 2), manifest().put("version", "1"), manifest().put("format", "other"),
            manifest().apply { remove("servers") }, manifest().put("unknown", "fixture-secret"),
        )
        for (json in malformed) {
            val failure = assertThrows(AppDataTransfer.InvalidArchiveException::class.java) { AppDataTransfer.read(input(json)) }
            assertNull(failure.cause)
            assertFalse(failure.message.orEmpty().contains("fixture-secret"))
        }
    }

    @Test
    fun missingManifestAndUnexpectedOrTraversingEntriesAreRejected() {
        assertThrows(AppDataTransfer.InvalidArchiveException::class.java) {
            AppDataTransfer.read(zip(listOf("profiles/one.conf" to profile.toWgQuickString().toByteArray())).inputStream())
        }
        for (name in listOf("profiles/../one.conf", "../one.conf", "one.conf", "profiles/one.CONF", "profiles/")) {
            assertThrows(AppDataTransfer.InvalidArchiveException::class.java) {
                AppDataTransfer.read(input(manifest(), listOf(name to profile.toWgQuickString().toByteArray())))
            }
        }
    }

    @Test
    fun manifestAfterProfilesIsValid() {
        val result = AppDataTransfer.read(zip(listOf(
            "profiles/one.conf" to profile.toWgQuickString().toByteArray(),
            AppDataTransfer.MANIFEST to manifest(profiles = listOf("one")).toString().toByteArray(),
        )).inputStream())
        assertEquals(mapOf("one" to profile.toWgQuickString()), result.profiles.mapValues { it.value.toWgQuickString() })
    }

    @Test
    fun duplicateEntriesAreRejected() {
        val bytes = zip(listOf(
            AppDataTransfer.MANIFEST to manifest(profiles = listOf("one")).toString().toByteArray(),
            "profiles/one.conf" to profile.toWgQuickString().toByteArray(),
            "profiles/two.conf" to profile.toWgQuickString().toByteArray(),
        ))
        val modified = bytes.toString(Charsets.ISO_8859_1).replace("profiles/two.conf", "profiles/one.conf").toByteArray(Charsets.ISO_8859_1)
        assertThrows(AppDataTransfer.InvalidArchiveException::class.java) { AppDataTransfer.read(modified.inputStream()) }
    }

    @Test
    fun malformedUtf8ProfileAndSecretParserInputHaveFixedErrors() {
        val malformedComment = (profile.toWgQuickString() + "\n#").toByteArray() + byteArrayOf(0xc3.toByte(), 0x28)
        val inputs = listOf(malformedComment, "[Interface]\nPrivateKey = fixture-secret".toByteArray())
        for (bytes in inputs) {
            val failure = assertThrows(AppDataTransfer.InvalidArchiveException::class.java) {
                AppDataTransfer.read(input(manifest(profiles = listOf("one")), listOf("profiles/one.conf" to bytes)))
            }
            assertNull(failure.cause)
            assertFalse(failure.message.orEmpty().contains("fixture-secret"))
        }
    }

    @Test
    fun trailingManifestPayloadIsRejectedWithoutParserCause() {
        assertThrows(AppDataTransfer.InvalidArchiveException::class.java) {
            AppDataTransfer.read(zip(listOf(AppDataTransfer.MANIFEST to (manifest().toString() + " fixture-secret").toByteArray())).inputStream())
        }
    }

    @Test
    fun missingProfilesCannotSilentlyProduceAPartialTransfer() {
        val failure = assertThrows(AppDataTransfer.InvalidArchiveException::class.java) {
            AppDataTransfer.read(input(
                manifest(profiles = listOf("one", "two")),
                listOf("profiles/one.conf" to profile.toWgQuickString().toByteArray()),
            ))
        }
        assertNull(failure.cause)
    }

    @Test
    fun zipPrefixEndingBetweenCompleteEntriesCannotSilentlyOmitProfiles() {
        val complete = ByteArrayOutputStream().also {
            AppDataTransfer.write(archive().copy(
                profiles = linkedMapOf("one" to profile, "two" to TunnelTestEnvironment.profile(51821)),
                lastUsedTunnel = null,
            ), it)
        }.toByteArray()
        val header = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
        val offsets = (0..complete.size - header.size).filter { offset ->
            header.indices.all { complete[offset + it] == header[it] }
        }
        assertEquals(3, offsets.size)
        val prefix = complete.copyOf(offsets[2])
        assertThrows(AppDataTransfer.InvalidArchiveException::class.java) { AppDataTransfer.read(prefix.inputStream()) }
    }

    @Test
    fun undeclaredDuplicateAndWrongTypeProfileInventoryIsRejected() {
        val inventories = listOf(org.json.JSONArray(listOf("one", "one")), org.json.JSONArray(listOf(1)), org.json.JSONArray())
        for (inventory in inventories) {
            assertThrows(AppDataTransfer.InvalidArchiveException::class.java) {
                AppDataTransfer.read(input(
                    manifest().put("profiles", inventory),
                    listOf("profiles/one.conf" to profile.toWgQuickString().toByteArray()),
                ))
            }
        }
    }

    @Test
    fun compressedEntryAndTotalExpandedLimitsAreEnforced() {
        val validManifest = manifest().toString().toByteArray()
        val oversizedManifest = validManifest + ByteArray(1_048_577 - validManifest.size) { ' '.code.toByte() }
        assertThrows(AppDataTransfer.InvalidArchiveException::class.java) {
            AppDataTransfer.read(zip(listOf(AppDataTransfer.MANIFEST to oversizedManifest)).inputStream())
        }
        val profileBytes = (profile.toWgQuickString() + "\n#").toByteArray()
        val padded = profileBytes + ByteArray(1_048_576 - profileBytes.size) { 'x'.code.toByte() }
        val entries = (1..17).map { "profiles/p$it.conf" to padded }
        assertThrows(AppDataTransfer.InvalidArchiveException::class.java) {
            AppDataTransfer.read(input(manifest(profiles = (1..17).map { "p$it" }), entries))
        }
    }
}
