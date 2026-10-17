/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.provisioning

import android.content.Context
import android.util.Log
import com.vpn0wn.config.Attribute
import com.vpn0wn.crypto.Key
import com.vpn0wn.crypto.KeyFormatException
import java.io.IOException
import com.vpn0wn.crypto.KeyPair
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

    suspend fun install(connection: VpsConnection, request: VpsInstallRequest, onAdmitted: suspend () -> Unit = {}): VpsInstallResult = withClient(connection) { client, remoteDir ->
        onAdmitted()
        ProvisioningJson.parseInstall(run(client, remoteDir, "install.sh", request.installArgs()).stdout)
    }

    suspend fun syncStatus(connection: VpsConnection): VpsServerStatus = withClient(connection) { client, remoteDir ->
        ProvisioningJson.parseStatus(run(client, remoteDir, "sync-status.sh").stdout)
    }

    suspend fun addPeer(
        connection: VpsConnection,
        request: VpsAddPeerRequest,
        expectedServerPublicKey: String,
        onAdmitted: suspend () -> Unit = {},
        onReceived: suspend (VpsAddPeerResult) -> Unit = {},
    ): VpsAddPeerResult {
        try {
            if (Key.fromBase64(expectedServerPublicKey).toBase64() != expectedServerPublicKey) throw invalidInput()
        } catch (_: KeyFormatException) {
            throw invalidInput()
        }
        return withClient(connection) { client, remoteDir ->
            onAdmitted()
            val result = ProvisioningJson.parseAddPeer(
                run(client, remoteDir, "add-peer.sh", request.args() + listOf("--expected-server-public-key", expectedServerPublicKey)).stdout,
            )
            // Persist the only private receipt before dispatcher-return cancellation can discard it.
            withContext(NonCancellable) { onReceived(result) }
            result
        }
    }

    private fun invalidInput() = ProvisioningException(
        ProvisioningFailureCategory.INVALID_INPUT.safeMessage, category = ProvisioningFailureCategory.INVALID_INPUT,
    )

    suspend fun removePeer(connection: VpsConnection, name: String, expectedPublicKey: String, expectedServerPublicKey: String,
        onAdmitted: suspend () -> Unit = {}, onReceived: suspend () -> Unit = {}): VpsPeerMutationResult {
        val args = peerMutationArgs(name, expectedPublicKey) + listOf("--expected-server-public-key", expectedServerPublicKey)
        try {
            if (Key.fromBase64(expectedServerPublicKey).toBase64() != expectedServerPublicKey) throw invalidInput()
        } catch (_: KeyFormatException) {
            throw invalidInput()
        }
        return withClient(connection) { client, remoteDir ->
            onAdmitted()
            val result = parsePeerMutation(run(client, remoteDir, "remove-peer.sh", args).stdout, name, expectedPublicKey, revoked = false)
            withContext(NonCancellable) { onReceived() }
            result
        }
    }

    // Validate independent caller identities before transport, then reject an uncorrelated mutation receipt.
    @Suppress("ThrowsCount")
    suspend fun revokePeer(connection: VpsConnection, name: String, expectedPublicKey: String, expectedServerPublicKey: String,
        operationId: String, onAdmitted: suspend () -> Unit = {}, onReceived: suspend () -> Unit = {}): VpsPeerMutationResult {
        if (!VpsRevokedArchive.OPERATION_ID.matches(operationId)) throw invalidInput()
        val args = peerMutationArgs(name, expectedPublicKey) +
            listOf("--expected-server-public-key", expectedServerPublicKey, "--operation-id", operationId)
        try {
            if (Key.fromBase64(expectedServerPublicKey).toBase64() != expectedServerPublicKey) throw invalidInput()
        } catch (_: KeyFormatException) {
            throw invalidInput()
        }
        return withClient(connection) { client, remoteDir ->
            onAdmitted()
            val result = parsePeerMutation(run(client, remoteDir, "revoke-peer.sh", args).stdout, name, expectedPublicKey, revoked = true)
            if (result.operationId != operationId || result.archiveName != "$name.conf.$operationId")
                throw ProvisioningException(ProvisioningFailureCategory.INVALID_RESPONSE.safeMessage, category = ProvisioningFailureCategory.INVALID_RESPONSE)
            withContext(NonCancellable) { onReceived() }
            result
        }
    }

    suspend fun rotatePeer(connection: VpsConnection, server: StoredVpsServer, attempt: PendingVpsRotate, proposed: VpsUserProfile,
        onAdmitted: suspend () -> Unit, onReceived: suspend () -> Unit): VpsPeerRotationResult =
        peerRotation(connection, server, attempt, proposed, VpsPeerRotationResult.Mode.ROTATE, onAdmitted, onReceived)

    suspend fun verifyPeerRotation(connection: VpsConnection, server: StoredVpsServer, attempt: PendingVpsRotate,
        proposed: VpsUserProfile): VpsPeerRotationResult =
        peerRotation(connection, server, attempt, proposed, VpsPeerRotationResult.Mode.VERIFY)

    private suspend fun peerRotation(connection: VpsConnection, server: StoredVpsServer, attempt: PendingVpsRotate, proposed: VpsUserProfile,
        mode: VpsPeerRotationResult.Mode, onAdmitted: suspend () -> Unit = {}, onReceived: suspend () -> Unit = {}): VpsPeerRotationResult {
        if (connection.endpoint != attempt.ssh || connection.hostKeyFingerprint != attempt.fingerprint) throw invalidInput()
        val input = rotationInput(server, attempt, proposed)
        return withClient(connection) { client, remoteDir ->
            val inputPath = "$remoteDir/rotation-input"
            client.upload(inputPath, input, "0600").requireSuccess()
            if (mode == VpsPeerRotationResult.Mode.ROTATE) onAdmitted()
            val args = listOf("--input", inputPath) + if (mode == VpsPeerRotationResult.Mode.VERIFY) listOf("--verify") else emptyList()
            val result = ProvisioningJson.parsePeerRotation(run(client, remoteDir, "rotate-peer.sh", args).stdout)
            if (result.mode != mode || !result.matches(attempt)) throw invalidRotationResponse()
            if (mode == VpsPeerRotationResult.Mode.ROTATE) withContext(NonCancellable) { onReceived() }
            result
        }
    }

    // Correlation, single-address and line-framing failures must reject before any private upload.
    @Suppress("ThrowsCount")
    private fun rotationInput(server: StoredVpsServer, attempt: PendingVpsRotate, proposed: VpsUserProfile): String {
        try {
            attempt.validate()
        } catch (_: IOException) {
            throw invalidInput()
        }
        if (!attempt.owns(server) || !attempt.owns(proposed)) throw invalidInput()
        val address = proposed.config.`interface`.addresses.singleOrNull()?.toString() ?: throw invalidInput()
        val psk = proposed.config.peers.single().preSharedKey.get().toBase64()
        val fields = linkedMapOf("version" to "1", "operationId" to attempt.operationId, "name" to attempt.name,
            "serverPublicKey" to attempt.serverPublicKey, "oldPublicKey" to attempt.oldClientPublicKey, "newPublicKey" to attempt.newClientPublicKey,
            "address" to address, "endpoint" to server.endpoint, "port" to server.listenPort, "cidr" to server.cidr, "dns" to server.dns, "presharedKey" to psk)
        if (fields.values.any { value -> value.any { it == '\n' || it == '\r' || it == '\u0000' } }) throw invalidInput()
        return fields.entries.joinToString("\n") { (key, value) -> "$key=$value" }
    }

    private fun invalidRotationResponse() = ProvisioningException(
        ProvisioningFailureCategory.INVALID_RESPONSE.safeMessage, category = ProvisioningFailureCategory.INVALID_RESPONSE,
    )

    private fun peerMutationArgs(name: String, expectedPublicKey: String): List<String> {
        if (!PEER_NAME.matches(name))
            throw ProvisioningException(ProvisioningFailureCategory.INVALID_INPUT.safeMessage, category = ProvisioningFailureCategory.INVALID_INPUT)
        try {
            Key.fromBase64(expectedPublicKey)
        } catch (_: KeyFormatException) {
            throw ProvisioningException(ProvisioningFailureCategory.INVALID_INPUT.safeMessage, category = ProvisioningFailureCategory.INVALID_INPUT)
        }
        return listOf("--name", name, "--expected-public-key", expectedPublicKey)
    }

    private fun parsePeerMutation(stdout: String, name: String, expectedPublicKey: String, revoked: Boolean): VpsPeerMutationResult {
        val result = ProvisioningJson.parsePeerMutation(stdout)
        if (result.name != name || result.publicKey != expectedPublicKey || result.revoked != revoked)
            throw ProvisioningException(ProvisioningFailureCategory.INVALID_RESPONSE.safeMessage, category = ProvisioningFailureCategory.INVALID_RESPONSE)
        return result
    }

    suspend fun backup(connection: VpsConnection): VpsBackupResult = withClient(connection) { client, remoteDir ->
        ProvisioningJson.parseBackup(run(client, remoteDir, "backup.sh").stdout)
    }

    suspend fun restoreFresh(
        connection: VpsConnection,
        snapshot: VpsBackupResult,
        request: VpsRestoreRequest,
        onAdmitted: suspend () -> Unit = {},
    ): VpsRestoreResult {
        val owned = snapshot.copy(revoked = snapshot.revoked.toList())
        validateRestore(owned, request)
        val expectedCidr = owned.config.lineSequence().singleOrNull { it.startsWith("# TUNNL_CIDR ") }
            ?.removePrefix("# TUNNL_CIDR ") ?: throw invalidInput()
        val expectedPeerCount = owned.config.lineSequence().count { it == "[Peer]" }
        return withClient(connection) { client, remoteDir ->
            val inputDir = uploadRestoreInput(client, remoteDir, owned)
            onAdmitted()
            val result = ProvisioningJson.parseRestore(run(client, remoteDir, "restore.sh", request.args(inputDir)).stdout)
            if (result.interfaceName != owned.interfaceName || result.endpoint != request.endpoint ||
                result.port != request.port.toString() || result.serverPublicKey != request.expectedServerPublicKey ||
                result.cidr != expectedCidr || result.peerCount != expectedPeerCount || result.revokedCount != owned.revokedCount)
                throw ProvisioningException(ProvisioningFailureCategory.INVALID_RESPONSE.safeMessage, category = ProvisioningFailureCategory.INVALID_RESPONSE)
            result
        }
    }

    suspend fun checkFreshRestore(connection: VpsConnection, snapshot: VpsBackupResult, request: VpsRestoreRequest): VpsRestoreCheckPlan {
        val owned = snapshot.copy(revoked = snapshot.revoked.toList())
        validateRestore(owned, request)
        val key = owned.config.lineSequence().mapNotNull { Attribute.parse(it.substringBefore('#').trim()).orElse(null) }
            .singleOrNull { it.key.equals("PrivateKey", ignoreCase = true) }?.value
        val serverPublicKey = try {
            key?.let { KeyPair(Key.fromBase64(it)).publicKey.toBase64() }
        } catch (_: KeyFormatException) {
            null
        }
        if (serverPublicKey == null || serverPublicKey != request.expectedServerPublicKey) throw invalidInput()
        val expected = VpsRestoreCheckPlan(
            interfaceName = owned.interfaceName, endpoint = request.endpoint, port = request.port.toString(),
            cidr = restoreMetadata(owned, "# TUNNL_CIDR "), dns = restoreMetadata(owned, "# TUNNL_DNS "),
            serverPublicKey = serverPublicKey, peerCount = owned.config.lineSequence().count { it == "[Peer]" },
            revokedCount = owned.revokedCount,
        )
        return withClient(connection) { client, remoteDir ->
            val inputDir = uploadRestoreInput(client, remoteDir, owned)
            val result = ProvisioningJson.parseRestoreCheck(run(client, remoteDir, "restore.sh", request.args(inputDir, checkOnly = true)).stdout)
            if (result != expected)
                throw ProvisioningException(ProvisioningFailureCategory.INVALID_RESPONSE.safeMessage, category = ProvisioningFailureCategory.INVALID_RESPONSE)
            result
        }
    }

    private fun restoreMetadata(snapshot: VpsBackupResult, prefix: String): String =
        snapshot.config.lineSequence().singleOrNull { it.startsWith(prefix) }?.removePrefix(prefix) ?: throw invalidInput()

    private suspend fun uploadRestoreInput(client: RemoteShellClient, remoteDir: String, snapshot: VpsBackupResult): String {
        val inputDir = "$remoteDir/restore-input"
        val revokedDir = "$inputDir/revoked"
        client.execute("umask 077 && mkdir -- ${inputDir.shellQuote()} ${revokedDir.shellQuote()}").requireSuccess()
        client.upload("$inputDir/server.conf", snapshot.config, "0600").requireSuccess()
        for (archive in snapshot.revoked) {
            client.upload("$revokedDir/${archive.archiveName}", archive.config, "0600").requireSuccess()
        }
        return inputDir
    }

    private fun validateRestore(snapshot: VpsBackupResult, request: VpsRestoreRequest) {
        val canonicalKey = try {
            Key.fromBase64(request.expectedServerPublicKey).toBase64()
        } catch (_: KeyFormatException) {
            null
        }
        if (snapshot.interfaceName != "wg0" || request.port !in 1..MAX_PORT || request.endpoint.isBlank() ||
            canonicalKey != request.expectedServerPublicKey)
            throw invalidInput()
        try {
            ProvisioningJson.encodeBackup(snapshot).fill(0)
        } catch (_: ProvisioningException) {
            throw invalidInput()
        }
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
                withContext(NonCancellable) {
                    try {
                        client.execute("rm -rf ${remoteDir.shellQuote()}").requireSuccess()
                    } catch (_: ProvisioningException) {
                        Log.d(TAG, "Remote cleanup failed for provisioning temp dir")
                    } catch (cleanup: Throwable) {
                        if (operationFailure == null) throw cleanup
                        if (cleanup !== operationFailure) operationFailure.addSuppressed(cleanup)
                    }
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
        private const val MAX_PORT = 65_535
        private val REMOTE_DIRECTORY = Regex("/tmp/vpn0wn-provisioning\\.[A-Za-z0-9]{6}")
        private val PEER_NAME = Regex("[A-Za-z0-9_-]{1,32}")
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

data class VpsRestoreRequest(val endpoint: String, val port: Int, val expectedServerPublicKey: String) {
    fun args(inputDir: String, checkOnly: Boolean = false): List<String> = listOf(
        "--input", inputDir, "--endpoint", endpoint, "--port", port.toString(),
        "--expected-server-public-key", expectedServerPublicKey, if (checkOnly) "--check" else "--confirm",
    )
}
