/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import com.vpn0wn.crypto.Key
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VpsRunningRestoreCheckTest {
    @Test fun runningReviewRetainsOnlyBoundedPublicMetadataAndAllowsReusedNames() {
        val input = VpsRunningRestoreFixture.response()
        input.getJSONObject("plan").getJSONObject("replacement").put("config", "synthetic-private-config")
        val result = ProvisioningJson.parseRunningRestoreCheck(input.toString())
        assertEquals(VpsRunningRestoreFixture.review, result.replacement)
        assertEquals(VpsFreshRestoreWorkflowTest.PLAN, result.plan)
        assertFalse(result.toString().contains("synthetic-private"))
        assertEquals(2, result.replacement.users.count { it.name == "phone" })
        assertFalse(result.replacement.serviceEnabled)
    }

    @Test fun freshAndRunningChecksCannotExchangeReviewModes() {
        val running = VpsRunningRestoreFixture.response()
        assertThrows(ProvisioningException::class.java) { ProvisioningJson.parseRestoreCheck(running.toString()) }
        running.getJSONObject("plan").remove("replacement")
        assertInvalid(running)
        assertEquals(VpsFreshRestoreWorkflowTest.PLAN, ProvisioningJson.parseRestoreCheck(running.toString()))
    }

    @Test fun requiredReviewFieldsRejectWrongTypesAndMissingValues() {
        for (field in VpsRunningRestoreFixture.replacement().keys().asSequence().toList()) {
            assertInvalid(VpsRunningRestoreFixture.response().apply { replacement().remove(field) })
            val wrongTypes = when (VpsRunningRestoreFixture.replacement().get(field)) {
                is String -> listOf(1, true, JSONArray())
                is Boolean -> listOf("true", 1, JSONArray())
                is Int -> listOf("1", true, 1.5)
                else -> listOf("wrong", 1, true)
            }
            for (value in listOf(JSONObject.NULL, JSONObject()) + wrongTypes) {
                assertInvalid(VpsRunningRestoreFixture.response().apply { replacement().put(field, value) })
            }
        }
        for ((field, value) in listOf("reviewSha256" to "A".repeat(64), "running" to false,
            "port" to "051820", "port" to "65536", "cidr" to "10.9.0.1/24", "endpoint" to "bad\nvalue",
            "dns" to "", "endpoint" to "x".repeat(1025), "dns" to "\uD800", "reviewSha256" to "a".repeat(63),
            "peerCount" to -1, "peerCount" to 254, "revokedCount" to -1, "revokedCount" to 1025)) {
            assertInvalid(VpsRunningRestoreFixture.response().apply { replacement().put(field, value) })
        }
        assertInvalid(VpsRunningRestoreFixture.response().put("ignored", "x".repeat(262_144)))
    }

    @Test fun identitiesChangesDuplicatesAndArrayBoundsFailClosed() {
        for ((field, value) in listOf("name" to "bad name", "publicKey" to "invalid", "change" to "unchanged")) {
            assertInvalid(VpsRunningRestoreFixture.response().apply { replacement().getJSONArray("users").getJSONObject(0).put(field, value) })
        }
        assertInvalid(VpsRunningRestoreFixture.response().apply {
            val rows = replacement().getJSONArray("users")
            rows.put(JSONObject(rows.getJSONObject(0).toString()))
        })
        for ((field, value) in listOf("archiveName" to "../foreign", "change" to "restored")) {
            assertInvalid(VpsRunningRestoreFixture.response().apply {
                replacement().getJSONArray("discardedRevoked").getJSONObject(0).put(field, value)
            })
        }
        assertInvalid(VpsRunningRestoreFixture.response().apply {
            val rows = replacement().getJSONArray("discardedRevoked")
            rows.put(JSONObject(rows.getJSONObject(0).toString()))
        })
        for ((field, count) in listOf("users" to 507, "discardedRevoked" to 1025)) {
            assertInvalid(VpsRunningRestoreFixture.response().apply {
                replacement().put(field, JSONArray().apply { repeat(count) { put(JSONObject()) } })
            })
        }
    }

    @Test fun maximumUniqueListsAndZeroCountsRemainSupported() {
        val input = VpsRunningRestoreFixture.response().apply {
            replacement().put("peerCount", 0).put("revokedCount", 0).put("serviceEnabled", true)
                .put("users", JSONArray().apply { repeat(506) { index ->
                    val bytes = ByteArray(32)
                    bytes[0] = index.toByte()
                    bytes[1] = (index shr 8).toByte()
                    put(JSONObject().put("name", "phone").put("publicKey", Key.fromBytes(bytes).toBase64()).put("change", "removed"))
                } }).put("discardedRevoked", JSONArray().apply { repeat(1024) { index ->
                    put(JSONObject().put("archiveName", "retired_$index.conf").put("change", "removed"))
                } })
        }
        val result = ProvisioningJson.parseRunningRestoreCheck(input.toString())
        assertEquals(506, result.replacement.users.size)
        assertEquals(1024, result.replacement.discardedRevoked.size)
        assertEquals(0, result.replacement.peerCount)
    }

    @Test fun changedTargetUsesSafeConflictCategory() {
        val failure = assertThrows(ProvisioningException::class.java) {
            ProvisioningJson.parseRestore("""{"ok":false,"error":"restore_target_changed","message":"synthetic-private-config"}""")
        }
        assertEquals(ProvisioningFailureCategory.CONFLICT, failure.category)
        assertEquals("restore_target_changed", failure.remoteCode)
        assertFalse(failure.message.orEmpty().contains("synthetic-private"))
    }

    private fun JSONObject.replacement() = getJSONObject("plan").getJSONObject("replacement")

    private fun assertInvalid(input: JSONObject) {
        val failure = assertThrows(ProvisioningException::class.java) { ProvisioningJson.parseRunningRestoreCheck(input.toString()) }
        assertEquals(ProvisioningFailureCategory.INVALID_RESPONSE, failure.category)
    }
}

internal object VpsRunningRestoreFixture {
    const val DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    val review = VpsRestoreReplacement(DIGEST, "current.example", "51820", "10.9.0.0/24", "9.9.9.9", 2, 2, false,
        listOf(VpsRestoreUserChange("phone", Key.fromBytes(ByteArray(32) { 2 }).toBase64(), "restored"),
            VpsRestoreUserChange("phone", Key.fromBytes(ByteArray(32) { 3 }).toBase64(), "removed")),
        listOf(VpsRestoreHistoryChange("retired.conf.ABC123", "removed"), VpsRestoreHistoryChange("phone.conf", "replaced")))

    fun replacement(): JSONObject = JSONObject().put("reviewSha256", review.reviewSha256).put("endpoint", review.endpoint)
        .put("port", review.port).put("cidr", review.cidr).put("dns", review.dns).put("peerCount", review.peerCount)
        .put("revokedCount", review.revokedCount).put("running", true).put("serviceEnabled", review.serviceEnabled)
        .put("users", JSONArray().apply { review.users.forEach { put(JSONObject().put("name", it.name).put("publicKey", it.publicKey).put("change", it.change)) } })
        .put("discardedRevoked", JSONArray().apply { review.discardedRevoked.forEach { put(JSONObject().put("archiveName", it.archiveName).put("change", it.change)) } })

    fun response(): JSONObject {
        val plan = VpsFreshRestoreWorkflowTest.PLAN
        return JSONObject().put("ok", true).put("checkOnly", true).put("plan", JSONObject()
            .put("interface", plan.interfaceName).put("endpoint", plan.endpoint).put("port", plan.port).put("cidr", plan.cidr)
            .put("dns", plan.dns).put("serverPublicKey", plan.serverPublicKey).put("peerCount", plan.peerCount)
            .put("revokedCount", plan.revokedCount).put("replacement", replacement()))
    }
}
