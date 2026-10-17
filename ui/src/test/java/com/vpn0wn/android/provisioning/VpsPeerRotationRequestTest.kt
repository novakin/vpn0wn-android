/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vpn0wn.crypto.KeyPair
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class VpsPeerRotationRequestTest {
    private val old = VpsProfileFixture.profile()
    private val proposed = old.rotated(KeyPair(), OPERATION)
    private val attempt = PendingVpsRotate(old.identity.serverId, old.identity.ssh, PIN, old.identity.serverPublicKey, old.name,
        old.identity.clientPublicKey, proposed.identity.clientPublicKey, OPERATION)
    private val connection = VpsConnection(attempt.ssh, VpsCredential.Password(charArrayOf()), false, PIN)

    @Test fun wrongEndpointAndPinCannotCreateClientOrUploadPrivateInput() = runBlocking {
        for (mode in VpsPeerRotationResult.Mode.entries) for (wrong in listOf(connection.copy(endpoint = attempt.ssh.copy(host = "203.0.113.99")),
            connection.copy(hostKeyFingerprint = OTHER_PIN), connection.copy(hostKeyFingerprint = null))) {
            val remote = Peer()
            val client = client(remote)
            val failure = runCatching {
                if (mode == VpsPeerRotationResult.Mode.VERIFY) client.verifyPeerRotation(wrong, VpsProfileFixture.server, attempt, proposed)
                else client.rotatePeer(wrong, VpsProfileFixture.server, attempt, proposed, {}, {})
            }.exceptionOrNull()
            assertEquals(ProvisioningFailureCategory.INVALID_INPUT, (failure as ProvisioningException).category)
            assertEquals(0, remote.creations)
            assertTrue(remote.uploads.isEmpty())
        }
    }

    @Test fun protectedInputPreservesPskButNeverUploadsClientPrivateKeysOrArgvSecrets() = runBlocking {
        val remote = Peer()
        var admitted = false
        var received = false
        client(remote).rotatePeer(connection, VpsProfileFixture.server, attempt, proposed,
            onAdmitted = { admitted = true; assertEquals("0600", remote.uploads.single { it.path.endsWith("rotation-input") }.mode) },
            onReceived = { received = true })
        assertTrue(admitted)
        assertTrue(received)
        val input = remote.uploads.single { it.path.endsWith("rotation-input") }
        assertTrue(input.content.contains("presharedKey=" + VpsProfileFixture.psk.toBase64()))
        for (privateKey in listOf(old.config.`interface`.keyPair.privateKey, proposed.config.`interface`.keyPair.privateKey))
            assertTrue(remote.uploads.none { it.content.contains(privateKey.toBase64()) })
        assertTrue(remote.commands.none { it.contains(VpsProfileFixture.psk.toBase64()) })
        assertEquals(1, remote.rotates)
        assertEquals(0, remote.verifies)
        assertEquals(1, remote.closes)
    }

    @Test fun reconciliationUsesOnlyVerifyAndRejectsUncorrelatedOrMalformedReceipts() = runBlocking {
        for (change in listOf<(JSONObject) -> Unit>({ it.put("operationId", OTHER_OPERATION) }, { it.put("newPublicKey", attempt.oldClientPublicKey) },
            { it.put("running", JSONObject.NULL) }, { it.put("verified", false) }, { it.put("presharedKey", "synthetic-must-not-be-output") })) {
            val remote = Peer().apply { receiptChange = change }
            val failure = runCatching { client(remote).verifyPeerRotation(connection, VpsProfileFixture.server, attempt, proposed) }.exceptionOrNull()
            assertEquals(ProvisioningFailureCategory.INVALID_RESPONSE, (failure as ProvisioningException).category)
            assertEquals(0, remote.rotates)
            assertEquals(1, remote.verifies)
            assertEquals(1, remote.closes)
        }
    }

    @Test fun validatedReceiptMarkerRunsBeforeCloseFailureCanReachCaller() = runBlocking {
        val remote = Peer().apply { failClose = true }
        var received = false
        val failure = runCatching { client(remote).rotatePeer(connection, VpsProfileFixture.server, attempt, proposed, {}, { received = true }) }.exceptionOrNull()
        assertTrue(received)
        assertTrue(failure is ProvisioningException)
        assertEquals(1, remote.rotates)
    }

    private fun client(peer: Peer) = Vpn0wnProvisioningClient(ApplicationProvider.getApplicationContext(), object : RemoteShellClientFactory {
        override fun create(connection: VpsConnection): RemoteShellClient { peer.creations++; return peer }
    })

    private data class Upload(val path: String, val content: String, val mode: String)

    private inner class Peer : RemoteShellClient {
        val uploads = mutableListOf<Upload>()
        val commands = mutableListOf<String>()
        var creations = 0
        var closes = 0
        var rotates = 0
        var verifies = 0
        var failClose = false
        var receiptChange: (JSONObject) -> Unit = {}

        override suspend fun execute(command: String): ProvisioningCommandOutput {
            commands += command
            val stdout = when {
                command.startsWith("mktemp -d") -> "/tmp/vpn0wn-provisioning.ABC123\n"
                command.contains("'rotate-peer.sh'") -> {
                    val verifying = command.contains("'--verify'")
                    if (verifying) verifies++ else rotates++
                    JSONObject().put("ok", true).put("mode", if (verifying) "verify" else "rotate").put("verified", true).put("interface", "wg0")
                        .put("operationId", attempt.operationId).put("name", attempt.name).put("serverPublicKey", attempt.serverPublicKey)
                        .put("oldPublicKey", attempt.oldClientPublicKey).put("newPublicKey", attempt.newClientPublicKey)
                        .put("running", true).put("cleanupPath", "").apply(receiptChange).toString()
                }
                else -> ""
            }
            return ProvisioningCommandOutput(0, stdout, "")
        }
        override suspend fun upload(path: String, content: String, mode: String): ProvisioningCommandOutput {
            uploads += Upload(path, content, mode)
            return ProvisioningCommandOutput(0, "", "")
        }
        override suspend fun close() {
            closes++
            if (failClose) throw ProvisioningException("synthetic close failure")
        }
    }

    private companion object {
        val PIN = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { 5 })
        val OTHER_PIN = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { 6 })
        const val OPERATION = "11111111-2222-4333-8444-555555555555"
        const val OTHER_OPERATION = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    }
}
