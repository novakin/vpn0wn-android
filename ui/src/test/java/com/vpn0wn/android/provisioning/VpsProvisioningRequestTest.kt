/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.provisioning

import org.junit.Assert.assertEquals
import org.junit.Test
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class VpsProvisioningRequestTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    @Test
    fun installArgsIncludeRequiredFieldsInCommandContractOrder() {
        val request = VpsInstallRequest(
            endpoint = "203.0.113.10",
            cidr = "10.8.0.0/24",
            dns = "1.1.1.1",
            port = 51821,
        )

        assertEquals(
            listOf(
                "--endpoint", "203.0.113.10",
                "--port", "51821",
                "--cidr", "10.8.0.0/24",
                "--dns", "1.1.1.1",
            ),
            request.installArgs(),
        )
    }

    @Test
    fun installArgsAppendForceOnlyWhenRequested() {
        val request = VpsInstallRequest(
            endpoint = "203.0.113.10",
            cidr = "10.8.0.0/24",
            dns = "1.1.1.1",
            force = true,
        )

        assertEquals(
            listOf(
                "--endpoint", "203.0.113.10",
                "--port", "51820",
                "--cidr", "10.8.0.0/24",
                "--dns", "1.1.1.1",
                "--force",
            ),
            request.installArgs(),
        )
    }

    @Test
    fun addPeerArgsOmitDnsWhenUnset() {
        val request = VpsAddPeerRequest(
            name = "phone",
            allowedIps = "0.0.0.0/0,::/0",
        )

        assertEquals(
            listOf("--name", "phone", "--allowed-ips", "0.0.0.0/0,::/0"),
            request.args(),
        )
    }

    @Test
    fun addPeerArgsIncludeDnsWhenSet() {
        val request = VpsAddPeerRequest(
            name = "phone",
            allowedIps = "0.0.0.0/0,::/0",
            dns = "9.9.9.9",
        )

        assertEquals(
            listOf("--name", "phone", "--dns", "9.9.9.9", "--allowed-ips", "0.0.0.0/0,::/0"),
            request.args(),
        )
    }

    @Test
    fun clientCommandsPreserveArgumentsThroughRootAndSudoShellBranches() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connection = VpsConnection(VpsEndpoint("vpn.example"), VpsCredential.Password(charArrayOf()), true)
        val hostileValue = "phone's; \$(printf injected)\nnext line"
        for (root in listOf(true, false)) {
            val remote = RecordingShell(temporaryFolder.newFolder(), root)
            val factory = object : RemoteShellClientFactory {
                override fun create(connection: VpsConnection) = remote
            }
            val client = Vpn0wnProvisioningClient(context, factory)
            val install = VpsInstallRequest(hostileValue, "10.8.0.0/24", "1.1.1.1, example.internal", force = true)
            client.plan(connection, install)
            client.install(connection, install)
            client.addPeer(connection, VpsAddPeerRequest(hostileValue, "0.0.0.0/0, ::/0", "1.1.1.1"), REMOVE_PUBLIC_KEY)
            client.removePeer(connection, "phone", REMOVE_PUBLIC_KEY, REMOVE_PUBLIC_KEY)
            client.revokePeer(connection, "tablet", REVOKE_PUBLIC_KEY, REMOVE_PUBLIC_KEY, OPERATION_ID)
            client.syncStatus(connection)
            val backup = client.backup(connection)
            assertEquals(1, backup.snapshotVersion)
            assertEquals(backup.revokedCount, backup.revoked.size)
            assertEquals("tablet.conf.ABC123", backup.revoked.single().archiveName)
            client.uninstall(connection, purgePackages = true)
            val installArgs = listOf("--endpoint", hostileValue, "--port", "51820", "--cidr", "10.8.0.0/24", "--dns", "1.1.1.1, example.internal", "--force")
            assertEquals(installArgs, remote.arguments.getValue("plan.sh"))
            assertEquals(installArgs, remote.arguments.getValue("install.sh"))
            assertEquals(listOf("--name", hostileValue, "--dns", "1.1.1.1", "--allowed-ips", "0.0.0.0/0, ::/0",
                "--expected-server-public-key", REMOVE_PUBLIC_KEY), remote.arguments.getValue("add-peer.sh"))
            assertEquals(listOf("--name", "phone", "--expected-public-key", REMOVE_PUBLIC_KEY, "--expected-server-public-key", REMOVE_PUBLIC_KEY), remote.arguments.getValue("remove-peer.sh"))
            assertEquals(listOf("--name", "tablet", "--expected-public-key", REVOKE_PUBLIC_KEY,
                "--expected-server-public-key", REMOVE_PUBLIC_KEY, "--operation-id", OPERATION_ID), remote.arguments.getValue("revoke-peer.sh"))
            assertEquals(emptyList<String>(), remote.arguments.getValue("sync-status.sh"))
            assertEquals(emptyList<String>(), remote.arguments.getValue("backup.sh"))
            assertEquals(listOf("--confirm", "--purge-packages"), remote.arguments.getValue("uninstall.sh"))
            assertEquals(remote.closes * ProvisioningScriptBundle.scriptNames.size, remote.uploads.size)
            assertEquals(8, remote.closes)
            assertTrue(remote.uploads.all { it.second == "0700" })
            assertEquals(ProvisioningScriptBundle.scriptNames.toSet(), remote.uploads.map { it.first }.toSet())
            for (name in ProvisioningScriptBundle.scriptNames) {
                val expected = context.assets.open(name).bufferedReader().use { it.readText() }
                assertEquals(expected, remote.contents.getValue(name))
            }
        }
    }

    @Test
    fun clientConsumesStructuredFailureFromNonzeroScriptExit() = runBlocking {
        val remote = RecordingShell(temporaryFolder.newFolder(), root = true, failScript = true)
        val factory = object : RemoteShellClientFactory {
            override fun create(connection: VpsConnection) = remote
        }
        val client = Vpn0wnProvisioningClient(ApplicationProvider.getApplicationContext(), factory)
        val connection = VpsConnection(VpsEndpoint("vpn.example"), VpsCredential.Password(charArrayOf()), true)
        try {
            client.removePeer(connection, "missing", REMOVE_PUBLIC_KEY, REMOVE_PUBLIC_KEY)
            throw AssertionError("Failed script must not succeed")
        } catch (failure: ProvisioningException) {
            assertEquals("peer_not_found", failure.remoteCode)
            assertEquals(ProvisioningRecovery.UNCHANGED, failure.recovery)
        }
        assertTrue(remote.cleanedUp)
        assertEquals(1, remote.closes)
    }

    @Test
    fun peerMutationsRejectInvalidIdentityBeforeCreatingRemoteClient() = runBlocking {
        var creations = 0
        val client = Vpn0wnProvisioningClient(
            ApplicationProvider.getApplicationContext(),
            object : RemoteShellClientFactory {
                override fun create(connection: VpsConnection): RemoteShellClient {
                    creations++
                    throw AssertionError("Invalid identity reached transport")
                }
            },
        )
        val invalidIdentities = listOf(
            "" to REMOVE_PUBLIC_KEY,
            " " to REMOVE_PUBLIC_KEY,
            "name's" to REMOVE_PUBLIC_KEY,
            "n".repeat(33) to REMOVE_PUBLIC_KEY,
            "phone" to "",
            "phone" to " ",
            "phone" to "synthetic-shape-secret",
            "phone" to ("A".repeat(42) + "B="),
        )
        for ((name, key) in invalidIdentities) {
            for (revoke in listOf(false, true)) {
                val failure = runCatching {
                    if (revoke) client.revokePeer(connection(), name, key, REMOVE_PUBLIC_KEY, OPERATION_ID) else client.removePeer(connection(), name, key, REMOVE_PUBLIC_KEY)
                }.exceptionOrNull()
                assertTrue(failure is ProvisioningException)
                assertEquals(ProvisioningFailureCategory.INVALID_INPUT, (failure as ProvisioningException).category)
                assertTrue(!failure.stackTraceToString().contains("synthetic-shape-secret"))
            }
        }
        assertEquals(0, creations)
    }

    @Test
    fun peerMutationsRequireExactReceiptIdentityAndActionWithoutRetrying() = runBlocking {
        for (revoke in listOf(false, true)) {
            val scriptName = if (revoke) "revoke-peer.sh" else "remove-peer.sh"
            val valid = JSONObject().put("ok", true).put("name", "phone").put("publicKey", REMOVE_PUBLIC_KEY)
                .put("removed", true).put("revoked", revoke).put("archive", if (revoke) "/fixture/revoked.conf" else "")
                .put("cleanupPath", "/fixture/private-residue")
            if (revoke) valid.put("archiveName", "phone.conf.$OPERATION_ID").put("operationId", OPERATION_ID)
            val responses = listOf(
                valid,
                JSONObject(valid.toString()).put("name", "reused-name"),
                JSONObject(valid.toString()).put("publicKey", REVOKE_PUBLIC_KEY),
                JSONObject(valid.toString()).put("removed", false),
                JSONObject(valid.toString()).put("revoked", !revoke),
                JSONObject(valid.toString()).apply { remove("publicKey") },
            )
            for ((index, response) in responses.withIndex()) {
                var mutations = 0
                var cleanups = 0
                var closes = 0
                val remote = object : RemoteShellClient {
                    override suspend fun execute(command: String): ProvisioningCommandOutput = when {
                        command.startsWith("mktemp ") -> ProvisioningCommandOutput(0, "/tmp/vpn0wn-provisioning.test01", "")
                        command.startsWith("mkdir -p ") -> ProvisioningCommandOutput(0, "", "")
                        command.startsWith("rm -rf ") -> {
                            cleanups++
                            ProvisioningCommandOutput(0, "", "")
                        }
                        command.startsWith("cd ") && command.contains("./'$scriptName'") -> {
                            mutations++
                            ProvisioningCommandOutput(0, response.toString(), "")
                        }
                        else -> throw AssertionError("Unexpected provisioning command")
                    }
                    override suspend fun upload(path: String, content: String, mode: String) = ProvisioningCommandOutput(0, "", "")
                    override suspend fun close() {
                        closes++
                    }
                }
                val result = runCatching {
                    val client = provisioningClient(remote)
                    if (revoke) client.revokePeer(connection(), "phone", REMOVE_PUBLIC_KEY, REMOVE_PUBLIC_KEY, OPERATION_ID) else client.removePeer(connection(), "phone", REMOVE_PUBLIC_KEY, REMOVE_PUBLIC_KEY)
                }
                if (index == 0) {
                    assertEquals(REMOVE_PUBLIC_KEY, result.getOrThrow().publicKey)
                    assertEquals("/fixture/private-residue", result.getOrThrow().cleanupPath)
                    assertEquals(revoke, result.getOrThrow().revoked)
                } else {
                    assertEquals(ProvisioningFailureCategory.INVALID_RESPONSE, (result.exceptionOrNull() as ProvisioningException).category)
                }
                assertEquals(1, mutations)
                assertEquals(1, cleanups)
                assertEquals(1, closes)
            }
        }
    }

    @Test
    fun tempDirectoryFailuresStillCloseLocalClient() = runBlocking {
        for (output in listOf(ProvisioningCommandOutput(1, "", ""), ProvisioningCommandOutput(0, "  ", ""))) {
            var closes = 0
            val remote = object : RemoteShellClient {
                override suspend fun execute(command: String) = output
                override suspend fun upload(path: String, content: String, mode: String): ProvisioningCommandOutput =
                    throw AssertionError("No upload after failed mktemp")
                override suspend fun close() { closes++ }
            }
            val result = runCatching { provisioningClient(remote).detect(connection()) }
            assertTrue(result.exceptionOrNull() is ProvisioningException)
            assertEquals(1, closes)
        }
    }

    @Test
    fun unownedTempDirectoryResponsesNeverReachRemoteMutations() = runBlocking {
        val outputs = listOf(
            "", "  ", "/", "/etc", "/tmp", "/tmp/synthetic", "/tmp/vpn0wn-provisioning.abcde",
            "/tmp/vpn0wn-provisioning.abcdefg", "/tmp/vpn0wn-provisioning.abc/../etc",
            "/tmp/vpn0wn-provisioning.abc_12", "/tmp/vpn0wn-provisioning.abc123\n/etc",
            " /tmp/vpn0wn-provisioning.abc123", "/tmp/vpn0wn-provisioning.abc123 ",
        )
        for (output in outputs) {
            val commands = mutableListOf<String>()
            var closes = 0
            val remote = object : RemoteShellClient {
                override suspend fun execute(command: String): ProvisioningCommandOutput {
                    commands += command
                    check(command.startsWith("mktemp ")) { "Unexpected command before temp ownership validation" }
                    return ProvisioningCommandOutput(0, output, "")
                }
                override suspend fun upload(path: String, content: String, mode: String): ProvisioningCommandOutput =
                    throw AssertionError("No upload before temp ownership validation")
                override suspend fun close() { closes++ }
            }
            val failure = runCatching { provisioningClient(remote).detect(connection()) }.exceptionOrNull()
            assertTrue(failure is ProvisioningException)
            assertEquals(ProvisioningFailureCategory.INVALID_RESPONSE, (failure as ProvisioningException).category)
            assertEquals("Remote temp directory creation returned an invalid path", failure.message)
            assertEquals(listOf("mktemp -d /tmp/vpn0wn-provisioning.XXXXXX"), commands)
            assertEquals(1, closes)
        }
    }

    @Test
    fun cancellationAndErrorsSurviveRemoteAndLocalCleanupFailures() = runBlocking {
        for (original in listOf(CancellationException("caller cancellation"), AssertionError("caller defect"))) {
            val remoteCleanup = AssertionError("cleanup defect")
            val localCleanup = ProvisioningException("local close failed")
            var closes = 0
            val remote = object : RemoteShellClient {
                override suspend fun execute(command: String): ProvisioningCommandOutput = when {
                    command.startsWith("mktemp ") -> ProvisioningCommandOutput(0, "/tmp/vpn0wn-provisioning.test01", "")
                    command.startsWith("rm -rf ") -> throw remoteCleanup
                    else -> throw original
                }
                override suspend fun upload(path: String, content: String, mode: String) = ProvisioningCommandOutput(0, "", "")
                override suspend fun close() {
                    closes++
                    throw localCleanup
                }
            }
            val result = runCatching { provisioningClient(remote).detect(connection()) }
            val failure = requireNotNull(result.exceptionOrNull())
            assertEquals(original.javaClass, failure.javaClass)
            assertSame(original, generateSequence(failure) { it.cause }.last())
            assertTrue(original.suppressed.contains(remoteCleanup))
            assertTrue(original.suppressed.contains(localCleanup))
            assertEquals(1, closes)
        }
    }

    private fun provisioningClient(remote: RemoteShellClient) = Vpn0wnProvisioningClient(
        ApplicationProvider.getApplicationContext(),
        object : RemoteShellClientFactory {
            override fun create(connection: VpsConnection) = remote
        },
    )

    @Test
    fun sameCancellationOrErrorFromCleanupCannotReplaceOriginalFailure() = runBlocking {
        for (original in listOf(CancellationException("caller cancellation"), AssertionError("caller defect"))) {
            val remote = object : RemoteShellClient {
                override suspend fun execute(command: String): ProvisioningCommandOutput =
                    if (command.startsWith("mktemp ")) ProvisioningCommandOutput(0, "/tmp/vpn0wn-provisioning.test01", "") else throw original
                override suspend fun upload(path: String, content: String, mode: String) = ProvisioningCommandOutput(0, "", "")
                override suspend fun close(): Unit = throw original
            }
            val failure = runCatching { provisioningClient(remote).detect(connection()) }.exceptionOrNull()
            assertEquals(original.javaClass, failure?.javaClass)
            assertSame(original, generateSequence(failure) { it.cause }.last())
        }
    }

    private fun connection() = VpsConnection(VpsEndpoint("198.51.100.10"), VpsCredential.Password(charArrayOf()), true)

    @Test
    fun nestedCoroutineRecoveryKeepsPrimaryErrorAndClosesLocalClient() = runBlocking {
        val original = AssertionError("nested caller defect")
        val remoteCleanup = AssertionError("nested remote cleanup defect")
        val localCleanup = ProvisioningException("local cleanup failed")
        var closes = 0
        val remote = object : RemoteShellClient {
            override suspend fun execute(command: String): ProvisioningCommandOutput = withContext(Dispatchers.IO) {
                when {
                    command.startsWith("mktemp ") -> ProvisioningCommandOutput(0, "/tmp/vpn0wn-provisioning.test01", "")
                    command.startsWith("rm -rf ") -> throw remoteCleanup
                    else -> throw original
                }
            }
            override suspend fun upload(path: String, content: String, mode: String) = ProvisioningCommandOutput(0, "", "")
            override suspend fun close() {
                closes++
                throw localCleanup
            }
        }
        val failure = runCatching { provisioningClient(remote).detect(connection()) }.exceptionOrNull()
        assertTrue(failure is AssertionError)
        assertSame(original, generateSequence(failure) { it.cause }.last())
        assertEquals(1, closes)
    }

    private companion object {
        const val REMOVE_PUBLIC_KEY = "UFVCTElDLTIgICAgICAgICAgICAgICAgICAgICAgICA="
        const val REVOKE_PUBLIC_KEY = "UFVCTElDLTMgICAgICAgICAgICAgICAgICAgICAgICA="
        const val OPERATION_ID = "00112233-4455-4677-8899-aabbccddeeff"
    }

    private class RecordingShell(directory: File, root: Boolean, private val failScript: Boolean = false) : RemoteShellClient {
        private val remotePath = "/tmp/vpn0wn-provisioning.test01"
        private val remoteDir = File(directory, "remote dir' \$(printf injected)").apply { mkdir() }
        private val bin = File(directory, "bin").apply { mkdir() }
        val arguments = mutableMapOf<String, List<String>>()
        val uploads = mutableListOf<Pair<String, String>>()
        val contents = mutableMapOf<String, String>()
        var cleanedUp = false
        var closes = 0

        override suspend fun close() {
            closes++
        }

        init {
            File(bin, "id").apply {
                writeText("#!/usr/bin/env bash\nprintf '%s' " + if (root) "0" else "1000")
                check(setExecutable(true))
            }
            File(bin, "sudo").apply {
                writeText("#!/usr/bin/env bash\n[[ \"\$1\" == -n ]] || exit 9\nshift\nexec \"\$@\"\n")
                check(setExecutable(true))
            }
        }

        override suspend fun execute(command: String): ProvisioningCommandOutput {
            return when {
                command.startsWith("mktemp ") -> ProvisioningCommandOutput(0, "$remotePath\n", "")
                command.startsWith("mkdir ") -> ProvisioningCommandOutput(0, "", "")
                command.startsWith("rm -rf ") -> {
                    cleanedUp = true
                    ProvisioningCommandOutput(0, "", "")
                }
                else -> {
                    val name = ProvisioningScriptBundle.scriptNames.first { command.contains("./'$it'") }
                    val localCommand = command.replace(remotePath.shellQuote(), remoteDir.path.shellQuote())
                    val process = ProcessBuilder("bash", "-c", localCommand).apply {
                        environment()["PATH"] = bin.path + ":" + environment().getValue("PATH")
                    }.start()
                    val output = process.inputStream.bufferedReader().use { it.readText() }
                    val error = process.errorStream.bufferedReader().use { it.readText() }
                    assertEquals("Shell command failed: $error", 0, process.waitFor())
                    arguments[name] = if (output.isEmpty()) emptyList() else output.split('\u0000').dropLast(1)
                    val fixtureName = when {
                        failScript -> "error"
                        name == "sync-status.sh" -> "status-running"
                        else -> name.removeSuffix(".sh")
                    }
                    var payload = requireNotNull(javaClass.getResourceAsStream("/provisioning/$fixtureName.json")).bufferedReader().use { it.readText() }
                    if (name == "revoke-peer.sh" && !failScript) payload = JSONObject(payload)
                        .put("archiveName", "tablet.conf.$OPERATION_ID").put("operationId", OPERATION_ID).toString()
                    ProvisioningCommandOutput(if (failScript) 74 else 0, payload, "")
                }
            }
        }

        override suspend fun upload(path: String, content: String, mode: String): ProvisioningCommandOutput {
            check(path.startsWith("$remotePath/"))
            val file = File(remoteDir, path.removePrefix("$remotePath/"))
            uploads.add(file.name to mode)
            contents[file.name] = content
            file.writeText("#!/usr/bin/env bash\nif ((\$#)); then printf '%s\\0' \"\$@\"; fi\n")
            return ProvisioningCommandOutput(0, "", "")
        }
    }
}
