/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Modified for vpn0wn.
 */
package com.vpn0wn.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vpn0wn.android.backend.WgQuickBackend
import com.vpn0wn.android.util.applicationScope
import com.vpn0wn.android.util.RootShell.RootShellException
import java.io.IOException
import kotlinx.coroutines.launch

class BootShutdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_SHUTDOWN) return
        val pendingResult = goAsync()
        applicationScope.launch {
            try {
                if (Application.getBackend() !is WgQuickBackend) return@launch
                val tunnelManager = Application.getTunnelManager()
                if (Intent.ACTION_BOOT_COMPLETED == action) {
                    Log.i(TAG, "Broadcast receiver restoring state (boot)")
                    tunnelManager.restoreState(false)
                } else if (Intent.ACTION_SHUTDOWN == action) {
                    Log.i(TAG, "Broadcast receiver saving state (shutdown)")
                    tunnelManager.saveState()
                }
            } catch (failure: IOException) {
                Log.e(TAG, "Failed to restore or save tunnel state", failure)
            } catch (failure: RootShellException) {
                Log.e(TAG, "Failed to restore or save tunnel state", failure)
            }
        }.invokeOnCompletion { pendingResult.finish() }
    }

    companion object {
        private const val TAG = "vpn0wn/BootShutdownReceiver"
    }
}
