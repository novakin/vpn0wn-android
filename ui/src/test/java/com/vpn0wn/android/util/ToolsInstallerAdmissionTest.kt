package com.vpn0wn.android.util

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ToolsInstallerAdmissionTest {
    @Test
    fun renamedApplicationsCannotEnterLegacyPrivilegedInstallation() {
        for (packageName in listOf("com.vpn0wn.android", "com.vpn0wn.android.debug")) {
            val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
                override fun getPackageName() = packageName
            }
            val shell = RecordingRootShell(context)
            try {
                ToolsInstaller(context, shell).install()
                fail("Expected legacy installation refusal")
            } catch (_: SecurityException) {
            }
            assertEquals(0, shell.commands)
        }
    }

    private class RecordingRootShell(context: Context) : RootShell(context) {
        var commands = 0

        override fun run(output: MutableCollection<String>?, command: String): Int {
            commands++
            return 0
        }
    }
}
