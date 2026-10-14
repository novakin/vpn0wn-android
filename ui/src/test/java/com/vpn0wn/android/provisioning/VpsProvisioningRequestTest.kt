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
            client.addPeer(connection, VpsAddPeerRequest(hostileValue, "0.0.0.0/0, ::/0", "1.1.1.1"))
            client.removePeer(connection, hostileValue)
            client.revokePeer(connection, hostileValue)
            client.syncStatus(connection)
            client.backup(connection)
            client.uninstall(connection, purgePackages = true)
            val installArgs = listOf("--endpoint", hostileValue, "--port", "51820", "--cidr", "10.8.0.0/24", "--dns", "1.1.1.1, example.internal", "--force")
            assertEquals(installArgs, remote.arguments.getValue("plan.sh"))
            assertEquals(installArgs, remote.arguments.getValue("install.sh"))
            assertEquals(listOf("--name", hostileValue, "--dns", "1.1.1.1", "--allowed-ips", "0.0.0.0/0, ::/0"), remote.arguments.getValue("add-peer.sh"))
            assertEquals(listOf("--name", hostileValue), remote.arguments.getValue("remove-peer.sh"))
            assertEquals(listOf("--name", hostileValue), remote.arguments.getValue("revoke-peer.sh"))
            assertEquals(emptyList<String>(), remote.arguments.getValue("sync-status.sh"))
            assertEquals(emptyList<String>(), remote.arguments.getValue("backup.sh"))
            assertEquals(listOf("--confirm", "--purge-packages"), remote.arguments.getValue("uninstall.sh"))
            assertEquals(80, remote.uploads.size)
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
            client.removePeer(connection, "missing")
            throw AssertionError("Failed script must not succeed")
        } catch (failure: ProvisioningException) {
            assertEquals("peer_not_found", failure.remoteCode)
            assertEquals(ProvisioningRecovery.UNCHANGED, failure.recovery)
        }
        assertTrue(remote.cleanedUp)
        assertEquals(1, remote.closes)
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
                    val payload = requireNotNull(javaClass.getResourceAsStream("/provisioning/$fixtureName.json")).bufferedReader().use { it.readText() }
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
