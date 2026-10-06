/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.model

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.databinding.BaseObservable
import androidx.databinding.Bindable
import com.wireguard.android.Application.Companion.get
import com.wireguard.android.Application.Companion.getBackend
import com.wireguard.android.Application.Companion.getTunnelManager
import com.wireguard.android.BR
import com.wireguard.android.R
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.configStore.ConfigStore
import com.wireguard.android.databinding.ObservableSortedKeyedArrayList
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.util.UserKnobs
import com.wireguard.android.util.applicationScope
import com.wireguard.config.Config
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Maintains and mediates changes to the set of available WireGuard tunnels,
 */
class TunnelManager(private val configStore: ConfigStore) : BaseObservable() {
    internal class NotManagedException : IllegalStateException("Tunnel is no longer managed")

    private var tunnels = CompletableDeferred<ObservableSortedKeyedArrayList<String, ObservableTunnel>>()
    private var loadingJob: Job? = null
    private val operationLock = Mutex()
    private val context: Context = get()
    private val tunnelMap: ObservableSortedKeyedArrayList<String, ObservableTunnel> = ObservableSortedKeyedArrayList(TunnelComparator)
    private var haveLoaded = false
    private var backendSwitchPending = false

    private fun addToList(name: String, config: Config?, state: Tunnel.State): ObservableTunnel {
        val tunnel = ObservableTunnel(this, name, config, state)
        tunnelMap.add(tunnel)
        return tunnel
    }

    suspend fun getTunnels(): ObservableSortedKeyedArrayList<String, ObservableTunnel> = withContext(Dispatchers.Main.immediate) {
        if (!haveLoaded && loadingJob?.isActive != true)
            onCreate()
        tunnels.await()
    }

    private suspend fun <T> mutate(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) {
        operationLock.withLock {
            check(!backendSwitchPending) { "Backend switch awaits restart" }
            // Once admitted, finish backend/store reconciliation before honouring caller cancellation.
            val result = withContext(NonCancellable) { block() }
            currentCoroutineContext().ensureActive()
            result
        }
    }

    private fun requireManaged(tunnel: ObservableTunnel) {
        if (tunnelMap[tunnel.name] !== tunnel) throw NotManagedException()
    }

    suspend fun create(name: String, config: Config): ObservableTunnel = mutate {
        getTunnels()
        if (Tunnel.isNameInvalid(name))
            throw IllegalArgumentException(context.getString(R.string.tunnel_error_invalid_name))
        if (tunnelMap.containsKey(name))
            throw IllegalArgumentException(context.getString(R.string.tunnel_error_already_exists, name))
        addToList(name, withContext(Dispatchers.IO) { configStore.create(name, config) }, Tunnel.State.DOWN)
    }

    // Backend/ConfigStore declare Exception; admitted mutations reconcile before rethrowing, and recovery failures stay attached.
    @Suppress("TooGenericExceptionCaught")
    suspend fun delete(tunnel: ObservableTunnel) = mutate {
        requireManaged(tunnel)
        val backend = getBackend()
        val originalState = withContext(Dispatchers.IO) { backend.getState(tunnel) }
        val wasLastUsed = tunnel == lastUsedTunnel
        withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.DOWN, null) }
        tunnel.onStateChanged(Tunnel.State.DOWN)
        try {
            withContext(Dispatchers.IO) { configStore.delete(tunnel.name) }
        } catch (e: Exception) {
            if (originalState == Tunnel.State.UP) {
                try {
                    val config = tunnel.getConfigAsync()
                    tunnel.onStateChanged(withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.UP, config) })
                } catch (recovery: Exception) {
                    e.addSuppressed(recovery)
                }
            }
            throw e
        }
        tunnelMap.remove(tunnel)
        if (wasLastUsed)
            lastUsedTunnel = null
        saveState()
    }

    @get:Bindable
    var lastUsedTunnel: ObservableTunnel? = null
        private set(value) {
            if (value == field) return
            field = value
            notifyPropertyChanged(BR.lastUsedTunnel)
            applicationScope.launch { UserKnobs.setLastUsedTunnel(value?.name) }
        }

    suspend fun getTunnelConfig(tunnel: ObservableTunnel): Config = withContext(Dispatchers.Main.immediate) {
        tunnel.onConfigChanged(withContext(Dispatchers.IO) { configStore.load(tunnel.name) })!!
    }

    // Backend/ConfigStore declare Exception; admitted mutations reconcile before rethrowing, and recovery failures stay attached.
    @Suppress("TooGenericExceptionCaught")
    fun onCreate() {
        if (haveLoaded || loadingJob?.isActive == true)
            return
        if (tunnels.isCompleted)
            tunnels = CompletableDeferred()
        val result = tunnels
        loadingJob = applicationScope.launch {
            try {
                onTunnelsLoaded(withContext(Dispatchers.IO) { configStore.enumerate() }, withContext(Dispatchers.IO) { getBackend().runningTunnelNames })
                result.complete(tunnelMap)
            } catch (e: CancellationException) {
                result.cancel(e)
                throw e
            } catch (e: Exception) {
                result.completeExceptionally(e)
                Log.e(TAG, "Failed to load tunnels on create", e)
                return@launch
            }
            try {
                restoreState(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore tunnel state", e)
            }
        }.also { job ->
            job.invokeOnCompletion { cause ->
                if (cause != null)
                    result.completeExceptionally(cause)
            }
        }
    }

    private suspend fun onTunnelsLoaded(present: Iterable<String>, running: Collection<String>) {
        val lastUsedName = UserKnobs.lastUsedTunnel.first()
        for (name in present)
            addToList(name, null, if (running.contains(name)) Tunnel.State.UP else Tunnel.State.DOWN)
        if (lastUsedName != null)
            lastUsedTunnel = tunnelMap[lastUsedName]
        haveLoaded = true
    }

    // Backend/ConfigStore declare Exception; admitted mutations reconcile before rethrowing, and recovery failures stay attached.
    @Suppress("TooGenericExceptionCaught")
    private fun refreshTunnelStates() {
        applicationScope.launch {
            try {
                mutate {
                    refreshObservedStates()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh tunnel states", e)
            }
        }
    }

    // Backend/ConfigStore declare Exception; admitted mutations reconcile before rethrowing, and recovery failures stay attached.
    @Suppress("TooGenericExceptionCaught")
    suspend fun restoreState(force: Boolean) {
        if (!haveLoaded || (!force && !UserKnobs.restoreOnBoot.first()))
            return
        val previouslyRunning = UserKnobs.runningTunnels.first()
        if (previouslyRunning.isEmpty()) return
        coroutineScope {
            tunnelMap.filter { previouslyRunning.contains(it.name) }.map { tunnel ->
                async {
                    try {
                        setTunnelState(tunnel, Tunnel.State.UP)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restore tunnel ${tunnel.name}", e)
                    }
                }
            }.awaitAll()
        }
    }

    suspend fun saveState() {
        UserKnobs.setRunningTunnels(tunnelMap.filter { it.state == Tunnel.State.UP }.map { it.name }.toSet())
    }

    private suspend fun refreshObservedStates() {
        val running = withContext(Dispatchers.IO) { getBackend().runningTunnelNames }
        for (tunnel in tunnelMap)
            tunnel.onStateChanged(if (running.contains(tunnel.name)) Tunnel.State.UP else Tunnel.State.DOWN)
    }

    // Backend/ConfigStore declare Exception; admitted mutations reconcile before rethrowing, and recovery failures stay attached.
    @Suppress("TooGenericExceptionCaught")
    suspend fun setTunnelConfig(tunnel: ObservableTunnel, config: Config): Config = mutate {
        requireManaged(tunnel)
        val previousConfig = tunnel.getConfigAsync()
        val backend = getBackend()
        tunnel.onConfigChanged(withContext(Dispatchers.IO) {
            val state = backend.getState(tunnel)
            backend.setState(tunnel, state, config)
            try {
                configStore.save(tunnel.name, config)
            } catch (e: Exception) {
                try {
                    backend.setState(tunnel, state, previousConfig)
                } catch (recovery: Exception) {
                    e.addSuppressed(recovery)
                }
                throw e
            }
        })!!
    }

    // Backend/ConfigStore declare Exception; admitted mutations reconcile before rethrowing, and recovery failures stay attached.
    @Suppress("TooGenericExceptionCaught")
    suspend fun setTunnelName(tunnel: ObservableTunnel, name: String): String = mutate {
        requireManaged(tunnel)
        if (Tunnel.isNameInvalid(name))
            throw IllegalArgumentException(context.getString(R.string.tunnel_error_invalid_name))
        if (tunnelMap.containsKey(name)) {
            throw IllegalArgumentException(context.getString(R.string.tunnel_error_already_exists, name))
        }
        val backend = getBackend()
        val originalState = withContext(Dispatchers.IO) { backend.getState(tunnel) }
        val wasLastUsed = tunnel == lastUsedTunnel
        if (wasLastUsed)
            lastUsedTunnel = null
        try {
            if (originalState == Tunnel.State.UP)
                withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.DOWN, null) }
            withContext(Dispatchers.IO) { configStore.rename(tunnel.name, name) }
            tunnelMap.remove(tunnel)
            tunnel.onNameChanged(name)
            tunnelMap.add(tunnel)
            if (originalState == Tunnel.State.UP)
                withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.UP, tunnel.getConfigAsync()) }
        } catch (e: Exception) {
            try {
                tunnel.onStateChanged(withContext(Dispatchers.IO) { backend.getState(tunnel) })
            } catch (recovery: Exception) {
                e.addSuppressed(recovery)
            }
            throw e
        } finally {
            tunnelMap.add(tunnel)
            if (wasLastUsed)
                lastUsedTunnel = tunnel
        }
        tunnel.name
    }

    suspend fun setTunnelState(tunnel: ObservableTunnel, state: Tunnel.State): Tunnel.State = mutate {
        requireManaged(tunnel)
        changeTunnelState(tunnel, state)
    }

    suspend fun prepareBackendSwitch(enableKernel: Boolean) = mutate {
        for (tunnel in tunnelMap)
            changeTunnelState(tunnel, Tunnel.State.DOWN)
        UserKnobs.setEnableKernelModule(enableKernel)
        backendSwitchPending = true
    }

    suspend fun cancelBackendSwitch(previousSetting: Boolean?) = withContext(Dispatchers.Main.immediate + NonCancellable) {
        operationLock.withLock {
            UserKnobs.setEnableKernelModule(previousSetting)
            backendSwitchPending = false
        }
    }

    // Backend/ConfigStore declare Exception; admitted mutations reconcile before rethrowing, and recovery failures stay attached.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun changeTunnelState(tunnel: ObservableTunnel, state: Tunnel.State): Tunnel.State {
        val backend = getBackend()
        var newState = withContext(Dispatchers.IO) { backend.getState(tunnel) }
        val target = if (state == Tunnel.State.TOGGLE) {
            if (newState == Tunnel.State.UP) Tunnel.State.DOWN else Tunnel.State.UP
        } else state
        if (target == newState) {
            tunnel.onStateChanged(newState)
            refreshObservedStates()
            saveState()
            return newState
        }
        var failure: Exception? = null
        tunnel.onTransitionStarted(target)
        try {
            val config = if (target == Tunnel.State.DOWN) null else tunnel.getConfigAsync()
            newState = withContext(Dispatchers.IO) { backend.setState(tunnel, target, config) }
            if (newState == Tunnel.State.UP)
                lastUsedTunnel = tunnel
        } catch (e: Exception) {
            failure = e
            try {
                newState = withContext(Dispatchers.IO) { backend.getState(tunnel) }
            } catch (recovery: Exception) {
                e.addSuppressed(recovery)
            }
        } finally {
            tunnel.onStateChanged(newState)
        }
        try {
            refreshObservedStates()
            saveState()
        } catch (e: Exception) {
            if (failure == null)
                throw e
            failure.addSuppressed(e)
        }
        if (failure != null)
            throw failure
        return newState
    }

    class IntentReceiver : BroadcastReceiver() {
        // Backend/ConfigStore declare Exception; admitted mutations reconcile before rethrowing, and recovery failures stay attached.
        @Suppress("TooGenericExceptionCaught")
        override fun onReceive(context: Context, intent: Intent?) {
            applicationScope.launch {
                val manager = getTunnelManager()
                if (intent == null) return@launch
                val action = intent.action ?: return@launch
                if ("com.wireguard.android.action.REFRESH_TUNNEL_STATES" == action) {
                    manager.refreshTunnelStates()
                    return@launch
                }
                if (!UserKnobs.allowRemoteControlIntents.first())
                    return@launch
                val state = when (action) {
                    "com.wireguard.android.action.SET_TUNNEL_UP" -> Tunnel.State.UP
                    "com.wireguard.android.action.SET_TUNNEL_DOWN" -> Tunnel.State.DOWN
                    else -> return@launch
                }
                val tunnelName = intent.getStringExtra("tunnel") ?: return@launch
                val tunnels = manager.getTunnels()
                val tunnel = tunnels[tunnelName] ?: return@launch
                try {
                    manager.setTunnelState(tunnel, state)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.makeText(context, ErrorMessages[e], Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    suspend fun getTunnelState(tunnel: ObservableTunnel): Tunnel.State = mutate {
        requireManaged(tunnel)
        tunnel.onStateChanged(withContext(Dispatchers.IO) { getBackend().getState(tunnel) })
    }

    // Backend/ConfigStore declare Exception; admitted mutations reconcile before rethrowing, and recovery failures stay attached.
    @Suppress("TooGenericExceptionCaught")
    internal fun onTunnelStateChange(tunnel: ObservableTunnel) {
        applicationScope.launch {
            try {
                mutate {
                    if (tunnelMap[tunnel.name] === tunnel) {
                        tunnel.onStateChanged(withContext(Dispatchers.IO) { getBackend().getState(tunnel) })
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reconcile backend state notification", e)
            }
        }
    }

    suspend fun getTunnelStatistics(tunnel: ObservableTunnel): Statistics = withContext(Dispatchers.Main.immediate) {
        tunnel.onStatisticsChanged(withContext(Dispatchers.IO) { getBackend().getStatistics(tunnel) })!!
    }

    companion object {
        private const val TAG = "WireGuard/TunnelManager"
    }
}
