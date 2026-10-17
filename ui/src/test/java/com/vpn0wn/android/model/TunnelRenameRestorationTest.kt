/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.model

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.vpn0wn.android.Application
import com.vpn0wn.android.backend.Backend
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.util.UserKnobs
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class TunnelRenameRestorationTest {
    @get:Rule val folder = TemporaryFolder()

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.environment(): TunnelTestEnvironment {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return TunnelTestEnvironment(folder.root, backgroundScope)
    }

    @Test
    fun upRenamePreservesUnrelatedRunningNamesAndFreshManagerRestoration() = runTest {
        val env = environment()
        try {
            val renamed = env.manager.create("old", TunnelTestEnvironment.profile())
            val other = env.manager.create("other", TunnelTestEnvironment.profile(51821))
            renamed.setStateAsync(Tunnel.State.UP)
            other.setStateAsync(Tunnel.State.UP)
            assertEquals("new", renamed.setNameAsync("new"))
            assertEquals(setOf("new", "other"), UserKnobs.runningTunnels.first())
            assertSame(renamed, env.manager.getTunnels()["new"])
            env.replaceBackend(TunnelTestEnvironment.ControlledBackend())
            val restarted = TunnelManager(env.store)
            val inventory = restarted.getTunnels()
            restarted.restoreState(true)
            assertEquals(Tunnel.State.UP, inventory["new"]!!.state)
            assertEquals(Tunnel.State.UP, inventory["other"]!!.state)
            assertEquals(setOf("new", "other"), env.store.configs.keys)
        } finally {
            env.close()
        }
    }

    @Test
    fun downRenameDoesNotGainRestorationIntent() = runTest {
        val env = environment()
        try {
            val renamed = env.manager.create("old", TunnelTestEnvironment.profile())
            val other = env.manager.create("other", TunnelTestEnvironment.profile(51821))
            other.setStateAsync(Tunnel.State.UP)
            renamed.setNameAsync("new")
            assertEquals(setOf("other"), UserKnobs.runningTunnels.first())
            env.replaceBackend(TunnelTestEnvironment.ControlledBackend())
            val restarted = TunnelManager(env.store)
            val inventory = restarted.getTunnels()
            restarted.restoreState(true)
            assertEquals(Tunnel.State.DOWN, inventory["new"]!!.state)
            assertEquals(Tunnel.State.UP, inventory["other"]!!.state)
        } finally {
            env.close()
        }
    }

    @Test
    fun failedFileRenamePersistsActualDownStateWithoutLosingTheProfile() = runTest {
        val env = environment()
        try {
            val tunnel = env.manager.create("old", TunnelTestEnvironment.profile())
            tunnel.setStateAsync(Tunnel.State.UP)
            env.store.failRename = true
            try {
                tunnel.setNameAsync("new")
                fail("Expected file rename failure")
            } catch (_: IOException) {
            }
            assertEquals("old", tunnel.name)
            assertEquals(setOf("old"), env.store.configs.keys)
            assertEquals(Tunnel.State.DOWN, tunnel.state)
            assertFalse("Failed DOWN operation must not leave stale UP intent", "old" in UserKnobs.runningTunnels.first())
        } finally {
            env.close()
        }
    }

    @Test
    fun failedReactivationKeepsRenamedProfileDownAndClearsOldRestoreName() = runTest {
        val env = environment()
        try {
            val tunnel = env.manager.create("old", TunnelTestEnvironment.profile())
            tunnel.setStateAsync(Tunnel.State.UP)
            env.backend.failUp = true
            try {
                tunnel.setNameAsync("new")
                fail("Expected backend activation failure")
            } catch (_: IOException) {
            }
            assertEquals("new", tunnel.name)
            assertEquals(setOf("new"), env.store.configs.keys)
            assertEquals(Tunnel.State.DOWN, tunnel.state)
            assertEquals(emptySet<String>(), UserKnobs.runningTunnels.first())
        } finally {
            env.close()
        }
    }

    @Test
    fun failedPersistenceReportsFailureAndKeepsActualRenamedIdentityRecoverable() = runTest {
        val env = environment()
        try {
            val tunnel = env.manager.create("old", TunnelTestEnvironment.profile())
            tunnel.setStateAsync(Tunnel.State.UP)
            val preferences = Application.getPreferencesDataStore()
            var rejectNewRestoreName = true
            env.replacePreferences(object : DataStore<Preferences> by preferences {
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                    preferences.updateData { previous ->
                        val updated = transform(previous)
                        if (rejectNewRestoreName && updated[UserKnobs.RUNNING_TUNNELS] == setOf("new"))
                            throw IOException("Injected running-name commit failure")
                        updated
                    }
            })
            try {
                tunnel.setNameAsync("new")
                fail("Expected persistence failure")
            } catch (failure: IOException) {
                assertEquals("Injected running-name commit failure", failure.message)
            }
            assertEquals("new", tunnel.name)
            assertSame(tunnel, env.manager.getTunnels()["new"])
            assertEquals(setOf("new"), env.store.configs.keys)
            assertEquals(Tunnel.State.UP, tunnel.state)
            assertEquals(setOf("old"), UserKnobs.runningTunnels.first())
            rejectNewRestoreName = false
            env.manager.saveState()
            assertEquals(setOf("new"), UserKnobs.runningTunnels.first())
        } finally {
            env.close()
        }
    }

    @Test
    fun primaryRenameFailureRetainsFailedObservationAsSuppressed() = runTest {
        val env = environment()
        try {
            val tunnel = env.manager.create("old", TunnelTestEnvironment.profile())
            tunnel.setStateAsync(Tunnel.State.UP)
            env.store.failRename = true
            val observationFailure = IOException("Injected running-name observation failure")
            env.replaceBackend(object : Backend by env.backend {
                override fun getRunningTunnelNames(): Set<String> = throw observationFailure
            })
            try {
                tunnel.setNameAsync("new")
                fail("Expected file rename failure")
            } catch (failure: IOException) {
                assertEquals("Injected rename failure", failure.message)
                val primary = generateSequence<Throwable>(failure) { it.cause }.firstOrNull {
                    it.javaClass == IOException::class.java && it.message == failure.message && it.suppressed.isNotEmpty()
                }
                assertNotNull("Original rename failure must retain the secondary failure", primary)
                val secondary = checkNotNull(primary).suppressed.single()
                assertEquals(IOException::class.java, secondary.javaClass)
                assertTrue(generateSequence<Throwable>(secondary) { it.cause }.any { it === observationFailure })
            }
            assertEquals("old", tunnel.name)
            assertEquals(setOf("old"), env.store.configs.keys)
        } finally {
            env.close()
        }
    }
}
