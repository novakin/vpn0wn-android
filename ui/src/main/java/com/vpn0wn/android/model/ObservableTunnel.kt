/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Modified for vpn0wn.
 */
package com.vpn0wn.android.model

import kotlinx.coroutines.CancellationException
import android.util.Log
import androidx.databinding.BaseObservable
import androidx.databinding.Bindable
import com.vpn0wn.android.BR
import com.vpn0wn.android.backend.Backend
import com.vpn0wn.android.backend.HandshakeObservation
import com.vpn0wn.android.backend.HandshakeSession
import com.vpn0wn.android.backend.RecoveryResult
import com.vpn0wn.android.backend.Statistics
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.databinding.Keyed
import com.vpn0wn.android.util.applicationScope
import com.vpn0wn.config.Config
import com.vpn0wn.crypto.Key
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
        configGeneration++
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
        if (newState == Tunnel.State.DOWN) handshakeSession.set(null)
        manager.onTunnelStateChange(this)
    }

    fun onStateChanged(state: Tunnel.State): Tunnel.State {
        if (state != Tunnel.State.TOGGLE) transitionTarget = null
        val changed = state != this.state
        this.state = state
        if (changed) invalidateStatistics()
        else if (state != Tunnel.State.UP) onStatisticsChanged(null)
        if (changed) notifyPropertyChanged(BR.state)
        if (state == Tunnel.State.DOWN && handshakeSession.get()?.isActive != true)
            clearHandshakeDiagnostics()
        return state
    }

    suspend fun setStateAsync(state: Tunnel.State): Tunnel.State = withContext(Dispatchers.Main.immediate) {
        manager.setTunnelState(this@ObservableTunnel, state)
    }


    private var cachedConfig = config

    internal var configGeneration = 0L
        private set

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
        configGeneration++
        this.config = config
        notifyPropertyChanged(BR.config)
        return config
    }

    fun peekConfig(): Config? = cachedConfig

    data class PeerHandshakeDiagnostics(
        val lastObservation: HandshakeObservation,
        val lastAttempt: HandshakeObservation? = null,
        val lastSendResult: HandshakeObservation? = null,
        val lastAuthenticated: HandshakeObservation? = null,
        val lastExhausted: HandshakeObservation? = null,
    )

    private val handshakeSession = AtomicReference<HandshakeSession?>()
    private var cachedHandshakeSession: HandshakeSession? = null
    private var cachedHandshakeDiagnostics: Map<Key, PeerHandshakeDiagnostics>? = null
    private var cachedRecoverySession: HandshakeSession? = null
    private var cachedRecoverySnapshot: RecoverySnapshot? = null

    data class RecoverySnapshot(
        val confirmedAdmissions: Int,
        val chargedAttempts: Int,
        val backendEntries: Int,
        val unknownSettlements: Int,
        val requestPending: Boolean,
        val automaticRecoveryDisabled: Boolean,
        val lastTriggerPeer: Key?,
        val lastRequestPeer: Key?,
        val lastRequestElapsedRealtimeMillis: Long?,
        val lastOutcome: RecoveryResult.Outcome?,
        val status: RecoveryStatus,
        val failureCategory: RecoveryFailureCategory?,
        val transportState: RecoveryTransportState,
        val powerState: RecoveryPowerState,
        val lastResultRecordedElapsedRealtimeMillis: Long? = null,
    )

    enum class RecoveryStatus {
        IDLE, PENDING, REQUESTED, BUSY_DEFERRED, COMPLETED, DROPPED_BUSY,
        DROPPED_COOLDOWN, DROPPED_ATTEMPT_LIMIT, DROPPED_ENTRY_LIMIT, DROPPED_DOZE,
        DISABLED, CANCELLED, FAILED, OBSOLETE
    }

    enum class RecoveryFailureCategory { CANCELLED, INTERRUPTED, IO, SERVICE_UNAVAILABLE, DEFECT }
    enum class RecoveryTransportState { UNKNOWN, READY, UNAVAILABLE }
    enum class RecoveryPowerState { UNKNOWN, NOT_DEEP_IDLE, DEEP_IDLE }

    internal val activeHandshakeSession: HandshakeSession?
        get() = handshakeSession.get()?.takeIf { it.isActive }

    @get:Bindable
    val recoverySnapshot: RecoverySnapshot?
        get() = cachedRecoverySnapshot.takeIf {
            val current = activeHandshakeSession
            current != null && current === cachedRecoverySession
        }

    internal fun onRecoverySnapshotChanged(session: HandshakeSession, snapshot: RecoverySnapshot) {
        if (activeHandshakeSession !== session) return
        cachedRecoverySession = session
        cachedRecoverySnapshot = snapshot
        notifyPropertyChanged(BR.recoverySnapshot)
    }

    @get:Bindable
    val handshakeDiagnostics: Map<Key, PeerHandshakeDiagnostics>?
        get() = cachedHandshakeDiagnostics.takeIf {
            val current = handshakeSession.get()
            current != null && current.isActive && current === cachedHandshakeSession
        }

    override fun onHandshakeSessionChange(session: HandshakeSession) {
        if (session.isActive) {
            while (true) {
                val current = handshakeSession.get()
                if (!session.isActive || (current != null && current.id > session.id)) return
                if (handshakeSession.compareAndSet(current, session)) break
            }
            applicationScope.launch(Dispatchers.Main) {
                if (handshakeSession.get() !== session || !session.isActive) return@launch
                if (cachedHandshakeSession !== session) {
                    cachedHandshakeSession = session
                    cachedHandshakeDiagnostics = emptyMap()
                    notifyPropertyChanged(BR.handshakeDiagnostics)
                }
                manager.onHandshakeSessionAccepted(this@ObservableTunnel, session)
            }
        } else if (handshakeSession.compareAndSet(session, null)) {
            applicationScope.launch(Dispatchers.Main) {
                if (handshakeSession.get() == null) clearHandshakeDiagnostics()
            }
        }
    }

    override fun onHandshakeObservation(observation: HandshakeObservation) {
        val session = observation.session()
        if (handshakeSession.get() !== session || !session.isActive) return
        applicationScope.launch(Dispatchers.Main) {
            if (handshakeSession.get() !== session || !session.isActive) return@launch
            val diagnostics = if (cachedHandshakeSession === session) cachedHandshakeDiagnostics.orEmpty() else emptyMap()
            val previous = diagnostics[observation.peer()]
            if (previous != null && previous.lastObservation.sequence() >= observation.sequence()) return@launch
            val peer = (previous ?: PeerHandshakeDiagnostics(observation)).copy(
                lastObservation = observation,
                lastAttempt = if (observation.event() == HandshakeObservation.Event.ATTEMPT) observation else previous?.lastAttempt,
                lastSendResult = if (observation.event() == HandshakeObservation.Event.SENT ||
                    observation.event() == HandshakeObservation.Event.SEND_FAILED) observation else previous?.lastSendResult,
                lastAuthenticated = if (observation.event() == HandshakeObservation.Event.AUTHENTICATED) observation else previous?.lastAuthenticated,
                lastExhausted = if (observation.event() == HandshakeObservation.Event.EXHAUSTED) observation else previous?.lastExhausted,
            )
            cachedHandshakeSession = session
            cachedHandshakeDiagnostics = diagnostics + (observation.peer() to peer)
            notifyPropertyChanged(BR.handshakeDiagnostics)
            if (observation.event() == HandshakeObservation.Event.EXHAUSTED)
                manager.onHandshakeExhausted(this@ObservableTunnel, observation)
        }
    }

    private fun clearHandshakeDiagnostics() {
        manager.onHandshakeSessionRetired(this)
        cachedHandshakeSession = null
        cachedRecoverySession = null
        if (cachedRecoverySnapshot != null) {
            cachedRecoverySnapshot = null
            notifyPropertyChanged(BR.recoverySnapshot)
        }
        if (cachedHandshakeDiagnostics == null) return
        cachedHandshakeDiagnostics = null
        notifyPropertyChanged(BR.handshakeDiagnostics)
    }


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
        private const val TAG = "vpn0wn/ObservableTunnel"
    }
}
