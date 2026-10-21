/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.activity

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.vpn0wn.android.R
import com.vpn0wn.android.backend.GoBackend
import com.vpn0wn.android.backend.GoBackendLifecycleTest
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.databinding.TvActivityBinding
import com.vpn0wn.android.databinding.TvTunnelListItemBinding
import com.vpn0wn.android.model.ObservableTunnel
import com.vpn0wn.android.model.VpnPermissionViewModel
import com.vpn0wn.android.model.TunnelTestEnvironment
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
import org.robolectric.shadows.ShadowVpnService

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28], shadows = [GoBackendLifecycleTest.LibraryShadow::class])
@OptIn(ExperimentalCoroutinesApi::class)
class TvVpnConsentTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun approvedResultUsesOriginalTunnelOnceAfterRecreation() = withFixture { fixture ->
        val code = launchConsent(fixture)
        fixture.env.replaceBackend(fixture.env.backend)
        fixture.controller.recreate()
        runCurrent()
        deliver(fixture, code, Activity.RESULT_OK)
        assertEquals(Tunnel.State.UP, fixture.tunnel.state)
        fixture.tunnel.setStateAsync(Tunnel.State.DOWN)
        deliver(fixture, code, Activity.RESULT_OK)
        assertEquals(Tunnel.State.DOWN, fixture.tunnel.state)
    }

    @Test fun deniedConsentDoesNotActivateAndAllowsRetry() = withFixture { fixture ->
        val code = launchConsent(fixture)
        fixture.env.replaceBackend(fixture.env.backend)
        deliver(fixture, code, Activity.RESULT_CANCELED)
        assertEquals(Tunnel.State.DOWN, fixture.tunnel.state)
        assertTrue(fixture.env.backend.appliedConfigs.isEmpty())
        val retry = launchConsent(fixture)
        fixture.env.replaceBackend(fixture.env.backend)
        deliver(fixture, retry, Activity.RESULT_OK)
        assertEquals(Tunnel.State.UP, fixture.tunnel.state)
    }

    @Test fun pendingApprovalPreservesRequestedDirectionWhenTunnelStateChanges() = withFixture { fixture ->
        val code = launchConsent(fixture)
        fixture.env.replaceBackend(fixture.env.backend)
        fixture.tunnel.setStateAsync(Tunnel.State.UP)
        deliver(fixture, code, Activity.RESULT_OK)
        assertEquals(Tunnel.State.UP, fixture.tunnel.state)
    }

    @Test fun deletedTargetCannotBecomeSameNameReplacement() = withFixture { fixture ->
        val code = launchConsent(fixture)
        fixture.env.replaceBackend(fixture.env.backend)
        fixture.tunnel.deleteAsync()
        val replacement = fixture.env.manager.create("original", TunnelTestEnvironment.profile(51821))
        deliver(fixture, code, Activity.RESULT_OK)
        assertEquals(Tunnel.State.DOWN, replacement.state)
        assertTrue(fixture.env.backend.appliedConfigs.isEmpty())
    }

    @Test fun savedStateWithoutRetainedOwnerDoesNotActivate() = withFixture { fixture ->
        val code = launchConsent(fixture)
        fixture.env.replaceBackend(fixture.env.backend)
        val saved = Bundle()
        fixture.controller.pause().saveInstanceState(saved).stop().destroy()
        fixture.controller = newController()
        fixture.controller.setup(saved)
        runCurrent()
        deliver(fixture, code, Activity.RESULT_OK)
        assertEquals(Tunnel.State.DOWN, fixture.tunnel.state)
        assertTrue(fixture.env.backend.appliedConfigs.isEmpty())
    }

    @Test fun deniedLaunchClearsRequestAndAllowsRetry() = withFixture { fixture ->
        rejectLaunchThenRetry(fixture, SecurityException("Injected launch refusal"))
    }

    @Test fun missingConsentActivityClearsRequestAndAllowsRetry() = withFixture { fixture ->
        rejectLaunchThenRetry(fixture, ActivityNotFoundException("Injected missing consent activity"))
    }

    private suspend fun TestScope.rejectLaunchThenRetry(fixture: Fixture, failure: RuntimeException) {
        val field = TvMainActivity::class.java.getDeclaredField("permissionActivityResultLauncher").apply { isAccessible = true }
        val original = field.get(fixture.activity)
        field.set(fixture.activity, object : ActivityResultLauncher<Intent>() {
            override fun launch(input: Intent, options: ActivityOptionsCompat?) = throw failure
            override fun unregister() = Unit
            override val contract = ActivityResultContracts.StartActivityForResult()
        })
        clickConsent(fixture)
        assertNull(shadowOf(fixture.activity).nextStartedActivityForResult)
        assertNull(ViewModelProvider(fixture.activity)[VpnPermissionViewModel::class.java].request)
        assertEquals(Tunnel.State.DOWN, fixture.tunnel.state)
        assertTrue(fixture.env.backend.appliedConfigs.isEmpty())
        field.set(fixture.activity, original)
        val code = launchConsent(fixture)
        fixture.env.replaceBackend(fixture.env.backend)
        deliver(fixture, code, Activity.RESULT_OK)
        assertEquals(Tunnel.State.UP, fixture.tunnel.state)
    }

    private suspend fun TestScope.launchConsent(fixture: Fixture): Int {
        clickConsent(fixture)
        return checkNotNull(shadowOf(fixture.activity).nextStartedActivityForResult).requestCode
    }

    private suspend fun TestScope.clickConsent(fixture: Fixture) {
        val content = fixture.activity.findViewById<ViewGroup>(android.R.id.content)
        val screen = checkNotNull(DataBindingUtil.getBinding<TvActivityBinding>(content.getChildAt(0)))
        val row = TvTunnelListItemBinding.inflate(LayoutInflater.from(fixture.activity), content, false)
        fixture.rows += row
        row.item = fixture.tunnel
        screen.tunnelRowConfigurationHandler!!.onConfigureRow(row, fixture.tunnel, 0)
        row.executePendingBindings()
        ShadowVpnService.setPrepareResult(Intent("test.vpn.consent"))
        fixture.env.replaceBackend(GoBackend(fixture.env.application))
        val previous = fixture.activity.lifecycleScope.coroutineContext[Job]!!.children.toSet()
        row.root.performClick()
        runCurrent()
        fixture.activity.lifecycleScope.coroutineContext[Job]!!.children.filter { it !in previous }.toList().joinAll()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private suspend fun TestScope.deliver(fixture: Fixture, code: Int, result: Int) {
        val previous = fixture.activity.lifecycleScope.coroutineContext[Job]!!.children.toSet()
        assertTrue(fixture.activity.activityResultRegistry.dispatchResult(code, result, null))
        runCurrent()
        fixture.activity.lifecycleScope.coroutineContext[Job]!!.children.filter { it !in previous }.toList().joinAll()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun newController(): ActivityController<TvMainActivity> =
        Robolectric.buildActivity(TvMainActivity::class.java).also { it.get().setTheme(R.style.AppTheme) }

    private fun withFixture(block: suspend TestScope.(Fixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.newFolder(), backgroundScope)
        val fixture = Fixture(env, newController(), env.manager.create("original", TunnelTestEnvironment.profile()))
        try {
            fixture.controller.setup()
            runCurrent()
            block(fixture)
        } finally {
            fixture.rows.forEach { it.unbind() }
            fixture.controller.pause().stop().destroy()
            env.close()
            ShadowVpnService.setPrepareResult(null)
            Dispatchers.resetMain()
        }
    }

    private class Fixture(
        val env: TunnelTestEnvironment,
        var controller: ActivityController<TvMainActivity>,
        val tunnel: ObservableTunnel,
    ) {
        val rows = mutableListOf<TvTunnelListItemBinding>()
        val activity: TvMainActivity get() = controller.get()
    }
}
