/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.vpn0wn.android.preference

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.lifecycle.Lifecycle
import androidx.preference.Preference
import com.google.android.material.snackbar.Snackbar
import com.vpn0wn.android.QuickTileService
import com.vpn0wn.android.R
import com.vpn0wn.android.util.activity
import com.vpn0wn.android.util.applicationScope
import com.vpn0wn.android.util.vpn0wnStyle
import com.vpn0wn.android.util.UserKnobs
import kotlinx.coroutines.launch
import java.io.IOException

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class QuickTilePreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    override fun getSummary() = context.getString(R.string.quick_settings_tile_add_summary)

    override fun getTitle() = context.getString(R.string.quick_settings_tile_add_title)

    override fun onClick() {
        val removalRevision = UserKnobs.quickTileRemovalRevision
        val statusBarManager = context.getSystemService(StatusBarManager::class.java)
        statusBarManager.requestAddTileService(
            ComponentName(context, QuickTileService::class.java),
            context.getString(R.string.quick_settings_tile_action),
            Icon.createWithResource(context, R.drawable.ic_tile),
            context.mainExecutor
        ) {
            when (it) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED,
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> {
                    applicationScope.launch {
                        try {
                            UserKnobs.setQuickTileAdded(true, removalRevision)
                        } catch (_: IOException) {
                            Log.w("WireGuard/QuickTile", "Unable to retain quick tile membership")
                        }
                    }
                }
                StatusBarManager.TILE_ADD_REQUEST_ERROR_MISMATCHED_PACKAGE,
                StatusBarManager.TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS,
                StatusBarManager.TILE_ADD_REQUEST_ERROR_BAD_COMPONENT,
                StatusBarManager.TILE_ADD_REQUEST_ERROR_NOT_CURRENT_USER,
                StatusBarManager.TILE_ADD_REQUEST_ERROR_APP_NOT_IN_FOREGROUND,
                StatusBarManager.TILE_ADD_REQUEST_ERROR_NO_STATUS_BAR_SERVICE -> {
                    val message = context.getString(R.string.quick_settings_tile_add_failure, it)
                    if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        Snackbar.make(activity.findViewById(android.R.id.content), message, Snackbar.LENGTH_SHORT).vpn0wnStyle().show()
                    } else {
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
