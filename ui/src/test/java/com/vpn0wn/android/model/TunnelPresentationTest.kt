/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.model

import android.app.Application
import android.content.Context
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.vpn0wn.android.R
import com.vpn0wn.android.backend.Statistics
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.databinding.BindingAdapters
import com.vpn0wn.android.widget.ToggleSwitch
import com.vpn0wn.android.widget.TunnelSignalView
import com.vpn0wn.crypto.Key
import java.time.Duration
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
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
import org.robolectric.shadows.ShadowSystemClock
import androidx.databinding.Observable

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class TunnelPresentationTest {
    @get:Rule val folder = TemporaryFolder()
    private val context: Context
        get() = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.AppTheme)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.environment(): TunnelTestEnvironment {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return TunnelTestEnvironment(folder.root, backgroundScope)
    }

    @Test
    fun pendingDisconnectDoesNotShowConnectingOrCheckedSwitch() = runTest {
        val env = environment()
        val tunnel = env.manager.create("qa", TunnelTestEnvironment.profile())
        tunnel.setStateAsync(Tunnel.State.UP)
        tunnel.onStatisticsChanged(statistics(3, 3))
        val observedModes = mutableListOf<TunnelSignalView.TunnelMode>()
        val signal = TunnelSignalView(context)
        tunnel.addOnPropertyChangedCallback(object : Observable.OnPropertyChangedCallback() {
            override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
                BindingAdapters.setTunnelSignalTunnel(signal, tunnel, tunnel.state, tunnel.peekStatistics())
                observedModes.add(signal.mode)
            }
        })
        env.backend.blockDown = true
        val disconnect = async { tunnel.setStateAsync(Tunnel.State.DOWN) }
        try {
            assertTrue(withContext(Dispatchers.IO) { env.backend.downEntered.await(5, TimeUnit.SECONDS) })
            BindingAdapters.setTunnelSignalTunnel(signal, tunnel, tunnel.state, null)
            assertEquals(TunnelSignalView.TunnelMode.DOWN, signal.mode)
            assertTrue(observedModes.isNotEmpty())
            assertTrue(observedModes.all { it == TunnelSignalView.TunnelMode.DOWN })
            val switch = ToggleSwitch(context)
            BindingAdapters.setTunnelSwitchModeTunnel(switch, tunnel, tunnel.state, null)
            assertFalse(switch.isChecked)
            assertFalse(switch.isEnabled)
        } finally {
            env.backend.releaseDown.countDown()
            disconnect.await()
        }
        assertNull(tunnel.transitionTarget)
        assertEquals(Tunnel.State.DOWN, tunnel.state)
    }

    @Test
    fun pendingConnectStillShowsConnectingAndBlocksRepeatTap() = runTest {
        val env = environment()
        val tunnel = env.manager.create("qa", TunnelTestEnvironment.profile())
        env.backend.blockUp = true
        val connect = async { tunnel.setStateAsync(Tunnel.State.UP) }
        try {
            assertTrue(withContext(Dispatchers.IO) { env.backend.upEntered.await(5, TimeUnit.SECONDS) })
            val signal = TunnelSignalView(context)
            BindingAdapters.setTunnelSignalTunnel(signal, tunnel, tunnel.state, null)
            assertEquals(TunnelSignalView.TunnelMode.CONNECTING, signal.mode)
            val switch = ToggleSwitch(context)
            BindingAdapters.setTunnelSwitchModeTunnel(switch, tunnel, tunnel.state, null)
            assertTrue(switch.isChecked)
            assertFalse(switch.isEnabled)
        } finally {
            env.backend.releaseUp.countDown()
            connect.await()
        }
        assertNull(tunnel.transitionTarget)
        assertEquals(Tunnel.State.UP, tunnel.state)
    }

    @Test
    fun failedDisconnectRestoresUpPresentationAndEnabledSwitch() = runTest {
        val env = environment()
        val tunnel = env.manager.create("qa", TunnelTestEnvironment.profile())
        tunnel.setStateAsync(Tunnel.State.UP)
        env.backend.failDown = true
        try {
            tunnel.setStateAsync(Tunnel.State.DOWN)
            fail("Expected failed disconnect")
        } catch (_: IOException) { }
        assertNull(tunnel.transitionTarget)
        assertEquals(Tunnel.State.UP, tunnel.state)
        val switch = ToggleSwitch(context)
        BindingAdapters.setTunnelSwitchModeTunnel(switch, tunnel, tunnel.state, null)
        assertTrue(switch.isChecked)
        assertTrue(switch.isEnabled)
    }

    @Test
    fun freshIdleStatisticsClearLastRateButRepeatedBindingsKeepCurrentRate() = runTest {
        val env = environment()
        val tunnel = env.manager.create("qa", TunnelTestEnvironment.profile())
        val initial = statistics(0, 0)
        BindingAdapters.tunnelRxRate(tunnel, initial)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        val traffic = statistics(3, 3)
        assertEquals("3 B/S", BindingAdapters.tunnelRxRate(tunnel, traffic))
        assertEquals("3 B/S", BindingAdapters.tunnelTxRate(tunnel, traffic))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        val idle = statistics(3, 3)
        assertEquals("0 B/S", BindingAdapters.tunnelRxRate(tunnel, idle))
        assertEquals("0 B/S", BindingAdapters.tunnelTxRate(tunnel, idle))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        val burst = statistics(13, 23)
        assertEquals("10 B/S", BindingAdapters.tunnelRxRate(tunnel, burst))
        assertEquals("20 B/S", BindingAdapters.tunnelTxRate(tunnel, burst))
        ShadowSystemClock.advanceBy(Duration.ofMillis(300))
        assertEquals("10 B/S", BindingAdapters.tunnelRxRate(tunnel, burst))
        assertEquals("20 B/S", BindingAdapters.tunnelTxRate(tunnel, burst))
    }

    @Test
    fun counterResetAndClearedStatisticsStartNewRateBaseline() = runTest {
        val env = environment()
        val tunnel = env.manager.create("qa", TunnelTestEnvironment.profile())
        BindingAdapters.tunnelRxRate(tunnel, statistics(100, 200))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        assertEquals("--", BindingAdapters.tunnelRxRate(tunnel, statistics(1, 2)))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        assertEquals("10 B/S", BindingAdapters.tunnelRxRate(tunnel, statistics(11, 22)))
        assertEquals("--", BindingAdapters.tunnelRxRate(tunnel, null))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        assertEquals("--", BindingAdapters.tunnelRxRate(tunnel, statistics(111, 222)))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        assertEquals("10 B/S", BindingAdapters.tunnelRxRate(tunnel, statistics(121, 242)))
    }

    @Test
    fun rapidFreshSnapshotsWaitForMinimumIntervalWithoutLosingTraffic() = runTest {
        val env = environment()
        val tunnel = env.manager.create("qa", TunnelTestEnvironment.profile())
        BindingAdapters.tunnelRxRate(tunnel, statistics(0, 0))
        ShadowSystemClock.advanceBy(Duration.ofMillis(100))
        assertEquals("--", BindingAdapters.tunnelRxRate(tunnel, statistics(3, 3)))
        ShadowSystemClock.advanceBy(Duration.ofMillis(900))
        assertEquals("10 B/S", BindingAdapters.tunnelRxRate(tunnel, statistics(10, 20)))
    }

    private fun statistics(rx: Long, tx: Long): Statistics {
        val statistics = Statistics::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        Statistics::class.java.getDeclaredMethod("add", Key::class.java, Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(statistics, Key.fromBase64("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="), rx, tx, 0L)
        return statistics
    }
}
