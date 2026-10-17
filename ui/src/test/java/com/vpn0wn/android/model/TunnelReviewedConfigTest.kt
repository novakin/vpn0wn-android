/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.model

import android.app.Application
import com.vpn0wn.android.backend.Backend
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.config.Config
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
import org.robolectric.annotation.Config as RobolectricConfig

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class TunnelReviewedConfigTest {
    @get:Rule val folder = TemporaryFolder()

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.environment(): TunnelTestEnvironment {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return TunnelTestEnvironment(folder.root, backgroundScope)
    }

    @Test fun structuralReviewUpdatesOnlyTheCapturedStoppedTunnel() = runTest {
        val env = environment()
        try {
            val previous = TunnelTestEnvironment.profile()
            val tunnel = env.manager.create("one", previous)
            val other = env.manager.create("other", previous)
            val expected = Config.parse(previous.toWgQuickString().byteInputStream())
            val saved = TunnelTestEnvironment.profile(UPDATED_PORT)
            assertNotSame(previous, expected)
            assertEquals(previous, expected)

            assertSame(saved, env.manager.setTunnelConfigIfDown(tunnel, "one", expected, saved) {})

            assertSame(saved, tunnel.peekConfig())
            assertSame(saved, env.store.configs["one"])
            assertSame(saved, env.backend.appliedConfigs[tunnel])
            assertEquals(Tunnel.State.DOWN, tunnel.state)
            assertEquals(Tunnel.State.DOWN, env.backend.getState(tunnel))
            assertSame(previous, other.peekConfig())
            assertSame(previous, env.store.configs["other"])
            assertNull(env.backend.appliedConfigs[other])
        } finally { env.close() }
    }

    @Test fun equivalentUpdateStillObservesDownWithoutMutationOrInvalidation() = runTest {
        val env = environment()
        try {
            val previous = TunnelTestEnvironment.profile()
            val tunnel = env.manager.create("one", previous)
            val equivalent = Config.parse(previous.toWgQuickString().byteInputStream())
            val configGeneration = tunnel.configGeneration
            val statisticsGeneration = tunnel.statisticsGeneration
            env.store.failSave = true
            env.backend.failDown = true
            env.backend.blockStateRead = true
            var validated = false
            val updating = async { env.manager.setTunnelConfigIfDown(tunnel, "one", equivalent, equivalent) { validated = true } }
            try {
                runCurrent()
                assertTrue(withContext(Dispatchers.IO) { env.backend.stateReadEntered.await(5, TimeUnit.SECONDS) })
                assertFalse(updating.isCompleted)
                env.backend.blockStateRead = false
                env.backend.releaseStateRead.countDown()
                assertSame(previous, updating.await())
                assertTrue(validated)
                assertSame(previous, tunnel.peekConfig())
                assertSame(previous, env.store.configs["one"])
                assertNull(env.backend.appliedConfigs[tunnel])
                assertEquals(configGeneration, tunnel.configGeneration)
                assertEquals(statisticsGeneration, tunnel.statisticsGeneration)
            } finally {
                env.backend.releaseStateRead.countDown()
                updating.cancel()
                updating.join()
            }
        } finally { env.close() }
    }

    @Test fun actualUpAndToggleRejectCachedDownIncludingEquivalentRequests() = runTest {
        val env = environment()
        try {
            val previous = TunnelTestEnvironment.profile()
            val tunnel = env.manager.create("one", previous)
            val generation = tunnel.statisticsGeneration
            for (observed in listOf(Tunnel.State.UP, Tunnel.State.TOGGLE)) {
                env.replaceBackend(object : Backend by env.backend {
                    override fun getState(tunnel: Tunnel): Tunnel.State = observed
                })
                for (config in listOf(previous, TunnelTestEnvironment.profile(UPDATED_PORT))) {
                    assertRejected { env.manager.setTunnelConfigIfDown(tunnel, "one", previous, config) {} }
                }
            }
            assertEquals(Tunnel.State.DOWN, tunnel.state)
            assertSame(previous, tunnel.peekConfig())
            assertSame(previous, env.store.configs["one"])
            assertNull(env.backend.appliedConfigs[tunnel])
            assertEquals(generation, tunnel.statisticsGeneration)
        } finally { env.close() }
    }

    @Test fun cachedUpAndToggleRejectEvenWhenActualBackendIsDown() = runTest {
        val env = environment()
        try {
            val previous = TunnelTestEnvironment.profile()
            val tunnel = env.manager.create("one", previous)
            for (cached in listOf(Tunnel.State.UP, Tunnel.State.TOGGLE)) {
                tunnel.onStateChanged(cached)
                val generation = tunnel.statisticsGeneration
                assertEquals(Tunnel.State.DOWN, env.backend.getState(tunnel))
                assertRejected { env.manager.setTunnelConfigIfDown(tunnel, "one", previous, previous) {} }
                assertEquals(generation, tunnel.statisticsGeneration)
            }
            assertSame(previous, tunnel.peekConfig())
            assertSame(previous, env.store.configs["one"])
            assertNull(env.backend.appliedConfigs[tunnel])
        } finally { env.close() }
    }

    @Test fun failedStateObservationPreservesTheReviewedConfiguration() = runTest {
        val env = environment()
        try {
            val previous = TunnelTestEnvironment.profile()
            val tunnel = env.manager.create("one", previous)
            val generation = tunnel.statisticsGeneration
            val failure = IOException("Synthetic state observation failure")
            env.replaceBackend(object : Backend by env.backend {
                override fun getState(tunnel: Tunnel): Tunnel.State = throw failure
            })
            try {
                env.manager.setTunnelConfigIfDown(tunnel, "one", previous, TunnelTestEnvironment.profile(UPDATED_PORT)) {}
                fail("Expected state observation failure")
            } catch (e: IOException) { assertEquals(failure.message, e.message) }
            assertSame(previous, tunnel.peekConfig())
            assertSame(previous, env.store.configs["one"])
            assertNull(env.backend.appliedConfigs[tunnel])
            assertEquals(generation, tunnel.statisticsGeneration)
        } finally { env.close() }
    }

    @Test fun queuedEditRejectsTheEarlierConfigurationReview() = verifyQueued(QueuedChange.EDIT)

    @Test fun queuedUpRejectsTheStoppedReview() = verifyQueued(QueuedChange.UP)

    @Test fun queuedRenameRejectsTheDisplayedNameReview() = verifyQueued(QueuedChange.RENAME)

    @Test fun queuedDeleteRejectsTheCapturedObject() = verifyQueued(QueuedChange.DELETE)

    @Test fun queuedSameNameReplacementRejectsTheCapturedObject() = verifyQueued(QueuedChange.REPLACE)

    private fun verifyQueued(change: QueuedChange) = runTest {
        val env = environment()
        try {
            val previous = TunnelTestEnvironment.profile()
            val tunnel = env.manager.create("one", previous)
            val blocker = env.manager.create("blocker", previous)
            val current = TunnelTestEnvironment.profile(UPDATED_PORT)
            env.backend.blockUp = true
            val holding = async { blocker.setStateAsync(Tunnel.State.UP) }
            try {
                runCurrent()
                assertTrue(withContext(Dispatchers.IO) { env.backend.upEntered.await(5, TimeUnit.SECONDS) })
                val mutation = async {
                    when (change) {
                        QueuedChange.EDIT -> tunnel.setConfigAsync(current)
                        QueuedChange.UP -> tunnel.setStateAsync(Tunnel.State.UP)
                        QueuedChange.RENAME -> tunnel.setNameAsync("renamed")
                        QueuedChange.DELETE, QueuedChange.REPLACE -> env.manager.delete(tunnel)
                    }
                }
                val replacement = if (change == QueuedChange.REPLACE) async { env.manager.create("one", current) } else null
                val updating = async {
                    assertRejected {
                        env.manager.setTunnelConfigIfDown(tunnel, "one", previous, TunnelTestEnvironment.profile(STALE_PORT)) {}
                    }
                }
                runCurrent()
                assertFalse("Reviewed request waits for mutation ownership", updating.isCompleted)
                env.backend.releaseUp.countDown()
                holding.await()
                mutation.await()
                replacement?.await()
                updating.await()
                when (change) {
                    QueuedChange.EDIT -> {
                        assertSame(current, tunnel.peekConfig())
                        assertSame(current, env.store.configs["one"])
                        assertSame(current, env.backend.appliedConfigs[tunnel])
                    }
                    QueuedChange.UP -> {
                        assertSame(previous, env.store.configs["one"])
                        assertSame(previous, env.backend.appliedConfigs[tunnel])
                        assertEquals(Tunnel.State.UP, tunnel.state)
                    }
                    QueuedChange.RENAME -> {
                        assertSame(tunnel, env.manager.getTunnels()["renamed"])
                        assertSame(previous, env.store.configs["renamed"])
                        assertFalse(env.store.configs.containsKey("one"))
                    }
                    QueuedChange.DELETE -> {
                        assertNull(env.manager.getTunnels()["one"])
                        assertFalse(env.store.configs.containsKey("one"))
                    }
                    QueuedChange.REPLACE -> {
                        assertNotSame(tunnel, env.manager.getTunnels()["one"])
                        assertSame(current, env.store.configs["one"])
                        assertSame(current, env.manager.getTunnels()["one"]!!.peekConfig())
                    }
                }
            } finally {
                env.backend.releaseUp.countDown()
                holding.cancel()
                holding.join()
            }
        } finally { env.close() }
    }

    @Test fun failedSaveUsesTheExistingRollbackWithoutPublishingTheCandidate() = runTest {
        val env = environment()
        try {
            val previous = TunnelTestEnvironment.profile()
            val tunnel = env.manager.create("one", previous)
            env.store.failSave = true
            try {
                env.manager.setTunnelConfigIfDown(tunnel, "one", previous, TunnelTestEnvironment.profile(UPDATED_PORT)) {}
                fail("Expected save failure")
            } catch (_: IOException) { }
            assertSame(previous, tunnel.peekConfig())
            assertSame(previous, env.store.configs["one"])
            assertSame(previous, env.backend.appliedConfigs[tunnel])
            assertEquals(Tunnel.State.DOWN, tunnel.state)
            assertEquals(Tunnel.State.DOWN, env.backend.getState(tunnel))
        } finally { env.close() }
    }

    @Test fun cancelledAdmittedUpdatePublishesItsCommittedProfileBeforeCancellation() = runTest {
        val env = environment()
        try {
            val previous = TunnelTestEnvironment.profile()
            val saved = TunnelTestEnvironment.profile(UPDATED_PORT)
            val tunnel = env.manager.create("one", previous)
            env.backend.blockStateRead = true
            val updating = launch { env.manager.setTunnelConfigIfDown(tunnel, "one", previous, saved) {} }
            try {
                runCurrent()
                assertTrue(withContext(Dispatchers.IO) { env.backend.stateReadEntered.await(5, TimeUnit.SECONDS) })
                updating.cancel()
                env.backend.blockStateRead = false
                env.backend.releaseStateRead.countDown()
                updating.join()
                assertTrue(updating.isCancelled)
                assertSame(saved, tunnel.peekConfig())
                assertSame(saved, env.store.configs["one"])
                assertSame(saved, env.backend.appliedConfigs[tunnel])
                assertEquals(Tunnel.State.DOWN, tunnel.state)
            } finally {
                env.backend.releaseStateRead.countDown()
                updating.cancel()
                updating.join()
            }
        } finally { env.close() }
    }

    @Test fun cancellationBeforeAdmissionDoesNotChangeTheReviewedTunnel() = runTest {
        val env = environment()
        try {
            val previous = TunnelTestEnvironment.profile()
            val tunnel = env.manager.create("one", previous)
            val blocker = env.manager.create("blocker", previous)
            val generation = tunnel.statisticsGeneration
            env.backend.blockUp = true
            val holding = async { blocker.setStateAsync(Tunnel.State.UP) }
            try {
                runCurrent()
                assertTrue(withContext(Dispatchers.IO) { env.backend.upEntered.await(5, TimeUnit.SECONDS) })
                val updating = launch {
                    env.manager.setTunnelConfigIfDown(tunnel, "one", previous, TunnelTestEnvironment.profile(UPDATED_PORT)) {}
                }
                runCurrent()
                assertFalse(updating.isCompleted)
                updating.cancel()
                updating.join()
                env.backend.releaseUp.countDown()
                holding.await()
                assertTrue(updating.isCancelled)
                assertSame(previous, tunnel.peekConfig())
                assertSame(previous, env.store.configs["one"])
                assertNull(env.backend.appliedConfigs[tunnel])
                assertEquals(generation, tunnel.statisticsGeneration)
            } finally {
                env.backend.releaseUp.countDown()
                holding.cancel()
                holding.join()
            }
        } finally { env.close() }
    }

    @Test fun finalAdmissionGuardRunsAfterTheQueueAndRejectsWithoutMutation() = runTest {
        val env = environment()
        try {
            val previous = TunnelTestEnvironment.profile()
            val tunnel = env.manager.create("one", previous)
            val blocker = env.manager.create("blocker", previous)
            val generation = tunnel.statisticsGeneration
            env.backend.blockUp = true
            val holding = async { blocker.setStateAsync(Tunnel.State.UP) }
            var validated = false
            try {
                runCurrent()
                assertTrue(withContext(Dispatchers.IO) { env.backend.upEntered.await(5, TimeUnit.SECONDS) })
                val updating = async {
                    try {
                        env.manager.setTunnelConfigIfDown(tunnel, "one", previous, TunnelTestEnvironment.profile(UPDATED_PORT)) {
                            validated = true
                            throw IOException("Synthetic retired admission")
                        }
                        fail("Expected final admission rejection")
                    } catch (_: IOException) { }
                }
                runCurrent()
                assertFalse(validated)
                env.backend.releaseUp.countDown()
                holding.await()
                updating.await()
                assertTrue(validated)
                assertSame(previous, tunnel.peekConfig())
                assertSame(previous, env.store.configs["one"])
                assertNull(env.backend.appliedConfigs[tunnel])
                assertEquals(generation, tunnel.statisticsGeneration)
            } finally {
                env.backend.releaseUp.countDown()
                holding.cancel()
                holding.join()
            }
        } finally { env.close() }
    }

    @Test fun pendingBackendRestartHasAnExplicitRefusalWithoutWrites() = runTest {
        val env = environment()
        try {
            val previous = TunnelTestEnvironment.profile()
            val tunnel = env.manager.create("one", previous)
            env.manager.prepareBackendSwitch(true)
            var validated = false
            try {
                env.manager.setTunnelConfigIfDown(tunnel, "one", previous, TunnelTestEnvironment.profile(UPDATED_PORT)) { validated = true }
                fail("Expected pending backend restart refusal")
            } catch (_: TunnelManager.BackendSwitchPendingException) { }
            assertFalse(validated)
            assertSame(previous, tunnel.peekConfig())
            assertSame(previous, env.store.configs["one"])
            assertNull(env.backend.appliedConfigs[tunnel])
        } finally { env.close() }
    }

    private suspend fun assertRejected(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected stale or active tunnel rejection")
        } catch (_: TunnelManager.ConfigUpdateRejectedException) { }
    }

    private enum class QueuedChange { EDIT, UP, RENAME, DELETE, REPLACE }

    private companion object {
        const val UPDATED_PORT = 51821
        const val STALE_PORT = 51822
    }
}
