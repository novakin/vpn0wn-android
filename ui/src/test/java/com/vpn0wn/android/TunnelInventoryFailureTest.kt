package com.vpn0wn.android

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.ViewGroup
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.databinding.DataBindingUtil
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.vpn0wn.android.activity.BaseActivity
import com.vpn0wn.android.activity.MainActivity
import com.vpn0wn.android.activity.TvMainActivity
import com.vpn0wn.android.backend.Backend
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.databinding.TunnelListFragmentBinding
import com.vpn0wn.android.databinding.TvActivityBinding
import com.vpn0wn.android.fragment.TunnelListFragment
import com.vpn0wn.android.model.ObservableTunnel
import com.vpn0wn.android.model.TunnelManager
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.android.util.RootShell.RootShellException
import com.vpn0wn.android.util.ErrorMessages
import com.vpn0wn.config.Config as TunnelConfig
import java.io.IOException
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowService
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [TunnelInventoryFailureTest.TileShadow::class])
@OptIn(ExperimentalCoroutinesApi::class)
class TunnelInventoryFailureTest {
    @get:Rule val folder = TemporaryFolder()
    private var originalNightMode = AppCompatDelegate.MODE_NIGHT_UNSPECIFIED

    @Before fun setUp() {
        originalNightMode = AppCompatDelegate.getDefaultNightMode()
        TileShadow.launched.clear()
        ShadowToast.reset()
    }

    @After fun tearDown() {
        AppCompatDelegate.setDefaultNightMode(originalNightMode)
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.setQualifiers("")
        Dispatchers.resetMain()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun homeReportsUnavailableInventoryAndRecreationCanRetry() = runTest {
        for ((index, failure) in expectedFailures().withIndex()) {
            val largeRtl = index == 1
            RuntimeEnvironment.setQualifiers(if (largeRtl) "ar-rEG-ldrtl-w640dp-h320dp-land-night" else "en-rUS-ldltr-w320dp-h640dp-port-notnight")
            RuntimeEnvironment.setFontScale(if (largeRtl) 2f else 1f)
            val (env, backend) = environment(failure)
            val controller = Robolectric.buildActivity(HomeHostActivity::class.java)
            controller.get().setTheme(R.style.AppTheme)
            controller.setup()
            controller.get().supportActionBar?.hide()
            val fragment = TunnelListFragment()
            try {
                controller.get().supportFragmentManager.beginTransaction()
                    .add(android.R.id.content, fragment).commitNow()
                settleInventory(env)
                shadowOf(Looper.getMainLooper()).idle()
                val binding = DataBindingUtil.getBinding<TunnelListFragmentBinding>(fragment.requireView())!!
                binding.executePendingBindings()
                assertNull(binding.tunnels)
                assertEquals(View.GONE, binding.homeStatusContainer.visibility)
                assertEquals(View.GONE, binding.homeEmptyScroll.visibility)
                assertEquals("vpn0wn", binding.homeHeaderSummary.contentDescription.toString())
                val message = binding.root.rootView.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
                assertNotNull(message)
                assertTrue(message.text.startsWith(fragment.getString(R.string.error_prepare, "").trimEnd()))
                captureHome(binding, largeRtl)
                assertEquals(setOf("one"), env.store.configs.keys)
                backend.failure = null
                val retry = TunnelListFragment()
                controller.get().supportFragmentManager.beginTransaction()
                    .replace(android.R.id.content, retry).commitNow()
                settleInventory(env)
                val restored = DataBindingUtil.getBinding<TunnelListFragmentBinding>(retry.requireView())!!
                assertEquals("one", restored.tunnels!![0].name)
                assertTrue(env.backend.appliedConfigs.isEmpty())
                restored.executePendingBindings()
                assertEquals(View.VISIBLE, restored.homeStatusContainer.visibility)
                assertEquals(View.GONE, restored.homeEmptyScroll.visibility)
                env.manager.delete(restored.tunnels!![0])
                runCurrent()
                restored.executePendingBindings()
                assertTrue(restored.tunnels!!.isEmpty())
                assertEquals(View.VISIBLE, restored.homeStatusContainer.visibility)
                assertEquals(View.VISIBLE, restored.homeEmptyScroll.visibility)
            } finally {
                controller.pause().stop().destroy()
                env.close()
            }
        }
    }

    @Test fun savedSelectionReportsUnavailableInventoryAndNewActivityCanRetry() = runTest {
        val (env, backend) = environment(IOException("Injected observation failure"))
        val saved = Bundle().apply { putString("selected_tunnel", "one") }
        val first = Robolectric.buildActivity(SelectionHostActivity::class.java)
        first.get().setTheme(R.style.AppTheme)
        first.create(saved).start().resume()
        try {
            settleInventory(env)
            assertNull(first.get().selectedTunnel)
            assertTrue(ShadowToast.getTextOfLatestToast().startsWith("Error preparing tunnel:"))
            first.pause().stop().destroy()
            backend.failure = null
            val retry = Robolectric.buildActivity(SelectionHostActivity::class.java)
            retry.get().setTheme(R.style.AppTheme)
            try {
                retry.create(saved).start().resume()
                settleInventory(env)
                assertEquals("one", retry.get().selectedTunnel!!.name)
                assertTrue(env.backend.appliedConfigs.isEmpty())
            } finally {
                retry.pause().stop().destroy()
            }
        } finally {
            if (!first.get().isDestroyed) first.pause().stop().destroy()
            env.close()
        }
    }

    @Test fun televisionReportsUnavailableInventoryAndNewActivityCanRetry() = runTest {
        val (env, backend) = environment(RootShellException(RootShellException.Reason.NO_ROOT_ACCESS))
        val first = Robolectric.buildActivity(TvMainActivity::class.java)
        first.get().setTheme(R.style.AppTheme)
        first.create().start().resume()
        try {
            settleInventory(env)
            assertTrue(ShadowToast.getTextOfLatestToast().startsWith("Error preparing tunnel:"))
            val binding = DataBindingUtil.getBinding<TvActivityBinding>(first.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0))!!
            assertNull(binding.tunnels)
            first.pause().stop().destroy()
            backend.failure = null
            val retry = Robolectric.buildActivity(TvMainActivity::class.java)
            retry.get().setTheme(R.style.AppTheme)
            try {
                retry.create().start().resume()
                settleInventory(env)
                val restored = DataBindingUtil.getBinding<TvActivityBinding>(retry.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0))!!
                assertEquals("one", restored.tunnels!![0].name)
                assertTrue(env.backend.appliedConfigs.isEmpty())
            } finally {
                retry.pause().stop().destroy()
            }
        } finally {
            if (!first.get().isDestroyed) first.pause().stop().destroy()
            env.close()
        }
    }

    @Test fun tileOpensAppWithoutTransitionWhenInventoryIsUnavailableAndNextClickCanRetry() = runTest {
        val (env, backend) = environment(IOException("Injected observation failure"))
        val controller = Robolectric.buildService(QuickTileService::class.java).create()
        try {
            controller.get().onClick()
            settleInventory(env)
            assertEquals(MainActivity::class.java.name, TileShadow.launched.single().component!!.className)
            assertTrue(env.backend.appliedConfigs.isEmpty())
            assertEquals(setOf("one"), env.store.configs.keys)
            backend.failure = null
            controller.get().onClick()
            settleInventory(env)
            assertEquals(2, TileShadow.launched.size)
            assertEquals("one", env.manager.getTunnels()[0].name)
            assertTrue(env.backend.appliedConfigs.isEmpty())
        } finally {
            controller.destroy()
            env.close()
        }
    }

    @Test fun remoteControlReportsExpectedInventoryFailureAndNextIntentCanRetry() = runTest {
        for (failure in expectedFailures()) {
            for (state in listOf(Tunnel.State.UP, Tunnel.State.DOWN)) {
                ShadowToast.reset()
                val (env, backend) = environment(failure)
                try {
                    enableRemoteControl()
                    val request = remoteIntent(state)
                    receive(env, request)
                    assertEquals(ErrorMessages[failure], ShadowToast.getTextOfLatestToast())
                    assertTrue(backend.transitions.isEmpty())
                    assertEquals(setOf("one"), env.store.configs.keys)
                    assertTrue(env.store.deleteAttempts.isEmpty())
                    backend.failure = null
                    ShadowToast.reset()
                    receive(env, request)
                    assertEquals(state, env.manager.getTunnels()["one"]!!.state)
                    assertEquals(if (state == Tunnel.State.UP) listOf(state) else emptyList<Tunnel.State>(), backend.transitions)
                    assertNull(ShadowToast.getTextOfLatestToast())
                    assertEquals(setOf("one"), env.store.configs.keys)
                } finally {
                    env.close()
                }
            }
        }
    }

    @Test fun remoteControlPreservesInventoryCancellationWithoutFeedbackOrTransition() = runTest {
        val (env, backend) = environment(CancellationException("Injected inventory cancellation"))
        try {
            enableRemoteControl()
            receive(env, remoteIntent(Tunnel.State.UP))
            assertNull(ShadowToast.getTextOfLatestToast())
            assertTrue(backend.transitions.isEmpty())
            assertEquals(setOf("one"), env.store.configs.keys)
            backend.failure = null
            receive(env, remoteIntent(Tunnel.State.UP))
            assertEquals(listOf(Tunnel.State.UP), backend.transitions)
        } finally {
            env.close()
        }
    }

    @Test fun remoteControlDoesNotConvertUnexpectedInventoryFailuresToFeedback() {
        for (failure in listOf(IllegalStateException("Injected inventory defect"), AssertionError("Injected inventory Error"))) {
            val propagated = assertThrows(failure.javaClass) {
                runTest {
                    val (env, backend) = environment(failure)
                    try {
                        enableRemoteControl()
                        receive(env, remoteIntent(Tunnel.State.UP))
                        assertNull(ShadowToast.getTextOfLatestToast())
                        assertTrue(backend.transitions.isEmpty())
                        assertEquals(setOf("one"), env.store.configs.keys)
                    } finally {
                        env.close()
                    }
                }
            }
            assertTrue("Expected original ${failure.javaClass.simpleName}", propagated === failure || propagated.cause === failure)
        }
    }

    @Test fun remoteControlAdmissionStillRejectsDisabledAndMalformedRequests() = runTest {
        val (env, backend) = environment(IOException("Injected observation failure"))
        try {
            receive(env, remoteIntent(Tunnel.State.UP))
            enableRemoteControl()
            receive(env, Intent("com.wireguard.android.action.SET_TUNNEL_UP").putExtra("tunnel", "one"))
            receive(env, Intent("com.vpn0wn.android.action.SET_TUNNEL_UP"))
            receive(env, Intent())
            assertNull(ShadowToast.getTextOfLatestToast())
            assertEquals(0, backend.observations)
            assertTrue(backend.transitions.isEmpty())
            assertEquals(setOf("one"), env.store.configs.keys)
            backend.failure = null
            receive(env, remoteIntent(Tunnel.State.UP).putExtra("tunnel", "missing"))
            assertTrue(backend.transitions.isEmpty())
            assertNull(ShadowToast.getTextOfLatestToast())
        } finally {
            env.close()
        }
    }

    private suspend fun enableRemoteControl() {
        com.vpn0wn.android.Application.getPreferencesDataStore().edit {
            it[booleanPreferencesKey("allow_remote_control_intents")] = true
        }
    }

    private fun remoteIntent(state: Tunnel.State) =
        Intent("com.vpn0wn.android.action.SET_TUNNEL_${state.name}").putExtra("tunnel", "one")

    private suspend fun TestScope.receive(env: TunnelTestEnvironment, intent: Intent) {
        val previousJobs = env.applicationScope.coroutineContext[Job]!!.children.toSet()
        TunnelManager.IntentReceiver().onReceive(env.application, intent)
        env.applicationScope.coroutineContext[Job]!!.children.single { it !in previousJobs }.join()
        settleInventory(env)
    }

    private fun TestScope.environment(failure: Throwable): Pair<TunnelTestEnvironment, InventoryBackend> {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.newFolder(), backgroundScope)
        env.store.configs["one"] = TunnelTestEnvironment.profile()
        val backend = InventoryBackend(env.backend).apply { this.failure = failure }
        env.replaceBackend(backend)
        return env to backend
    }

    private suspend fun TestScope.settleInventory(env: TunnelTestEnvironment) {
        runCurrent()
        val loading = TunnelManager::class.java.getDeclaredField("loadingJob")
            .apply { isAccessible = true }.get(env.manager) as? Job
        loading?.join()
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun expectedFailures() = listOf(
        IOException("Injected observation failure"),
        RootShellException(RootShellException.Reason.NO_ROOT_ACCESS),
    )

    private fun captureHome(binding: TunnelListFragmentBinding, largeRtl: Boolean) {
        binding.executePendingBindings()
        val root = binding.root.rootView
        val density = root.resources.displayMetrics.density
        val width = ((if (largeRtl) 640 else 320) * density).toInt()
        val height = ((if (largeRtl) 320 else 640) * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        assertEquals(if (largeRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR, root.layoutDirection)
        assertEquals(if (largeRtl) 2f else 1f, root.resources.configuration.fontScale, 0f)
        assertEquals(if (largeRtl) "ar" else "en", root.resources.configuration.locales[0].language)
        assertFalse("Canceled pre-draw cannot be accepted as a rendered frame", root.viewTreeObserver.dispatchOnPreDraw())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val directory = File("../.artifacts/kernel-inventory-previews").apply { mkdirs() }
        val name = if (largeRtl) "home-unavailable-large-rtl-dark.png" else "home-unavailable-portrait-light.png"
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private class InventoryBackend(private val backend: Backend) : Backend by backend {
        var failure: Throwable? = null
        var observations = 0
        val transitions = mutableListOf<Tunnel.State>()
        override fun getRunningTunnelNames(): Set<String> {
            observations++
            failure?.let { throw it }
            return backend.runningTunnelNames
        }
        override fun setState(tunnel: Tunnel, state: Tunnel.State, config: TunnelConfig?): Tunnel.State {
            transitions += state
            return backend.setState(tunnel, state, config)
        }
    }

    class HomeHostActivity : AppCompatActivity()

    class SelectionHostActivity : BaseActivity() {
        override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) = true
    }

    @Implements(TileService::class)
    class TileShadow : ShadowService() {
        private val tile by lazy { Shadow.newInstanceOf(Tile::class.java) }
        @Implementation fun getQsTile(): Tile = tile
        @Implementation fun startActivityAndCollapse(intent: Intent) {
            launched += intent
        }
        companion object {
            val launched = mutableListOf<Intent>()
        }
    }
}
