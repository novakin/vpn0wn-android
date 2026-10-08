package com.wireguard.android.model

import android.app.Application
import androidx.appcompat.app.AppCompatActivity
import com.wireguard.android.R
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.fragment.TunnelDetailFragment
import com.wireguard.crypto.Key
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class TunnelStatisticsTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var applicationScopeJob: Job

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun bindingGetterNeverStartsBackendReads() = runTest {
        val fixture = activeTunnel()
        val calls = AtomicInteger()
        fixture.env.backend.statisticsReader = { calls.incrementAndGet(); statistics(10) }
        repeat(20) { assertNull(fixture.tunnel.statistics) }
        runCurrent()
        assertEquals(0, calls.get())

        val snapshot = fixture.tunnel.getStatisticsAsync()
        assertNotNull(snapshot)
        ShadowSystemClock.advanceBy(Duration.ofMillis(901))
        repeat(20) { assertSame(snapshot, fixture.tunnel.statistics) }
        runCurrent()
        assertEquals(1, calls.get())
    }

    @Test
    fun overlappingExplicitReadersShareOneBackendSnapshot() = runTest {
        val fixture = activeTunnel()
        val calls = AtomicInteger()
        val read = BlockedRead(statistics(10))
        fixture.env.backend.statisticsReader = { calls.incrementAndGet(); read.read() }
        val first = async { fixture.tunnel.getStatisticsAsync() }
        val second = async { fixture.env.manager.getTunnelStatistics(fixture.tunnel) }
        val third = async { fixture.tunnel.getStatisticsAsync() }
        try {
            awaitEntered(read)
            repeat(20) { assertNull(fixture.tunnel.statistics) }
            runCurrent()
            assertEquals(1, calls.get())
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            read.release.countDown()
            assertSame(read.snapshot, first.await())
            assertSame(read.snapshot, second.await())
            assertSame(read.snapshot, third.await())
            assertSame(read.snapshot, fixture.tunnel.statistics)
        } finally {
            read.release.countDown()
        }
    }

    @Test
    fun freshCacheIsSharedAcrossEntryPointsAndRefetchedAfterNineHundredMilliseconds() = runTest {
        val fixture = activeTunnel()
        val calls = AtomicInteger()
        fixture.env.backend.statisticsReader = { statistics(calls.incrementAndGet().toLong()) }
        val first = fixture.tunnel.getStatisticsAsync()
        assertSame(first, fixture.env.manager.getTunnelStatistics(fixture.tunnel))
        ShadowSystemClock.advanceBy(Duration.ofMillis(900))
        assertFalse(first!!.isStale)
        assertSame(first, fixture.tunnel.getStatisticsAsync())
        assertEquals(1, calls.get())
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        assertTrue(first.isStale)
        val second = fixture.env.manager.getTunnelStatistics(fixture.tunnel)
        assertNotSame(first, second)
        assertEquals(2L, second!!.totalRx())
        assertEquals(2, calls.get())
    }

    @Test
    fun downAndTransitionModelsDoNotReadStatistics() = runTest {
        val env = environment()
        val tunnel = env.manager.create("one", TunnelTestEnvironment.profile())
        val calls = AtomicInteger()
        env.backend.statisticsReader = { calls.incrementAndGet(); statistics(10) }
        assertNull(tunnel.getStatisticsAsync())
        assertNull(env.manager.getTunnelStatistics(tunnel))
        tunnel.onTransitionStarted(Tunnel.State.UP)
        assertEquals(Tunnel.State.TOGGLE, tunnel.state)
        assertNull(tunnel.getStatisticsAsync())
        assertNull(env.manager.getTunnelStatistics(tunnel))
        assertEquals(0, calls.get())
    }

    @Test
    fun sameUpReconciliationKeepsFreshSnapshot() = runTest {
        val fixture = activeTunnel()
        val calls = AtomicInteger()
        fixture.env.backend.statisticsReader = { calls.incrementAndGet(); statistics(10) }
        val snapshot = fixture.tunnel.getStatisticsAsync()
        assertEquals(Tunnel.State.UP, fixture.env.manager.getTunnelState(fixture.tunnel))
        assertSame(snapshot, fixture.tunnel.getStatisticsAsync())
        assertEquals(1, calls.get())
    }

    @Test
    fun downUpInvalidationRejectsOldResultWithoutRemovingReplacementRead() = runTest {
        val fixture = activeTunnel()
        val calls = AtomicInteger()
        val old = BlockedRead(statistics(10), ignoreInterruption = true)
        val replacement = BlockedRead(statistics(20))
        fixture.env.backend.statisticsReader = {
            if (calls.incrementAndGet() == 1) old.read() else replacement.read()
        }
        val pending = async { fixture.tunnel.getStatisticsAsync() }
        try {
            awaitEntered(old)
            fixture.tunnel.setStateAsync(Tunnel.State.DOWN)
            fixture.tunnel.setStateAsync(Tunnel.State.UP)
            assertNull(fixture.tunnel.statistics)
            val current = async { fixture.tunnel.getStatisticsAsync() }
            awaitEntered(replacement)
            old.release.countDown()
            assertNull(pending.await())
            assertNull(fixture.tunnel.statistics)
            val joined = async { fixture.env.manager.getTunnelStatistics(fixture.tunnel) }
            runCurrent()
            assertEquals(2, calls.get())
            replacement.release.countDown()
            assertSame(replacement.snapshot, current.await())
            assertSame(replacement.snapshot, joined.await())
            assertSame(replacement.snapshot, fixture.tunnel.statistics)
        } finally {
            old.release.countDown()
            replacement.release.countDown()
        }
    }

    @Test
    fun transitionInvalidatesReadBeforeBlockedBackendStateChangeFinishes() = runTest {
        val fixture = activeTunnel()
        val read = BlockedRead(statistics(10), ignoreInterruption = true)
        fixture.env.backend.statisticsReader = { read.read() }
        val pending = async { fixture.tunnel.getStatisticsAsync() }
        try {
            awaitEntered(read)
            fixture.env.backend.blockDown = true
            val disconnect = async { fixture.tunnel.setStateAsync(Tunnel.State.DOWN) }
            assertTrue(withContext(Dispatchers.IO) { fixture.env.backend.downEntered.await(5, TimeUnit.SECONDS) })
            assertEquals(Tunnel.State.TOGGLE, fixture.tunnel.state)
            assertNull(fixture.tunnel.statistics)
            assertNull(fixture.tunnel.getStatisticsAsync())
            fixture.env.backend.releaseDown.countDown()
            assertEquals(Tunnel.State.DOWN, disconnect.await())
            read.release.countDown()
            assertNull(pending.await())
            assertNull(fixture.tunnel.statistics)
        } finally {
            fixture.env.backend.releaseDown.countDown()
            read.release.countDown()
        }
    }

    @Test
    fun configReplacementInvalidatesFreshSnapshotWhileTunnelStaysUp() = runTest {
        val fixture = activeTunnel()
        val calls = AtomicInteger()
        fixture.env.backend.statisticsReader = { statistics(calls.incrementAndGet().toLong()) }
        val previous = fixture.tunnel.getStatisticsAsync()
        fixture.tunnel.setConfigAsync(TunnelTestEnvironment.profile(51821))
        assertEquals(Tunnel.State.UP, fixture.tunnel.state)
        assertNull(fixture.tunnel.statistics)
        assertNotSame(previous, fixture.tunnel.getStatisticsAsync())
        assertEquals(2, calls.get())
    }

    @Test
    fun renameInvalidatesAdmittedReadForSameModelIdentity() = runTest {
        val fixture = activeTunnel()
        val read = BlockedRead(statistics(10), ignoreInterruption = true)
        fixture.env.backend.statisticsReader = { read.read() }
        val pending = async { fixture.tunnel.getStatisticsAsync() }
        try {
            awaitEntered(read)
            assertEquals("renamed", fixture.tunnel.setNameAsync("renamed"))
            assertSame(fixture.tunnel, fixture.env.manager.getTunnels()["renamed"])
            read.release.countDown()
            assertNull(pending.await())
            assertNull(fixture.tunnel.statistics)
            val replacement = statistics(20)
            fixture.env.backend.statisticsReader = { replacement }
            assertSame(replacement, fixture.tunnel.getStatisticsAsync())
        } finally {
            read.release.countDown()
        }
    }

    @Test
    fun deletedModelCannotPublishIntoSameNameReplacement() = runTest {
        val fixture = activeTunnel()
        val read = BlockedRead(statistics(10), ignoreInterruption = true)
        fixture.env.backend.statisticsReader = { read.read() }
        val pending = async { fixture.tunnel.getStatisticsAsync() }
        try {
            awaitEntered(read)
            fixture.env.manager.delete(fixture.tunnel)
            val replacement = fixture.env.manager.create("one", TunnelTestEnvironment.profile())
            replacement.setStateAsync(Tunnel.State.UP)
            read.release.countDown()
            assertNull(pending.await())
            assertNull(replacement.statistics)
            val rejected = runCatching { fixture.tunnel.getStatisticsAsync() }.exceptionOrNull()
            assertTrue(rejected is TunnelManager.NotManagedException)
            val snapshot = statistics(20)
            fixture.env.backend.statisticsReader = { snapshot }
            assertSame(snapshot, replacement.getStatisticsAsync())
        } finally {
            read.release.countDown()
        }
    }

    @Test
    fun backendReplacementRejectsOldReadAndUsesNewBackendSnapshot() = runTest {
        val fixture = activeTunnel()
        val old = BlockedRead(statistics(10), ignoreInterruption = true)
        fixture.env.backend.statisticsReader = { old.read() }
        val pending = async { fixture.tunnel.getStatisticsAsync() }
        try {
            awaitEntered(old)
            val snapshot = statistics(20)
            val calls = AtomicInteger()
            fixture.env.replaceBackend(object : Backend by fixture.env.backend {
                override fun getStatistics(tunnel: Tunnel): Statistics {
                    calls.incrementAndGet()
                    return snapshot
                }
            })
            assertSame(snapshot, fixture.tunnel.getStatisticsAsync())
            old.release.countDown()
            assertNull(pending.await())
            assertSame(snapshot, fixture.tunnel.statistics)
            assertSame(snapshot, fixture.env.manager.getTunnelStatistics(fixture.tunnel))
            assertEquals(1, calls.get())
        } finally {
            old.release.countDown()
        }
    }

    @Test
    fun backendIdentityChangeInvalidatesFreshCachedSnapshot() = runTest {
        val fixture = activeTunnel()
        val original = statistics(10)
        fixture.env.backend.statisticsReader = { original }
        assertSame(original, fixture.tunnel.getStatisticsAsync())
        val replacement = statistics(20)
        fixture.env.replaceBackend(object : Backend by fixture.env.backend {
            override fun getStatistics(tunnel: Tunnel): Statistics = replacement
        })
        assertFalse(original.isStale)
        assertSame(replacement, fixture.tunnel.getStatisticsAsync())
        assertSame(replacement, fixture.tunnel.statistics)
    }

    @Test
    fun backendSwitchInvalidatesStatisticsBeforeItsFirstBlockedStateLookup() = runTest {
        val fixture = activeTunnel()
        val second = fixture.env.manager.create("two", TunnelTestEnvironment.profile(51821))
        second.setStateAsync(Tunnel.State.UP)
        fixture.env.backend.statisticsReader = { statistics(10) }
        fixture.tunnel.getStatisticsAsync()
        second.getStatisticsAsync()
        ShadowSystemClock.advanceBy(Duration.ofMillis(901))
        val calls = AtomicInteger()
        val read = BlockedRead(statistics(20), ignoreInterruption = true)
        fixture.env.backend.statisticsReader = { calls.incrementAndGet(); read.read() }
        val pending = async { fixture.tunnel.getStatisticsAsync() }
        try {
            awaitEntered(read)
            fixture.env.backend.blockStateRead = true
            val switching = async { fixture.env.manager.prepareBackendSwitch(true) }
            assertTrue(withContext(Dispatchers.IO) {
                fixture.env.backend.stateReadEntered.await(5, TimeUnit.SECONDS)
            })
            assertNull(fixture.tunnel.statistics)
            assertNull(second.statistics)
            assertFalse(switching.isCompleted)
            read.release.countDown()
            assertNull(pending.await())
            fixture.env.backend.blockStateRead = false
            fixture.env.backend.releaseStateRead.countDown()
            switching.await()
            assertNull(fixture.tunnel.getStatisticsAsync())
            assertNull(second.getStatisticsAsync())
            assertNull(fixture.env.manager.getTunnelStatistics(fixture.tunnel))
            assertEquals(1, calls.get())
        } finally {
            read.release.countDown()
            fixture.env.backend.releaseStateRead.countDown()
        }
    }

    @Test
    fun cancellingOneReaderKeepsSharedReadAliveForOtherReader() = runTest {
        val fixture = activeTunnel()
        val calls = AtomicInteger()
        val read = BlockedRead(statistics(10))
        fixture.env.backend.statisticsReader = { calls.incrementAndGet(); read.read() }
        val first = async { fixture.tunnel.getStatisticsAsync() }
        val second = async { fixture.tunnel.getStatisticsAsync() }
        try {
            awaitEntered(read)
            first.cancel()
            first.join()
            assertTrue(first.isCancelled)
            assertEquals(1L, read.interrupted.count)
            assertFalse(second.isCompleted)
            read.release.countDown()
            assertSame(read.snapshot, second.await())
            assertEquals(1, calls.get())
        } finally {
            read.release.countDown()
        }
    }

    @Test
    fun cancellingAllReadersInterruptsBackendAndNextReadRetries() = runTest {
        val fixture = activeTunnel()
        val calls = AtomicInteger()
        val read = BlockedRead(statistics(10))
        fixture.env.backend.statisticsReader = { calls.incrementAndGet(); read.read() }
        val first = async { fixture.tunnel.getStatisticsAsync() }
        val second = async { fixture.tunnel.getStatisticsAsync() }
        try {
            awaitEntered(read)
            first.cancel()
            second.cancel()
            first.join()
            second.join()
            assertTrue(withContext(Dispatchers.IO) { read.interrupted.await(5, TimeUnit.SECONDS) })
            assertNull(fixture.tunnel.statistics)
            val snapshot = statistics(20)
            fixture.env.backend.statisticsReader = { calls.incrementAndGet(); snapshot }
            assertSame(snapshot, fixture.tunnel.getStatisticsAsync())
            assertEquals(2, calls.get())
        } finally {
            read.release.countDown()
        }
    }

    @Test
    fun backendFailuresAndCancellationPropagateAndAllowRetry() = runTest {
        val fixture = activeTunnel()
        val calls = AtomicInteger()
        val failures = listOf(IOException("Injected statistics I/O failure"),
            IllegalStateException("Injected statistics defect"), AssertionError("Injected statistics error"),
            CancellationException("Injected backend cancellation"))
        for (failure in failures) {
            fixture.env.backend.statisticsReader = { calls.incrementAndGet(); throw failure }
            val result = async { runCatching { fixture.tunnel.getStatisticsAsync() } }.await()
            val observed = result.exceptionOrNull()
            assertTrue("Expected original ${failure.javaClass.simpleName}", observed === failure || observed?.cause === failure)
            assertNull(fixture.tunnel.statistics)
            val snapshot = statistics(calls.get().toLong())
            fixture.env.backend.statisticsReader = { calls.incrementAndGet(); snapshot }
            assertSame(snapshot, fixture.tunnel.getStatisticsAsync())
            fixture.tunnel.onStateChanged(Tunnel.State.DOWN)
            fixture.tunnel.onStateChanged(Tunnel.State.UP)
        }
        assertEquals(failures.size * 2, calls.get())
    }

    @Test
    fun completedBackendFailuresStayFailuresWhenSessionChangesBeforeCallerResumes() = runTest {
        val fixture = activeTunnel()
        val failures = listOf(IOException("Completed statistics I/O failure"),
            CancellationException("Completed backend cancellation"))
        for (failure in failures) {
            runCurrent()
            val applicationJob = applicationScopeJob
            val previousJobs = applicationJob.children.toSet()
            val read = BlockedRead(statistics(10))
            fixture.env.backend.statisticsReader = { read.read(); throw failure }
            val pending = async { runCatching { fixture.tunnel.getStatisticsAsync() } }
            try {
                awaitEntered(read)
                val readJobs = applicationJob.children.filter { it !in previousJobs }.toList()
                assertTrue("No application read job was admitted", readJobs.isNotEmpty())
                val completed = CountDownLatch(readJobs.size)
                for (job in readJobs) job.invokeOnCompletion { completed.countDown() }
                read.release.countDown()
                assertTrue(completed.await(5, TimeUnit.SECONDS))
                fixture.tunnel.onStateChanged(Tunnel.State.DOWN)
                fixture.tunnel.onStateChanged(Tunnel.State.UP)
                val observed = pending.await().exceptionOrNull()
                assertTrue("Expected original ${failure.javaClass.simpleName}", observed === failure || observed?.cause === failure)
                assertNull(fixture.tunnel.statistics)
                val snapshot = statistics(20)
                fixture.env.backend.statisticsReader = { snapshot }
                assertSame(snapshot, fixture.tunnel.getStatisticsAsync())
                fixture.tunnel.onStateChanged(Tunnel.State.DOWN)
                fixture.tunnel.onStateChanged(Tunnel.State.UP)
            } finally {
                read.release.countDown()
            }
        }
    }

    @Test
    fun applicationCancellationIsNotReportedAsObsoleteData() = runTest {
        val fixture = activeTunnel()
        val read = BlockedRead(statistics(10))
        fixture.env.backend.statisticsReader = { read.read() }
        val pending = async { runCatching { fixture.tunnel.getStatisticsAsync() } }
        try {
            awaitEntered(read)
            backgroundScope.cancel()
            assertTrue(pending.await().exceptionOrNull() is CancellationException)
            assertNull(fixture.tunnel.statistics)
        } finally {
            read.release.countDown()
        }
    }

    @Test
    fun callerCancellationBeforeQueuedPublicationLeavesCacheEmpty() = runTest {
        val fixture = activeTunnel()
        val read = BlockedRead(statistics(10))
        fixture.env.backend.statisticsReader = { read.read() }
        val pending = async { fixture.tunnel.getStatisticsAsync() }
        try {
            awaitEntered(read)
            read.release.countDown()
            assertTrue(read.finished.await(5, TimeUnit.SECONDS))
            pending.cancel()
            pending.join()
            runCurrent()
            assertTrue(pending.isCancelled)
            assertNull(fixture.tunnel.statistics)
        } finally {
            read.release.countDown()
        }
    }

    @Test
    fun callerCancellationDuringSessionInvalidationRemainsCancellation() = runTest {
        val fixture = activeTunnel()
        val read = BlockedRead(statistics(10), ignoreInterruption = true)
        fixture.env.backend.statisticsReader = { read.read() }
        val pending = async { fixture.tunnel.getStatisticsAsync() }
        try {
            awaitEntered(read)
            pending.cancel()
            fixture.tunnel.setStateAsync(Tunnel.State.DOWN)
            read.release.countDown()
            pending.join()
            assertTrue(pending.isCancelled)
            assertNull(fixture.tunnel.statistics)
        } finally {
            read.release.countDown()
        }
    }

    @Test
    fun workerDownUpNotificationsInvalidateResultBeforeQueuedMainPublication() = runTest {
        val fixture = activeTunnel()
        fixture.env.backend.statisticsReader = { statistics(10) }
        fixture.tunnel.getStatisticsAsync()
        ShadowSystemClock.advanceBy(Duration.ofMillis(901))
        val read = BlockedRead(statistics(20))
        fixture.env.backend.statisticsReader = { read.read() }
        val previousJobs = applicationScopeJob.children.toSet()
        val pending = async { fixture.tunnel.getStatisticsAsync() }
        val worker = Executors.newSingleThreadExecutor()
        try {
            awaitEntered(read)
            val readJobs = applicationScopeJob.children.filter { it !in previousJobs }.toList()
            assertTrue("No application read job was admitted", readJobs.isNotEmpty())
            val completed = CountDownLatch(readJobs.size)
            for (job in readJobs) job.invokeOnCompletion { completed.countDown() }
            read.release.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            worker.submit {
                fixture.env.backend.setState(fixture.tunnel, Tunnel.State.DOWN, null)
                fixture.env.backend.setState(fixture.tunnel, Tunnel.State.UP, fixture.tunnel.peekConfig())
            }.get(5, TimeUnit.SECONDS)
            assertEquals(Tunnel.State.UP, fixture.tunnel.state)
            assertNull(fixture.tunnel.statistics)
            assertNull(pending.await())
            val snapshot = statistics(30)
            fixture.env.backend.statisticsReader = { snapshot }
            assertSame(snapshot, fixture.tunnel.getStatisticsAsync())
            assertSame(snapshot, fixture.tunnel.statistics)
        } finally {
            read.release.countDown()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun queuedReaderAdmittedAfterWorkerEpochChangeSurvivesOlderNotificationCleanup() = runTest {
        val fixture = activeTunnel()
        fixture.env.backend.statisticsReader = { statistics(10) }
        fixture.tunnel.getStatisticsAsync()
        runCurrent()
        val read = BlockedRead(statistics(20))
        fixture.env.backend.statisticsReader = { read.read() }
        val previousJobs = applicationScopeJob.children.toSet()
        val pending = async(Dispatchers.Main.immediate) { fixture.tunnel.getStatisticsAsync() }
        val worker = Executors.newSingleThreadExecutor()
        try {
            worker.submit {
                fixture.env.backend.setState(fixture.tunnel, Tunnel.State.DOWN, null)
                fixture.env.backend.setState(fixture.tunnel, Tunnel.State.UP, fixture.tunnel.peekConfig())
            }.get(5, TimeUnit.SECONDS)
            val notifications = applicationScopeJob.children.filter { it !in previousJobs }.toList()
            awaitEntered(read)
            for (notification in notifications) notification.join()
            assertFalse(pending.isCompleted)
            assertEquals(1L, read.interrupted.count)
            read.release.countDown()
            assertSame(read.snapshot, pending.await())
            assertSame(read.snapshot, fixture.tunnel.statistics)
        } finally {
            read.release.countDown()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun stillResumedDetailPollContinuesAfterConfigInvalidatesRead() = runTest {
        val fixture = activeTunnel()
        fixture.env.backend.statisticsReader = { statistics(10) }
        fixture.tunnel.getStatisticsAsync()
        val controller = Robolectric.buildActivity(HostActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
        val fragment = TunnelDetailFragment()
        controller.get().supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        fragment.onSelectedTunnelChanged(null, fixture.tunnel)
        runCurrent()
        val polling = TunnelDetailFragment::class.java.getDeclaredField("statsJob").apply { isAccessible = true }
            .get(fragment) as Job
        val blocked = BlockedRead(statistics(20))
        fixture.env.backend.statisticsReader = { blocked.read() }
        try {
            ShadowSystemClock.advanceBy(Duration.ofMillis(901))
            advanceTimeBy(1000)
            awaitEntered(blocked)
            val obsolete = async { fixture.tunnel.getStatisticsAsync() }
            runCurrent()
            val nextRead = CountDownLatch(1)
            val snapshot = statistics(30)
            fixture.env.backend.statisticsReader = { nextRead.countDown(); snapshot }
            fixture.tunnel.setConfigAsync(TunnelTestEnvironment.profile(51821))
            assertNull(obsolete.await())
            runCurrent()
            assertTrue(fragment.isResumed)
            assertTrue(polling.isActive)
            advanceTimeBy(1000)
            runCurrent()
            assertTrue(withContext(Dispatchers.IO) { nextRead.await(5, TimeUnit.SECONDS) })
            assertSame(snapshot, fixture.tunnel.getStatisticsAsync())
            assertTrue(polling.isActive)
        } finally {
            blocked.release.countDown()
            controller.pause().stop().destroy()
        }
    }

    private fun TestScope.environment(): TunnelTestEnvironment {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val environment = TunnelTestEnvironment(folder.root, backgroundScope)
        applicationScopeJob = environment.applicationScope.coroutineContext[Job]!!
        return environment
    }

    private suspend fun TestScope.activeTunnel(): Fixture {
        val env = environment()
        val tunnel = env.manager.create("one", TunnelTestEnvironment.profile())
        tunnel.setStateAsync(Tunnel.State.UP)
        return Fixture(env, tunnel)
    }

    private suspend fun TestScope.awaitEntered(read: BlockedRead) {
        runCurrent()
        assertTrue("Statistics backend was not entered", withContext(Dispatchers.IO) {
            read.entered.await(5, TimeUnit.SECONDS)
        })
    }

    private fun statistics(rx: Long): Statistics {
        val snapshot = Statistics::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        Statistics::class.java.getDeclaredMethod("add", Key::class.java, Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(snapshot, Key.fromBytes(ByteArray(32) { 1 }), rx, rx * 2, 0L)
        return snapshot
    }

    private data class Fixture(val env: TunnelTestEnvironment, val tunnel: ObservableTunnel)

    private class BlockedRead(val snapshot: Statistics, private val ignoreInterruption: Boolean = false) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val finished = CountDownLatch(1)

        fun read(): Statistics {
            entered.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            try {
                while (true) {
                    try {
                        check(release.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) {
                            "Statistics fixture timed out"
                        }
                        return snapshot
                    } catch (failure: InterruptedException) {
                        interrupted.countDown()
                        if (!ignoreInterruption) throw failure
                    }
                }
            } finally {
                finished.countDown()
            }
        }
    }

    class HostActivity : AppCompatActivity()
}
