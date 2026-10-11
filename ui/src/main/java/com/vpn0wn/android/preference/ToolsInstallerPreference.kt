/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.preference

import com.vpn0wn.android.util.RootShell.RootShellException
import java.io.IOException
import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import com.vpn0wn.android.Application
import com.vpn0wn.android.R
import com.vpn0wn.android.util.ToolsInstaller
import com.vpn0wn.android.util.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Preference implementing a button that asynchronously runs `ToolsInstaller` and displays the
 * result as the preference summary.
 */
class ToolsInstallerPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    private var state = State.INITIAL
    override fun getSummary() = context.getString(state.messageResourceId)

    override fun getTitle() = context.getString(R.string.tools_installer_title)

    override fun onAttached() {
        super.onAttached()
        lifecycleScope.launch {
            try {
                val state = withContext(Dispatchers.IO) { Application.getToolsInstaller().areInstalled() }
                when (state) {
                    ToolsInstaller.YES or ToolsInstaller.MAGISK -> setState(State.SUCCESS_MAGISK)
                    ToolsInstaller.YES or ToolsInstaller.SYSTEM -> setState(State.ALREADY)
                    ToolsInstaller.NO or ToolsInstaller.MAGISK -> setState(State.INITIAL_MAGISK)
                    ToolsInstaller.BLOCKED -> setState(State.BLOCKED)
                    else -> setState(State.PROBE_FAILURE)
                }
            } catch (_: RootShellException) {
                setState(State.PROBE_FAILURE)
            }
        }
    }

    override fun onClick() {
        setState(State.WORKING)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { Application.getToolsInstaller().install() }
                when (result) {
                    ToolsInstaller.YES or ToolsInstaller.MAGISK -> setState(State.SUCCESS_MAGISK)
                    ToolsInstaller.YES or ToolsInstaller.SYSTEM -> setState(State.ALREADY)
                    ToolsInstaller.BLOCKED -> setState(State.BLOCKED)
                    else -> setState(State.FAILURE)
                }
            } catch (_: RootShellException) {
                setState(State.FAILURE)
            } catch (_: IOException) {
                setState(State.FAILURE)
            } catch (_: SecurityException) {
                setState(State.FAILURE)
            }
        }
    }

    private fun setState(state: State) {
        if (this.state == state) return
        this.state = state
        if (isEnabled != state.shouldEnableView) isEnabled = state.shouldEnableView
        notifyChanged()
    }

    private enum class State(val messageResourceId: Int, val shouldEnableView: Boolean) {
        INITIAL(R.string.tools_installer_initial, true),
        ALREADY(R.string.tools_installer_already, false),
        BLOCKED(R.string.tools_installer_blocked, false),
        PROBE_FAILURE(R.string.tools_installer_probe_failure, true),
        FAILURE(R.string.tools_installer_failure, true),
        WORKING(R.string.tools_installer_working, false),
        INITIAL_MAGISK(R.string.tools_installer_initial_magisk, true),
        SUCCESS_MAGISK(R.string.tools_installer_success_magisk, false);
    }
}
