/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vpn0wn.crypto.Key
import com.vpn0wn.crypto.KeyPair
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class VpsRestoreClientTest {
    @Test
    fun freshRestoreUploadsPrivateDataBeforeAdmissionAndChecksReceipt() = runBlocking {
        val remote = RecordingRemote()
        val snapshot = snapshot()
        val result = client(remote).restoreFresh(connection(), snapshot, request()) {
            assertTrue(remote.uploads.keys.any { it.endsWith("/server.conf") })
            assertTrue(remote.uploads.keys.any { it.endsWith("/retired.conf.ABC123") })
            assertFalse(remote.commands.any(::isRestore))
            remote.admitted = true
        }
        assertEquals(SERVER_KEY, result.serverPublicKey)
        assertEquals("destination.example", result.endpoint)
        assertEquals("51821", result.port)
        assertEquals(1, result.peerCount)
        assertEquals(1, result.revokedCount)
        val state = remote.uploads.filterKeys { it.contains("/restore-input/") }
        assertEquals(2, state.size)
        assertTrue(state.values.all { it.second == "0600" })
        assertEquals(snapshot.config, state.getValue("$REMOTE/restore-input/server.conf").first)
        assertEquals(snapshot.revoked.single().config, state.getValue("$REMOTE/restore-input/revoked/retired.conf.ABC123").first)
        assertTrue(remote.commands.any { it.startsWith("umask 077 && mkdir -- ") })
        assertTrue(remote.commands.none { it.contains(PRIVATE_KEY) || it.contains(PSK) })
        assertTrue(remote.cleaned)
        assertEquals(1, remote.closes)
    }

    @Test
    fun invalidSnapshotNamesCountsUnicodeAndRequestsNeverCreateTransport() = runBlocking {
        val valid = snapshot()
        val invalid = listOf(
            valid.copy(interfaceName = "other0") to request(),
            valid.copy(revokedCount = 0) to request(),
            valid.copy(config = valid.config + '\uD800') to request(),
            valid.copy(revoked = listOf(VpsRevokedBackup("../../foreign", "private"))) to request(),
            valid.copy(revoked = valid.revoked + valid.revoked, revokedCount = 2) to request(),
            valid to request().copy(port = 0),
            valid to request().copy(expectedServerPublicKey = "invalid"),
        )
        var creates = 0
        val client = Vpn0wnProvisioningClient(ApplicationProvider.getApplicationContext(), object : RemoteShellClientFactory {
            override fun create(connection: VpsConnection): RemoteShellClient {
                creates++
                throw AssertionError("Invalid restore opened SSH")
            }
        })
        for ((snapshot, request) in invalid) {
            val failure = runCatching { client.restoreFresh(connection(), snapshot, request) }.exceptionOrNull()
            assertTrue(failure is ProvisioningException)
            assertEquals(ProvisioningFailureCategory.INVALID_INPUT, (failure as ProvisioningException).category)
        }
        assertEquals(0, creates)
    }

    @Test
    fun uploadFailureDoesNotAdmitOrRunRestoreAndClosesOwnedClient() = runBlocking {
        val remote = RecordingRemote().apply { failUpload = true }
        val failure = runCatching {
            client(remote).restoreFresh(connection(), snapshot(), request()) { remote.admitted = true }
        }.exceptionOrNull()
        assertTrue(failure is ProvisioningException)
        assertFalse(remote.admitted)
        assertFalse(remote.commands.any(::isRestore))
        assertTrue(remote.cleaned)
        assertEquals(1, remote.closes)
    }

    @Test
    fun cancelledUploadAttemptsCleanupOnStillOpenClientAndClosesOwner() = runBlocking {
        val remote = RecordingRemote().apply { blockUpload = true }
        val pending = async { client(remote).restoreFresh(connection(), snapshot(), request()) { remote.admitted = true } }
        try {
            remote.entered.await()
            pending.cancelAndJoin()
            assertFalse(remote.admitted)
            assertFalse(remote.commands.any(::isRestore))
            assertTrue(remote.cleaned)
            assertEquals(1, remote.closes)
        } finally {
            pending.cancelAndJoin()
        }
    }

    @Test
    fun mismatchedAndMalformedReceiptsAreNotRetried() = runBlocking {
        val wrong = listOf(
            "interface" to "other0", "endpoint" to "source.example", "port" to "51820",
            "serverPublicKey" to PEER_KEY, "cidr" to "10.9.0.0/24", "peerCount" to 2,
            "peerCount" to -1, "peerCount" to "1",
            "revokedCount" to 0, "revokedCount" to true,
        )
        for ((field, value) in wrong) {
            val remote = RecordingRemote().apply { response.put(field, value) }
            val failure = runCatching { client(remote).restoreFresh(connection(), snapshot(), request()) }.exceptionOrNull()
            assertTrue(failure is ProvisioningException)
            assertEquals(ProvisioningFailureCategory.INVALID_RESPONSE, (failure as ProvisioningException).category)
            assertEquals(1, remote.commands.count(::isRestore))
            assertTrue(remote.cleaned)
            assertEquals(1, remote.closes)
        }
    }

    @Test
    fun callerHistoryMutationAfterAdmissionCannotChangeUploadedNames() = runBlocking {
        val history = snapshot().revoked.toMutableList()
        val remote = RecordingRemote()
        val result = client(remote) {
            history.clear()
            history.add(VpsRevokedBackup("../../foreign", "private"))
        }.restoreFresh(connection(), snapshot().copy(revoked = history), request())
        assertEquals(1, result.revokedCount)
        assertTrue(remote.uploads.keys.any { it.endsWith("/retired.conf.ABC123") })
        assertFalse(remote.uploads.keys.any { it.contains("..") })
    }

    @Test
    fun occupiedDestinationPortPreservesStructuredConflictWithoutRetry() = runBlocking {
        val remote = RecordingRemote().apply { conflict = true }
        val failure = runCatching { client(remote).restoreFresh(connection(), snapshot(), request()) }.exceptionOrNull()
        assertTrue(failure is ProvisioningException)
        assertEquals(ProvisioningFailureCategory.CONFLICT, (failure as ProvisioningException).category)
        assertEquals("port_in_use", failure.remoteCode)
        assertEquals(1, remote.commands.count(::isRestore))
        assertEquals(1, remote.closes)
    }

    private fun client(remote: RecordingRemote, beforeCreate: () -> Unit = {}) = Vpn0wnProvisioningClient(
        ApplicationProvider.getApplicationContext(), object : RemoteShellClientFactory {
            override fun create(connection: VpsConnection): RemoteShellClient {
                beforeCreate()
                return remote
            }
        },
    )

    private class RecordingRemote : RemoteShellClient {
        val uploads = linkedMapOf<String, Pair<String, String>>()
        val commands = mutableListOf<String>()
        val entered = CompletableDeferred<Unit>()
        val response = JSONObject().put("ok", true).put("interface", "wg0").put("endpoint", "destination.example")
            .put("port", "51821").put("cidr", "10.8.0.0/24").put("serverPublicKey", SERVER_KEY)
            .put("peerCount", 1).put("revokedCount", 1)
        var closes = 0
        var admitted = false
        var cleaned = false
        var failUpload = false
        var blockUpload = false
        var conflict = false

        override suspend fun execute(command: String): ProvisioningCommandOutput {
            commands.add(command)
            return when {
                command.startsWith("mktemp ") -> ProvisioningCommandOutput(0, "$REMOTE\n", "")
                command.startsWith("rm -rf ") -> {
                    currentCoroutineContext().ensureActive()
                    cleaned = true
                    ProvisioningCommandOutput(0, "", "")
                }
                isRestore(command) -> {
                    if (conflict) ProvisioningCommandOutput(73, """{"ok":false,"error":"port_in_use","message":"occupied"}""", "")
                    else ProvisioningCommandOutput(0, response.toString(), "")
                }
                else -> ProvisioningCommandOutput(0, "", "")
            }
        }

        override suspend fun upload(path: String, content: String, mode: String): ProvisioningCommandOutput {
            uploads[path] = content to mode
            if (path.endsWith("/server.conf")) {
                if (failUpload) return ProvisioningCommandOutput(74, "", "synthetic-secret-diagnostic")
                if (blockUpload) {
                    entered.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
            }
            return ProvisioningCommandOutput(0, "", "")
        }

        override suspend fun close() { closes++ }
    }

    private companion object {
        const val REMOTE = "/tmp/vpn0wn-provisioning.test01"
        val PRIVATE_KEY: String = Key.fromBytes(ByteArray(Key.Format.BINARY.length) { 1 }).toBase64()
        val SERVER_KEY: String = KeyPair(Key.fromBase64(PRIVATE_KEY)).publicKey.toBase64()
        val PEER_KEY: String = Key.fromBytes(ByteArray(Key.Format.BINARY.length) { 2 }).toBase64()
        val REVOKED_KEY: String = Key.fromBytes(ByteArray(Key.Format.BINARY.length) { 3 }).toBase64()
        val PSK: String = Key.fromBytes(ByteArray(Key.Format.BINARY.length) { 4 }).toBase64()

        fun isRestore(command: String) = command.contains("./'restore.sh'")
        fun request() = VpsRestoreRequest("destination.example", 51821, SERVER_KEY)
        fun connection() = VpsConnection(VpsEndpoint("ssh.example"), VpsCredential.Password(charArrayOf()), false, hostKeyFingerprint = "synthetic-pin")
        fun snapshot() = VpsBackupResult(
            "wg0", """
                # TUNNL_MANAGED 1
                # ENDPOINT source.example
                # TUNNL_CIDR 10.8.0.0/24
                # TUNNL_DNS 1.1.1.1
                [Interface]
                Address = 10.8.0.1/24
                PrivateKey = $PRIVATE_KEY
                ListenPort = 51820
                SaveConfig = false
                PostUp = iptables -t nat -A POSTROUTING -s 10.8.0.0/24 ! -d 10.8.0.0/24 -j MASQUERADE
                PostDown = iptables -t nat -D POSTROUTING -s 10.8.0.0/24 ! -d 10.8.0.0/24 -j MASQUERADE

                # BEGIN_PEER phone
                [Peer]
                PublicKey = $PEER_KEY
                PresharedKey = $PSK
                AllowedIPs = 10.8.0.2/32
                # END_PEER phone
            """.trimIndent() + "\n", 1, 1, "2026-10-16T00:00:00Z",
            listOf(VpsRevokedBackup("retired.conf.ABC123", "# BEGIN_PEER retired\n[Peer]\nPublicKey = $REVOKED_KEY\nAllowedIPs = 10.8.0.3/32\n# END_PEER retired\n")),
        )
    }
}
