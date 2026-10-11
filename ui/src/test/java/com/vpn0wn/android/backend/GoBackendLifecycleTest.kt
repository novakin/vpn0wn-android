package com.vpn0wn.android.backend

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.android.util.SharedLibraryLoader
import com.vpn0wn.config.Config
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements


@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(application = Application::class, sdk = [28], shadows = [GoBackendLifecycleTest.LibraryShadow::class, GoBackendLifecycleTest.NativeShadow::class])
class GoBackendLifecycleTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var backend: GoBackend
    private lateinit var serviceRef: AtomicReference<CompletableFuture<GoBackend.VpnService>>

    @Before
    fun setUp() {
        backend = GoBackend(ApplicationProvider.getApplicationContext())
        @Suppress("UNCHECKED_CAST")
        serviceRef = GoBackend::class.java.getDeclaredField("vpnServiceRef").apply { isAccessible = true }
            .get(null) as AtomicReference<CompletableFuture<GoBackend.VpnService>>
        serviceRef.set(CompletableFuture())
        NativeShadow.failure = null
        NativeShadow.query = null
        NativeShadow.turnOff = null
    }

    @Test
    fun managerDisconnectsUserspaceTunnelWithoutLoadingUnreadableProfile() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var environment: TunnelTestEnvironment? = null
        try {
            val env = TunnelTestEnvironment(folder.root, backgroundScope)
            environment = env
            val profile = TunnelTestEnvironment.profile()
            env.store.configs["one"] = profile
            env.replaceBackend(backend)
            val tunnel = env.manager.getTunnels()["one"]!!
            val service = Robolectric.buildService(GoBackend.VpnService::class.java).create().get()
            service.setOwner(backend)
            for ((name, value) in mapOf("currentService" to service, "currentTunnel" to tunnel,
                    "currentTunnelHandle" to 7, "currentConfig" to profile))
                GoBackend::class.java.getDeclaredField(name).apply { isAccessible = true }.set(backend, value)
            tunnel.onStateChanged(Tunnel.State.UP)
            env.store.failLoadNames += "one"
            var nativeShutdowns = 0
            NativeShadow.turnOff = { nativeShutdowns++ }
            assertEquals(Tunnel.State.DOWN, tunnel.setStateAsync(Tunnel.State.DOWN))
            assertEquals(Tunnel.State.DOWN, backend.getState(tunnel))
            assertEquals(Tunnel.State.DOWN, tunnel.state)
            assertEquals(1, nativeShutdowns)
            assertTrue(env.store.loadAttempts.isEmpty())
            assertSame(profile, env.store.configs["one"])
        } finally {
            withContext(NonCancellable) {
                environment?.close()
                Dispatchers.resetMain()
            }
        }
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
    fun endpointFreeProfilePreservesExplicitPort() {
        assertTrue(TunnelTestEnvironment.profile().toWgUserspaceString().contains("listen_port=51820"))
    }

    @Test
    fun profileWithoutExplicitPortPreservesAbsence() {
        val profile = Config.parse("""
            [Interface]
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            [Peer]
            PublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            Endpoint = 192.0.2.1:51820
            AllowedIPs = 0.0.0.0/0
        """.trimIndent().byteInputStream())
        assertFalse(profile.toWgUserspaceString().contains("listen_port="))
    }

    @Test
    fun nativeCallbackRejectsDestroyedService() {
        val service = GoBackend.VpnService()
        service.onDestroy()
        val failure = assertThrows(InvocationTargetException::class.java) { invokeActive(service, null) }
        assertTrue(failure.cause is BackendException)
    }

    @Test
    fun retainedNativeCallbackDoesNotTreatCallerInterruptionAsServiceDeath() {
        val service = GoBackend.VpnService()
        Thread.currentThread().interrupt()
        try {
            assertTrue(invokeActive(service, null))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        assertTrue(invokeActive(service, null))
    }

    @Test
    fun nativeWorkerChecksOriginatingActivationThread() {
        val waiting = CountDownLatch(1)
        val gate = Any()
        val startup = Thread {
            Thread.currentThread().interrupt()
            waiting.countDown()
            synchronized(gate) { }
        }
        try {
            synchronized(gate) {
                startup.start()
                assertTrue(waiting.await(5, TimeUnit.SECONDS))
                val failure = assertThrows(InvocationTargetException::class.java) { invokeActive(GoBackend.VpnService(), startup) }
                assertTrue(failure.cause is InterruptedException)
                assertTrue(startup.isInterrupted)
                assertFalse(Thread.currentThread().isInterrupted)
            }
        } finally {
            startup.join(TimeUnit.SECONDS.toMillis(5))
            assertFalse(startup.isAlive)
        }
    }

    @Test
    fun nativeProtectionKeepsOwningServiceAndDescriptor() {
        val service = NativeService()
        val callback = GoBackend.VpnService::class.java.getDeclaredMethod("protectNativeSocket", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        assertFalse(callback.invoke(service, 42) as Boolean)
        assertEquals(42, service.descriptor)
        service.allowProtection = true
        assertTrue(callback.invoke(service, 24) as Boolean)
        assertEquals(24, service.descriptor)
    }

    @Test
    fun nativeProtectionDoesNotSwallowProgrammingError() {
        val service = NativeService()
        val original = AssertionError("Synthetic callback defect")
        service.failure = original
        val callback = GoBackend.VpnService::class.java.getDeclaredMethod("protectNativeSocket", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val failure = assertThrows(InvocationTargetException::class.java) { callback.invoke(service, 42) }
        assertSame(original, failure.cause)
    }

    @Test
    fun nativeTeardownErrorStillClearsStateAndPublishesDown() {
        val service = Robolectric.buildService(GoBackend.VpnService::class.java).create().get()
        val states = mutableListOf<Tunnel.State>()
        val tunnel = installOwnedTunnel(service, states)
        val original = AssertionError("Synthetic teardown defect")
        NativeShadow.failure = original
        val failure = assertThrows(AssertionError::class.java) { backend.setState(tunnel, Tunnel.State.DOWN, null) }
        assertSame(original, failure)
        assertEquals(Tunnel.State.DOWN, backend.getState(tunnel))
        assertEquals(listOf(Tunnel.State.DOWN), states)
        assertFalse(serviceRef.get().isDone)
    }

    @Test
    fun serviceCleanupErrorStillClearsStateAndPublishesDown() {
        val service = Robolectric.buildService(GoBackend.VpnService::class.java).create().get()
        val states = mutableListOf<Tunnel.State>()
        val tunnel = installOwnedTunnel(service, states)
        val original = AssertionError("Synthetic callback defect")
        NativeShadow.failure = original
        val failure = assertThrows(InvocationTargetException::class.java) {
            GoBackend::class.java.getDeclaredMethod("handleServiceDestroyed", GoBackend.VpnService::class.java)
                .apply { isAccessible = true }.invoke(backend, service)
        }
        assertSame(original, failure.cause)
        assertEquals(Tunnel.State.DOWN, backend.getState(tunnel))
        assertEquals(listOf(Tunnel.State.DOWN), states)
        assertFalse(serviceRef.get().isDone)
    }

    @Test
    fun disconnectCancelsBlockedStatisticsWithoutHoldingStateLock() {
        assertStatisticsTeardown(false)
    }

    @Test
    fun serviceCleanupCancelsBlockedStatisticsWithoutHoldingStateLock() {
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
            assertEquals(0, statistics.get(5, TimeUnit.SECONDS).totalRx())
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun assertStatisticsTeardown(serviceCleanup: Boolean) {
        val service = GoBackend.VpnService()
        val states = mutableListOf<Tunnel.State>()
        val tunnel = installOwnedTunnel(service, states)
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
                if (serviceCleanup) {
                    GoBackend::class.java.getDeclaredMethod("handleServiceDestroyed", GoBackend.VpnService::class.java)
                        .apply { isAccessible = true }.invoke(backend, service)
                } else {
                    backend.setState(tunnel, Tunnel.State.DOWN, null)
                }
            }
            teardown.get(5, TimeUnit.SECONDS)
            assertEquals(0, statistics.get(5, TimeUnit.SECONDS).totalRx())
            assertEquals(Tunnel.State.DOWN, backend.getState(tunnel))
            assertEquals(listOf(Tunnel.State.DOWN), states)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun installOwnedTunnel(service: GoBackend.VpnService, states: MutableList<Tunnel.State>): Tunnel {
        val tunnel = object : Tunnel {
            override fun getName(): String = "fixture"
            override fun onStateChange(newState: Tunnel.State) { states += newState }
        }
        for ((name, value) in mapOf("currentService" to service, "currentTunnel" to tunnel, "currentTunnelHandle" to 7))
            GoBackend::class.java.getDeclaredField(name).apply { isAccessible = true }.set(backend, value)
        return tunnel
    }

    private fun invokeActive(service: GoBackend.VpnService, startup: Thread?): Boolean =
        GoBackend.VpnService::class.java.getDeclaredMethod("isNativeServiceActive", Thread::class.java)
            .apply { isAccessible = true }.invoke(service, startup) as Boolean

    private class NativeService : GoBackend.VpnService() {
        var descriptor: Int? = null
        var allowProtection = false
        var failure: Error? = null
        override fun protect(socket: Int): Boolean {
            descriptor = socket
            failure?.let { throw it }
            return allowProtection
        }
    }

    @Implements(value = GoBackend::class, isInAndroidSdk = false)
    // Robolectric instantiates this shadow through a public no-argument constructor.
    @Suppress("UtilityClassWithPublicConstructor")
    class NativeShadow {
        companion object {
            var failure: Error? = null
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
                failure?.let { throw it }
            }
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
