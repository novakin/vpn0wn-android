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
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.databinding.Keyed
import com.wireguard.android.util.applicationScope
import com.wireguard.config.Config
import java.util.concurrent.atomic.AtomicLong
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

    var transitionTarget: Tunnel.State? = null
        private set

    fun onTransitionStarted(target: Tunnel.State) {
        transitionTarget = target
        onStateChanged(Tunnel.State.TOGGLE)
    }

    override fun onStateChange(newState: Tunnel.State) {
        // Backend callbacks can precede Main's already-queued statistics publication.
        statisticsEpoch.incrementAndGet()
        manager.onTunnelStateChange(this)
    }

    fun onStateChanged(state: Tunnel.State): Tunnel.State {
        if (state != Tunnel.State.TOGGLE) transitionTarget = null
        val changed = state != this.state
        this.state = state
        if (changed) invalidateStatistics()
        else if (state != Tunnel.State.UP) onStatisticsChanged(null)
        if (changed) notifyPropertyChanged(BR.state)
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

    private val statisticsEpoch = AtomicLong()
    private var cachedStatisticsGeneration = 0L

    internal val statisticsGeneration: Long
        get() = statisticsEpoch.get()

    internal var statisticsBackend: Backend? = null
        private set

    @get:Bindable
    var statistics: Statistics?
        get() = cachedStatistics.takeIf { cachedStatisticsGeneration == statisticsGeneration }
        private set(value) {
            cachedStatistics = value
        }

    suspend fun getStatisticsAsync(): Statistics? = manager.getTunnelStatistics(this)

    fun onStatisticsChanged(
        statistics: Statistics?,
        backend: Backend? = null,
        generation: Long = statisticsGeneration,
    ): Statistics? {
        statisticsBackend = backend
        cachedStatisticsGeneration = generation
        if (statistics === cachedStatistics) return statistics
        this.statistics = statistics
        notifyPropertyChanged(BR.statistics)
        return statistics
    }

    fun peekStatistics(): Statistics? = statistics

    internal fun invalidateStatistics() {
        statisticsEpoch.incrementAndGet()
        manager.invalidateTunnelStatistics(this)
        onStatisticsChanged(null)
    }

    internal fun discardObsoleteStatistics() {
        manager.invalidateTunnelStatistics(this)
        if (cachedStatisticsGeneration != statisticsGeneration) onStatisticsChanged(null)
    }


    suspend fun deleteAsync() = manager.delete(this)


    companion object {
        private const val TAG = "WireGuard/ObservableTunnel"
    }
}
