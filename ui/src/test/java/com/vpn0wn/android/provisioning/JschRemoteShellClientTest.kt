/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.app.Application
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class JschRemoteShellClientTest {
    @Test fun actualStderrProgressArrivesBeforeExitWithoutPublishingRawOutputOrStdoutMarkers() = runBlocking {
        for (command in listOf("progress", "progress-nonzero")) {
            SshTestServer(temporaryFolder.newFolder().toPath()).use { peer ->
                val client = JschRemoteShellClientFactory().create(peer.connection())
                val first = CompletableDeferred<VpsInstallProgress>()
                val phases = java.util.Collections.synchronizedList(mutableListOf<VpsInstallProgress>())
                try {
                    val operation = async(Dispatchers.IO) {
                        client.execute(command) { phase -> phases.add(phase); first.complete(phase) }
                    }
                    assertEquals(VpsInstallProgress.PREPARING, withTimeout(TEST_TIMEOUT) { first.await() })
                    assertFalse(operation.isCompleted)
                    assertEquals(listOf(VpsInstallProgress.PREPARING), phases.toList())
                    peer.releaseHungOutput.countDown()
                    val result = withTimeout(TEST_TIMEOUT) { operation.await() }
                    assertEquals(if (command == "progress") 0 else 17, result.exitCode)
                    assertTrue(result.stdout.contains(SshTestServer.SECRET_MARKER))
                    assertEquals(listOf(VpsInstallProgress.PREPARING, VpsInstallProgress.PACKAGES, VpsInstallProgress.VERIFYING), phases.toList())
                } finally { client.close() }
            }
        }
    }

    @Test fun cancellingActualProgressCommandSettlesOwnedWorkersAndStopsDelivery() = runBlocking {
        SshTestServer(temporaryFolder.newFolder().toPath()).use { peer ->
            val client = JschRemoteShellClientFactory().create(peer.connection())
            val first = CompletableDeferred<Unit>()
            val phases = java.util.Collections.synchronizedList(mutableListOf<VpsInstallProgress>())
            try {
                val operation = async(Dispatchers.IO) { client.execute("progress-hang") { phases.add(it); first.complete(Unit) } }
                withTimeout(TEST_TIMEOUT) { first.await() }
                val threads = liveSessionThreads()
                withTimeout(TEST_TIMEOUT) { operation.cancelAndJoin() }
                client.close()
                assertThreadsStopped(threads)
                assertEquals(listOf(VpsInstallProgress.PREPARING), phases.toList())
            } finally { client.close() }
        }
    }

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun createIsLazyAndRejectsHostnamesMissingPinsAndMalformedPinsBeforeSocketCreation() = runBlocking {
        var socketCreations = 0
        val factory = JschRemoteShellClientFactory { socketCreations++; Socket() }
        val base = offlineConnection()
        val lazy = factory.create(base)
        assertEquals(0, socketCreations)
        lazy.close()
        lazy.close()
        assertEquals(0, socketCreations)
        factory.create(base.copy(endpoint = base.endpoint.copy(host = "::1"))).close()
        assertEquals(0, socketCreations)
        for (invalid in listOf(
            base.copy(endpoint = base.endpoint.copy(host = "localhost")),
            base.copy(endpoint = base.endpoint.copy(host = "127.0.0.1.invalid")),
            base.copy(endpoint = base.endpoint.copy(port = 0)),
            base.copy(hostKeyFingerprint = null, trustHostKey = true),
            base.copy(hostKeyFingerprint = "SHA256:invalid", trustHostKey = true),
            base.copy(hostKeyFingerprint = "MD5:00:00"),
        )) {
            assertTrue(runCatching { factory.create(invalid) }.exceptionOrNull() is ProvisioningException)
        }
        assertEquals(0, socketCreations)
    }

    @Test
    fun discoveryReturnsVerifiedRsaAndEcdsaKeysBeforeLoadingAnyAuthenticationMethod() = runBlocking {
        val original = requireNotNull(JSch.getConfig("userauth.none"))
        try {
            JSch.setConfig("userauth.none", "com.vpn0wn.tests.AuthenticationMustNotStart")
            for (ecdsa in listOf(false, true)) {
                SshTestServer(temporaryFolder.newFolder().toPath(), ecdsaHostKey = ecdsa).use { peer ->
                    val socket = Socket()
                    val fingerprint = withTimeout(TEST_TIMEOUT) {
                        JschRemoteShellClientFactory { socket }.discoverHostKey("127.0.0.1", peer.port)
                    }
                    assertEquals(peer.fingerprint, fingerprint)
                    assertTrue(socket.isClosed)
                    assertEquals(0, peer.passwordAttempts.get())
                    assertEquals(0, peer.keyAttempts.get())
                    assertEquals(1L, peer.commandEntered.count)
                    assertEquals(1L, peer.channelOpenEntered.count)
                    assertEquals(1L, peer.uploadEntered.count)
                    awaitNoSessions(peer)
                }
            }
        } finally { JSch.setConfig("userauth.none", original) }
    }

    @Test
    fun discoveredPinAuthenticatesOnlyTheSameHostKey() = runBlocking {
        server().use { peer ->
            val factory = JschRemoteShellClientFactory()
            val discovered = withTimeout(TEST_TIMEOUT) { factory.discoverHostKey("127.0.0.1", peer.port) }
            val client = factory.create(peer.connection(pin = discovered))
            try {
                assertEquals(0, withTimeout(TEST_TIMEOUT) { client.execute("success") }.exitCode)
                assertEquals(1, peer.passwordAttempts.get())
            } finally { client.close() }
            SshTestServer(temporaryFolder.newFolder().toPath()).use { changed ->
                val refused = factory.create(changed.connection(pin = discovered))
                try {
                    assertSafeFailure(runCatching { withTimeout(TEST_TIMEOUT) { refused.execute("success") } }.exceptionOrNull())
                    assertEquals(0, changed.passwordAttempts.get())
                    assertEquals(0, changed.keyAttempts.get())
                } finally { refused.close() }
                awaitNoSessions(changed)
            }
        }
    }

    @Test
    fun discoveryRejectsInvalidEndpointsBeforeSocketCreationAndPreservesDefects() = runBlocking {
        var socketCreations = 0
        val defect = AssertionError("synthetic socket defect")
        val factory = JschRemoteShellClientFactory { socketCreations++; throw defect }
        for ((host, port) in listOf("localhost" to 22, "127.0.0.1.invalid" to 22, "127.0.0.1" to 0, "::1" to 65536)) {
            assertSafeFailure(runCatching { factory.discoverHostKey(host, port) }.exceptionOrNull())
        }
        assertEquals(0, socketCreations)
        assertOriginalError(defect, runCatching { factory.discoverHostKey("127.0.0.1", 22) }.exceptionOrNull())
        assertEquals(1, socketCreations)
    }

    @Test
    fun discoveryCancellationDrainsBlockedConnectAndBannerIo() = runBlocking {
        for (stage in BlockingStage.entries) {
            val socket = GateSocket(stage)
            supervisorScope {
                val operation = async(Dispatchers.IO) {
                    JschRemoteShellClientFactory { socket }.discoverHostKey("127.0.0.1", 22222)
                }
                try {
                    awaitEntered(socket.entered)
                    withTimeout(TEST_TIMEOUT) { operation.cancelAndJoin() }
                    assertTrue(operation.isCancelled)
                    assertTrue(socket.isClosed)
                    assertTrue(socket.exited.await(1, TimeUnit.SECONDS))
                } finally {
                    socket.close()
                    operation.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun correctPinPasswordAndOneSessionPreserveSeparateOutputsExitStatusAndClose() = runBlocking {
        server().use { peer ->
            val socket = Socket()
            val client = JschRemoteShellClientFactory { socket }.create(peer.connection())
            var sessionThreads = emptyList<Thread>()
            try {
                val success = withTimeout(TEST_TIMEOUT) { client.execute("success") }
                assertEquals(0, success.exitCode)
                assertEquals("synthetic stdout\n", success.stdout)
                assertEquals("synthetic stderr\n", success.stderr)
                sessionThreads = liveSessionThreads()
                val nonzero = withTimeout(TEST_TIMEOUT) { client.execute("nonzero") }
                assertEquals(17, nonzero.exitCode)
                assertEquals("synthetic stdout\n", nonzero.stdout)
                assertEquals("synthetic stderr\n", nonzero.stderr)
                assertEquals(1, peer.passwordAttempts.get())
            } finally {
                client.close()
                client.close()
            }
            assertTrue(socket.isClosed)
            assertThreadsStopped(sessionThreads)
            awaitNoSessions(peer)
        }
    }

    @Test
    fun optionalCryptoClassesCannotExpandSessionSignatureProbes() = runBlocking {
        val optional = listOf("ssh-ed25519", "ssh-ed448")
        val original = optional.associateWith { requireNotNull(JSch.getConfig(it)) }
        val previousProbes = requireNotNull(JSch.getConfig("CheckSignatures"))
        try {
            optional.forEach { JSch.setConfig(it, ExcludedSignatureProbe::class.java.name) }
            JSch.setConfig("CheckSignatures", optional.joinToString(","))
            for (ecdsa in listOf(false, true)) {
                SshTestServer(temporaryFolder.root.toPath().resolve("probe-$ecdsa"), ecdsaHostKey = ecdsa).use { peer ->
                    val client = JschRemoteShellClientFactory().create(peer.connection())
                    try {
                        assertEquals(0, withTimeout(TEST_TIMEOUT) { client.execute("success") }.exitCode)
                        val delegate = GuardedRemoteShellClient::class.java.getDeclaredField("delegate")
                            .apply { isAccessible = true }.get(client)
                        val session = delegate.javaClass.getDeclaredField("session").apply { isAccessible = true }
                            .get(delegate) as com.jcraft.jsch.Session
                        val admitted = session.getConfig("server_host_key").split(',')
                        assertEquals(admitted, session.getConfig("CheckSignatures").split(','))
                        assertFalse(admitted.any { it in optional })
                        assertFalse(session.getConfig("kex").split(',').any {
                            it.startsWith("curve") || it.startsWith("mlkem") || it.startsWith("sntrup")
                        })
                        assertFalse(session.getConfig("cipher.c2s").contains("chacha20"))
                        assertEquals(session.getConfig("cipher.c2s"), session.getConfig("cipher.s2c"))
                    } finally { client.close() }
                    awaitNoSessions(peer)
                }
            }
        } finally {
            original.forEach { (name, implementation) -> JSch.setConfig(name, implementation) }
            JSch.setConfig("CheckSignatures", previousProbes)
        }
    }

    class ExcludedSignatureProbe : com.jcraft.jsch.jce.SignatureRSASHA256() {
        override fun init() {
            throw AssertionError("Excluded optional signature probe was initialized")
        }
    }

    @Test
    fun changedPinCannotAuthenticateEvenWhenLegacyTrustFlagIsEnabled() = runBlocking {
        server().use { peer ->
            val socket = Socket()
            val client = JschRemoteShellClientFactory { socket }.create(peer.connection(pin = ZERO_PIN, trust = true))
            try {
                assertSafeFailure(runCatching { withTimeout(TEST_TIMEOUT) { client.execute("success") } }.exceptionOrNull())
                assertEquals(0, peer.passwordAttempts.get())
                assertEquals(0, peer.keyAttempts.get())
            } finally { client.close() }
            assertTrue(socket.isClosed)
        }
    }

    @Test
    fun numericIpv6LoopbackRunsRealPinnedExecAndSftp() = runBlocking {
        val ipv6Loopback = java.net.InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 })
        val supported = runCatching { ServerSocket(0, 1, ipv6Loopback).use { } }.isSuccess
        assumeTrue("JVM has no IPv6 loopback listener", supported)
        SshTestServer(temporaryFolder.root.toPath().resolve("server"), host = "::1").use { peer ->
            val client = JschRemoteShellClientFactory().create(peer.connection())
            try {
                assertEquals(0, withTimeout(TEST_TIMEOUT) { client.execute("success") }.exitCode)
                assertEquals(0, withTimeout(TEST_TIMEOUT) { client.upload("/ipv6.sh", "synthetic IPv6 content") }.exitCode)
                assertEquals("synthetic IPv6 content", String(Files.readAllBytes(
                    temporaryFolder.root.toPath().resolve("server/ipv6.sh")), Charsets.UTF_8))
                assertEquals(1, peer.passwordAttempts.get())
            } finally { client.close() }
        }
    }

    @Test
    fun ecdsaServerHostKeyNegotiatesSeparatelyFromPasswordAuthentication() = runBlocking {
        SshTestServer(temporaryFolder.root.toPath().resolve("server"), ecdsaHostKey = true).use { peer ->
            val client = JschRemoteShellClientFactory().create(peer.connection())
            try {
                assertEquals(0, withTimeout(TEST_TIMEOUT) { client.execute("success") }.exitCode)
                assertEquals(1, peer.passwordAttempts.get())
            } finally { client.close() }
        }
    }

    @Test
    fun rsaSha1OnlyServerCannotTriggerAutomaticLegacyAlgorithmFallback() = runBlocking {
        SshTestServer(temporaryFolder.root.toPath().resolve("server"), legacyRsaOnly = true).use { peer ->
            val client = JschRemoteShellClientFactory().create(peer.connection())
            try {
                assertSafeFailure(runCatching { withTimeout(TEST_TIMEOUT) { client.execute("success") } }.exceptionOrNull())
                assertEquals(0, peer.passwordAttempts.get())
            } finally { client.close() }
        }
    }

    @Test
    fun sha1OnlyMacWithCtrCipherCannotTriggerLegacyMacFallback() = runBlocking {
        SshTestServer(temporaryFolder.root.toPath().resolve("server"), sha1MacOnly = true).use { peer ->
            val client = JschRemoteShellClientFactory().create(peer.connection())
            try {
                assertSafeFailure(runCatching { withTimeout(TEST_TIMEOUT) { client.execute("success") } }.exceptionOrNull())
                assertEquals(0, peer.passwordAttempts.get())
            } finally { client.close() }
        }
    }

    @Test
    fun rsaAndEcdsaEncryptedAndUnencryptedPrivateKeysAuthenticateThroughRealLibrary() = runBlocking {
        for (type in listOf(KeyPair.RSA, KeyPair.ECDSA)) {
            for (passphrase in listOf<String?>(null, "synthetic-passphrase")) {
                val identity = identity(type, passphrase)
                server().use { peer ->
                    peer.allowedPublicKey = identity.publicKey
                    val client = JschRemoteShellClientFactory().create(peer.connection(identity.credential))
                    try {
                        assertEquals(0, withTimeout(TEST_TIMEOUT) { client.execute("success") }.exitCode)
                        assertEquals(0, peer.passwordAttempts.get())
                        assertTrue(peer.keyAttempts.get() > 0)
                    } finally { client.close() }
                    awaitNoSessions(peer)
                }
            }
        }
    }

    @Test
    fun wrongPasswordInvalidKeyAndWrongPassphraseReportSafeFailures() = runBlocking {
        val encrypted = identity(KeyPair.RSA, "synthetic-passphrase")
        server().use { peer ->
            for (credential in listOf(
                VpsCredential.Password(SshTestServer.SECRET_MARKER.toCharArray()),
                VpsCredential.PrivateKey(SshTestServer.SECRET_MARKER),
                encrypted.credential.copy(passphrase = SshTestServer.SECRET_MARKER),
            )) {
                val client = JschRemoteShellClientFactory().create(peer.connection(credential))
                try {
                    assertSafeFailure(runCatching { withTimeout(TEST_TIMEOUT) { client.execute("success") } }.exceptionOrNull())
                } finally { client.close() }
            }
            assertEquals(1, peer.passwordAttempts.get())
            assertEquals(0, peer.keyAttempts.get())
        }
    }

    @Test
    fun uploadSendsExactUtf8ContentAndAppliesRequestedMode() = runBlocking {
        server().use { peer ->
            val directory = temporaryFolder.root.toPath().resolve("server")
            val client = JschRemoteShellClientFactory().create(peer.connection())
            val content = "#!/bin/sh\nprintf 'synthetic café ✓\\n'\n"
            try {
                val output = withTimeout(TEST_TIMEOUT) { client.upload("/uploaded.sh", content, "0700") }
                assertEquals(0, output.exitCode)
                assertEquals(content, String(Files.readAllBytes(directory.resolve("uploaded.sh")), Charsets.UTF_8))
                assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(directory.resolve("uploaded.sh")))
            } finally { client.close() }
        }
    }

    @Test
    fun rejectedChmodCannotReportUploadSuccessOrLeakServerPayload() = runBlocking {
        server().use { peer ->
            peer.failChmod = true
            val client = JschRemoteShellClientFactory().create(peer.connection())
            try {
                assertSafeFailure(runCatching {
                    withTimeout(TEST_TIMEOUT) { client.upload("/uploaded.sh", "synthetic content") }
                }.exceptionOrNull())
            } finally { client.close() }
        }
    }

    @Test
    fun missingExitStatusCannotReportSuccess() = runBlocking {
        server().use { peer ->
            val client = JschRemoteShellClientFactory().create(peer.connection())
            try {
                assertSafeFailure(runCatching { withTimeout(TEST_TIMEOUT) { client.execute("missing-status") } }.exceptionOrNull())
            } finally { client.close() }
        }
    }

    @Test
    fun stdoutAndStderrCapsAcceptExactBoundaryAndRejectOneExtraByte() = runBlocking {
        for ((stream, limit) in listOf(
            "stdout" to JschRemoteShellClientFactory.MAX_STDOUT_BYTES,
            "stderr" to JschRemoteShellClientFactory.MAX_STDERR_BYTES,
        )) {
            server().use { peer ->
                val client = JschRemoteShellClientFactory().create(peer.connection())
                try {
                    val output = withTimeout(TEST_TIMEOUT) { client.execute("$stream:$limit") }
                    assertEquals(limit, if (stream == "stdout") output.stdout.length else output.stderr.length)
                    assertSafeFailure(runCatching {
                        withTimeout(TEST_TIMEOUT) { client.execute("$stream:${limit + 1}") }
                    }.exceptionOrNull())
                } finally { client.close() }
            }
        }
    }

    @Test
    fun blockedPreReturnConnectBannerReadAndBannerWriteCancelAndDrainBeforeReturning() = runBlocking {
        for (stage in BlockingStage.entries) {
            val socket = GateSocket(stage)
            val client = JschRemoteShellClientFactory { socket }.create(offlineConnection())
            supervisorScope {
                val operation = async(Dispatchers.IO) { client.execute("success") }
                try {
                    awaitEntered(socket.entered)
                    withTimeout(TEST_TIMEOUT) { operation.cancelAndJoin() }
                    assertTrue(operation.isCancelled)
                    assertTrue(socket.isClosed)
                    assertTrue(socket.exited.await(1, TimeUnit.SECONDS))
                } finally {
                    socket.close()
                    operation.cancelAndJoin()
                    client.close()
                }
            }
        }
    }

    @Test
    fun explicitCloseAbortsEnteredReadSettlesOperationAndPreventsReconnect() = runBlocking {
        val socket = GateSocket(BlockingStage.READ)
        var creations = 0
        val client = JschRemoteShellClientFactory { creations++; socket }.create(offlineConnection())
        supervisorScope {
            val operation = async(Dispatchers.IO) { runCatching { client.execute("success") } }
            try {
                awaitEntered(socket.entered)
                withTimeout(TEST_TIMEOUT) { client.close() }
                assertSafeFailure(withTimeout(TEST_TIMEOUT) { operation.await() }.exceptionOrNull())
                assertTrue(socket.isClosed)
                assertTrue(socket.exited.await(1, TimeUnit.SECONDS))
                assertSafeFailure(runCatching { client.execute("success") }.exceptionOrNull())
                assertEquals(1, creations)
            } finally {
                socket.close()
                operation.cancelAndJoin()
                client.close()
            }
        }
    }

    @Test
    fun ownedGuardTimeoutAndEnclosingTimeoutRemainDistinctAndDrainBlockedWork() = runBlocking {
        for (enclosing in listOf(false, true)) {
            val socket = GateSocket(BlockingStage.CONNECT)
            val delegate = JschRemoteShellClientFactory { socket }.create(offlineConnection())
            val client = if (enclosing) delegate else GuardedRemoteShellClient(delegate, timeoutMillis = 1_000L)
            try {
                val result = runCatching {
                    if (enclosing) withTimeout(1_000L) { client.execute("success") } else client.execute("success")
                }
                assertTrue(socket.entered.count == 0L)
                if (enclosing) assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
                else {
                    assertTrue(result.exceptionOrNull() is ProvisioningException)
                    assertEquals("Remote command timed out", result.exceptionOrNull()?.message)
                }
                assertTrue(socket.isClosed)
                assertTrue(socket.exited.await(1, TimeUnit.SECONDS))
            } finally { client.close() }
        }
    }

    @Test
    fun cancellationOfEnteredCommandClosesAuthenticatedSession() = runBlocking {
        server().use { peer ->
            val socket = Socket()
            val client = JschRemoteShellClientFactory { socket }.create(peer.connection())
            supervisorScope {
                val operation = async(Dispatchers.IO) { client.execute("hang") }
                try {
                    awaitEntered(peer.commandEntered)
                    val threads = liveSessionThreads()
                    withTimeout(TEST_TIMEOUT) { operation.cancelAndJoin() }
                    assertTrue(operation.isCancelled)
                    assertTrue(socket.isClosed)
                    assertThreadsStopped(threads)
                    awaitNoSessions(peer)
                } finally {
                    operation.cancelAndJoin()
                    client.close()
                }
            }
        }
    }

    @Test
    fun blockedAuthenticatedCommandAndUploadWritesCancelWithoutWaitingForLibraryDisconnect() = runBlocking {
        for (upload in listOf(false, true)) {
            server().use { peer ->
                val socket = GatedWriteSocket()
                val client = JschRemoteShellClientFactory { socket }.create(peer.connection())
                try {
                    assertEquals(0, withTimeout(TEST_TIMEOUT) { client.execute("success") }.exitCode)
                    val threads = liveSessionThreads()
                    socket.blockWrites.set(true)
                    supervisorScope {
                        val operation = async(Dispatchers.IO) {
                            if (upload) client.upload("/uploaded.sh", "synthetic content") else client.execute("success")
                        }
                        try {
                            awaitEntered(socket.entered)
                            withTimeout(TEST_TIMEOUT) { operation.cancelAndJoin() }
                            assertTrue(operation.isCancelled)
                            assertTrue(socket.isClosed)
                            assertTrue(socket.exited.await(1, TimeUnit.SECONDS))
                            assertThreadsStopped(threads)
                        } finally {
                            socket.close()
                            operation.cancelAndJoin()
                        }
                    }
                } finally { client.close() }
                awaitNoSessions(peer)
            }
        }
    }

    @Test
    fun blockedSftpWriteAcknowledgementCancelsAndClosesBeforeFixtureResponds() = runBlocking {
        server().use { peer ->
            peer.hangUpload = true
            val socket = Socket()
            val client = JschRemoteShellClientFactory { socket }.create(peer.connection())
            supervisorScope {
                val operation = async(Dispatchers.IO) { client.upload("/uploaded.sh", "synthetic content") }
                try {
                    awaitEntered(peer.uploadEntered)
                    val threads = liveSessionThreads()
                    withTimeout(TEST_TIMEOUT) { operation.cancelAndJoin() }
                    assertTrue(operation.isCancelled)
                    assertTrue(socket.isClosed)
                    assertEquals(1L, peer.releaseUpload.count)
                    assertThreadsStopped(threads)
                } finally {
                    peer.releaseUpload.countDown()
                    operation.cancelAndJoin()
                    client.close()
                }
            }
        }
    }

    @Test
    fun withheldChannelOpenConfirmationCannotDelayCommandOrUploadCancellation() = runBlocking {
        for (upload in listOf(false, true)) {
            server().use { peer ->
                peer.hangChannelOpen = true
                val socket = Socket()
                val client = JschRemoteShellClientFactory { socket }.create(peer.connection())
                supervisorScope {
                    val operation = async(Dispatchers.IO) {
                        if (upload) client.upload("/uploaded.sh", "synthetic content") else client.execute("success")
                    }
                    try {
                        awaitEntered(peer.channelOpenEntered)
                        val threads = liveSessionThreads()
                        withTimeout(3_000L) { operation.cancelAndJoin() }
                        assertTrue(operation.isCancelled)
                        assertTrue(socket.isClosed)
                        assertEquals(1L, peer.releaseChannelOpen.count)
                        assertThreadsStopped(threads)
                    } finally {
                        peer.releaseChannelOpen.countDown()
                        socket.close()
                        operation.cancelAndJoin()
                        client.close()
                    }
                }
            }
        }
    }

    @Test
    fun expectedConnectFailureDropsPayloadButProgrammingErrorPropagatesAndCloses() = runBlocking {
        for (failure in listOf(IOException(SshTestServer.SECRET_MARKER), AssertionError("synthetic defect"))) {
            val socket = object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) { throw failure }
            }
            val client = JschRemoteShellClientFactory { socket }.create(offlineConnection())
            try {
                val result = runCatching { withTimeout(TEST_TIMEOUT) { client.execute("success") } }
                if (failure is AssertionError) assertOriginalError(failure, result.exceptionOrNull())
                else assertSafeFailure(result.exceptionOrNull())
                assertTrue(socket.isClosed)
            } finally { client.close() }
        }
    }

    @Test
    fun realSessionReaderErrorSurvivesChannelOpenAndClosesOwnedWorkers() = runBlocking {
        server().use { peer ->
            val defect = AssertionError("synthetic reader defect")
            val socket = ErrorReadSocket(defect)
            val client = JschRemoteShellClientFactory { socket }.create(peer.connection())
            try {
                assertEquals(0, withTimeout(TEST_TIMEOUT) { client.execute("success") }.exitCode)
                val threads = liveSessionThreads()
                socket.failNextRead.set(true)
                val result = runCatching { withTimeout(3_000L) { client.execute("success") } }
                assertOriginalError(defect, result.exceptionOrNull())
                assertTrue(socket.isClosed)
                assertThreadsStopped(threads)
            } finally {
                runCatching { client.close() }.exceptionOrNull()?.let { assertOriginalError(defect, it) }
            }
        }
    }

    @Test
    fun unexpectedSocketCloseDefectCannotReplaceOriginalErrorOrSkipRawClosure() = runBlocking {
        val original = AssertionError("synthetic connect defect")
        val cleanup = AssertionError("synthetic close defect")
        val failedClose = AtomicBoolean()
        val socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) { throw original }
            override fun close() {
                super.close()
                if (failedClose.compareAndSet(false, true)) throw cleanup
            }
        }
        val client = JschRemoteShellClientFactory { socket }.create(offlineConnection())
        try {
            val result = runCatching { withTimeout(TEST_TIMEOUT) { client.execute("success") } }
            assertOriginalError(original, result.exceptionOrNull())
            val cleanupRetained = generateSequence(result.exceptionOrNull()) { it.cause }.any { cause ->
                cause.suppressed.any { suppressed -> generateSequence(suppressed) { it.cause }.last() === cleanup }
            }
            assertTrue("Cleanup defect was not retained on original Error", cleanupRetained)
            assertTrue(socket.isClosed)
        } finally {
            runCatching { client.close() }.exceptionOrNull()?.let { assertOriginalError(cleanup, it) }
        }
    }

    @Test
    fun readerErrorDuringEnteredCommandPollingPreservesErrorAndDrainsWorkers() = runBlocking {
        server().use { peer ->
            val original = AssertionError("synthetic polling reader defect")
            val socket = ErrorReadSocket(original)
            val client = JschRemoteShellClientFactory { socket }.create(peer.connection())
            supervisorScope {
                val operation = async(Dispatchers.IO) { runCatching { client.execute("hang") } }
                try {
                    awaitEntered(peer.commandEntered)
                    awaitCommandPolling()
                    val threads = liveSessionThreads()
                    socket.failNextRead.set(true)
                    peer.releaseHungOutput.countDown()
                    val result = withTimeout(TEST_TIMEOUT) { operation.await() }
                    assertOriginalError(original, result.exceptionOrNull())
                    assertTrue(socket.isClosed)
                    assertThreadsStopped(threads)
                } finally {
                    peer.releaseHungOutput.countDown()
                    socket.close()
                    operation.cancelAndJoin()
                    runCatching { client.close() }.exceptionOrNull()?.let { assertOriginalError(original, it) }
                }
            }
        }
    }

    @Test
    fun sameConnectAndCloseErrorCannotCauseSelfSuppression() = runBlocking {
        val original = AssertionError("synthetic same defect")
        val failedClose = AtomicBoolean()
        val socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) { throw original }
            override fun close() {
                super.close()
                if (failedClose.compareAndSet(false, true)) throw original
            }
        }
        val client = JschRemoteShellClientFactory { socket }.create(offlineConnection())
        try {
            val result = runCatching { withTimeout(TEST_TIMEOUT) { client.execute("success") } }
            assertOriginalError(original, result.exceptionOrNull())
            val selfSuppressed = generateSequence(result.exceptionOrNull()) { it.cause }.any { cause ->
                cause.suppressed.any { suppressed -> generateSequence(suppressed) { it.cause }.last() === original }
            }
            assertFalse("Original Error was also attached as its own cleanup failure", selfSuppressed)
            assertTrue(socket.isClosed)
        } finally {
            runCatching { client.close() }.exceptionOrNull()?.let { assertOriginalError(original, it) }
        }
    }

    @Test
    fun idleReaderErrorAndDistinctCloseDefectRemainObservableOnClose() = runBlocking {
        server().use { peer ->
            val original = AssertionError("synthetic idle reader defect")
            val cleanup = AssertionError("synthetic idle close defect")
            val socket = ErrorReadSocket(original, cleanup)
            val client = JschRemoteShellClientFactory { socket }.create(peer.connection())
            try {
                assertEquals(0, withTimeout(TEST_TIMEOUT) { client.execute("success") }.exitCode)
                val threads = liveSessionThreads()
                socket.failNextRead.set(true)
                peer.sendIdleMessage()
                withTimeout(TEST_TIMEOUT) {
                    while (!socket.isClosed) delay(1)
                }
                val result = runCatching { withTimeout(TEST_TIMEOUT) { client.close() } }
                assertOriginalError(original, result.exceptionOrNull())
                assertTrue(generateSequence(result.exceptionOrNull()) { it.cause }.any { cause ->
                    cause.suppressed.any { suppressed -> generateSequence(suppressed) { it.cause }.last() === cleanup }
                })
                assertThreadsStopped(threads)
            } finally {
                runCatching { client.close() }.exceptionOrNull()?.let { assertOriginalError(original, it) }
            }
        }
    }

    @Test
    fun readerErrorDuringEnteredSftpUploadCannotBecomeExpectedTransferFailure() = runBlocking {
        server().use { peer ->
            peer.hangUpload = true
            val original = AssertionError("synthetic upload reader defect")
            val socket = ErrorReadSocket(original)
            val client = JschRemoteShellClientFactory { socket }.create(peer.connection())
            supervisorScope {
                val operation = async(Dispatchers.IO) { runCatching { client.upload("/uploaded.sh", "synthetic content") } }
                try {
                    awaitEntered(peer.uploadEntered)
                    val threads = liveSessionThreads()
                    socket.failNextRead.set(true)
                    peer.releaseUpload.countDown()
                    val result = withTimeout(TEST_TIMEOUT) { operation.await() }
                    assertOriginalError(original, result.exceptionOrNull())
                    assertTrue(socket.isClosed)
                    assertThreadsStopped(threads)
                } finally {
                    peer.releaseUpload.countDown()
                    socket.close()
                    operation.cancelAndJoin()
                    runCatching { client.close() }.exceptionOrNull()?.let { assertOriginalError(original, it) }
                }
            }
        }
    }

    @Test
    fun refusedLoopbackConnectionUsesRealSocketAndClosesWithSafeFailure() = runBlocking {
        val unavailablePort = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }
        val socket = Socket()
        val client = JschRemoteShellClientFactory { socket }.create(
            offlineConnection().copy(endpoint = VpsEndpoint("127.0.0.1", unavailablePort, SshTestServer.USER)),
        )
        try {
            assertSafeFailure(runCatching { withTimeout(TEST_TIMEOUT) { client.execute("success") } }.exceptionOrNull())
            assertTrue(socket.isClosed)
        } finally { client.close() }
    }

    private fun server() = SshTestServer(temporaryFolder.root.toPath().resolve("server"))

    private fun offlineConnection() = VpsConnection(
        VpsEndpoint("127.0.0.1", 22222, SshTestServer.USER),
        VpsCredential.Password(SshTestServer.PASSWORD.toCharArray()), false, ZERO_PIN,
    )

    private suspend fun awaitEntered(latch: CountDownLatch) = withContext(Dispatchers.IO) {
        assertTrue("Real-library operation did not enter expected blocking boundary", latch.await(5, TimeUnit.SECONDS))
    }

    private suspend fun awaitNoSessions(peer: SshTestServer) {
        withTimeout(TEST_TIMEOUT) {
            while (peer.activeSessions != 0) delay(10)
        }
    }

    private suspend fun awaitCommandPolling() {
        withTimeout(TEST_TIMEOUT) {
            while (Thread.getAllStackTraces().values.none { stack ->
                stack.any { it.className == "java.lang.Thread" && it.methodName.startsWith("sleep") } &&
                    stack.any { it.className.startsWith("com.vpn0wn.android.provisioning.JschRemoteShellClient") } &&
                    stack.none { it.className.startsWith("com.jcraft.jsch.") }
            }) delay(1)
        }
    }

    private fun liveSessionThreads(): List<Thread> {
        val threads = Thread.getAllStackTraces().keys.filter { it.name == "Connect thread 127.0.0.1 session" && it.isAlive }
        assertTrue("Expected real JSch session worker", threads.isNotEmpty())
        return threads
    }

    private fun assertThreadsStopped(threads: List<Thread>) {
        assertTrue("Owned JSch session worker remained alive after settlement", threads.none { it.isAlive })
    }

    private fun assertSafeFailure(failure: Throwable?) {
        assertTrue("Expected transport failure, got ${failure?.javaClass?.simpleName}", failure is ProvisioningException)
        val rendered = failure!!.stackTraceToString()
        for (secret in listOf(SshTestServer.SECRET_MARKER, SshTestServer.PASSWORD, "synthetic-passphrase", "BEGIN RSA PRIVATE KEY")) {
            assertFalse("Failure retained synthetic secret marker", rendered.contains(secret))
        }
        assertEquals(null, (failure as ProvisioningException).output)
        assertEquals(null, failure.cause)
    }

    private fun assertOriginalError(expected: Error, actual: Throwable?) {
        assertTrue("Programming Error was converted to another failure", actual is Error)
        assertSame(expected, generateSequence(actual) { it.cause }.last())
    }

    private fun identity(type: Int, passphrase: String?): SyntheticIdentity {
        val key = KeyPair.genKeyPair(JSch(), type, if (type == KeyPair.RSA) 2048 else 256)
        try {
            val privateKey = ByteArrayOutputStream()
            key.writePrivateKey(privateKey, passphrase?.toByteArray())
            val algorithm = if (type == KeyPair.RSA) "ssh-rsa" else "ecdsa-sha2-nistp256"
            return SyntheticIdentity(
                VpsCredential.PrivateKey(privateKey.toString(Charsets.UTF_8), passphrase),
                "$algorithm ${Base64.getEncoder().encodeToString(key.publicKeyBlob)}",
            )
        } finally { key.dispose() }
    }

    private class SyntheticIdentity(val credential: VpsCredential.PrivateKey, val publicKey: String)
    private enum class BlockingStage { CONNECT, READ, WRITE }

    private class GateSocket(private val stage: BlockingStage) : Socket() {
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        private val released = CountDownLatch(1)
        private val closed = AtomicBoolean()

        override fun connect(endpoint: SocketAddress, timeout: Int) { if (stage == BlockingStage.CONNECT) block() }
        override fun setTcpNoDelay(on: Boolean) = Unit
        override fun setSoTimeout(timeout: Int) = Unit
        override fun getInputStream(): InputStream = object : InputStream() {
            override fun read(): Int { block(); return -1 }
        }
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) { if (stage == BlockingStage.WRITE) block() }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { if (stage == BlockingStage.WRITE) block() }
        }
        private fun block() {
            entered.countDown()
            try { check(released.await(10, TimeUnit.SECONDS)) }
            finally { exited.countDown() }
            throw SocketException(SshTestServer.SECRET_MARKER)
        }
        override fun isClosed(): Boolean = closed.get()
        override fun close() { closed.set(true); released.countDown() }
    }

    private class GatedWriteSocket : Socket() {
        val blockWrites = AtomicBoolean()
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        private val released = CountDownLatch(1)
        override fun getOutputStream(): OutputStream {
            val delegate = super.getOutputStream()
            return object : OutputStream() {
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (blockWrites.get()) {
                        entered.countDown()
                        try { check(released.await(10, TimeUnit.SECONDS)) }
                        finally { exited.countDown() }
                        throw SocketException(SshTestServer.SECRET_MARKER)
                    }
                    delegate.write(bytes, offset, length)
                }
                override fun flush() { delegate.flush() }
            }
        }
        override fun close() { released.countDown(); super.close() }
    }

    private class ErrorReadSocket(private val defect: AssertionError, private val closeDefect: Error? = null) : Socket() {
        val failNextRead = AtomicBoolean()
        private val failedClose = AtomicBoolean()
        override fun getInputStream(): InputStream {
            val delegate = super.getInputStream()
            return object : InputStream() {
                override fun read(): Int {
                    val value = delegate.read()
                    if (value >= 0 && failNextRead.compareAndSet(true, false)) throw defect
                    return value
                }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    val count = delegate.read(bytes, offset, length)
                    if (count > 0 && failNextRead.compareAndSet(true, false)) throw defect
                    return count
                }
            }
        }
        override fun close() {
            super.close()
            closeDefect?.let { if (failedClose.compareAndSet(false, true)) throw it }
        }
    }

    companion object {
        private const val TEST_TIMEOUT = 10_000L
        private val ZERO_PIN = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32))
    }
}
