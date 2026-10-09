package com.wireguard.android.preference

import android.app.Application
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.R
import com.wireguard.android.activity.SettingsActivity
import com.wireguard.android.model.TunnelTestEnvironment
import com.wireguard.android.util.RootShell.RootShellException
import com.wireguard.android.util.RootShell
import com.wireguard.android.util.ToolsInstaller
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
@Config(application = Application::class, sdk = [28], shadows = [ToolsInstallerPreferenceTest.InstallerShadow::class])
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
        com.wireguard.android.Application::class.java.getDeclaredField("toolsInstaller")
            .apply { isAccessible = true }
            .set(env.application, ToolsInstaller(env.application, RootShell(env.application)))
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.AppTheme) }
        InstallerShadow.probeResult = probeResult
        controller.create()
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
            controller.destroy()
            env.application.onTerminate()
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
