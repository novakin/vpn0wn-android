/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.model

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.databinding.BaseObservable
import androidx.databinding.Bindable
import androidx.datastore.preferences.core.edit
import com.vpn0wn.android.Application.Companion.get
import com.vpn0wn.android.Application.Companion.getBackend
import com.vpn0wn.android.Application.Companion.getTunnelManager
import com.vpn0wn.android.Application.Companion.getPreferencesDataStore
import com.vpn0wn.android.BR
import com.vpn0wn.android.R
import com.vpn0wn.android.backend.Backend
import com.vpn0wn.android.backend.Statistics
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.backend.WgQuickBackend
import com.vpn0wn.android.configStore.ConfigStore
import com.vpn0wn.android.databinding.ObservableSortedKeyedArrayList
import com.vpn0wn.android.util.ErrorMessages
import com.vpn0wn.android.util.AppDataTransfer
import com.vpn0wn.android.util.UserKnobs
import com.vpn0wn.android.util.applicationScope
import com.vpn0wn.config.Config
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
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
    private var lastUsedPersistence: Job? = null
    private var selectedLastUsedTunnel: ObservableTunnel? = null
    private val statisticsScope = applicationScope
    private val statisticsRequests = mutableMapOf<ObservableTunnel, StatisticsRequest>()

    private class StatisticsRequest(
        val generation: Long,
        val backend: Backend,
        val name: String,
        val read: Deferred<Statistics>,
    ) {
        var readers = 0
        val obsoleteCause = CancellationException("Tunnel statistics session changed")
    }

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

    internal suspend fun exportAppData(): AppDataTransfer.Archive = mutate {
        getTunnels()
        lastUsedPersistence?.join()
        val profiles = withContext(Dispatchers.IO) {
            tunnelMap.associate { it.name to configStore.load(it.name) }
        }
        val preferences = getPreferencesDataStore().data.first()
        withContext(Dispatchers.IO) { AppDataTransfer.snapshot(profiles, preferences).also(AppDataTransfer::validate) }
    }

    // ConfigStore/metadata failure needs owned-file rollback before propagating cancellation or defects.
    @Suppress("TooGenericExceptionCaught")
    internal suspend fun importAppData(archive: AppDataTransfer.Archive): Int = mutate {
        withContext(Dispatchers.IO) { AppDataTransfer.validate(archive) }
        getTunnels()
        lastUsedPersistence?.join()
        val existingNames = tunnelMap.map { it.name }.toSet()
        val newProfiles = withContext(Dispatchers.IO) {
            archive.profiles.filter { (name, config) ->
                if (name !in existingNames) true
                else {
                    if (configStore.load(name).toWgQuickString() != config.toWgQuickString()) throw AppDataTransfer.ConflictException()
                    false
                }
            }
        }
        val selection = archive.lastUsedTunnel
        if (selection != null && selection !in archive.profiles && !tunnelMap.containsKey(selection)) {
            throw AppDataTransfer.InvalidArchiveException()
        }
        val preferencesStore = getPreferencesDataStore()
        val restoreNames = preferencesStore.data.first()[UserKnobs.RUNNING_TUNNELS].orEmpty()
        if (restoreNames.any { it in newProfiles }) {
            // Commit stale-intent removal before files can survive a failed metadata commit and rollback.
            preferencesStore.edit { preferences ->
                AppDataTransfer.validateMetadata(archive, preferences)
                AppDataTransfer.clearRestoreMarkers(preferences, newProfiles.keys)
            }
        }
        val created = linkedMapOf<String, Config>()
        try {
            preferencesStore.edit { preferences ->
                val servers = AppDataTransfer.validateMetadata(archive, preferences)
                for ((name, config) in newProfiles) {
                    created[name] = withContext(Dispatchers.IO) { configStore.create(name, config) }
                }
                AppDataTransfer.applyMetadata(archive, preferences, servers, created.keys)
            }
        } catch (failure: Throwable) {
            var incomplete = false
            for ((name, config) in created) {
                try {
                    withContext(Dispatchers.IO) { configStore.delete(name) }
                } catch (recovery: Throwable) {
                    incomplete = true
                    failure.addSuppressed(recovery)
                    // Coroutine stack recovery may unwrap a same-class copy at the next suspension boundary.
                    val cause = failure.cause
                    if (cause != null && cause.javaClass == failure.javaClass && cause !== recovery) cause.addSuppressed(recovery)
                    addToList(name, config, Tunnel.State.DOWN)
                }
            }
            if (incomplete) throwIncompleteTransferRecovery(failure)
            throw failure
        }
        for ((name, config) in created) addToList(name, config, Tunnel.State.DOWN)
        if (selection != null) publishLastUsedTunnel(tunnelMap[selection])
        created.size
    }

    private fun throwIncompleteTransferRecovery(failure: Throwable): Nothing {
        if (failure !is Exception || failure is RuntimeException && failure !is SecurityException) throw failure
        throw AppDataTransfer.IncompleteRecoveryException().apply { initCause(failure) }
    }

    // Backend/ConfigStore declare Exception; admitted mutations reconcile before rethrowing, and recovery failures stay attached.
    @Suppress("TooGenericExceptionCaught")
    suspend fun delete(tunnel: ObservableTunnel) = mutate {
        requireManaged(tunnel)
        tunnel.invalidateStatistics()
        val backend = getBackend()
        val originalState = withContext(Dispatchers.IO) { backend.getState(tunnel) }
        val wasLastUsed = tunnel == lastUsedTunnel
        val config = getDownConfig(tunnel, backend, originalState)
        withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.DOWN, config) }
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
    var lastUsedTunnel: ObservableTunnel?
        get() = selectedLastUsedTunnel
        private set(value) {
            if (!publishLastUsedTunnel(value)) return
            val previous = lastUsedPersistence
            lastUsedPersistence = applicationScope.launch {
                previous?.join()
                UserKnobs.setLastUsedTunnel(value?.name)
            }
        }

    private fun publishLastUsedTunnel(value: ObservableTunnel?): Boolean {
        if (value == selectedLastUsedTunnel) return false
        selectedLastUsedTunnel = value
        notifyPropertyChanged(BR.lastUsedTunnel)
        return true
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
            publishLastUsedTunnel(tunnelMap[lastUsedName])
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
        val eligible = tunnelMap.toList()
        val previouslyRunning = UserKnobs.runningTunnels.first()
        if (previouslyRunning.isEmpty()) return
        coroutineScope {
            eligible.filter { previouslyRunning.contains(it.name) }.map { tunnel ->
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
        tunnel.invalidateStatistics()
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
        tunnel.invalidateStatistics()
        val backend = getBackend()
        val originalState = withContext(Dispatchers.IO) { backend.getState(tunnel) }
        val wasLastUsed = tunnel == lastUsedTunnel
        if (wasLastUsed)
            lastUsedTunnel = null
        try {
            if (originalState == Tunnel.State.UP) {
                val config = getDownConfig(tunnel, backend, originalState)
                withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.DOWN, config) }
            }
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
            tunnel.invalidateStatistics()
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

    private suspend fun getDownConfig(tunnel: ObservableTunnel, backend: Backend, observedState: Tunnel.State): Config? =
        if (backend is WgQuickBackend && observedState == Tunnel.State.UP) tunnel.getConfigAsync() else null

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
            val config = if (target == Tunnel.State.DOWN) getDownConfig(tunnel, backend, newState) else tunnel.getConfigAsync()
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
                if (action == "com.vpn0wn.android.action.REFRESH_TUNNEL_STATES" ||
                    action == "com.wireguard.android.action.REFRESH_TUNNEL_STATES") {
                    manager.refreshTunnelStates()
                    return@launch
                }
                if (!UserKnobs.allowRemoteControlIntents.first())
                    return@launch
                val state = when (action) {
                    "com.vpn0wn.android.action.SET_TUNNEL_UP" -> Tunnel.State.UP
                    "com.vpn0wn.android.action.SET_TUNNEL_DOWN" -> Tunnel.State.DOWN
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
            tunnel.discardObsoleteStatistics()
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

    suspend fun getTunnelStatistics(tunnel: ObservableTunnel): Statistics? = withContext(Dispatchers.Main.immediate) {
        requireManaged(tunnel)
        if (backendSwitchPending || tunnel.state != Tunnel.State.UP) return@withContext null
        val request = operationLock.withLock {
            requireManaged(tunnel)
            if (backendSwitchPending || tunnel.state != Tunnel.State.UP) return@withContext null
            val backend = getBackend()
            if (tunnel.statisticsBackend != null && tunnel.statisticsBackend !== backend)
                tunnel.invalidateStatistics()
            val cached = tunnel.peekStatistics()
            if (cached != null && !cached.isStale && tunnel.statisticsBackend === backend)
                return@withContext cached
            var current = statisticsRequests[tunnel]
            if (current != null && current.backend !== backend) {
                tunnel.invalidateStatistics()
                current = null
            }
            if (current != null && current.generation != tunnel.statisticsGeneration) {
                invalidateTunnelStatistics(tunnel)
                current = null
            }
            if (current == null || current.read.isCancelled) {
                current = StatisticsRequest(tunnel.statisticsGeneration, backend, tunnel.name,
                    statisticsScope.async(Dispatchers.IO) {
                        runInterruptible { backend.getStatistics(tunnel) }
                    })
                statisticsRequests[tunnel] = current
            }
            current.readers++
            current
        }
        try {
            val statistics = request.read.await()
            currentCoroutineContext().ensureActive()
            statisticsScope.coroutineContext.ensureActive()
            if (!isCurrentStatisticsRequest(tunnel, request)) return@withContext null
            tunnel.onStatisticsChanged(statistics, request.backend, request.generation)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            statisticsScope.coroutineContext.ensureActive()
            if (e !== request.obsoleteCause && e.cause !== request.obsoleteCause) throw e
            null
        } finally {
            request.readers--
            if (request.readers == 0) {
                if (statisticsRequests[tunnel] === request) statisticsRequests.remove(tunnel)
                request.read.cancel()
            }
        }
    }

    private suspend fun isCurrentStatisticsRequest(tunnel: ObservableTunnel, request: StatisticsRequest): Boolean {
        if (getBackend() !== request.backend) return false
        if (backendSwitchPending || tunnel.state != Tunnel.State.UP) return false
        if (tunnel.statisticsGeneration != request.generation || tunnel.name != request.name) return false
        if (statisticsRequests[tunnel] !== request || tunnelMap[tunnel.name] !== tunnel) return false
        return true
    }

    internal fun invalidateTunnelStatistics(tunnel: ObservableTunnel) {
        val request = statisticsRequests[tunnel] ?: return
        if (request.generation == tunnel.statisticsGeneration) return
        statisticsRequests.remove(tunnel)
        request.read.cancel(request.obsoleteCause)
    }

    companion object {
        private const val TAG = "WireGuard/TunnelManager"
    }
}
