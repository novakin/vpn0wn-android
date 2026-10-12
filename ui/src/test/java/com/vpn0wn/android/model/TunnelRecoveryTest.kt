package com.vpn0wn.android.model

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.databinding.Observable
import androidx.test.core.app.ApplicationProvider
import com.vpn0wn.android.backend.GoBackend
import com.vpn0wn.android.backend.BackendException
import com.vpn0wn.android.backend.GoBackendHandshakeTest
import com.vpn0wn.android.backend.GoBackendLifecycleTest
import com.vpn0wn.android.backend.GoBackendRebindTest
import com.vpn0wn.android.BR
import com.vpn0wn.android.backend.HandshakeObservation
import com.vpn0wn.android.backend.RecoveryResult
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.backend.WgQuickBackend
import com.vpn0wn.android.util.RootShell
import com.vpn0wn.android.util.ToolsInstaller
import com.vpn0wn.android.util.UserKnobs
import com.vpn0wn.config.Config
import com.vpn0wn.config.InetEndpoint
import com.vpn0wn.config.InetEndpointTest
import com.vpn0wn.crypto.Key
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowVpnService

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(application = Application::class, sdk = [36], shadows = [TunnelRecoveryTest.NativeShadow::class,
    GoBackendRebindTest.BuilderShadow::class, GoBackendHandshakeTest.DescriptorShadow::class,
    GoBackendLifecycleTest.LibraryShadow::class, InetEndpointTest.NumericAddressShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalCoroutinesApi::class)
class TunnelRecoveryTest {
    @get:Rule val folder = TemporaryFolder()
    private val peer = Key.fromBytes(ByteArray(32) { 9 })
    private val otherPeer = Key.fromBytes(ByteArray(32) { 10 })

    @After fun tearDown() {
        Thread.interrupted()
        Dispatchers.resetMain()
    }

    @Test fun twoChargedAttemptsRequireThirtySecondsAndDoNotCreateIdleWork() = runTest {
        withFixture { fixture ->
            val session = fixture.tunnel.activeHandshakeSession
            val saved = fixture.env.store.configs.toMap()
            val running = UserKnobs.runningTunnels.first()
            emit(fixture, 1)
            val first = awaitSnapshot(fixture) { it.confirmedAdmissions == 1 && !it.requestPending }
            assertCounts(first, 1, 1, 1, 0)
            assertEquals(ObservableTunnel.RecoveryPowerState.NOT_DEEP_IDLE, first.powerState)
            clock(29_999)
            emit(fixture, 2)
            assertEquals(ObservableTunnel.RecoveryStatus.DROPPED_COOLDOWN, fixture.snapshot().status)
            assertEquals(1, NativeShadow.calls.size)
            clock(1)
            pump()
            assertEquals(1, NativeShadow.calls.size)
            emit(fixture, 3)
            val second = awaitSnapshot(fixture) { it.confirmedAdmissions == 2 && !it.requestPending }
            assertCounts(second, 2, 2, 2, 0)
            assertTrue(second.lastRequestElapsedRealtimeMillis!! - first.lastRequestElapsedRealtimeMillis!! >= 30_000)
            clock(30_000)
            emit(fixture, 4)
            assertEquals(ObservableTunnel.RecoveryStatus.DROPPED_ATTEMPT_LIMIT, fixture.snapshot().status)
            advanceTimeBy(600_000)
            pump()
            assertEquals(2, NativeShadow.calls.size)
            assertSame(session, fixture.tunnel.activeHandshakeSession)
            assertEquals(1, NativeShadow.startupCalls)
            assertEquals(1, GoBackendRebindTest.BuilderShadow.establishCalls)
            assertEquals(saved, fixture.env.store.configs)
            assertEquals(running, UserKnobs.runningTunnels.first())
            assertTrue(NativeShadow.calls.all { it.peer.contentEquals(peer.bytes) })
        }
    }

    @Test fun busyGetsOneOneSecondDeferralAndFourBackendEntriesMaximum() = runTest {
        withFixture { fixture ->
            NativeShadow.response = { native(RecoveryResult.Outcome.BUSY) }
            emit(fixture, 1)
            awaitSnapshot(fixture) { it.backendEntries == 1 && it.status == ObservableTunnel.RecoveryStatus.BUSY_DEFERRED }
            advanceTimeBy(999)
            pump()
            assertEquals(1, NativeShadow.calls.size)
            advanceTimeBy(1)
            pump()
            assertCounts(awaitSnapshot(fixture) { it.backendEntries == 2 && !it.requestPending }, 0, 0, 2, 0)
            emit(fixture, 2)
            awaitSnapshot(fixture) { it.backendEntries == 3 && it.status == ObservableTunnel.RecoveryStatus.BUSY_DEFERRED }
            advanceTimeBy(1_000)
            pump()
            assertCounts(awaitSnapshot(fixture) { it.backendEntries == 4 && !it.requestPending }, 0, 0, 4, 0)
            emit(fixture, 3)
            assertEquals(ObservableTunnel.RecoveryStatus.DROPPED_ENTRY_LIMIT, fixture.snapshot().status)
            advanceTimeBy(60_000)
            pump()
            assertEquals(4, NativeShadow.calls.size)
        }
    }

    @Test fun deepDozeDropsWithoutChargeOrWakeQueueAndBusyDeferralRechecksIt() = runTest {
        withFixture { fixture ->
            idle(fixture, true)
            emit(fixture, 1)
            assertCounts(fixture.snapshot(), 0, 0, 0, 0)
            assertEquals(ObservableTunnel.RecoveryStatus.DROPPED_DOZE, fixture.snapshot().status)
            assertEquals(ObservableTunnel.RecoveryPowerState.DEEP_IDLE, fixture.snapshot().powerState)
            idle(fixture, false)
            advanceTimeBy(60_000)
            pump()
            assertTrue(NativeShadow.calls.isEmpty())
            NativeShadow.response = { native(RecoveryResult.Outcome.BUSY) }
            emit(fixture, 2)
            awaitSnapshot(fixture) { it.backendEntries == 1 && it.status == ObservableTunnel.RecoveryStatus.BUSY_DEFERRED }
            idle(fixture, true)
            advanceTimeBy(1_000)
            pump()
            assertCounts(awaitSnapshot(fixture) { !it.requestPending }, 0, 0, 1, 0)
            assertEquals(ObservableTunnel.RecoveryStatus.DROPPED_DOZE, fixture.snapshot().status)
            idle(fixture, false)
            NativeShadow.response = { native(RecoveryResult.Outcome.INITIATION_STARTED) }
            emit(fixture, 3)
            assertCounts(awaitSnapshot(fixture) { it.confirmedAdmissions == 1 && !it.requestPending }, 1, 1, 2, 0)
        }
    }

    @Test fun onePendingSlotExistsBeforeWaitingForManagerLock() = runTest {
        withFixture { fixture ->
            val lock = TunnelManager::class.java.getDeclaredField("operationLock").apply { isAccessible = true }
                .get(fixture.env.manager) as Mutex
            lock.lock()
            try {
                emit(fixture, 1)
                assertTrue(fixture.snapshot().requestPending)
                repeat(20) { emit(fixture, it.toLong() + 2, otherPeer) }
                assertTrue(fixture.snapshot().requestPending)
                assertTrue(NativeShadow.calls.isEmpty())
            } finally {
                lock.unlock()
            }
            assertCounts(awaitSnapshot(fixture) { it.confirmedAdmissions == 1 && !it.requestPending }, 1, 1, 1, 0)
            assertArrayEquals(peer.bytes, NativeShadow.calls.single().peer)
            assertEquals(1L, NativeShadow.calls.single().episode)
            assertEquals(peer, fixture.snapshot().lastRequestPeer)
            assertEquals(otherPeer, fixture.snapshot().lastTriggerPeer)
            clock(60_000)
            advanceTimeBy(60_000)
            pump()
            assertEquals(1, NativeShadow.calls.size)
        }
    }

    @Test fun manualNoOpUpCancelsBusyDeferralWithoutRefillingSameSessionBudget() = runTest {
        withFixture { fixture ->
            val session = fixture.tunnel.activeHandshakeSession
            emit(fixture, 1)
            awaitSnapshot(fixture) { it.confirmedAdmissions == 1 && !it.requestPending }
            clock(30_000)
            NativeShadow.response = { native(RecoveryResult.Outcome.BUSY) }
            emit(fixture, 2)
            awaitSnapshot(fixture) { it.backendEntries == 2 && it.status == ObservableTunnel.RecoveryStatus.BUSY_DEFERRED }
            fixture.tunnel.setStateAsync(Tunnel.State.UP)
            pump()
            advanceTimeBy(1_000)
            pump()
            assertCounts(fixture.snapshot(), 1, 1, 2, 0)
            assertFalse(fixture.snapshot().requestPending)
            assertSame(session, fixture.tunnel.activeHandshakeSession)
            assertEquals(ObservableTunnel.RecoveryTransportState.READY, fixture.snapshot().transportState)
            NativeShadow.response = { native(RecoveryResult.Outcome.INITIATION_STARTED) }
            emit(fixture, 3)
            assertCounts(awaitSnapshot(fixture) { it.confirmedAdmissions == 2 && !it.requestPending }, 2, 2, 3, 0)
            assertEquals(1, NativeShadow.startupCalls)
        }
    }

    @Test fun manualAdmissionCancelsPendingRecoveryBeforeBackendEntryWithoutCharge() = runTest {
        withFixture { fixture ->
            val lock = TunnelManager::class.java.getDeclaredField("operationLock").apply { isAccessible = true }
                .get(fixture.env.manager) as Mutex
            lock.lock()
            val manual = async { fixture.tunnel.setStateAsync(Tunnel.State.UP) }
            try {
                emit(fixture, 1)
                assertTrue(fixture.snapshot().requestPending)
                assertTrue(NativeShadow.calls.isEmpty())
            } finally {
                lock.unlock()
            }
            manual.await()
            assertCounts(awaitSnapshot(fixture) { !it.requestPending }, 0, 0, 0, 0)
            emit(fixture, 2)
            assertCounts(awaitSnapshot(fixture) { it.confirmedAdmissions == 1 && !it.requestPending }, 1, 1, 1, 0)
        }
    }

    @Test fun busyThenUnknownIoSettlementKeepsOneChargeAndDisablesFurtherWork() = runTest {
        withFixture { fixture ->
            NativeShadow.response = { call ->
                if (call.episode == 1L && NativeShadow.calls.size == 1) native(RecoveryResult.Outcome.BUSY)
                else throw IOException("Synthetic native recovery failure")
            }
            emit(fixture, 1)
            awaitSnapshot(fixture) { it.status == ObservableTunnel.RecoveryStatus.BUSY_DEFERRED }
            advanceTimeBy(1_000)
            pump()
            val failed = awaitSnapshot(fixture) { it.unknownSettlements == 1 && !it.requestPending }
            assertCounts(failed, 0, 1, 2, 1)
            assertEquals(ObservableTunnel.RecoveryFailureCategory.IO, failed.failureCategory)
            assertTrue(failed.automaticRecoveryDisabled)
            fixture.tunnel.setStateAsync(Tunnel.State.UP)
            clock(30_000)
            emit(fixture, 2)
            assertEquals(2, NativeShadow.calls.size)
            assertCounts(fixture.snapshot(), 0, 1, 2, 1)
            assertEquals(ObservableTunnel.RecoveryStatus.DISABLED, fixture.snapshot().status)
            val old = fixture.tunnel.activeHandshakeSession
            fixture.tunnel.setStateAsync(Tunnel.State.DOWN)
            pump()
            assertNull(fixture.tunnel.recoverySnapshot)
            fixture.recreateService()
            NativeShadow.response = { native(RecoveryResult.Outcome.INITIATION_STARTED) }
            fixture.tunnel.setStateAsync(Tunnel.State.UP)
            pump()
            assertNotSame(old, fixture.tunnel.activeHandshakeSession)
            emit(fixture, 3)
            assertCounts(awaitSnapshot(fixture) { it.confirmedAdmissions == 1 && !it.requestPending }, 1, 1, 1, 0)
        }
    }

    @Test fun reusableAdmittedFailureRequiresFreshEpisodeAndKeepsPeerAuthenticationHistory() = runTest {
        withFixture { fixture ->
            emit(fixture, 0, otherPeer, HandshakeObservation.Event.AUTHENTICATED)
            NativeShadow.response = { native(RecoveryResult.Outcome.TRANSPORT_FAILED) }
            emit(fixture, 1)
            val failed = awaitSnapshot(fixture) { it.confirmedAdmissions == 1 && !it.requestPending }
            assertCounts(failed, 1, 1, 1, 0)
            assertEquals(ObservableTunnel.RecoveryTransportState.UNAVAILABLE, failed.transportState)
            assertFalse(failed.automaticRecoveryDisabled)
            assertNotNull(fixture.tunnel.handshakeDiagnostics!![otherPeer]!!.lastAuthenticated)
            emit(fixture, 0, event = HandshakeObservation.Event.AUTHENTICATED)
            assertNotNull(fixture.tunnel.handshakeDiagnostics!![peer]!!.lastAuthenticated)
            assertEquals(ObservableTunnel.RecoveryTransportState.UNAVAILABLE, fixture.snapshot().transportState)
            clock(30_000)
            emit(fixture, 1)
            assertEquals(1, NativeShadow.calls.size)
            NativeShadow.response = { native(RecoveryResult.Outcome.INITIATION_SKIPPED) }
            emit(fixture, 2)
            val settled = awaitSnapshot(fixture) { it.confirmedAdmissions == 2 && !it.requestPending }
            assertCounts(settled, 2, 2, 2, 0)
            assertEquals(RecoveryResult.Outcome.INITIATION_SKIPPED, settled.lastOutcome)
            assertEquals(ObservableTunnel.RecoveryTransportState.READY, settled.transportState)
            assertNotNull(fixture.tunnel.handshakeDiagnostics!![otherPeer]!!.lastAuthenticated)
        }
    }

    @Test fun unusableContextDoesNotSpendRemainingBudget() = runTest {
        withFixture { fixture ->
            NativeShadow.response = { native(RecoveryResult.Outcome.TRANSPORT_FAILED) or (1 shl 8) }
            emit(fixture, 1)
            val failed = awaitSnapshot(fixture) { it.confirmedAdmissions == 1 && !it.requestPending }
            assertCounts(failed, 1, 1, 1, 0)
            assertEquals(ObservableTunnel.RecoveryTransportState.UNAVAILABLE, failed.transportState)
            assertTrue(failed.automaticRecoveryDisabled)
            clock(30_000)
            emit(fixture, 2)
            assertEquals(1, NativeShadow.calls.size)
            assertEquals(ObservableTunnel.RecoveryStatus.DISABLED, fixture.snapshot().status)
        }
    }

    @Test fun blockedNativeRecoveryDoesNotHoldManagerLockOrDelayActualDown() = runTest {
        withFixture { fixture ->
            val blocked = BlockedRecovery()
            NativeShadow.response = { blocked.run() }
            NativeShadow.turnOff = { blocked.release.countDown() }
            try {
                emit(fixture, 1)
                await(blocked.entered)
                val session = fixture.tunnel.activeHandshakeSession!!
                val down = async { fixture.tunnel.setStateAsync(Tunnel.State.DOWN) }
                down.await()
                pump()
                await(blocked.finished)
                assertFalse(session.isActive)
                assertEquals(Tunnel.State.DOWN, fixture.tunnel.state)
                assertNull(fixture.tunnel.recoverySnapshot)
                assertEquals(1, NativeShadow.calls.size)
                assertEquals(1, NativeShadow.turnOffCalls)
                assertEquals(1, NativeShadow.startupCalls)
                assertEquals(emptySet<String>(), UserKnobs.runningTunnels.first())
            } finally {
                blocked.release.countDown()
            }
        }
    }

    @Test fun manualSameSessionCancellationKeepsUnknownChargeAndDisablesRecovery() = runTest {
        withFixture { fixture ->
            val blocked = BlockedRecovery()
            val session = fixture.tunnel.activeHandshakeSession
            NativeShadow.response = { blocked.run() }
            try {
                emit(fixture, 1)
                await(blocked.entered)
                fixture.tunnel.setStateAsync(Tunnel.State.UP)
                pump()
                await(blocked.interrupted)
                assertSame(session, fixture.tunnel.activeHandshakeSession)
                blocked.release.countDown()
                val cancelled = awaitSnapshot(fixture) { it.unknownSettlements == 1 && !it.requestPending }
                assertCounts(cancelled, 0, 1, 1, 1)
                assertEquals(ObservableTunnel.RecoveryTransportState.UNKNOWN, cancelled.transportState)
                assertTrue(cancelled.automaticRecoveryDisabled)
                clock(30_000)
                emit(fixture, 2)
                assertEquals(1, NativeShadow.calls.size)
                assertEquals(ObservableTunnel.RecoveryStatus.DISABLED, fixture.snapshot().status)
                assertEquals(1, NativeShadow.startupCalls)
            } finally {
                blocked.release.countDown()
            }
        }
    }

    @Test fun onlyConfiguredExhaustionWithFreshTokenAndNumericEndpointEntersRecovery() = runTest {
        withFixture { fixture ->
            HandshakeObservation.Event.values().filter { it != HandshakeObservation.Event.EXHAUSTED }.forEach {
                emit(fixture, 1, event = it)
            }
            emit(fixture, 0)
            emit(fixture, 1, endpoint = "")
            emit(fixture, 2, key = Key.fromBytes(ByteArray(32) { 11 }))
            val rejection = assertThrows(InvocationTargetException::class.java) {
                capture(fixture, 2, endpoint = "example.invalid:51820")
            }
            assertTrue(rejection.cause is IllegalArgumentException)
            val session = fixture.tunnel.activeHandshakeSession!!
            fixture.tunnel.onHandshakeObservation(HandshakeObservation(session, peer, HandshakeObservation.Event.EXHAUSTED,
                true, InetEndpoint.parse("127.0.0.1:51820"), HandshakeObservation.AddressFamily.IPV6, 100, 1, 3))
            pump()
            fixture.tunnel.onHandshakeObservation(HandshakeObservation(session, peer, HandshakeObservation.Event.EXHAUSTED,
                true, InetEndpoint.parse("127.0.0.1:51820"), HandshakeObservation.AddressFamily.UNKNOWN, 101, 1, 4))
            pump()
            assertCounts(fixture.snapshot(), 0, 0, 0, 0)
            assertTrue(NativeShadow.calls.isEmpty())
        }
    }

    @Test fun duplicateAndOlderEpisodesCannotSpendAnotherChargeAfterCooldown() = runTest {
        withFixture { fixture ->
            emit(fixture, 7)
            awaitSnapshot(fixture) { it.confirmedAdmissions == 1 && !it.requestPending }
            clock(30_000)
            emit(fixture, 7)
            emit(fixture, 6)
            assertCounts(fixture.snapshot(), 1, 1, 1, 0)
            assertEquals(1, NativeShadow.calls.size)
            emit(fixture, 8)
            assertCounts(awaitSnapshot(fixture) { it.confirmedAdmissions == 2 && !it.requestPending }, 2, 2, 2, 0)
            assertEquals(listOf(7L, 8L), NativeShadow.calls.map { it.episode })
        }
    }

    @Test fun savedEndpointFreePeerCanTriggerWithLearnedNumericRuntimeEndpoint() = runTest {
        withFixture { fixture ->
            val saved = fixture.env.store.configs.toMap()
            assertFalse(fixture.tunnel.peekConfig()!!.peers.single { it.publicKey == otherPeer }.endpoint.isPresent)
            emit(fixture, 1, otherPeer)
            val result = awaitSnapshot(fixture) { it.confirmedAdmissions == 1 && !it.requestPending }
            assertEquals(otherPeer, result.lastRequestPeer)
            assertArrayEquals(otherPeer.bytes, NativeShadow.calls.single().peer)
            assertEquals(saved, fixture.env.store.configs)
        }
    }

    @Test fun kernelBackendCannotEnterUserspaceRecovery() = runTest {
        withFixture { fixture ->
            val context = fixture.env.application
            val shell = RootShell(context)
            fixture.env.replaceBackend(WgQuickBackend(context, shell, ToolsInstaller(context, shell)))
            try {
                emit(fixture, 1)
                assertCounts(awaitSnapshot(fixture) { !it.requestPending }, 0, 0, 0, 0)
                assertTrue(NativeShadow.calls.isEmpty())
            } finally {
                fixture.env.replaceBackend(fixture.backend)
            }
        }
    }

    @Test fun unavailablePowerServiceAllowsAdmissionWithUnknownPowerState() = runTest {
        withFixture { fixture ->
            val contextField = TunnelManager::class.java.getDeclaredField("context").apply { isAccessible = true }
            val original = contextField.get(fixture.env.manager) as Context
            contextField.set(fixture.env.manager, object : ContextWrapper(original) {
                override fun getSystemService(name: String): Any? =
                    if (name == Context.POWER_SERVICE) null else super.getSystemService(name)
            })
            emit(fixture, 1)
            val result = awaitSnapshot(fixture) { it.confirmedAdmissions == 1 && !it.requestPending }
            assertCounts(result, 1, 1, 1, 0)
            assertEquals(ObservableTunnel.RecoveryPowerState.UNKNOWN, result.powerState)
        }
    }

    @Test fun restorationDoesNotCancelSameSessionBusyDeferral() = runTest {
        withFixture { fixture ->
            val session = fixture.tunnel.activeHandshakeSession
            NativeShadow.response = {
                native(if (NativeShadow.calls.size == 1) RecoveryResult.Outcome.BUSY else RecoveryResult.Outcome.INITIATION_STARTED)
            }
            emit(fixture, 1)
            awaitSnapshot(fixture) { it.status == ObservableTunnel.RecoveryStatus.BUSY_DEFERRED }
            val request = recoveryRequestField(fixture, "job") as Job
            val lock = TunnelManager::class.java.getDeclaredField("operationLock").apply { isAccessible = true }
                .get(fixture.env.manager) as Mutex
            val restoring = synchronized(backendField(fixture.backend, "stateLock")) {
                val operation = async { fixture.env.manager.restoreState(true) }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!lock.isLocked) {
                    pump()
                    assertTrue("Restoration did not reach manager admission", System.nanoTime() < deadline)
                    Thread.yield()
                }
                assertTrue(fixture.snapshot().requestPending)
                assertFalse(request.isCancelled)
                assertEquals(1, NativeShadow.calls.size)
                operation
            }
            restoring.await()
            assertSame(session, fixture.tunnel.activeHandshakeSession)
            assertFalse(request.isCancelled)
            advanceTimeBy(1_000)
            pump()
            assertCounts(awaitSnapshot(fixture) { it.confirmedAdmissions == 1 && !it.requestPending }, 1, 1, 2, 0)
            assertEquals(listOf(1L, 1L), NativeShadow.calls.map { it.episode })
            assertEquals(1, NativeShadow.startupCalls)
        }
    }

    @Test fun cooldownStartsAtActualBackendEntryAfterDelayedDispatch() = runTest {
        withFixture { fixture ->
            var delayed = false
            val callback = object : Observable.OnPropertyChangedCallback() {
                override fun onPropertyChanged(sender: Observable, propertyId: Int) {
                    if (propertyId == BR.recoverySnapshot && !delayed &&
                        fixture.snapshot().status == ObservableTunnel.RecoveryStatus.REQUESTED) {
                        delayed = true
                        clock(5_000)
                    }
                }
            }
            fixture.tunnel.addOnPropertyChangedCallback(callback)
            try {
                emit(fixture, 1)
                awaitSnapshot(fixture) { it.confirmedAdmissions == 1 && !it.requestPending }
                assertTrue(delayed)
            } finally {
                fixture.tunnel.removeOnPropertyChangedCallback(callback)
            }
            clock(29_999)
            emit(fixture, 2)
            assertEquals(ObservableTunnel.RecoveryStatus.DROPPED_COOLDOWN, fixture.snapshot().status)
            assertEquals(1, NativeShadow.calls.size)
            clock(1)
            emit(fixture, 3)
            assertCounts(awaitSnapshot(fixture) { it.confirmedAdmissions == 2 && !it.requestPending }, 2, 2, 2, 0)
        }
    }

    @Test fun knownNativeFailureSurvivesManualCancellationBeforeMainContinuation() = runTest {
        withFixture { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            NativeShadow.response = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Native result fixture timed out" }
                native(RecoveryResult.Outcome.TRANSPORT_FAILED)
            }
            try {
                withContext(Dispatchers.Main.immediate) {
                    emit(fixture, 1)
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    val child = recoveryRequestField(fixture, "child") as Deferred<*>
                    release.countDown()
                    awaitWithoutPumping { child.isCompleted }
                    fixture.tunnel.setStateAsync(Tunnel.State.UP)
                }
                val result = awaitSnapshot(fixture) { !it.requestPending }
                assertCounts(result, 1, 1, 1, 0)
                assertEquals(RecoveryResult.Outcome.TRANSPORT_FAILED, result.lastOutcome)
                assertEquals(ObservableTunnel.RecoveryTransportState.UNAVAILABLE, result.transportState)
                assertFalse(result.automaticRecoveryDisabled)
                assertEquals(1, NativeShadow.startupCalls)
            } finally {
                release.countDown()
            }
        }
    }

    @Test fun cancellationBeforeLazyRequestBodyClearsSlotWithoutBackendEntry() = runTest {
        withFixture { fixture ->
            val callback = object : Observable.OnPropertyChangedCallback() {
                override fun onPropertyChanged(sender: Observable, propertyId: Int) {
                    if (propertyId == BR.recoverySnapshot && fixture.snapshot().status == ObservableTunnel.RecoveryStatus.PENDING)
                        (recoveryRequestField(fixture, "job") as Job).cancel()
                }
            }
            fixture.tunnel.addOnPropertyChangedCallback(callback)
            try {
                emit(fixture, 1)
                assertCounts(awaitSnapshot(fixture) { !it.requestPending }, 0, 0, 0, 0)
                assertTrue(NativeShadow.calls.isEmpty())
            } finally {
                fixture.tunnel.removeOnPropertyChangedCallback(callback)
            }
            emit(fixture, 2)
            assertCounts(awaitSnapshot(fixture) { it.confirmedAdmissions == 1 && !it.requestPending }, 1, 1, 1, 0)
        }
    }

    @Test fun unexpectedExceptionRetainsIdentityAndPublishesOnlyFixedFailureCategory() = runTest {
        assertUnexpectedFailure(IllegalStateException("Synthetic detail must stay out of diagnostics"))
    }

    @Test fun nativeErrorRetainsIdentityAndPublishesOnlyFixedFailureCategory() = runTest {
        assertUnexpectedFailure(AssertionError("Synthetic detail must stay out of diagnostics"))
    }

    @Test fun cancelledNativeEntryStillPropagatesItsLateOriginalException() = runTest {
        assertCancelledLateFailure(IllegalStateException("Synthetic late native defect"))
    }

    @Test fun cancelledNativeEntryStillPropagatesItsLateOriginalError() = runTest {
        assertCancelledLateFailure(AssertionError("Synthetic late native defect"))
    }

    @Test fun otherBackendExceptionReasonRetainsItsOriginalDefectIdentity() = runTest {
        assertUnexpectedFailure(BackendException(BackendException.Reason.DNS_RESOLUTION_FAILURE))
    }

    // JNI exposes the original callback cause; reflection adds a wrapper that this fixture must remove.
    @Suppress("SwallowedException")
    @Test fun destroyedServiceCallbackSettlesAsExpectedUnavailableWithoutUncaughtFailure() = runTest {
        val failures = CopyOnWriteArrayList<Throwable>()
        val handler = CoroutineExceptionHandler { _, failure -> failures += failure }
        withFixture(handler) { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val destroyed = GoBackend.VpnService::class.java.getDeclaredField("destroyed").apply { isAccessible = true }
            val activeCheck = GoBackend.VpnService::class.java.getDeclaredMethod("isNativeServiceActive", Thread::class.java)
                .apply { isAccessible = true }
            NativeShadow.response = { call ->
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Service callback fixture timed out" }
                try {
                    activeCheck.invoke(fixture.service, call.caller)
                } catch (failure: InvocationTargetException) {
                    val cause = failure.cause
                    if (cause == null) throw failure
                    throw cause
                }
                native(RecoveryResult.Outcome.INITIATION_STARTED)
            }
            try {
                emit(fixture, 1)
                await(entered)
                val activation = TunnelManager::class.java.getDeclaredField("recoveryActivation").apply { isAccessible = true }
                    .get(fixture.env.manager)
                val request = recoveryRequestField(fixture, "job") as Job
                lateinit var destruction: Deferred<Unit>
                synchronized(NativeShadow.stateLock) {
                    destruction = async(Dispatchers.IO) { fixture.service.onDestroy() }
                    awaitWithoutPumping { destroyed.getBoolean(fixture.service) }
                    val child = recoveryRequestField(fixture, "child") as Deferred<*>
                    release.countDown()
                    awaitWithoutPumping { child.isCompleted }
                    pump()
                    fun settledField(name: String): Any? = activation.javaClass.getDeclaredField(name)
                        .apply { isAccessible = true }.get(activation)
                    assertTrue(request.isCompleted)
                    assertEquals(0, settledField("confirmedAdmissions"))
                    assertEquals(1, settledField("chargedAttempts"))
                    assertEquals(1, settledField("backendEntries"))
                    assertEquals(1, settledField("unknownSettlements"))
                    assertNull(settledField("request"))
                    assertEquals(ObservableTunnel.RecoveryFailureCategory.SERVICE_UNAVAILABLE, settledField("failureCategory"))
                    assertEquals(ObservableTunnel.RecoveryTransportState.UNKNOWN, settledField("transportState"))
                    assertEquals(true, settledField("disabled"))
                    assertNull(fixture.tunnel.recoverySnapshot)
                    assertTrue(failures.isEmpty())
                }
                destruction.await()
                awaitCondition { fixture.tunnel.state == Tunnel.State.DOWN }
                assertNull(fixture.tunnel.recoverySnapshot)
                assertTrue(failures.isEmpty())
            } finally {
                release.countDown()
            }
        }
    }

    @Test fun obsoleteNativeCompletionCannotOverwriteReplacementSessionRecord() = runTest {
        withFixture { fixture ->
            val blocked = BlockedRecovery()
            NativeShadow.response = { blocked.run() }
            try {
                emit(fixture, 1)
                await(blocked.entered)
                val oldJob = recoveryRequestField(fixture, "job") as Job
                val oldSession = fixture.tunnel.activeHandshakeSession
                fixture.tunnel.setStateAsync(Tunnel.State.DOWN)
                fixture.recreateService()
                NativeShadow.response = { native(RecoveryResult.Outcome.INITIATION_STARTED) }
                fixture.tunnel.setStateAsync(Tunnel.State.UP)
                pump()
                assertNotSame(oldSession, fixture.tunnel.activeHandshakeSession)
                emit(fixture, 2)
                val current = awaitSnapshot(fixture) { it.confirmedAdmissions == 1 && !it.requestPending }
                blocked.release.countDown()
                oldJob.join()
                pump()
                assertSame(current, fixture.tunnel.recoverySnapshot)
                assertCounts(fixture.snapshot(), 1, 1, 1, 0)
                assertFalse(fixture.snapshot().automaticRecoveryDisabled)
                assertEquals(listOf(7, 8), NativeShadow.calls.map { it.handle })
            } finally {
                blocked.release.countDown()
            }
        }
    }

    @Test fun serviceDestructionCancelsBlockedNativeRecoveryAndClearsSnapshot() = runTest {
        withFixture { fixture ->
            val blocked = BlockedRecovery()
            NativeShadow.response = { blocked.run() }
            NativeShadow.turnOff = { blocked.release.countDown() }
            try {
                emit(fixture, 1)
                await(blocked.entered)
                val request = recoveryRequestField(fixture, "job") as Job
                fixture.service.onDestroy()
                request.join()
                awaitCondition { fixture.tunnel.state == Tunnel.State.DOWN }
                assertNull(fixture.tunnel.recoverySnapshot)
                assertNull(fixture.tunnel.activeHandshakeSession)
                assertEquals(1, NativeShadow.calls.size)
                assertEquals(1, NativeShadow.turnOffCalls)
            } finally {
                blocked.release.countDown()
            }
        }
    }

    @Test fun configAdmissionCancelsBusyDeferral() = runTest {
        assertMutationCancelsBusy { env.manager.setTunnelConfig(tunnel, tunnel.getConfigAsync()) }
    }

    @Test fun renameAdmissionCancelsBusyDeferral() = runTest {
        withFixture { fixture ->
            NativeShadow.response = { native(RecoveryResult.Outcome.BUSY) }
            emit(fixture, 1)
            awaitSnapshot(fixture) { it.status == ObservableTunnel.RecoveryStatus.BUSY_DEFERRED }
            NativeShadow.expectedName = "renamed"
            val renaming = async { fixture.tunnel.setNameAsync("renamed") }
            awaitCondition { !vpnServiceFuture().get().isDone }
            fixture.recreateService(resetFuture = false)
            assertEquals("renamed", renaming.await())
            advanceTimeBy(1_000)
            pump()
            assertEquals(1, NativeShadow.calls.size)
            assertFalse(fixture.tunnel.recoverySnapshot?.requestPending == true)
        }
    }

    @Test fun deleteAdmissionCancelsBusyDeferral() = runTest {
        assertMutationCancelsBusy { env.manager.delete(tunnel) }
    }

    @Test fun backendSwitchAdmissionCancelsBusyDeferral() = runTest {
        assertMutationCancelsBusy { env.manager.prepareBackendSwitch(true) }
    }

    private suspend fun TestScope.assertMutationCancelsBusy(mutation: suspend Fixture.() -> Unit) {
        withFixture { fixture ->
            NativeShadow.response = { native(RecoveryResult.Outcome.BUSY) }
            emit(fixture, 1)
            awaitSnapshot(fixture) { it.status == ObservableTunnel.RecoveryStatus.BUSY_DEFERRED }
            mutation(fixture)
            pump()
            advanceTimeBy(1_000)
            pump()
            assertEquals(1, NativeShadow.calls.size)
            assertFalse(fixture.tunnel.recoverySnapshot?.requestPending == true)
        }
    }

    private suspend fun TestScope.assertUnexpectedFailure(failure: Throwable) {
        val failures = CopyOnWriteArrayList<Throwable>()
        val reported = CountDownLatch(1)
        val handler = CoroutineExceptionHandler { _, observed -> failures += observed; reported.countDown() }
        withFixture(handler) { fixture ->
            NativeShadow.response = { throw failure }
            emit(fixture, 1)
            await(reported)
            val result = awaitSnapshot(fixture) { it.unknownSettlements == 1 && !it.requestPending }
            assertSame(failure, failures.single())
            assertCounts(result, 0, 1, 1, 1)
            assertEquals(ObservableTunnel.RecoveryStatus.FAILED, result.status)
            assertEquals(ObservableTunnel.RecoveryFailureCategory.DEFECT, result.failureCategory)
            assertEquals(ObservableTunnel.RecoveryTransportState.UNKNOWN, result.transportState)
            assertTrue(result.automaticRecoveryDisabled)
            assertNull(result.lastOutcome)
            failure.message?.let { assertFalse(result.toString().contains(it)) }
        }
    }

    private suspend fun TestScope.assertCancelledLateFailure(failure: Throwable) {
        val failures = CopyOnWriteArrayList<Throwable>()
        val reported = CountDownLatch(1)
        val handler = CoroutineExceptionHandler { _, observed -> failures += observed; reported.countDown() }
        withFixture(handler) { fixture ->
            val blocked = BlockedRecovery()
            val session = fixture.tunnel.activeHandshakeSession
            NativeShadow.response = {
                blocked.run()
                throw failure
            }
            try {
                emit(fixture, 1)
                await(blocked.entered)
                fixture.tunnel.setStateAsync(Tunnel.State.UP)
                await(blocked.interrupted)
                pump()
                assertSame(session, fixture.tunnel.activeHandshakeSession)
                assertTrue(fixture.snapshot().requestPending)
                assertEquals(0, fixture.snapshot().unknownSettlements)
                blocked.release.countDown()
                val result = awaitSnapshot(fixture) { it.unknownSettlements == 1 && !it.requestPending }
                assertTrue("Late native defect did not reach exception handler",
                    withContext(Dispatchers.IO) { reported.await(5, TimeUnit.SECONDS) })
                assertSame(failure, failures.single())
                assertCounts(result, 0, 1, 1, 1)
                assertEquals(ObservableTunnel.RecoveryStatus.FAILED, result.status)
                assertEquals(ObservableTunnel.RecoveryFailureCategory.DEFECT, result.failureCategory)
                assertTrue(result.automaticRecoveryDisabled)
                clock(30_000)
                emit(fixture, 2)
                assertEquals(1, NativeShadow.calls.size)
                assertEquals(ObservableTunnel.RecoveryStatus.DISABLED, fixture.snapshot().status)
            } finally {
                blocked.release.countDown()
            }
        }
    }

    private suspend fun TestScope.withFixture(handler: CoroutineExceptionHandler? = null, block: suspend TestScope.(Fixture) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        NativeShadow.reset()
        InetEndpointTest.NumericAddressShadow.unavailableAddress = null
        val scope = if (handler == null) backgroundScope else CoroutineScope(backgroundScope.coroutineContext + handler)
        val env = TunnelTestEnvironment(folder.root, scope)
        var fixture: Fixture? = null
        try {
            val backend = GoBackend(ApplicationProvider.getApplicationContext())
            NativeShadow.stateLock = backendField(backend, "stateLock")
            env.replaceBackend(backend)
            val tunnel = env.manager.create("fixture", profile())
            val activeFixture = Fixture(env, backend, tunnel)
            fixture = activeFixture
            activeFixture.recreateService()
            tunnel.setStateAsync(Tunnel.State.UP)
            pump()
            assertNotNull(tunnel.activeHandshakeSession)
            block(activeFixture)
        } finally {
            try {
                val activeFixture = fixture
                if (activeFixture != null) withContext(NonCancellable + Dispatchers.IO) {
                    activeFixture.backend.setState(activeFixture.tunnel, Tunnel.State.DOWN, null)
                }
                shadowOf(Looper.getMainLooper()).idle()
            } finally {
                env.close()
            }
        }
    }

    private inner class Fixture(val env: TunnelTestEnvironment, val backend: GoBackend, val tunnel: ObservableTunnel) {
        lateinit var service: GoBackend.VpnService

        fun recreateService(resetFuture: Boolean = true) {
            if (resetFuture) vpnServiceFuture().set(CompletableFuture())
            service = Robolectric.buildService(GoBackend.VpnService::class.java).create().get()
            ShadowVpnService.setPrepareResult(null)
            NativeShadow.expectedService = service
            GoBackendRebindTest.BuilderShadow.descriptor = ParcelFileDescriptor.open(folder.newFile(), ParcelFileDescriptor.MODE_READ_WRITE)
        }

        fun snapshot(): ObservableTunnel.RecoverySnapshot = checkNotNull(tunnel.recoverySnapshot)
    }

    private fun TestScope.emit(fixture: Fixture, episode: Long, key: Key = peer,
                               event: HandshakeObservation.Event = HandshakeObservation.Event.EXHAUSTED,
                               endpoint: String = "127.0.0.1:51820") {
        capture(fixture, episode, key, event, endpoint)
        pump()
    }

    private fun capture(fixture: Fixture, episode: Long, key: Key = peer,
                        event: HandshakeObservation.Event = HandshakeObservation.Event.EXHAUSTED,
                        endpoint: String = "127.0.0.1:51820") {
        GoBackend.VpnService::class.java.getDeclaredMethod("onNativeHandshakeObservation", Long::class.javaPrimitiveType,
            ByteArray::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(fixture.service, NativeShadow.activationId, key.bytes,
                event.ordinal + 1, event != HandshakeObservation.Event.AUTHENTICATED, endpoint, episode)
    }

    private fun TestScope.pump() {
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
    }

    // A real-IO suspension lets runTest consume the virtual BUSY delay before the waiting test observes admission.
    private fun TestScope.awaitSnapshot(fixture: Fixture,
                                       condition: (ObservableTunnel.RecoverySnapshot) -> Boolean): ObservableTunnel.RecoverySnapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            pump()
            val snapshot = fixture.tunnel.recoverySnapshot
            if (snapshot != null && condition(snapshot)) return snapshot
            assertTrue("Recovery did not settle: $snapshot", System.nanoTime() < deadline)
            Thread.yield()
        }
    }

    private suspend fun await(latch: CountDownLatch) {
        assertTrue("Recovery fixture did not reach its barrier", withContext(Dispatchers.IO) { latch.await(5, TimeUnit.SECONDS) })
    }

    private fun TestScope.awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            pump()
            assertTrue("Recovery fixture did not settle", System.nanoTime() < deadline)
            Thread.yield()
        }
    }

    private fun awaitWithoutPumping(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            assertTrue("Native receipt did not settle", System.nanoTime() < deadline)
            Thread.yield()
        }
    }

    private fun recoveryRequestField(fixture: Fixture, name: String): Any {
        val activation = checkNotNull(TunnelManager::class.java.getDeclaredField("recoveryActivation")
            .apply { isAccessible = true }.get(fixture.env.manager))
        val request = checkNotNull(activation.javaClass.getDeclaredField("request").apply { isAccessible = true }.get(activation))
        return checkNotNull(request.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(request))
    }

    @Suppress("UNCHECKED_CAST")
    private fun vpnServiceFuture(): AtomicReference<CompletableFuture<GoBackend.VpnService>> =
        GoBackend::class.java.getDeclaredField("vpnServiceRef").apply { isAccessible = true }
            .get(null) as AtomicReference<CompletableFuture<GoBackend.VpnService>>

    private fun idle(fixture: Fixture, idle: Boolean) {
        val power = fixture.env.application.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(power).setIsDeviceIdleMode(idle)
    }

    private fun clock(milliseconds: Long) = ShadowSystemClock.advanceBy(Duration.ofMillis(milliseconds))

    private fun assertCounts(snapshot: ObservableTunnel.RecoverySnapshot, admissions: Int, charges: Int, entries: Int, unknown: Int) {
        assertEquals(admissions, snapshot.confirmedAdmissions)
        assertEquals(charges, snapshot.chargedAttempts)
        assertEquals(entries, snapshot.backendEntries)
        assertEquals(unknown, snapshot.unknownSettlements)
    }

    private fun profile(): Config = Config.parse("""
        [Interface]
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        ListenPort = 51820
        Address = 192.0.2.2/32
        [Peer]
        PublicKey = ${peer.toBase64()}
        AllowedIPs = 192.0.2.1/32
        Endpoint = 127.0.0.1:51820
        [Peer]
        PublicKey = ${otherPeer.toBase64()}
        AllowedIPs = 192.0.2.3/32
    """.trimIndent().byteInputStream())

    private class BlockedRecovery {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val finished = CountDownLatch(1)

        fun run(): Int {
            entered.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var wasInterrupted = false
            try {
                while (true) {
                    try {
                        check(release.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) { "Recovery fixture timed out" }
                        if (wasInterrupted) Thread.currentThread().interrupt()
                        return native(RecoveryResult.Outcome.INITIATION_STARTED)
                    } catch (failure: InterruptedException) {
                        wasInterrupted = true
                        interrupted.countDown()
                    }
                }
            } finally {
                finished.countDown()
            }
        }
    }

    data class NativeCall(val handle: Int, val peer: ByteArray, val episode: Long, val endpoint: String, val caller: Thread)

    @Implements(value = GoBackend::class, isInAndroidSdk = false)
    // Robolectric instantiates this JNI shadow through a public no-argument constructor.
    @Suppress("UtilityClassWithPublicConstructor")
    class NativeShadow {
        companion object {
            val calls = CopyOnWriteArrayList<NativeCall>()
            var response: (NativeCall) -> Int = { native(RecoveryResult.Outcome.INITIATION_STARTED) }
            var turnOff: (() -> Unit)? = null
            var nextHandle = 7
            var startupCalls = 0
            var turnOffCalls = 0
            var activationId = 0L
            var expectedName = "fixture"
            lateinit var expectedService: GoBackend.VpnService
            lateinit var stateLock: Any

            fun reset() {
                calls.clear()
                response = { native(RecoveryResult.Outcome.INITIATION_STARTED) }
                turnOff = null
                nextHandle = 7
                startupCalls = 0
                turnOffCalls = 0
                activationId = 0
                expectedName = "fixture"
                GoBackendRebindTest.BuilderShadow.establishCalls = 0
            }

            @Implementation @JvmStatic fun wgTurnOn(name: String, fd: Int, settings: String,
                service: GoBackend.VpnService, thread: Thread, id: Long): Int {
                assertEquals(expectedName, name)
                assertEquals(73, fd)
                assertTrue(settings.contains("listen_port=51820"))
                assertSame(expectedService, service)
                assertSame(Thread.currentThread(), thread)
                activationId = id
                startupCalls++
                return nextHandle++
            }

            @Implementation @JvmStatic fun wgRecover(handle: Int, peer: ByteArray, episode: Long, endpoint: String, caller: Thread): Int {
                assertFalse(Thread.holdsLock(stateLock))
                assertNotSame(Looper.getMainLooper().thread, Thread.currentThread())
                assertSame(Thread.currentThread(), caller)
                val call = NativeCall(handle, peer.copyOf(), episode, endpoint, caller)
                calls += call
                return response(call)
            }

            @Implementation @JvmStatic fun wgTurnOff(handle: Int) {
                assertTrue(handle >= 7)
                turnOffCalls++
                turnOff?.invoke()
            }

            // Robolectric must retain the zero-argument JNI method shape.
            @Suppress("FunctionOnlyReturningConstant")
            @Implementation @JvmStatic fun wgVersion() = "synthetic"
        }
    }

    companion object {
        private fun native(outcome: RecoveryResult.Outcome) = outcome.ordinal + 1

        private fun backendField(backend: GoBackend, name: String): Any =
            checkNotNull(GoBackend::class.java.getDeclaredField(name).apply { isAccessible = true }.get(backend))
    }
}
