package com.wireguard.android.fragment

import android.app.Application
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.wireguard.android.R
import com.wireguard.android.databinding.TunnelDetailFragmentBinding
import com.wireguard.android.model.TunnelTestEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
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

    class HostActivity : AppCompatActivity()
}
