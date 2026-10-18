/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.provisioning

import java.io.IOException
import kotlinx.coroutines.withTimeoutOrNull

interface RemoteShellClient {
    suspend fun execute(command: String): ProvisioningCommandOutput
    suspend fun execute(command: String, onProgress: (VpsInstallProgress) -> Unit): ProvisioningCommandOutput = execute(command)
    suspend fun upload(path: String, content: String, mode: String = "0700"): ProvisioningCommandOutput
    suspend fun close()
}

interface RemoteShellClientFactory {
    fun create(connection: VpsConnection): RemoteShellClient
}

class GuardedRemoteShellClient(
    private val delegate: RemoteShellClient,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : RemoteShellClient {
    override suspend fun close() = delegate.close()

    override suspend fun execute(command: String): ProvisioningCommandOutput = guard("Remote command timed out", "Remote command failed") {
        delegate.execute(command)
    }

    override suspend fun execute(command: String, onProgress: (VpsInstallProgress) -> Unit): ProvisioningCommandOutput =
        guard("Remote command timed out", "Remote command failed") { delegate.execute(command, onProgress) }

    override suspend fun upload(path: String, content: String, mode: String): ProvisioningCommandOutput = guard("Remote upload timed out", "Remote upload failed") {
        delegate.upload(path, content, mode)
    }

    private suspend fun guard(timeoutMessage: String, failureMessage: String, block: suspend () -> ProvisioningCommandOutput): ProvisioningCommandOutput {
        return try {
            withTimeoutOrNull(timeoutMillis) { block() }
                ?: throw ProvisioningException(timeoutMessage)
        } catch (e: IOException) {
            throw ProvisioningException("$failureMessage: ${e.message.orEmpty()}".trim(), cause = e)
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
    }
}

class GuardedRemoteShellClientFactory(
    private val delegate: RemoteShellClientFactory,
    private val timeoutMillis: Long = GuardedRemoteShellClient.DEFAULT_TIMEOUT_MILLIS,
) : RemoteShellClientFactory {
    override fun create(connection: VpsConnection): RemoteShellClient {
        if (!connection.trustHostKey && connection.hostKeyFingerprint.isNullOrBlank())
            throw ProvisioningException("SSH host fingerprint is required when host-key trust is disabled")
        if (connection.maxAuthAttempts < 1)
            throw ProvisioningException("SSH auth attempt limit must be at least 1")
        return GuardedRemoteShellClient(delegate.create(connection), timeoutMillis)
    }
}
