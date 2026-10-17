package com.vpn0wn.android.activity

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import androidx.lifecycle.lifecycleScope
import com.vpn0wn.android.QuickTileTransitionTest
import com.vpn0wn.android.R
import com.vpn0wn.android.backend.Backend
import com.vpn0wn.android.backend.GoBackend
import com.vpn0wn.android.backend.GoBackendLifecycleTest
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.model.ObservableTunnel
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.config.Config as TunnelConfig
import java.util.concurrent.CopyOnWriteArrayList
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.robolectric.shadows.ShadowVpnService

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [
    QuickTileTransitionTest.ClickableTile::class, GoBackendLifecycleTest.LibraryShadow::class,
])
@OptIn(ExperimentalCoroutinesApi::class)
class TunnelToggleActivityTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun consentPreservesOriginalIdentityAndAbsoluteUpAcrossSelectionAndRecreation() = permissionTest { f ->
        val requestCode = launchConsent(f)
        val other = f.environment.manager.create("other", TunnelTestEnvironment.profile())
        other.setStateAsync(Tunnel.State.UP)
        f.backend.transitions.clear()
        f.controller!!.recreate()
        settle(f.activity)
        assertNull(shadowOf(f.activity).nextStartedActivityForResult)
        assertTrue(f.activity.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, null))
        settle(f.activity)
        assertEquals(listOf(f.original to Tunnel.State.UP), f.backend.transitions)
        assertSame(f.original, f.environment.manager.lastUsedTunnel)
        f.activity.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, null)
        settle(f.activity)
        assertEquals(listOf(f.original to Tunnel.State.UP), f.backend.transitions)
    }

    @Test fun cancelledConsentDropsRequestAndLateApprovalCannotActivate() = permissionTest { f ->
        val requestCode = launchConsent(f)
        assertTrue(f.activity.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_CANCELED, null))
        settle(f.activity)
        f.activity.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, null)
        settle(f.activity)
        assertTrue(f.backend.transitions.isEmpty())
        assertEquals(Tunnel.State.DOWN, f.original.state)
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test fun externalActivationDuringConsentCannotTurnOriginalDown() = permissionTest { f ->
        val requestCode = launchConsent(f)
        f.original.setStateAsync(Tunnel.State.UP)
        f.backend.transitions.clear()
        assertTrue(f.activity.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, null))
        settle(f.activity)
        assertTrue(f.backend.transitions.isEmpty())
        assertEquals(Tunnel.State.UP, f.original.state)
    }

    @Test fun sameNameReplacementBeforeActivityAdmissionRejectsOriginalBinderIdentity() = permissionTest { f ->
        val intent = parcelledIntent(f)
        f.environment.manager.delete(f.original)
        val replacement = f.environment.manager.create("original", TunnelTestEnvironment.profile())
        f.backend.transitions.clear()
        start(f, intent)
        settle(f.activity)
        assertTrue(f.backend.transitions.isEmpty())
        assertEquals(Tunnel.State.DOWN, replacement.state)
        assertTrue(ShadowToast.getTextOfLatestToast().contains("no longer managed"))
    }

    @Test fun sameNameReplacementWhileConsentOpenCannotReceiveOriginalRequest() = permissionTest { f ->
        val requestCode = launchConsent(f)
        f.environment.manager.delete(f.original)
        val replacement = f.environment.manager.create("original", TunnelTestEnvironment.profile())
        f.backend.transitions.clear()
        assertTrue(f.activity.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, null))
        settle(f.activity)
        assertTrue(f.backend.transitions.isEmpty())
        assertEquals(Tunnel.State.DOWN, replacement.state)
        assertTrue(ShadowToast.getTextOfLatestToast().contains("no longer managed"))
    }

    @Test fun restoredSavedStateWithoutRetainedOwnerDoesNotReplayBinderRequest() = permissionTest { f ->
        val requestCode = launchConsent(f)
        val intent = f.activity.intent
        val saved = Bundle()
        f.controller!!.pause().saveInstanceState(saved).stop().destroy()
        f.controller = null
        start(f, intent, saved)
        settle(f.activity)
        f.activity.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, null)
        settle(f.activity)
        assertNull(shadowOf(f.activity).nextStartedActivityForResult)
        assertTrue(f.backend.transitions.isEmpty())
    }

    @Test fun missingRequestCannotFallBackToLastUsedToggle() = permissionTest { f ->
        f.original.setStateAsync(Tunnel.State.UP)
        f.backend.transitions.clear()
        start(f, Intent(f.environment.application, TunnelToggleActivity::class.java))
        settle(f.activity)
        assertTrue(f.backend.transitions.isEmpty())
        assertEquals(Tunnel.State.UP, f.original.state)
    }

    @Test fun foreignBinderCannotAuthorizeFallback() = permissionTest { f ->
        val intent = Intent(f.environment.application, TunnelToggleActivity::class.java)
            .putExtras(Bundle().apply { putBinder("com.vpn0wn.android.tile.REQUEST", Binder()) })
        start(f, intent)
        settle(f.activity)
        assertTrue(f.backend.transitions.isEmpty())
        assertEquals(Tunnel.State.DOWN, f.original.state)
    }

    private suspend fun TestScope.launchConsent(f: Fixture): Int {
        ShadowVpnService.setPrepareResult(Intent("test.tile.consent"))
        f.environment.replaceBackend(GoBackend(f.environment.application))
        start(f, parcelledIntent(f))
        settle(f.activity)
        val request = checkNotNull(shadowOf(f.activity).nextStartedActivityForResult)
        assertEquals("test.tile.consent", request.intent.action)
        assertTrue(f.backend.transitions.isEmpty())
        assertEquals(Tunnel.State.DOWN, f.original.state)
        f.environment.replaceBackend(f.backend)
        return request.requestCode
    }

    private fun parcelledIntent(f: Fixture): Intent {
        val source = TunnelToggleActivity.createIntent(f.environment.application, f.original, Tunnel.State.UP)
        val parcel = Parcel.obtain()
        return try {
            source.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Intent.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    private fun start(f: Fixture, intent: Intent, saved: Bundle? = null) {
        val controller = Robolectric.buildActivity(TunnelToggleActivity::class.java, intent)
        f.controller = controller
        controller.get().setTheme(R.style.AppTheme)
        if (saved == null) controller.setup() else controller.setup(saved)
    }

    private suspend fun TestScope.settle(activity: TunnelToggleActivity) {
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        activity.lifecycleScope.coroutineContext[Job]!!.children.toList().joinAll()
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun permissionTest(block: suspend TestScope.(Fixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val environment = TunnelTestEnvironment(folder.newFolder(), backgroundScope)
        var fixture: Fixture? = null
        try {
            val original = environment.manager.create("original", TunnelTestEnvironment.profile())
            val backend = CountingBackend(environment.backend)
            environment.replaceBackend(backend)
            val current = Fixture(environment, backend, original)
            fixture = current
            block(current)
        } finally {
            try {
                fixture?.controller?.pause()?.stop()?.destroy()
            } finally {
                environment.close()
                ShadowVpnService.setPrepareResult(null)
                ShadowToast.reset()
                QuickTileTransitionTest.ClickableTile.listeningRequests.clear()
                Dispatchers.resetMain()
            }
        }
    }

    private class Fixture(
        val environment: TunnelTestEnvironment,
        val backend: CountingBackend,
        val original: ObservableTunnel,
    ) {
        var controller: ActivityController<TunnelToggleActivity>? = null
        val activity: TunnelToggleActivity get() = checkNotNull(controller).get()
    }

    private class CountingBackend(private val delegate: Backend) : Backend by delegate {
        val transitions = CopyOnWriteArrayList<Pair<Tunnel, Tunnel.State>>()

        override fun setState(tunnel: Tunnel, state: Tunnel.State, config: TunnelConfig?): Tunnel.State {
            transitions += tunnel to state
            return delegate.setState(tunnel, state, config)
        }
    }
}
