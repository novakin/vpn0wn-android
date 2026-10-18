/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.vpn0wn.android.model.NetworkAutomationController
import com.vpn0wn.android.model.NetworkAutomationState
import com.vpn0wn.android.model.TunnelTestEnvironment
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import com.vpn0wn.android.Application as VpnApplication
import com.vpn0wn.android.util.UserKnobs

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [NetworkMonitorServiceTest.ConnectivityShadow::class])
class NetworkMonitorServiceTest {
    @get:Rule val folder = TemporaryFolder()
    @Before fun resetCallbacks() {
        ConnectivityShadow.registered.clear()
        ConnectivityShadow.unregistered.clear()
        ConnectivityShadow.registration = CompletableDeferred()
    }
    @After fun resetMain() = Dispatchers.resetMain()

    @Test fun newerStartWhileAttachWaitsRegistersEachCallbackOnce() = exerciseOverlappingStarts(false)

    @Test fun staleAttachFailureCannotStopNewerSuccessfulCommand() = exerciseOverlappingStarts(true)

    @Test fun resumeOfRegisteredMonitorPreservesManualHoldWithoutRetry() = exerciseActiveResume(NetworkAutomationController.Condition.MANUAL_HOLD)

    @Test fun resumeOfRegisteredMonitorPreservesFailureWithoutRetry() = exerciseActiveResume(NetworkAutomationController.Condition.FAILED)

    private fun exerciseActiveResume(condition: NetworkAutomationController.Condition) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.root, backgroundScope)
        val service = Robolectric.buildService(NetworkMonitorService::class.java)
        try {
            val receipt = NetworkAutomationState.Receipt("fixture", "UP", "UP", 0, 0, 0, 1, NetworkAutomationState.Outcome.FAILED)
            NetworkAutomationState.update { it.copy(enabled = true, episode = 1, serial = 1,
                manual = receipt.takeIf { condition == NetworkAutomationController.Condition.MANUAL_HOLD },
                attempt = receipt.takeIf { condition == NetworkAutomationController.Condition.FAILED }) }
            service.create()
            service.get().onStartCommand(null, 0, 1)
            val controller = env.manager.networkAutomation
            controller.status.first { it.condition == NetworkAutomationController.Condition.MONITORING }
            @Suppress("UNCHECKED_CAST")
            val statuses = controller.javaClass.getDeclaredField("mutableStatus").apply { isAccessible = true }.get(controller)
                as MutableStateFlow<NetworkAutomationController.Status>
            statuses.value = statuses.value.copy(condition = condition)
            val before = NetworkAutomationState.read(VpnApplication.getPreferencesDataStore().data.first())
            val delegate = VpnApplication.getPreferencesDataStore()
            val committed = CompletableDeferred<Unit>()
            env.replacePreferences(object : DataStore<Preferences> by delegate {
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    val result = delegate.updateData(transform)
                    committed.complete(Unit)
                    return result
                }
            })
            service.get().onStartCommand(Intent().setAction(NetworkMonitorService.RESUME), 0, 2)
            committed.await()
            runCurrent()
            assertEquals(condition, controller.status.value.condition)
            val after = NetworkAutomationState.read(delegate.data.first())
            assertEquals(before.manual, after.manual)
            assertEquals(before.attempt, after.attempt)
            assertEquals(2, ConnectivityShadow.registered.size)
            assertTrue(ConnectivityShadow.unregistered.isEmpty())
            assertTrue(env.backend.appliedConfigs.isEmpty())
            assertFalse(shadowOf(service.get()).isStoppedBySelf)
        } finally {
            service.destroy()
            env.close()
        }
    }

    @Test fun failedInitialDisableCommitKeepsObserverCallbacksAndReceipts() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.root, backgroundScope)
        val service = Robolectric.buildService(NetworkMonitorService::class.java)
        try {
            val receipt = NetworkAutomationState.Receipt("fixture", "UP", "UP", 0, 0, 0, 1)
            NetworkAutomationState.update { it.copy(enabled = true, episode = 1, manual = receipt, serial = 1) }
            UserKnobs.setRunningTunnels(setOf("fixture"))
            service.create()
            service.get().onStartCommand(null, 0, 1)
            env.manager.networkAutomation.status.first { it.condition == NetworkAutomationController.Condition.MONITORING }
            val observerField = env.manager.networkAutomation.javaClass.getDeclaredField("observer").apply { isAccessible = true }
            val originalObserver = observerField.get(env.manager.networkAutomation)
            assertNotNull(originalObserver)
            val delegate = VpnApplication.getPreferencesDataStore()
            env.replacePreferences(object : DataStore<Preferences> by delegate {
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = delegate.updateData { before ->
                    val after = transform(before)
                    if (!NetworkAutomationState.read(after).enabled) throw IOException("Synthetic opt-out commit failure")
                    after
                }
            })
            service.get().onStartCommand(Intent().setAction(NetworkMonitorService.DISABLE), 0, 2)
            env.manager.networkAutomation.status.first { it.condition == NetworkAutomationController.Condition.FAILED }
            val retained = NetworkAutomationState.read(delegate.data.first())
            assertTrue(retained.enabled)
            assertEquals(receipt, retained.manual)
            assertEquals(NetworkAutomationState.Exit.NONE, retained.exit)
            assertEquals(setOf("fixture"), UserKnobs.runningTunnels.first())
            assertSame(originalObserver, observerField.get(env.manager.networkAutomation))
            assertEquals(2, ConnectivityShadow.registered.size)
            assertTrue(ConnectivityShadow.unregistered.isEmpty())
            assertFalse(shadowOf(service.get()).isStoppedBySelf)
            assertTrue(env.backend.appliedConfigs.isEmpty())
        } finally {
            service.destroy()
            env.close()
        }
    }

    @Test fun failedDisableAdmissionDoesNotLeaveNewServicePromotedWithoutCallbacks() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.root, backgroundScope)
        val service = Robolectric.buildService(NetworkMonitorService::class.java)
        try {
            NetworkAutomationState.update { it.copy(enabled = true, episode = 1) }
            val delegate = VpnApplication.getPreferencesDataStore()
            env.replacePreferences(object : DataStore<Preferences> by delegate {
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = delegate.updateData { before ->
                    val after = transform(before)
                    if (!NetworkAutomationState.read(after).enabled) throw IOException("Synthetic opt-out failure")
                    after
                }
            })
            service.create()
            service.get().onStartCommand(Intent().setAction(NetworkMonitorService.DISABLE), 0, 1)
            env.manager.networkAutomation.status.first { it.condition == NetworkAutomationController.Condition.FAILED }
            assertTrue(NetworkAutomationState.read(delegate.data.first()).enabled)
            assertTrue(ConnectivityShadow.registered.isEmpty())
            assertTrue(shadowOf(service.get()).isStoppedBySelf)
        } finally {
            service.destroy()
            env.close()
        }
    }

    private fun exerciseOverlappingStarts(failFirstAttach: Boolean) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.root, backgroundScope)
        val service = Robolectric.buildService(NetworkMonitorService::class.java)
        val attachEntered = CompletableDeferred<Unit>()
        val releaseAttach = CompletableDeferred<Unit>()
        try {
            NetworkAutomationState.update { it.copy(enabled = true, episode = 1) }
            val delegate = VpnApplication.getPreferencesDataStore()
            val reads = AtomicInteger()
            env.replacePreferences(object : DataStore<Preferences> by delegate {
                override val data = flow {
                    if (reads.incrementAndGet() == 2) {
                        attachEntered.complete(Unit)
                        releaseAttach.await()
                        if (failFirstAttach) throw IOException("Synthetic old attach failure")
                    }
                    delegate.data.collect { emit(it) }
                }
            })
            service.create()
            service.get().onStartCommand(null, 0, 1)
            runCurrent()
            attachEntered.await()
            service.get().onStartCommand(null, 0, 2)
            runCurrent()
            releaseAttach.complete(Unit)
            env.manager.networkAutomation.status.first { it.condition == NetworkAutomationController.Condition.MONITORING }
            ConnectivityShadow.registration.await()
            assertEquals(2, ConnectivityShadow.registered.size)
            assertEquals(2, ConnectivityShadow.registered.toSet().size)
            assertTrue(ConnectivityShadow.unregistered.isEmpty())
            assertFalse(shadowOf(service.get()).isStoppedBySelf)
            assertEquals(NetworkAutomationController.Condition.MONITORING, env.manager.networkAutomation.status.value.condition)
            service.get().onStartCommand(null, 0, 3)
            runCurrent()
            assertEquals(2, ConnectivityShadow.registered.size)
        } finally {
            releaseAttach.complete(Unit)
            service.destroy()
            env.close()
        }
    }

    @Implements(ConnectivityManager::class)
    class ConnectivityShadow {
        @Implementation fun registerNetworkCallback(request: NetworkRequest, callback: ConnectivityManager.NetworkCallback) {
            assertTrue(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            assertTrue(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
            registered += callback
        }
        @Implementation fun registerDefaultNetworkCallback(callback: ConnectivityManager.NetworkCallback) {
            registered += callback
            registration.complete(Unit)
        }
        @Implementation fun unregisterNetworkCallback(callback: ConnectivityManager.NetworkCallback) {
            unregistered += callback
        }

        companion object {
            val registered = mutableListOf<ConnectivityManager.NetworkCallback>()
            val unregistered = mutableListOf<ConnectivityManager.NetworkCallback>()
            var registration = CompletableDeferred<Unit>()
        }
    }
}
