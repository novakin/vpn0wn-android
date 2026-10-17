/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android

import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Looper
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.vpn0wn.android.backend.Backend
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.backend.WgQuickBackend
import com.vpn0wn.android.configStore.ConfigStore
import com.vpn0wn.android.model.TunnelManager
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.android.util.RootShell
import com.vpn0wn.android.util.RootShell.RootShellException
import com.vpn0wn.android.util.ToolsInstaller
import com.vpn0wn.android.util.UserKnobs
import com.vpn0wn.config.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
@RobolectricConfig(application = android.app.Application::class, sdk = [28], shadows = [BootBroadcastLifetimeTest.KernelShadow::class])
@OptIn(ExperimentalCoroutinesApi::class)
class BootBroadcastLifetimeTest {
    @get:Rule val folder = TemporaryFolder()

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun bootRetainsBroadcastWhileBackendSelectionWaitsAndSettlesForUserspace() = runTest {
        withFixture { fixture ->
            val broadcast = fixture.dispatch(Intent.ACTION_BOOT_COMPLETED)
            runCurrent()
            assertFalse(broadcast.finished)
            fixture.backend.complete(fixture.env.backend)
            broadcast.job!!.join()
            assertTrue(broadcast.finished)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun bootRetainsBroadcastUntilDelayedInventoryAndRestorationSettle() = runTest {
        withFixture(blockInventory = true) { fixture ->
            fixture.env.store.configs["one"] = TunnelTestEnvironment.profile()
            Application.getPreferencesDataStore().edit {
                it[booleanPreferencesKey("restore_on_boot")] = true
                it[UserKnobs.RUNNING_TUNNELS] = setOf("one")
            }
            fixture.backend.complete(fixture.kernel)
            val broadcast = fixture.dispatch(Intent.ACTION_BOOT_COMPLETED)
            runCurrent()
            assertTrue(withContext(Dispatchers.IO) { fixture.enumerating.await(5, TimeUnit.SECONDS) })
            assertFalse(broadcast.finished)
            fixture.release.countDown()
            broadcast.job!!.join()
            assertTrue(broadcast.finished)
            assertEquals(Tunnel.State.UP, fixture.manager.getTunnels()["one"]!!.state)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun shutdownRetainsBroadcastUntilBackendSelectionAndPersistenceSettle() = runTest {
        withFixture { fixture ->
            fixture.env.replaceBackend(fixture.kernel)
            val profile = TunnelTestEnvironment.profile()
            val tunnel = fixture.manager.create("one", profile)
            tunnel.setStateAsync(Tunnel.State.UP)
            Application::class.java.getDeclaredField("futureBackend").apply { isAccessible = true }
                .set(fixture.env.application, fixture.backend)
            val broadcast = fixture.dispatch(Intent.ACTION_SHUTDOWN)
            runCurrent()
            assertFalse(broadcast.finished)
            fixture.backend.complete(fixture.kernel)
            broadcast.job!!.join()
            assertTrue(broadcast.finished)
            assertEquals(setOf("one"), UserKnobs.runningTunnels.first())
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun coldShutdownRetainsBroadcastAndSavedNamesUntilInventoryIsReady() = runTest {
        withFixture(blockInventory = true) { fixture ->
            fixture.env.store.configs["one"] = TunnelTestEnvironment.profile()
            UserKnobs.setRunningTunnels(setOf("previous"))
            KernelShadow.runningNames = setOf("one")
            fixture.backend.complete(fixture.kernel)
            val broadcast = fixture.dispatch(Intent.ACTION_SHUTDOWN)
            runCurrent()
            assertTrue(withContext(Dispatchers.IO) { fixture.enumerating.await(5, TimeUnit.SECONDS) })
            assertFalse(broadcast.finished)
            assertEquals(setOf("previous"), UserKnobs.runningTunnels.first())
            fixture.release.countDown()
            broadcast.job!!.join()
            assertTrue(broadcast.finished)
            assertEquals(setOf("one"), UserKnobs.runningTunnels.first())
            assertEquals(Tunnel.State.UP, fixture.manager.getTunnels()["one"]!!.state)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun failedColdShutdownInventoryPreservesSavedNamesAndFinishesBroadcast() = runTest {
        withFixture(blockInventory = true) { fixture ->
            fixture.env.store.configs["one"] = TunnelTestEnvironment.profile()
            fixture.env.store.failEnumerate = true
            UserKnobs.setRunningTunnels(setOf("previous"))
            KernelShadow.runningNames = setOf("one")
            fixture.backend.complete(fixture.kernel)
            val broadcast = fixture.dispatch(Intent.ACTION_SHUTDOWN)
            runCurrent()
            assertTrue(withContext(Dispatchers.IO) { fixture.enumerating.await(5, TimeUnit.SECONDS) })
            assertFalse(broadcast.finished)
            fixture.release.countDown()
            broadcast.job!!.join()
            assertTrue(broadcast.finished)
            assertEquals(setOf("previous"), UserKnobs.runningTunnels.first())
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun expectedBackendFailuresFinishBroadcastWithoutEscaping() = runTest {
        for (failure in listOf(IOException("Injected backend failure"), RootShellException(RootShellException.Reason.NO_ROOT_ACCESS))) {
            withFixture { fixture ->
                val broadcast = fixture.dispatch(Intent.ACTION_BOOT_COMPLETED)
                runCurrent()
                assertFalse(broadcast.finished)
                fixture.backend.completeExceptionally(failure)
                broadcast.job!!.join()
                assertTrue(broadcast.finished)
                assertTrue(fixture.failures.isEmpty())
            }
        }
    }

    @Test
    fun unexpectedErrorPropagatesAfterFinishingBroadcast() = runTest {
        withFixture { fixture ->
            val failure = AssertionError("Injected backend defect")
            val broadcast = fixture.dispatch(Intent.ACTION_BOOT_COMPLETED)
            runCurrent()
            fixture.backend.completeExceptionally(failure)
            broadcast.job!!.join()
            assertTrue(broadcast.finished)
            assertEquals(1, fixture.failures.size)
            val reported = fixture.failures.single()
            assertEquals(failure.javaClass, reported.javaClass)
            assertTrue(generateSequence<Throwable>(reported) { it.cause }.any { it === failure })
        }
    }

    @Test
    fun cancellationDuringBackendSelectionFinishesBroadcast() = runTest {
        withFixture { fixture ->
            val broadcast = fixture.dispatch(Intent.ACTION_BOOT_COMPLETED)
            runCurrent()
            assertFalse(broadcast.finished)
            broadcast.job!!.cancelAndJoin()
            assertTrue(broadcast.finished)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun cancellationDuringInventoryFinishesBroadcastBeforeSharedInventoryCompletes() = runTest {
        withFixture(blockInventory = true) { fixture ->
            fixture.env.store.configs["one"] = TunnelTestEnvironment.profile()
            Application.getPreferencesDataStore().edit {
                it[booleanPreferencesKey("restore_on_boot")] = true
                it[UserKnobs.RUNNING_TUNNELS] = setOf("one")
            }
            fixture.backend.complete(fixture.kernel)
            val broadcast = fixture.dispatch(Intent.ACTION_BOOT_COMPLETED)
            runCurrent()
            assertTrue(withContext(Dispatchers.IO) { fixture.enumerating.await(5, TimeUnit.SECONDS) })
            assertFalse(broadcast.finished)
            broadcast.job!!.cancelAndJoin()
            assertTrue(broadcast.finished)
            fixture.release.countDown()
            assertEquals(Tunnel.State.DOWN, fixture.manager.getTunnels()["one"]!!.state)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun alreadyCancelledApplicationScopeStillFinishesBroadcast() = runTest {
        withFixture { fixture ->
            fixture.env.applicationScope.coroutineContext[Job]!!.cancelAndJoin()
            val broadcast = fixture.dispatch(Intent.ACTION_BOOT_COMPLETED)
            runCurrent()
            assertTrue(broadcast.finished)
            assertFalse(fixture.backend.isCompleted)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun unrelatedActionDoesNotAcquireAsynchronousBroadcastOwnership() = runTest {
        withFixture { fixture ->
            val receiver = fixture.receiver()
            for (action in listOf(null, "com.vpn0wn.android.UNRELATED")) {
                fixture.env.application.sendBroadcast(Intent(action).setClass(fixture.env.application, BootShutdownReceiver::class.java))
                shadowOf(Looper.getMainLooper()).idle()
                runCurrent()
                assertFalse(shadowOf(receiver).wentAsync())
            }
            assertFalse(fixture.backend.isCompleted)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    private suspend fun TestScope.withFixture(blockInventory: Boolean = false, block: suspend TestScope.(Fixture) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val failures = mutableListOf<Throwable>()
        val handler = CoroutineExceptionHandler { _, failure -> failures += failure }
        val env = TunnelTestEnvironment(folder.newFolder(), CoroutineScope(backgroundScope.coroutineContext + handler))
        val fixture = Fixture(env, failures, blockInventory)
        try {
            block(fixture)
        } finally {
            fixture.release.countDown()
            env.close()
        }
    }

    private class Fixture(val env: TunnelTestEnvironment, val failures: List<Throwable>, blockInventory: Boolean) {
        val backend = CompletableDeferred<Backend>()
        val enumerating = CountDownLatch(1)
        val release = CountDownLatch(if (blockInventory) 1 else 0)
        val manager = TunnelManager(object : ConfigStore by env.store {
            override fun enumerate(): Set<String> {
                enumerating.countDown()
                if (!release.await(5, TimeUnit.SECONDS)) throw IOException("Inventory fixture timed out")
                return env.store.enumerate()
            }
        })
        val kernel: WgQuickBackend

        init {
            Application::class.java.getDeclaredField("futureBackend").apply { isAccessible = true }.set(env.application, backend)
            Application::class.java.getDeclaredField("tunnelManager").apply { isAccessible = true }.set(env.application, manager)
            val shell = RootShell(env.application)
            kernel = WgQuickBackend(env.application, shell, ToolsInstaller(env.application, shell))
            KernelShadow.backend = env.backend
            KernelShadow.runningNames = null
        }

        fun receiver(): BootShutdownReceiver = shadowOf(env.application)
            .getReceiversForIntent(Intent(Intent.ACTION_BOOT_COMPLETED)).filterIsInstance<BootShutdownReceiver>().single()

        fun dispatch(action: String): HeldBroadcast {
            val receiver = receiver()
            val previous = env.applicationScope.coroutineContext[Job]!!.children.toSet()
            env.application.sendBroadcast(Intent(action).setClass(env.application, BootShutdownReceiver::class.java))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(shadowOf(receiver).wentAsync())
            val result = checkNotNull(shadowOf(receiver).originalPendingResult)
            val job = env.applicationScope.coroutineContext[Job]!!.children.singleOrNull { it !in previous }
            return HeldBroadcast(result, job)
        }
    }

    private class HeldBroadcast(val result: BroadcastReceiver.PendingResult, val job: Job?) {
        val finished: Boolean get() = shadowOf(result).future.isDone
    }

    @Implements(WgQuickBackend::class)
    class KernelShadow {
        @Implementation
        fun getRunningTunnelNames(): Set<String> = runningNames ?: backend.getRunningTunnelNames()

        @Implementation
        fun getState(tunnel: Tunnel): Tunnel.State = backend.getState(tunnel)

        @Implementation
        fun setState(tunnel: Tunnel, state: Tunnel.State, config: Config?): Tunnel.State = backend.setState(tunnel, state, config)

        companion object {
            internal lateinit var backend: TunnelTestEnvironment.ControlledBackend
            internal var runningNames: Set<String>? = null
        }
    }
}
