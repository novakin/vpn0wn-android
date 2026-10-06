package com.wireguard.android.preference

import android.app.Application
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.R
import com.wireguard.android.activity.SettingsActivity
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.model.TunnelTestEnvironment
import com.wireguard.android.util.UserKnobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class KernelModuleEnablerPreferenceTest {
    @get:Rule val folder = TemporaryFolder()
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun failedShutdownRestoresUsableControlAndPreviousSetting() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.root, backgroundScope)
        val tunnel = env.manager.create("one", TunnelTestEnvironment.profile())
        tunnel.setStateAsync(Tunnel.State.UP)
        env.backend.failDown = true
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.AppTheme) }
        controller.create()
        val preference = KernelModuleEnablerPreference(activity, null)
        runCurrent()
        assertTrue(preference.isVisible)
        try {
            KernelModuleEnablerPreference::class.java.getDeclaredMethod("onClick")
                .apply { isAccessible = true }.invoke(preference)
            assertFalse(preference.isEnabled)
            val switching = activity.lifecycleScope.coroutineContext[Job]!!.children.toList()
            for (job in switching) job.join()
            assertTrue(preference.isEnabled)
            assertTrue(preference.isVisible)
            assertFalse(UserKnobs.enableKernelModule.first())
            assertEquals(Tunnel.State.UP, tunnel.state)
            assertEquals(Tunnel.State.UP, env.backend.getState(tunnel))
        } finally {
            controller.destroy()
        }
    }
}
