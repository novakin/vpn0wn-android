package com.wireguard.android

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class ApplicationBackendLifecycleTest {
    @Test
    fun initializerCancelledBeforeExecutionWithIndependentPackageSettlesBackendWaiters() = runTest {
        if (BuildConfig.BUILD_TYPE == "release") {
            assertFalse("Release startup must exercise non-debug initialization", BuildConfig.DEBUG)
        }
        val application = Application()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getPackageName() = "com.vpn0wn.android"
        }
        Application::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(application, context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.cancel()
        Application::class.java.getDeclaredField("coroutineScope")
            .apply { isAccessible = true }.set(application, scope)
        try {
            application.onCreate()
            Application.getBackend()
            fail("Expected cancelled backend initialization")
        } catch (_: CancellationException) {
        } finally {
            application.onTerminate()
        }
    }
}
