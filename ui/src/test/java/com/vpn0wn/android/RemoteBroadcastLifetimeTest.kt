/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android

import android.content.Intent
import android.os.Looper
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.vpn0wn.android.backend.Backend
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.model.TunnelManager
import com.vpn0wn.android.model.TunnelTestEnvironment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteBroadcastLifetimeTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun setRetainsBroadcastUntilInventoryAndTransitionSettle() = withFixture { env ->
        env.store.configs["one"] = TunnelTestEnvironment.profile()
        val backend = holdBackend(env)
        val broadcast = dispatch(env, "com.vpn0wn.android.action.SET_TUNNEL_UP")
        runCurrent()
        assertFalse(broadcast.finished())
        backend.complete(env.backend)
        broadcast.job!!.join()
        assertTrue(broadcast.finished())
        assertEquals(Tunnel.State.UP, env.manager.getTunnels()["one"]!!.state)
    }

    @Test fun refreshRetainsBroadcastUntilActualRefreshFinishes() = withFixture { env ->
        env.manager.getTunnels()
        val backend = holdBackend(env)
        val broadcast = dispatch(env, "com.vpn0wn.android.action.REFRESH_TUNNEL_STATES")
        runCurrent()
        assertFalse(broadcast.finished())
        backend.complete(env.backend)
        broadcast.job!!.join()
        assertTrue(broadcast.finished())
    }

    @Test fun inventoryFailureFinishesBroadcast() = withFixture { env ->
        env.store.failEnumerate = true
        val broadcast = dispatch(env, "com.vpn0wn.android.action.SET_TUNNEL_UP")
        broadcast.job!!.join()
        assertTrue(broadcast.finished())
        assertTrue(env.backend.appliedConfigs.isEmpty())
    }

    @Test fun cancellationFinishesBroadcastWithoutTransition() = withFixture { env ->
        val backend = holdBackend(env)
        val broadcast = dispatch(env, "com.wireguard.android.action.REFRESH_TUNNEL_STATES")
        runCurrent()
        assertFalse(broadcast.finished())
        broadcast.job!!.cancel()
        runCurrent()
        assertFalse(broadcast.finished())
        backend.complete(env.backend)
        broadcast.job.join()
        assertTrue(broadcast.finished())
        assertTrue(env.backend.appliedConfigs.isEmpty())
    }

    @Test fun cancelledScopeStillFinishesBroadcast() = withFixture { env ->
        env.applicationScope.coroutineContext[Job]!!.cancelAndJoin()
        val broadcast = dispatch(env, "com.vpn0wn.android.action.SET_TUNNEL_DOWN")
        runCurrent()
        assertTrue(broadcast.finished())
        assertTrue(env.backend.appliedConfigs.isEmpty())
    }

    private fun holdBackend(env: TunnelTestEnvironment): CompletableDeferred<Backend> {
        val backend = CompletableDeferred<Backend>()
        Application::class.java.getDeclaredField("futureBackend").apply { isAccessible = true }
            .set(env.application, backend)
        return backend
    }

    private fun dispatch(env: TunnelTestEnvironment, action: String): HeldBroadcast {
        val receiver = shadowOf(env.application).getReceiversForIntent(Intent(action))
            .filterIsInstance<TunnelManager.IntentReceiver>().single()
        val previous = env.applicationScope.coroutineContext[Job]!!.children.toSet()
        env.application.sendBroadcast(Intent(action).putExtra("tunnel", "one")
            .setClass(env.application, TunnelManager.IntentReceiver::class.java))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Framework receiver must retain asynchronous work", shadowOf(receiver).wentAsync())
        val result = checkNotNull(shadowOf(receiver).originalPendingResult)
        return HeldBroadcast(
            env.applicationScope.coroutineContext[Job]!!.children.singleOrNull { it !in previous },
            { shadowOf(result).future.isDone },
        )
    }

    private data class HeldBroadcast(val job: Job?, val finished: () -> Boolean)

    private fun withFixture(block: suspend TestScope.(TunnelTestEnvironment) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.newFolder(), backgroundScope)
        try {
            Application.getPreferencesDataStore().edit { it[booleanPreferencesKey("allow_remote_control_intents")] = true }
            block(env)
        } finally {
            @Suppress("UNCHECKED_CAST")
            val backend = Application::class.java.getDeclaredField("futureBackend").apply { isAccessible = true }
                .get(env.application) as CompletableDeferred<Backend>
            backend.complete(env.backend)
            env.close()
            Dispatchers.resetMain()
        }
    }
}
