package com.vpn0wn.android.model

import android.app.Application
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.configStore.ConfigStore
import com.vpn0wn.config.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class TunnelConfigPublicationTest {
    @get:Rule val folder = TemporaryFolder()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.fixture(): ReadFixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return ReadFixture(folder.root, backgroundScope)
    }

    @Test
    fun ordinaryUncachedReadPublishesStoredProfile() = runTest {
        val fixture = fixture()
        fixture.release.countDown()
        try {
            val tunnel = fixture.manager.getTunnels()["one"]!!
            assertSame(fixture.original, tunnel.getConfigAsync())
            assertSame(fixture.original, tunnel.peekConfig())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun capturedReadReturnsSavedProfileToLaterConnectAndExport() = runTest {
        val fixture = fixture()
        val tunnel = fixture.manager.getTunnels()["one"]!!
        val reading = async { tunnel.getConfigAsync() }
        try {
            runCurrent()
            fixture.awaitCapture()
            val saved = TunnelTestEnvironment.profile(51821)
            assertSame(saved, tunnel.setConfigAsync(saved))
            fixture.release.countDown()
            assertSame(saved, reading.await())
            assertSame(saved, tunnel.peekConfig())
            assertSame(saved, fixture.env.store.configs["one"])
            tunnel.setStateAsync(Tunnel.State.UP)
            assertSame(saved, fixture.env.backend.appliedConfigs[tunnel])
            assertSame(saved, fixture.manager.exportAppData().profiles["one"])
        } finally {
            fixture.release.countDown()
            reading.cancel()
            reading.join()
            fixture.close()
        }
    }

    @Test
    fun reviewedDownUpdateWinsOverAnEarlierCapturedRead() = runTest {
        val fixture = fixture()
        val tunnel = fixture.manager.getTunnels()["one"]!!
        val reading = async { tunnel.getConfigAsync() }
        try {
            runCurrent()
            fixture.awaitCapture()
            val expected = Config.parse(fixture.original.toWgQuickString().byteInputStream())
            val saved = TunnelTestEnvironment.profile(51821)
            assertSame(saved, fixture.manager.setTunnelConfigIfDown(tunnel, "one", expected, saved) {})
            fixture.release.countDown()
            assertSame(saved, reading.await())
            assertSame(saved, tunnel.peekConfig())
            assertSame(saved, fixture.env.store.configs["one"])
            assertSame(saved, fixture.manager.exportAppData().profiles["one"])
            assertEquals(Tunnel.State.DOWN, tunnel.state)
        } finally {
            fixture.release.countDown()
            reading.cancel()
            reading.join()
            fixture.close()
        }
    }

    @Test
    fun renamedOwnerReloadsItsCurrentNameBeforePublication() = runTest {
        val fixture = fixture()
        val tunnel = fixture.manager.getTunnels()["one"]!!
        val reading = async { tunnel.getConfigAsync() }
        try {
            runCurrent()
            fixture.awaitCapture()
            tunnel.setNameAsync("renamed")
            val current = TunnelTestEnvironment.profile(51821)
            fixture.env.store.configs["renamed"] = current
            fixture.release.countDown()
            assertSame(current, reading.await())
            assertSame(current, tunnel.peekConfig())
            assertEquals(listOf("one", "renamed"), fixture.env.store.loadAttempts)
        } finally {
            fixture.release.countDown()
            reading.cancel()
            reading.join()
            fixture.close()
        }
    }

    @Test
    fun renameAwayAndBackStillRejectsTheCapturedGeneration() = runTest {
        val fixture = fixture()
        val tunnel = fixture.manager.getTunnels()["one"]!!
        val reading = async { tunnel.getConfigAsync() }
        try {
            runCurrent()
            fixture.awaitCapture()
            tunnel.setNameAsync("renamed")
            tunnel.setNameAsync("one")
            val current = TunnelTestEnvironment.profile(51821)
            fixture.env.store.configs["one"] = current
            fixture.release.countDown()
            assertSame(current, reading.await())
            assertSame(current, tunnel.peekConfig())
            assertEquals(listOf("one", "one"), fixture.env.store.loadAttempts)
        } finally {
            fixture.release.countDown()
            reading.cancel()
            reading.join()
            fixture.close()
        }
    }

    @Test
    fun deletedOwnerCannotPublishAfterSameNameReplacement() = runTest {
        val fixture = fixture()
        val tunnel = fixture.manager.getTunnels()["one"]!!
        val reading = async {
            try {
                tunnel.getConfigAsync()
                false
            } catch (_: TunnelManager.NotManagedException) {
                true
            }
        }
        try {
            runCurrent()
            fixture.awaitCapture()
            tunnel.deleteAsync()
            val current = TunnelTestEnvironment.profile(51821)
            val replacement = fixture.manager.create("one", current)
            fixture.release.countDown()
            assertTrue(reading.await())
            assertNull(tunnel.peekConfig())
            assertSame(replacement, fixture.manager.getTunnels()["one"])
            assertSame(current, replacement.getConfigAsync())
            assertSame(current, fixture.env.store.configs["one"])
        } finally {
            fixture.release.countDown()
            reading.cancel()
            reading.join()
            fixture.close()
        }
    }

    @Test
    fun concurrentReadKeepsTheFirstPublishedCurrentCache() = runTest {
        val fixture = fixture()
        val tunnel = fixture.manager.getTunnels()["one"]!!
        val reading = async { tunnel.getConfigAsync() }
        try {
            runCurrent()
            fixture.awaitCapture()
            val equivalent = TunnelTestEnvironment.profile()
            fixture.env.store.configs["one"] = equivalent
            assertSame(equivalent, tunnel.getConfigAsync())
            fixture.release.countDown()
            assertSame(equivalent, reading.await())
            assertSame(equivalent, tunnel.peekConfig())
        } finally {
            fixture.release.countDown()
            reading.cancel()
            reading.join()
            fixture.close()
        }
    }

    @Test
    fun cancelledReadDoesNotPublishItsCapturedProfile() = runTest {
        val fixture = fixture()
        val tunnel = fixture.manager.getTunnels()["one"]!!
        val reading = launch { tunnel.getConfigAsync() }
        try {
            runCurrent()
            fixture.awaitCapture()
            reading.cancel()
            fixture.release.countDown()
            reading.join()
            assertTrue(reading.isCancelled)
            assertNull(tunnel.peekConfig())
            assertSame(fixture.original, tunnel.getConfigAsync())
        } finally {
            fixture.release.countDown()
            reading.cancel()
            reading.join()
            fixture.close()
        }
    }

    @Test
    fun failedSaveRetainsPreviousProfileAfterCapturedReadSettles() = runTest {
        val fixture = fixture()
        val tunnel = fixture.manager.getTunnels()["one"]!!
        val reading = async { tunnel.getConfigAsync() }
        try {
            runCurrent()
            fixture.awaitCapture()
            fixture.env.store.failSave = true
            try {
                tunnel.setConfigAsync(TunnelTestEnvironment.profile(51821))
                fail("Expected save failure")
            } catch (_: IOException) { }
            fixture.release.countDown()
            assertSame(fixture.original, reading.await())
            assertSame(fixture.original, tunnel.peekConfig())
            assertSame(fixture.original, fixture.env.store.configs["one"])
            assertSame(fixture.original, fixture.env.backend.appliedConfigs[tunnel])
        } finally {
            fixture.release.countDown()
            reading.cancel()
            reading.join()
            fixture.close()
        }
    }

    @Test
    fun cancelledSaveStillPublishesItsCommittedProfileBeforeCancellation() = runTest {
        val fixture = fixture()
        val tunnel = fixture.manager.getTunnels()["one"]!!
        val reading = async { tunnel.getConfigAsync() }
        try {
            runCurrent()
            fixture.awaitCapture()
            val saved = TunnelTestEnvironment.profile(51821)
            fixture.env.backend.blockStateRead = true
            val saving = launch { tunnel.setConfigAsync(saved) }
            try {
                runCurrent()
                assertTrue(withContext(Dispatchers.IO) {
                    fixture.env.backend.stateReadEntered.await(5, TimeUnit.SECONDS)
                })
                saving.cancel()
                fixture.env.backend.blockStateRead = false
                fixture.env.backend.releaseStateRead.countDown()
                saving.join()
                assertTrue(saving.isCancelled)
                fixture.release.countDown()
                assertSame(saved, reading.await())
                assertSame(saved, tunnel.peekConfig())
                assertSame(saved, fixture.env.store.configs["one"])
            } finally {
                fixture.env.backend.releaseStateRead.countDown()
                saving.cancel()
                saving.join()
            }
        } finally {
            fixture.release.countDown()
            reading.cancel()
            reading.join()
            fixture.close()
        }
    }

    private class ReadFixture(directory: File, scope: CoroutineScope) {
        val env = TunnelTestEnvironment(directory, scope)
        val original = TunnelTestEnvironment.profile()
        val captured = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val firstRead = AtomicBoolean(true)
        private val store = object : ConfigStore by env.store {
            override fun load(name: String): Config {
                val snapshot = env.store.load(name)
                if (firstRead.compareAndSet(true, false)) {
                    captured.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "Config read fixture timed out" }
                }
                return snapshot
            }
        }
        val manager = TunnelManager(store)

        init {
            env.store.configs["one"] = original
        }

        suspend fun awaitCapture() {
            assertTrue(withContext(Dispatchers.IO) { captured.await(5, TimeUnit.SECONDS) })
        }

        suspend fun close() {
            release.countDown()
            env.close()
        }
    }
}
