/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.model

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.vpn0wn.android.backend.GoBackend
import com.vpn0wn.android.backend.GoBackendLifecycleTest
import com.vpn0wn.android.util.AppDataTransfer
import com.vpn0wn.android.util.UserKnobs
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.vpn0wn.android.Application as VpnApplication

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [GoBackendLifecycleTest.LibraryShadow::class])
class NetworkAutomationPrerequisiteTest {
    @get:Rule val folder = TemporaryFolder()

    @After fun resetMain() = Dispatchers.resetMain()

    private fun TestScope.environment(): TunnelTestEnvironment {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return TunnelTestEnvironment(folder.root, backgroundScope)
    }

    @Test fun enabledMetadataOnlyImportCannotEnableMultipleTunnels() = runTest {
        val env = environment()
        try {
            val tunnel = env.manager.create("fixture", TunnelTestEnvironment.profile())
            val expected = NetworkAutomationState.update {
                it.copy(enabled = true, episode = 1, defaultName = tunnel.name, resolved = true)
            }
            val original = tunnel.getConfigAsync()
            val archive = AppDataTransfer.Archive(mapOf(tunnel.name to original),
                mapOf(UserKnobs.MULTIPLE_TUNNELS.name to true), null, emptyList())
            try {
                env.manager.importAppData(archive)
                fail("Expected enabled-automation prerequisite rejection")
            } catch (_: IOException) { }
            assertFalse(UserKnobs.multipleTunnels.first())
            assertEquals(expected, state())
            assertSame(tunnel, env.manager.getTunnels()[tunnel.name])
            assertSame(original, env.store.configs[tunnel.name])
            assertTrue(env.backend.appliedConfigs.isEmpty())
        } finally { env.close() }
    }

    @Test fun enabledIdenticalImportWithSingleTunnelSettingRetainsReceiptsAndBinding() = runTest {
        val env = environment()
        try {
            val tunnel = env.manager.create("fixture", TunnelTestEnvironment.profile())
            val manual = NetworkAutomationState.Receipt(tunnel.name, "DOWN", "DOWN", 0, 0, 0, 1,
                NetworkAutomationState.Outcome.DOWN)
            val expected = NetworkAutomationState.update {
                it.copy(enabled = true, episode = 1, defaultName = tunnel.name, resolved = true, manual = manual, serial = 1)
            }
            val archive = AppDataTransfer.Archive(mapOf(tunnel.name to tunnel.getConfigAsync()),
                mapOf(UserKnobs.MULTIPLE_TUNNELS.name to false), null, emptyList())
            assertEquals(0, env.manager.importAppData(archive))
            assertFalse(UserKnobs.multipleTunnels.first())
            assertEquals(expected, state())
            assertSame(tunnel, env.manager.getTunnels()[tunnel.name])
            assertTrue(env.backend.appliedConfigs.isEmpty())
        } finally { env.close() }
    }

    @Test fun disabledMetadataOnlyImportPreservesMultipleTunnelTransfer() = runTest {
        val env = environment()
        try {
            val tunnel = env.manager.create("fixture", TunnelTestEnvironment.profile())
            val archive = AppDataTransfer.Archive(mapOf(tunnel.name to tunnel.getConfigAsync()),
                mapOf(UserKnobs.MULTIPLE_TUNNELS.name to true), null, emptyList())
            assertEquals(0, env.manager.importAppData(archive))
            assertTrue(UserKnobs.multipleTunnels.first())
            assertEquals(NetworkAutomationState(), state())
            assertSame(tunnel, env.manager.getTunnels()[tunnel.name])
            assertTrue(env.backend.appliedConfigs.isEmpty())
        } finally { env.close() }
    }

    @Test fun enableAdmissionFinishesBeforeCompetingMultipleTunnelWriteIsRejected() = runTest {
        val env = environment()
        val gate = WriteGate()
        try {
            val tunnel = env.manager.create("fixture", TunnelTestEnvironment.profile())
            env.replaceBackend(GoBackend(env.application))
            env.manager.networkAutomation.selectDefault(tunnel.name)
            gate.install(env) { before, after ->
                !NetworkAutomationState.read(before).enabled && NetworkAutomationState.read(after).enabled
            }
            val enable = async { env.manager.networkAutomation.enable() }
            gate.entered.await()
            val multiple = async { runCatching { env.manager.setMultipleTunnels(true) }.exceptionOrNull() }
            runCurrent()
            assertFalse(multiple.isCompleted)
            assertFalse(UserKnobs.multipleTunnels.first())
            gate.release.complete(Unit)
            enable.await()
            assertTrue(multiple.await() is IOException)
            assertTrue(state().enabled)
            assertFalse(UserKnobs.multipleTunnels.first())
        } finally {
            gate.release.complete(Unit)
            env.close()
        }
    }

    @Test fun multipleTunnelAdmissionFinishesBeforeCompetingEnableIsRejected() = runTest {
        val env = environment()
        val gate = WriteGate()
        try {
            val tunnel = env.manager.create("fixture", TunnelTestEnvironment.profile())
            env.replaceBackend(GoBackend(env.application))
            env.manager.networkAutomation.selectDefault(tunnel.name)
            gate.install(env) { before, after ->
                before[UserKnobs.MULTIPLE_TUNNELS] != true && after[UserKnobs.MULTIPLE_TUNNELS] == true
            }
            val multiple = async { env.manager.setMultipleTunnels(true) }
            gate.entered.await()
            val enable = async { runCatching { env.manager.networkAutomation.enable() }.exceptionOrNull() }
            runCurrent()
            assertFalse(enable.isCompleted)
            assertFalse(state().enabled)
            gate.release.complete(Unit)
            multiple.await()
            assertTrue(enable.await() is IOException)
            assertFalse(state().enabled)
            assertTrue(UserKnobs.multipleTunnels.first())
        } finally {
            gate.release.complete(Unit)
            env.close()
        }
    }

    private suspend fun state() = NetworkAutomationState.read(VpnApplication.getPreferencesDataStore().data.first())

    private class WriteGate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        fun install(env: TunnelTestEnvironment, predicate: (Preferences, Preferences) -> Boolean) {
            val delegate = VpnApplication.getPreferencesDataStore()
            env.replacePreferences(object : DataStore<Preferences> {
                override val data = delegate.data
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                    delegate.updateData { before ->
                        val after = transform(before)
                        if (predicate(before, after)) {
                            entered.complete(Unit)
                            release.await()
                        }
                        after
                    }
            })
        }
    }
}
