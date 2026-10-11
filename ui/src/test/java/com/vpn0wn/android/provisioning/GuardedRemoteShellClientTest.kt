/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.provisioning

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuardedRemoteShellClientTest {
    @Test
    fun executeTimesOutHungCommand() = runTest {
        val client = GuardedRemoteShellClient(
            object : RemoteShellClient {
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
        override suspend fun execute(command: String) = success()
        override suspend fun upload(path: String, content: String, mode: String) = success()
    }
}
