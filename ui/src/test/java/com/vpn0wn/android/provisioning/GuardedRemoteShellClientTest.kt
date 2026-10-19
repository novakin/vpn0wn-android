/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.provisioning

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuardedRemoteShellClientTest {
    @Test fun streamedProgressCannotBypassTheExistingOperationDeadline() = runTest {
        var settled = false
        val phases = mutableListOf<VpsInstallProgress>()
        val client = GuardedRemoteShellClient(object : RemoteShellClient {
            override suspend fun close() = Unit
            override suspend fun execute(command: String) = success()
            override suspend fun execute(command: String, onProgress: (VpsInstallProgress) -> Unit): ProvisioningCommandOutput {
                try {
                    onProgress(VpsInstallProgress.PREPARING)
                    delay(Long.MAX_VALUE)
                    error("unreachable")
                } finally { settled = true }
            }
            override suspend fun upload(path: String, content: String, mode: String) = success()
        }, timeoutMillis = 1000L)
        val failure = runCatching { client.execute("install", phases::add) }.exceptionOrNull()
        assertTrue(failure is ProvisioningException)
        assertEquals(1000L, testScheduler.currentTime)
        assertEquals(listOf(VpsInstallProgress.PREPARING), phases)
        assertTrue(settled)
    }

    @Test
    fun executePreservesEnclosingTimeoutCancellation() = runTest {
        var settled = false
        val client = GuardedRemoteShellClient(
            object : RemoteShellClient {
                override suspend fun close() = Unit
                override suspend fun execute(command: String): ProvisioningCommandOutput {
                    try {
                        delay(Long.MAX_VALUE)
                        error("unreachable")
                    } finally {
                        settled = true
                    }
                }

                override suspend fun upload(path: String, content: String, mode: String) = success()
            },
            timeoutMillis = 60_000L,
        )

        val result = runCatching { withTimeout(1_000L) { client.execute("detect") } }

        assertTrue("Expected caller timeout, got ${result.exceptionOrNull()?.javaClass?.simpleName}",
            result.exceptionOrNull() is TimeoutCancellationException)
        assertTrue(settled)
        assertEquals(1_000L, testScheduler.currentTime)
    }

    @Test
    fun uploadPreservesEnclosingTimeoutCancellation() = runTest {
        var settled = false
        val client = GuardedRemoteShellClient(
            object : RemoteShellClient {
                override suspend fun close() = Unit
                override suspend fun execute(command: String) = success()

                override suspend fun upload(path: String, content: String, mode: String): ProvisioningCommandOutput {
                    try {
                        delay(Long.MAX_VALUE)
                        error("unreachable")
                    } finally {
                        settled = true
                    }
                }
            },
            timeoutMillis = 60_000L,
        )

        val result = runCatching { withTimeout(1_000L) { client.upload("/tmp/fixture.sh", "synthetic script") } }

        assertTrue("Expected caller timeout, got ${result.exceptionOrNull()?.javaClass?.simpleName}",
            result.exceptionOrNull() is TimeoutCancellationException)
        assertTrue(settled)
        assertEquals(1_000L, testScheduler.currentTime)
    }

    @Test
    fun uploadTimesOutHungTransferAndSettlesDelegate() = runTest {
        var settled = false
        val client = GuardedRemoteShellClient(
            object : RemoteShellClient {
                override suspend fun close() = Unit
                override suspend fun execute(command: String) = success()

                override suspend fun upload(path: String, content: String, mode: String): ProvisioningCommandOutput {
                    try {
                        delay(Long.MAX_VALUE)
                        error("unreachable")
                    } finally {
                        settled = true
                    }
                }
            },
            timeoutMillis = 1_000L,
        )

        val result = runCatching { client.upload("/tmp/fixture.sh", "synthetic script") }

        assertTrue(result.exceptionOrNull() is ProvisioningException)
        assertEquals("Remote upload timed out", result.exceptionOrNull()?.message)
        assertTrue(settled)
        assertEquals(1_000L, testScheduler.currentTime)
    }

    @Test
    fun successfulExecuteAndUploadKeepArgumentsAndOutput() = runTest {
        val commandOutput = ProvisioningCommandOutput(0, "command output", "command stderr")
        val uploadOutput = ProvisioningCommandOutput(0, "upload output", "upload stderr")
        val client = GuardedRemoteShellClient(
            object : RemoteShellClient {
                override suspend fun close() = Unit
                override suspend fun execute(command: String): ProvisioningCommandOutput {
                    assertEquals("detect", command)
                    return commandOutput
                }

                override suspend fun upload(path: String, content: String, mode: String): ProvisioningCommandOutput {
                    assertEquals("/tmp/fixture.sh", path)
                    assertEquals("synthetic script", content)
                    assertEquals("0700", mode)
                    return uploadOutput
                }
            },
            timeoutMillis = 1_000L,
        )

        assertSame(commandOutput, client.execute("detect"))
        assertSame(uploadOutput, client.upload("/tmp/fixture.sh", "synthetic script"))
    }

    @Test
    fun uploadIoFailureKeepsExistingMessageAndCause() = runTest {
        val failure = IOException("network is unreachable")
        val client = GuardedRemoteShellClient(
            object : RemoteShellClient {
                override suspend fun close() = Unit
                override suspend fun execute(command: String) = success()
                override suspend fun upload(path: String, content: String, mode: String): ProvisioningCommandOutput = throw failure
            },
        )

        val result = runCatching { client.upload("/tmp/fixture.sh", "synthetic script") }

        assertTrue(result.exceptionOrNull() is ProvisioningException)
        assertEquals("Remote upload failed: network is unreachable", result.exceptionOrNull()?.message)
        assertEquals(failure.javaClass, result.exceptionOrNull()?.cause?.javaClass)
        assertEquals(failure.message, result.exceptionOrNull()?.cause?.message)
    }

    @Test
    fun executeTimesOutHungCommand() = runTest {
        val client = GuardedRemoteShellClient(
            object : RemoteShellClient {
                override suspend fun close() = Unit
                override suspend fun execute(command: String): ProvisioningCommandOutput {
                    delay(Long.MAX_VALUE)
                    error("unreachable")
                }

                override suspend fun upload(path: String, content: String, mode: String) = success()
            },
            timeoutMillis = 1_000L,
        )

        val result = runCatching { client.execute("sleep forever") }

        assertTrue(result.exceptionOrNull() is ProvisioningException)
        assertEquals("Remote command timed out", result.exceptionOrNull()?.message)
    }

    @Test
    fun unreachableServerMapsToProvisioningException() = runTest {
        val client = GuardedRemoteShellClient(
            object : RemoteShellClient {
                override suspend fun close() = Unit
                override suspend fun execute(command: String): ProvisioningCommandOutput {
                    throw IOException("network is unreachable")
                }

                override suspend fun upload(path: String, content: String, mode: String) = success()
            },
        )

        val result = runCatching { client.execute("detect") }

        assertTrue(result.exceptionOrNull() is ProvisioningException)
        assertEquals("Remote command failed: network is unreachable", result.exceptionOrNull()?.message)
    }

    @Test
    fun coroutineCancellationIsNotConvertedToGenericFailure() = runTest {
        val client = GuardedRemoteShellClient(
            object : RemoteShellClient {
                override suspend fun close() = Unit
                override suspend fun execute(command: String): ProvisioningCommandOutput {
                    delay(Long.MAX_VALUE)
                    error("unreachable")
                }

                override suspend fun upload(path: String, content: String, mode: String) = success()
            },
            timeoutMillis = 60_000L,
        )
        val job = async { client.execute("sleep forever") }

        job.cancel()
        val result = runCatching { job.await() }

        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test
    fun factoryRejectsUntrustedHostWithoutFingerprint() {
        val factory = GuardedRemoteShellClientFactory(
            object : RemoteShellClientFactory {
                override fun create(connection: VpsConnection): RemoteShellClient = fakeClient()
            },
        )

        val exception = assertThrows(ProvisioningException::class.java) {
            factory.create(connection(trustHostKey = false, hostKeyFingerprint = null))
        }

        assertEquals("SSH host fingerprint is required when host-key trust is disabled", exception.message)
    }

    @Test
    fun factoryRejectsInvalidAuthAttemptLimit() {
        val factory = GuardedRemoteShellClientFactory(
            object : RemoteShellClientFactory {
                override fun create(connection: VpsConnection): RemoteShellClient = fakeClient()
            },
        )

        val exception = assertThrows(ProvisioningException::class.java) {
            factory.create(connection(maxAuthAttempts = 0))
        }

        assertEquals("SSH auth attempt limit must be at least 1", exception.message)
    }

    @Test
    fun factoryDiscoveryDelegatesWithoutCreatingAnAuthenticatedClient() = runTest {
        val factory = GuardedRemoteShellClientFactory(object : RemoteShellClientFactory {
            override fun create(connection: VpsConnection): RemoteShellClient = error("Unexpected authentication")
            override suspend fun discoverHostKey(host: String, port: Int): String {
                assertEquals("198.51.100.10", host)
                assertEquals(2222, port)
                return "synthetic fingerprint"
            }
        })
        assertEquals("synthetic fingerprint", factory.discoverHostKey("198.51.100.10", 2222))
    }

    @Test
    fun factoryDiscoveryTimeoutSettlesDelegateAndPreservesEnclosingCancellation() = runTest {
        for (enclosing in listOf(false, true)) {
            var settled = false
            val factory = GuardedRemoteShellClientFactory(object : RemoteShellClientFactory {
                override fun create(connection: VpsConnection): RemoteShellClient = error("Unexpected authentication")
                override suspend fun discoverHostKey(host: String, port: Int): String {
                    try {
                        delay(Long.MAX_VALUE)
                        error("unreachable")
                    } finally { settled = true }
                }
            }, timeoutMillis = if (enclosing) 60_000L else 1_000L)
            val result = runCatching { withTimeout(2_000L) { factory.discoverHostKey("198.51.100.10", 22) } }
            if (enclosing) assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
            else {
                assertTrue(result.exceptionOrNull() is ProvisioningException)
                assertEquals("SSH host-key discovery timed out", result.exceptionOrNull()?.message)
            }
            assertTrue(settled)
        }
    }

    @Test
    fun passwordCredentialCanBeCleared() {
        val password = VpsCredential.Password(charArrayOf('s', 'e', 'c', 'r', 'e', 't'))

        password.clear()

        assertTrue(password.value.all { it == '\u0000' })
    }

    private fun success() = ProvisioningCommandOutput(0, "{}", "")

    private fun connection(
        trustHostKey: Boolean = true,
        hostKeyFingerprint: String? = null,
        maxAuthAttempts: Int = 3,
    ) = VpsConnection(
        endpoint = VpsEndpoint("198.51.100.10"),
        credential = VpsCredential.Password(charArrayOf('s', 'e', 'c', 'r', 'e', 't')),
        trustHostKey = trustHostKey,
        hostKeyFingerprint = hostKeyFingerprint,
        maxAuthAttempts = maxAuthAttempts,
    )

    private fun fakeClient() = object : RemoteShellClient {
        override suspend fun close() = Unit
        override suspend fun execute(command: String) = success()
        override suspend fun upload(path: String, content: String, mode: String) = success()
    }
}
