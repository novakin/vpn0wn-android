/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Modified for vpn0wn.
 */

package com.vpn0wn.android.util

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.vpn0wn.android.Application
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicLong

object UserKnobs {
    private val QUICK_TILE_ADDED = booleanPreferencesKey("quick_tile_added")
    // Request results and tile notifications arrive independently; removal invalidates earlier confirmations before asynchronous persistence.
    private val quickTileRemovalCounter = AtomicLong()
    val quickTileRemovalRevision: Long
        get() = quickTileRemovalCounter.get()

    val quickTileAdded: Flow<Boolean?>
        get() = Application.getPreferencesDataStore().data.map {
            it[QUICK_TILE_ADDED]
        }

    fun recordQuickTileRemoval() {
        quickTileRemovalCounter.incrementAndGet()
    }

    suspend fun setQuickTileAdded(added: Boolean, removalRevision: Long = quickTileRemovalRevision) {
        Application.getPreferencesDataStore().edit {
            if (!added || removalRevision == quickTileRemovalRevision) {
                it[QUICK_TILE_ADDED] = added
            }
        }
    }

    private val ENABLE_KERNEL_MODULE = booleanPreferencesKey("enable_kernel_module")
    val enableKernelModule: Flow<Boolean>
        get() = Application.getPreferencesDataStore().data.map {
            it[ENABLE_KERNEL_MODULE] ?: false
        }

    suspend fun setEnableKernelModule(enable: Boolean?) {
        Application.getPreferencesDataStore().edit {
            if (enable == null)
                it.remove(ENABLE_KERNEL_MODULE)
            else
                it[ENABLE_KERNEL_MODULE] = enable
        }
    }

    internal val MULTIPLE_TUNNELS = booleanPreferencesKey("multiple_tunnels")
    val multipleTunnels: Flow<Boolean>
        get() = Application.getPreferencesDataStore().data.map {
            it[MULTIPLE_TUNNELS] ?: false
        }

    internal suspend fun setMultipleTunnels(enabled: Boolean) {
        Application.getPreferencesDataStore().edit { it[MULTIPLE_TUNNELS] = enabled }
    }

    private val DARK_THEME = booleanPreferencesKey("dark_theme")
    val darkTheme: Flow<Boolean>
        get() = Application.getPreferencesDataStore().data.map {
            it[DARK_THEME] ?: false
        }

    suspend fun setDarkTheme(on: Boolean) {
        Application.getPreferencesDataStore().edit {
            it[DARK_THEME] = on
        }
    }

    private val ALLOW_REMOTE_CONTROL_INTENTS = booleanPreferencesKey("allow_remote_control_intents")
    val allowRemoteControlIntents: Flow<Boolean>
        get() = Application.getPreferencesDataStore().data.map {
            it[ALLOW_REMOTE_CONTROL_INTENTS] ?: false
        }

    private val RESTORE_ON_BOOT = booleanPreferencesKey("restore_on_boot")
    val restoreOnBoot: Flow<Boolean>
        get() = Application.getPreferencesDataStore().data.map {
            it[RESTORE_ON_BOOT] ?: false
        }

    internal val LAST_USED_TUNNEL = stringPreferencesKey("last_used_tunnel")
    val lastUsedTunnel: Flow<String?>
        get() = Application.getPreferencesDataStore().data.map {
            it[LAST_USED_TUNNEL]
        }

    suspend fun setLastUsedTunnel(lastUsedTunnel: String?) {
        Application.getPreferencesDataStore().edit {
            if (lastUsedTunnel == null)
                it.remove(LAST_USED_TUNNEL)
            else
                it[LAST_USED_TUNNEL] = lastUsedTunnel
        }
    }

    internal val RUNNING_TUNNELS = stringSetPreferencesKey("enabled_configs")
    val runningTunnels: Flow<Set<String>>
        get() = Application.getPreferencesDataStore().data.map {
            it[RUNNING_TUNNELS] ?: emptySet()
        }

    suspend fun setRunningTunnels(runningTunnels: Set<String>) {
        Application.getPreferencesDataStore().edit {
            setRunningTunnels(it, runningTunnels)
        }
    }

    internal fun setRunningTunnels(preferences: MutablePreferences, runningTunnels: Set<String>) {
        if (runningTunnels.isEmpty()) preferences.remove(RUNNING_TUNNELS)
        else preferences[RUNNING_TUNNELS] = runningTunnels
    }

    internal val transferBooleanKeys = listOf(DARK_THEME, MULTIPLE_TUNNELS, ALLOW_REMOTE_CONTROL_INTENTS, RESTORE_ON_BOOT)
}
