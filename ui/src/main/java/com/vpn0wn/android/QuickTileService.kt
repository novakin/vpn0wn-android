/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Modified for vpn0wn.
 */
package com.vpn0wn.android

import kotlinx.coroutines.CancellationException
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.databinding.Observable
import androidx.databinding.Observable.OnPropertyChangedCallback
import com.vpn0wn.android.activity.MainActivity
import com.vpn0wn.android.activity.TunnelToggleActivity
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.backend.BackendException
import com.vpn0wn.android.model.ObservableTunnel
import com.vpn0wn.android.util.applicationScope
import com.vpn0wn.android.util.UserKnobs
import com.vpn0wn.android.util.ErrorMessages
import com.vpn0wn.android.util.RootShell.RootShellException
import com.vpn0wn.android.widget.SlashDrawable
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Service that maintains the application's custom Quick Settings tile. This service is bound by the
 * system framework as necessary to update the appearance of the tile in the system UI, and to
 * forward click events to the application.
 */
class QuickTileService : TileService() {
    private val onStateChangedCallback = OnStateChangedCallback()
    private val onTunnelChangedCallback = OnTunnelChangedCallback()
    private var iconOff: Icon? = null
    private var iconOn: Icon? = null
    private var tunnel: ObservableTunnel? = null

    /* This works around an annoying unsolved frameworks bug some people are hitting. */
    @Suppress("TooGenericExceptionCaught")
    override fun onBind(intent: Intent): IBinder? {
        var ret: IBinder? = null
        try {
            ret = super.onBind(intent)
        } catch (e: Exception) {
            Log.d(TAG, "Failed to bind to TileService", e)
        }
        return ret
    }

    override fun onClick() {
        applicationScope.launch {
            if (tunnel == null) {
                try {
                    Application.getTunnelManager().getTunnels()
                    updateTile()
                } catch (failure: IOException) {
                    Log.e(TAG, "Failed to load tunnel inventory, so opening the app", failure)
                } catch (failure: RootShellException) {
                    Log.e(TAG, "Failed to load tunnel inventory, so opening the app", failure)
                }
            }
            when (val tunnel = tunnel) {
                null -> {
                    Log.d(TAG, "No tunnel set, so launching main activity")
                    val intent = Intent(this@QuickTileService, MainActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startActivityAndCollapse(PendingIntent.getActivity(this@QuickTileService, 0, intent, PendingIntent.FLAG_IMMUTABLE))
                    } else {
                        @Suppress("DEPRECATION")
                        startActivityAndCollapse(intent)
                    }
                }

                else -> {
                    unlockAndRun {
                        applicationScope.launch {
                            val target = if (tunnel.state == Tunnel.State.UP) Tunnel.State.DOWN else Tunnel.State.UP
                            setTunnelState(tunnel, target)
                        }
                    }
                }
            }
        }
    }

    // Backend/ConfigStore expose Exception; this operation reports their failures while cancellation and Errors propagate.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun setTunnelState(tunnel: ObservableTunnel, target: Tunnel.State) {
        try {
            tunnel.setStateAsync(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackendException) {
            if (e.reason != BackendException.Reason.VPN_NOT_AUTHORIZED || target != Tunnel.State.UP) {
                reportFailure(e)
                return
            }
            try {
                requestPermission(tunnel, target)
            } catch (failure: SecurityException) {
                reportFailure(failure)
            } catch (failure: ActivityNotFoundException) {
                reportFailure(failure)
            }
        } catch (e: Exception) {
            reportFailure(e)
        } finally {
            updateTile()
        }
    }

    private fun requestPermission(tunnel: ObservableTunnel, target: Tunnel.State) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !Settings.canDrawOverlays(this)) {
            Log.d(TAG, "Need overlay permissions")
            val permissionIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, permissionIntent, PendingIntent.FLAG_IMMUTABLE))
            return
        }
        startActivity(TunnelToggleActivity.createIntent(this, tunnel, target))
    }

    private fun reportFailure(failure: Exception) {
        val message = getString(R.string.toggle_error, ErrorMessages[failure])
        Log.e(TAG, message, failure)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onCreate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            iconOn = Icon.createWithResource(this, R.drawable.ic_tile)
            iconOff = iconOn
            return
        }
        val icon = SlashDrawable(resources.getDrawable(R.drawable.ic_tile, Application.get().theme))
        icon.setAnimationEnabled(false) /* Unfortunately we can't have animations, since Icons are marshaled. */
        icon.setSlashed(false)
        var b = Bitmap.createBitmap(icon.intrinsicWidth, icon.intrinsicHeight, Bitmap.Config.ARGB_8888)
        var c = Canvas(b)
        icon.setBounds(0, 0, c.width, c.height)
        icon.draw(c)
        iconOn = Icon.createWithBitmap(b)
        icon.setSlashed(true)
        b = Bitmap.createBitmap(icon.intrinsicWidth, icon.intrinsicHeight, Bitmap.Config.ARGB_8888)
        c = Canvas(b)
        icon.setBounds(0, 0, c.width, c.height)
        icon.draw(c)
        iconOff = Icon.createWithBitmap(b)
    }

    override fun onStartListening() {
        Application.getTunnelManager().addOnPropertyChangedCallback(onTunnelChangedCallback)
        tunnel?.addOnPropertyChangedCallback(onStateChangedCallback)
        updateTile()
    }

    override fun onStopListening() {
        tunnel?.removeOnPropertyChangedCallback(onStateChangedCallback)
        Application.getTunnelManager().removeOnPropertyChangedCallback(onTunnelChangedCallback)
    }

    override fun onTileAdded() {
        val removalRevision = UserKnobs.quickTileRemovalRevision
        applicationScope.launch {
            try {
                UserKnobs.setQuickTileAdded(true, removalRevision)
            } catch (_: IOException) {
                Log.w(TAG, "Unable to retain quick tile membership")
            }
        }
    }

    override fun onTileRemoved() {
        UserKnobs.recordQuickTileRemoval()
        applicationScope.launch {
            try {
                UserKnobs.setQuickTileAdded(false)
            } catch (_: IOException) {
                Log.w(TAG, "Unable to retain quick tile membership")
            }
        }
    }

    private fun updateTile() {
        // Update the tunnel.
        val newTunnel = Application.getTunnelManager().lastUsedTunnel
        if (newTunnel != tunnel) {
            tunnel?.removeOnPropertyChangedCallback(onStateChangedCallback)
            tunnel = newTunnel
            tunnel?.addOnPropertyChangedCallback(onStateChangedCallback)
        }
        // Update the tile contents.
        val tile = qsTile ?: return

        when (val tunnel = tunnel) {
            null -> {
                tile.label = getString(R.string.app_name)
                tile.state = Tile.STATE_INACTIVE
                tile.icon = iconOff
            }
            else -> {
                tile.label = tunnel.name
                tile.state = if (tunnel.state == Tunnel.State.UP) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                tile.icon = if (tunnel.state == Tunnel.State.UP) iconOn else iconOff
            }
        }
        tile.updateTile()
    }

    private inner class OnStateChangedCallback : OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable, propertyId: Int) {
            if (sender != tunnel) {
                sender.removeOnPropertyChangedCallback(this)
                return
            }
            if (propertyId != 0 && propertyId != BR.state)
                return
            updateTile()
        }
    }

    private inner class OnTunnelChangedCallback : OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable, propertyId: Int) {
            if (propertyId != 0 && propertyId != BR.lastUsedTunnel)
                return
            updateTile()
        }
    }

    companion object {
        private const val TAG = "vpn0wn/QuickTileService"
    }
}
