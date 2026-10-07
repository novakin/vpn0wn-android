package com.wireguard.android.backend

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(application = Application::class, sdk = [28], shadows = [GoBackendLifecycleTest.LibraryShadow::class, GoBackendStatisticsTest.NativeShadow::class])
class GoBackendStatisticsTest {
    private lateinit var backend: GoBackend

    @Before
    fun setUp() {
        backend = GoBackend(ApplicationProvider.getApplicationContext())
        @Suppress("UNCHECKED_CAST")
        val serviceRef = GoBackend::class.java.getDeclaredField("vpnServiceRef").apply { isAccessible = true }
            .get(null) as AtomicReference<CompletableFuture<GoBackend.VpnService>>
        serviceRef.set(CompletableFuture())
        NativeShadow.query = null
        NativeShadow.turnOff = null
    }

    @Test
    fun disconnectCancelsBlockedStatisticsWithoutHoldingStateLock() {
        assertStatisticsTeardown(false)
    }

    @Test
    fun serviceDestructionCancelsBlockedStatisticsWithoutHoldingStateLock() {
        assertStatisticsTeardown(true)
    }

    @Test
    fun delayedStatisticsDiscardResultsAfterTunnelReplacement() {
        val service = GoBackend.VpnService()
        val tunnel = installOwnedTunnel(service, mutableListOf())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        NativeShadow.query = { handle ->
            assertEquals(7, handle)
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            "public_key=${"01".repeat(32)}\nrx_bytes=123\ntx_bytes=456\n"
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val statistics = executor.submit<Statistics> { backend.getStatistics(tunnel) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val stateLock = GoBackend::class.java.getDeclaredField("stateLock").apply { isAccessible = true }.get(backend)
            synchronized(stateLock) {
                GoBackend::class.java.getDeclaredField("currentTunnelHandle").apply { isAccessible = true }.set(backend, 8)
            }
            release.countDown()
            assertEquals(0L, statistics.get(5, TimeUnit.SECONDS).totalRx())
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun assertStatisticsTeardown(serviceDestruction: Boolean) {
        val service = Robolectric.buildService(GoBackend.VpnService::class.java).create().get()
        service.setOwner(backend)
        val states = mutableListOf<Tunnel.State>()
        val down = CountDownLatch(1)
        val tunnel = installOwnedTunnel(service, states, down)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        NativeShadow.query = { handle ->
            assertEquals(7, handle)
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            "public_key=${"01".repeat(32)}\nrx_bytes=123\ntx_bytes=456\n"
        }
        NativeShadow.turnOff = { release.countDown() }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val statistics = executor.submit<Statistics> { backend.getStatistics(tunnel) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val teardown = executor.submit {
                if (serviceDestruction)
                    service.onDestroy()
                else
                    backend.setState(tunnel, Tunnel.State.DOWN, null)
            }
            teardown.get(5, TimeUnit.SECONDS)
            assertTrue(down.await(5, TimeUnit.SECONDS))
            assertEquals(0L, statistics.get(5, TimeUnit.SECONDS).totalRx())
            assertEquals(Tunnel.State.DOWN, backend.getState(tunnel))
            assertEquals(listOf(Tunnel.State.DOWN), states)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun installOwnedTunnel(service: GoBackend.VpnService, states: MutableList<Tunnel.State>, down: CountDownLatch? = null): Tunnel {
        val tunnel = object : Tunnel {
            override fun getName(): String = "fixture"
            override fun onStateChange(newState: Tunnel.State) {
                states += newState
                down?.countDown()
            }
        }
        for ((name, value) in mapOf("currentService" to service, "currentTunnel" to tunnel, "currentTunnelHandle" to 7))
            GoBackend::class.java.getDeclaredField(name).apply { isAccessible = true }.set(backend, value)
        return tunnel
    }

    @Implements(value = GoBackend::class, isInAndroidSdk = false)
    // Robolectric instantiates this shadow through a public no-argument constructor.
    @Suppress("UtilityClassWithPublicConstructor")
    class NativeShadow {
        companion object {
            var query: ((Int) -> String?)? = null
            var turnOff: ((Int) -> Unit)? = null
            @Implementation
            @JvmStatic
            fun wgGetConfig(handle: Int): String? = query?.invoke(handle)
            @Implementation
            @JvmStatic
            fun wgTurnOff(handle: Int) {
                assertEquals(7, handle)
                turnOff?.invoke(handle)
            }
        }
    }
}
