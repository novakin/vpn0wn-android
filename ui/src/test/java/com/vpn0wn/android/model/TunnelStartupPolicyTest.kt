/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.model

import android.content.Intent
import android.os.Looper
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.vpn0wn.android.BootShutdownReceiver
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.backend.WgQuickBackend
import com.vpn0wn.android.util.RootShell
import com.vpn0wn.android.util.ToolsInstaller
import com.vpn0wn.android.util.UserKnobs
import com.vpn0wn.config.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(application = android.app.Application::class, sdk = [28], shadows = [TunnelStartupPolicyTest.KernelShadow::class])
@OptIn(ExperimentalCoroutinesApi::class)
class TunnelStartupPolicyTest {
    @get:Rule val folder = TemporaryFolder()

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun inventoryDoesNotActivateSavedProfilesWithBootRestoreDisabled() = inventoryOnly(false)

    @Test
    fun inventoryDoesNotActivateSavedProfilesWithBootRestoreEnabled() = inventoryOnly(true)

    private fun inventoryOnly(restoreOnBoot: Boolean) = runTest {
        val fixture = fixture(restoreOnBoot)
        try {
            fixture.release.countDown()
            fixture.manager.onCreate()
            fixture.loadingJob().join()
            val tunnel = fixture.manager.getTunnels()["one"]!!
            assertEquals(Tunnel.State.DOWN, tunnel.state)
            assertEquals(0, KernelShadow.upOperations.get())
            assertEquals(setOf("one"), UserKnobs.runningTunnels.first())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun coldBootWithDisabledPreferenceDoesNotActivateAfterDelayedInventory() = boot(false)

    @Test
    fun coldBootWithEnabledPreferenceRestoresOnceAfterDelayedInventory() = boot(true)

    private fun boot(restoreOnBoot: Boolean) = runTest {
        val fixture = fixture(restoreOnBoot)
        try {
            fixture.manager.onCreate()
            runCurrent()
            assertTrue(withContext(Dispatchers.IO) { fixture.enumerating.await(5, TimeUnit.SECONDS) })
            val existing = fixture.env.applicationScope.coroutineContext[Job]!!.children.toSet()
            fixture.env.application.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED).setClass(fixture.env.application, BootShutdownReceiver::class.java))
            shadowOf(Looper.getMainLooper()).idle()
            val receiving = fixture.env.applicationScope.coroutineContext[Job]!!.children.filter { it !in existing }.toList()
            assertEquals(1, receiving.size)
            runCurrent()
            assertEquals(0, KernelShadow.upOperations.get())
            fixture.release.countDown()
            for (job in receiving) job.join()
            fixture.loadingJob().join()
            val expected = if (restoreOnBoot) Tunnel.State.UP else Tunnel.State.DOWN
            assertEquals(expected, fixture.manager.getTunnels()["one"]!!.state)
            assertEquals(if (restoreOnBoot) 1 else 0, KernelShadow.upOperations.get())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun explicitForcedRestorationWaitsForInventoryEvenWithBootRestoreDisabled() = runTest {
        val fixture = fixture(false)
        try {
            fixture.manager.onCreate()
            runCurrent()
            assertTrue(withContext(Dispatchers.IO) { fixture.enumerating.await(5, TimeUnit.SECONDS) })
            val restoring = async { fixture.manager.restoreState(true) }
            runCurrent()
            assertFalse(restoring.isCompleted)
            fixture.release.countDown()
            restoring.await()
            assertEquals(Tunnel.State.UP, fixture.manager.getTunnels()["one"]!!.state)
            assertEquals(1, KernelShadow.upOperations.get())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun interactiveRestorationIgnoresBootPreferenceAndRunsOnlyOncePerManager() = runTest {
        val fixture = fixture(false)
        try {
            fixture.release.countDown()
            fixture.restoreFromActivity()
            val tunnel = fixture.manager.getTunnels()["one"]!!
            assertEquals(Tunnel.State.UP, tunnel.state)
            assertEquals(1, KernelShadow.upOperations.get())
            fixture.env.backend.setState(tunnel, Tunnel.State.DOWN, null)
            fixture.restoreFromActivity()
            assertEquals(Tunnel.State.DOWN, fixture.env.backend.getState(tunnel))
            assertEquals(1, KernelShadow.upOperations.get())
            assertEquals(setOf("one"), UserKnobs.runningTunnels.first())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun failedInteractiveInventorySettlesAndExplicitRestorationCanRetry() = runTest {
        val fixture = fixture(false)
        try {
            fixture.env.store.failEnumerate = true
            fixture.release.countDown()
            fixture.restoreFromActivity()
            assertEquals(0, KernelShadow.upOperations.get())
            fixture.env.store.failEnumerate = false
            fixture.manager.restoreState(true)
            assertEquals(Tunnel.State.UP, fixture.manager.getTunnels()["one"]!!.state)
            assertEquals(1, KernelShadow.upOperations.get())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancelledRestorationCannotActivateWhenSharedInventoryLaterCompletes() = runTest {
        val fixture = fixture(false)
        try {
            fixture.manager.onCreate()
            runCurrent()
            assertTrue(withContext(Dispatchers.IO) { fixture.enumerating.await(5, TimeUnit.SECONDS) })
            val restoring = async { fixture.manager.restoreState(true) }
            runCurrent()
            restoring.cancelAndJoin()
            fixture.release.countDown()
            fixture.loadingJob().join()
            assertEquals(Tunnel.State.DOWN, fixture.manager.getTunnels()["one"]!!.state)
            assertEquals(0, KernelShadow.upOperations.get())
        } finally {
            fixture.close()
        }
    }

    private suspend fun TestScope.fixture(restoreOnBoot: Boolean): StartupFixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.newFolder(), backgroundScope)
        env.store.configs["one"] = TunnelTestEnvironment.profile()
        com.vpn0wn.android.Application.getPreferencesDataStore().edit {
            it[booleanPreferencesKey("restore_on_boot")] = restoreOnBoot
            it[UserKnobs.RUNNING_TUNNELS] = setOf("one")
        }
        KernelShadow.backend = env.backend
        KernelShadow.upOperations.set(0)
        val shell = RootShell(env.application)
        env.replaceBackend(WgQuickBackend(env.application, shell, ToolsInstaller(env.application, shell)))
        return StartupFixture(env)
    }

    private class StartupFixture(val env: TunnelTestEnvironment) {
        val enumerating = CountDownLatch(1)
        val release = CountDownLatch(1)
        val manager = TunnelManager(object : com.vpn0wn.android.configStore.ConfigStore by env.store {
            override fun enumerate(): Set<String> {
                enumerating.countDown()
                if (!release.await(5, TimeUnit.SECONDS)) throw java.io.IOException("Inventory fixture timed out")
                return env.store.enumerate()
            }
        })

        init {
            com.vpn0wn.android.Application::class.java.getDeclaredField("tunnelManager")
                .apply { isAccessible = true }.set(env.application, manager)
        }

        fun loadingJob(): Job = TunnelManager::class.java.getDeclaredField("loadingJob")
            .apply { isAccessible = true }.get(manager) as Job

        suspend fun restoreFromActivity() {
            val existing = env.applicationScope.coroutineContext[Job]!!.children.toSet()
            manager.onActivityCreated()
            for (job in env.applicationScope.coroutineContext[Job]!!.children.filter { it !in existing }.toList())
                job.join()
        }

        suspend fun close() {
            release.countDown()
            env.close()
        }
    }

    @Implements(WgQuickBackend::class)
    class KernelShadow {
        @Implementation
        fun getRunningTunnelNames(): Set<String> = backend.getRunningTunnelNames()

        @Implementation
        fun getState(tunnel: Tunnel): Tunnel.State = backend.getState(tunnel)

        @Implementation
        fun setState(tunnel: Tunnel, state: Tunnel.State, config: Config?): Tunnel.State {
            if (state == Tunnel.State.UP) upOperations.incrementAndGet()
            return backend.setState(tunnel, state, config)
        }

        companion object {
            internal lateinit var backend: TunnelTestEnvironment.ControlledBackend
            val upOperations = AtomicInteger()
        }
    }
}
