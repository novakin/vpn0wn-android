package com.vpn0wn.android.backend

import android.app.Application
import android.net.VpnService
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.vpn0wn.config.Config
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
@RobolectricConfig(application = Application::class, sdk = [36], shadows = [GoBackendRebindTest.NativeShadow::class,
    GoBackendRebindTest.BuilderShadow::class, GoBackendHandshakeTest.DescriptorShadow::class,
    GoBackendLifecycleTest.LibraryShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class GoBackendRebindTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var backend: GoBackend
    private lateinit var service: GoBackend.VpnService
    private val states = Collections.synchronizedList(mutableListOf<Tunnel.State>())
    private val sessions = mutableListOf<HandshakeSession>()
    private val down = CountDownLatch(1)
    private val tunnel = object : Tunnel {
        override fun getName() = "fixture"
        override fun onStateChange(newState: Tunnel.State) {
            states += newState
            if (newState == Tunnel.State.DOWN) down.countDown()
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
        NativeShadow.nextHandle = 7
        NativeShadow.rebindCalls.clear()
        NativeShadow.rebind = null
        NativeShadow.turnOff = null
        NativeShadow.stateLock = checkNotNull(field("stateLock").get(backend))
    }

    @After fun tearDown() {
        Thread.interrupted()
        NativeShadow.rebind = null
        NativeShadow.turnOff = null
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
        shadowOf(Looper.getMainLooper()).idle()
        return config
    }

    private fun session(): HandshakeSession {
        val activation = checkNotNull(field("activation").get(backend))
        return activation.javaClass.getDeclaredField("session").apply { isAccessible = true }.get(activation) as HandshakeSession
    }

    private fun field(name: String) = GoBackend::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Implements(value = VpnService.Builder::class)
    class BuilderShadow {
        companion object {
            lateinit var descriptor: ParcelFileDescriptor
            var establishCalls = 0
        }
        @Implementation fun establish(): ParcelFileDescriptor {
            establishCalls++
            return descriptor
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
            var turnOff: ((Int) -> Unit)? = null
            lateinit var stateLock: Any
            lateinit var expectedService: GoBackend.VpnService
            @Implementation @JvmStatic fun wgTurnOn(name: String, fd: Int, settings: String,
                service: GoBackend.VpnService, thread: Thread, id: Long): Int {
                assertEquals("fixture", name)
                assertEquals(73, fd)
                assertFalse(settings.isEmpty())
                assertSame(expectedService, service)
                assertSame(Thread.currentThread(), thread)
                assertTrue(id > 0)
                return nextHandle++
            }
            @Implementation @JvmStatic fun wgRebind(handle: Int): Int {
                assertFalse(Thread.holdsLock(stateLock))
                rebindCalls += handle
                return rebind?.invoke(handle) ?: 0
            }
            @Implementation @JvmStatic fun wgTurnOff(handle: Int) { turnOff?.invoke(handle) }
            // Robolectric must retain the zero-argument JNI method shape.
            @Suppress("FunctionOnlyReturningConstant")
            @Implementation @JvmStatic fun wgVersion() = "synthetic"
        }
    }
}
