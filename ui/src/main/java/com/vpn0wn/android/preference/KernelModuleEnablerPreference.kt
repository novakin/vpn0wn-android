/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.preference

import java.io.IOException
import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.util.Log
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.vpn0wn.android.Application
import com.vpn0wn.android.R
import com.vpn0wn.android.activity.SettingsActivity
import com.vpn0wn.android.backend.WgQuickBackend
import com.vpn0wn.android.util.UserKnobs
import com.vpn0wn.android.util.activity
import com.vpn0wn.android.util.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

class KernelModuleEnablerPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    private var state = State.UNKNOWN

    init {
        isVisible = false
        lifecycleScope.launch {
            setState(if (Application.getBackend() is WgQuickBackend) State.ENABLED else State.DISABLED)
        }
    }

    override fun getSummary() = if (state == State.UNKNOWN) "" else context.getString(state.summaryResourceId)

    override fun getTitle() = if (state == State.UNKNOWN) "" else context.getString(state.titleResourceId)

    // Backend transitions declare Exception; cancellation propagates and failed preference rollback remains visible.
    @Suppress("TooGenericExceptionCaught")
    override fun onClick() {
        val previousState = state
        if (previousState != State.DISABLED && previousState != State.ENABLED)
            return
        setState(if (previousState == State.DISABLED) State.ENABLING else State.DISABLING)
        activity.lifecycleScope.launch {
            var previousSetting: Boolean? = null
            var changingSetting = false
            try {
                previousSetting = UserKnobs.enableKernelModule.first()
                changingSetting = true
                Application.getTunnelManager().getTunnels()
                Application.getTunnelManager().prepareBackendSwitch(previousState == State.DISABLED)
                val restartIntent = Intent(context, SettingsActivity::class.java)
                restartIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                restartIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                Application.get().startActivity(restartIntent)
                exitProcess(0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enable kernel module", e)
            } finally {
                if (changingSetting) {
                    try {
                        Application.getTunnelManager().cancelBackendSwitch(previousSetting)
                    } catch (recovery: IOException) {
                        Log.e(TAG, "Failed to restore backend preference", recovery)
                    }
                }
                setState(previousState)
            }
        }
    }

    private fun setState(state: State) {
        if (this.state == state) return
        this.state = state
        if (isEnabled != state.shouldEnableView) isEnabled = state.shouldEnableView
        if (isVisible != state.visible) isVisible = state.visible
        notifyChanged()
    }

    private enum class State(val titleResourceId: Int, val summaryResourceId: Int, val shouldEnableView: Boolean, val visible: Boolean) {
        UNKNOWN(0, 0, false, false),
        ENABLED(R.string.module_enabler_enabled_title, R.string.module_enabler_enabled_summary, true, true),
        DISABLED(R.string.module_enabler_disabled_title, R.string.module_enabler_disabled_summary, true, true),
        ENABLING(R.string.module_enabler_disabled_title, R.string.success_application_will_restart, false, true),
        DISABLING(R.string.module_enabler_enabled_title, R.string.success_application_will_restart, false, true);
    }

    companion object {
        private const val TAG = "WireGuard/KernelModuleEnablerPreference"
    }
}
