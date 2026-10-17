/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Modified for vpn0wn.
 */
package com.vpn0wn.android.activity

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import com.vpn0wn.android.Application
import com.vpn0wn.android.QuickTileService
import com.vpn0wn.android.R
import com.vpn0wn.android.backend.GoBackend
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.model.ObservableTunnel
import com.vpn0wn.android.util.ErrorMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class TunnelToggleActivity : AppCompatActivity() {
    private val permissionState by viewModels<TilePermissionViewModel>()
    private val permissionActivityResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (permissionState.awaitingConsent) {
                permissionState.awaitingConsent = false
                completeRequest(result.resultCode == Activity.RESULT_OK)
            }
        }

    private fun completeRequest(approved: Boolean) {
        val request = permissionState.request ?: return
        permissionState.request = null
        if (!approved) {
            finishRequest()
            return
        }
        setTunnelState(request)
    }

    // Backend/ConfigStore expose Exception; cancellation and Errors remain terminal and propagate.
    @Suppress("TooGenericExceptionCaught")
    private fun setTunnelState(request: Request) {
        lifecycleScope.launch {
            try {
                request.tunnel.setStateAsync(request.target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportFailure(e)
            } finally {
                finishRequest()
            }
        }
    }

    private fun reportFailure(failure: Exception) {
        val message = getString(R.string.toggle_error, ErrorMessages[failure])
        Log.e(TAG, message, failure)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun finishRequest() {
        TileService.requestListeningState(this, ComponentName(this, QuickTileService::class.java))
        finishAffinity()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!permissionState.initialized) {
            permissionState.initialized = true
            if (savedInstanceState == null)
                permissionState.request = intent.extras?.getBinder(EXTRA_REQUEST) as? Request
        }
        val request = permissionState.request
        if (request == null) {
            finishRequest()
            return
        }
        if (permissionState.awaitingConsent) return
        lifecycleScope.launch {
            if (request.target == Tunnel.State.UP && Application.getBackend() is GoBackend) {
                try {
                    val permissionIntent = GoBackend.VpnService.prepare(this@TunnelToggleActivity)
                    if (permissionIntent != null) {
                        permissionState.awaitingConsent = true
                        permissionActivityResultLauncher.launch(permissionIntent)
                        return@launch
                    }
                } catch (e: SecurityException) {
                    rejectPreparation(e)
                    return@launch
                } catch (e: ActivityNotFoundException) {
                    rejectPreparation(e)
                    return@launch
                }
            }
            completeRequest(true)
        }
    }

    private fun rejectPreparation(failure: Exception) {
        permissionState.request = null
        permissionState.awaitingConsent = false
        reportFailure(failure)
        finishRequest()
    }

    // Local Binder identity survives activity launch; a process-restored proxy cannot authorize a retry.
    internal class Request(val tunnel: ObservableTunnel, val target: Tunnel.State) : Binder()

    companion object {
        private const val TAG = "vpn0wn/TunnelToggleActivity"
        private const val EXTRA_REQUEST = "com.vpn0wn.android.tile.REQUEST"

        internal fun createIntent(context: Context, tunnel: ObservableTunnel, target: Tunnel.State): Intent =
            Intent(context, TunnelToggleActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtras(Bundle().apply { putBinder(EXTRA_REQUEST, Request(tunnel, target)) })
    }
}

internal class TilePermissionViewModel : ViewModel() {
    var initialized = false
    var awaitingConsent = false
    var request: TunnelToggleActivity.Request? = null
}
