package com.wireguard.android.activity

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.wireguard.android.R
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.GoBackendLifecycleTest
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.backend.WgQuickBackend
import com.wireguard.android.model.TunnelTestEnvironment
import com.wireguard.android.util.RootShell
import com.wireguard.android.util.ToolsInstaller
import com.wireguard.config.Config as WireGuardConfig
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowInstrumentation

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], shadows = [
    SettingsActivityTest.KernelSupport::class,
    SettingsActivityTest.InstalledTools::class,
    GoBackendLifecycleTest.LibraryShadow::class,
    SettingsVpnPolicyTest.NativeVersion::class,
    SettingsVpnPolicyTest.VpnSettingsLaunch::class
])
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsVpnPolicyTest {
    @get:Rule val folder = TemporaryFolder()
    private val controllers = mutableListOf<ActivityController<SettingsActivity>>()
    private val fixtures = mutableListOf<Fixture>()

    @Before fun resetPlatform() {
        SettingsActivityTest.KernelSupport.available = false
        VpnSettingsLaunch.denied = false
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(false)
    }

    @After fun tearDown() {
        controllers.forEach { it.pause().stop().destroy() }
        fixtures.clear()
        Dispatchers.resetMain()
        VpnSettingsLaunch.denied = false
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(false)
    }

    @Test @Config(sdk = [28]) fun olderApiShowsUnavailableWithoutReadingPolicy() = runTest {
        val fixture = environment()
        val activity = openSettings()
        settle(activity)
        assertSetupSummary(activity, R.string.system_vpn_settings_api_unavailable)
        assertEquals(0, fixture.backend.alwaysOnCalls.get())
        assertEquals(0, fixture.backend.lockdownCalls.get())
        assertTrue(preference(activity).isEnabled)
    }

    @Test fun bothDisabledAreObservedForThisApp() = runTest {
        assertObservation(false, false, R.string.system_vpn_settings_both_disabled)
    }

    @Test fun alwaysOnWithoutLockdownIsObservedForThisApp() = runTest {
        assertObservation(true, false, R.string.system_vpn_settings_always_on_enabled)
    }

    @Test fun bothEnabledAreObservedForThisApp() = runTest {
        assertObservation(true, true, R.string.system_vpn_settings_both_enabled)
    }

    @Test fun lockdownWithoutAlwaysOnRemainsUnknown() = runTest {
        assertObservation(false, true, R.string.system_vpn_settings_unknown)
    }

    @Test fun realGoBackendWithoutServiceRemainsUnknownAndStartsNoService() = runTest {
        val fixture = environment()
        @Suppress("UNCHECKED_CAST")
        val serviceReference = GoBackend::class.java.getDeclaredField("vpnServiceRef").apply { isAccessible = true }
            .get(null) as AtomicReference<CompletableFuture<GoBackend.VpnService>>
        val missingService = CompletableFuture<GoBackend.VpnService>()
        val previous = serviceReference.getAndSet(missingService)
        try {
            fixture.env.replaceBackend(GoBackend(fixture.env.application))
            val activity = openSettings()
            settle(activity)
            assertSetupSummary(activity, R.string.system_vpn_settings_service_unavailable)
            assertSame(missingService, serviceReference.get())
            assertFalse(missingService.isDone)
            assertNull(shadowOf(activity).nextStartedService)
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally {
            serviceReference.set(previous)
        }
    }

    @Test fun checkedPolicyFailureShowsUnavailable() = runTest {
        assertReadFailure(ExecutionException(IOException("fixture read failure")), R.string.system_vpn_settings_read_unavailable)
    }

    @Test fun deniedPolicyReadHasDistinctSummary() = runTest {
        assertReadFailure(SecurityException("fixture denied"), R.string.system_vpn_settings_read_denied)
    }

    @Test fun wrappedDeniedPolicyReadHasDistinctSummary() = runTest {
        assertReadFailure(ExecutionException(SecurityException("fixture denied")), R.string.system_vpn_settings_read_denied)
    }

    @Test fun interruptedPolicyReadCancelsWithoutPublishingUnavailable() = runTest {
        assertInterruptedRead(InterruptedException("fixture interrupted"))
    }

    @Test fun wrappedInterruptedPolicyReadCancelsWithoutPublishingUnavailable() = runTest {
        assertInterruptedRead(ExecutionException(InterruptedException("fixture interrupted")))
    }

    @Test fun kernelBackendDisablesUserspacePolicyAction() = runTest {
        val fixture = environment()
        val shell = RootShell(fixture.env.application)
        fixture.env.replaceBackend(WgQuickBackend(fixture.env.application, shell, ToolsInstaller(fixture.env.application, shell)))
        val activity = openSettings()
        settle(activity)
        assertEquals(activity.getString(R.string.system_vpn_settings_kernel_unsupported), preference(activity).summary.toString())
        assertFalse(preference(activity).isEnabled)
        preference(activity).performClick()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(0, fixture.backend.alwaysOnCalls.get())
    }

    @Test fun administratorRestrictionDisablesActionAndRetainsObservation() = runTest {
        val fixture = environment()
        fixture.backend.alwaysOn = true
        fixture.backend.lockdown = true
        setRestricted(fixture.env, true)
        val activity = openSettings()
        settle(activity)
        assertEquals(activity.getString(R.string.system_vpn_settings_restricted_summary,
            activity.getString(R.string.system_vpn_settings_both_enabled)), preference(activity).summary.toString())
        assertFalse(preference(activity).isEnabled)
        preference(activity).performClick()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun restrictionAddedAfterRefreshIsCheckedBeforeLaunch() = runTest {
        val fixture = environment()
        val activity = openSettings()
        settle(activity)
        assertTrue(preference(activity).isEnabled)
        setRestricted(fixture.env, true)
        preference(activity).performClick()
        settle(activity)
        assertFalse(preference(activity).isEnabled)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFeedback(activity, R.string.system_vpn_settings_admin_restricted)
    }

    @Test fun actionOpensPublicSettingsWithoutPersistingOrMutatingTunnel() = runTest {
        val fixture = environment()
        val activity = openSettings()
        settle(activity)
        val before = withContext(Dispatchers.IO) { com.wireguard.android.Application.getPreferencesDataStore().data.first() }
        val action = preference(activity)
        assertFalse(action.isPersistent)
        action.performClick()
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_VPN_SETTINGS, intent.action)
        assertNull(intent.component)
        assertNull(intent.extras)
        assertNull(shadowOf(activity).nextStartedService)
        assertEquals(0, fixture.backend.stateWrites.get())
        assertTrue(fixture.env.store.configs.isEmpty())
        val after = withContext(Dispatchers.IO) { com.wireguard.android.Application.getPreferencesDataStore().data.first() }
        assertEquals(before, after)
        assertFalse(after.asMap().keys.any { it.name == "system_vpn_settings" })
    }

    @Test fun missingSystemActivityReportsUnavailable() = runTest {
        environment()
        val activity = openSettings()
        settle(activity)
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
        preference(activity).performClick()
        assertFeedback(activity, R.string.system_vpn_settings_activity_unavailable)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(preference(activity).isEnabled)
    }

    @Test fun deniedSystemLaunchReportsDenied() = runTest {
        environment()
        val activity = openSettings()
        settle(activity)
        VpnSettingsLaunch.denied = true
        preference(activity).performClick()
        assertFeedback(activity, R.string.system_vpn_settings_launch_denied)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun returningToForegroundRefreshesPolicyObservation() = runTest {
        val fixture = environment()
        val activity = openSettings()
        settle(activity)
        assertSetupSummary(activity, R.string.system_vpn_settings_both_disabled)
        controllers.last().pause()
        fixture.backend.alwaysOn = true
        fixture.backend.lockdown = true
        controllers.last().resume()
        settle(activity)
        assertSetupSummary(activity, R.string.system_vpn_settings_both_enabled)
        assertEquals(2, fixture.backend.alwaysOnCalls.get())
        assertEquals(2, fixture.backend.lockdownCalls.get())
    }

    @Test fun pauseCancelsBlockedReadAndResumePublishesOnlyFreshObservation() = runTest {
        val fixture = environment()
        val blocked = BlockedRead(true)
        fixture.backend.alwaysOnReader = blocked::read
        fixture.backend.lockdown = true
        val activity = openSettings()
        runCurrent()
        assertTrue(blocked.entered.await(5, TimeUnit.SECONDS))
        val action = preference(activity)
        try {
            controllers.last().pause()
        } finally {
            blocked.release.countDown()
        }
        assertTrue(blocked.finished.await(5, TimeUnit.SECONDS))
        settle(activity)
        assertEquals(activity.getString(R.string.system_vpn_settings_checking), action.summary.toString())
        assertFalse(action.isEnabled)
        fixture.backend.alwaysOnReader = null
        fixture.backend.lockdown = false
        controllers.last().resume()
        settle(activity)
        assertSetupSummary(activity, R.string.system_vpn_settings_both_disabled)
    }

    @Test fun destroyedViewRejectsLateReadWithoutChangingReplacement() = runTest {
        val fixture = environment()
        val blocked = BlockedRead(true)
        fixture.backend.alwaysOnReader = blocked::read
        fixture.backend.lockdown = true
        val activity = openSettings()
        runCurrent()
        assertTrue(blocked.entered.await(5, TimeUnit.SECONDS))
        val previous = fragment(activity)
        val action = preference(activity)
        val reads = previous.viewLifecycleOwner.lifecycleScope.coroutineContext[Job]!!.children.toList()
        try {
            fixture.backend.alwaysOnReader = null
            fixture.backend.lockdown = false
            activity.supportFragmentManager.beginTransaction()
                .replace(R.id.settings_content, SettingsActivity.SettingsFragment()).commitNow()
            assertNull(previous.view)
        } finally {
            blocked.release.countDown()
        }
        assertTrue(blocked.finished.await(5, TimeUnit.SECONDS))
        reads.joinAll()
        settle(activity)
        assertEquals(activity.getString(R.string.system_vpn_settings_checking), action.summary.toString())
        assertNotSame(action, preference(activity))
        assertSetupSummary(activity, R.string.system_vpn_settings_both_disabled)
    }

    private suspend fun TestScope.assertObservation(alwaysOn: Boolean, lockdown: Boolean, summary: Int) {
        val fixture = environment()
        fixture.backend.alwaysOn = alwaysOn
        fixture.backend.lockdown = lockdown
        val activity = openSettings()
        settle(activity)
        assertSetupSummary(activity, summary)
        assertTrue(preference(activity).isEnabled)
        assertEquals(1, fixture.backend.alwaysOnCalls.get())
        assertEquals(1, fixture.backend.lockdownCalls.get())
        assertEquals(listOf(false, false), fixture.backend.readsOnMain)
    }

    private suspend fun TestScope.assertReadFailure(failure: Exception, summary: Int) {
        val fixture = environment()
        fixture.backend.failure = failure
        val activity = openSettings()
        settle(activity)
        assertSetupSummary(activity, summary)
        assertTrue(preference(activity).isEnabled)
        assertNull(shadowOf(activity).nextStartedService)
    }

    private suspend fun TestScope.assertInterruptedRead(failure: Exception) {
        val fixture = environment()
        fixture.backend.failure = failure
        val activity = openSettings()
        settle(activity)
        assertEquals(activity.getString(R.string.system_vpn_settings_checking), preference(activity).summary.toString())
        assertFalse(preference(activity).isEnabled)
    }

    private fun TestScope.environment(): Fixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.root, backgroundScope)
        val shell = RootShell(env.application)
        for ((name, value) in mapOf("rootShell" to shell, "toolsInstaller" to ToolsInstaller(env.application, shell))) {
            com.wireguard.android.Application::class.java.getDeclaredField(name).apply { isAccessible = true }.set(env.application, value)
        }
        val backend = PolicyBackend(env.backend)
        env.replaceBackend(backend)
        setRestricted(env, false)
        return Fixture(env, backend).also { fixtures += it }
    }

    private fun openSettings(): SettingsActivity {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        controllers += controller
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
        controller.get().supportFragmentManager.executePendingTransactions()
        return controller.get()
    }

    private suspend fun TestScope.settle(activity: SettingsActivity) {
        activity.supportFragmentManager.executePendingTransactions()
        runCurrent()
        fragment(activity).lifecycleScope.coroutineContext[Job]!!.children.toList().joinAll()
        fragment(activity).viewLifecycleOwner.lifecycleScope.coroutineContext[Job]!!.children.toList().joinAll()
        activity.lifecycleScope.coroutineContext[Job]!!.children.toList().joinAll()
        runCurrent()
    }

    private fun fragment(activity: SettingsActivity) =
        activity.supportFragmentManager.findFragmentById(R.id.settings_content) as SettingsActivity.SettingsFragment

    private fun preference(activity: SettingsActivity) = fragment(activity).findPreference<Preference>("system_vpn_settings")!!

    private fun assertSetupSummary(activity: SettingsActivity, summary: Int) {
        assertEquals(activity.getString(R.string.system_vpn_settings_setup_summary, activity.getString(summary)),
            preference(activity).summary.toString())
    }

    private fun assertFeedback(activity: SettingsActivity, message: Int) {
        shadowOf(Looper.getMainLooper()).idle()
        val feedback = activity.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        assertNotNull(feedback)
        assertEquals(activity.getString(message), feedback.text.toString())
    }

    private fun setRestricted(env: TunnelTestEnvironment, restricted: Boolean) {
        val manager = env.application.getSystemService(UserManager::class.java)!!
        shadowOf(manager).setUserRestriction(Process.myUserHandle(), UserManager.DISALLOW_CONFIG_VPN, restricted)
    }

    private data class Fixture(val env: TunnelTestEnvironment, val backend: PolicyBackend)

    private class PolicyBackend(delegate: Backend) : Backend by delegate {
        @Volatile var alwaysOn = false
        @Volatile var lockdown = false
        @Volatile var failure: Exception? = null
        @Volatile var alwaysOnReader: (() -> Boolean)? = null
        val alwaysOnCalls = AtomicInteger()
        val lockdownCalls = AtomicInteger()
        val stateWrites = AtomicInteger()
        val readsOnMain = CopyOnWriteArrayList<Boolean>()

        override fun isAlwaysOn(): Boolean {
            alwaysOnCalls.incrementAndGet()
            readsOnMain += Looper.myLooper() == Looper.getMainLooper()
            failure?.let { throw it }
            return alwaysOnReader?.invoke() ?: alwaysOn
        }

        override fun isLockdownEnabled(): Boolean {
            lockdownCalls.incrementAndGet()
            readsOnMain += Looper.myLooper() == Looper.getMainLooper()
            return lockdown
        }

        override fun setState(tunnel: Tunnel, state: Tunnel.State, config: WireGuardConfig?): Tunnel.State {
            stateWrites.incrementAndGet()
            error("Policy observation must not mutate tunnel state")
        }
    }

    private class BlockedRead(private val result: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)

        fun read(): Boolean {
            entered.countDown()
            try {
                check(release.await(5, TimeUnit.SECONDS)) { "Policy fixture timed out" }
                return result
            } finally {
                finished.countDown()
            }
        }
    }

    @Implements(value = GoBackend::class, isInAndroidSdk = false)
    // Robolectric instantiates shadow classes; this shadows the native static version entry point without loading JNI.
    @Suppress("UtilityClassWithPublicConstructor")
    class NativeVersion {
        companion object {
            // The native version entry point needs a callable constant result in the JVM fixture.
            @Suppress("FunctionOnlyReturningConstant")
            @JvmStatic @Implementation fun wgVersion() = "fixture"
        }
    }

    @Implements(Instrumentation::class)
    class VpnSettingsLaunch : ShadowInstrumentation() {
        @Implementation
        override fun execStartActivity(who: Context, contextThread: IBinder?, token: IBinder?, target: Activity?,
            intent: Intent, requestCode: Int, options: Bundle?): Instrumentation.ActivityResult? {
            if (denied && intent.action == Settings.ACTION_VPN_SETTINGS) throw SecurityException("fixture denied")
            return super.execStartActivity(who, contextThread, token, target, intent, requestCode, options)
        }

        companion object {
            var denied = false
        }
    }
}
