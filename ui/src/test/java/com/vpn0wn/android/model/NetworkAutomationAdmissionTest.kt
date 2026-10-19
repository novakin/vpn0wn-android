/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.model

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.util.UserKnobs
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.vpn0wn.android.Application as VpnApplication

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NetworkAutomationAdmissionTest {
    @get:Rule val folder = TemporaryFolder()
    @After fun resetMain() = Dispatchers.resetMain()

    private fun TestScope.environment(): TunnelTestEnvironment {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return TunnelTestEnvironment(folder.root, backgroundScope)
    }

    @Test fun disabledOrdinaryControlsCreateNoAutomationRecord() = runTest {
        val env = environment()
        try {
            val tunnel = env.manager.create("fixture", TunnelTestEnvironment.profile())
            env.manager.setTunnelState(tunnel, Tunnel.State.UP)
            env.manager.setTunnelState(tunnel, Tunnel.State.DOWN)
            assertNull(VpnApplication.getPreferencesDataStore().data.first()[stringPreferencesKey("network_automation_v1")])
        } finally { env.close() }
    }

    @Test fun failedRequiredManualReceiptAdmitsNoBackendReadOrMutation() = runTest {
        val env = environment()
        try {
            val tunnel = env.manager.create("fixture", TunnelTestEnvironment.profile())
            NetworkAutomationState.update { it.copy(enabled = true, episode = 1, defaultName = tunnel.name, resolved = true) }
            val delegate = VpnApplication.getPreferencesDataStore()
            env.replacePreferences(object : DataStore<Preferences> {
                override val data = delegate.data
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw IOException("Receipt write failed")
            })
            env.backend.blockStateRead = true
            try {
                env.manager.setTunnelState(tunnel, Tunnel.State.UP)
                fail("Expected required receipt failure")
            } catch (_: IOException) { }
            assertEquals(1L, env.backend.stateReadEntered.count)
            assertTrue(env.backend.appliedConfigs.isEmpty())
            assertEquals(Tunnel.State.DOWN, tunnel.state)
        } finally {
            env.backend.releaseStateRead.countDown()
            env.close()
        }
    }

    @Test fun manualCutIncludesReceivedUnprocessedObservationsBeforeBackendReadAndFailure() = runTest {
        val env = environment()
        try {
            val tunnel = env.manager.create("fixture", TunnelTestEnvironment.profile())
            NetworkAutomationState.update { it.copy(enabled = true, episode = 1, defaultName = tunnel.name, resolved = true) }
            val frontier = env.manager.networkAutomation.nextSequence()
            env.backend.blockStateRead = true
            env.backend.failUp = true
            val operation = async { runCatching { env.manager.setTunnelState(tunnel, Tunnel.State.UP) }.exceptionOrNull() }
            runCurrent()
            withContext(Dispatchers.IO) { assertTrue(env.backend.stateReadEntered.await(5, TimeUnit.SECONDS)) }
            val admitted = NetworkAutomationState.read(VpnApplication.getPreferencesDataStore().data.first())
            assertEquals(frontier, admitted.manual?.frontier)
            assertEquals(NetworkAutomationState.Outcome.PENDING, admitted.manual?.outcome)
            assertEquals("UP", admitted.manual?.absolute)
            env.backend.releaseStateRead.countDown()
            assertTrue(operation.await() is IOException)
            val failed = NetworkAutomationState.read(VpnApplication.getPreferencesDataStore().data.first())
            assertEquals(NetworkAutomationState.Outcome.FAILED, failed.manual?.outcome)
            assertTrue(failed.suppressesRestore(tunnel.name))
            assertEquals(Tunnel.State.DOWN, tunnel.state)
            assertNull(tunnel.transitionTarget)
            assertTrue(UserKnobs.runningTunnels.first().isEmpty())
        } finally {
            env.backend.releaseStateRead.countDown()
            env.close()
        }
    }

    @Test fun successfulSameObjectConfigEditResolvesDefaultButRetainsFencedManualIntent() = runTest {
        val env = environment()
        try {
            val tunnel = env.manager.create("fixture", TunnelTestEnvironment.profile())
            val receipt = NetworkAutomationState.Receipt(tunnel.name, "UP", "UP", 1, 1, 0, 1, NetworkAutomationState.Outcome.UP)
            NetworkAutomationState.update { it.copy(enabled = true, episode = 1, defaultName = tunnel.name, resolved = true, manual = receipt) }
            env.manager.setTunnelConfig(tunnel, TunnelTestEnvironment.profile(51821))
            val state = NetworkAutomationState.read(VpnApplication.getPreferencesDataStore().data.first())
            assertTrue(state.resolved)
            assertEquals(NetworkAutomationState.Outcome.UNRESOLVED, state.manual?.outcome)
            assertTrue(state.suppressesRestore(tunnel.name))
        } finally { env.close() }
    }

    @Test fun failedConfigSaveRetainsDurableUnresolvedFence() = runTest {
        val env = environment()
        try {
            val tunnel = env.manager.create("fixture", TunnelTestEnvironment.profile())
            NetworkAutomationState.update { it.copy(enabled = true, episode = 1, defaultName = tunnel.name, resolved = true) }
            env.store.failSave = true
            try {
                env.manager.setTunnelConfig(tunnel, TunnelTestEnvironment.profile(51821))
                fail("Expected config replacement failure")
            } catch (_: IOException) { }
            val state = NetworkAutomationState.read(VpnApplication.getPreferencesDataStore().data.first())
            assertFalse(state.resolved)
            assertTrue(state.suppressesRestore(tunnel.name))
        } finally { env.close() }
    }

    @Test fun resumePreservesManualAndFailedAttemptAndDisableRetiresBoth() = runTest {
        val env = environment()
        try {
            val tunnel = env.manager.create("fixture", TunnelTestEnvironment.profile())
            val receipt = NetworkAutomationState.Receipt(tunnel.name, "UP", "UP", 1, 1, 0, 1, NetworkAutomationState.Outcome.FAILED)
            NetworkAutomationState.update { it.copy(enabled = true, episode = 1, defaultName = tunnel.name, resolved = true,
                paused = true, manual = receipt, attempt = receipt) }
            env.manager.networkAutomation.resume()
            val resumed = NetworkAutomationState.read(VpnApplication.getPreferencesDataStore().data.first())
            assertFalse(resumed.paused)
            assertEquals(receipt, resumed.manual)
            assertEquals(receipt, resumed.attempt)
            env.manager.networkAutomation.disable()
            val disabled = NetworkAutomationState.read(VpnApplication.getPreferencesDataStore().data.first())
            assertFalse(disabled.enabled)
            assertNull(disabled.manual)
            assertNull(disabled.attempt)
            assertFalse(disabled.suppressesRestore(tunnel.name))
        } finally { env.close() }
    }
}
