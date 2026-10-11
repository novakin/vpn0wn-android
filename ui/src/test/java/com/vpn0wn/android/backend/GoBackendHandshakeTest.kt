package com.vpn0wn.android.backend

import android.app.Application
import android.net.VpnService
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.vpn0wn.config.Config
import com.vpn0wn.config.InetEndpointTest
import com.vpn0wn.crypto.Key
import java.lang.reflect.InvocationTargetException
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
import org.robolectric.shadows.ShadowParcelFileDescriptor

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(application = Application::class, sdk = [36], shadows = [GoBackendHandshakeTest.NativeShadow::class,
    GoBackendHandshakeTest.BuilderShadow::class, GoBackendHandshakeTest.DescriptorShadow::class,
    GoBackendLifecycleTest.LibraryShadow::class, InetEndpointTest.NumericAddressShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class GoBackendHandshakeTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var backend: GoBackend
    private lateinit var service: GoBackend.VpnService
    private val peer = Key.fromBytes(ByteArray(32) { 9 })
    private val secondPeer = Key.fromBytes(ByteArray(32) { 10 })
    private val observations = mutableListOf<HandshakeObservation>()
    private val sessions = mutableListOf<HandshakeSession>()
    private val tunnel = object : Tunnel {
        override fun getName() = "fixture"
        override fun onStateChange(newState: Tunnel.State) = Unit
        override fun onHandshakeSessionChange(session: HandshakeSession) {
            assertOutsideOwnerLocks()
            sessions += session
        }
        override fun onHandshakeObservation(observation: HandshakeObservation) {
            assertOutsideOwnerLocks()
            observations += observation
        }
    }

    @Before fun setUp() {
        backend = GoBackend(ApplicationProvider.getApplicationContext())
        @Suppress("UNCHECKED_CAST")
        val reference = GoBackend::class.java.getDeclaredField("vpnServiceRef").apply { isAccessible = true }
            .get(null) as AtomicReference<CompletableFuture<GoBackend.VpnService>>
        reference.set(CompletableFuture())
        service = Robolectric.buildService(GoBackend.VpnService::class.java).create().get()
        ShadowVpnService.setPrepareResult(null)
        BuilderShadow.descriptor = ParcelFileDescriptor.open(folder.newFile(), ParcelFileDescriptor.MODE_READ_WRITE)
        NativeShadow.startup = { _, _ -> 7 }
        NativeShadow.insideNative = false
    }

    @After fun tearDown() {
        backend.setState(tunnel, Tunnel.State.DOWN, null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun successfulStartupBuffersConfiguredPeerEventsOutsideNativeAndLocks() {
        NativeShadow.startup = { owner, id ->
            repeat(40) { capture(owner, id, peer, 1) }
            capture(owner, id, peer, 3)
            capture(owner, id, secondPeer, 4, endpoint = "[::1]:51820")
            capture(owner, id, Key.fromBytes(ByteArray(32) { 11 }), 5)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(observations.isEmpty())
            assertTrue(sessions.isEmpty())
            7
        }
        backend.setState(tunnel, Tunnel.State.UP, profile())
        assertTrue(observations.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, sessions.size)
        assertTrue(sessions.single().isActive)
        assertEquals(3, observations.size)
        assertEquals(listOf(HandshakeObservation.Event.ATTEMPT, HandshakeObservation.Event.SEND_FAILED,
            HandshakeObservation.Event.AUTHENTICATED), observations.map { it.event() })
        assertEquals(listOf(peer, peer, secondPeer), observations.map { it.peer() })
        assertEquals(HandshakeObservation.AddressFamily.IPV6, observations.last().addressFamily())
        assertEquals("[::1]:51820", observations.last().endpoint().toString())
        assertTrue(observations.zipWithNext().all { (first, second) -> first.sequence() < second.sequence() })
        assertEquals(40L, observations.first().sequence())
    }

    @Test fun unsuccessfulStartupDiscardsEveryStagedObservation() {
        NativeShadow.startup = { owner, id -> capture(owner, id, peer, 4); -1 }
        assertThrows(BackendException::class.java) { backend.setState(tunnel, Tunnel.State.UP, profile()) }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(sessions.isEmpty())
        assertTrue(observations.isEmpty())
    }

    @Test fun invalidNativeHostnameFailsStartupWithoutPublishingStagedState() {
        NativeShadow.startup = { owner, id ->
            capture(owner, id, peer, 4)
            capture(owner, id, peer, 1, endpoint = "vpn.example.invalid:51820")
            7
        }
        val failure = assertThrows(InvocationTargetException::class.java) {
            backend.setState(tunnel, Tunnel.State.UP, profile())
        }
        assertTrue(failure.cause is IllegalArgumentException)
        assertEquals("Invalid native numeric endpoint", failure.cause?.message)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Tunnel.State.DOWN, backend.getState(tunnel))
        assertTrue(sessions.isEmpty())
        assertTrue(observations.isEmpty())
    }

    @Test fun disconnectBeforeQueuedDeliveryCannotPublishRetiredActivation() {
        NativeShadow.startup = { owner, id -> capture(owner, id, peer, 4); 7 }
        backend.setState(tunnel, Tunnel.State.UP, profile())
        backend.setState(tunnel, Tunnel.State.DOWN, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(observations.isEmpty())
        assertTrue(sessions.all { !it.isActive })
    }

    @Test fun backendReplacementGetsDistinctIdentityAndOldNativeEventsAreIgnored() {
        var oldId = 0L
        NativeShadow.startup = { _, id -> oldId = id; 7 }
        backend.setState(tunnel, Tunnel.State.UP, profile())
        shadowOf(Looper.getMainLooper()).idle()
        val old = sessions.single()
        backend.setState(tunnel, Tunnel.State.DOWN, null)
        backend = GoBackend(ApplicationProvider.getApplicationContext())
        service = Robolectric.buildService(GoBackend.VpnService::class.java).create().get()
        BuilderShadow.descriptor = ParcelFileDescriptor.open(folder.newFile(), ParcelFileDescriptor.MODE_READ_WRITE)
        NativeShadow.startup = { owner, id -> capture(owner, oldId, peer, 4); capture(owner, id, peer, 2); 7 }
        backend.setState(tunnel, Tunnel.State.UP, profile())
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(old.isActive)
        assertTrue(sessions.last().id > old.id)
        assertEquals(listOf(HandshakeObservation.Event.SENT), observations.map { it.event() })
        assertSame(sessions.last(), observations.single().session())
    }

    @Test fun serviceDestructionRetiresStagedEventsBeforeWaitingForTransitionLock() {
        NativeShadow.startup = { owner, id -> capture(owner, id, peer, 4); 7 }
        backend.setState(tunnel, Tunnel.State.UP, profile())
        val lock = checkNotNull(GoBackend::class.java.getDeclaredField("stateLock").apply { isAccessible = true }.get(backend))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val held = worker.submit {
                synchronized(lock) { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            service.onDestroy()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(observations.isEmpty())
            assertTrue(sessions.all { !it.isActive })
            release.countDown()
            held.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun assertOutsideOwnerLocks() {
        assertFalse(NativeShadow.insideNative)
        val lock = checkNotNull(GoBackend::class.java.getDeclaredField("stateLock").apply { isAccessible = true }.get(backend))
        assertFalse(Thread.holdsLock(lock))
        val activation = GoBackend::class.java.getDeclaredField("activation").apply { isAccessible = true }.get(backend)
        if (activation != null) assertFalse(Thread.holdsLock(activation))
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
        PublicKey = ${secondPeer.toBase64()}
        AllowedIPs = 192.0.2.3/32
    """.trimIndent().byteInputStream())

    private fun capture(owner: GoBackend.VpnService, id: Long, key: Key, event: Int, endpoint: String = "127.0.0.1:51820") {
        GoBackend.VpnService::class.java.getDeclaredMethod("onNativeHandshakeObservation", Long::class.javaPrimitiveType,
            ByteArray::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java)
            .apply { isAccessible = true }.invoke(owner, id, key.bytes, event, true, endpoint)
    }

    @Implements(value = VpnService.Builder::class)
    class BuilderShadow {
        companion object { lateinit var descriptor: ParcelFileDescriptor }
        @Implementation fun establish(): ParcelFileDescriptor = descriptor
    }

    @Implements(value = ParcelFileDescriptor::class)
    class DescriptorShadow : ShadowParcelFileDescriptor() {
        // Host JDK descriptors cannot perform Android's owned-TUN handoff.
        @Suppress("FunctionOnlyReturningConstant")
        @Implementation fun detachFd(): Int = 73
    }

    @Implements(value = GoBackend::class, isInAndroidSdk = false)
    @Suppress("UtilityClassWithPublicConstructor")
    class NativeShadow {
        companion object {
            lateinit var startup: (GoBackend.VpnService, Long) -> Int
            var insideNative = false
            @Implementation @JvmStatic fun wgTurnOn(name: String, fd: Int, settings: String,
                service: GoBackend.VpnService, thread: Thread, id: Long): Int {
                assertEquals("fixture", name)
                assertTrue(settings.contains("listen_port=51820"))
                assertSame(Thread.currentThread(), thread)
                assertEquals(73, fd)
                insideNative = true
                return try { startup(service, id) } finally { insideNative = false }
            }
            @Implementation @JvmStatic fun wgTurnOff(handle: Int) = assertEquals(7, handle)
            // Robolectric must retain the zero-argument JNI method shape.
            @Suppress("FunctionOnlyReturningConstant")
            @Implementation @JvmStatic fun wgVersion() = "synthetic"
        }
    }
}
