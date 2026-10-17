/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.app.Application
import java.io.IOException
import java.util.Base64
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PendingVpsRestoreTest {
    @Test fun exactPublicRecordRoundTripsWithNoSnapshotOrCredentialFields() {
        val attempt = VpsRestoreFixture.attempt
        val encoded = PendingVpsRestore.encode(listOf(attempt))
        assertEquals(listOf(attempt), PendingVpsRestore.decode(encoded))
        val fields = JSONArray(encoded).getJSONObject(0).keys().asSequence().toSet()
        assertEquals(setOf("operationId", "serverId", "ssh", "fingerprint", "ciphertextSha256", "plan"), fields)
        assertFalse(encoded.contains("privateKey"))
        assertFalse(encoded.contains("password"))
    }

    @Test fun schemaRejectsUnknownFieldsWrongTypesAndTrailingPayload() {
        val encoded = PendingVpsRestore.encode(listOf(VpsRestoreFixture.attempt))
        val mutations = listOf(
            JSONArray(encoded).apply { getJSONObject(0).put("password", "synthetic") },
            JSONArray(encoded).apply { getJSONObject(0).getJSONObject("ssh").put("port", "22") },
            JSONArray(encoded).apply { getJSONObject(0).getJSONObject("ssh").put("extra", true) },
            JSONArray(encoded).apply { getJSONObject(0).getJSONObject("plan").put("peerCount", "1") },
            JSONArray(encoded).apply { getJSONObject(0).getJSONObject("plan").put("extra", true) },
        )
        mutations.forEach { assertInvalid(it.toString()) }
        assertInvalid("$encoded {}")
        assertInvalid("null")
    }

    @Test fun identifiersAndDigestMustUseCanonicalEncoding() {
        val attempt = VpsRestoreFixture.attempt
        listOf(
            attempt.copy(operationId = VpsRestoreFixture.OTHER_OPERATION.uppercase()),
            attempt.copy(operationId = "1-2-3-4-5"),
            attempt.copy(ciphertextSha256 = "AB".repeat(32)),
            attempt.copy(ciphertextSha256 = "a".repeat(63)),
            attempt.copy(ciphertextSha256 = "g".repeat(64)),
            attempt.copy(fingerprint = attempt.fingerprint + "="),
        ).forEach { assertTrue(runCatching { PendingVpsRestore.encode(listOf(it)) }.exceptionOrNull() is IOException) }
    }

    @Test fun planRejectsInvalidNetworksKeysCountsAndPorts() {
        val attempt = VpsRestoreFixture.attempt
        val plan = attempt.plan
        listOf(
            plan.copy(interfaceName = "wg1"), plan.copy(port = "0"), plan.copy(port = "65536"), plan.copy(port = "051820"),
            plan.copy(cidr = "10.8.0.1/24"), plan.copy(cidr = "10.8.0.0/33"), plan.copy(cidr = "::/24"),
            plan.copy(serverPublicKey = "invalid"), plan.copy(peerCount = -1), plan.copy(peerCount = 254),
            plan.copy(revokedCount = 1025), plan.copy(dns = ""), plan.copy(endpoint = "\nexample.test"),
        ).forEach { assertTrue(runCatching { PendingVpsRestore.encode(listOf(attempt.copy(plan = it))) }.exceptionOrNull() is IOException) }
    }

    @Test fun duplicateOwnersEndpointsAndAttemptsAreRejected() {
        val attempt = VpsRestoreFixture.attempt
        val distinct = attempt.copy(operationId = VpsRestoreFixture.OTHER_OPERATION, serverId = "other", ssh = attempt.ssh.copy(host = "198.51.100.2"))
        assertEquals(2, PendingVpsRestore.decode(PendingVpsRestore.encode(listOf(attempt, distinct))).size)
        listOf(distinct.copy(serverId = attempt.serverId), distinct.copy(ssh = attempt.ssh), distinct.copy(operationId = attempt.operationId))
            .forEach { assertTrue(runCatching { PendingVpsRestore.encode(listOf(attempt, it)) }.exceptionOrNull() is IOException) }
    }

    @Test fun listAndInputBoundsFailClosed() {
        val attempt = VpsRestoreFixture.attempt
        val array = JSONArray()
        val item = JSONArray(PendingVpsRestore.encode(listOf(attempt))).getJSONObject(0)
        repeat(257) { array.put(item) }
        assertInvalid(array.toString())
        assertInvalid(" ".repeat(2_097_153))
        assertEquals(emptyList<PendingVpsRestore>(), PendingVpsRestore.decode(""))
    }

    @Test fun verificationRequiresEveryPublicValueAndRunningEnabledState() {
        val attempt = VpsRestoreFixture.attempt
        val proof = VpsRestoreFixture.verification
        assertTrue(attempt.observes(proof))
        listOf(proof.copy(endpoint = "changed.test"), proof.copy(port = "51821"), proof.copy(cidr = "10.9.0.0/24"),
            proof.copy(dns = "1.1.1.1"), proof.copy(serverPublicKey = "other"), proof.copy(peerCount = 2),
            proof.copy(revokedCount = 3), proof.copy(running = false), proof.copy(serviceEnabled = false))
            .forEach { assertFalse(attempt.observes(it)) }
    }

    private fun assertInvalid(text: String) {
        assertTrue(runCatching { PendingVpsRestore.decode(text) }.exceptionOrNull() is IOException)
    }
}

internal object VpsRestoreFixture {
    val server = VpsProfileFixture.server
    val pin = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { 5 })
    val plan = VpsRestoreCheckPlan("wg0", "restored.example", "51820", "10.8.0.0/24", "9.9.9.9",
        VpsProfileFixture.serverKey.publicKey.toBase64(), 1, 2)
    val attempt = PendingVpsRestore("11111111-2222-4333-8444-555555555555", server.id,
        VpsEndpoint(server.host, server.sshPort, server.sshUser), pin, "ab".repeat(32), plan)
    val verification = VpsRestoreVerification(plan.interfaceName, plan.endpoint, plan.port, plan.cidr, plan.dns,
        plan.serverPublicKey, plan.peerCount, plan.revokedCount, running = true, serviceEnabled = true)
    const val OTHER_OPERATION = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
}
