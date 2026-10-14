package com.vpn0wn.android.fragment

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.vpn0wn.android.R
import com.vpn0wn.android.activity.BaseActivity
import com.vpn0wn.android.backend.Backend
import com.vpn0wn.android.backend.GoBackend
import com.vpn0wn.android.backend.GoBackendLifecycleTest
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.databinding.TunnelListItemBinding
import com.vpn0wn.android.model.ObservableTunnel
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.config.Config as TunnelConfig
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
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
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowVpnService

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [GoBackendLifecycleTest.LibraryShadow::class])
@OptIn(ExperimentalCoroutinesApi::class)
class VpnConsentLifecycleTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun positiveResultContinuesOriginalTunnelOnceWithoutRecreation() = consentTest { fixture ->
        val requestCode = launchConsent(fixture)
        fixture.environment.replaceBackend(fixture.backend)
        assertTrue(fixture.activity.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, null))
        settle(fixture.activity)
        assertEquals(Tunnel.State.UP, fixture.tunnel.state)
        assertEquals(listOf(fixture.tunnel), fixture.backend.upCalls)
        assertSame(fixture.tunnel.peekConfig(), fixture.environment.backend.appliedConfigs[fixture.tunnel])
    }

    @Test fun positiveResultContinuesOriginalTunnelOnceAfterActivityAndFragmentRecreation() = consentTest { fixture ->
        val requestCode = launchConsent(fixture)
        val originalActivity = fixture.activity
        val originalFragment = fixture.fragment
        fixture.environment.replaceBackend(fixture.backend)
        fixture.controller.recreate()
        assertNotSame(originalActivity, fixture.activity)
        assertNotSame(originalFragment, fixture.fragment)
        assertTrue(fixture.activity.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, null))
        settle(fixture.activity)
        assertEquals(Tunnel.State.UP, fixture.tunnel.state)
        assertEquals(listOf(fixture.tunnel), fixture.backend.upCalls)
    }

    @Test fun changedSelectionDoesNotRetargetPendingConsent() = consentTest { fixture ->
        fixture.activity.selectedTunnel = fixture.tunnel
        val requestCode = launchConsent(fixture)
        fixture.environment.replaceBackend(fixture.backend)
        val selected = fixture.environment.manager.create("selected", TunnelTestEnvironment.profile(51821))
        fixture.activity.selectedTunnel = selected
        assertTrue(fixture.activity.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, null))
        settle(fixture.activity)
        assertSame(selected, fixture.activity.selectedTunnel)
        assertEquals(Tunnel.State.DOWN, selected.state)
        assertEquals(Tunnel.State.UP, fixture.tunnel.state)
        assertEquals(listOf(fixture.tunnel), fixture.backend.upCalls)
    }

    @Test fun deniedConsentLaunchSettlesSwitchAndAllowsRetry() = consentTest { fixture ->
        rejectLaunchThenRetry(fixture, SecurityException("Injected consent launch denial"))
    }

    @Test fun missingConsentActivitySettlesSwitchAndAllowsRetry() = consentTest { fixture ->
        rejectLaunchThenRetry(fixture, ActivityNotFoundException("Injected missing consent activity"))
    }

    @Test fun cancelledResultRestoresOriginalSwitchAndAllowsConsentRetry() = consentTest { fixture ->
        val row = bindRow(fixture)
        val cancelled = launchConsent(fixture, row)
        fixture.environment.replaceBackend(fixture.backend)
        assertTrue(fixture.activity.activityResultRegistry.dispatchResult(cancelled, Activity.RESULT_CANCELED, null))
        settle(fixture.activity)
        row.executePendingBindings()
        assertEquals(Tunnel.State.DOWN, fixture.tunnel.state)
        assertTrue(fixture.backend.upCalls.isEmpty())
        assertFalse(row.tunnelSwitch.isChecked)
        assertTrue(row.tunnelSwitch.isEnabled)

        val retried = launchConsent(fixture, row)
        fixture.environment.replaceBackend(fixture.backend)
        assertTrue(fixture.activity.activityResultRegistry.dispatchResult(retried, Activity.RESULT_OK, null))
        settle(fixture.activity)
        row.executePendingBindings()
        assertEquals(listOf(fixture.tunnel), fixture.backend.upCalls)
        assertTrue(row.tunnelSwitch.isChecked)
        assertTrue(row.tunnelSwitch.isEnabled)
    }

    @Test fun duplicatePositiveResultCannotRepeatActivationAfterRecreation() = consentTest { fixture ->
        val requestCode = launchConsent(fixture)
        fixture.environment.replaceBackend(fixture.backend)
        assertTrue(fixture.activity.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, null))
        settle(fixture.activity)
        assertTrue(fixture.activity.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, null))
        fixture.controller.recreate()
        settle(fixture.activity)
        assertEquals(Tunnel.State.UP, fixture.tunnel.state)
        assertEquals(listOf(fixture.tunnel), fixture.backend.upCalls)
    }

    @Test fun recreatedResultCannotActivateReplacementWithOriginalName() = consentTest { fixture ->
        val requestCode = launchConsent(fixture)
        fixture.environment.replaceBackend(fixture.backend)
        fixture.environment.manager.delete(fixture.tunnel)
        val replacement = fixture.environment.manager.create("original", TunnelTestEnvironment.profile(51821))
        fixture.controller.recreate()
        assertTrue(fixture.activity.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, null))
        settle(fixture.activity)
        assertSame(replacement, fixture.environment.manager.getTunnels()["original"])
        assertEquals(Tunnel.State.DOWN, replacement.state)
        assertEquals(Tunnel.State.DOWN, fixture.environment.backend.getState(replacement))
        assertTrue(fixture.backend.upCalls.isEmpty())
    }

    @Test fun backendFailureSettlesSwitchAndAllowsNewConsentRequest() = consentTest { fixture ->
        val row = bindRow(fixture)
        val failed = launchConsent(fixture, row)
        fixture.environment.replaceBackend(fixture.backend)
        fixture.environment.backend.failUp = true
        assertTrue(fixture.activity.activityResultRegistry.dispatchResult(failed, Activity.RESULT_OK, null))
        settle(fixture.activity)
        row.executePendingBindings()
        assertEquals(Tunnel.State.DOWN, fixture.tunnel.state)
        assertEquals(listOf(fixture.tunnel), fixture.backend.upCalls)
        assertFalse(row.tunnelSwitch.isChecked)
        assertTrue(row.tunnelSwitch.isEnabled)
        val failure = ShadowLog.getLogsForTag("vpn0wn/BaseFragment").single()
        assertEquals(Log.ERROR, failure.type)
        assertTrue(failure.throwable is IOException)
        assertEquals("Injected backend failure", failure.throwable?.message)

        fixture.environment.backend.failUp = false
        val retried = launchConsent(fixture, row)
        fixture.environment.replaceBackend(fixture.backend)
        assertTrue(fixture.activity.activityResultRegistry.dispatchResult(retried, Activity.RESULT_OK, null))
        settle(fixture.activity)
        row.executePendingBindings()
        assertEquals(Tunnel.State.UP, fixture.tunnel.state)
        assertEquals(listOf(fixture.tunnel, fixture.tunnel), fixture.backend.upCalls)
        assertTrue(row.tunnelSwitch.isChecked)
        assertTrue(row.tunnelSwitch.isEnabled)
    }

    @Test fun recreatedConsentPreservesRequestedDownDirection() = consentTest { fixture ->
        fixture.environment.replaceBackend(fixture.backend)
        fixture.tunnel.setStateAsync(Tunnel.State.UP)
        val requestCode = launchConsent(fixture)
        fixture.environment.replaceBackend(fixture.backend)
        fixture.controller.recreate()
        assertTrue(fixture.activity.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, null))
        settle(fixture.activity)
        assertEquals(Tunnel.State.DOWN, fixture.tunnel.state)
        assertEquals(Tunnel.State.DOWN, fixture.environment.backend.getState(fixture.tunnel))
        assertEquals(listOf(fixture.tunnel), fixture.backend.upCalls)
    }

    @Test fun removedFragmentCannotContinueItsConsentRequest() = consentTest { fixture ->
        val requestCode = launchConsent(fixture)
        fixture.environment.replaceBackend(fixture.backend)
        val removed = fixture.fragment
        fixture.activity.supportFragmentManager.beginTransaction().remove(removed).commitNow()
        assertFalse(removed.isAdded)
        fixture.activity.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, null)
        settle(fixture.activity)
        assertEquals(Tunnel.State.DOWN, fixture.tunnel.state)
        assertTrue(fixture.backend.upCalls.isEmpty())
    }

    @Test fun removingFragmentCancelsPreparationSuspendedOnBackendInitialization() = consentTest { fixture ->
        val row = bindRow(fixture)
        val fragment = fixture.fragment
        val activityOwner = fixture.activity.lifecycleScope.coroutineContext[Job]!!
        val fragmentOwner = fragment.lifecycleScope.coroutineContext[Job]!!
        val previousJobs = activityOwner.children.toSet() + fragmentOwner.children.toSet()
        val backendWaiter = CompletableDeferred<Backend>()
        com.vpn0wn.android.Application::class.java.getDeclaredField("futureBackend")
            .apply { isAccessible = true }.set(fixture.environment.application, backendWaiter)
        ShadowVpnService.setPrepareResult(Intent("test.vpn.consent"))
        row.tunnelSwitch.performClick()
        runCurrent()
        val preparation = (activityOwner.children.toList() + fragmentOwner.children.toList())
            .single { it !in previousJobs }
        var completionCause: Throwable? = null
        preparation.invokeOnCompletion { completionCause = it }
        assertTrue(preparation.isActive)
        assertFalse(backendWaiter.isCompleted)
        assertNull(shadowOf(fixture.activity).nextStartedActivityForResult)

        fixture.activity.supportFragmentManager.beginTransaction().remove(fragment).commitNow()
        backendWaiter.complete(GoBackend(fixture.environment.application))
        preparation.join()
        settle(fixture.activity)
        assertTrue("Removed owner must cancel preparation", completionCause is CancellationException)
        assertNull(shadowOf(fixture.activity).nextStartedActivityForResult)
        assertEquals(Tunnel.State.DOWN, fixture.tunnel.state)
        assertTrue(fixture.backend.upCalls.isEmpty())
    }

    @Test fun savedActivityRestorationWithoutRetainedViewModelDoesNotActivate() = consentTest { fixture ->
        val requestCode = launchConsent(fixture)
        fixture.environment.replaceBackend(fixture.backend)
        val original = fixture.fragment
        val saved = Bundle()
        fixture.controller.pause().saveInstanceState(saved).stop().destroy()
        val restored = Robolectric.buildActivity(HostActivity::class.java)
        fixture.controller = restored
        restored.get().setTheme(R.style.AppTheme)
        restored.setup(saved)
        assertNotSame(original, fixture.fragment)
        assertTrue(fixture.activity.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, null))
        settle(fixture.activity)
        assertEquals(Tunnel.State.DOWN, fixture.tunnel.state)
        assertTrue(fixture.backend.upCalls.isEmpty())
    }

    private fun bindRow(fixture: Fixture): TunnelListItemBinding {
        val row = TunnelListItemBinding.inflate(LayoutInflater.from(fixture.activity),
            fixture.fragment.requireView() as ViewGroup, true)
        fixture.rows += row
        row.item = fixture.tunnel
        row.fragment = fixture.fragment
        row.executePendingBindings()
        return row
    }

    private suspend fun TestScope.launchConsent(fixture: Fixture, row: TunnelListItemBinding = bindRow(fixture)): Int {
        val originalState = fixture.tunnel.state
        val priorUpCalls = fixture.backend.upCalls.size
        ShadowVpnService.setPrepareResult(Intent("test.vpn.consent"))
        // GoBackend selects the real consent path; activation crosses an injected backend boundary after launch.
        fixture.environment.replaceBackend(GoBackend(fixture.environment.application))
        val owner = fixture.fragment.lifecycleScope.coroutineContext[Job]!!
        val previousJobs = owner.children.toSet()
        row.tunnelSwitch.performClick()
        val preparation = owner.children.single { it !in previousJobs }
        assertFalse(row.tunnelSwitch.isEnabled)
        settle(fixture.activity, preparation)
        val request = checkNotNull(shadowOf(fixture.activity).nextStartedActivityForResult)
        assertEquals("test.vpn.consent", request.intent.action)
        assertEquals(originalState, fixture.tunnel.state)
        assertEquals(priorUpCalls, fixture.backend.upCalls.size)
        return request.requestCode
    }

    private suspend fun TestScope.rejectLaunchThenRetry(fixture: Fixture, failure: RuntimeException) {
        val row = bindRow(fixture)
        fixture.activity.launchFailure = failure
        ShadowVpnService.setPrepareResult(Intent("test.vpn.consent"))
        fixture.environment.replaceBackend(GoBackend(fixture.environment.application))
        val owner = fixture.fragment.lifecycleScope.coroutineContext[Job]!!
        val previousJobs = owner.children.toSet()
        row.tunnelSwitch.performClick()
        val preparation = owner.children.single { it !in previousJobs }
        assertFalse(row.tunnelSwitch.isEnabled)
        settle(fixture.activity, preparation)
        row.executePendingBindings()
        assertNull(shadowOf(fixture.activity).nextStartedActivityForResult)
        assertEquals(Tunnel.State.DOWN, fixture.tunnel.state)
        assertTrue(fixture.backend.upCalls.isEmpty())
        assertFalse(row.tunnelSwitch.isChecked)
        assertTrue(row.tunnelSwitch.isEnabled)
        val logged = ShadowLog.getLogsForTag("vpn0wn/BaseFragment").single()
        assertEquals(Log.ERROR, logged.type)
        assertSame(failure, logged.throwable)

        fixture.activity.launchFailure = null
        val requestCode = launchConsent(fixture, row)
        fixture.environment.replaceBackend(fixture.backend)
        assertTrue(fixture.activity.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, null))
        settle(fixture.activity)
        row.executePendingBindings()
        assertEquals(listOf(fixture.tunnel), fixture.backend.upCalls)
        assertTrue(row.tunnelSwitch.isChecked)
        assertTrue(row.tunnelSwitch.isEnabled)
    }

    private suspend fun TestScope.settle(activity: HostActivity, preparation: Job? = null) {
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        preparation?.join()
        activity.lifecycleScope.coroutineContext[Job]!!.children.toList().joinAll()
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun consentTest(block: suspend TestScope.(Fixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var environment: TunnelTestEnvironment? = null
        var controller: ActivityController<HostActivity>? = null
        var fixture: Fixture? = null
        try {
            ShadowLog.clear()
            val env = TunnelTestEnvironment(folder.newFolder(), backgroundScope)
            environment = env
            val tunnel = env.manager.create("original", TunnelTestEnvironment.profile())
            val host = Robolectric.buildActivity(HostActivity::class.java)
            controller = host
            host.get().setTheme(R.style.AppTheme)
            host.setup()
            host.get().supportFragmentManager.beginTransaction()
                .add(android.R.id.content, TunnelListFragment(), "home").commitNow()
            val current = Fixture(env, CountingBackend(env.backend), host, tunnel)
            fixture = current
            runCurrent()
            block(current)
        } finally {
            try {
                fixture?.rows?.forEach { it.unbind() }
                (fixture?.controller ?: controller)?.pause()?.stop()?.destroy()
            } finally {
                environment?.close()
                ShadowVpnService.setPrepareResult(null)
                Dispatchers.resetMain()
            }
        }
    }

    private class Fixture(
        val environment: TunnelTestEnvironment,
        val backend: CountingBackend,
        var controller: ActivityController<HostActivity>,
        val tunnel: ObservableTunnel,
    ) {
        val rows = mutableListOf<TunnelListItemBinding>()
        val activity: HostActivity get() = controller.get()
        val fragment: TunnelListFragment get() =
            activity.supportFragmentManager.findFragmentByTag("home") as TunnelListFragment
    }

    private class CountingBackend(private val delegate: Backend) : Backend by delegate {
        val upCalls = CopyOnWriteArrayList<Tunnel>()

        override fun setState(tunnel: Tunnel, state: Tunnel.State, config: TunnelConfig?): Tunnel.State {
            if (state == Tunnel.State.UP) upCalls += tunnel
            return delegate.setState(tunnel, state, config)
        }
    }

    class HostActivity : BaseActivity() {
        var launchFailure: RuntimeException? = null

        // The host accepts selection to exercise BaseActivity's actual Fragment notification path.
        @Suppress("FunctionOnlyReturningConstant")
        override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) = true

        override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
            if (intent.action == "test.vpn.consent") launchFailure?.let { throw it }
            super.startActivityForResult(intent, requestCode, options)
        }
    }
}
