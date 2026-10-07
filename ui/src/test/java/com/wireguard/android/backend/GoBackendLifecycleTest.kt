package com.wireguard.android.backend

import android.app.Application
import android.content.Context
import android.net.TrafficStats
import androidx.test.core.app.ApplicationProvider
import com.wireguard.android.model.TunnelTestEnvironment
import com.wireguard.android.util.SharedLibraryLoader
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import java.lang.reflect.InvocationTargetException
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

private const val CALLER_TRAFFIC_TAG = 42
private const val PREFLIGHT_RECEIVE_TIMEOUT_MS = 1_000

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(application = Application::class, sdk = [28], shadows = [GoBackendLifecycleTest.LibraryShadow::class])
class GoBackendLifecycleTest {
    private lateinit var backend: GoBackend
    private lateinit var serviceRef: AtomicReference<CompletableFuture<GoBackend.VpnService>>

    @Before
    fun setUp() {
        backend = GoBackend(ApplicationProvider.getApplicationContext())
        @Suppress("UNCHECKED_CAST")
        serviceRef = GoBackend::class.java.getDeclaredField("vpnServiceRef").apply { isAccessible = true }
            .get(null) as AtomicReference<CompletableFuture<GoBackend.VpnService>>
        serviceRef.set(CompletableFuture())
    }

    @Test
    fun destructionReturnsWhileActivationLockIsHeld() {
        val service = Robolectric.buildService(GoBackend.VpnService::class.java).create().get()
        service.setOwner(backend)
        val oldFuture = serviceRef.get()
        val lock = GoBackend::class.java.getDeclaredField("stateLock").apply { isAccessible = true }.get(backend)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val activation = executor.submit {
                synchronized(lock) {
                    held.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(held.await(5, TimeUnit.SECONDS))
            val start = System.nanoTime()
            service.onDestroy()
            assertTrue("Destruction waited for activation", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1))
            assertEquals(1L, release.count)
            assertNotSame(oldFuture, serviceRef.get())
            assertFalse(serviceRef.get().isDone)
            release.countDown()
            activation.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun destroyedServiceCannotBeReownedAndNextServiceIsAcquired() {
        val first = Robolectric.buildService(GoBackend.VpnService::class.java).create().get()
        first.onDestroy()
        assertThrows(BackendException::class.java) { first.setOwner(backend) }
        val replacementFuture = serviceRef.get()
        val second = Robolectric.buildService(GoBackend.VpnService::class.java).create().get()
        assertSame(second, replacementFuture.get(0, TimeUnit.SECONDS))
        second.onDestroy()
    }

    @Test
    fun oldServiceCleanupDoesNotCloseNewServiceTunnel() {
        val old = GoBackend.VpnService()
        val current = GoBackend.VpnService()
        val tunnel = object : Tunnel {
            override fun getName(): String = "fixture"
            override fun onStateChange(newState: Tunnel.State) = fail("Old cleanup changed current tunnel")
        }
        for ((name, value) in mapOf("currentService" to current, "currentTunnel" to tunnel, "currentTunnelHandle" to 7))
            GoBackend::class.java.getDeclaredField(name).apply { isAccessible = true }.set(backend, value)
        GoBackend::class.java.getDeclaredMethod("handleServiceDestroyed", GoBackend.VpnService::class.java)
            .apply { isAccessible = true }.invoke(backend, old)
        assertEquals(Tunnel.State.UP, backend.getState(tunnel))
    }

    @Test
    fun destroyedServiceCallbackFinishesBeforeNextUpRequest() {
        val service = GoBackend.VpnService()
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val tunnel = object : Tunnel {
            override fun getName(): String = "fixture"
            override fun onStateChange(newState: Tunnel.State) {
                assertEquals(Tunnel.State.DOWN, newState)
                callbackEntered.countDown()
                assertTrue(releaseCallback.await(5, TimeUnit.SECONDS))
            }
        }
        for ((name, value) in mapOf("currentService" to service, "currentTunnel" to tunnel))
            GoBackend::class.java.getDeclaredField(name).apply { isAccessible = true }.set(backend, value)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val destruction = executor.submit {
                GoBackend::class.java.getDeclaredMethod("handleServiceDestroyed", GoBackend.VpnService::class.java)
                    .apply { isAccessible = true }.invoke(backend, service)
            }
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
            val nextStarted = CountDownLatch(1)
            val nextUp = executor.submit {
                nextStarted.countDown()
                backend.setState(tunnel, Tunnel.State.UP, null)
            }
            assertTrue(nextStarted.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { nextUp.get(500, TimeUnit.MILLISECONDS) }
            releaseCallback.countDown()
            destruction.get(5, TimeUnit.SECONDS)
            val failure = assertThrows(ExecutionException::class.java) { nextUp.get(5, TimeUnit.SECONDS) }
            assertTrue(failure.cause is BackendException)
        } finally {
            releaseCallback.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun endpointFreeExplicitPortProfileSkipsPreflight() {
        val service = PreflightService()
        invokePreflight(TunnelTestEnvironment.profile(), service)
        assertEquals(0, service.protectCalls)
        assertTrue(TunnelTestEnvironment.profile().toWgUserspaceString().contains("listen_port=51820"))
    }

    @Test
    fun profileWithoutExplicitPortSkipsPreflight() {
        val profile = Config.parse("""
            [Interface]
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            [Peer]
            PublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            Endpoint = 192.0.2.1:51820
            AllowedIPs = 0.0.0.0/0
        """.trimIndent().byteInputStream())
        val service = PreflightService()
        invokePreflight(profile, service)
        assertEquals(0, service.protectCalls)
    }

    @Test
    fun interruptionAbortsPreflightEvenWithoutEndpoints() {
        Thread.currentThread().interrupt()
        try {
            val failure = assertThrows(InvocationTargetException::class.java) {
                invokePreflight(TunnelTestEnvironment.profile(), PreflightService())
            }
            assertTrue(failure.cause is InterruptedException)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun failedProtectionPreventsBindAndSend() {
        val method = GoBackend::class.java.getDeclaredMethod("sendUdpPreflight", GoBackend.VpnService::class.java,
            Int::class.javaPrimitiveType, InetAddress::class.java, Int::class.javaPrimitiveType, InetEndpoint::class.java)
            .apply { isAccessible = true }
        val service = PreflightService()
        val previousTag = TrafficStats.getThreadStatsTag()
        TrafficStats.setThreadStatsTag(CALLER_TRAFFIC_TAG)
        try {
            val failure = assertThrows(InvocationTargetException::class.java) {
                method.invoke(backend, service, 51820, InetAddress.getByAddress(byteArrayOf(-64, 0, 2, 1)),
                    51820, InetEndpoint.parse("192.0.2.1:51820"))
            }
            assertTrue(failure.cause is SocketException)
            assertEquals(1, service.protectCalls)
            assertEquals(0, service.trafficTag)
            assertEquals(CALLER_TRAFFIC_TAG, TrafficStats.getThreadStatsTag())
        } finally {
            TrafficStats.setThreadStatsTag(previousTag)
        }
    }

    @Test
    fun successfulPreflightTagsSocketAndRestoresUntaggedCaller() {
        val previousTag = TrafficStats.getThreadStatsTag()
        TrafficStats.clearThreadStatsTag()
        try {
            val address = InetAddress.getLoopbackAddress()
            DatagramSocket(0, address).use { receiver ->
                receiver.soTimeout = PREFLIGHT_RECEIVE_TIMEOUT_MS
                val service = PreflightService().apply { allowProtection = true }
                val method = GoBackend::class.java.getDeclaredMethod("sendUdpPreflight", GoBackend.VpnService::class.java,
                    Int::class.javaPrimitiveType, InetAddress::class.java, Int::class.javaPrimitiveType, InetEndpoint::class.java)
                    .apply { isAccessible = true }
                method.invoke(backend, service, 0, address, receiver.localPort, InetEndpoint.parse("127.0.0.1:${receiver.localPort}"))
                val packet = DatagramPacket(ByteArray(2), 2)
                receiver.receive(packet)
                assertEquals(1, packet.length)
                assertEquals(0, packet.data[0].toInt())
                assertEquals(1, service.protectCalls)
                assertEquals(0, service.trafficTag)
                assertEquals(-1, TrafficStats.getThreadStatsTag())
            }
        } finally {
            TrafficStats.setThreadStatsTag(previousTag)
        }
    }

    private fun invokePreflight(config: Config, service: GoBackend.VpnService) {
        GoBackend::class.java.getDeclaredMethod("sendUdpPreflight", Config::class.java, GoBackend.VpnService::class.java)
            .apply { isAccessible = true }.invoke(backend, config, service)
    }

    private class PreflightService : GoBackend.VpnService() {
        var protectCalls = 0
        var allowProtection = false
        var trafficTag: Int? = null
        override fun protect(socket: DatagramSocket): Boolean {
            protectCalls++
            trafficTag = TrafficStats.getThreadStatsTag()
            return allowProtection
        }
    }

    @Implements(value = SharedLibraryLoader::class, isInAndroidSdk = false)
    class LibraryShadow private constructor() {
        companion object {
            @Implementation
            @JvmStatic
            fun loadSharedLibrary(context: Context, name: String) {
                assertNotNull(context)
                assertEquals("wg-go", name)
            }
        }
    }
}
