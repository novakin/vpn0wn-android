/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.model

import android.app.Application
import android.app.ApplicationExitInfo
import android.net.NetworkCapabilities
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class NetworkAutomationPolicyTest {
    @Test fun validatedMobileBeatsNonLosingCaptiveWifiEvenWhileLosing() {
        val mobile = candidate(11, NetworkCapabilities.TRANSPORT_CELLULAR, validated = true, losing = true)
        val wifi = candidate(12, NetworkCapabilities.TRANSPORT_WIFI, captive = true)
        assertEquals(mobile.network, snapshot(mobile, wifi).select(wifi.network))
        assertEquals(mobile.network, snapshot(mobile, wifi).select(null))
    }

    @Test fun validationQualityPrecedesEthernetAndLosingPreference() {
        val mobile = candidate(11, NetworkCapabilities.TRANSPORT_CELLULAR, validated = true)
        val ethernet = candidate(12, NetworkCapabilities.TRANSPORT_ETHERNET)
        assertEquals(mobile.network, snapshot(ethernet, mobile).select(ethernet.network))
        assertEquals(ethernet.network, snapshot(ethernet, mobile.copy(capabilities = capabilities(NetworkCapabilities.TRANSPORT_CELLULAR))).select(null))
    }

    @Test fun losingWithinWinningTierRetainsCurrentAndUnknownTiesStayUnknown() {
        val first = candidate(11, NetworkCapabilities.TRANSPORT_WIFI, validated = true, losing = true)
        val second = candidate(12, NetworkCapabilities.TRANSPORT_WIFI, validated = true, losing = true)
        assertEquals(first.network, snapshot(first, second).select(first.network))
        assertNull(snapshot(first, second).select(null))
        assertEquals(second.network, snapshot(first, second).copy(appDefault = second.network).select(null))
        assertEquals(second.network, snapshot(first, second.copy(losing = false)).select(null))
    }

    @Test fun blockedSuspendedAmbiguousAndVpnCandidatesAreExcluded() {
        val ordinary = candidate(11, NetworkCapabilities.TRANSPORT_CELLULAR)
        val blocked = candidate(12, NetworkCapabilities.TRANSPORT_WIFI, validated = true).copy(blocked = true)
        val suspended = candidate(13, NetworkCapabilities.TRANSPORT_ETHERNET, validated = true).let {
            it.copy(capabilities = NetworkCapabilities(it.capabilities).also { caps -> shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED) })
        }
        val ambiguous = candidate(14, NetworkCapabilities.TRANSPORT_WIFI, validated = true).let {
            it.copy(capabilities = NetworkCapabilities(it.capabilities).also { caps -> shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR) })
        }
        val vpn = candidate(15, NetworkCapabilities.TRANSPORT_WIFI, validated = true).let {
            it.copy(capabilities = NetworkCapabilities(it.capabilities).also { caps -> shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) })
        }
        assertEquals(ordinary.network, snapshot(ordinary, blocked, suspended, ambiguous, vpn).select(null))
    }

    @Test fun recordRoundTripRetainsManualAndEquivalentFailureAcrossOperationKinds() {
        val receipt = NetworkAutomationState.Receipt("fixture", "TRANSPORT", "UP", 3, 9, 4, 5, NetworkAutomationState.Outcome.FAILED)
        val state = NetworkAutomationState(enabled = true, episode = 2, defaultName = "fixture", resolved = true,
            revision = 3, frontier = 9, selection = 4, serial = 5, manual = receipt.copy(requested = "TOGGLE", absolute = null), attempt = receipt)
        val restored = NetworkAutomationState.decode(NetworkAutomationState.encode(state))
        assertEquals(state, restored)
        assertTrue(restored.suppressesRestore("fixture"))
        assertTrue(restored.equivalentAttempt("fixture"))
        assertFalse(restored.copy(selection = 6).equivalentAttempt("fixture"))
        assertTrue(restored.copy(attempt = receipt.copy(requested = "UP"), selection = 6).equivalentAttempt("fixture"))
    }

    @Test fun malformedOptionalRecordFieldsCannotEraseManualIntent() {
        val valid = NetworkAutomationState.encode(NetworkAutomationState(enabled = true))
        for (field in listOf("manual", "attempt", "stop", "acknowledgedStop")) {
            val invalid = JSONObject(valid).put(field, "wrong shape").toString()
            assertThrows(IOException::class.java) { NetworkAutomationState.decode(invalid) }
        }
        for (field in listOf("enabled", "resolved", "paused", "episode", "revision", "frontier", "selection", "serial")) {
            assertThrows(IOException::class.java) { NetworkAutomationState.decode(JSONObject(valid).put(field, "1").toString()) }
        }
    }

    @Test fun fencesRemainUnresolvedAcrossFailedEditAndSameNameRecreation() {
        val manual = NetworkAutomationState.Receipt("fixture", "UP", "UP", 1, 3, 0, 1, NetworkAutomationState.Outcome.UP)
        val original = NetworkAutomationState(enabled = true, episode = 1, defaultName = "fixture", resolved = true, manual = manual)
        val fenced = NetworkAutomationState.decode(NetworkAutomationState.encode(original.fence(setOf("fixture"))))
        assertFalse(fenced.resolved)
        assertEquals(NetworkAutomationState.Outcome.UNRESOLVED, fenced.manual?.outcome)
        assertTrue(fenced.suppressesRestore("fixture"))
        assertEquals(original, original.fence(setOf("unrelated")))
    }

    @Test fun stopBoundaryConsumesOldStopWithoutTimestampArithmeticOrMissingHistoryGuess() {
        val consumed = stamp(2, 1000, ApplicationExitInfo.REASON_USER_REQUESTED)
        val state = NetworkAutomationState(enabled = true, acknowledgedStop = consumed)
        assertFalse(AutomationStopHistory.observe(state, listOf(consumed)).paused)
        val newer = stamp(3, 900, ApplicationExitInfo.REASON_USER_REQUESTED)
        val paused = AutomationStopHistory.observe(state, listOf(newer, consumed))
        assertTrue(paused.paused)
        assertEquals(newer, paused.stop)
        assertEquals(state, AutomationStopHistory.observe(state, listOf(newer)))
        assertEquals(paused, AutomationStopHistory.observe(paused, null))
        val resumed = AutomationStopHistory.acknowledge(paused, null)
        assertFalse(resumed.paused)
        assertEquals(newer, resumed.acknowledgedStop)
        assertFalse(AutomationStopHistory.observe(resumed, listOf(newer, consumed)).paused)
    }

    private fun stamp(pid: Int, timestamp: Long, reason: Int) = NetworkAutomationState.StopStamp("fixture", pid, timestamp, reason)

    private fun candidate(id: Int, transport: Int, validated: Boolean = false, captive: Boolean = false, losing: Boolean = false) =
        AutomationNetwork(ShadowNetwork.newInstance(id), capabilities(transport, validated, captive), blocked = false, losing = losing)

    private fun capabilities(transport: Int, validated: Boolean = false, captive: Boolean = false): NetworkCapabilities =
        NetworkCapabilities().also { caps ->
            val shadow = shadowOf(caps)
            shadow.addTransportType(transport)
            shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
            shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
            if (validated) shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (captive) shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        }

    private fun snapshot(vararg candidates: AutomationNetwork) = AutomationNetworks(Any(), 1, candidates.toList(), null)
}
