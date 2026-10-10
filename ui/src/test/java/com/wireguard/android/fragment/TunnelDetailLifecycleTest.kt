package com.wireguard.android.fragment

import android.app.Application
import android.graphics.Canvas
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import androidx.databinding.BaseObservable
import androidx.databinding.Observable
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.R
import com.wireguard.android.BR
import com.wireguard.android.databinding.TunnelDetailFragmentBinding
import com.wireguard.android.databinding.TunnelDetailPeerBinding
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.model.TunnelTestEnvironment
import com.wireguard.android.util.QuantityFormatter
import com.wireguard.config.Config as TunnelConfig
import com.wireguard.crypto.Key
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class TunnelDetailLifecycleTest {
    @get:Rule val folder = TemporaryFolder()
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun slowerPreviousSelectionCannotReplaceCurrentConfig() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.root, backgroundScope)
        env.store.configs["one"] = TunnelTestEnvironment.profile(51820)
        env.store.configs["two"] = TunnelTestEnvironment.profile(51821)
        env.store.loadEntered["one"] = CountDownLatch(1)
        env.store.releaseLoad["one"] = CountDownLatch(1)
        val tunnels = env.manager.getTunnels()
        val controller = Robolectric.buildActivity(HostActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.AppTheme) }
        controller.setup()
        val fragment = TunnelDetailFragment()
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        val binding = DataBindingUtil.getBinding<TunnelDetailFragmentBinding>(fragment.requireView())!!
        try {
            fragment.onSelectedTunnelChanged(null, tunnels["one"])
            val first = configJob(fragment)
            assertTrue(withContext(Dispatchers.IO) { env.store.loadEntered["one"]!!.await(5, TimeUnit.SECONDS) })
            fragment.onSelectedTunnelChanged(tunnels["one"], tunnels["two"])
            configJob(fragment).join()
            assertEquals(51821, binding.config!!.getInterface().listenPort.get())
            env.store.releaseLoad["one"]!!.countDown()
            first.join()
            runCurrent()
            assertEquals(51821, binding.config!!.getInterface().listenPort.get())
            assertSame(tunnels["two"], binding.tunnel)
        } finally {
            env.store.releaseLoad["one"]!!.countDown()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun completionAfterViewDestructionCannotPopulateOldBinding() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.root, backgroundScope)
        env.store.configs["one"] = TunnelTestEnvironment.profile()
        env.store.loadEntered["one"] = CountDownLatch(1)
        env.store.releaseLoad["one"] = CountDownLatch(1)
        val tunnel = env.manager.getTunnels()["one"]!!
        val controller = Robolectric.buildActivity(HostActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.AppTheme) }
        controller.setup()
        val fragment = TunnelDetailFragment()
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        val binding = DataBindingUtil.getBinding<TunnelDetailFragmentBinding>(fragment.requireView())!!
        try {
            fragment.onSelectedTunnelChanged(null, tunnel)
            val loading = configJob(fragment)
            assertTrue(withContext(Dispatchers.IO) { env.store.loadEntered["one"]!!.await(5, TimeUnit.SECONDS) })
            activity.supportFragmentManager.beginTransaction().remove(fragment).commitNow()
            env.store.releaseLoad["one"]!!.countDown()
            loading.join()
            runCurrent()
            assertNull(fragment.view)
            assertNull(binding.config)
        } finally {
            env.store.releaseLoad["one"]!!.countDown()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun clearingSelectionCancelsPendingConfig() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.root, backgroundScope)
        env.store.configs["one"] = TunnelTestEnvironment.profile()
        env.store.loadEntered["one"] = CountDownLatch(1)
        env.store.releaseLoad["one"] = CountDownLatch(1)
        val tunnel = env.manager.getTunnels()["one"]!!
        val controller = Robolectric.buildActivity(HostActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.AppTheme) }
        controller.setup()
        val fragment = TunnelDetailFragment()
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        val binding = DataBindingUtil.getBinding<TunnelDetailFragmentBinding>(fragment.requireView())!!
        try {
            fragment.onSelectedTunnelChanged(null, tunnel)
            val loading = configJob(fragment)
            assertTrue(withContext(Dispatchers.IO) { env.store.loadEntered["one"]!!.await(5, TimeUnit.SECONDS) })
            fragment.onSelectedTunnelChanged(tunnel, null)
            env.store.releaseLoad["one"]!!.countDown()
            loading.join()
            runCurrent()
            assertNull(binding.tunnel)
            assertNull(binding.config)
        } finally {
            env.store.releaseLoad["one"]!!.countDown()
            controller.pause().stop().destroy()
        }
    }

    private fun configJob(fragment: TunnelDetailFragment): Job =
        TunnelDetailFragment::class.java.getDeclaredField("configJob").apply { isAccessible = true }.get(fragment) as Job

    @Test fun lateStatisticsFromPreviousSelectionCannotReplaceCurrentPeerCells() = runTest {
        val fixture = statsFixture()
        try {
            val pending = fixture.blockFirst(this)
            fixture.fragment.onSelectedTunnelChanged(fixture.first, fixture.second)
            configJob(fixture.fragment).join()
            fixture.binding.executePendingBindings()
            updateStats(fixture.fragment)
            val current = fixture.peer()
            current.executePendingBindings()
            assertEquals(fixture.second.getConfigAsync().peers.single().publicKey, current.item!!.publicKey)
            assertEquals(QuantityFormatter.formatBytes(900), current.rxText.text.toString())
            fixture.backend.release.countDown()
            pending.join()
            current.executePendingBindings()
            assertEquals(QuantityFormatter.formatBytes(900), current.rxText.text.toString())
            assertEquals(QuantityFormatter.formatBytes(800), current.txText.text.toString())
        } finally {
            fixture.close()
        }
    }

    @Test fun disconnectClearsPeerRowsWithoutWaitingForPendingStatistics() = runTest {
        val fixture = statsFixture()
        try {
            val pending = fixture.blockFirst(this)
            val calls = fixture.backend.calls.get()
            fixture.first.setStateAsync(Tunnel.State.DOWN)
            runCurrent()
            val peer = fixture.peer()
            peer.executePendingBindings()
            assertEquals(android.view.View.GONE, peer.transferGrid.visibility)
            assertEquals(android.view.View.GONE, peer.latestHandshakeRow.visibility)
            assertEquals(calls, fixture.backend.calls.get())
            fixture.backend.release.countDown()
            pending.join()
            peer.executePendingBindings()
            assertEquals(android.view.View.GONE, peer.transferGrid.visibility)
            assertEquals(QuantityFormatter.formatBytes(0), peer.rxText.text.toString())
        } finally {
            fixture.close()
        }
    }

    @Test fun statisticsAfterViewDestructionCannotChangeDetachedPeerCells() = runTest {
        val fixture = statsFixture()
        try {
            val old = fixture.peer()
            val previous = old.statistics
            val pending = fixture.blockFirst(this)
            fixture.controller.get().supportFragmentManager.beginTransaction().remove(fixture.fragment).commitNow()
            fixture.backend.release.countDown()
            pending.join()
            assertTrue(pending.isCancelled)
            assertNull(fixture.fragment.view)
            assertSame(previous, old.statistics)
        } finally {
            fixture.close()
        }
    }

    @Test fun statisticsFailureClearsCountersAndHandshakeTogether() = runTest {
        val fixture = statsFixture()
        try {
            fixture.backend.fail = true
            fixture.first.onStatisticsChanged(null)
            updateStats(fixture.fragment)
            val peer = fixture.peer()
            peer.executePendingBindings()
            assertNull(peer.statistics)
            assertEquals(android.view.View.GONE, peer.transferGrid.visibility)
            assertEquals(android.view.View.GONE, peer.latestHandshakeRow.visibility)
            assertEquals(QuantityFormatter.formatBytes(0), peer.txText.text.toString())
            assertEquals("", peer.latestHandshakeText.text.toString())
        } finally {
            fixture.close()
        }
    }

    @Test fun selectedConfigNotificationsReplaceAndClearBindingWithoutLoading() = runTest {
        val fixture = statsFixture()
        try {
            val loads = fixture.environment.store.loadAttempts.toList()
            val key = fixture.first.peekConfig()!!.peers.single().publicKey
            for (state in listOf(Tunnel.State.UP, Tunnel.State.DOWN)) {
                fixture.first.setStateAsync(state)
                for (config in listOf(profile(key, 1280), profile(key, 1380), profile(key, 0), profile(key), null)) {
                    fixture.first.onConfigChanged(config)
                    assertSame(config, fixture.binding.config)
                    fixture.binding.executePendingBindings()
                    val value = config?.`interface`?.mtu?.map { it.toString() }?.orElse("—") ?: "—"
                    assertEquals(listOf("MTU $value"), drawnMtu(fixture.binding))
                    assertEquals(loads, fixture.environment.store.loadAttempts.toList())
                }
            }
        } finally {
            fixture.close()
        }
    }

    @Test fun selectionRebindsTopologyMtuAndClearingSelectionRemovesPreviousValue() = runTest {
        val fixture = statsFixture()
        try {
            fixture.first.onConfigChanged(profile(fixture.first.peekConfig()!!.peers.single().publicKey, 1280))
            fixture.fragment.onSelectedTunnelChanged(fixture.first, fixture.first)
            configJob(fixture.fragment).join()
            fixture.binding.executePendingBindings()
            assertEquals(listOf("MTU 1280"), drawnMtu(fixture.binding))
            fixture.second.onConfigChanged(profile(fixture.second.peekConfig()!!.peers.single().publicKey, 1380))
            fixture.fragment.onSelectedTunnelChanged(fixture.first, fixture.second)
            configJob(fixture.fragment).join()
            fixture.binding.executePendingBindings()
            assertSame(fixture.second.peekConfig(), fixture.binding.config)
            assertEquals(listOf("MTU 1380"), drawnMtu(fixture.binding))
            fixture.fragment.onSelectedTunnelChanged(fixture.second, null)
            fixture.binding.executePendingBindings()
            assertNull(fixture.binding.config)
            assertNull(fixture.binding.tunnel)
            assertEquals(listOf("MTU —"), drawnMtu(fixture.binding))
        } finally {
            fixture.close()
        }
    }

    @Test fun staleAndUnrelatedCallbacksCannotChangeCurrentConfigOrRefreshStatistics() = runTest {
        val fixture = statsFixture()
        try {
            val callback = tunnelCallback(fixture.fragment)
            fixture.fragment.onSelectedTunnelChanged(fixture.first, fixture.second)
            configJob(fixture.fragment).join()
            fixture.binding.executePendingBindings()
            runCurrent()
            val current = fixture.binding.config
            val stale = profile(fixture.first.peekConfig()!!.peers.single().publicKey, 1280)
            fixture.first.onConfigChanged(stale)
            fixture.second.onStatisticsChanged(null)
            val calls = fixture.backend.calls.get()
            for (sender in listOf(fixture.first, BaseObservable(), null)) {
                callback.onPropertyChanged(sender, BR.config)
                callback.onPropertyChanged(sender, BR.state)
            }
            for (job in fixture.fragment.viewLifecycleOwner.lifecycleScope.coroutineContext[Job]!!.children.toList())
                job.join()
            runCurrent()
            assertSame(fixture.second, fixture.binding.tunnel)
            assertSame(current, fixture.binding.config)
            assertEquals(calls, fixture.backend.calls.get())
        } finally {
            fixture.close()
        }
    }

    @Test fun callbacksAfterViewDestructionCannotChangeDetachedBindingOrAccessViewLifecycle() = runTest {
        val fixture = statsFixture()
        try {
            val callback = tunnelCallback(fixture.fragment)
            val config = fixture.binding.config
            fixture.controller.get().supportFragmentManager.beginTransaction().remove(fixture.fragment).commitNow()
            val replacement = profile(fixture.first.peekConfig()!!.peers.single().publicKey, 1280)
            fixture.first.onConfigChanged(replacement)
            callback.onPropertyChanged(fixture.first, BR.config)
            callback.onPropertyChanged(fixture.first, BR.state)
            runCurrent()
            assertNull(fixture.fragment.view)
            assertSame(config, fixture.binding.config)
        } finally {
            fixture.close()
        }
    }

    private fun tunnelCallback(fragment: TunnelDetailFragment): Observable.OnPropertyChangedCallback =
        TunnelDetailFragment::class.java.getDeclaredField("tunnelCallback").apply { isAccessible = true }
            .get(fragment) as Observable.OnPropertyChangedCallback

    private fun drawnMtu(binding: TunnelDetailFragmentBinding): List<String> {
        binding.throughputView.layout(0, 0, TOPOLOGY_WIDTH, TOPOLOGY_HEIGHT)
        val canvas = Canvas()
        binding.throughputView.draw(canvas)
        val shadow = shadowOf(canvas)
        return (0 until shadow.textHistoryCount).map { shadow.getDrawnTextEvent(it).text }.filter { it.startsWith("MTU ") }
    }

    private suspend fun TestScope.statsFixture(): StatsFixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.root, backgroundScope)
        val backend = SnapshotBackend(env.backend)
        com.wireguard.android.Application::class.java.getDeclaredField("futureBackend").apply { isAccessible = true }
            .set(env.application, CompletableDeferred<Backend>(backend))
        val firstKey = Key.fromBytes(ByteArray(32) { 1 })
        val secondKey = Key.fromBytes(ByteArray(32) { 2 })
        val first = env.manager.create("one", profile(firstKey))
        val second = env.manager.create("two", profile(secondKey))
        first.setStateAsync(Tunnel.State.UP)
        second.setStateAsync(Tunnel.State.UP)
        backend.snapshots[first] = statistics(firstKey, 100, 200)
        backend.snapshots[second] = statistics(secondKey, 900, 800)
        first.onStatisticsChanged(backend.snapshots[first])
        second.onStatisticsChanged(backend.snapshots[second])
        val controller = Robolectric.buildActivity(HostActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
        val fragment = TunnelDetailFragment()
        controller.get().supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        TunnelDetailFragment::class.java.getDeclaredMethod("stopStatsRefresh").apply { isAccessible = true }.invoke(fragment)
        fragment.onSelectedTunnelChanged(null, first)
        configJob(fragment).join()
        val binding = DataBindingUtil.getBinding<TunnelDetailFragmentBinding>(fragment.requireView())!!
        binding.executePendingBindings()
        updateStats(fragment)
        val fixture = StatsFixture(env, backend, controller, fragment, binding, first, second)
        fixture.peer().executePendingBindings()
        assertEquals(android.view.View.VISIBLE, fixture.peer().transferGrid.visibility)
        return fixture
    }

    private suspend fun updateStats(fragment: TunnelDetailFragment): Unit = suspendCoroutine { continuation ->
        val result = TunnelDetailFragment::class.java.getDeclaredMethod("updateStats", Continuation::class.java)
            .apply { isAccessible = true }.invoke(fragment, continuation)
        if (result !== COROUTINE_SUSPENDED) continuation.resume(Unit)
    }

    private fun profile(key: Key, mtu: Int? = null): TunnelConfig = TunnelConfig.parse("""
        [Interface]
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        Address = 192.0.2.2/32
        ${mtu?.let { "MTU = $it" } ?: ""}
        [Peer]
        PublicKey = ${key.toBase64()}
        AllowedIPs = 0.0.0.0/0
    """.trimIndent().byteInputStream())

    private fun statistics(key: Key, rx: Long, tx: Long): Statistics {
        val snapshot = Statistics::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        Statistics::class.java.getDeclaredMethod("add", Key::class.java, Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(snapshot, key, rx, tx, System.currentTimeMillis() - 5000L)
        return snapshot
    }

    private inner class StatsFixture(
        val environment: TunnelTestEnvironment,
        val backend: SnapshotBackend,
        val controller: ActivityController<HostActivity>,
        val fragment: TunnelDetailFragment,
        val binding: TunnelDetailFragmentBinding,
        val first: ObservableTunnel,
        val second: ObservableTunnel
    ) {
        fun peer(): TunnelDetailPeerBinding = DataBindingUtil.getBinding(binding.peersLayout.getChildAt(0))!!

        suspend fun blockFirst(scope: TestScope): Job {
            backend.blocked = first
            first.onStatisticsChanged(null)
            val pending = fragment.viewLifecycleOwner.lifecycleScope.launch { updateStats(fragment) }
            scope.runCurrent()
            assertTrue(withContext(Dispatchers.IO) { backend.entered.await(5, TimeUnit.SECONDS) })
            return pending
        }

        fun close() {
            backend.release.countDown()
            controller.pause().stop().destroy()
        }
    }

    private class SnapshotBackend(delegate: Backend) : Backend by delegate {
        val snapshots = java.util.concurrent.ConcurrentHashMap<Tunnel, Statistics>()
        var blocked: Tunnel? = null
        var fail = false
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun getStatistics(tunnel: Tunnel): Statistics {
            calls.incrementAndGet()
            val snapshot = checkNotNull(snapshots[tunnel])
            if (blocked === tunnel) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Statistics fixture timed out" }
            }
            if (fail) throw java.io.IOException("Statistics fixture failed")
            return snapshot
        }
    }

    class HostActivity : AppCompatActivity()

    private companion object {
        const val TOPOLOGY_WIDTH = 272
        const val TOPOLOGY_HEIGHT = 206
    }
}
