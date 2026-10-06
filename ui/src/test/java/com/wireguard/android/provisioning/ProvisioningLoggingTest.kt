/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.provisioning

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ProvisioningLoggingTest {
    @Before
    fun clearLogs() = ShadowLog.clear()

    @Test
    fun failedCommandsRetainOutputWithoutLoggingIt() {
        val secret = "synthetic-command-secret"
        for (output in listOf(
            ProvisioningCommandOutput(7, secret, ""),
            ProvisioningCommandOutput(7, "", secret),
        )) {
            val failure = assertThrows(ProvisioningException::class.java) { output.requireSuccess() }

            assertEquals("Remote command failed with exit code 7", failure.message)
            assertSame(output, failure.output)
            assertFalse(logs().contains(secret))
        }
    }

    @Test
    fun malformedProfileDoesNotLeakThroughLogsOrExceptionCause() {
        val secret = "synthetic-json-private-key"
        val failure = assertThrows(ProvisioningException::class.java) {
            ProvisioningJson.parseBackup("{\"ok\":true,\"config\":\"[Interface]\\nPrivateKey=$secret\"")
        }

        assertEquals("Remote command returned invalid JSON", failure.message)
        assertFalse(failure.stackTraceToString().contains(secret))
        assertFalse(logs().contains(secret))
    }

    @Test
    fun validProfileStillReturnsItsPrivateKeyWithoutLoggingIt() {
        val secret = "synthetic-profile-private-key"
        val profile = "[Interface]\nPrivateKey=$secret"
        val payload = JSONObject().put("ok", true).put("name", "phone").put("config", profile).toString()

        assertEquals(profile, ProvisioningJson.parseAddPeer(payload).config)
        assertFalse(logs().contains(secret))
    }

    @Test
    fun cleanupFailureDoesNotLogExceptionPayloadOrHideCompletedResult() = runBlocking {
        val secret = "synthetic-cleanup-secret"
        val remote = object : RemoteShellClient {
            override suspend fun execute(command: String): ProvisioningCommandOutput = when {
                command.startsWith("mktemp ") -> ProvisioningCommandOutput(0, "/tmp/tunnl-provisioning.test", "")
                command.startsWith("rm -rf ") -> throw ProvisioningException(secret)
                else -> ProvisioningCommandOutput(0, "{\"ok\":true,\"os\":\"debian\"}", "")
            }

            override suspend fun upload(path: String, content: String, mode: String) = ProvisioningCommandOutput(0, "", "")
        }
        val factory = object : RemoteShellClientFactory {
            override fun create(connection: VpsConnection) = remote
        }
        val client = TunnlProvisioningClient(ApplicationProvider.getApplicationContext(), factory)
        val connection = VpsConnection(VpsEndpoint("203.0.113.10"), VpsCredential.Password(charArrayOf()), true)

        assertEquals("debian", client.detect(connection).os)
        assertFalse(logs().contains(secret))
    }

    private fun logs(): String = ShadowLog.getLogs().joinToString("\n") {
        it.msg + it.throwable?.stackTraceToString().orEmpty()
    }
}
