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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class VpsRestoreClientTest {
    @Test fun runningConfirmRechecksSameUploadBeforeAdmissionAndUsesExactDigest() = runBlocking {
        val remote = RecordingRemote().apply {
            checkResponse.getJSONObject("plan").put("replacement", VpsRunningRestoreFixture.replacement())
            beforeRestore = {
                assertEquals(commands.last().contains("'--confirm'"), admitted)
            }
        }
        val checked = client(remote).checkRunningServerRestore(connection(), snapshot(), request())
        assertEquals(VpsRunningRestoreFixture.review, checked.replacement)
        client(remote).restoreRunningServer(connection(), snapshot(), request(), checked.replacement) { remote.admitted = true }
        val commands = remote.commands.filter(::isRestore)
        assertEquals(3, commands.size)
        assertTrue(commands.take(2).all { it.contains("'--check'") && it.contains("'--replace-running'") })
        assertTrue(commands.last().contains("'--confirm' '--replace-running' '--expected-current-state' '${VpsRunningRestoreFixture.DIGEST}'"))
        assertEquals(2, remote.restoreInputUploads)
        assertTrue(remote.cleaned)
        assertEquals(2, remote.closes)
        remote.beforeRestore = null
        client(remote).verifyFreshRestore(connection(), snapshot(), request())
        val verify = remote.commands.last(::isRestore)
        assertTrue(verify.contains("'--verify'"))
        assertFalse(verify.contains("'--replace-running'") || verify.contains("'--expected-current-state'"))
    }

    @Test fun changedRunningReviewNeverAdmitsOrConfirms() = runBlocking {
        val remote = RecordingRemote().apply { checkResponse.getJSONObject("plan").put("replacement", VpsRunningRestoreFixture.replacement()) }
        val failure = runCatching {
            client(remote).restoreRunningServer(connection(), snapshot(), request(),
                VpsRunningRestoreFixture.review.copy(reviewSha256 = "a".repeat(64))) { remote.admitted = true }
        }.exceptionOrNull()
        assertTrue(failure is ProvisioningException)
        assertEquals(ProvisioningFailureCategory.CONFLICT, (failure as ProvisioningException).category)
        assertFalse(remote.admitted)
        assertEquals(1, remote.commands.count(::isRestore))
        assertTrue(remote.commands.single(::isRestore).contains("'--check'"))
        assertTrue(remote.cleaned)
        assertEquals(1, remote.closes)
    }

    @Test fun cancelledRunningRecheckDrainsPrivateCleanupWithoutAdmission() = runBlocking {
        val remote = RecordingRemote().apply { blockRestore = true }
        val pending = async { client(remote).restoreRunningServer(connection(), snapshot(), request(), VpsRunningRestoreFixture.review) { remote.admitted = true } }
        try {
            remote.entered.await()
            pending.cancelAndJoin()
            assertFalse(remote.admitted)
            assertFalse(remote.commands.any { it.contains("'--confirm'") })
            assertTrue(remote.cleaned)
            assertEquals(1, remote.closes)
        } finally { pending.cancelAndJoin() }
    }

    @Test fun everyDesiredRunningPlanFieldStillCorrelatesWithCapturedSourceAndRequest() = runBlocking {
        for ((field, value) in listOf("interface" to "other0", "endpoint" to "foreign.example", "port" to "51822",
            "cidr" to "10.10.0.0/24", "dns" to "8.8.8.8", "serverPublicKey" to PEER_KEY, "peerCount" to 0, "revokedCount" to 0)) {
            val remote = RecordingRemote().apply {
                checkResponse.getJSONObject("plan").put("replacement", VpsRunningRestoreFixture.replacement()).put(field, value)
            }
            val failure = runCatching { client(remote).checkRunningServerRestore(connection(), snapshot(), request()) }.exceptionOrNull()
            assertTrue(failure is ProvisioningException)
            assertEquals(ProvisioningFailureCategory.INVALID_RESPONSE, (failure as ProvisioningException).category)
            assertFalse(remote.admitted)
            assertTrue(remote.cleaned)
            assertEquals(1, remote.closes)
        }
    }

    @Test
    fun checkUploadsPrivateInputsBeforeReadOnlyDispatchAndReturnsCorrelatedPublicPlan() = runBlocking {
        val snapshot = snapshot()
        val remote = RecordingRemote().apply {
            beforeRestore = {
                val state = uploads.filterKeys { it.contains("/restore-input/") }
                assertEquals(2, state.size)
                assertTrue(state.values.all { it.second == "0600" })
                assertTrue(state.getValue("$REMOTE/restore-input/server.conf").first == snapshot.config)
                assertTrue(state.getValue("$REMOTE/restore-input/revoked/retired.conf.ABC123").first == snapshot.revoked.single().config)
                assertFalse(admitted)
            }
        }
        val result = client(remote).checkFreshRestore(connection(), snapshot, request())
        assertEquals(SERVER_KEY, result.serverPublicKey)
        assertEquals("destination.example", result.endpoint)
        assertEquals("51821", result.port)
        assertEquals("10.8.0.0/24", result.cidr)
        assertEquals("1.1.1.1", result.dns)
        assertEquals(1, result.peerCount)
        assertEquals(1, result.revokedCount)
        val command = remote.commands.single(::isRestore)
        assertTrue(command.contains("'--check'"))
        assertFalse(command.contains("'--confirm'"))
        assertTrue(remote.commands.any { it.startsWith("umask 077 && mkdir -- ") })
        assertTrue(remote.commands.none { it.contains(PRIVATE_KEY) || it.contains(PSK) })
        assertFalse(remote.admitted)
        assertTrue(remote.cleaned)
        assertEquals(1, remote.closes)
    }

    @Test
    fun invalidReadOnlySnapshotMetadataIdentityAndRequestsNeverCreateTransport() = runBlocking {
        val valid = snapshot()
        val invalid = listOf(
            valid.copy(interfaceName = "other0") to request(),
            valid.copy(snapshotVersion = 2) to request(),
            valid.copy(capturedAtUtc = "invalid-date") to request(),
            valid.copy(revokedCount = 0) to request(),
            valid.copy(config = valid.config + '\uD800') to request(),
            valid.copy(revoked = listOf(VpsRevokedBackup("../../foreign", "private"))) to request(),
            valid.copy(revoked = valid.revoked + valid.revoked, revokedCount = 2) to request(),
            valid.copy(config = valid.config.replace("# TUNNL_CIDR ", "# OTHER_CIDR ")) to request(),
            valid.copy(config = valid.config + "# TUNNL_CIDR 10.9.0.0/24\n") to request(),
            valid.copy(config = valid.config.replace("# TUNNL_DNS ", "# OTHER_DNS ")) to request(),
            valid.copy(config = valid.config + "# TUNNL_DNS 8.8.8.8\n") to request(),
            valid.copy(config = valid.config.replace("PrivateKey = $PRIVATE_KEY", "PrivateKey = invalid")) to request(),
            valid.copy(config = valid.config + "PrivateKey = $PRIVATE_KEY\n") to request(),
            valid to request().copy(port = 0),
            valid to request().copy(expectedServerPublicKey = "invalid"),
            valid to request().copy(expectedServerPublicKey = PEER_KEY),
        )
        var creates = 0
        val client = Vpn0wnProvisioningClient(ApplicationProvider.getApplicationContext(), object : RemoteShellClientFactory {
            override fun create(connection: VpsConnection): RemoteShellClient {
                creates++
                throw AssertionError("Invalid check opened SSH")
            }
        })
        for ((snapshot, request) in invalid) {
            val failures = listOf(runCatching { client.checkFreshRestore(connection(), snapshot, request) }.exceptionOrNull(),
                runCatching { client.verifyFreshRestore(connection(), snapshot, request) }.exceptionOrNull())
            for (failure in failures) {
                assertTrue(failure is ProvisioningException)
                assertEquals(ProvisioningFailureCategory.INVALID_INPUT, (failure as ProvisioningException).category)
            }
        }
        assertEquals(0, creates)
    }

    @Test
    fun uncorrelatedCheckPlansAreRejectedWithoutRetryAndClearOwnedInputs() = runBlocking {
        for ((field, value) in listOf(
            "interface" to "other0", "endpoint" to "source.example", "port" to "51820",
            "cidr" to "10.9.0.0/24", "dns" to "8.8.8.8", "serverPublicKey" to PEER_KEY,
            "peerCount" to 2, "revokedCount" to 0,
        )) {
            val remote = RecordingRemote().apply { checkResponse.getJSONObject("plan").put(field, value) }
            val failure = runCatching { client(remote).checkFreshRestore(connection(), snapshot(), request()) }.exceptionOrNull()
            assertTrue(failure is ProvisioningException)
            assertEquals(ProvisioningFailureCategory.INVALID_RESPONSE, (failure as ProvisioningException).category)
            assertFalse(failure.message.orEmpty().contains(PRIVATE_KEY))
            assertFalse(remote.admitted)
            assertEquals(1, remote.commands.count(::isRestore))
            assertTrue(remote.cleaned)
            assertEquals(1, remote.closes)
        }
    }

    @Test
    fun checkUploadFailureNeverDispatchesOrAdmitsAndClosesOwnedClient() = runBlocking {
        val remote = RecordingRemote().apply { failUpload = true }
        val failure = runCatching { client(remote).checkFreshRestore(connection(), snapshot(), request()) }.exceptionOrNull()
        assertTrue(failure is ProvisioningException)
        assertFalse(failure?.message.orEmpty().contains("synthetic-secret-diagnostic"))
        assertFalse(remote.admitted)
        assertFalse(remote.commands.any(::isRestore))
        assertTrue(remote.cleaned)
        assertEquals(1, remote.closes)
    }

    @Test
    fun cancelledCheckUploadDrainsCleanupAndClosesWithoutDispatch() = runBlocking {
        val remote = RecordingRemote().apply { blockUpload = true }
        val pending = async { client(remote).checkFreshRestore(connection(), snapshot(), request()) }
        try {
            remote.entered.await()
            pending.cancelAndJoin()
            assertFalse(remote.admitted)
            assertFalse(remote.commands.any(::isRestore))
            assertTrue(remote.cleaned)
            assertEquals(1, remote.closes)
        } finally { pending.cancelAndJoin() }
    }

    @Test
    fun cancelledCheckDispatchDrainsCleanupAndClosesWithoutMutationAdmission() = runBlocking {
        val remote = RecordingRemote().apply { blockRestore = true }
        val pending = async { client(remote).checkFreshRestore(connection(), snapshot(), request()) }
        try {
            remote.entered.await()
            pending.cancelAndJoin()
            assertFalse(remote.admitted)
            assertEquals(1, remote.commands.count(::isRestore))
            assertTrue(remote.cleaned)
            assertEquals(1, remote.closes)
        } finally { pending.cancelAndJoin() }
    }

    @Test
    fun checkDefectPreservesThrowableAndStillCleansAndCloses() = runBlocking {
        val defect = AssertionError("Synthetic check defect")
        val remote = RecordingRemote().apply { beforeRestore = { throw defect } }
        val failure = requireNotNull(runCatching { client(remote).checkFreshRestore(connection(), snapshot(), request()) }.exceptionOrNull())
        assertEquals(defect.javaClass, failure.javaClass)
        // Coroutine stack recovery can copy the Error while retaining its original cause.
        assertSame(defect, generateSequence(failure) { it.cause }.last())
        assertTrue(remote.cleaned)
        assertEquals(1, remote.closes)
    }

    @Test
    fun callerHistoryMutationCannotChangeCheckUploadsOrCorrelation() = runBlocking {
        val history = snapshot().revoked.toMutableList()
        val remote = RecordingRemote()
        val result = client(remote) {
            history.clear()
            history.add(VpsRevokedBackup("../../foreign", "private"))
        }.checkFreshRestore(connection(), snapshot().copy(revoked = history), request())
        assertEquals(1, result.revokedCount)
        assertTrue(remote.uploads.keys.any { it.endsWith("/retired.conf.ABC123") })
        assertFalse(remote.uploads.keys.any { it.contains("..") })
    }

    @Test
    fun checkConflictKeepsSafeTypedFailureWithoutRetry() = runBlocking {
        val remote = RecordingRemote().apply { conflict = true }
        val failure = runCatching { client(remote).checkFreshRestore(connection(), snapshot(), request()) }.exceptionOrNull()
        assertTrue(failure is ProvisioningException)
        assertEquals(ProvisioningFailureCategory.CONFLICT, (failure as ProvisioningException).category)
        assertEquals("port_in_use", failure.remoteCode)
        assertFalse(failure.message.orEmpty().contains("occupied"))
        assertFalse(remote.admitted)
        assertEquals(1, remote.commands.count(::isRestore))
        assertTrue(remote.cleaned)
        assertEquals(1, remote.closes)
    }

    @Test
    fun verifyUploadsPrivateInputsBeforeObservationOnlyDispatchAndChecksEveryPublicField() = runBlocking {
        val source = snapshot()
        val remote = RecordingRemote().apply {
            beforeRestore = {
                val state = uploads.filterKeys { it.contains("/restore-input/") }
                assertEquals(2, state.size)
                assertTrue(state.values.all { it.second == "0600" })
                assertTrue(state.getValue("$REMOTE/restore-input/server.conf").first == source.config)
                assertTrue(state.getValue("$REMOTE/restore-input/revoked/retired.conf.ABC123").first == source.revoked.single().config)
                assertFalse(admitted)
            }
        }
        val result = client(remote).verifyFreshRestore(connection(), source, request())
        assertEquals(VpsRestoreVerification("wg0", "destination.example", "51821", "10.8.0.0/24", "1.1.1.1", SERVER_KEY, 1, 1, true, true), result)
        val command = remote.commands.single(::isRestore)
        assertTrue(command.contains("'--verify'"))
        assertFalse(command.contains("'--check'") || command.contains("'--confirm'"))
        assertTrue(remote.commands.none { it.contains(PRIVATE_KEY) || it.contains(PSK) })
        assertFalse(remote.admitted)
        assertTrue(remote.cleaned)
        assertEquals(1, remote.closes)
    }

    @Test
    fun uncorrelatedVerificationReceiptsAreRejectedWithoutRetryAndClearOwnedInputs() = runBlocking {
        for ((field, value) in listOf(
            "interface" to "other0", "endpoint" to "source.example", "port" to "51820",
            "cidr" to "10.9.0.0/24", "dns" to "8.8.8.8", "serverPublicKey" to PEER_KEY,
            "peerCount" to 2, "revokedCount" to 0, "running" to false, "serviceEnabled" to false,
        )) {
            val remote = RecordingRemote().apply { verificationResponse.put(field, value) }
            val failure = runCatching { client(remote).verifyFreshRestore(connection(), snapshot(), request()) }.exceptionOrNull()
            assertTrue(failure is ProvisioningException)
            assertEquals(ProvisioningFailureCategory.INVALID_RESPONSE, (failure as ProvisioningException).category)
            assertFalse(failure.message.orEmpty().contains(PRIVATE_KEY))
            assertFalse(remote.admitted)
            assertEquals(1, remote.commands.count(::isRestore))
            assertTrue(remote.cleaned)
            assertEquals(1, remote.closes)
        }
    }

    @Test
    fun verifyRejectsCheckAndMutationReceiptsWithoutDispatchingAnotherMode() = runBlocking {
        for (useCheck in listOf(false, true)) {
            val remote = RecordingRemote().apply { verificationResponse = if (useCheck) checkResponse else response }
            val failure = runCatching { client(remote).verifyFreshRestore(connection(), snapshot(), request()) }.exceptionOrNull()
            assertTrue(failure is ProvisioningException)
            assertEquals(ProvisioningFailureCategory.INVALID_RESPONSE, (failure as ProvisioningException).category)
            assertTrue(remote.commands.single(::isRestore).contains("'--verify'"))
            assertFalse(remote.admitted)
            assertTrue(remote.cleaned)
            assertEquals(1, remote.closes)
        }
    }

    @Test
    fun verifyUploadFailureNeverDispatchesOrAdmitsAndClosesOwnedClient() = runBlocking {
        val remote = RecordingRemote().apply { failUpload = true }
        val failure = runCatching { client(remote).verifyFreshRestore(connection(), snapshot(), request()) }.exceptionOrNull()
        assertTrue(failure is ProvisioningException)
        assertFalse(failure?.message.orEmpty().contains("synthetic-secret-diagnostic"))
        assertFalse(remote.admitted)
        assertFalse(remote.commands.any(::isRestore))
        assertTrue(remote.cleaned)
        assertEquals(1, remote.closes)
    }

    @Test
    fun cancelledVerifyUploadAndDispatchDrainCleanupWithoutMutationAdmission() = runBlocking {
        for (duringUpload in listOf(false, true)) {
            val remote = RecordingRemote().apply { blockUpload = duringUpload; blockRestore = !duringUpload }
            val pending = async { client(remote).verifyFreshRestore(connection(), snapshot(), request()) }
            try {
                remote.entered.await()
                pending.cancelAndJoin()
                assertFalse(remote.admitted)
                assertEquals(if (duringUpload) 0 else 1, remote.commands.count(::isRestore))
                assertTrue(remote.cleaned)
                assertEquals(1, remote.closes)
            } finally { pending.cancelAndJoin() }
        }
    }

    @Test
    fun verifyDefectPreservesThrowableAndStillCleansAndCloses() = runBlocking {
        val defect = AssertionError("Synthetic verification defect")
        val remote = RecordingRemote().apply { beforeRestore = { throw defect } }
        val failure = requireNotNull(runCatching { client(remote).verifyFreshRestore(connection(), snapshot(), request()) }.exceptionOrNull())
        assertEquals(defect.javaClass, failure.javaClass)
        assertSame(defect, generateSequence(failure) { it.cause }.last())
        assertFalse(remote.admitted)
        assertTrue(remote.cleaned)
        assertEquals(1, remote.closes)
    }

    @Test
    fun callerHistoryMutationCannotChangeVerifyUploadsOrCorrelation() = runBlocking {
        val history = snapshot().revoked.toMutableList()
        val remote = RecordingRemote()
        val result = client(remote) {
            history.clear()
            history.add(VpsRevokedBackup("../../foreign", "private"))
        }.verifyFreshRestore(connection(), snapshot().copy(revoked = history), request())
        assertEquals(1, result.revokedCount)
        assertTrue(remote.uploads.keys.any { it.endsWith("/retired.conf.ABC123") })
        assertFalse(remote.uploads.keys.any { it.contains("..") })
    }

    @Test
    fun verifyPostconditionAndObservationFailuresStaySafeWithoutRetry() = runBlocking {
        for (code in listOf("postcondition_failed", "runtime_unknown")) {
            val remote = RecordingRemote().apply {
                restoreFailure = ProvisioningCommandOutput(74, """{"ok":false,"error":"$code","message":"synthetic-secret-diagnostic"}""", "")
            }
            val failure = runCatching { client(remote).verifyFreshRestore(connection(), snapshot(), request()) }.exceptionOrNull()
            assertTrue(failure is ProvisioningException)
            assertEquals(code, (failure as ProvisioningException).remoteCode)
            assertFalse(failure.message.orEmpty().contains("synthetic-secret"))
            assertFalse(remote.admitted)
            assertEquals(1, remote.commands.count(::isRestore))
            assertTrue(remote.cleaned)
            assertEquals(1, remote.closes)
        }
    }

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
        val checkResponse = JSONObject().put("ok", true).put("checkOnly", true).put("plan",
            JSONObject(response.toString()).apply { remove("ok"); put("dns", "1.1.1.1") },
        )
        var verificationResponse = JSONObject(response.toString()).put("verifyOnly", true).put("verified", true)
            .put("dns", "1.1.1.1").put("running", true).put("serviceEnabled", true)
        var restoreFailure: ProvisioningCommandOutput? = null
        var closes = 0
        var admitted = false
        var cleaned = false
        var failUpload = false
        var blockUpload = false
        var blockRestore = false
        var conflict = false
        var beforeRestore: (() -> Unit)? = null
        var restoreInputUploads = 0

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
                    beforeRestore?.invoke()
                    if (blockRestore) {
                        entered.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    }
                    if (restoreFailure != null) requireNotNull(restoreFailure)
                    else if (conflict) ProvisioningCommandOutput(73, """{"ok":false,"error":"port_in_use","message":"occupied"}""", "")
                    else if (command.contains("'--check'")) ProvisioningCommandOutput(0, checkResponse.toString(), "")
                    else if (command.contains("'--verify'")) ProvisioningCommandOutput(0, verificationResponse.toString(), "")
                    else ProvisioningCommandOutput(0, response.toString(), "")
                }
                else -> ProvisioningCommandOutput(0, "", "")
            }
        }

        override suspend fun upload(path: String, content: String, mode: String): ProvisioningCommandOutput {
            uploads[path] = content to mode
            if (path.endsWith("/server.conf")) {
                restoreInputUploads++
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
