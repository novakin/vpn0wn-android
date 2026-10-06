/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.model

import kotlinx.coroutines.CancellationException
import android.util.Log
import androidx.databinding.BaseObservable
import androidx.databinding.Bindable
import com.wireguard.android.BR
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.databinding.Keyed
import com.wireguard.android.util.applicationScope
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Encapsulates the volatile and nonvolatile state of a WireGuard tunnel.
 */
class ObservableTunnel internal constructor(
    private val manager: TunnelManager,
    private var name: String,
    config: Config?,
    state: Tunnel.State
) : BaseObservable(), Keyed<String>, Tunnel {
    override val key
        get() = name

    @Bindable
    override fun getName() = name

    suspend fun setNameAsync(name: String): String = withContext(Dispatchers.Main.immediate) {
        if (name != this@ObservableTunnel.name)
            manager.setTunnelName(this@ObservableTunnel, name)
        else
            this@ObservableTunnel.name
    }

    fun onNameChanged(name: String): String {
        this.name = name
        notifyPropertyChanged(BR.name)
        return name
    }


    @get:Bindable
    var state = state
        private set

    override fun onStateChange(newState: Tunnel.State) {
        manager.onTunnelStateChange(this)
    }

    fun onStateChanged(state: Tunnel.State): Tunnel.State {
        if (state != Tunnel.State.UP) onStatisticsChanged(null)
        if (state == this.state) return state
        this.state = state
        notifyPropertyChanged(BR.state)
        return state
    }

    suspend fun setStateAsync(state: Tunnel.State): Tunnel.State = withContext(Dispatchers.Main.immediate) {
        manager.setTunnelState(this@ObservableTunnel, state)
    }


    private var cachedConfig = config

    @get:Bindable
    // The asynchronous getter handles the generic ConfigStore/Backend contract.
    @Suppress("TooGenericExceptionCaught")
    var config: Config?
        get() {
            if (cachedConfig == null)
            // Opportunistically fetch this if we don't have a cached one, and rely on data bindings to update it eventually
                applicationScope.launch {
                    try {
                        manager.getTunnelConfig(this@ObservableTunnel)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to fetch tunnel config", e)
                    }
                }
            return cachedConfig
        }
        private set(value) {
            cachedConfig = value
        }

    suspend fun getConfigAsync(): Config = withContext(Dispatchers.Main.immediate) {
        cachedConfig ?: manager.getTunnelConfig(this@ObservableTunnel)
    }

    suspend fun setConfigAsync(config: Config): Config = withContext(Dispatchers.Main.immediate) {
        cachedConfig.let {
            if (config != it)
                manager.setTunnelConfig(this@ObservableTunnel, config)
            else
                it
        }
    }

    fun onConfigChanged(config: Config?): Config? {
        if (config === cachedConfig) return config
        this.config = config
        notifyPropertyChanged(BR.config)
        return config
    }

    fun peekConfig(): Config? = cachedConfig


    private var cachedStatistics: Statistics? = null

    @get:Bindable
    // The asynchronous getter handles the generic ConfigStore/Backend contract.
    @Suppress("TooGenericExceptionCaught")
    var statistics: Statistics?
        get() {
            if (state == Tunnel.State.UP && (cachedStatistics == null || cachedStatistics?.isStale != false))
            // Opportunistically fetch this if we don't have a cached one, and rely on data bindings to update it eventually
                applicationScope.launch {
                    try {
                        manager.getTunnelStatistics(this@ObservableTunnel)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to fetch tunnel statistics", e)
                    }
                }
            return cachedStatistics
        }
        private set(value) {
            cachedStatistics = value
        }

    suspend fun getStatisticsAsync(): Statistics = withContext(Dispatchers.Main.immediate) {
        cachedStatistics.let {
            if (it == null || it.isStale)
                manager.getTunnelStatistics(this@ObservableTunnel)
            else
                it
        }
    }

    fun onStatisticsChanged(statistics: Statistics?): Statistics? {
        if (statistics === cachedStatistics) return statistics
        this.statistics = statistics
        notifyPropertyChanged(BR.statistics)
        return statistics
    }

    fun peekStatistics(): Statistics? = cachedStatistics


    suspend fun deleteAsync() = manager.delete(this)


    companion object {
        private const val TAG = "WireGuard/ObservableTunnel"
    }
}
