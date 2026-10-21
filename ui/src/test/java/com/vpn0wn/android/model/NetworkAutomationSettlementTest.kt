/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.model

import android.app.Application
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.vpn0wn.android.backend.GoBackend
import com.vpn0wn.android.backend.GoBackendLifecycleTest
import com.vpn0wn.android.backend.GoBackendHandshakeTest
import com.vpn0wn.android.backend.GoBackendRebindTest
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.util.UserKnobs
import com.vpn0wn.config.Config as TunnelConfig
import com.vpn0wn.config.InetEndpoint
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowVpnService
import org.robolectric.shadows.ShadowNetwork
import com.vpn0wn.android.Application as VpnApplication

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [
    GoBackendLifecycleTest.LibraryShadow::class,
    GoBackendRebindTest.BuilderShadow::class,
    GoBackendHandshakeTest.DescriptorShadow::class,
    GoBackendRebindTest.TransportServiceShadow::class,
    NetworkAutomationSettlementTest.NativeShadow::class,
])
class NetworkAutomationSettlementTest {
    @get:Rule val folder = TemporaryFolder()
    private var activeBackend: GoBackend? = null
    private var activeTunnel: ObservableTunnel? = null

    @After fun resetMain() {
        try {
            activeTunnel?.let { activeBackend?.setState(it, Tunnel.State.DOWN, null) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.environment(): TunnelTestEnvironment {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return TunnelTestEnvironment(folder.root, backgroundScope)
    }

    @Test fun failedAutomaticSettlementRetainsPendingEvidenceAndConsumerHandlesLaterMaterialNetwork() = runTest {
        val env = environment()
        try {
            val tunnel = env.manager.create("fixture", TunnelTestEnvironment.profile())
            connect(env, tunnel)
            NetworkAutomationState.update { it.copy(enabled = true, episode = 1, defaultName = tunnel.name, resolved = true) }
            failOneSettlement(env) { before, after ->
                before.attempt?.outcome == NetworkAutomationState.Outcome.PENDING &&
                    after.attempt?.outcome == NetworkAutomationState.Outcome.UP
            }
            val controller = env.manager.networkAutomation
            val generation = Any()
            assertTrue(controller.attach(generation))
            controller.observe(networks(generation, 1, 1))
            controller.status.first { it.condition == NetworkAutomationController.Condition.FAILED }
            assertEquals(NetworkAutomationState.Outcome.PENDING, NetworkAutomationState.read(VpnApplication.getPreferencesDataStore().data.first()).attempt?.outcome)
            controller.observe(networks(generation, 2, 2))
            controller.status.first { it.condition == NetworkAutomationController.Condition.OWNED }
            assertEquals(2, NativeShadow.replacements.get())
            assertEquals(NetworkAutomationState.Outcome.UP, NetworkAutomationState.read(VpnApplication.getPreferencesDataStore().data.first()).attempt?.outcome)
        } finally { env.close() }
    }

    @Test fun failedDisableSettlementStaysDisabledUnknownAndExplicitReconciliationDoesNotConvertAgain() = runTest {
        val env = environment()
        try {
            val tunnel = env.manager.create("fixture", TunnelTestEnvironment.profile())
            connect(env, tunnel)
            NetworkAutomationState.update { it.copy(enabled = true, episode = 1, defaultName = tunnel.name, resolved = true) }
            failOneSettlement(env) { before, after ->
                before.exit == NetworkAutomationState.Exit.PENDING && after.exit == NetworkAutomationState.Exit.COMPLETE
            }
            env.manager.networkAutomation.disable()
            val pending = NetworkAutomationState.read(VpnApplication.getPreferencesDataStore().data.first())
            assertFalse(pending.enabled)
            assertEquals(NetworkAutomationState.Exit.PENDING, pending.exit)
            assertEquals(NetworkAutomationController.Condition.EXIT_UNKNOWN, env.manager.networkAutomation.status.value.condition)
            assertFalse(env.manager.networkAutomation.status.value.settling)
            assertEquals(1, NativeShadow.replacements.get())
            env.manager.networkAutomation.disable()
            assertEquals(1, NativeShadow.replacements.get())
            assertEquals(NetworkAutomationState.Exit.COMPLETE, NetworkAutomationState.read(VpnApplication.getPreferencesDataStore().data.first()).exit)
            assertEquals(NetworkAutomationController.Condition.ANDROID_HANDLING, env.manager.networkAutomation.status.value.condition)
        } finally { env.close() }
    }

    @Test fun completeDisableHandBackCannotRegrantFailedDownRestoreIntent() = runTest {
        val env = environment()
        try {
            val tunnel = env.manager.create("fixture", TunnelTestEnvironment.profile())
            connect(env, tunnel)
            val backend = requireNotNull(activeBackend)
            val original = requireNotNull(backend.getTransportSnapshot(tunnel))
            val failedDown = NetworkAutomationState.Receipt(tunnel.name, "DOWN", "DOWN", 0, 0, 0, 2, NetworkAutomationState.Outcome.FAILED)
            val oldUp = failedDown.copy(requested = "UP", absolute = "UP", serial = 1, outcome = NetworkAutomationState.Outcome.UP)
            NetworkAutomationState.update { it.copy(enabled = true, episode = 1, defaultName = tunnel.name, resolved = true,
                manual = failedDown, attempt = oldUp, serial = 2) }
            UserKnobs.setRunningTunnels(setOf(tunnel.name))
            env.manager.networkAutomation.disable()
            assertEquals(NetworkAutomationState.Exit.COMPLETE, state().exit)
            assertTrue(UserKnobs.runningTunnels.first().isEmpty())
            val actual = requireNotNull(backend.getTransportSnapshot(tunnel))
            assertSame(original.session(), actual.session())
            assertEquals(GoBackend.TransportMode.LEGACY, actual.mode())
            assertTrue(actual.ready())
            assertEquals(Tunnel.State.UP, backend.getState(tunnel))
            assertEquals(1, NativeShadow.replacements.get())
            env.manager.setTunnelState(tunnel, Tunnel.State.UP)
            assertEquals(setOf(tunnel.name), UserKnobs.runningTunnels.first())
        } finally { env.close() }
    }

    @Test fun oldManualSettlementCannotRegrantRetiredIntentBeforeDisableConversion() = runTest {
        val env = environment()
        val manualEntered = CountDownLatch(1)
        val releaseManual = CountDownLatch(1)
        val exitEntered = CountDownLatch(1)
        val releaseExit = CountDownLatch(1)
        var manual: Deferred<Throwable?>? = null
        var disabling: Deferred<Unit>? = null
        try {
            val tunnel = env.manager.create("fixture", TunnelTestEnvironment.profile())
            connect(env, tunnel)
            NetworkAutomationState.update { it.copy(enabled = true, episode = 1, defaultName = "missing", resolved = false) }
            UserKnobs.setRunningTunnels(setOf(tunnel.name))
            val controller = env.manager.networkAutomation
            val generation = Any()
            assertTrue(controller.attach(generation))
            controller.observe(networks(generation, 1, 1))
            val operation = AtomicInteger()
            NativeShadow.beforeTransport = {
                if (operation.incrementAndGet() == 1) {
                    manualEntered.countDown()
                    assertTrue(releaseManual.await(5, TimeUnit.SECONDS))
                    throw IOException("Synthetic retired manual operation")
                } else {
                    exitEntered.countDown()
                    assertTrue(releaseExit.await(5, TimeUnit.SECONDS))
                }
            }
            manual = async { runCatching { env.manager.setTunnelState(tunnel, Tunnel.State.UP) }.exceptionOrNull() }
            withContext(Dispatchers.IO) { assertTrue(manualEntered.await(5, TimeUnit.SECONDS)) }
            disabling = async { controller.disable() }
            controller.status.first { it.condition == NetworkAutomationController.Condition.EXIT_PENDING }
            assertTrue(UserKnobs.runningTunnels.first().isEmpty())
            releaseManual.countDown()
            withContext(Dispatchers.IO) { assertTrue(exitEntered.await(5, TimeUnit.SECONDS)) }
            assertTrue(manual.await() is IOException)
            assertEquals(NetworkAutomationState.Exit.PENDING, state().exit)
            assertTrue(UserKnobs.runningTunnels.first().isEmpty())
            releaseExit.countDown()
            disabling.await()
            assertEquals(NetworkAutomationState.Exit.COMPLETE, state().exit)
            assertTrue(UserKnobs.runningTunnels.first().isEmpty())
        } finally {
            releaseManual.countDown()
            releaseExit.countDown()
            withContext(NonCancellable) {
                manual?.join()
                disabling?.join()
                env.close()
            }
        }
    }

    @Test fun completedManualOwnerFollowsAndLosesTransportWithoutDefaultSubstitutionOrHoldRelease() = runTest {
        val env = environment()
        try {
            val default = env.manager.create("default", TunnelTestEnvironment.profile())
            val held = env.manager.create("fixture", TunnelTestEnvironment.profile())
            connect(env, held)
            val oldFailure = NetworkAutomationState.Receipt(held.name, "UP", "UP", 0, 0, 0, 1, NetworkAutomationState.Outcome.FAILED)
            NetworkAutomationState.update { it.copy(enabled = true, episode = 1, defaultName = default.name, resolved = false,
                attempt = oldFailure, serial = 1) }
            val controller = env.manager.networkAutomation
            val generation = Any()
            assertTrue(controller.attach(generation))
            controller.observe(networks(generation, 1, 1))
            env.manager.setTunnelState(held, Tunnel.State.UP)
            val manual = state().manual
            assertEquals(NetworkAutomationState.Outcome.UP, manual?.outcome)
            val backend = requireNotNull(activeBackend)
            val session = requireNotNull(backend.getTransportSnapshot(held)).session()
            controller.observe(networks(generation, 2, 2))
            VpnApplication.getPreferencesDataStore().data.first {
                NetworkAutomationState.read(it).attempt?.let { receipt -> receipt.serial > requireNotNull(manual).serial && receipt.outcome == NetworkAutomationState.Outcome.UP } == true
            }
            assertEquals(ShadowNetwork.newInstance(2), backend.getTransportSnapshot(held)?.network())
            assertSame(session, backend.getTransportSnapshot(held)?.session())
            controller.observe(AutomationNetworks(generation, 3, emptyList(), null))
            VpnApplication.getPreferencesDataStore().data.first { NetworkAutomationState.read(it).attempt?.outcome == NetworkAutomationState.Outcome.NO_TRANSPORT }
            assertEquals(GoBackend.TransportMode.UNAVAILABLE, backend.getTransportSnapshot(held)?.mode())
            controller.observe(networks(generation, 4, 4))
            VpnApplication.getPreferencesDataStore().data.first { NetworkAutomationState.read(it).attempt?.let { receipt ->
                receipt.frontier == 4L && receipt.outcome == NetworkAutomationState.Outcome.UP
            } == true }
            assertEquals(ShadowNetwork.newInstance(4), backend.getTransportSnapshot(held)?.network())
            assertSame(session, backend.getTransportSnapshot(held)?.session())
            assertEquals(manual, state().manual)
            assertEquals(default.name, state().defaultName)
            assertEquals(Tunnel.State.DOWN, backend.getState(default))
            assertEquals(4, NativeShadow.replacements.get())
            env.manager.delete(held)
            val replacement = env.manager.create(held.name, TunnelTestEnvironment.profile())
            val before = NativeShadow.replacements.get()
            controller.detach(generation)
            val replacementGeneration = Any()
            assertTrue(controller.attach(replacementGeneration))
            controller.observe(networks(replacementGeneration, 5, 5))
            controller.status.first { it.condition == NetworkAutomationController.Condition.MANUAL_HOLD }
            runCurrent()
            assertEquals(before, NativeShadow.replacements.get())
            assertEquals(Tunnel.State.DOWN, backend.getState(replacement))
            assertEquals(Tunnel.State.DOWN, backend.getState(default))
            assertEquals(NetworkAutomationState.Outcome.UNRESOLVED, state().manual?.outcome)
        } finally { env.close() }
    }

    @Test fun missingCompletedManualOwnerCannotStartFromRunningMarker() = runTest {
        val env = environment()
        try {
            val held = env.manager.create("fixture", TunnelTestEnvironment.profile())
            connect(env, held, startOwned = false)
            val receipt = NetworkAutomationState.Receipt(held.name, "UP", "UP", 0, 0, 0, 1, NetworkAutomationState.Outcome.UP)
            NetworkAutomationState.update { it.copy(enabled = true, episode = 1, defaultName = "missing", resolved = false,
                manual = receipt, serial = 1) }
            UserKnobs.setRunningTunnels(setOf(held.name))
            val controller = env.manager.networkAutomation
            val generation = Any()
            assertTrue(controller.attach(generation))
            controller.observe(networks(generation, 1, 1))
            controller.status.first { it.condition == NetworkAutomationController.Condition.MANUAL_HOLD }
            assertEquals(Tunnel.State.DOWN, requireNotNull(activeBackend).getState(held))
            assertEquals(0, NativeShadow.starts.get())
            assertEquals(0, NativeShadow.replacements.get())
            assertEquals(receipt, state().manual)
        } finally { env.close() }
    }

    @Test fun recreatedResolvedDefaultRestoresCompletedManualUpFromCommittedRunningIntent() = exerciseRecreatedManualOwner(true)

    @Test fun recreatedNonDefaultManualOwnerCannotStartFromCommittedRunningIntent() = exerciseRecreatedManualOwner(false)

    private fun exerciseRecreatedManualOwner(isDefault: Boolean) = runTest {
        val original = environment()
        var recreated: TunnelTestEnvironment? = null
        try {
            original.manager.create("fixture", TunnelTestEnvironment.profile())
            original.manager.create("default", TunnelTestEnvironment.profile())
            val receipt = NetworkAutomationState.Receipt("fixture", "UP", "UP", 0, 0, 0, 1, NetworkAutomationState.Outcome.UP)
            NetworkAutomationState.update { it.copy(enabled = true, episode = 1, resolved = true,
                defaultName = if (isDefault) "fixture" else "default", manual = receipt, serial = 1) }
            UserKnobs.setRunningTunnels(setOf("fixture"))
            original.close()
            val fresh = environment()
            recreated = fresh
            fresh.store.configs["fixture"] = TunnelTestEnvironment.profile()
            fresh.store.configs["default"] = TunnelTestEnvironment.profile()
            val target = requireNotNull(fresh.manager.getTunnels()["fixture"])
            connect(fresh, target, startOwned = false)
            val controller = fresh.manager.networkAutomation
            val generation = Any()
            assertTrue(controller.attach(generation))
            controller.observe(networks(generation, 1, 1))
            controller.status.first { it.condition == if (isDefault) NetworkAutomationController.Condition.OWNED else NetworkAutomationController.Condition.MANUAL_HOLD }
            assertEquals(if (isDefault) 1 else 0, NativeShadow.starts.get())
            assertEquals(if (isDefault) Tunnel.State.UP else Tunnel.State.DOWN, requireNotNull(activeBackend).getState(target))
            assertEquals(receipt, state().manual)
            assertEquals(if (isDefault) "fixture" else "default", state().defaultName)
        } finally {
            recreated?.close()
            original.close()
        }
    }

    @Test fun selectingSameDefaultDoesNotRetireSettledCaptureUsedByOrdinaryReopen() = runTest {
        val env = environment()
        try {
            val tunnel = env.manager.create("fixture", TunnelTestEnvironment.profile())
            connect(env, tunnel)
            NetworkAutomationState.update { it.copy(enabled = true, episode = 1, defaultName = tunnel.name, resolved = true) }
            val controller = env.manager.networkAutomation
            val generation = Any()
            assertTrue(controller.attach(generation))
            controller.observe(networks(generation, 1, 1))
            controller.status.first { it.condition == NetworkAutomationController.Condition.OWNED }
            controller.selectDefault(tunnel.name)
            assertTrue(requireNotNull(activeBackend).rebind(tunnel))
            assertTrue(requireNotNull(activeBackend?.getTransportSnapshot(tunnel)).ready())
        } finally { env.close() }
    }

    @Test fun alreadyLegacyDisableRetainsUsableOrdinaryCapture() = runTest {
        val env = environment()
        try {
            val tunnel = env.manager.create("fixture", cachedHostnameProfile())
            connect(env, tunnel, startOwned = false)
            env.manager.setTunnelState(tunnel, Tunnel.State.UP)
            val backend = requireNotNull(activeBackend)
            val original = requireNotNull(backend.getTransportSnapshot(tunnel))
            val capture = requireNotNull(NativeShadow.capture)
            assertEquals(GoBackend.TransportMode.LEGACY, original.mode())
            assertTrue(original.ready())
            NetworkAutomationState.update { it.copy(enabled = true, episode = 1, defaultName = "missing", resolved = false) }
            env.manager.networkAutomation.disable()
            assertEquals(NetworkAutomationState.Exit.COMPLETE, state().exit)
            assertEquals(0, NativeShadow.replacements.get())
            assertSame(capture, NativeShadow.capture)
            assertSame(original.session(), backend.getTransportSnapshot(tunnel)?.session())
            assertTrue(backend.rebind(tunnel))
            assertSame(capture, NativeShadow.capture)
            val reopened = requireNotNull(backend.getTransportSnapshot(tunnel))
            assertSame(original.session(), reopened.session())
            assertEquals(GoBackend.TransportMode.LEGACY, reopened.mode())
            assertTrue(reopened.ready())
        } finally { env.close() }
    }

    @Test fun enabledHostnameUpWithoutEligibleNetworkFailsBeforeNativeStartup() = runTest {
        val env = environment()
        try {
            val tunnel = env.manager.create("fixture", cachedHostnameProfile())
            connect(env, tunnel, startOwned = false)
            NetworkAutomationState.update { it.copy(enabled = true, episode = 1, defaultName = "missing", resolved = false) }
            try {
                env.manager.setTunnelState(tunnel, Tunnel.State.UP)
                fail("Expected missing eligible network failure")
            } catch (failure: IOException) {
                assertEquals("No eligible public network", failure.message)
            }
            val manual = requireNotNull(state().manual)
            assertEquals(tunnel.name, manual.name)
            assertEquals(Tunnel.State.UP.name, manual.absolute)
            assertEquals(NetworkAutomationState.Outcome.FAILED, manual.outcome)
            assertEquals(0, NativeShadow.starts.get())
            assertEquals(0, NativeShadow.replacements.get())
            assertEquals(Tunnel.State.DOWN, requireNotNull(activeBackend).getState(tunnel))
            assertNull(activeBackend?.getTransportSnapshot(tunnel))
            assertTrue(UserKnobs.runningTunnels.first().isEmpty())
        } finally { env.close() }
    }

    private fun cachedHostnameProfile(): TunnelConfig {
        val config = TunnelConfig.parse("""
            [Interface]
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            Address = 192.0.2.2/32
            [Peer]
            PublicKey = CQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQk=
            Endpoint = fixture.invalid:51820
        """.trimIndent().byteInputStream())
        val endpoint = config.peers.single().endpoint.get()
        InetEndpoint::class.java.getDeclaredField("resolved").apply { isAccessible = true }.set(endpoint, InetEndpoint.parse("127.0.0.1:51820"))
        InetEndpoint::class.java.getDeclaredField("lastResolution").apply { isAccessible = true }.set(endpoint, Instant.now())
        return config
    }

    private suspend fun state() = NetworkAutomationState.read(VpnApplication.getPreferencesDataStore().data.first())

    private fun failOneSettlement(env: TunnelTestEnvironment, shouldFail: (NetworkAutomationState, NetworkAutomationState) -> Boolean) {
        val delegate = VpnApplication.getPreferencesDataStore()
        var failed = false
        env.replacePreferences(object : DataStore<Preferences> {
            override val data = delegate.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = delegate.updateData { before ->
                val after = transform(before)
                if (!failed && shouldFail(NetworkAutomationState.read(before), NetworkAutomationState.read(after))) {
                    failed = true
                    throw IOException("Synthetic settlement write failure")
                }
                after
            }
        })
    }

    private fun networks(generation: Any, sequence: Long, id: Int): AutomationNetworks {
        val network = ShadowNetwork.newInstance(id)
        val capabilities = NetworkCapabilities().also { caps ->
            val shadow = shadowOf(caps)
            shadow.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
            shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        }
        return AutomationNetworks(generation, sequence, listOf(AutomationNetwork(network, capabilities)), network)
    }

    private fun connect(env: TunnelTestEnvironment, tunnel: ObservableTunnel, startOwned: Boolean = true) {
        val backend = GoBackend(env.application)
        activeBackend = backend
        activeTunnel = tunnel
        @Suppress("UNCHECKED_CAST")
        val reference = GoBackend::class.java.getDeclaredField("vpnServiceRef").apply { isAccessible = true }
            .get(null) as AtomicReference<CompletableFuture<GoBackend.VpnService>>
        reference.set(CompletableFuture())
        NativeShadow.expectedService = Robolectric.buildService(GoBackend.VpnService::class.java).create().get()
        ShadowVpnService.setPrepareResult(null)
        GoBackendRebindTest.BuilderShadow.descriptor = ParcelFileDescriptor.open(folder.newFile(), ParcelFileDescriptor.MODE_READ_WRITE)
        GoBackendRebindTest.BuilderShadow.beforeEstablish = null
        GoBackendRebindTest.BuilderShadow.establishCalls = 0
        GoBackendRebindTest.TransportServiceShadow.reporting = null
        GoBackendRebindTest.TransportServiceShadow.reported.clear()
        NativeShadow.replacements.set(0)
        NativeShadow.starts.set(0)
        NativeShadow.snapshot.set(0)
        NativeShadow.capture = null
        NativeShadow.beforeTransport = null
        env.replaceBackend(backend)
        if (startOwned) backend.setStateOwned(tunnel, Tunnel.State.UP, TunnelTestEnvironment.profile(), ShadowNetwork.newInstance(0), GoBackend.TransportAuthority())
    }

    @Implements(value = GoBackend::class, isInAndroidSdk = false)
    // Robolectric instantiates this shadow through a public no-argument constructor.
    @Suppress("UtilityClassWithPublicConstructor")
    class NativeShadow {
        companion object {
            val replacements = AtomicInteger()
            val starts = AtomicInteger()
            val snapshot = AtomicLong()
            var capture: Any? = null
            var beforeTransport: (() -> Unit)? = null
            lateinit var expectedService: GoBackend.VpnService

            @Implementation @JvmStatic fun wgTurnOn(name: String, fd: Int, settings: String,
                service: GoBackend.VpnService, thread: Thread, id: Long,
                @org.robolectric.annotation.ClassName("com.vpn0wn.android.backend.GoBackend\$SocketCapture") capture: Any): Int {
                assertEquals("fixture", name)
                assertEquals(73, fd)
                assertFalse(settings.isEmpty())
                assertSame(expectedService, service)
                assertSame(Thread.currentThread(), thread)
                assertTrue(id > 0)
                starts.incrementAndGet()
                this.capture = capture
                publish(capture, 1, true)
                return 7
            }

            @Implementation @JvmStatic fun wgTransport(handle: Int, expectedGeneration: Long,
                @org.robolectric.annotation.ClassName("com.vpn0wn.android.backend.GoBackend\$SocketCapture") capture: Any,
                available: Boolean): Long {
                assertEquals(7, handle)
                assertEquals(snapshot.get() ushr 2, expectedGeneration)
                replacements.incrementAndGet()
                beforeTransport?.invoke()
                this.capture = capture
                publish(capture, expectedGeneration + 1, available)
                return (if (available) GoBackend.TransportOutcome.READY else GoBackend.TransportOutcome.NO_TRANSPORT).ordinal.toLong()
            }

            @Implementation @JvmStatic fun wgRebind(handle: Int): Int {
                assertEquals(7, handle)
                publish(requireNotNull(capture), (snapshot.get() ushr 2) + 1, true)
                return 0
            }

            @Implementation @JvmStatic fun wgTransportSnapshot(handle: Int): Long {
                assertEquals(7, handle)
                return snapshot.get()
            }
            @Implementation @JvmStatic fun wgTurnOff(handle: Int) { assertEquals(7, handle) }

            // Robolectric must retain the zero-argument JNI method shape.
            @Suppress("FunctionOnlyReturningConstant")
            @Implementation @JvmStatic fun wgVersion() = "synthetic"

            private fun publish(capture: Any, generation: Long, ready: Boolean) {
                val report = capture.javaClass.getDeclaredMethod("report", Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                val confirm = capture.javaClass.getDeclaredMethod("confirm", Long::class.javaPrimitiveType).apply { isAccessible = true }
                assertEquals(true, report.invoke(capture, generation, ready))
                if (ready) assertEquals(true, confirm.invoke(capture, generation))
                snapshot.set((generation shl 2) or if (ready) 3L else 2L)
            }
        }
    }
}
