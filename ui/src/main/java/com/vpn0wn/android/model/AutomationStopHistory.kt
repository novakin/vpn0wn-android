/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.model

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

internal object AutomationStopHistory {
    fun read(context: Context): List<NetworkAutomationState.StopStamp>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        return manager.getHistoricalProcessExitReasons(context.packageName, 0, 0)
            .filter { it.processName == context.packageName }
            .map { NetworkAutomationState.StopStamp(it.processName, it.pid, it.timestamp, it.reason) }
    }

    fun observe(state: NetworkAutomationState, history: List<NetworkAutomationState.StopStamp>?): NetworkAutomationState {
        if (!state.enabled || history == null) return state
        val acknowledged = state.acknowledgedStop
        val boundary = acknowledged?.let(history::indexOf) ?: history.size
        val stop = if (boundary >= 0) history.take(boundary).firstOrNull { it.reason == ApplicationExitInfo.REASON_USER_REQUESTED } else null
        return if (stop == null) state else state.copy(paused = true, stop = stop)
    }

    fun acknowledge(state: NetworkAutomationState, history: List<NetworkAutomationState.StopStamp>?): NetworkAutomationState =
        state.copy(paused = false, acknowledgedStop = history?.firstOrNull() ?: state.stop ?: state.acknowledgedStop)
}
