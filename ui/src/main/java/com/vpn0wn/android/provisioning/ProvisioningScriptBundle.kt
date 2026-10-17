/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.provisioning

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ProvisioningScript(val name: String, val content: String)

class ProvisioningScriptBundle(private val scripts: List<ProvisioningScript>) {
    suspend fun uploadTo(client: RemoteShellClient, remoteDir: String) {
        client.execute("mkdir -p ${remoteDir.shellQuote()} && chmod 700 ${remoteDir.shellQuote()}").requireSuccess()
        for (script in scripts) {
            client.upload("$remoteDir/${script.name}", script.content).requireSuccess()
        }
    }

    companion object {
        val scriptNames = listOf(
            "common.sh",
            "detect.sh",
            "plan.sh",
            "install.sh",
            "add-peer.sh",
            "remove-peer.sh",
            "revoke-peer.sh",
            "rotate-peer.sh",
            "sync-status.sh",
            "backup.sh",
            "restore.sh",
            "uninstall.sh",
        )

        suspend fun load(context: Context): ProvisioningScriptBundle = withContext(Dispatchers.IO) {
            ProvisioningScriptBundle(
                scriptNames.map { name ->
                    context.assets.open(name).bufferedReader().use {
                        ProvisioningScript(name, it.readText())
                    }
                },
            )
        }
    }
}

internal fun String.shellQuote(): String = "'${replace("'", "'\"'\"'")}'"
