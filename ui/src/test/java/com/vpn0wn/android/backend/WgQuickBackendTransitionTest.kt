package com.vpn0wn.android.backend

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.android.util.RootShell
import com.vpn0wn.android.util.ToolsInstaller
import com.vpn0wn.android.util.UserKnobs
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [WgQuickBackendTransitionTest.ToolsShadow::class, WgQuickBackendTransitionTest.KernelSupportShadow::class])
@OptIn(ExperimentalCoroutinesApi::class)
class WgQuickBackendTransitionTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var shell: SimulatedRootShell
    private lateinit var backend: WgQuickBackend
    private lateinit var context: Context
    private val profile = TunnelTestEnvironment.profile()

    @After
    fun resetMain() = Dispatchers.resetMain()

    @Before
    fun setUp() {
        KernelSupportShadow.supported = true
        ToolsShadow.calls.set(0)
        ToolsShadow.blockAtCall = 0
        ToolsShadow.entered = CountDownLatch(1)
        ToolsShadow.release = CountDownLatch(1)
        ToolsShadow.failure = null
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getCacheDir(): File = folder.root
        }
        assertTrue(File(folder.root, "tmp").mkdir())
        shell = SimulatedRootShell(context)
        backend = WgQuickBackend(context, shell, ToolsInstaller(context, shell))
        assertSame(backend, Shadow.extract<KernelSupportShadow>(backend).realBackend)
    }

    @Test
    fun concurrentUpKeepsOnlyOneTunnelWhenMultipleModeIsDisabled() = concurrentUp(false)

    @Test
    fun concurrentUpRetainsBothTunnelsWhenMultipleModeIsEnabled() = concurrentUp(true)

    @Test
    fun runningNamesPropagateToolExtractionFailure() {
        val failure = FileNotFoundException("Injected extraction failure")
        ToolsShadow.failure = failure
        assertSame(failure, assertThrows(FileNotFoundException::class.java) { backend.runningTunnelNames })
        assertTrue(shell.events.isEmpty())
    }

    @Test
    fun runningNamesPropagateRootIoAndCancellationFailures() {
        val failures = listOf(
            RootShell.RootShellException(RootShell.RootShellException.Reason.NO_ROOT_ACCESS),
            IOException("Injected observation failure"),
            CancellationException("Injected observation cancellation"),
        )
        for (failure in failures) {
            shell.observationFailure = failure
            assertSame(failure, assertThrows(failure.javaClass) { backend.runningTunnelNames })
            assertSame(failure, assertThrows(failure.javaClass) { backend.getState(NamedTunnel("one")) })
        }
        assertTrue(shell.events.isEmpty())
    }

    @Test
    fun nonzeroObservationRejectsEvenCollectedInterfaceNames() {
        val tunnel = NamedTunnel("one")
        backend.setState(tunnel, Tunnel.State.UP, profile)
        shell.events.clear()
        shell.observationExit = 7
        val failure = assertThrows(IOException::class.java) { backend.runningTunnelNames }
        assertTrue(failure.message!!.contains("code 7"))
        assertThrows(IOException::class.java) { backend.setState(tunnel, Tunnel.State.DOWN, profile) }
        assertEquals(Tunnel.State.UP, tunnel.observedState)
        assertTrue(shell.events.isEmpty())
    }

    @Test
    fun failedObservationRejectsAllTransitionsWithoutChangingActiveTunnel() {
        val tunnel = NamedTunnel("one")
        backend.setState(tunnel, Tunnel.State.UP, profile)
        shell.events.clear()
        val failure = IOException("Injected observation failure")
        shell.observationFailure = failure
        for (state in Tunnel.State.entries) {
            assertSame(failure, assertThrows(IOException::class.java) { backend.setState(tunnel, state, profile) })
        }
        assertEquals(Tunnel.State.UP, tunnel.observedState)
        assertTrue(shell.events.isEmpty())
        shell.observationFailure = null
        assertEquals(setOf("one"), backend.runningTunnelNames)
        assertEquals(Tunnel.State.DOWN, backend.setState(tunnel, Tunnel.State.DOWN, profile))
        assertEquals(listOf("down:one"), shell.events)
    }

    @Test
    fun successfulEmptyObservationAllowsDownNoOp() {
        val tunnel = NamedTunnel("one")
        assertTrue(backend.runningTunnelNames.isEmpty())
        assertEquals(Tunnel.State.DOWN, backend.getState(tunnel))
        assertEquals(Tunnel.State.DOWN, backend.setState(tunnel, Tunnel.State.DOWN, null))
        shell.emptyInventoryLine = true
        assertTrue(backend.runningTunnelNames.isEmpty())
        assertEquals(Tunnel.State.DOWN, backend.setState(tunnel, Tunnel.State.DOWN, null))
        assertTrue(shell.events.isEmpty())
    }

    @Test
    fun failedObservationPreventsDeleteAndRetainsRecoveryProfile() = recreatedKernelEnvironment(true) { env ->
        val tunnel = env.manager.getTunnels()["one"]!!
        val failure = IOException("Injected observation failure")
        shell.observationFailure = failure
        val caught = runCatching { env.manager.delete(tunnel) }.exceptionOrNull()
        assertEquals(failure.message, caught?.message)
        assertEquals(Tunnel.State.UP, tunnel.state)
        assertSame(tunnel, env.manager.getTunnels()["one"])
        assertSame(profile, env.store.configs["one"])
        assertTrue(env.store.deleteAttempts.isEmpty())
        assertTrue(shell.events.isEmpty())
        shell.observationFailure = null
        env.manager.delete(tunnel)
        assertEquals(listOf("down:one"), shell.events)
        assertTrue(backend.runningTunnelNames.isEmpty())
        assertTrue(env.store.configs.isEmpty())
    }

    @Test
    fun failedObservationPreventsBackendSwitchAndRetainsSetting() = recreatedKernelEnvironment(true) { env ->
        val tunnel = env.manager.getTunnels()["one"]!!
        val originalSetting = UserKnobs.enableKernelModule.first()
        shell.observationFailure = IOException("Injected observation failure")
        assertTrue(runCatching { env.manager.prepareBackendSwitch(!originalSetting) }.exceptionOrNull() is IOException)
        assertEquals(originalSetting, UserKnobs.enableKernelModule.first())
        assertEquals(Tunnel.State.UP, tunnel.state)
        assertSame(profile, env.store.configs["one"])
        assertTrue(shell.events.isEmpty())
        shell.observationFailure = null
        env.manager.prepareBackendSwitch(!originalSetting)
        assertEquals(!originalSetting, UserKnobs.enableKernelModule.first())
        assertEquals(Tunnel.State.DOWN, tunnel.state)
        assertEquals(listOf("down:one"), shell.events)
    }

    @Test
    fun failedInitialObservationSettlesInventoryWaitersAndCanRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.root, backgroundScope)
        try {
            env.store.configs["one"] = profile
            backend.setState(NamedTunnel("one"), Tunnel.State.UP, profile)
            shell.events.clear()
            env.replaceBackend(backend)
            shell.observationFailure = IOException("Injected observation failure")
            assertTrue(runCatching { env.manager.getTunnels() }.exceptionOrNull() is IOException)
            assertSame(profile, env.store.configs["one"])
            assertTrue(shell.events.isEmpty())
            shell.observationFailure = null
            val tunnel = env.manager.getTunnels()["one"]!!
            assertEquals(Tunnel.State.UP, tunnel.state)
        } finally {
            env.close()
        }
    }

    @Test
    fun managerDisconnectsKernelTunnelAfterBackendRecreation() = recreatedKernelEnvironment(true) { env ->
        val tunnel = env.manager.getTunnels()["one"]!!
        assertEquals(Tunnel.State.UP, tunnel.state)
        assertEquals(Tunnel.State.DOWN, tunnel.setStateAsync(Tunnel.State.DOWN))
        assertEquals(Tunnel.State.DOWN, tunnel.state)
        assertTrue(backend.runningTunnelNames.isEmpty())
        assertSame(profile, env.store.configs["one"])
        assertEquals(listOf("one"), env.store.loadAttempts)
        assertEquals(listOf("down:one"), shell.events)
    }

    @Test
    fun managerRenamesActiveKernelTunnelAfterBackendRecreation() = recreatedKernelEnvironment(true) { env ->
        val tunnel = env.manager.getTunnels()["one"]!!
        assertEquals("renamed", tunnel.setNameAsync("renamed"))
        assertEquals(Tunnel.State.UP, tunnel.state)
        assertEquals(setOf("renamed"), backend.runningTunnelNames)
        assertEquals(setOf("renamed"), env.store.configs.keys)
        assertSame(profile, env.store.configs["renamed"])
        assertEquals(listOf("down:one", "up:renamed"), shell.events)
    }

    @Test
    fun managerDeletesActiveKernelTunnelAfterBackendRecreation() = recreatedKernelEnvironment(true) { env ->
        val tunnel = env.manager.getTunnels()["one"]!!
        env.manager.delete(tunnel)
        assertEquals(Tunnel.State.DOWN, tunnel.state)
        assertTrue(backend.runningTunnelNames.isEmpty())
        assertTrue(env.manager.getTunnels().isEmpty())
        assertTrue(env.store.configs.isEmpty())
        assertEquals(listOf("down:one"), shell.events)
    }

    @Test
    fun unreadableActiveKernelProfileFailsBeforeShutdownAndCanRetry() = recreatedKernelEnvironment(true) { env ->
        val tunnel = env.manager.getTunnels()["one"]!!
        env.store.failLoadNames += "one"
        try {
            tunnel.setStateAsync(Tunnel.State.DOWN)
            fail("Expected profile load failure")
        } catch (_: IOException) { }
        assertEquals(Tunnel.State.UP, tunnel.state)
        assertEquals(setOf("one"), backend.runningTunnelNames)
        assertSame(profile, env.store.configs["one"])
        assertTrue(shell.events.isEmpty())
        env.store.failLoadNames.clear()
        assertEquals(Tunnel.State.DOWN, tunnel.setStateAsync(Tunnel.State.DOWN))
    }

    @Test
    fun failedRecreatedKernelShutdownPreservesProfileAndObservedUp() = recreatedKernelEnvironment(true) { env ->
        val tunnel = env.manager.getTunnels()["one"]!!
        shell.failDown["one"] = 1
        try {
            tunnel.setStateAsync(Tunnel.State.DOWN)
            fail("Expected shutdown failure")
        } catch (_: BackendException) { }
        assertEquals(Tunnel.State.UP, tunnel.state)
        assertEquals(setOf("one"), backend.runningTunnelNames)
        assertSame(profile, env.store.configs["one"])
        assertSame(tunnel, env.manager.getTunnels()["one"])
        assertEquals(Tunnel.State.DOWN, tunnel.setStateAsync(Tunnel.State.DOWN))
    }

    @Test
    fun unreadableActiveKernelProfilePreventsDeleteBeforeAnyMutation() = recreatedKernelEnvironment(true) { env ->
        val tunnel = env.manager.getTunnels()["one"]!!
        env.store.failLoadNames += "one"
        try {
            env.manager.delete(tunnel)
            fail("Expected profile load failure")
        } catch (_: IOException) { }
        assertEquals(Tunnel.State.UP, tunnel.state)
        assertEquals(setOf("one"), backend.runningTunnelNames)
        assertSame(tunnel, env.manager.getTunnels()["one"])
        assertSame(profile, env.store.configs["one"])
        assertTrue(shell.events.isEmpty())
        assertTrue(env.store.deleteAttempts.isEmpty())
    }

    @Test
    fun unreadableActiveKernelProfilePreservesNameBeforeAnyMutation() = recreatedKernelEnvironment(true) { env ->
        val tunnel = env.manager.getTunnels()["one"]!!
        env.store.failLoadNames += "one"
        try {
            tunnel.setNameAsync("renamed")
            fail("Expected profile load failure")
        } catch (_: IOException) { }
        assertEquals("one", tunnel.name)
        assertEquals(Tunnel.State.UP, tunnel.state)
        assertEquals(setOf("one"), backend.runningTunnelNames)
        assertSame(tunnel, env.manager.getTunnels()["one"])
        assertSame(profile, env.store.configs["one"])
        assertTrue(shell.events.isEmpty())
    }

    @Test
    fun failedDeleteRestoresRecreatedKernelTunnel() = recreatedKernelEnvironment(true) { env ->
        val tunnel = env.manager.getTunnels()["one"]!!
        env.store.failDelete = true
        try {
            env.manager.delete(tunnel)
            fail("Expected profile delete failure")
        } catch (_: IOException) { }
        assertEquals(Tunnel.State.UP, tunnel.state)
        assertEquals(setOf("one"), backend.runningTunnelNames)
        assertSame(profile, env.store.configs["one"])
        assertSame(tunnel, env.manager.getTunnels()["one"])
        assertEquals(listOf("down:one", "up:one"), shell.events)
    }

    @Test
    fun inactiveKernelDownAndDeleteDoNotLoadUnreadableProfile() = recreatedKernelEnvironment(false) { env ->
        val tunnel = env.manager.getTunnels()["one"]!!
        env.store.failLoadNames += "one"
        assertEquals(Tunnel.State.DOWN, tunnel.setStateAsync(Tunnel.State.DOWN))
        env.manager.delete(tunnel)
        assertTrue(env.manager.getTunnels().isEmpty())
        assertTrue(env.store.configs.isEmpty())
        assertTrue(env.store.loadAttempts.isEmpty())
        assertTrue(shell.events.isEmpty())
    }

    private fun recreatedKernelEnvironment(active: Boolean, block: suspend (TunnelTestEnvironment) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.root, backgroundScope)
        try {
            env.store.configs["one"] = profile
            if (active) backend.setState(NamedTunnel("one"), Tunnel.State.UP, profile)
            backend = WgQuickBackend(context, shell, ToolsInstaller(context, shell))
            env.replaceBackend(backend)
            shell.events.clear()
            block(env)
        } finally {
            env.close()
        }
    }

    private fun concurrentUp(multiple: Boolean) {
        backend.setMultipleTunnels(multiple)
        ToolsShadow.blockAtCall = 2
        val first = NamedTunnel("first")
        val second = NamedTunnel("second")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val firstUp = executor.submit<Tunnel.State> { backend.setState(first, Tunnel.State.UP, profile) }
            assertTrue(ToolsShadow.entered.await(5, TimeUnit.SECONDS))
            val contenderStarted = CountDownLatch(1)
            val secondUp = executor.submit<Tunnel.State> {
                contenderStarted.countDown()
                backend.setState(second, Tunnel.State.UP, profile)
            }
            assertTrue(contenderStarted.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { secondUp.get(500, TimeUnit.MILLISECONDS) }
            ToolsShadow.release.countDown()
            assertEquals(Tunnel.State.UP, firstUp.get(5, TimeUnit.SECONDS))
            assertEquals(Tunnel.State.UP, secondUp.get(5, TimeUnit.SECONDS))
            val expected = if (multiple) setOf("first", "second") else setOf("second")
            assertEquals(expected, backend.runningTunnelNames)
            assertEquals(if (multiple) Tunnel.State.UP else Tunnel.State.DOWN, backend.getState(first))
            assertEquals(if (multiple) Tunnel.State.UP else Tunnel.State.DOWN, first.observedState)
            assertEquals(Tunnel.State.UP, backend.getState(second))
            assertEquals(Tunnel.State.UP, second.observedState)
        } finally {
            ToolsShadow.release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun liveMultipleSettingDoesNotBlockMainBehindTransition() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.root, backgroundScope)
        try {
            val app = env.application
            val deadlockGuardSeconds = 5L
            val initialSettingApplied = CountDownLatch(1)
            val settingEntered = CountDownLatch(1)
            val settingApplied = CountDownLatch(1)
            val multipleTunnels = booleanPreferencesKey("multiple_tunnels")
            val preferences = com.vpn0wn.android.Application.getPreferencesDataStore()
            val observedPreferences = object : DataStore<Preferences> by preferences {
                override val data = flow {
                    preferences.data.collect { value ->
                        val multiple = value[multipleTunnels] == true
                        if (multiple) settingEntered.countDown()
                        emit(value)
                        if (multiple) {
                            settingApplied.countDown()
                        } else {
                            // first() aborts emit; only the live collector acknowledges its initial setting.
                            initialSettingApplied.countDown()
                        }
                    }
                }
            }
            for ((name, value) in mapOf("rootShell" to shell, "toolsInstaller" to ToolsInstaller(app, shell), "preferencesDataStore" to observedPreferences))
                com.vpn0wn.android.Application::class.java.getDeclaredField(name)
                    .apply { isAccessible = true }.set(app, value)
            UserKnobs.setEnableKernelModule(true)
            val kernel = suspendCoroutine<Backend> { continuation ->
                val result = com.vpn0wn.android.Application::class.java
                    .getDeclaredMethod("determineBackend", Continuation::class.java)
                    .apply { isAccessible = true }.invoke(app, continuation)
                if (result !== COROUTINE_SUSPENDED) continuation.resume(result as Backend)
            } as WgQuickBackend
            assertEquals(1, shell.startCalls)
            assertTrue("Initial Application setting was not applied", withContext(Dispatchers.IO) {
                initialSettingApplied.await(deadlockGuardSeconds, TimeUnit.SECONDS)
            })
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val transition = executor.submit {
                synchronized(kernel) {
                    held.countDown()
                    try {
                        assertTrue("Kernel transition monitor timed out", release.await(deadlockGuardSeconds, TimeUnit.SECONDS))
                    } finally {
                        release.countDown()
                    }
                }
            }
            try {
                assertTrue("Kernel transition monitor was not acquired", withContext(Dispatchers.IO) {
                    held.await(deadlockGuardSeconds, TimeUnit.SECONDS)
                })
                com.vpn0wn.android.Application.getPreferencesDataStore().edit {
                    it[multipleTunnels] = true
                }
                assertTrue("Application did not receive the live setting", withContext(Dispatchers.IO) {
                    settingEntered.await(deadlockGuardSeconds, TimeUnit.SECONDS)
                })
                var mainProgress = false
                backgroundScope.launch(Dispatchers.Main) { mainProgress = true }
                runCurrent()
                assertTrue("Main blocked behind kernel transition", mainProgress)
                assertEquals("Kernel transition released before Main progress", 1L, release.count)
                assertEquals("Setting applied before the kernel transition released", 1L, settingApplied.count)
                release.countDown()
                withContext(Dispatchers.IO) { transition.get(deadlockGuardSeconds, TimeUnit.SECONDS) }
                assertTrue("Application did not finish applying the live setting", withContext(Dispatchers.IO) {
                    settingApplied.await(deadlockGuardSeconds, TimeUnit.SECONDS)
                })
                val setting = WgQuickBackend::class.java.getDeclaredField("multipleTunnels").apply { isAccessible = true }
                assertTrue(withContext(Dispatchers.IO) {
                    synchronized(kernel) { setting.getBoolean(kernel) }
                })
            } finally {
                release.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(deadlockGuardSeconds, TimeUnit.SECONDS))
            }
        } finally {
            env.close()
        }
    }

    @Test
    fun failedActivationRestoresPreviousTunnel() {
        val first = NamedTunnel("first")
        val second = NamedTunnel("second")
        backend.setState(first, Tunnel.State.UP, profile)
        shell.failUp["second"] = 1
        assertThrows(BackendException::class.java) { backend.setState(second, Tunnel.State.UP, profile) }
        assertEquals(setOf("first"), backend.runningTunnelNames)
        assertEquals(Tunnel.State.UP, backend.getState(first))
        assertEquals(Tunnel.State.DOWN, backend.getState(second))
        assertEquals(profile.toWgQuickString(), shell.applied["first"])
    }

    @Test
    fun rollbackFailureIsAttachedAndObservedStateRemainsDown() {
        val first = NamedTunnel("first")
        val second = NamedTunnel("second")
        backend.setState(first, Tunnel.State.UP, profile)
        shell.failUp["second"] = 1
        shell.failUp["first"] = 1
        val failure = assertThrows(BackendException::class.java) { backend.setState(second, Tunnel.State.UP, profile) }
        assertEquals(1, failure.suppressed.size)
        assertTrue(failure.suppressed.single() is BackendException)
        assertTrue(backend.runningTunnelNames.isEmpty())
        assertEquals(Tunnel.State.DOWN, backend.getState(first))
        assertEquals(Tunnel.State.DOWN, backend.getState(second))
    }

    @Test
    fun failedShutdownDoesNotActivateReplacement() {
        val first = NamedTunnel("first")
        val second = NamedTunnel("second")
        backend.setState(first, Tunnel.State.UP, profile)
        shell.failDown["first"] = 1
        assertThrows(BackendException::class.java) { backend.setState(second, Tunnel.State.UP, profile) }
        assertEquals(setOf("first"), backend.runningTunnelNames)
        assertEquals(Tunnel.State.UP, backend.getState(first))
        assertEquals(Tunnel.State.DOWN, backend.getState(second))
        assertFalse(shell.events.contains("up:second"))
    }

    @Test
    fun failedConfigUpdateRestoresOriginalConfig() {
        val tunnel = NamedTunnel("one")
        backend.setState(tunnel, Tunnel.State.UP, profile)
        shell.failUp["one"] = 1
        val replacement = TunnelTestEnvironment.profile(51821)
        assertThrows(BackendException::class.java) { backend.setState(tunnel, Tunnel.State.UP, replacement) }
        assertEquals(setOf("one"), backend.runningTunnelNames)
        assertEquals(profile.toWgQuickString(), shell.applied["one"])
    }

    private class NamedTunnel(private val name: String) : Tunnel {
        var observedState = Tunnel.State.DOWN
            private set
        override fun getName(): String = name
        override fun onStateChange(newState: Tunnel.State) {
            observedState = newState
        }
    }

    private class SimulatedRootShell(context: Context) : RootShell(context) {
        private val commandLock = Any()
        private val running = linkedSetOf<String>()
        val applied = mutableMapOf<String, String>()
        val failUp = mutableMapOf<String, Int>()
        val failDown = mutableMapOf<String, Int>()
        val events = mutableListOf<String>()
        var observationFailure: Exception? = null
        var observationExit = 0
        var emptyInventoryLine = false
        var startCalls = 0
            private set

        override fun start() {
            startCalls++
        }

        override fun run(output: MutableCollection<String>?, command: String): Int = synchronized(commandLock) {
            if (command == "wg show interfaces") {
                observationFailure?.let { throw it }
                if (running.isNotEmpty() || emptyInventoryLine) output?.add(running.joinToString(" "))
                return@synchronized observationExit
            }
            val match = Regex("wg-quick (up|down) '([^']+)'").find(command)
                ?: error("Unexpected fixture command")
            val direction = match.groupValues[1]
            val file = File(match.groupValues[2])
            val name = file.name.removeSuffix(".conf")
            events.add("$direction:$name")
            val faults = if (direction == "up") failUp else failDown
            val remaining = faults[name] ?: 0
            if (remaining > 0) {
                faults[name] = remaining - 1
                return@synchronized 7
            }
            if (direction == "up") {
                running.add(name)
                applied[name] = file.readText()
            } else {
                running.remove(name)
                applied.remove(name)
            }
            0
        }
    }

    @Implements(ToolsInstaller::class)
    class ToolsShadow {
        @Implementation
        fun ensureToolsAvailable() {
            failure?.let { throw it }
            if (calls.incrementAndGet() == blockAtCall) {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
        }

        companion object {
            val calls = AtomicInteger()
            var blockAtCall = 0
            lateinit var entered: CountDownLatch
            lateinit var release: CountDownLatch
            var failure: Exception? = null
        }
    }

    @Implements(WgQuickBackend::class)
    class KernelSupportShadow {
        @RealObject
        lateinit var realBackend: WgQuickBackend

        companion object {
            var supported = false
            @Implementation
            @JvmStatic
            fun hasKernelSupport(): Boolean = supported
        }
    }
}
