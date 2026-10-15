/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.provisioning

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class Vpn0wnProvisioningClient(
    private val context: Context,
    private val remoteShellClientFactory: RemoteShellClientFactory,
) {
    suspend fun detect(connection: VpsConnection): VpsDetectResult = withClient(connection) { client, remoteDir ->
        ProvisioningJson.parseDetect(run(client, remoteDir, "detect.sh", requiresRoot = false).stdout)
    }

    suspend fun plan(connection: VpsConnection, request: VpsInstallRequest): VpsPlanResult = withClient(connection) { client, remoteDir ->
        ProvisioningJson.parsePlan(run(client, remoteDir, "plan.sh", request.installArgs(), requiresRoot = false).stdout)
    }

    suspend fun install(connection: VpsConnection, request: VpsInstallRequest): VpsInstallResult = withClient(connection) { client, remoteDir ->
        ProvisioningJson.parseInstall(run(client, remoteDir, "install.sh", request.installArgs()).stdout)
    }

    suspend fun syncStatus(connection: VpsConnection): VpsServerStatus = withClient(connection) { client, remoteDir ->
        ProvisioningJson.parseStatus(run(client, remoteDir, "sync-status.sh").stdout)
    }

    suspend fun addPeer(connection: VpsConnection, request: VpsAddPeerRequest): VpsAddPeerResult = withClient(connection) { client, remoteDir ->
        ProvisioningJson.parseAddPeer(run(client, remoteDir, "add-peer.sh", request.args()).stdout)
    }

    suspend fun removePeer(connection: VpsConnection, name: String): VpsPeerMutationResult = withClient(connection) { client, remoteDir ->
        ProvisioningJson.parsePeerMutation(run(client, remoteDir, "remove-peer.sh", listOf("--name", name)).stdout)
    }

    suspend fun revokePeer(connection: VpsConnection, name: String): VpsPeerMutationResult = withClient(connection) { client, remoteDir ->
        ProvisioningJson.parsePeerMutation(run(client, remoteDir, "revoke-peer.sh", listOf("--name", name)).stdout)
    }

    suspend fun backup(connection: VpsConnection): VpsBackupResult = withClient(connection) { client, remoteDir ->
        ProvisioningJson.parseBackup(run(client, remoteDir, "backup.sh").stdout)
    }

    suspend fun uninstall(connection: VpsConnection, purgePackages: Boolean = false): VpsUninstallResult = withClient(connection) { client, remoteDir ->
        val args = buildList {
            add("--confirm")
            if (purgePackages) add("--purge-packages")
        }
        ProvisioningJson.parseUninstall(run(client, remoteDir, "uninstall.sh", args).stdout)
    }

    // Cleanup reports failure only without an original cancellation or defect to preserve.
    @Suppress("TooGenericExceptionCaught", "ThrowingExceptionFromFinally")
    private suspend fun <T> withClient(connection: VpsConnection, block: suspend (RemoteShellClient, String) -> T): T = withContext(Dispatchers.IO) {
        val client = remoteShellClientFactory.create(connection)
        var failure: Throwable? = null
        try {
            val remoteDir = client.execute("mktemp -d /tmp/vpn0wn-provisioning.XXXXXX").requireSuccess().stdout.trimEnd('\r', '\n')
            if (!REMOTE_DIRECTORY.matches(remoteDir))
                throw ProvisioningException(
                    "Remote temp directory creation returned an invalid path",
                    category = ProvisioningFailureCategory.INVALID_RESPONSE,
                )
            var operationFailure: Throwable? = null
            try {
                ProvisioningScriptBundle.load(context).uploadTo(client, remoteDir)
                block(client, remoteDir)
            } catch (problem: Throwable) {
                operationFailure = problem
                throw problem
            } finally {
                try {
                    client.execute("rm -rf ${remoteDir.shellQuote()}").requireSuccess()
                } catch (_: ProvisioningException) {
                    Log.d(TAG, "Remote cleanup failed for provisioning temp dir")
                } catch (cleanup: Throwable) {
                    if (operationFailure == null) throw cleanup
                    if (cleanup !== operationFailure) operationFailure.addSuppressed(cleanup)
                }
            }
        } catch (problem: Throwable) {
            failure = problem
            throw problem
        } finally {
            withContext(NonCancellable) {
                try {
                    client.close()
                } catch (cleanup: Throwable) {
                    if (failure == null) throw cleanup
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
            }
        }
    }

    private suspend fun run(
        client: RemoteShellClient,
        remoteDir: String,
        scriptName: String,
        args: List<String> = emptyList(),
        requiresRoot: Boolean = true,
    ): ProvisioningCommandOutput {
        val command = buildString {
            append("cd ")
            append(remoteDir.shellQuote())
            append(" && ")
            if (requiresRoot) {
                append("if [ \"\$(id -u)\" -eq 0 ]; then bash ./")
                append(scriptName.shellQuote())
            } else {
                append("bash ./")
                append(scriptName.shellQuote())
            }
            for (arg in args) {
                append(' ')
                append(arg.shellQuote())
            }
            if (requiresRoot) {
                append("; else sudo -n bash ./")
                append(scriptName.shellQuote())
                for (arg in args) {
                    append(' ')
                    append(arg.shellQuote())
                }
                append("; fi")
            }
        }
        return ProvisioningJson.requireScriptSuccess(client.execute(command))
    }

    companion object {
        private const val TAG = "vpn0wn/Provisioning"
        private val REMOTE_DIRECTORY = Regex("/tmp/vpn0wn-provisioning\\.[A-Za-z0-9]{6}")
    }
}

data class VpsInstallRequest(
    val endpoint: String,
    val cidr: String,
    val dns: String,
    val port: Int = 51820,
    val force: Boolean = false,
) {
    fun installArgs(): List<String> = buildList {
        add("--endpoint")
        add(endpoint)
        add("--port")
        add(port.toString())
        add("--cidr")
        add(cidr)
        add("--dns")
        add(dns)
        if (force) add("--force")
    }
}

data class VpsAddPeerRequest(
    val name: String,
    val allowedIps: String,
    val dns: String? = null,
) {
    fun args(): List<String> = buildList {
        add("--name")
        add(name)
        if (dns != null) {
            add("--dns")
            add(dns)
        }
        add("--allowed-ips")
        add(allowedIps)
    }
}
