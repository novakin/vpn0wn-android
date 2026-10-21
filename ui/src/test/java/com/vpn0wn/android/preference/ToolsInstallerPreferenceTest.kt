package com.vpn0wn.android.preference

import android.app.Application
import androidx.lifecycle.lifecycleScope
import com.vpn0wn.android.R
import com.vpn0wn.android.activity.SettingsActivity
import com.vpn0wn.android.backend.WgQuickBackend
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.android.util.RootShell.RootShellException
import com.vpn0wn.android.util.RootShell
import com.vpn0wn.android.util.ToolsInstaller
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [
    ToolsInstallerPreferenceTest.InstallerShadow::class,
    ToolsInstallerPreferenceTest.KernelSupportShadow::class
])
class ToolsInstallerPreferenceTest {
    @get:Rule val folder = TemporaryFolder()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        InstallerShadow.probeResult = ToolsInstaller.NO or ToolsInstaller.MAGISK
        InstallerShadow.installResult = ToolsInstaller.YES or ToolsInstaller.MAGISK
        InstallerShadow.probeFailure = null
        InstallerShadow.installFailure = null
    }

    @Test
    fun compatibleToolsDisableInstallWithoutClaimingAReboot() = checkPreference(
        ToolsInstaller.YES or ToolsInstaller.SYSTEM, R.string.tools_installer_already, false
    )

    @Test
    fun stagedOwnModuleShowsRebootInsteadOfAnotherInstall() = checkPreference(
        ToolsInstaller.YES or ToolsInstaller.MAGISK, R.string.tools_installer_success_magisk, false
    )

    @Test
    fun conflictDisablesWritesWithExplicitRefusal() = checkPreference(
        ToolsInstaller.BLOCKED, R.string.tools_installer_blocked, false
    )

    @Test
    fun freeMagiskPathsAllowInstallation() = checkPreference(
        ToolsInstaller.NO or ToolsInstaller.MAGISK, R.string.tools_installer_initial_magisk, true
    )

    @Test
    fun observationFailureAllowsRetryWithoutOfferingSystemWrites() = checkPreference(
        ToolsInstaller.ERROR, R.string.tools_installer_probe_failure, true
    )

    @Test
    fun rootDenialAllowsRetryWithoutClaimingPathsAreFree() {
        InstallerShadow.probeFailure = RootShellException(RootShellException.Reason.NO_ROOT_ACCESS)
        checkPreference(ToolsInstaller.NO or ToolsInstaller.MAGISK, R.string.tools_installer_probe_failure, true)
    }

    @Test
    fun changedConflictAtInstallDisablesAnotherAttempt() = checkPreference(
        ToolsInstaller.NO or ToolsInstaller.MAGISK, R.string.tools_installer_blocked, false,
        ToolsInstaller.BLOCKED
    )

    @Test
    fun compatiblePairFoundAtInstallDoesNotReportSystemMutation() = checkPreference(
        ToolsInstaller.NO or ToolsInstaller.MAGISK, R.string.tools_installer_already, false,
        ToolsInstaller.YES or ToolsInstaller.SYSTEM
    )

    @Test
    fun installIoFailureRestoresRetry() {
        InstallerShadow.installFailure = IOException("fixture observation failure")
        checkPreference(ToolsInstaller.NO or ToolsInstaller.MAGISK, R.string.tools_installer_failure, true,
            ToolsInstaller.ERROR)
    }

    private fun checkPreference(probeResult: Int, summary: Int, enabled: Boolean, installResult: Int? = null) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.root, backgroundScope)
        com.vpn0wn.android.Application::class.java.getDeclaredField("toolsInstaller")
            .apply { isAccessible = true }
            .set(env.application, ToolsInstaller(env.application, RootShell(env.application)))
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.AppTheme) }
        InstallerShadow.probeResult = probeResult
        controller.create()
        activity.supportFragmentManager.executePendingTransactions()
        val ownerJobs = buildList {
            add(activity.lifecycleScope.coroutineContext[Job]!!)
            for (fragment in activity.supportFragmentManager.fragments) {
                add(fragment.lifecycleScope.coroutineContext[Job]!!)
                fragment.viewLifecycleOwnerLiveData.value?.let {
                    add(it.lifecycleScope.coroutineContext[Job]!!)
                }
            }
        }
        val preference = ToolsInstallerPreference(activity, null)
        runCurrent()
        try {
            preference.onAttached()
            runCurrent()
            activity.lifecycleScope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
            if (installResult != null) {
                InstallerShadow.installResult = installResult
                ToolsInstallerPreference::class.java.getDeclaredMethod("onClick")
                    .apply { isAccessible = true }.invoke(preference)
                assertEquals(false, preference.isEnabled)
                assertEquals(activity.getString(R.string.tools_installer_working), preference.summary)
                runCurrent()
                activity.lifecycleScope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
            }
            assertEquals(enabled, preference.isEnabled)
            assertEquals(activity.getString(summary), preference.summary)
        } finally {
            withContext(NonCancellable) {
                controller.destroy()
                ownerJobs.forEach { it.cancelAndJoin() }
                env.application.onTerminate()
                env.applicationScope.coroutineContext[Job]!!.join()
            }
        }
    }

    // Robolectric must instantiate this shadow and call its fixed static kernel probe.
    @Suppress("UtilityClassWithPublicConstructor", "FunctionOnlyReturningConstant")
    @Implements(WgQuickBackend::class)
    class KernelSupportShadow {
        companion object {
            @JvmStatic
            @Implementation
            fun hasKernelSupport(): Boolean = false
        }
    }

    @Implements(ToolsInstaller::class)
    class InstallerShadow {
        @Implementation
        fun areInstalled(): Int {
            probeFailure?.let { throw it }
            return probeResult
        }

        @Implementation
        fun install(): Int {
            installFailure?.let { throw it }
            return installResult
        }

        companion object {
            var probeResult = ToolsInstaller.NO or ToolsInstaller.MAGISK
            var installResult = ToolsInstaller.YES or ToolsInstaller.MAGISK
            var probeFailure: Exception? = null
            var installFailure: Exception? = null
        }
    }
}
