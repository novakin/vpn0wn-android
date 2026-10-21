/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VpsRestoreVerificationTest {
    @Test fun verificationContainsOnlyTypedPublicObservations() {
        val response = response().put("config", "synthetic-private-config").put("presharedKey", "synthetic-private-psk")
            .put("cleanupPath", "synthetic-private-path")
        val result = ProvisioningJson.parseRestoreVerification(response.toString())
        assertEquals("wg0", result.interfaceName)
        assertEquals("destination.example", result.endpoint)
        assertEquals("51821", result.port)
        assertEquals("10.8.0.0/24", result.cidr)
        assertEquals("1.1.1.1", result.dns)
        assertEquals(PUBLIC_KEY, result.serverPublicKey)
        assertEquals(1, result.peerCount)
        assertEquals(1, result.revokedCount)
        assertTrue(result.running)
        assertTrue(result.serviceEnabled)
        assertFalse(result.toString().contains("synthetic-private"))
        for ((peers, revoked) in listOf(0 to 0, 253 to 1024)) {
            val boundary = ProvisioningJson.parseRestoreVerification(response().put("peerCount", peers).put("revokedCount", revoked).toString())
            assertEquals(peers, boundary.peerCount)
            assertEquals(revoked, boundary.revokedCount)
        }
    }

    @Test fun verificationRequiresExplicitSuccessAndPositiveObservedRuntimeState() {
        for (field in listOf("ok", "verifyOnly", "verified", "running", "serviceEnabled")) {
            for (value in listOf(false, "true", 1, JSONObject.NULL, JSONArray(), JSONObject())) {
                assertInvalid(response().put(field, value).toString())
            }
            assertInvalid(response().apply { remove(field) }.toString())
        }
        val confirmed = response().apply { remove("verifyOnly"); remove("verified") }.put("cleanupPath", "")
        assertInvalid(confirmed.toString())
        val checked = JSONObject().put("ok", true).put("checkOnly", true).put("plan", response())
        assertInvalid(checked.toString())
        for (input in listOf("[]", response().toString() + " trailing", "null", "{")) assertInvalid(input)
    }

    @Test fun everyObservationRequiresExactTypeAndBoundedIntegerCounts() {
        val fields = listOf("interface", "endpoint", "port", "cidr", "dns", "serverPublicKey", "peerCount", "revokedCount")
        for (field in fields) {
            assertInvalid(response().apply { remove(field) }.toString())
            for (value in listOf(JSONObject.NULL, true, JSONArray(), JSONObject())) {
                assertInvalid(response().put(field, value).toString())
            }
        }
        for (field in fields.take(6)) assertInvalid(response().put(field, 1).toString())
        for ((field, maximum) in listOf("peerCount" to 253, "revokedCount" to 1024)) {
            for (value in listOf(-1, maximum + 1, "1", 1.5, Long.MAX_VALUE)) {
                assertInvalid(response().put(field, value).toString())
            }
        }
    }

    private fun assertInvalid(input: String) {
        val failure = assertThrows(ProvisioningException::class.java) { ProvisioningJson.parseRestoreVerification(input) }
        assertEquals(ProvisioningFailureCategory.INVALID_RESPONSE, failure.category)
        assertFalse(failure.message.orEmpty().contains("synthetic-private"))
    }

    private fun response() = JSONObject().put("ok", true).put("verifyOnly", true).put("verified", true)
        .put("interface", "wg0").put("endpoint", "destination.example").put("port", "51821").put("cidr", "10.8.0.0/24")
        .put("dns", "1.1.1.1").put("serverPublicKey", PUBLIC_KEY).put("peerCount", 1).put("revokedCount", 1)
        .put("running", true).put("serviceEnabled", true)

    private companion object {
        const val PUBLIC_KEY = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
    }
}
