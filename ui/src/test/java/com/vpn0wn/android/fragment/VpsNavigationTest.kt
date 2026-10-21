/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.fragment

import android.app.Application
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vpn0wn.android.Application as Vpn0wnApplication
import com.vpn0wn.android.R
import com.vpn0wn.android.activity.MainActivity
import com.vpn0wn.android.databinding.TunnelDetailFragmentBinding
import com.vpn0wn.android.databinding.TunnelListFragmentBinding
import com.vpn0wn.android.model.ObservableTunnel
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.android.provisioning.VpsPlanningTestPeer
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.AuthMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class VpsNavigationTest {
    @get:Rule val folder = TemporaryFolder()

    @After fun tearDown() {
        Dispatchers.resetMain()
        RuntimeEnvironment.setQualifiers("")
    }

    @Test fun homeServersOpensWizardAndBackRestoresPhoneAndTabletHome() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (tablet in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers(if (tablet) "sw600dp-w960dp-h600dp-land" else "w320dp-h640dp-port")
            val environment = TunnelTestEnvironment(folder.newFolder(), backgroundScope)
            val controller = Robolectric.buildActivity(MainActivity::class.java)
            try {
                controller.get().setTheme(R.style.AppTheme)
                controller.setup()
                runCurrent()
                shadowOf(Looper.getMainLooper()).idle()
                val activity = controller.get()
                val fragments = activity.supportFragmentManager
                val home = fragments.findFragmentByTag("LIST") as TunnelListFragment
                val entry = home.requireView().findViewById<View>(R.id.home_servers)
                assertEquals(activity.getString(R.string.vpn0wn_servers_title), entry.contentDescription)
                assertTrue(entry.performClick())
                fragments.executePendingTransactions()
                runCurrent()
                shadowOf(Looper.getMainLooper()).idle()

                val container = if (tablet) R.id.detail_container else R.id.list_detail_container
                val servers = fragments.findFragmentById(container) as VpsProvisioningFragment
                assertEquals(1, fragments.backStackEntryCount)
                if (tablet) {
                    assertTrue(entry.performClick())
                    fragments.executePendingTransactions()
                    assertSame(servers, fragments.findFragmentById(container))
                    assertEquals(1, fragments.backStackEntryCount)
                }
                val addLabel = activity.getString(R.string.vpn0wn_add_server).removePrefix("+ ").trim()
                val add = descendants(servers.requireView()).filterIsInstance<TextView>().single { it.text.toString() == addLabel }
                assertTrue(clickableAncestor(add).performClick())
                runCurrent()
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(descendants(servers.requireView()).filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.vpn0wn_continue) })

                activity.onBackPressedDispatcher.onBackPressed()
                runCurrent()
                shadowOf(Looper.getMainLooper()).idle()
                activity.onBackPressedDispatcher.onBackPressed()
                fragments.executePendingTransactions()
                runCurrent()
                assertEquals(0, fragments.backStackEntryCount)
                assertSame(home, fragments.findFragmentByTag("LIST"))
                assertTrue(home.requireView().findViewById<View>(R.id.home_servers).isEnabled)
                assertEquals(if (tablet) null else home, fragments.findFragmentById(container))
            } finally {
                try { controller.pause().stop().destroy() } finally { environment.close() }
            }
        }
    }

    @Test fun tabletTunnelRowsDismissServersAndDrainTheWizardAcrossExistingContexts() = runTest {
        for (context in listOf("none", "detail", "editor", "same")) {
            val fixture = fixture(tablet = true)
            try {
                if (context != "none") selectRow(fixture, "bravo")
                if (context == "editor") {
                    fixture.activity.openTunnelEditor()
                    settle(fixture)
                }
                val peer = VpsPlanningTestPeer().apply { blockDetect = CompletableDeferred() }
                val model = injectModel(fixture, peer)
                Vpn0wnApplication.getVpsServerStore().trustHost("198.51.100.42", 22, peer.discoveredFingerprint)
                fixture.home.requireView().findViewById<View>(R.id.home_servers).performClick()
                settle(fixture)
                model.begin()
                model.edit(model.draft.copy(host = "198.51.100.42", authMode = AuthMode.PASSWORD))
                model.edit(model.draft.copy(password = "synthetic-navigation-password".toCharArray()))
                val password = model.draft.password
                val check = requireNotNull(model.detect())
                try {
                    peer.detectEntered.await()
                    val name = if (context == "same") "bravo" else "charlie"
                    selectRow(fixture, name)
                    check.join()
                    assertTrue(fixture.fragments.findFragmentById(fixture.container) is TunnelDetailFragment)
                    assertSame(fixture.tunnel(name), fixture.activity.selectedTunnel)
                    assertEquals(1, fixture.fragments.backStackEntryCount)
                    assertFalse(model.state.value.active)
                    assertFalse(model.state.value.busy)
                    assertTrue(password.all { it == '\u0000' })
                    assertEquals(1, peer.closes.get())
                    fixture.activity.onBackPressedDispatcher.onBackPressed()
                    assertTrue(fixture.activity.isFinishing)
                } finally {
                    peer.blockDetect?.complete(Unit)
                    model.leave()
                    withContext(NonCancellable) { check.cancelAndJoin() }
                }
            } finally { fixture.close() }
        }
    }

    @Test fun serversBackRestoresSelectedDetailAndEditorOnPhoneAndTablet() = runTest {
        for (tablet in listOf(false, true)) for (editor in listOf(false, true)) {
            val fixture = fixture(tablet)
            try {
                selectRow(fixture, "bravo")
                if (editor) {
                    fixture.activity.openTunnelEditor()
                    settle(fixture)
                }
                fixture.activity.openServers()
                settle(fixture)
                assertTrue(fixture.fragments.findFragmentById(fixture.container) is VpsProvisioningFragment)
                fixture.activity.onBackPressedDispatcher.onBackPressed()
                settle(fixture)
                val restored = fixture.fragments.findFragmentById(fixture.container)
                assertTrue(if (editor) restored is TunnelEditorFragment else restored is TunnelDetailFragment)
                assertSame(fixture.tunnel("bravo"), fixture.activity.selectedTunnel)
                assertEquals(if (editor) 2 else 1, fixture.fragments.backStackEntryCount)
                if (editor) {
                    fixture.activity.onBackPressedDispatcher.onBackPressed()
                    settle(fixture)
                    assertTrue(fixture.fragments.findFragmentById(fixture.container) is TunnelDetailFragment)
                    assertSame(fixture.tunnel("bravo"), fixture.activity.selectedTunnel)
                    assertEquals(1, fixture.fragments.backStackEntryCount)
                }
                fixture.activity.onBackPressedDispatcher.onBackPressed()
                settle(fixture)
                if (tablet) {
                    assertTrue(fixture.activity.isFinishing)
                } else {
                    assertNull(fixture.activity.selectedTunnel)
                    assertEquals(0, fixture.fragments.backStackEntryCount)
                    assertTrue(fixture.fragments.findFragmentById(fixture.container) is TunnelListFragment)
                }
            } finally { fixture.close() }
        }
    }

    @Test fun detailHeaderActionsRetainExistingPhoneAndTabletNavigation() = runTest {
        for (tablet in listOf(false, true)) {
            val fixture = fixture(tablet)
            try {
                selectRow(fixture, "bravo")
                val detail = fixture.fragments.findFragmentById(fixture.container) as TunnelDetailFragment
                assertEquals("Only a visible tablet Home may retain keyboard targets", tablet, homeFocusTargets(fixture).isNotEmpty())
                val binding = requireNotNull(DataBindingUtil.getBinding<TunnelDetailFragmentBinding>(detail.requireView()))
                binding.executePendingBindings()
                val root = detail.requireView() as ViewGroup
                val density = root.resources.displayMetrics.density
                root.measure(View.MeasureSpec.makeMeasureSpec((320 * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((640 * density).toInt(), View.MeasureSpec.EXACTLY))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                assertEquals(if (tablet) View.INVISIBLE else View.VISIBLE, binding.detailBackButton.visibility)
                val edit = binding.detailEditButton
                assertTrue(edit.width >= 48 * density && edit.height >= 48 * density)
                val bounds = Rect().also { edit.getDrawingRect(it); root.offsetDescendantRectToMyCoords(edit, it) }
                val time = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(time, time, action, bounds.right - 1f, bounds.bottom - 1f, 0)
                    try { assertTrue(root.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
                settle(fixture)
                assertTrue(fixture.fragments.findFragmentById(fixture.container) is TunnelEditorFragment)
                assertSame(fixture.tunnel("bravo"), fixture.activity.selectedTunnel)
                fixture.activity.onBackPressedDispatcher.onBackPressed()
                settle(fixture)
                assertEquals("Editor return preserves foreground focus ownership", tablet, homeFocusTargets(fixture).isNotEmpty())
                fixture.controller.recreate()
                settle(fixture)
                val restored = fixture.fragments.findFragmentById(fixture.container) as TunnelDetailFragment
                assertEquals("Recreation preserves foreground focus ownership", tablet, homeFocusTargets(fixture).isNotEmpty())
                if (!tablet) {
                    val back = requireNotNull(DataBindingUtil.getBinding<TunnelDetailFragmentBinding>(restored.requireView())).detailBackButton
                    assertTrue(back.requestFocus())
                    assertTrue(back.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
                    assertTrue(back.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)))
                    settle(fixture)
                    assertNull(fixture.activity.selectedTunnel)
                    assertTrue(fixture.fragments.findFragmentById(fixture.container) is TunnelListFragment)
                    assertFalse("Back restores Home keyboard targets", homeFocusTargets(fixture).isEmpty())
                }
            } finally { fixture.close() }
        }
    }

    @Test fun recreationWithSelectedTunnelPreservesServersAndItsRetainedDraft() = runTest {
        for (tablet in listOf(false, true)) {
            val fixture = fixture(tablet)
            try {
                selectRow(fixture, "bravo")
                fixture.activity.openServers()
                settle(fixture)
                val servers = fixture.fragments.findFragmentById(fixture.container) as VpsProvisioningFragment
                val model = ViewModelProvider(servers)[VpsProvisioningViewModel::class.java]
                model.begin()
                model.edit(model.draft.copy(host = "198.51.100.42"))
                settle(fixture)
                fixture.controller.recreate()
                settle(fixture)
                val restored = fixture.fragments.findFragmentById(fixture.container) as VpsProvisioningFragment
                assertSame(model, ViewModelProvider(restored)[VpsProvisioningViewModel::class.java])
                assertTrue(model.state.value.active)
                assertEquals("198.51.100.42", model.draft.host)
                assertSame(fixture.tunnel("bravo"), fixture.activity.selectedTunnel)
                assertEquals(2, fixture.fragments.backStackEntryCount)
            } finally { fixture.close() }
        }
    }

    // Setup failure must drain the application owner before Main is reset.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun TestScope.fixture(tablet: Boolean): Fixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        RuntimeEnvironment.setQualifiers(if (tablet) "sw600dp-w960dp-h600dp-land" else "w320dp-h640dp-port")
        val environment = TunnelTestEnvironment(folder.newFolder(), backgroundScope)
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        try {
            environment.store.configs["bravo"] = TunnelTestEnvironment.profile()
            environment.store.configs["charlie"] = TunnelTestEnvironment.profile()
            environment.manager.getTunnels()
            controller.get().setTheme(R.style.AppTheme)
            controller.setup()
            return Fixture(environment, controller, tablet).also { settle(it) }
        } catch (failure: Throwable) {
            try { controller.pause().stop().destroy() } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            try { environment.close() } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    private fun homeFocusTargets(fixture: Fixture): List<View> {
        val homeViews = descendants(fixture.home.requireView()).toSet()
        return ArrayList<View>().also { fixture.activity.window.decorView.addFocusables(it, View.FOCUS_FORWARD) }.filter { it in homeViews }
    }

    private fun injectModel(fixture: Fixture, peer: VpsPlanningTestPeer): VpsProvisioningViewModel {
        val model = VpsProvisioningViewModel(fixture.activity.application, Vpn0wnApplication.getVpsServerStore(), peer)
        fixture.peers.add(peer)
        fixture.modelJobs.add(requireNotNull(model.viewModelScope.coroutineContext[Job]))
        fixture.fragments.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentPreCreated(fm: FragmentManager, fragment: Fragment, savedInstanceState: android.os.Bundle?) {
                if (fragment is VpsProvisioningFragment) ViewModelProvider(fragment, object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(model)!!
                })[VpsProvisioningViewModel::class.java]
            }
        }, false)
        return model
    }

    private suspend fun TestScope.selectRow(fixture: Fixture, name: String) {
        val binding = requireNotNull(DataBindingUtil.getBinding<TunnelListFragmentBinding>(fixture.home.requireView()))
        val index = requireNotNull(binding.tunnels).indexOf(fixture.tunnel(name))
        binding.tunnelList.scrollToPosition(index)
        val root = fixture.activity.findViewById<View>(android.R.id.content)
        val density = root.resources.displayMetrics.density
        val width = (if (fixture.tablet) 960 else 320) * density
        val height = (if (fixture.tablet) 600 else 640) * density
        root.measure(View.MeasureSpec.makeMeasureSpec(width.toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height.toInt(), View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        assertTrue(requireNotNull(binding.tunnelList.findViewHolderForAdapterPosition(index)).itemView.performClick())
        settle(fixture)
    }

    private fun TestScope.settle(fixture: Fixture) {
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        fixture.fragments.executePendingTransactions()
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private class Fixture(
        val environment: TunnelTestEnvironment,
        val controller: ActivityController<MainActivity>,
        val tablet: Boolean,
    ) {
        val modelJobs = mutableSetOf<Job>()
        val peers = mutableListOf<VpsPlanningTestPeer>()
        val activity get() = controller.get()
        val fragments get() = activity.supportFragmentManager
        val home get() = fragments.findFragmentByTag("LIST") as TunnelListFragment
        val container get() = if (tablet) R.id.detail_container else R.id.list_detail_container
        suspend fun tunnel(name: String): ObservableTunnel = requireNotNull(environment.manager.getTunnels()[name])
        suspend fun close() = withContext(NonCancellable) {
            peers.forEach { it.blockDetect?.complete(Unit) }
            try {
                fragments.fragments.filterIsInstance<VpsProvisioningFragment>().forEach { fragment ->
                    modelJobs.add(requireNotNull(ViewModelProvider(fragment)[VpsProvisioningViewModel::class.java].viewModelScope.coroutineContext[Job]))
                }
            } finally {
                try { controller.pause().stop().destroy() } finally {
                    try { modelJobs.forEach { it.cancelAndJoin() } } finally { environment.close() }
                }
            }
        }
    }

    private fun clickableAncestor(view: View): View = if (view.isClickable) view else clickableAncestor(view.parent as View)

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }
}
