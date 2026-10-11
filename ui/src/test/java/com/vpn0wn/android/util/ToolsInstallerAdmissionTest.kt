package com.vpn0wn.android.util

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ToolsInstallerAdmissionTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun unrelatedAndNearMatchApplicationsCannotEnterPrivilegedInstallation() {
        for (packageName in listOf(
            "org.example.unrelated",
            "com.vpn0wn.unrelated",
            "com.vpn0wn.android.release",
            "com.vpn0wn.android.debug.extra",
            "com.vpn0wn.android.debuggable",
            "com.wireguard.android",
            "com.wireguard.android.debug",
        )) {
            val context = context(packageName)
            val shell = RecordingRootShell(context)
            try {
                ToolsInstaller(context, shell).install()
                fail("Expected package admission refusal")
            } catch (_: SecurityException) {
            }
            assertEquals(0, shell.commands)
            assertFalse(File(context.codeCacheDir, "bin").exists())
        }
    }

    @Test
    fun selectedReleaseAndDebugApplicationsReachSafeConflictObservation() {
        for (packageName in listOf("com.vpn0wn.android", "com.vpn0wn.android.debug")) {
            val context = context(packageName)
            val privateTools = File(context.codeCacheDir, "bin").apply { assertTrue(mkdir()) }
            for (name in listOf("wg", "wg-quick")) File(privateTools, name).writeText("synthetic $name")
            val shell = RecordingRootShell(context)
            assertEquals(ToolsInstaller.BLOCKED, ToolsInstaller(context, shell).install())
            assertEquals(1, shell.commands)
            for (name in listOf("wg", "wg-quick")) {
                assertEquals("synthetic $name", File(privateTools, name).readText())
            }
        }
    }

    private fun context(packageName: String): Context {
        val codeCache = folder.newFolder(packageName)
        return object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getPackageName() = packageName
            override fun getCodeCacheDir() = codeCache
        }
    }

    private class RecordingRootShell(context: Context) : RootShell(context) {
        var commands = 0

        override fun run(output: MutableCollection<String>?, command: String): Int {
            commands++
            return ToolsInstaller.BLOCKED
        }
    }
}
