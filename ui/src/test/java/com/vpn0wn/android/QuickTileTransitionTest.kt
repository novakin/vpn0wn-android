package com.vpn0wn.android

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.test.core.app.ApplicationProvider
import com.vpn0wn.android.activity.TunnelToggleActivity
import com.vpn0wn.android.backend.Backend
import com.vpn0wn.android.backend.BackendException
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.model.ObservableTunnel
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.config.Config as TunnelConfig
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowService
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], shadows = [QuickTileTransitionTest.ClickableTile::class])
@OptIn(ExperimentalCoroutinesApi::class)
class QuickTileTransitionTest {
    @get:Rule val folder = TemporaryFolder()

    @Test @Config(sdk = [24, 33, 34])
    fun successfulUpFollowedByObservationFailureDoesNotLaunchInverseToggle() = tileTest(Tunnel.State.DOWN) { f ->
        observationFailureDoesNotRetry(f, Tunnel.State.UP)
    }

    @Test @Config(sdk = [24, 33, 34])
    fun successfulDownFollowedByObservationFailureDoesNotLaunchInverseToggle() = tileTest(Tunnel.State.UP) { f ->
        observationFailureDoesNotRetry(f, Tunnel.State.DOWN)
    }

    @Test @Config(sdk = [24, 33, 34])
    fun actualVpnPermissionFailureCarriesOriginalIdentityAndAbsoluteUp() = tileTest(Tunnel.State.DOWN) { f ->
        f.backend.transitionFailure = BackendException(BackendException.Reason.VPN_NOT_AUTHORIZED)
        click(f)
        val intent = checkNotNull(nextActivity())
        assertEquals(TunnelToggleActivity::class.java.name, intent.component!!.className)
        val request = intent.extras!!.getBinder("com.vpn0wn.android.tile.REQUEST") as TunnelToggleActivity.Request
        assertSame(f.tunnel, request.tunnel)
        assertEquals(Tunnel.State.UP, request.target)
        assertEquals(listOf(Tunnel.State.UP), f.backend.transitions)
        assertEquals(Tunnel.State.DOWN, f.tunnel.state)
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test @Config(sdk = [34])
    fun missingOverlayPermissionOpensSettingsWithoutASecondTransition() = tileTest(Tunnel.State.DOWN) { f ->
        ShadowSettings.setCanDrawOverlays(false)
        f.backend.transitionFailure = BackendException(BackendException.Reason.VPN_NOT_AUTHORIZED)
        click(f)
        val intent = checkNotNull(nextActivity())
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, intent.action)
        assertEquals("package:${f.controller.get().packageName}", intent.data.toString())
        assertEquals(listOf(Tunnel.State.UP), f.backend.transitions)
        assertEquals(Tunnel.State.DOWN, f.tunnel.state)
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test fun unrelatedFailureCannotRequestConsent() = tileTest(Tunnel.State.DOWN) { f ->
        f.backend.transitionFailure = IOException("Injected transition failure")
        click(f)
        assertNull(nextActivity())
        assertEquals(listOf(Tunnel.State.UP), f.backend.transitions)
        assertTrue(ShadowToast.getTextOfLatestToast().contains("Injected transition failure"))
    }

    @Test fun downPermissionFailureCannotRequestAnUpContinuation() = tileTest(Tunnel.State.UP) { f ->
        f.backend.transitionFailure = BackendException(BackendException.Reason.VPN_NOT_AUTHORIZED)
        click(f)
        assertNull(nextActivity())
        assertEquals(listOf(Tunnel.State.DOWN), f.backend.transitions)
        assertEquals(Tunnel.State.UP, f.tunnel.state)
        assertTrue(ShadowToast.getTextOfLatestToast().isNotEmpty())
    }

    @Test fun cancellationRemainsCancellationWithoutFeedbackOrFallback() = tileTest(Tunnel.State.DOWN) { f ->
        f.backend.transitionFailure = CancellationException("Injected cancellation")
        click(f)
        assertNull(nextActivity())
        assertNull(ShadowToast.getTextOfLatestToast())
        assertEquals(listOf(Tunnel.State.UP), f.backend.transitions)
        assertEquals(Tunnel.State.DOWN, f.tunnel.state)
    }

    @Test fun errorsAreNotConvertedToConsentOrFeedback() {
        val failure = AssertionError("Injected Error")
        val propagated = assertThrows(AssertionError::class.java) {
            tileTest(Tunnel.State.DOWN) { f ->
                f.backend.transitionFailure = failure
                click(f)
                assertNull(nextActivity())
                assertNull(ShadowToast.getTextOfLatestToast())
            }
        }
        assertTrue(generateSequence<Throwable>(propagated) { it.cause }.any { it === failure })
    }

    private suspend fun TestScope.observationFailureDoesNotRetry(f: Fixture, target: Tunnel.State) {
        f.backend.failObservation = true
        click(f)
        assertEquals(listOf(target), f.backend.transitions)
        assertEquals(target, f.environment.backend.getState(f.tunnel))
        assertEquals(target, f.tunnel.state)
        assertEquals(if (target == Tunnel.State.UP) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE, f.controller.get().qsTile.state)
        assertNull(nextActivity())
        assertTrue(ShadowToast.getTextOfLatestToast().contains("Injected post-transition observation failure"))
    }

    private suspend fun TestScope.click(f: Fixture): List<Job> {
        val previous = f.environment.applicationScope.coroutineContext[Job]!!.children.toSet()
        f.controller.get().onClick()
        runCurrent()
        val jobs = f.environment.applicationScope.coroutineContext[Job]!!.children.filter { it !in previous }.toList()
        jobs.joinAll()
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        return jobs
    }

    private fun nextActivity(): Intent? = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity

    private fun tileTest(initial: Tunnel.State, block: suspend TestScope.(Fixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.newFolder(), backgroundScope)
        val controller = Robolectric.buildService(QuickTileService::class.java).create()
        try {
            ShadowToast.reset()
            ShadowSettings.setCanDrawOverlays(true)
            val tunnel = env.manager.create("requested", TunnelTestEnvironment.profile())
            tunnel.setStateAsync(Tunnel.State.UP)
            if (initial == Tunnel.State.DOWN) tunnel.setStateAsync(Tunnel.State.DOWN)
            env.manager.getTunnelState(tunnel)
            val backend = ControlledTransitionBackend(env.backend)
            env.replaceBackend(backend)
            controller.get().onStartListening()
            block(Fixture(env, backend, tunnel, controller))
        } finally {
            controller.get().onStopListening()
            controller.destroy()
            env.close()
            ShadowToast.reset()
            ClickableTile.listeningRequests.clear()
            ShadowSettings.setCanDrawOverlays(false)
            Dispatchers.resetMain()
        }
    }

    private class Fixture(
        val environment: TunnelTestEnvironment,
        val backend: ControlledTransitionBackend,
        val tunnel: ObservableTunnel,
        val controller: ServiceController<QuickTileService>,
    )

    private class ControlledTransitionBackend(private val delegate: Backend) : Backend by delegate {
        val transitions = CopyOnWriteArrayList<Tunnel.State>()
        var transitionFailure: Throwable? = null
        var failObservation = false
        private var failurePending = false

        override fun setState(tunnel: Tunnel, state: Tunnel.State, config: TunnelConfig?): Tunnel.State {
            transitions += state
            transitionFailure?.let { throw it }
            return delegate.setState(tunnel, state, config).also { failurePending = failObservation }
        }

        override fun getRunningTunnelNames(): Set<String> {
            if (failurePending) {
                failurePending = false
                throw IOException("Injected post-transition observation failure")
            }
            return delegate.runningTunnelNames
        }
    }

    // Robolectric's tile shadow lacks Service teardown and the system's unlock callback delivery.
    @Implements(TileService::class)
    class ClickableTile : ShadowService() {
        private val tile: Tile by lazy { Shadow.newInstanceOf(Tile::class.java) }

        @Implementation fun getQsTile(): Tile = tile
        @Implementation fun unlockAndRun(action: Runnable) = action.run()
        @Implementation(minSdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        fun startActivityAndCollapse(intent: PendingIntent) = intent.send()

        companion object {
            val listeningRequests = CopyOnWriteArrayList<Pair<Context, ComponentName>>()

            @JvmStatic @Implementation
            fun requestListeningState(context: Context, component: ComponentName) {
                listeningRequests += context to component
            }
        }
    }
}
