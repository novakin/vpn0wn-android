/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class VpsRestoreCheckPlanTest {
    @Test fun checkPlanContainsOnlyTypedPublicMetadata() {
        val response = response()
        response.getJSONObject("plan").put("config", "synthetic-private-config").put("presharedKey", "synthetic-private-psk")
        val plan = ProvisioningJson.parseRestoreCheck(response.toString())
        assertEquals("wg0", plan.interfaceName)
        assertEquals("destination.example", plan.endpoint)
        assertEquals("51821", plan.port)
        assertEquals("10.8.0.0/24", plan.cidr)
        assertEquals("1.1.1.1", plan.dns)
        assertEquals(PUBLIC_KEY, plan.serverPublicKey)
        assertEquals(1, plan.peerCount)
        assertEquals(1, plan.revokedCount)
        assertFalse(plan.toString().contains("synthetic-private"))
        for ((peers, revoked) in listOf(0 to 0, 253 to 1024)) {
            response.getJSONObject("plan").put("peerCount", peers).put("revokedCount", revoked)
            val boundary = ProvisioningJson.parseRestoreCheck(response.toString())
            assertEquals(peers, boundary.peerCount)
            assertEquals(revoked, boundary.revokedCount)
        }
    }

    @Test fun checkRequiresSuccessfulExplicitCheckEnvelopeAndNestedPlan() {
        for ((field, value) in listOf(
            "ok" to false, "ok" to "true", "ok" to JSONObject.NULL,
            "checkOnly" to false, "checkOnly" to "true", "checkOnly" to JSONObject.NULL,
            "plan" to JSONArray(), "plan" to "synthetic-private-config", "plan" to JSONObject.NULL,
        )) {
            val input = response().put(field, value)
            assertInvalid(input.toString())
        }
        for (field in listOf("ok", "checkOnly", "plan")) {
            val input = response().apply { remove(field) }
            assertInvalid(input.toString())
        }
        for (input in listOf("[]", response().toString() + " trailing", "null", "{")) assertInvalid(input)
    }

    @Test fun everyPlanFieldRequiresExactTypeAndCountsRemainBounded() {
        val fields = listOf("interface", "endpoint", "port", "cidr", "dns", "serverPublicKey", "peerCount", "revokedCount")
        for (field in fields) {
            val input = response()
            input.getJSONObject("plan").remove(field)
            assertInvalid(input.toString())
            for (value in listOf(JSONObject.NULL, true, JSONArray(), JSONObject())) {
                input.getJSONObject("plan").put(field, value)
                assertInvalid(input.toString())
            }
        }
        for (field in fields.take(6)) {
            assertInvalid(response().apply { getJSONObject("plan").put(field, 1) }.toString())
        }
        for ((field, maximum) in listOf("peerCount" to 253, "revokedCount" to 1024)) {
            for (value in listOf(-1, maximum + 1, "1", 1.5, Long.MAX_VALUE)) {
                assertInvalid(response().apply { getJSONObject("plan").put(field, value) }.toString())
            }
        }
    }

    private fun assertInvalid(input: String) {
        val failure = assertThrows(ProvisioningException::class.java) { ProvisioningJson.parseRestoreCheck(input) }
        assertEquals(ProvisioningFailureCategory.INVALID_RESPONSE, failure.category)
        assertFalse(failure.message.orEmpty().contains("synthetic-private"))
    }

    private fun response(): JSONObject = JSONObject().put("ok", true).put("checkOnly", true).put("plan",
        JSONObject().put("interface", "wg0").put("endpoint", "destination.example").put("port", "51821")
            .put("cidr", "10.8.0.0/24").put("dns", "1.1.1.1").put("serverPublicKey", PUBLIC_KEY)
            .put("peerCount", 1).put("revokedCount", 1),
    )

    private companion object { const val PUBLIC_KEY = "UFVCTElDLTIgICAgICAgICAgICAgICAgICAgICAgICA=" }
}
