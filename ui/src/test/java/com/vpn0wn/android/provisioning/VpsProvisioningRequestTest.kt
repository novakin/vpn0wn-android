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
    }

    private class RecordingShell(directory: File, root: Boolean, private val failScript: Boolean = false) : RemoteShellClient {
        private val remoteDir = File(directory, "remote dir' \$(printf injected)").apply { mkdir() }
        private val bin = File(directory, "bin").apply { mkdir() }
        val arguments = mutableMapOf<String, List<String>>()
        val uploads = mutableListOf<Pair<String, String>>()
        val contents = mutableMapOf<String, String>()
        var cleanedUp = false

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
                command.startsWith("mktemp ") -> ProvisioningCommandOutput(0, remoteDir.path, "")
                command.startsWith("mkdir ") -> ProvisioningCommandOutput(0, "", "")
                command.startsWith("rm -rf ") -> {
                    cleanedUp = true
                    ProvisioningCommandOutput(0, "", "")
                }
                else -> {
                    val name = ProvisioningScriptBundle.scriptNames.first { command.contains("./'$it'") }
                    val process = ProcessBuilder("bash", "-c", command).apply {
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
            val file = File(path)
            uploads.add(file.name to mode)
            contents[file.name] = content
            file.writeText("#!/usr/bin/env bash\nif ((\$#)); then printf '%s\\0' \"\$@\"; fi\n")
            return ProvisioningCommandOutput(0, "", "")
        }
    }
}
