package com.wireguard.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class ApplicationBackendLifecycleTest {
    @Test
    fun initializerCancelledBeforeExecutionSettlesBackendWaiters() = runTest {
        val application = Application()
        Application::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(application, ApplicationProvider.getApplicationContext<Context>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.cancel()
        Application::class.java.getDeclaredField("coroutineScope")
            .apply { isAccessible = true }.set(application, scope)
        application.onCreate()
        try {
            Application.getBackend()
            fail("Expected cancelled backend initialization")
        } catch (_: CancellationException) {
        } finally {
            application.onTerminate()
        }
    }
}
