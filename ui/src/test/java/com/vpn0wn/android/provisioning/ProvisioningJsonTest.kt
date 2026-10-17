/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.provisioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class ProvisioningJsonTest {
    @Test fun revocationHistoryKeepsLegacyNamesAndExactOperationCorrelationWithoutPrivateBodies() {
        val operationId = "00112233-4455-4677-8899-aabbccddeeff"
        val key = "UFVCTElDLTIgICAgICAgICAgICAgICAgICAgICAgICA="
        val base = JSONObject().put("ok", true).put("interface", "wg0").put("running", true)
            .put("endpoint", "vpn.example").put("listenPort", "51820").put("cidr", "10.8.0.0/24")
            .put("dns", "1.1.1.1").put("peers", JSONArray())
        fun archive(name: String, operation: String? = null) = JSONObject().put("archiveName", name).put("name", "phone")
            .put("publicKey", key).put("operationId", operation ?: JSONObject.NULL)
        val history = JSONArray().put(archive("phone.conf")).put(archive("phone.conf.ABC123"))
            .put(archive("phone.conf.$operationId", operationId))
        assertEquals(null, ProvisioningJson.parseStatus(base.toString()).revokedArchives)
        assertEquals(null, ProvisioningJson.parseStatus(JSONObject(base.toString()).put("revokedArchives", JSONObject.NULL).toString()).revokedArchives)
        val parsed = ProvisioningJson.parseStatus(JSONObject(base.toString()).put("revokedArchives", history).toString()).revokedArchives!!
        assertEquals(3, parsed.size)
        assertEquals(operationId, parsed.last().operationId)
        assertEquals(listOf(key, key, key), parsed.map { it.publicKey })
        for (invalid in listOf(
            JSONArray().put(archive("phone.conf.$operationId")),
            JSONArray().put(archive("phone.conf.ABC123", operationId)),
            JSONArray().put(archive("phone.conf.$operationId", operationId.uppercase())),
            JSONArray().put(archive("other.conf.ABC123")),
            JSONArray().put(archive("phone.conf.ABC123").put("publicKey", "private-invalid-key")),
            JSONArray().put(history.getJSONObject(2)).put(history.getJSONObject(2)),
            JSONArray().put(archive("phone.conf")).put(archive("phone.conf")),
            JSONArray().apply { repeat(1025) { put(archive("phone.conf")) } },
            "partial-history",
        )) assertThrows(ProvisioningException::class.java) {
            ProvisioningJson.parseStatus(JSONObject(base.toString()).put("revokedArchives", invalid).toString())
        }
    }

    @Test fun peerInventoriesAreNullableCanonicalAndDistinct() {
        val key = "UFVCTElDLTIgICAgICAgICAgICAgICAgICAgICAgICA="
        val base = JSONObject().put("ok", true).put("interface", "wg0").put("running", true)
            .put("endpoint", "vpn.example").put("listenPort", "51820").put("cidr", "10.8.0.0/24")
            .put("dns", "1.1.1.1").put("peers", JSONArray())
        for (field in listOf("runtimePeerPublicKeys", "configuredPeerPublicKeys")) {
            fun inventory(status: VpsServerStatus) = if (field == "runtimePeerPublicKeys") status.runtimePeerPublicKeys else status.configuredPeerPublicKeys
            assertEquals(null, inventory(ProvisioningJson.parseStatus(base.toString())))
            assertEquals(null, inventory(ProvisioningJson.parseStatus(JSONObject(base.toString()).put(field, JSONObject.NULL).toString())))
            assertEquals(emptyList<String>(), inventory(ProvisioningJson.parseStatus(JSONObject(base.toString()).put(field, JSONArray()).toString())))
            assertEquals(listOf(key), inventory(ProvisioningJson.parseStatus(JSONObject(base.toString()).put(field, JSONArray(listOf(key))).toString())))
            for (invalid in listOf("not-an-array", JSONArray(listOf(true)), JSONArray(listOf("invalid")), JSONArray(listOf(key, key)))) {
                assertThrows(ProvisioningException::class.java) {
                    ProvisioningJson.parseStatus(JSONObject(base.toString()).put(field, invalid).toString())
                }
            }
        }
    }

    @Test
    fun parseDetectReadsServerCapabilities() {
        val result = ProvisioningJson.parseDetect(
            """
            {
              "ok": true,
              "os": "debian",
              "version": "12",
              "supported": true,
              "packageManager": "apt",
              "firewall": "nftables",
              "wireGuardInstalled": true,
              "configExists": false,
              "interface": "wg0",
              "endpoint": "203.0.113.10",
              "listenPort": "51820",
              "cidr": "10.8.0.0/24",
              "ipv4Forwarding": "enabled"
            }
            """.trimIndent(),
        )

        assertEquals("debian", result.os)
        assertEquals("12", result.version)
        assertTrue(result.supported)
        assertEquals("apt", result.packageManager)
        assertEquals("nftables", result.firewall)
        assertTrue(result.wireGuardInstalled)
        assertFalse(result.configExists)
        assertEquals("wg0", result.interfaceName)
        assertEquals("203.0.113.10", result.endpoint)
        assertEquals("51820", result.listenPort)
        assertEquals("10.8.0.0/24", result.cidr)
        assertEquals("enabled", result.ipv4Forwarding)
    }

    @Test
    fun parsePlanReadsStepsAndOverwriteFlag() {
        val result = ProvisioningJson.parsePlan(
            """
            {
              "ok": true,
              "supported": true,
              "endpoint": "203.0.113.10",
              "port": "51820",
              "cidr": "10.8.0.0/24",
              "dns": "1.1.1.1",
              "willOverwriteConfig": true,
              "steps": ["install wireguard-tools", "write wg0.conf"]
            }
            """.trimIndent(),
        )

        assertEquals("203.0.113.10", result.endpoint)
        assertEquals("51820", result.port)
        assertEquals("10.8.0.0/24", result.cidr)
        assertEquals("1.1.1.1", result.dns)
        assertTrue(result.willOverwriteConfig)
        assertEquals(listOf("install wireguard-tools", "write wg0.conf"), result.steps)
    }

    @Test
    fun parseInstallReadsCreatedServerConfig() {
        val result = ProvisioningJson.parseInstall(
            """
            {
              "ok": true,
              "interface": "wg0",
              "endpoint": "203.0.113.10",
              "port": "51820",
              "cidr": "10.8.0.0/24",
              "serverPublicKey": "server-key"
            }
            """.trimIndent(),
        )

        assertEquals("wg0", result.interfaceName)
        assertEquals("203.0.113.10", result.endpoint)
        assertEquals("51820", result.port)
        assertEquals("10.8.0.0/24", result.cidr)
        assertEquals("server-key", result.serverPublicKey)
    }

    @Test
    fun parseStatusReadsPeersAndCounters() {
        val result = ProvisioningJson.parseStatus(
            """
            {
              "ok": true,
              "interface": "wg0",
              "running": true,
              "endpoint": "203.0.113.10",
              "listenPort": "51820",
              "cidr": "10.8.0.0/24",
              "dns": "1.1.1.1",
              "peers": [
                {
                  "name": "phone",
                  "publicKey": "phone-key",
                  "allowedIps": "10.8.0.2/32",
                  "latestHandshake": "1710000000",
                  "rxBytes": "1024",
                  "txBytes": "2048"
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("wg0", result.interfaceName)
        assertEquals(true, result.running)
        assertEquals("203.0.113.10", result.endpoint)
        assertEquals("51820", result.listenPort)
        assertEquals("10.8.0.0/24", result.cidr)
        assertEquals("1.1.1.1", result.dns)
        assertEquals(1, result.peers.size)
        assertEquals(null, result.serverPublicKey)
        assertEquals("phone", result.peers.single().name)
        assertEquals("phone-key", result.peers.single().publicKey)
        assertEquals("10.8.0.2/32", result.peers.single().allowedIps)
        assertEquals(1710000000L, result.peers.single().latestHandshake)
        assertEquals(1024L, result.peers.single().rxBytes)
        assertEquals(2048L, result.peers.single().txBytes)
    }

    @Test
    fun statusServerIdentityAcceptsCanonicalKeysAndKeepsLegacyOrNullUnknown() {
        val json = JSONObject(fixture("status-running"))
        val key = "UFVCTElDLTIgICAgICAgICAgICAgICAgICAgICAgICA="
        assertEquals(key, ProvisioningJson.parseStatus(json.put("serverPublicKey", key).toString()).serverPublicKey)
        assertEquals(null, ProvisioningJson.parseStatus(json.put("serverPublicKey", JSONObject.NULL).toString()).serverPublicKey)
        json.remove("serverPublicKey")
        assertEquals(null, ProvisioningJson.parseStatus(json.toString()).serverPublicKey)
    }

    @Test
    fun statusServerIdentityRejectsMalformedSuppliedValuesWithoutPayloadDiagnostics() {
        for (value in listOf("", " ", "synthetic-shape-secret", "A".repeat(42) + "B=", true, 42, JSONObject())) {
            val json = JSONObject(fixture("status-running")).put("serverPublicKey", value)
            assertInvalid { ProvisioningJson.parseStatus(json.toString()) }
        }
    }

    @Test
    fun serverIdentityDriftIsAConflict() {
        val failure = assertThrows(ProvisioningException::class.java) {
            ProvisioningJson.parseAddPeer("""{"ok":false,"error":"server_identity_mismatch","message":"synthetic-shape-secret"}""")
        }
        assertEquals(ProvisioningFailureCategory.CONFLICT, failure.category)
        assertEquals("server_identity_mismatch", failure.remoteCode)
        assertFalse(failure.stackTraceToString().contains("synthetic-shape-secret"))
    }

    @Test
    fun parseAddPeerReadsConfigPayload() {
        val result = ProvisioningJson.parseAddPeer(
            """
            {
              "ok": true,
              "name": "phone",
              "address": "10.8.0.2/32",
              "publicKey": "phone-key",
              "config": "[Interface]\nAddress = 10.8.0.2/32"
            }
            """.trimIndent(),
        )

        assertEquals("phone", result.name)
        assertEquals("10.8.0.2/32", result.address)
        assertEquals("phone-key", result.publicKey)
        assertTrue(result.config.contains("Address = 10.8.0.2/32"))
    }

    @Test
    fun parsePeerMutationReadsRemoveAndRevokeFlags() {
        val result = ProvisioningJson.parsePeerMutation(
            """
            {
              "ok": true,
              "name": "phone",
              "publicKey": "UFVCTElDLTIgICAgICAgICAgICAgICAgICAgICAgICA=",
              "removed": true,
              "revoked": false,
              "archive": "/etc/wireguard/revoked/phone.conf"
            }
            """.trimIndent(),
        )

        assertEquals("phone", result.name)
        assertEquals("UFVCTElDLTIgICAgICAgICAgICAgICAgICAgICAgICA=", result.publicKey)
        assertTrue(result.removed)
        assertFalse(result.revoked)
        assertEquals("/etc/wireguard/revoked/phone.conf", result.archive)
    }

    @Test
    fun parseBackupReadsBackupMetadata() {
        val result = ProvisioningJson.parseBackup(fixture("backup"))

        assertEquals("wg0", result.interfaceName)
        assertTrue(result.config.startsWith("# TUNNL_MANAGED 1\n"))
        assertEquals(1, result.revokedCount)
        assertEquals(1, result.snapshotVersion)
        assertEquals("2026-10-16T00:00:00Z", result.capturedAtUtc)
        assertEquals("tablet.conf.ABC123", result.revoked.single().archiveName)
        assertTrue(result.revoked.single().config.contains("# BEGIN_PEER tablet"))
    }

    @Test
    fun backupPreservesExactTextAndDistinctHistoricalRevocations() {
        val json = JSONObject(fixture("backup"))
        val config = json.getString("config") + "# UTF-8: café\u0001\t\r\n\n"
        val entry = json.getJSONArray("revoked").getJSONObject(0)
        val archived = entry.getString("config") + "\r\n\n"
        entry.put("config", archived)
        json.put("config", config).put("revokedCount", "3").getJSONArray("revoked")
            .put(JSONObject(entry.toString()).put("archiveName", "tablet.conf"))
            .put(JSONObject(entry.toString()).put("archiveName", "tablet.conf.DEF456"))

        val result = ProvisioningJson.parseBackup(json.toString())
        assertEquals(config, result.config)
        assertEquals(3, result.revokedCount)
        assertTrue(result.revoked.all { it.config == archived })
        assertEquals(3, result.revoked.map { it.archiveName }.toSet().size)
        assertEquals("VpsBackupResult(redacted)", result.toString())
        assertTrue(result.revoked.all { it.toString() == "VpsRevokedBackup(redacted)" })
    }

    @Test
    fun backupAcceptsEmptyRevokedSnapshotWithoutInventingEntries() {
        val json = JSONObject(fixture("backup")).put("revokedCount", "0").put("revoked", JSONArray())
        val result = ProvisioningJson.parseBackup(json.toString())
        assertEquals(0, result.revokedCount)
        assertTrue(result.revoked.isEmpty())
    }

    @Test
    fun backupRejectsMissingAndUnsupportedVersions() {
        val original = JSONObject(fixture("backup"))
        assertInvalid { ProvisioningJson.parseBackup(JSONObject(original.toString()).apply { remove("snapshotVersion") }.toString()) }
        for (version in listOf<Any>(0, 2, "1", true, JSONObject.NULL)) {
            assertInvalid { ProvisioningJson.parseBackup(JSONObject(original.toString()).put("snapshotVersion", version).toString()) }
        }
        assertInvalid { ProvisioningJson.parseBackup(fixture("backup").replace("\"snapshotVersion\":1", "\"snapshotVersion\":1.0")) }
    }

    @Test
    fun backupRejectsInvalidDatesAndInterfaces() {
        for (date in listOf("2026-02-30T00:00:00Z", "2026-10-16T24:00:00Z", "2026-10-16T00:00:60Z", "2026-10-16", "2026-10-16T00:00:00+00:00")) {
            assertInvalid { ProvisioningJson.parseBackup(JSONObject(fixture("backup")).put("capturedAtUtc", date).toString()) }
        }
        for (name in listOf("", "../wg0", "wg0\n", "a".repeat(16))) {
            assertInvalid { ProvisioningJson.parseBackup(JSONObject(fixture("backup")).put("interface", name).toString()) }
        }
    }

    @Test
    fun backupRejectsIncompleteAmbiguousAndUnsafeRevokedArrays() {
        for (count in listOf("0", "2", "-1", "1025", "9223372036854775808")) {
            assertInvalid { ProvisioningJson.parseBackup(JSONObject(fixture("backup")).put("revokedCount", count).toString()) }
        }
        for (entry in listOf<Any>("synthetic-shape-secret", JSONObject.NULL, JSONObject().put("archiveName", "alice.conf"))) {
            assertInvalid { ProvisioningJson.parseBackup(JSONObject(fixture("backup")).put("revoked", JSONArray().put(entry)).toString()) }
        }
        for (name in listOf("../alice.conf", "/alice.conf", "revoked/alice.conf", ".hidden", "alice.conf.extra-suffix")) {
            val json = JSONObject(fixture("backup"))
            json.getJSONArray("revoked").getJSONObject(0).put("archiveName", name)
            assertInvalid { ProvisioningJson.parseBackup(json.toString()) }
        }
        val duplicate = JSONObject(fixture("backup")).put("revokedCount", "2")
        val entries = duplicate.getJSONArray("revoked")
        entries.put(JSONObject(entries.getJSONObject(0).toString()))
        assertInvalid { ProvisioningJson.parseBackup(duplicate.toString()) }
    }

    @Test
    fun backupRejectsUnsupportedTextAndOversizedBodies() {
        val json = JSONObject(fixture("backup"))
        for (config in listOf("", " ", "[Interface]", "# TUNNL_MANAGED 1\n\u0000", "# TUNNL_MANAGED 1\n\uD800", "# TUNNL_MANAGED 1\n" + "é".repeat(VpsBackupResult.MAX_CONFIG_BYTES / 2))) {
            assertInvalid { ProvisioningJson.parseBackup(JSONObject(json.toString()).put("config", config).toString()) }
        }
        for (config in listOf("", "\u0000", "\uD800", "é".repeat(VpsBackupResult.MAX_REVOKED_BYTES / 2 + 1))) {
            val malformed = JSONObject(json.toString())
            malformed.getJSONArray("revoked").getJSONObject(0).put("config", config)
            assertInvalid { ProvisioningJson.parseBackup(malformed.toString()) }
        }
        assertInvalid { ProvisioningJson.parseBackup(" ".repeat(VpsBackupResult.MAX_RESPONSE_BYTES + 1)) }
        assertInvalid { ProvisioningJson.parseBackup(json.toString() + " synthetic-shape-secret") }
        assertInvalid { ProvisioningJson.parseBackup(json.toString() + '\u0000') }
    }

    @Test
    fun backupAcceptsExactBodyBoundsAndRejectsExcessiveEntryCounts() {
        val json = JSONObject(fixture("backup"))
        val marker = "# TUNNL_MANAGED 1\n"
        json.put("config", marker + "x".repeat(VpsBackupResult.MAX_CONFIG_BYTES - marker.length))
        json.getJSONArray("revoked").getJSONObject(0).put("config", "x".repeat(VpsBackupResult.MAX_REVOKED_BYTES))
        assertEquals(VpsBackupResult.MAX_CONFIG_BYTES, ProvisioningJson.parseBackup(json.toString()).config.length)
        val entries = JSONArray()
        repeat(VpsBackupResult.MAX_REVOKED_FILES + 1) { index ->
            entries.put(JSONObject().put("archiveName", "peer$index.conf").put("config", "[Peer]"))
        }
        val oversized = JSONObject(fixture("backup")).put("revokedCount", entries.length().toString()).put("revoked", entries)
        assertInvalid { ProvisioningJson.parseBackup(oversized.toString()) }
    }

    @Test
    fun parseUninstallReadsResult() {
        val result = ProvisioningJson.parseUninstall(
            """
            {
              "ok": true,
              "uninstalled": true,
              "backup": "/tmp/wg0.conf",
              "packagesPurged": false
            }
            """.trimIndent(),
        )

        assertTrue(result.uninstalled)
        assertEquals("/tmp/wg0.conf", result.backup)
        assertFalse(result.packagesPurged)
    }

    @Test
    fun parseInvalidJsonThrowsProvisioningException() {
        val exception = assertThrows(ProvisioningException::class.java) {
            ProvisioningJson.parseDetect("[Interface]\nPrivateKey = secret")
        }

        assertEquals("Remote command returned invalid JSON", exception.message)
        assertFalse(exception.message.orEmpty().contains("secret"))
    }

    @Test
    fun parseErrorPayloadThrowsProvisioningException() {
        val exception = assertThrows(ProvisioningException::class.java) {
            ProvisioningJson.parseDetect(
                """{"ok":false,"error":"unsupported_os","message":"Debian 10"}""",
            )
        }

        assertEquals("Server operating system is unsupported", exception.message)
    }

    @Test
    fun nestedMalformedDataFailsWithoutRetainingPayload() {
        val secret = "synthetic-nested-private-key"
        for (peers in listOf("[\"$secret\"]", "{}", "[{\"name\":\"$secret\"}]")) {
            val failure = assertThrows(ProvisioningException::class.java) {
                ProvisioningJson.parseStatus("""{"ok":true,"interface":"wg0","running":true,"endpoint":"vpn.example","listenPort":"51820","cidr":"10.8.0.0/24","dns":"1.1.1.1","peers":$peers}""")
            }
            assertFalse(failure.stackTraceToString().contains(secret))
            assertEquals(null, failure.cause)
        }
    }

    @Test
    fun remoteErrorTextIsNeverDiagnosticText() {
        val secret = "synthetic-remote-private-key"
        for (error in listOf("\"unsupported_os\"", "\"$secret\"", "{}", "null")) {
            val failure = assertThrows(ProvisioningException::class.java) {
                ProvisioningJson.parseDetect("""{"ok":false,"error":$error,"message":"$secret"}""")
            }
            assertFalse(failure.stackTraceToString().contains(secret))
            assertEquals(null, failure.cause)
        }
    }

    @Test
    fun actualScriptResponsesParseAcrossCommandSurface() {
        assertTrue(ProvisioningJson.parsePlan(fixture("plan")).willOverwriteConfig)
        assertEquals("wg0", ProvisioningJson.parseInstall(fixture("install")).interfaceName)
        val profile = ProvisioningJson.parseAddPeer(fixture("add-peer")).config
        assertTrue(profile.contains("PrivateKey"))
        val config = com.vpn0wn.config.Config.parse(profile.byteInputStream())
        assertEquals(1, config.peers.size)
        val status = ProvisioningJson.parseStatus(fixture("status-running"))
        assertEquals(true, status.running)
        assertEquals("UFVCTElDLTEgICAgICAgICAgICAgICAgICAgICAgICA=", status.serverPublicKey)
        assertEquals(1024L, status.peers.single().rxBytes)
        val stopped = ProvisioningJson.parseStatus(fixture("status-stopped"))
        assertEquals(false, stopped.running)
        assertEquals("UFVCTElDLW9sZCAgICAgICAgICAgICAgICAgICAgICA=", stopped.serverPublicKey)
        val unknown = ProvisioningJson.parseStatus(fixture("status-unknown"))
        assertEquals(null, unknown.running)
        assertEquals(null, unknown.serverPublicKey)
        assertEquals(null, ProvisioningJson.parseStatus(fixture("status-unknown")).peers.single().rxBytes)
        val runningUnknown = ProvisioningJson.parseStatus(fixture("status-running-unknown"))
        assertEquals(true, runningUnknown.running)
        assertEquals(null, runningUnknown.peers.single().latestHandshake)
        assertEquals(null, runningUnknown.peers.single().rxBytes)
        val removed = ProvisioningJson.parsePeerMutation(fixture("remove-peer"))
        assertEquals("phone", removed.name)
        assertTrue(removed.removed)
        assertFalse(removed.revoked)
        val revoked = ProvisioningJson.parsePeerMutation(fixture("revoke-peer"))
        assertEquals("tablet", revoked.name)
        assertTrue(revoked.removed)
        assertTrue(revoked.revoked)
        assertTrue(revoked.archive.endsWith("/tablet.conf.ABC123"))
        assertEquals("tablet.conf.ABC123", revoked.archiveName)
        assertEquals(null, revoked.operationId)
        assertEquals(1, ProvisioningJson.parseBackup(fixture("backup")).revokedCount)
        assertTrue(ProvisioningJson.parseUninstall(fixture("uninstall")).uninstalled)
    }

    @Test
    fun missingAndWrongTypesAreNotCoercedToSuccess() {
        val controls = listOf<Pair<String, (String) -> Any>>(
            "plan" to ProvisioningJson::parsePlan,
            "install" to ProvisioningJson::parseInstall,
            "status-running" to ProvisioningJson::parseStatus,
            "add-peer" to ProvisioningJson::parseAddPeer,
            "remove-peer" to ProvisioningJson::parsePeerMutation,
            "backup" to ProvisioningJson::parseBackup,
            "uninstall" to ProvisioningJson::parseUninstall,
        )
        for ((name, parser) in controls) {
            val original = JSONObject(fixture(name))
            for (key in original.keys().asSequence().toList().filter { it !in listOf("publicKey", "cleanupPath") }) {
                val malformed = JSONObject(original.toString()).put(key, JSONObject().put("secret", "synthetic-shape-secret"))
                assertInvalid { parser(malformed.toString()) }
            }
            val missingOk = JSONObject(original.toString()).apply { remove("ok") }
            assertInvalid { parser(missingOk.toString()) }
            assertInvalid { parser(JSONObject(original.toString()).put("ok", "true").toString()) }
        }
        assertInvalid { ProvisioningJson.parsePeerMutation("""{"ok":true,"removed":"phone"}""") }
        assertInvalid { ProvisioningJson.parsePlan(JSONObject(fixture("plan")).put("steps", listOf(JSONObject())).toString()) }
    }

    @Test
    fun countersRejectMalformedNegativeAndOverflowWithoutLosingValidZero() {
        for (value in listOf("-1", "1.5", "9223372036854775808", "synthetic-shape-secret")) {
            val json = JSONObject(fixture("status-running"))
            json.getJSONArray("peers").getJSONObject(0).put("rxBytes", value)
            assertInvalid { ProvisioningJson.parseStatus(json.toString()) }
        }
        val json = JSONObject(fixture("status-running"))
        json.getJSONArray("peers").getJSONObject(0).put("rxBytes", "0").put("txBytes", Long.MAX_VALUE.toString())
        val peer = ProvisioningJson.parseStatus(json.toString()).peers.single()
        assertEquals(0L, peer.rxBytes)
        assertEquals(Long.MAX_VALUE, peer.txBytes)
    }

    @Test
    fun remoteFailuresHaveSafeCategoriesAndRecovery() {
        val known = assertThrows(ProvisioningException::class.java) { ProvisioningJson.parseDetect(fixture("error")) }
        assertEquals(ProvisioningFailureCategory.CONFLICT, known.category)
        assertEquals("peer_not_found", known.remoteCode)
        assertEquals(ProvisioningRecovery.UNCHANGED, known.recovery)
        for (recovery in listOf("unchanged", "restored", "incomplete", "synthetic-shape-secret")) {
            val payload = """{"ok":false,"error":"apply_failed","message":"synthetic-shape-secret","recovery":"$recovery","recoveryPath":"synthetic-shape-secret","artifact":"synthetic-shape-secret"}"""
            val failure = assertThrows(ProvisioningException::class.java) { ProvisioningJson.parseDetect(payload) }
            assertEquals(ProvisioningFailureCategory.MUTATION_FAILED, failure.category)
            assertFalse(failure.stackTraceToString().contains("synthetic-shape-secret"))
        }
        val unknown = assertThrows(ProvisioningException::class.java) {
            ProvisioningJson.parseDetect("""{"ok":false,"error":"synthetic-shape-secret","message":"synthetic-shape-secret"}""")
        }
        assertEquals(ProvisioningFailureCategory.REMOTE_FAILURE, unknown.category)
        assertEquals(null, unknown.remoteCode)
        for (payload in listOf("""{"ok":false}""", """{"ok":false,"error":"unsupported_os"}""", """{"ok":false,"error":[],"message":{}}""")) {
            assertInvalid { ProvisioningJson.parseDetect(payload) }
        }
    }

    @Test
    fun failedScriptExitPreservesStructuredCategoryAndNeverAcceptsSuccess() {
        val failure = assertThrows(ProvisioningException::class.java) {
            ProvisioningJson.requireScriptSuccess(ProvisioningCommandOutput(74, fixture("error"), "synthetic-shape-secret"))
        }
        assertEquals("peer_not_found", failure.remoteCode)
        assertEquals(null, failure.output)
        assertFalse(failure.stackTraceToString().contains("synthetic-shape-secret"))
        val successWithFailedExit = assertThrows(ProvisioningException::class.java) {
            ProvisioningJson.requireScriptSuccess(ProvisioningCommandOutput(74, fixture("install"), ""))
        }
        assertEquals(ProvisioningFailureCategory.COMMAND_FAILED, successWithFailedExit.category)
    }

    @Test
    fun cleanupResidueRemainsAvailableWithoutChangingSuccess() {
        val json = JSONObject(fixture("remove-peer")).put("cleanupPath", "/fixture/private-residue")
        val result = ProvisioningJson.parsePeerMutation(json.toString())
        assertTrue(result.removed)
        assertEquals("/fixture/private-residue", result.cleanupPath)
    }

    @Test
    fun revocationRequiresArchiveButRemovalCanOmitIt() {
        val missing = JSONObject(fixture("revoke-peer")).apply { remove("archive") }
        assertInvalid { ProvisioningJson.parsePeerMutation(missing.toString()) }
        for (archive in listOf("", " ", JSONObject().put("secret", "synthetic-shape-secret"))) {
            val malformed = JSONObject(fixture("revoke-peer")).put("archive", archive)
            assertInvalid { ProvisioningJson.parsePeerMutation(malformed.toString()) }
        }
        assertEquals("", ProvisioningJson.parsePeerMutation(fixture("remove-peer")).archive)
        assertTrue(ProvisioningJson.parsePeerMutation(fixture("revoke-peer")).archive.isNotBlank())
    }

    @Test
    fun mutationRequiresCanonicalPublicKeyAndNonblankNameWithoutPayloadDiagnostics() {
        for (fixtureName in listOf("remove-peer", "revoke-peer")) {
            for (field in listOf("name", "publicKey")) {
                assertInvalid { ProvisioningJson.parsePeerMutation(JSONObject(fixture(fixtureName)).apply { remove(field) }.toString()) }
                for (value in listOf("", " ", JSONObject.NULL, JSONObject().put("secret", "synthetic-shape-secret"))) {
                    assertInvalid { ProvisioningJson.parsePeerMutation(JSONObject(fixture(fixtureName)).put(field, value).toString()) }
                }
            }
            for (key in listOf("synthetic-shape-secret", "A".repeat(42) + "B=", "A".repeat(43), "A".repeat(43) + "==")) {
                assertInvalid { ProvisioningJson.parsePeerMutation(JSONObject(fixture(fixtureName)).put("publicKey", key).toString()) }
            }
        }
    }

    @Test
    fun peerIdentityGuardFailuresUseExistingSafeCategories() {
        val categories = listOf(
            "invalid_public_key" to ProvisioningFailureCategory.INVALID_INPUT,
            "peer_identity_mismatch" to ProvisioningFailureCategory.CONFLICT,
        )
        for ((code, category) in categories) {
            val failure = runCatching {
                ProvisioningJson.parsePeerMutation("""{"ok":false,"error":"$code","message":"synthetic-shape-secret"}""")
            }.exceptionOrNull() as ProvisioningException
            assertEquals(category, failure.category)
            assertEquals(code, failure.remoteCode)
            assertFalse(failure.stackTraceToString().contains("synthetic-shape-secret"))
        }
    }

    @Test
    fun statusRejectsMissingOrDuplicatePeerIdentityWithoutPayloadDiagnostics() {
        for (field in listOf("name", "publicKey")) {
            val blank = JSONObject(fixture("status-running"))
            blank.getJSONArray("peers").getJSONObject(0).put(field, " ")
            assertInvalid { ProvisioningJson.parseStatus(blank.toString()) }
            val duplicate = JSONObject(fixture("status-running"))
            val peers = duplicate.getJSONArray("peers")
            val first = peers.getJSONObject(0)
            val second = JSONObject(first.toString()).put(if (field == "name") "publicKey" else "name", "synthetic-shape-secret")
            duplicate.put("peers", org.json.JSONArray().put(first).put(second))
            assertInvalid { ProvisioningJson.parseStatus(duplicate.toString()) }
        }
    }

    private fun assertInvalid(parse: () -> Any) {
        val failure = assertThrows(ProvisioningException::class.java) { parse() }
        assertEquals(ProvisioningFailureCategory.INVALID_RESPONSE, failure.category)
        assertEquals(null, failure.cause)
        assertEquals(null, failure.output)
        assertFalse(failure.stackTraceToString().contains("synthetic-shape-secret"))
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/provisioning/$name.json")).bufferedReader().use { it.readText() }
}
