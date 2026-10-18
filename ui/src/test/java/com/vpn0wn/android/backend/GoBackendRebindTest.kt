package com.vpn0wn.android.backend

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.VpnService
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.vpn0wn.config.Config
import com.vpn0wn.config.InetEndpoint
import com.vpn0wn.crypto.Key
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
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
import org.robolectric.shadows.ShadowVpnService

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(application = GoBackendRebindTest.ServiceStartApplication::class, sdk = [36], shadows = [GoBackendRebindTest.NativeShadow::class,
    GoBackendRebindTest.BuilderShadow::class, GoBackendHandshakeTest.DescriptorShadow::class,
    GoBackendLifecycleTest.LibraryShadow::class, GoBackendRebindTest.TransportServiceShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class GoBackendRebindTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var backend: GoBackend
    private lateinit var service: GoBackend.VpnService
    private val states = Collections.synchronizedList(mutableListOf<Tunnel.State>())
    private val sessions = mutableListOf<HandshakeSession>()
    private val down = CountDownLatch(1)
    private var stateCallback: ((Tunnel.State) -> Unit)? = null
    private val tunnel = object : Tunnel {
        override fun getName() = "fixture"
        override fun onStateChange(newState: Tunnel.State) {
            states += newState
            if (newState == Tunnel.State.DOWN) down.countDown()
            stateCallback?.invoke(newState)
        }
        override fun onHandshakeSessionChange(session: HandshakeSession) { sessions += session }
    }

    @Before fun setUp() {
        backend = GoBackend(ApplicationProvider.getApplicationContext())
        @Suppress("UNCHECKED_CAST")
        val reference = field("vpnServiceRef").get(null) as AtomicReference<CompletableFuture<GoBackend.VpnService>>
        reference.set(CompletableFuture())
        service = Robolectric.buildService(GoBackend.VpnService::class.java).create().get()
        ShadowVpnService.setPrepareResult(null)
        BuilderShadow.establishCalls = 0
        BuilderShadow.beforeEstablish = null
        ServiceStartApplication.requested = null
        stateCallback = null
        NativeShadow.nextHandle = 7
        NativeShadow.rebindCalls.clear()
        NativeShadow.rebind = null
        NativeShadow.recover = null
        NativeShadow.recoveryCalls.clear()
        NativeShadow.turnOff = null
        NativeShadow.turnOn = null
        NativeShadow.transport = null
        NativeShadow.transportSnapshot = null
        TransportServiceShadow.reporting = null
        TransportServiceShadow.reported.clear()
        NativeShadow.stateLock = checkNotNull(field("stateLock").get(backend))
    }

    @After fun tearDown() {
        Thread.interrupted()
        NativeShadow.rebind = null
        NativeShadow.recover = null
        NativeShadow.turnOff = null
        NativeShadow.turnOn = null
        NativeShadow.transport = null
        NativeShadow.transportSnapshot = null
        BuilderShadow.beforeEstablish = null
        ServiceStartApplication.requested = null
        stateCallback = null
        TransportServiceShadow.reporting = null
        backend.setState(tunnel, Tunnel.State.DOWN, null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun successfulRebindPreservesExplicitPortConfigurationTunAndSession() {
        assertSuccessfulRebindPreservesOwnership(51820)
    }

    @Test fun successfulRebindPreservesAbsentListenPort() {
        assertSuccessfulRebindPreservesOwnership(null)
    }

    @Test fun inactiveAndWrongTunnelNeverEnterNativeRebind() {
        assertFalse(backend.rebind(tunnel))
        connect()
        val other = object : Tunnel {
            override fun getName() = "other"
            override fun onStateChange(newState: Tunnel.State) = Unit
        }
        assertFalse(backend.rebind(other))
        backend.setState(tunnel, Tunnel.State.DOWN, null)
        assertFalse(backend.rebind(tunnel))
        assertTrue(NativeShadow.rebindCalls.isEmpty())
    }

    @Test fun destroyedCurrentServiceIsRejectedBeforeNativeEntry() {
        connect()
        GoBackend.VpnService::class.java.getDeclaredField("destroyed").apply { isAccessible = true }.set(service, true)
        assertThrows(BackendException::class.java) { backend.rebind(tunnel) }
        assertTrue(NativeShadow.rebindCalls.isEmpty())
    }

    @Test fun disconnectCancelsBlockedRebindWithoutHoldingStateLock() {
        assertBlockedRebindTeardown(false)
    }

    @Test fun serviceDestructionCancelsBlockedRebindWithoutHoldingStateLock() {
        assertBlockedRebindTeardown(true)
    }

    @Test fun downDuringStartupJoinsSoleCleanupBeforePropagatingInterruption() {
        val nativeEntered = CountDownLatch(1)
        val releaseNative = CountDownLatch(1)
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val downThread = AtomicReference<Thread>()
        NativeShadow.turnOn = { _, handle ->
            nativeEntered.countDown()
            assertTrue(releaseNative.await(5, TimeUnit.SECONDS))
            handle
        }
        NativeShadow.turnOff = {
            cleanupEntered.countDown()
            assertTrue(releaseCleanup.await(5, TimeUnit.SECONDS))
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val starting = executor.submit<Throwable?> { runCatching { connect() }.exceptionOrNull() }
            assertTrue(nativeEntered.await(5, TimeUnit.SECONDS))
            val stopping = executor.submit<Throwable?> {
                downThread.set(Thread.currentThread())
                runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }.exceptionOrNull()
            }
            awaitRetiredStartup()
            downThread.get().interrupt()
            releaseNative.countDown()
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))
            assertFalse(stopping.isDone)
            assertFalse(starting.isDone)
            releaseCleanup.countDown()
            assertTrue(stopping.get(5, TimeUnit.SECONDS) is InterruptedException)
            assertTrue(starting.get(5, TimeUnit.SECONDS) is BackendException)
            assertEquals(Tunnel.State.DOWN, backend.getState(tunnel))
            assertFalse(states.contains(Tunnel.State.UP))
        } finally {
            releaseNative.countDown()
            releaseCleanup.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun downBeforeEstablishRetiresPreparedRequestAndPreventsStartupPublication() {
        val preparing = CountDownLatch(1)
        val release = CountDownLatch(1)
        BuilderShadow.beforeEstablish = {
            preparing.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val starting = executor.submit<Throwable?> { runCatching { connect() }.exceptionOrNull() }
            assertTrue(preparing.await(5, TimeUnit.SECONDS))
            val stopping = executor.submit { backend.setState(tunnel, Tunnel.State.DOWN, null) }
            awaitRetiredStartup()
            assertFalse(stopping.isDone)
            release.countDown()
            assertTrue(starting.get(5, TimeUnit.SECONDS) is BackendException)
            stopping.get(5, TimeUnit.SECONDS)
            assertEquals(Tunnel.State.DOWN, backend.getState(tunnel))
            assertFalse(states.contains(Tunnel.State.UP))
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun retirementBeforeReportRejectsCapturedPublication() {
        connect()
        val authority = GoBackend.TransportAuthority()
        val capture = capture(authority)
        authority.retire()
        assertFalse(callback(capture, "report", 91L, true))
        assertFalse(callback(capture, "confirm", 91L))
        assertTrue(TransportServiceShadow.reported.isEmpty())
    }

    @Test fun retirementAfterReportClaimPreventsNativeReadyConfirmation() {
        connect()
        val authority = GoBackend.TransportAuthority()
        val capture = capture(authority)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        TransportServiceShadow.reporting = {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            true
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val reporting = executor.submit<Boolean> { callback(capture, "report", 91L, true) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            authority.retire()
            release.countDown()
            assertTrue(reporting.get(5, TimeUnit.SECONDS))
            assertFalse(callback(capture, "confirm", 91L))
            assertFalse(requireNotNull(backend.getTransportSnapshot(tunnel)).ready())
            assertTrue(callback(capture, "report", 91L, false))
            val unavailable = requireNotNull(backend.getTransportSnapshot(tunnel))
            assertEquals(GoBackend.TransportMode.UNAVAILABLE, unavailable.mode())
            assertFalse(unavailable.ready())
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun confirmedCaptureReportsOnlyItsImmutableNetworkMode() {
        connect()
        val capture = capture(GoBackend.TransportAuthority())
        assertTrue(callback(capture, "report", 91L, true))
        assertFalse(requireNotNull(backend.getTransportSnapshot(tunnel)).ready())
        assertTrue(callback(capture, "confirm", 91L))
        val ready = requireNotNull(backend.getTransportSnapshot(tunnel))
        assertEquals(91L, ready.generation())
        assertEquals(GoBackend.TransportMode.LEGACY, ready.mode())
        assertTrue(ready.ready())
        assertNull(TransportServiceShadow.reported.single())
    }

    @Test fun requestRetiredWhileWaitingTransitionOwnerCannotTearDownPredecessor() {
        val original = connect()
        val replacement = Config.parse(original.toWgQuickString().byteInputStream())
        val authority = GoBackend.TransportAuthority()
        val worker = AtomicReference<Thread>()
        val entered = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val request = synchronized(field("transitionLock").get(backend)) {
                val pending = executor.submit<Throwable?> {
                    worker.set(Thread.currentThread())
                    entered.countDown()
                    runCatching { backend.setStateWithAuthority(tunnel, Tunnel.State.UP, replacement, authority) }.exceptionOrNull()
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (worker.get().state != Thread.State.BLOCKED) {
                    check(System.nanoTime() < deadline) { "Request did not wait for transition owner" }
                    Thread.sleep(1)
                }
                authority.retire()
                pending
            }
            assertTrue(request.get(5, TimeUnit.SECONDS) is IOException)
            assertSame(original, field("currentConfig").get(backend))
            assertEquals(Tunnel.State.UP, backend.getState(tunnel))
            assertEquals(1, BuilderShadow.establishCalls)
            assertEquals(listOf(Tunnel.State.UP), states)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun rollbackRetirementPreservesOriginalStartupFailureWithoutResurrection() {
        val original = connect()
        val replacement = Config.parse(original.toWgQuickString().byteInputStream())
        val authority = GoBackend.TransportAuthority()
        val failure = IOException("Synthetic startup failure")
        NativeShadow.turnOn = { _, handle ->
            if (handle == 8) throw failure
            handle
        }
        BuilderShadow.beforeEstablish = {
            if (BuilderShadow.establishCalls == 2) authority.retire()
        }
        BuilderShadow.descriptor = ParcelFileDescriptor.open(folder.newFile(), ParcelFileDescriptor.MODE_READ_WRITE)
        val requested = expectFreshServiceStart()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val starting = executor.submit<Throwable?> {
                runCatching { backend.setStateWithAuthority(tunnel, Tunnel.State.UP, replacement, authority) }.exceptionOrNull()
            }
            fulfillFreshServiceStart(requested)
            val actual = requireNotNull(starting.get(5, TimeUnit.SECONDS))
            assertSame(failure, actual)
            assertEquals(1, actual.suppressed.size)
            assertTrue(actual.suppressed.single() is BackendException)
            assertEquals(Tunnel.State.DOWN, backend.getState(tunnel))
            assertNull(field("currentConfig").get(backend))
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun destroyedServiceCannotExposeReadyTransportBeforeSessionRetirement() {
        connect()
        val capture = capture(GoBackend.TransportAuthority())
        assertTrue(callback(capture, "report", 91L, true))
        assertTrue(callback(capture, "confirm", 91L))
        assertTrue(requireNotNull(backend.getTransportSnapshot(tunnel)).ready())
        GoBackend.VpnService::class.java.getDeclaredField("destroyed").apply { isAccessible = true }.setBoolean(service, true)
        assertTrue(session().isActive)
        assertNull(backend.getTransportSnapshot(tunnel))
    }

    @Test fun nativeEmptyOrUnhealthySettlementClearsLegacyProofAcrossEveryEntry() {
        var needsFreshService = false
        for (entry in listOf("rebind", "recover", "replace")) {
            for (healthy in listOf(true, false)) {
                if (needsFreshService) createService()
                connect()
                val capture = capture(GoBackend.TransportAuthority())
                assertTrue(callback(capture, "report", 91L, true))
                assertTrue(callback(capture, "confirm", 91L))
                val expected = requireNotNull(backend.getTransportSnapshot(tunnel))
                NativeShadow.transportSnapshot = { (92L shl 2) or if (healthy) 2L else 0L }
                when (entry) {
                    "rebind" -> backend.rebind(tunnel)
                    "recover" -> backend.recover(tunnel, exhaustion())
                    else -> backend.replaceTransport(tunnel, expected, GoBackend.TransportMode.LEGACY, null,
                        GoBackend.TransportAuthority())
                }
                val actual = requireNotNull(backend.getTransportSnapshot(tunnel))
                assertFalse("$entry healthy=$healthy", actual.ready())
                assertEquals(if (healthy) GoBackend.TransportMode.UNAVAILABLE else GoBackend.TransportMode.UNKNOWN, actual.mode())
                backend.setState(tunnel, Tunnel.State.DOWN, null)
                needsFreshService = true
            }
        }
    }

    @Test fun failedNativeQueryPreservesOriginalThrowableAndReleasesSettlementCount() {
        connect()
        val capture = capture(GoBackend.TransportAuthority())
        assertTrue(callback(capture, "report", 91L, true))
        assertTrue(callback(capture, "confirm", 91L))
        val failure = IOException("Synthetic native failure")
        val settlement = AssertionError("Synthetic snapshot failure")
        NativeShadow.rebind = { throw failure }
        NativeShadow.transportSnapshot = { throw settlement }
        assertSame(failure, assertThrows(IOException::class.java) { backend.rebind(tunnel) })
        assertSame(settlement, failure.suppressed.single())
        val activation = requireNotNull(field("activation").get(backend))
        val operations = activation.javaClass.getDeclaredField("transportOperations").apply { isAccessible = true }
            .get(activation) as java.util.concurrent.atomic.AtomicInteger
        assertEquals(0, operations.get())
        val actual = requireNotNull(backend.getTransportSnapshot(tunnel))
        assertFalse(actual.ready())
        assertEquals(GoBackend.TransportMode.UNKNOWN, actual.mode())
        NativeShadow.rebind = null
        assertSame(settlement, assertThrows(AssertionError::class.java) { backend.rebind(tunnel) })
        assertEquals(0, operations.get())
    }

    @Test fun delayedOldSnapshotCannotOverwriteNewerConfirmedGeneration() {
        connect()
        val capture = capture(GoBackend.TransportAuthority())
        assertTrue(callback(capture, "report", 91L, true))
        assertTrue(callback(capture, "confirm", 91L))
        val queried = CountDownLatch(1)
        val release = CountDownLatch(1)
        val count = java.util.concurrent.atomic.AtomicInteger()
        NativeShadow.transportSnapshot = {
            if (count.incrementAndGet() == 1) {
                queried.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                (91L shl 2) or 2L
            } else (92L shl 2) or 3L
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val older = executor.submit<Boolean> { backend.rebind(tunnel) }
            assertTrue(queried.await(5, TimeUnit.SECONDS))
            assertFalse(requireNotNull(backend.getTransportSnapshot(tunnel)).ready())
            NativeShadow.rebind = {
                assertTrue(callback(capture, "report", 92L, true))
                assertTrue(callback(capture, "confirm", 92L))
                0
            }
            assertTrue(backend.rebind(tunnel))
            assertFalse(requireNotNull(backend.getTransportSnapshot(tunnel)).ready())
            release.countDown()
            assertTrue(older.get(5, TimeUnit.SECONDS))
            val actual = requireNotNull(backend.getTransportSnapshot(tunnel))
            assertEquals(92L, actual.generation())
            assertEquals(GoBackend.TransportMode.LEGACY, actual.mode())
            assertTrue(actual.ready())
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun delayedUnhealthyQueryCannotChangeReplacementActivation() {
        connect()
        val queried = CountDownLatch(1)
        val release = CountDownLatch(1)
        NativeShadow.transportSnapshot = {
            queried.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            0L
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val older = executor.submit<Boolean> { backend.rebind(tunnel) }
            assertTrue(queried.await(5, TimeUnit.SECONDS))
            backend.setState(tunnel, Tunnel.State.DOWN, null)
            createService()
            connect(51821)
            val replacement = session()
            val capture = capture(GoBackend.TransportAuthority())
            assertTrue(callback(capture, "report", 1L, true))
            assertTrue(callback(capture, "confirm", 1L))
            release.countDown()
            assertFalse(older.get(5, TimeUnit.SECONDS))
            val actual = requireNotNull(backend.getTransportSnapshot(tunnel))
            assertSame(replacement, actual.session())
            assertEquals(1L, actual.generation())
            assertEquals(GoBackend.TransportMode.LEGACY, actual.mode())
            assertTrue(actual.ready())
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun capture(authority: GoBackend.TransportAuthority): Any {
        val type = GoBackend::class.java.declaredClasses.single { it.simpleName == "SocketCapture" }
        return type.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(backend, field("activation").get(backend), GoBackend.TransportMode.LEGACY, null, authority)
    }

    private fun callback(capture: Any, name: String, generation: Long, ready: Boolean? = null): Boolean {
        val method = if (ready == null) capture.javaClass.getDeclaredMethod(name, Long::class.javaPrimitiveType)
        else capture.javaClass.getDeclaredMethod(name, Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        return (if (ready == null) method.invoke(capture, generation) else method.invoke(capture, generation, ready)) as Boolean
    }

    @Test fun newerDownDuringConfigStartupCannotRestorePreviousConfig() {
        connect()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        NativeShadow.turnOn = { _, handle ->
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            handle
        }
        val requested = expectFreshServiceStart()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val starting = executor.submit<Throwable?> { runCatching { connect(51821) }.exceptionOrNull() }
            fulfillFreshServiceStart(requested)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val stopping = executor.submit { backend.setState(tunnel, Tunnel.State.DOWN, null) }
            awaitRetiredStartup()
            release.countDown()
            assertTrue(starting.get(5, TimeUnit.SECONDS) is BackendException)
            stopping.get(5, TimeUnit.SECONDS)
            assertEquals(Tunnel.State.DOWN, backend.getState(tunnel))
            assertEquals(2, BuilderShadow.establishCalls)
            assertEquals(listOf(Tunnel.State.UP, Tunnel.State.DOWN), states)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun awaitRetiredStartup() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            val transition = field("startingTransition").get(backend)
            if (transition != null && transition.javaClass.getDeclaredField("cancelledByDown").apply { isAccessible = true }.getBoolean(transition)) return
            check(System.nanoTime() < deadline) { "DOWN did not retire startup" }
            Thread.sleep(1)
        }
    }

    @Test fun downAfterJavaHandlePublicationDrainsActiveHandleBeforeSettling() {
        val published = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val closes = java.util.concurrent.atomic.AtomicInteger()
        stateCallback = { state ->
            if (state == Tunnel.State.UP) {
                published.countDown()
                assertTrue(releasePublication.await(5, TimeUnit.SECONDS))
            }
        }
        NativeShadow.turnOff = {
            closes.incrementAndGet()
            cleanupEntered.countDown()
            assertTrue(releaseCleanup.await(5, TimeUnit.SECONDS))
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val starting = executor.submit { connect() }
            assertTrue(published.await(5, TimeUnit.SECONDS))
            assertNull(field("startingTransition").get(backend))
            val stopping = executor.submit { backend.setState(tunnel, Tunnel.State.DOWN, null) }
            releasePublication.countDown()
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))
            assertFalse(stopping.isDone)
            releaseCleanup.countDown()
            starting.get(5, TimeUnit.SECONDS)
            stopping.get(5, TimeUnit.SECONDS)
            assertEquals(1, closes.get())
            assertEquals(Tunnel.State.DOWN, backend.getState(tunnel))
            assertNull(field("currentTunnel").get(backend))
            assertEquals(-1, field("currentTunnelHandle").getInt(backend))
        } finally {
            releasePublication.countDown()
            releaseCleanup.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun retiringRequestDuringFailedStartupCannotMintAuthorityForRollback() {
        val original = connect()
        val replacement = Config.parse(original.toWgQuickString().replace("ListenPort = 51820", "ListenPort = 51821").byteInputStream())
        BuilderShadow.descriptor = ParcelFileDescriptor.open(folder.newFile(), ParcelFileDescriptor.MODE_READ_WRITE)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = IOException("Synthetic startup failure")
        val authority = GoBackend.TransportAuthority()
        NativeShadow.turnOn = { _, _ ->
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            throw failure
        }
        val requested = expectFreshServiceStart()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val starting = executor.submit<Throwable?> {
                runCatching { backend.setStateWithAuthority(tunnel, Tunnel.State.UP, replacement, authority) }.exceptionOrNull()
            }
            fulfillFreshServiceStart(requested)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            authority.retire()
            release.countDown()
            assertSame(failure, starting.get(5, TimeUnit.SECONDS))
            assertEquals(Tunnel.State.DOWN, backend.getState(tunnel))
            assertEquals(2, BuilderShadow.establishCalls)
            assertEquals(listOf(Tunnel.State.UP, Tunnel.State.DOWN), states)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun replacementOfSameTunnelRejectsDelayedNativeSuccess() {
        connect()
        val previous = sessions.single()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        NativeShadow.rebind = {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            0
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val rebound = executor.submit<Boolean> { backend.rebind(tunnel) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            backend.setState(tunnel, Tunnel.State.DOWN, null)
            service = Robolectric.buildService(GoBackend.VpnService::class.java).create().get()
            connect()
            assertEquals(8, field("currentTunnelHandle").get(backend))
            assertFalse(previous.isActive)
            assertTrue(sessions.last().isActive)
            release.countDown()
            assertFalse(rebound.get(5, TimeUnit.SECONDS))
            assertEquals(Tunnel.State.UP, backend.getState(tunnel))
            assertSame(sessions.last(), session())
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun busyAndStaleResultsLeaveCurrentTunnelIntact() {
        val config = connect()
        val activation = field("activation").get(backend)
        for (result in listOf(-16, -19)) {
            NativeShadow.rebind = { result }
            assertFalse(backend.rebind(tunnel))
            assertSame(config, field("currentConfig").get(backend))
            assertSame(activation, field("activation").get(backend))
            assertEquals(Tunnel.State.UP, backend.getState(tunnel))
        }
    }

    @Test fun nativeIoFailureRemainsAnIoFailureWithoutChangingOwnership() {
        val config = connect()
        val active = session()
        NativeShadow.rebind = { -5 }
        assertThrows(IOException::class.java) { backend.rebind(tunnel) }
        assertSame(config, field("currentConfig").get(backend))
        assertSame(active, session())
        assertTrue(active.isActive)
        assertEquals(Tunnel.State.UP, backend.getState(tunnel))
    }

    @Test fun interruptedCallerIsRejectedBeforeNativeEntryWithoutClearingInterruption() {
        connect()
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) { backend.rebind(tunnel) }
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(NativeShadow.rebindCalls.isEmpty())
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun interruptionDuringNativeWorkCannotReportSuccessfulRebind() {
        connect()
        NativeShadow.rebind = { Thread.currentThread().interrupt(); 0 }
        try {
            assertThrows(InterruptedException::class.java) { backend.rebind(tunnel) }
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(Tunnel.State.UP, backend.getState(tunnel))
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun interruptionRacingStaleIdentityCannotReportOrdinaryFailure() {
        connect()
        NativeShadow.rebind = {
            backend.setState(tunnel, Tunnel.State.DOWN, null)
            Thread.currentThread().interrupt()
            0
        }
        try {
            assertThrows(InterruptedException::class.java) { backend.rebind(tunnel) }
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(Tunnel.State.DOWN, backend.getState(tunnel))
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun nativeInterruptionRetainsIdentity() {
        connect()
        val interruption = InterruptedException("Synthetic native cancellation")
        NativeShadow.rebind = { throw interruption }
        assertSame(interruption, assertThrows(InterruptedException::class.java) { backend.rebind(tunnel) })
        assertEquals(Tunnel.State.UP, backend.getState(tunnel))
    }

    @Test fun nativeProgrammingFailureRetainsIdentity() {
        connect()
        val defect = IllegalStateException("Synthetic native defect")
        NativeShadow.rebind = { throw defect }
        assertSame(defect, assertThrows(IllegalStateException::class.java) { backend.rebind(tunnel) })
        assertEquals(Tunnel.State.UP, backend.getState(tunnel))
    }

    @Test fun nativeErrorRetainsIdentity() {
        connect()
        val error = AssertionError("Synthetic native error")
        NativeShadow.rebind = { throw error }
        assertSame(error, assertThrows(AssertionError::class.java) { backend.rebind(tunnel) })
        assertEquals(Tunnel.State.UP, backend.getState(tunnel))
    }

    @Test fun recoveryChecksExpectedSessionBeforeNativeLookup() {
        connect()
        val old = exhaustion()
        backend.setState(tunnel, Tunnel.State.DOWN, null)
        service = Robolectric.buildService(GoBackend.VpnService::class.java).create().get()
        connect()
        assertEquals(RecoveryResult.Outcome.OBSOLETE, backend.recover(tunnel, old).outcome())
        assertTrue(NativeShadow.recoveryCalls.isEmpty())
        assertFalse(old.session().isActive)
    }

    @Test fun recoveryPreservesOwnershipAndSeparatesOutcomeFacts() {
        val config = connect()
        val active = session()
        val trigger = exhaustion()
        for ((index, outcome) in RecoveryResult.Outcome.values().withIndex()) {
            NativeShadow.recover = { index + 1 }
            val result = backend.recover(tunnel, trigger)
            assertEquals(outcome, result.outcome())
            assertEquals(index >= 3, result.admitted())
            assertEquals(index >= 4, result.transportReady())
            assertEquals(outcome == RecoveryResult.Outcome.INITIATION_STARTED ||
                outcome == RecoveryResult.Outcome.INITIATION_SEND_FAILED, result.initiationStarted())
            assertTrue(result.contextReusable())
            assertTrue(result.currentActivation())
        }
        assertSame(config, field("currentConfig").get(backend))
        assertSame(active, session())
        assertEquals(1, BuilderShadow.establishCalls)
        assertEquals(Tunnel.State.UP, backend.getState(tunnel))
    }

    @Test fun admittedUnavailableContextCannotReportReadyWithoutThrowable() {
        connect()
        NativeShadow.recover = { 5 or (1 shl 8) }
        val result = backend.recover(tunnel, exhaustion())
        assertTrue(result.admitted())
        assertTrue(result.initiationStarted())
        assertFalse(result.contextReusable())
        assertFalse(result.transportReady())
    }

    @Test fun admittedObsoleteCompletionKeepsChargeEvidence() {
        connect()
        val trigger = exhaustion()
        NativeShadow.recover = {
            backend.setState(tunnel, Tunnel.State.DOWN, null)
            service = Robolectric.buildService(GoBackend.VpnService::class.java).create().get()
            connect()
            5
        }
        val result = backend.recover(tunnel, trigger)
        assertTrue(result.admitted())
        assertFalse(result.currentActivation())
        assertFalse(result.transportReady())
        assertTrue(session().isActive)
        assertFalse(trigger.session().isActive)
    }

    @Test fun disconnectCancelsBlockedRecoveryOutsideStateLock() {
        assertBlockedRecoveryTeardown(false)
    }

    @Test fun serviceDestructionCancelsBlockedRecoveryOutsideStateLock() {
        assertBlockedRecoveryTeardown(true)
    }

    private fun assertBlockedRecoveryTeardown(destruction: Boolean) {
        connect()
        val trigger = exhaustion()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        NativeShadow.recover = {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            4
        }
        NativeShadow.turnOff = { release.countDown() }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val recovering = executor.submit<RecoveryResult> { backend.recover(tunnel, trigger) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            executor.submit {
                if (destruction) service.onDestroy() else backend.setState(tunnel, Tunnel.State.DOWN, null)
            }.get(5, TimeUnit.SECONDS)
            assertTrue(down.await(5, TimeUnit.SECONDS))
            val result = recovering.get(5, TimeUnit.SECONDS)
            assertTrue(result.admitted())
            assertFalse(result.currentActivation())
            assertEquals(Tunnel.State.DOWN, backend.getState(tunnel))
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun recoveryInterruptionPreservesFlagAndWaitsForNativeSettlement() {
        connect()
        val trigger = exhaustion()
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) { backend.recover(tunnel, trigger) }
            assertTrue(NativeShadow.recoveryCalls.isEmpty())
        } finally { Thread.interrupted() }
        NativeShadow.recover = { Thread.currentThread().interrupt(); 5 }
        try {
            assertThrows(InterruptedException::class.java) { backend.recover(tunnel, trigger) }
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(session().isActive)
        } finally { Thread.interrupted() }
    }

    @Test fun recoveryInterruptedDuringStateAdmissionNeverEntersNative() {
        connect()
        val trigger = exhaustion()
        val caller = AtomicReference<Thread>()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val waiting = synchronized(checkNotNull(field("stateLock").get(backend))) {
                val operation = executor.submit<Boolean> {
                    caller.set(Thread.currentThread())
                    assertThrows(InterruptedException::class.java) { backend.recover(tunnel, trigger) }
                    Thread.currentThread().isInterrupted
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (caller.get()?.state != Thread.State.BLOCKED) {
                    assertTrue("Recovery did not wait for state admission", System.nanoTime() < deadline)
                    Thread.yield()
                }
                caller.get().interrupt()
                operation
            }
            assertTrue(waiting.get(5, TimeUnit.SECONDS))
            assertTrue(NativeShadow.recoveryCalls.isEmpty())
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun recoveryPreservesNativeExceptionAndErrorIdentity() {
        connect()
        val trigger = exhaustion()
        for (failure in listOf(InterruptedException("Synthetic interruption"), IllegalStateException("Synthetic defect"), AssertionError("Synthetic Error"))) {
            NativeShadow.recover = { throw failure }
            assertSame(failure, assertThrows(failure.javaClass) { backend.recover(tunnel, trigger) })
            assertTrue(session().isActive)
        }
    }

    private fun exhaustion() = HandshakeObservation(session(),
        Key.fromBase64("CQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQk="), HandshakeObservation.Event.EXHAUSTED, true,
        InetEndpoint.parse("127.0.0.1:51820"), HandshakeObservation.AddressFamily.IPV4, 1, 1, 73)

    private fun assertSuccessfulRebindPreservesOwnership(port: Int?) {
        val config = connect(port)
        val activation = field("activation").get(backend)
        val active = session()
        NativeShadow.rebind = {
            assertFalse(Thread.holdsLock(activation))
            0
        }
        assertTrue(backend.rebind(tunnel))
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(config, field("currentConfig").get(backend))
        assertSame(service, field("currentService").get(backend))
        assertSame(activation, field("activation").get(backend))
        assertEquals(7, field("currentTunnelHandle").get(backend))
        assertEquals(port ?: 0, config.getInterface().getListenPort().orElse(0))
        assertEquals(port != null, config.getInterface().getListenPort().isPresent)
        assertSame(active, sessions.single())
        assertTrue(active.isActive)
        assertEquals(listOf(Tunnel.State.UP), states)
        assertEquals(listOf(7), NativeShadow.rebindCalls)
        assertEquals(1, BuilderShadow.establishCalls)
        assertEquals(8, NativeShadow.nextHandle)
    }

    private fun assertBlockedRebindTeardown(destruction: Boolean) {
        connect()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        NativeShadow.rebind = {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            0
        }
        NativeShadow.turnOff = { release.countDown() }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val rebound = executor.submit<Boolean> { backend.rebind(tunnel) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val teardown = executor.submit {
                if (destruction) service.onDestroy() else backend.setState(tunnel, Tunnel.State.DOWN, null)
            }
            teardown.get(5, TimeUnit.SECONDS)
            assertTrue(down.await(5, TimeUnit.SECONDS))
            assertFalse(rebound.get(5, TimeUnit.SECONDS))
            assertEquals(Tunnel.State.DOWN, backend.getState(tunnel))
            assertEquals(listOf(Tunnel.State.UP, Tunnel.State.DOWN), states)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun connect(port: Int? = 51820): Config {
        NativeShadow.expectedService = service
        BuilderShadow.descriptor = ParcelFileDescriptor.open(folder.newFile(), ParcelFileDescriptor.MODE_READ_WRITE)
        val listenPort = port?.let { "ListenPort = $it" }.orEmpty()
        val config = Config.parse("""
            [Interface]
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            Address = 192.0.2.2/32
            $listenPort
            [Peer]
            PublicKey = CQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQk=
            AllowedIPs = 192.0.2.1/32
            Endpoint = 127.0.0.1:51820
        """.trimIndent().byteInputStream())
        backend.setState(tunnel, Tunnel.State.UP, config)
        if (Thread.currentThread() === Looper.getMainLooper().thread) shadowOf(Looper.getMainLooper()).idle()
        return config
    }

    private fun expectFreshServiceStart() = CountDownLatch(1).also { ServiceStartApplication.requested = it }

    private fun fulfillFreshServiceStart(requested: CountDownLatch) {
        assertSame(Looper.getMainLooper().thread, Thread.currentThread())
        assertTrue(requested.await(5, TimeUnit.SECONDS))
        ServiceStartApplication.requested = null
        createService()
    }

    private fun createService() {
        assertSame(Looper.getMainLooper().thread, Thread.currentThread())
        val controller = Robolectric.buildService(GoBackend.VpnService::class.java)
        service = controller.get()
        NativeShadow.expectedService = service
        controller.create()
    }

    private fun session(): HandshakeSession {
        val activation = checkNotNull(field("activation").get(backend))
        return activation.javaClass.getDeclaredField("session").apply { isAccessible = true }.get(activation) as HandshakeSession
    }

    private fun field(name: String) = GoBackend::class.java.getDeclaredField(name).apply { isAccessible = true }

    class ServiceStartApplication : Application() {
        override fun startService(service: Intent): ComponentName? = super.startService(service).also {
            if (service.component?.className == GoBackend.VpnService::class.java.name) requested?.countDown()
        }

        companion object {
            @Volatile var requested: CountDownLatch? = null
        }
    }

    @Implements(value = VpnService.Builder::class)
    class BuilderShadow {
        companion object {
            lateinit var descriptor: ParcelFileDescriptor
            var establishCalls = 0
            var beforeEstablish: (() -> Unit)? = null
        }
        @Implementation fun establish(): ParcelFileDescriptor {
            beforeEstablish?.invoke()
            establishCalls++
            return descriptor
        }
    }

    @Implements(value = VpnService::class)
    class TransportServiceShadow : ShadowVpnService() {
        @Implementation fun setUnderlyingNetworks(networks: Array<android.net.Network>?): Boolean {
            reported += networks
            return reporting?.invoke() ?: true
        }

        companion object {
            val reported = mutableListOf<Array<android.net.Network>?>()
            var reporting: (() -> Boolean)? = null
        }
    }

    @Implements(value = GoBackend::class, isInAndroidSdk = false)
    // Robolectric instantiates this shadow through a public no-argument constructor.
    @Suppress("UtilityClassWithPublicConstructor")
    class NativeShadow {
        companion object {
            var nextHandle = 7
            val rebindCalls = mutableListOf<Int>()
            var rebind: ((Int) -> Int)? = null
            var recover: ((Int) -> Int)? = null
            val recoveryCalls = mutableListOf<Int>()
            var turnOff: ((Int) -> Unit)? = null
            var turnOn: ((Any, Int) -> Int)? = null
            var transport: ((Int, Long, Any, Boolean) -> Long)? = null
            var transportSnapshot: ((Int) -> Long)? = null
            lateinit var stateLock: Any
            lateinit var expectedService: GoBackend.VpnService
            @Implementation @JvmStatic fun wgTurnOn(name: String, fd: Int, settings: String,
                service: GoBackend.VpnService, thread: Thread, id: Long, @org.robolectric.annotation.ClassName("com.vpn0wn.android.backend.GoBackend\$SocketCapture") capture: Any): Int {
                assertEquals("fixture", name)
                assertEquals(73, fd)
                assertFalse(settings.isEmpty())
                assertSame(expectedService, service)
                assertSame(Thread.currentThread(), thread)
                assertTrue(id > 0)
                val handle = nextHandle++
                return turnOn?.invoke(capture, handle) ?: handle
            }
            @Implementation @JvmStatic fun wgRebind(handle: Int): Int {
                assertFalse(Thread.holdsLock(stateLock))
                rebindCalls += handle
                return rebind?.invoke(handle) ?: 0
            }
            @Implementation @JvmStatic fun wgRecover(handle: Int, peer: ByteArray, episode: Long, endpoint: String, caller: Thread): Int {
                assertFalse(Thread.holdsLock(stateLock))
                assertEquals(32, peer.size)
                assertEquals(73L, episode)
                assertEquals("127.0.0.1:51820", endpoint)
                assertSame(Thread.currentThread(), caller)
                recoveryCalls += handle
                return recover?.invoke(handle) ?: 6
            }
            @Implementation @JvmStatic fun wgTransport(handle: Int, generation: Long,
                @org.robolectric.annotation.ClassName("com.vpn0wn.android.backend.GoBackend\$SocketCapture") capture: Any,
                available: Boolean): Long = transport?.invoke(handle, generation, capture, available) ?: 5L
            @Implementation @JvmStatic fun wgTransportSnapshot(handle: Int): Long = transportSnapshot?.invoke(handle) ?: 2L
            @Implementation @JvmStatic fun wgTurnOff(handle: Int) { turnOff?.invoke(handle) }
            // Robolectric must retain the zero-argument JNI method shape.
            @Suppress("FunctionOnlyReturningConstant")
            @Implementation @JvmStatic fun wgVersion() = "synthetic"
        }
    }
}
