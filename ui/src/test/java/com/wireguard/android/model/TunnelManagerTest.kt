package com.wireguard.android.model

import android.app.Application
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.util.UserKnobs
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class TunnelManagerTest {
    @get:Rule val folder = TemporaryFolder()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.environment(): TunnelTestEnvironment {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return TunnelTestEnvironment(folder.root, backgroundScope)
    }

    @Test
    fun initializationFailureSettlesWaitersAndRetries() = runTest {
        val env = environment()
        env.store.failEnumerate = true
        try {
            env.manager.getTunnels()
            fail("Expected enumeration failure")
        } catch (_: IOException) { }
        env.store.failEnumerate = false
        env.store.configs["one"] = TunnelTestEnvironment.profile()
        assertEquals(setOf("one"), env.manager.getTunnels().map { it.name }.toSet())
    }

    @Test
    fun cancelledInitializationSettlesWaiters() = runTest {
        val env = environment()
        env.manager.onCreate()
        backgroundScope.cancel()
        try {
            env.manager.getTunnels()
            fail("Expected cancelled initialization")
        } catch (_: kotlinx.coroutines.CancellationException) { }
    }

    @Test
    fun toggleThenDeleteCannotLeaveAnActiveBackend() = runTest {
        val env = environment()
        val tunnel = env.manager.create("one", TunnelTestEnvironment.profile())
        env.backend.blockUp = true
        val activation = async { tunnel.setStateAsync(Tunnel.State.TOGGLE) }
        try {
            assertTrue(withContext(Dispatchers.IO) { env.backend.upEntered.await(5, TimeUnit.SECONDS) })
            val deletion = async { env.manager.delete(tunnel) }
            runCurrent()
            assertFalse(deletion.isCompleted)
            env.backend.releaseUp.countDown()
            activation.await()
            deletion.await()
            assertEquals(Tunnel.State.DOWN, env.backend.getState(tunnel))
            assertTrue(env.manager.getTunnels().isEmpty())
            assertTrue(env.store.configs.isEmpty())
            assertTrue(UserKnobs.runningTunnels.first().isEmpty())
        } finally {
            env.backend.releaseUp.countDown()
        }
    }

    @Test
    fun failedDeleteRestoresActiveProfileAndOwnership() = runTest {
        val env = environment()
        val tunnel = env.manager.create("one", TunnelTestEnvironment.profile())
        tunnel.setStateAsync(Tunnel.State.UP)
        env.store.failDelete = true
        try {
            env.manager.delete(tunnel)
            fail("Expected delete failure")
        } catch (_: IOException) { }
        assertEquals(Tunnel.State.UP, env.backend.getState(tunnel))
        assertEquals(Tunnel.State.UP, tunnel.state)
        assertSame(tunnel, env.manager.getTunnels()["one"])
        assertTrue(env.store.configs.containsKey("one"))
    }

    @Test
    fun failedActivationSettlesStateAndAllowsNextRequest() = runTest {
        val env = environment()
        val tunnel = env.manager.create("one", TunnelTestEnvironment.profile())
        env.backend.failUp = true
        try {
            tunnel.setStateAsync(Tunnel.State.UP)
            fail("Expected activation failure")
        } catch (_: IOException) { }
        assertEquals(Tunnel.State.DOWN, tunnel.state)
        env.backend.failUp = false
        assertEquals(Tunnel.State.UP, tunnel.setStateAsync(Tunnel.State.UP))
    }

    @Test
    fun cancellationAfterAdmissionStillSettlesBackendAndModel() = runTest {
        val env = environment()
        val tunnel = env.manager.create("one", TunnelTestEnvironment.profile())
        env.backend.blockUp = true
        val activation = launch { tunnel.setStateAsync(Tunnel.State.UP) }
        try {
            assertTrue(withContext(Dispatchers.IO) { env.backend.upEntered.await(5, TimeUnit.SECONDS) })
            activation.cancel()
            env.backend.releaseUp.countDown()
            activation.join()
            assertTrue(activation.isCancelled)
            assertEquals(Tunnel.State.UP, tunnel.state)
            assertEquals(Tunnel.State.UP, env.backend.getState(tunnel))
            assertSame(tunnel, env.manager.getTunnels()["one"])
        } finally {
            env.backend.releaseUp.countDown()
        }
    }

    @Test
    fun queuedRequestCannotReactivateDeletedTunnel() = runTest {
        val env = environment()
        val tunnel = env.manager.create("one", TunnelTestEnvironment.profile())
        env.backend.blockUp = true
        val activation = async { tunnel.setStateAsync(Tunnel.State.UP) }
        try {
            assertTrue(withContext(Dispatchers.IO) { env.backend.upEntered.await(5, TimeUnit.SECONDS) })
            val deletion = async { env.manager.delete(tunnel) }
            val stale = async {
                try {
                    env.manager.setTunnelState(tunnel, Tunnel.State.UP)
                    false
                } catch (_: IllegalStateException) { true }
            }
            runCurrent()
            env.backend.releaseUp.countDown()
            activation.await()
            deletion.await()
            assertTrue(stale.await())
            assertEquals(Tunnel.State.DOWN, env.backend.getState(tunnel))
        } finally {
            env.backend.releaseUp.countDown()
        }
    }

    @Test
    fun failedConfigSaveRestoresRuntimeAndKeepsCachedProfile() = runTest {
        val env = environment()
        val previous = TunnelTestEnvironment.profile(51820)
        val tunnel = env.manager.create("one", previous)
        tunnel.setStateAsync(Tunnel.State.UP)
        env.store.failSave = true
        try {
            tunnel.setConfigAsync(TunnelTestEnvironment.profile(51821))
            fail("Expected save failure")
        } catch (_: IOException) { }
        assertEquals(previous.toWgQuickString(), env.backend.appliedConfigs[tunnel]!!.toWgQuickString())
        assertEquals(previous.toWgQuickString(), env.store.configs["one"]!!.toWgQuickString())
        assertEquals(previous.toWgQuickString(), tunnel.peekConfig()!!.toWgQuickString())
        assertEquals(Tunnel.State.UP, tunnel.state)
    }

    @Test
    fun downRequestQueuesBehindUpBeforeAdmission() = runTest {
        val env = environment()
        val blocker = env.manager.create("blocker", TunnelTestEnvironment.profile())
        val tunnel = env.manager.create("one", TunnelTestEnvironment.profile())
        env.backend.blockUp = true
        val active = async { blocker.setStateAsync(Tunnel.State.UP) }
        try {
            assertTrue(withContext(Dispatchers.IO) { env.backend.upEntered.await(5, TimeUnit.SECONDS) })
            val up = async { tunnel.setStateAsync(Tunnel.State.UP) }
            val down = async { tunnel.setStateAsync(Tunnel.State.DOWN) }
            runCurrent()
            assertEquals(Tunnel.State.DOWN, tunnel.state)
            assertFalse(down.isCompleted)
            env.backend.releaseUp.countDown()
            active.await()
            up.await()
            assertEquals(Tunnel.State.DOWN, down.await())
            assertEquals(Tunnel.State.DOWN, env.backend.getState(tunnel))
        } finally {
            env.backend.releaseUp.countDown()
        }
    }

    @Test
    fun backendSwitchDrainsQueuedActivationBeforeChangingSetting() = runTest {
        val env = environment()
        val tunnel = env.manager.create("one", TunnelTestEnvironment.profile())
        env.backend.blockUp = true
        val activation = async { tunnel.setStateAsync(Tunnel.State.UP) }
        try {
            assertTrue(withContext(Dispatchers.IO) { env.backend.upEntered.await(5, TimeUnit.SECONDS) })
            val switching = async { env.manager.prepareBackendSwitch(true) }
            runCurrent()
            assertFalse(switching.isCompleted)
            assertFalse(UserKnobs.enableKernelModule.first())
            env.backend.releaseUp.countDown()
            activation.await()
            switching.await()
            assertEquals(Tunnel.State.DOWN, env.backend.getState(tunnel))
            assertTrue(UserKnobs.enableKernelModule.first())
        } finally {
            env.backend.releaseUp.countDown()
        }
    }

    @Test
    fun switchingSingleActiveTunnelPersistsOnlyCurrentBackendOwner() = runTest {
        val env = environment()
        env.backend.singleActiveTunnel = true
        val first = env.manager.create("first", TunnelTestEnvironment.profile())
        val second = env.manager.create("second", TunnelTestEnvironment.profile())
        first.setStateAsync(Tunnel.State.UP)
        second.setStateAsync(Tunnel.State.UP)
        assertEquals(Tunnel.State.DOWN, first.state)
        assertEquals(Tunnel.State.UP, second.state)
        assertEquals(setOf("second"), UserKnobs.runningTunnels.first())
    }

    @Test
    fun serviceDownNotificationReconcilesAfterOlderUpSnapshot() = runTest {
        val env = environment()
        val tunnel = env.manager.create("one", TunnelTestEnvironment.profile())
        tunnel.setStateAsync(Tunnel.State.UP)
        env.backend.blockStateRead = true
        val reading = async { env.manager.getTunnelState(tunnel) }
        try {
            assertTrue(withContext(Dispatchers.IO) { env.backend.stateReadEntered.await(5, TimeUnit.SECONDS) })
            val existingJobs = backgroundScope.coroutineContext[kotlinx.coroutines.Job]!!.children.toSet()
            withContext(Dispatchers.IO) { env.backend.setState(tunnel, Tunnel.State.DOWN, null) }
            val notifications = backgroundScope.coroutineContext[kotlinx.coroutines.Job]!!.children.filter { it !in existingJobs }.toList()
            env.backend.blockStateRead = false
            env.backend.releaseStateRead.countDown()
            reading.await()
            for (notification in notifications) notification.join()
            assertEquals(Tunnel.State.DOWN, tunnel.state)
            assertEquals(setOf("one"), UserKnobs.runningTunnels.first())
            env.manager.restoreState(true)
            assertEquals(Tunnel.State.UP, tunnel.state)
        } finally {
            env.backend.releaseStateRead.countDown()
        }
    }

    @Test
    fun stateReadCannotOverwriteLaterActivation() = runTest {
        val env = environment()
        val tunnel = env.manager.create("one", TunnelTestEnvironment.profile())
        env.backend.blockStateRead = true
        val reading = async { env.manager.getTunnelState(tunnel) }
        try {
            assertTrue(withContext(Dispatchers.IO) { env.backend.stateReadEntered.await(5, TimeUnit.SECONDS) })
            val activation = async { tunnel.setStateAsync(Tunnel.State.UP) }
            runCurrent()
            assertFalse(activation.isCompleted)
            env.backend.blockStateRead = false
            env.backend.releaseStateRead.countDown()
            assertEquals(Tunnel.State.DOWN, reading.await())
            assertEquals(Tunnel.State.UP, activation.await())
            assertEquals(Tunnel.State.UP, tunnel.state)
        } finally {
            env.backend.releaseStateRead.countDown()
        }
    }

    @Test
    fun backendSwitchRejectsQueuedActivationUntilRollback() = runTest {
        val env = environment()
        val tunnel = env.manager.create("one", TunnelTestEnvironment.profile())
        val switching = async { env.manager.prepareBackendSwitch(true) }
        val activation = async {
            try {
                tunnel.setStateAsync(Tunnel.State.UP)
                false
            } catch (_: IllegalStateException) { true }
        }
        switching.await()
        assertTrue(activation.await())
        assertEquals(Tunnel.State.DOWN, env.backend.getState(tunnel))
        assertTrue(UserKnobs.enableKernelModule.first())
        env.manager.cancelBackendSwitch(false)
        assertFalse(UserKnobs.enableKernelModule.first())
        assertEquals(Tunnel.State.UP, tunnel.setStateAsync(Tunnel.State.UP))
    }

    @Test
    fun alreadyDownRequestClearsPersistedRunningState() = runTest {
        val env = environment()
        val tunnel = env.manager.create("one", TunnelTestEnvironment.profile())
        UserKnobs.setRunningTunnels(setOf("one"))
        assertEquals(Tunnel.State.DOWN, tunnel.setStateAsync(Tunnel.State.DOWN))
        assertTrue(UserKnobs.runningTunnels.first().isEmpty())
    }

    @Test
    fun downRequestWaitsForInFlightToggle() = runTest {
        val env = environment()
        val tunnel = env.manager.create("one", TunnelTestEnvironment.profile())
        env.backend.blockUp = true
        val activation = async { tunnel.setStateAsync(Tunnel.State.TOGGLE) }
        try {
            assertTrue(withContext(Dispatchers.IO) { env.backend.upEntered.await(5, TimeUnit.SECONDS) })
            val shutdown = async { tunnel.setStateAsync(Tunnel.State.DOWN) }
            runCurrent()
            assertFalse(shutdown.isCompleted)
            env.backend.releaseUp.countDown()
            activation.await()
            assertEquals(Tunnel.State.DOWN, shutdown.await())
            assertEquals(Tunnel.State.DOWN, env.backend.getState(tunnel))
        } finally {
            env.backend.releaseUp.countDown()
        }
    }
}
