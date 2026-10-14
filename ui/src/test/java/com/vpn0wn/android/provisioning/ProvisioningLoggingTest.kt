/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.provisioning

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
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first

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
        val payload = JSONObject().put("ok", true).put("name", "phone").put("address", "10.8.0.2/32")
            .put("publicKey", "synthetic-public-key").put("config", profile).toString()

        assertEquals(profile, ProvisioningJson.parseAddPeer(payload).config)
        assertFalse(logs().contains(secret))
    }

    @Test
    fun cleanupFailureDoesNotLogExceptionPayloadOrHideCompletedResult() = runBlocking {
        val secret = "synthetic-cleanup-secret"
        val remote = object : RemoteShellClient {
            override suspend fun close() = Unit
            override suspend fun execute(command: String): ProvisioningCommandOutput = when {
                command.startsWith("mktemp ") -> ProvisioningCommandOutput(0, "/tmp/vpn0wn-provisioning.test01", "")
                command.startsWith("rm -rf ") -> throw ProvisioningException(secret)
                else -> ProvisioningCommandOutput(0, """{"ok":true,"os":"debian","version":"12","supported":true,"packageManager":"apt","firewall":"iptables","wireGuardInstalled":true,"configExists":true,"interface":"wg0","endpoint":"vpn.example","listenPort":"51820","cidr":"10.8.0.0/24","ipv4Forwarding":"1"}""", "")
            }

            override suspend fun upload(path: String, content: String, mode: String) = ProvisioningCommandOutput(0, "", "")
        }
        val factory = object : RemoteShellClientFactory {
            override fun create(connection: VpsConnection) = remote
        }
        val client = Vpn0wnProvisioningClient(ApplicationProvider.getApplicationContext(), factory)
        val connection = VpsConnection(VpsEndpoint("203.0.113.10"), VpsCredential.Password(charArrayOf()), true)

        assertEquals("debian", client.detect(connection).os)
        assertFalse(logs().contains(secret))
    }

    private fun logs(): String = ShadowLog.getLogs().joinToString("\n") {
        it.msg + it.throwable?.stackTraceToString().orEmpty()
    }

    @Test
    fun nestedAndStructuredFailuresDoNotLogSecretPayloads() {
        val secret = "synthetic-remote-private-key"
        for (payload in listOf(
            """{"ok":false,"error":"unsupported_os","message":"$secret"}""",
            """{"ok":false,"error":"$secret","message":"$secret"}""",
            """{"ok":false,"error":{},"message":"$secret"}""",
            """{"ok":true,"interface":"wg0","running":true,"endpoint":"vpn.example","listenPort":"51820","cidr":"10.8.0.0/24","dns":"1.1.1.1","peers":["$secret"]}""",
        )) {
            val failure = assertThrows(ProvisioningException::class.java) { ProvisioningJson.parseStatus(payload) }
            assertFalse(failure.stackTraceToString().contains(secret))
            assertFalse(logs().contains(secret))
        }
    }

    @Test
    fun corruptStoreLogsOnlyItsCategory() = runBlocking {
        val secret = "synthetic-store-private-key"
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val file = File(System.getProperty("java.io.tmpdir"), "log-store-${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        try {
            dataStore.edit { it[stringPreferencesKey("vps_servers")] = """[{"secret":"$secret""" }
            assertEquals(emptyList<StoredVpsServer>(), VpsServerStore(dataStore).servers.first())
            assertFalse(logs().contains(secret))
        } finally {
            scope.cancel()
            file.delete()
        }
    }
}
