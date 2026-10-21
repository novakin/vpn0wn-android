/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.activity

import android.app.Application
import android.os.Bundle
import android.os.Looper
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Lifecycle
import com.vpn0wn.android.R
import com.vpn0wn.android.backend.Backend
import com.vpn0wn.android.databinding.TunnelDetailFragmentBinding
import com.vpn0wn.android.databinding.TunnelEditorFragmentBinding
import com.vpn0wn.android.fragment.TunnelDetailFragment
import com.vpn0wn.android.fragment.TunnelEditorFragment
import com.vpn0wn.android.model.ObservableTunnel
import com.vpn0wn.android.model.TunnelManager
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.android.util.RootShell.RootShellException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class BaseActivitySelectionTest {
    @get:Rule val folder = TemporaryFolder()

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun delayedDetailReceivesResolvedManagedIdentityOnce() = runTest {
        verifyRestoration(editor = false, loaded = false)
    }

    @Test fun loadedDetailReceivesSelectionAfterPostCreate() = runTest {
        verifyRestoration(editor = false, loaded = true)
    }

    @Test fun delayedEditorRetainsDraftAndBackStackWhenIdentityArrives() = runTest {
        verifyRestoration(editor = true, loaded = false)
    }

    @Test fun loadedEditorRetainsDraftAndBackStackAfterPostCreate() = runTest {
        verifyRestoration(editor = true, loaded = true)
    }

    @Test fun missingSavedTunnelLeavesRestoredDetailUnselected() = runTest {
        verifyUnavailable(editor = false, failure = null)
    }

    @Test fun missingSavedTunnelPreservesEditorDraftWithoutInventingIdentity() = runTest {
        verifyUnavailable(editor = true, failure = null)
    }

    @Test fun unavailableInventoryPreservesAttachedDetailAndReportsFailure() = runTest {
        for (failure in inventoryFailures()) verifyUnavailable(editor = false, failure)
    }

    @Test fun unavailableInventoryPreservesEditorDraftAndReportsFailure() = runTest {
        for (failure in inventoryFailures()) verifyUnavailable(editor = true, failure)
    }

    @Test fun explicitDeselectionCancelsPendingSavedSelection() = runTest {
        val saved = snapshot(editor = false)
        val env = environment()
        val controller = controller()
        val notifications = observe(controller.get())
        try {
            controller.create(saved).start().postCreate(saved).resume()
            assertNull(controller.get().selectedTunnel)
            controller.get().selectedTunnel = null
            settle(env)
            assertNull(controller.get().selectedTunnel)
            assertTrue(notifications.isEmpty())
            assertNull(detailBinding(controller).tunnel)
        } finally {
            controller.pause().stop().destroy()
            env.close()
        }
    }

    @Test fun destroyedActivityDoesNotPublishPendingSelection() = runTest {
        val saved = snapshot(editor = false)
        val env = environment()
        val controller = controller()
        val notifications = observe(controller.get())
        val inventoryEntered = CountDownLatch(1)
        val releaseInventory = CountDownLatch(1)
        env.replaceBackend(object : Backend by env.backend {
            override fun getRunningTunnelNames(): Set<String> {
                inventoryEntered.countDown()
                check(releaseInventory.await(5, TimeUnit.SECONDS)) { "Inventory fixture timed out" }
                return emptySet()
            }
        })
        try {
            controller.create(saved).start().postCreate(saved).resume()
            runCurrent()
            assertTrue(withContext(Dispatchers.IO) { inventoryEntered.await(5, TimeUnit.SECONDS) })
            assertTrue(notifications.isEmpty())
            assertNull(controller.get().selectedTunnel)
            controller.pause().stop().destroy()
            assertEquals(Lifecycle.State.DESTROYED, controller.get().lifecycle.currentState)
            releaseInventory.countDown()
            settle(env)
            assertTrue(notifications.isEmpty())
            assertNull(controller.get().selectedTunnel)
        } finally {
            releaseInventory.countDown()
            if (!controller.get().isDestroyed) controller.pause().stop().destroy()
            env.close()
        }
    }

    @Test fun secondRecreationDuringInventoryWaitPreservesEditorDraftAndSavedIdentity() = runTest {
        val saved = snapshot(editor = true)
        val env = environment()
        val first = controller()
        val second = controller()
        try {
            first.create(saved).start().postCreate(saved).resume()
            assertNull(first.get().selectedTunnel)
            assertDraft(editorBinding(first))
            val pending = Bundle()
            first.saveInstanceState(pending).pause().stop().destroy()
            assertEquals("one", pending.getString("selected_tunnel"))
            second.create(pending).start().postCreate(pending).resume()
            settle(env)
            assertSame(env.manager.getTunnels()["one"], second.get().selectedTunnel)
            assertDraft(editorBinding(second))
            assertEquals(2, second.get().supportFragmentManager.backStackEntryCount)
        } finally {
            if (!first.get().isDestroyed) first.pause().stop().destroy()
            second.pause().stop().destroy()
            env.close()
        }
    }

    private suspend fun TestScope.verifyRestoration(editor: Boolean, loaded: Boolean) {
        val saved = snapshot(editor)
        val env = environment()
        if (loaded) settle(env)
        val controller = controller()
        val notifications = observe(controller.get())
        try {
            controller.create(saved)
            if (loaded) {
                runCurrent()
                assertEquals("one", controller.get().selectedTunnel?.name)
                assertTrue("Selection must not reach subclass consumers during onCreate", notifications.isEmpty())
            }
            controller.start().postCreate(saved).resume()
            if (!loaded) assertNull(controller.get().selectedTunnel)
            settle(env)
            val tunnel = env.manager.getTunnels()["one"]!!
            assertSame(tunnel, controller.get().selectedTunnel)
            assertEquals(listOf(tunnel), notifications)
            assertEquals(if (editor) 2 else 1, controller.get().supportFragmentManager.backStackEntryCount)
            if (editor) {
                val binding = editorBinding(controller)
                assertDraft(binding)
                assertFalse(binding.isNew)
                assertEquals("draft-name", binding.name)
            } else {
                val detail = controller.get().supportFragmentManager.fragments.filterIsInstance<TunnelDetailFragment>().single()
                val configJob = TunnelDetailFragment::class.java.getDeclaredField("configJob").apply { isAccessible = true }
                    .get(detail) as Job
                configJob.join()
                val binding = detailBinding(controller)
                assertSame(tunnel, binding.tunnel)
                assertEquals(51820, binding.config!!.`interface`.listenPort.get())
            }
            assertTrue(env.backend.appliedConfigs.isEmpty())
        } finally {
            controller.pause().stop().destroy()
            env.close()
        }
    }

    private suspend fun TestScope.verifyUnavailable(editor: Boolean, failure: Throwable?) {
        val saved = snapshot(editor)
        val env = environment()
        if (failure == null) env.store.configs.clear() else env.replaceBackend(object : Backend by env.backend {
            override fun getRunningTunnelNames(): Set<String> = throw failure
        })
        val controller = controller()
        val notifications = observe(controller.get())
        ShadowToast.reset()
        try {
            controller.create(saved).start().postCreate(saved).resume()
            settle(env)
            assertNull(controller.get().selectedTunnel)
            assertTrue(notifications.isEmpty())
            assertEquals(if (editor) 2 else 1, controller.get().supportFragmentManager.backStackEntryCount)
            if (editor) assertDraft(editorBinding(controller)) else assertNull(detailBinding(controller).tunnel)
            if (failure == null) assertNull(ShadowToast.getTextOfLatestToast())
            else assertTrue(ShadowToast.getTextOfLatestToast().startsWith("Error preparing tunnel:"))
            assertTrue(env.backend.appliedConfigs.isEmpty())
        } finally {
            controller.pause().stop().destroy()
            env.close()
        }
    }

    private suspend fun TestScope.snapshot(editor: Boolean): Bundle {
        val env = environment()
        val controller = controller()
        try {
            controller.setup()
            settle(env)
            controller.get().selectedTunnel = env.manager.getTunnels()["one"]!!
            controller.get().supportFragmentManager.executePendingTransactions()
            runCurrent()
            if (editor) {
                controller.get().openTunnelEditor()
                controller.get().supportFragmentManager.executePendingTransactions()
                runCurrent()
                val binding = editorBinding(controller)
                binding.config!!.`interface`.listenPort = "53000"
                binding.config!!.`interface`.dnsServers = "192.0.2.53"
                binding.name = "draft-name"
                binding.executePendingBindings()
                assertDraft(binding)
            }
            return Bundle().also { controller.saveInstanceState(it) }
        } finally {
            controller.pause().stop().destroy()
            env.close()
        }
    }

    private fun TestScope.environment(): TunnelTestEnvironment {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return TunnelTestEnvironment(folder.newFolder(), backgroundScope).also {
            it.store.configs["one"] = TunnelTestEnvironment.profile()
            it.manager.onCreate()
        }
    }

    private fun controller(): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java).also { it.get().setTheme(R.style.AppTheme) }

    private fun observe(activity: MainActivity): MutableList<ObservableTunnel?> =
        mutableListOf<ObservableTunnel?>().also { values ->
            activity.addOnSelectedTunnelChangedListener(object : BaseActivity.OnSelectedTunnelChangedListener {
                override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
                    values += newTunnel
                }
            })
        }

    private fun editorBinding(controller: ActivityController<MainActivity>): TunnelEditorFragmentBinding {
        val fragment = controller.get().supportFragmentManager.fragments.filterIsInstance<TunnelEditorFragment>().single()
        return requireNotNull(DataBindingUtil.getBinding(fragment.requireView()))
    }

    private fun detailBinding(controller: ActivityController<MainActivity>): TunnelDetailFragmentBinding {
        val fragment = controller.get().supportFragmentManager.fragments.filterIsInstance<TunnelDetailFragment>().single()
        return requireNotNull(DataBindingUtil.getBinding(fragment.requireView()))
    }

    private fun assertDraft(binding: TunnelEditorFragmentBinding) {
        assertEquals("53000", binding.config!!.`interface`.listenPort)
        assertEquals("192.0.2.53", binding.config!!.`interface`.dnsServers)
    }

    private suspend fun TestScope.settle(env: TunnelTestEnvironment) {
        runCurrent()
        val loading = TunnelManager::class.java.getDeclaredField("loadingJob").apply { isAccessible = true }
            .get(env.manager) as? Job
        loading?.join()
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
    }

    private fun inventoryFailures() = listOf(
        IOException("Injected inventory failure"),
        RootShellException(RootShellException.Reason.NO_ROOT_ACCESS),
    )
}
