/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.databinding

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.vpn0wn.android.R
import com.vpn0wn.android.backend.HandshakeObservation
import com.vpn0wn.android.backend.HandshakeSession
import com.vpn0wn.android.backend.RecoveryResult
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.model.ObservableTunnel
import com.vpn0wn.config.Config
import com.vpn0wn.crypto.Key
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(application = Application::class, sdk = [28])
class RecoveryDiagnosticsBindingTest {
    private val first = Key.fromBytes(ByteArray(32) { 1 })
    private val requested = Key.fromBytes(ByteArray(32) { 2 })
    private val healthy = Key.fromBytes(ByteArray(32) { 3 })
    private val endpointFree = Key.fromBytes(ByteArray(32) { 4 })
    private val ids = listOf(R.id.recovery_status, R.id.recovery_counts_attempts, R.id.recovery_counts_admissions,
        R.id.recovery_counts_requests, R.id.recovery_counts_uncertain, R.id.recovery_transport,
        R.id.recovery_request_peer, R.id.recovery_result, R.id.recovery_result_recorded, R.id.recovery_authentication)

    @Test fun unavailableAndFreshIdleKeepAbsenceDifferentFromZero() {
        val view = layout()
        for (state in listOf(Tunnel.State.DOWN, Tunnel.State.TOGGLE, null)) {
            bind(view, snapshot(), state = state)
            for (id in ids) assertText(view, id, R.string.vpn0wn_recovery_unavailable)
        }
        bind(view, null)
        for (id in ids) assertText(view, id, R.string.vpn0wn_recovery_unavailable)
        bind(view, snapshot())
        assertText(view, R.id.recovery_status, R.string.vpn0wn_recovery_idle)
        for (id in ids.filter { it in listOf(R.id.recovery_counts_attempts, R.id.recovery_counts_admissions,
            R.id.recovery_counts_requests, R.id.recovery_counts_uncertain) }) assertEquals("0", text(view, id))
        assertText(view, R.id.recovery_transport, R.string.vpn0wn_recovery_unknown)
        assertText(view, R.id.recovery_request_peer, R.string.vpn0wn_recovery_peer_unrecorded)
        assertText(view, R.id.recovery_result, R.string.vpn0wn_recovery_result_unrecorded)
        assertText(view, R.id.recovery_result_recorded, R.string.vpn0wn_recovery_time_unrecorded)
        assertText(view, R.id.recovery_authentication, R.string.vpn0wn_recovery_unknown)
    }

    @Test fun everyStatusKeepsIndependentPendingAndInhibitionFactsInBothLanguages() {
        val cases = mapOf(
            ObservableTunnel.RecoveryStatus.IDLE to R.string.vpn0wn_recovery_idle,
            ObservableTunnel.RecoveryStatus.PENDING to R.string.vpn0wn_recovery_pending,
            ObservableTunnel.RecoveryStatus.REQUESTED to R.string.vpn0wn_recovery_requested,
            ObservableTunnel.RecoveryStatus.BUSY_DEFERRED to R.string.vpn0wn_recovery_busy_deferred,
            ObservableTunnel.RecoveryStatus.COMPLETED to R.string.vpn0wn_recovery_completed,
            ObservableTunnel.RecoveryStatus.DROPPED_BUSY to R.string.vpn0wn_recovery_dropped_busy,
            ObservableTunnel.RecoveryStatus.DROPPED_COOLDOWN to R.string.vpn0wn_recovery_dropped_cooldown,
            ObservableTunnel.RecoveryStatus.DROPPED_ATTEMPT_LIMIT to R.string.vpn0wn_recovery_dropped_attempt_limit,
            ObservableTunnel.RecoveryStatus.DROPPED_ENTRY_LIMIT to R.string.vpn0wn_recovery_dropped_entry_limit,
            ObservableTunnel.RecoveryStatus.DROPPED_DOZE to R.string.vpn0wn_recovery_dropped_doze,
            ObservableTunnel.RecoveryStatus.DISABLED to R.string.vpn0wn_recovery_disabled,
            ObservableTunnel.RecoveryStatus.CANCELLED to R.string.vpn0wn_recovery_cancelled,
            ObservableTunnel.RecoveryStatus.FAILED to R.string.vpn0wn_recovery_failed,
            ObservableTunnel.RecoveryStatus.OBSOLETE to R.string.vpn0wn_recovery_obsolete,
        )
        assertEquals(ObservableTunnel.RecoveryStatus.entries.toSet(), cases.keys)
        for (locale in listOf("en", "fr")) {
            val view = layout(locale)
            for ((status, resource) in cases) for (pending in listOf(false, true)) for (disabled in listOf(false, true)) {
                bind(view, snapshot().copy(status = status, requestPending = pending, automaticRecoveryDisabled = disabled))
                val expected = mutableListOf(view.context.getString(resource))
                if (pending) expected += view.context.getString(R.string.vpn0wn_recovery_pending_fact)
                if (disabled) expected += view.context.getString(R.string.vpn0wn_recovery_disabled_fact)
                assertEquals(expected.joinToString("\n"), text(view, R.id.recovery_status))
            }
        }
    }

    @Test fun allOutcomesAndSafeFailureCategoriesHaveFixedLocalizedMeanings() {
        val outcomes = mapOf(
            RecoveryResult.Outcome.BUSY to R.string.vpn0wn_recovery_result_busy,
            RecoveryResult.Outcome.OBSOLETE to R.string.vpn0wn_recovery_result_obsolete,
            RecoveryResult.Outcome.UNAVAILABLE to R.string.vpn0wn_recovery_result_unavailable,
            RecoveryResult.Outcome.TRANSPORT_FAILED to R.string.vpn0wn_recovery_result_transport_failed,
            RecoveryResult.Outcome.INITIATION_STARTED to R.string.vpn0wn_recovery_result_initiation_started,
            RecoveryResult.Outcome.INITIATION_SKIPPED to R.string.vpn0wn_recovery_result_initiation_skipped,
            RecoveryResult.Outcome.INITIATION_FAILED to R.string.vpn0wn_recovery_result_initiation_failed,
            RecoveryResult.Outcome.INITIATION_SEND_FAILED to R.string.vpn0wn_recovery_result_send_failed,
        )
        val failures = mapOf(
            ObservableTunnel.RecoveryFailureCategory.CANCELLED to R.string.vpn0wn_recovery_failure_cancelled,
            ObservableTunnel.RecoveryFailureCategory.INTERRUPTED to R.string.vpn0wn_recovery_failure_interrupted,
            ObservableTunnel.RecoveryFailureCategory.DNS_RESOLUTION to R.string.vpn0wn_recovery_failure_dns,
            ObservableTunnel.RecoveryFailureCategory.IO to R.string.vpn0wn_recovery_failure_io,
            ObservableTunnel.RecoveryFailureCategory.SERVICE_UNAVAILABLE to R.string.vpn0wn_recovery_failure_service,
            ObservableTunnel.RecoveryFailureCategory.DEFECT to R.string.vpn0wn_recovery_failure_defect,
        )
        assertEquals(RecoveryResult.Outcome.values().toSet(), outcomes.keys)
        assertEquals(ObservableTunnel.RecoveryFailureCategory.entries.toSet(), failures.keys)
        for (locale in listOf("en", "fr")) {
            val view = layout(locale)
            for ((outcome, resource) in outcomes) {
                bind(view, snapshot().copy(lastOutcome = outcome))
                assertText(view, R.id.recovery_result, resource)
            }
            for ((failure, resource) in failures) {
                bind(view, snapshot().copy(failureCategory = failure))
                assertText(view, R.id.recovery_result, resource)
            }
            bind(view, snapshot().copy(lastOutcome = RecoveryResult.Outcome.TRANSPORT_FAILED,
                failureCategory = ObservableTunnel.RecoveryFailureCategory.INTERRUPTED))
            assertEquals(view.context.getString(R.string.vpn0wn_recovery_result_transport_failed) + "\n" +
                view.context.getString(R.string.vpn0wn_recovery_failure_interrupted), text(view, R.id.recovery_result))
        }
        assertNotEquals(layout("en").context.getString(R.string.vpn0wn_recovery_failure_io),
            layout("fr").context.getString(R.string.vpn0wn_recovery_failure_io))
    }

    @Test fun fourCountsAndRecordedUptimeUseActualResourceLocaleWithoutPrivateBudgets() {
        for (locale in listOf("en", "fr", "ar")) {
            val view = layout(locale)
            bind(view, snapshot().copy(chargedAttempts = 17, confirmedAdmissions = 19, backendEntries = 23,
                unknownSettlements = 29, lastRequestElapsedRealtimeMillis = 100, lastResultRecordedElapsedRealtimeMillis = 234_567))
            for ((id, number) in listOf(R.id.recovery_counts_attempts to 17, R.id.recovery_counts_admissions to 19,
                R.id.recovery_counts_requests to 23, R.id.recovery_counts_uncertain to 29)) {
                assertEquals(String.format(Locale.forLanguageTag(locale), "%d", number), text(view, id))
            }
            assertEquals(String.format(Locale.forLanguageTag(locale), "%d ms", 234_567), text(view, R.id.recovery_result_recorded))
            assertFalse(text(view, R.id.recovery_result_recorded).contains("100"))
            assertFalse(text(view, R.id.recovery_counts_attempts).contains("/"))
        }
    }

    @Test fun sharedTransportRetainsFailureBesideHistoricalAuthenticationAndLaterTrigger() {
        val view = layout()
        val session = session()
        val oldRequestAuthentication = authenticated(session, requested, 99)
        val laterOtherAuthentication = authenticated(session, first, 500)
        val recorded = linkedMapOf(healthy to diagnostics(authenticated(session, healthy, 600)),
            first to diagnostics(laterOtherAuthentication), requested to diagnostics(oldRequestAuthentication))
        val request = snapshot().copy(lastRequestPeer = requested, lastTriggerPeer = first,
            lastRequestElapsedRealtimeMillis = 100, lastOutcome = RecoveryResult.Outcome.TRANSPORT_FAILED,
            transportState = ObservableTunnel.RecoveryTransportState.UNAVAILABLE, lastResultRecordedElapsedRealtimeMillis = 120)
        for (keys in listOf(listOf(first, requested, healthy, endpointFree), listOf(endpointFree, healthy, requested, first))) {
            bind(view, request, recorded, profile(keys))
            assertEquals(view.context.getString(R.string.vpn0wn_peer_title, "%02d".format(keys.indexOf(requested) + 1)),
                text(view, R.id.recovery_request_peer))
            assertText(view, R.id.recovery_authentication, R.string.vpn0wn_recovery_unknown)
            val later = recorded + (requested to diagnostics(authenticated(session, requested, 150)))
            bind(view, request, later, profile(keys))
            assertEquals(view.context.getString(R.string.vpn0wn_recovery_authenticated_later, 150), text(view, R.id.recovery_authentication))
            assertText(view, R.id.recovery_transport, R.string.vpn0wn_recovery_transport_unavailable)
            assertText(view, R.id.recovery_result, R.string.vpn0wn_recovery_result_transport_failed)
        }
    }

    @Test fun authenticationRequiresConfiguredExactRequestPeerAndStrictLaterActiveSessionEvidence() {
        val view = layout()
        val session = session()
        val request = snapshot().copy(lastRequestPeer = requested, lastRequestElapsedRealtimeMillis = 100)
        for (time in listOf(99L, 100L)) {
            bind(view, request, mapOf(requested to diagnostics(authenticated(session, requested, time))), profile(listOf(requested)))
            assertText(view, R.id.recovery_authentication, R.string.vpn0wn_recovery_unknown)
        }
        val later = authenticated(session, requested, 101)
        for (config in listOf(null, profile(listOf(first)))) {
            bind(view, request, mapOf(requested to diagnostics(later)), config)
            assertText(view, R.id.recovery_request_peer, R.string.vpn0wn_recovery_peer_unavailable)
            assertText(view, R.id.recovery_authentication, R.string.vpn0wn_recovery_unknown)
        }
        bind(view, request.copy(lastRequestElapsedRealtimeMillis = null), mapOf(requested to diagnostics(later)), profile(listOf(requested)))
        assertText(view, R.id.recovery_authentication, R.string.vpn0wn_recovery_unknown)
        bind(view, request, mapOf(requested to diagnostics(authenticated(session, first, 200))), profile(listOf(requested)))
        assertText(view, R.id.recovery_authentication, R.string.vpn0wn_recovery_unknown)
        val replacement = session()
        bind(view, request, mapOf(requested to ObservableTunnel.PeerHandshakeDiagnostics(
            authenticated(replacement, requested, 300), lastAuthenticated = later)), profile(listOf(requested)))
        assertText(view, R.id.recovery_authentication, R.string.vpn0wn_recovery_unknown)
        HandshakeSession::class.java.getDeclaredMethod("retire").apply { isAccessible = true }.invoke(session)
        bind(view, request, mapOf(requested to diagnostics(later)), profile(listOf(requested)))
        assertText(view, R.id.recovery_authentication, R.string.vpn0wn_recovery_unknown)
    }

    @Test fun transportStatesAreExhaustiveAndNeverInferredFromAuthentication() {
        val view = layout()
        val cases = mapOf(ObservableTunnel.RecoveryTransportState.UNKNOWN to R.string.vpn0wn_recovery_unknown,
            ObservableTunnel.RecoveryTransportState.READY to R.string.vpn0wn_recovery_transport_ready,
            ObservableTunnel.RecoveryTransportState.UNAVAILABLE to R.string.vpn0wn_recovery_transport_unavailable)
        assertEquals(ObservableTunnel.RecoveryTransportState.entries.toSet(), cases.keys)
        for ((state, text) in cases) {
            bind(view, snapshot().copy(transportState = state))
            assertText(view, R.id.recovery_transport, text)
        }
    }

    @Test fun duplicateConfiguredRequestKeysKeepAttributionAndAuthenticationUnknown() {
        val view = layout()
        val request = snapshot().copy(chargedAttempts = 1, lastRequestPeer = requested, lastRequestElapsedRealtimeMillis = 100)
        val evidence = mapOf(requested to diagnostics(authenticated(session(), requested, 200)))
        val parsed = profile(listOf(first, requested, requested))
        val original = profile(listOf(requested))
        val built = Config.Builder().setInterface(original.`interface`).addPeers(original.peers).addPeer(original.peers.single()).build()
        for (config in listOf(parsed, built)) {
            assertEquals(2, config.peers.count { it.publicKey == requested })
            bind(view, request, evidence, config)
            assertText(view, R.id.recovery_request_peer, R.string.vpn0wn_recovery_peer_unavailable)
            assertText(view, R.id.recovery_authentication, R.string.vpn0wn_recovery_unknown)
            assertEquals("1", text(view, R.id.recovery_counts_attempts))
        }
        val uniqueRequest = profile(listOf(first, first, requested))
        bind(view, request, evidence, uniqueRequest)
        assertEquals(view.context.getString(R.string.vpn0wn_peer_title, "03"), text(view, R.id.recovery_request_peer))
        assertEquals(view.context.getString(R.string.vpn0wn_recovery_authenticated_later, 200), text(view, R.id.recovery_authentication))
    }

    private fun layout(locale: String = "en"): LinearLayout {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) }
        return LinearLayout(context.createConfigurationContext(configuration)).apply {
            for (resource in ids) addView(TextView(this.context).apply { id = resource })
        }
    }

    private fun bind(view: LinearLayout, snapshot: ObservableTunnel.RecoverySnapshot?,
                     diagnostics: Map<Key, ObservableTunnel.PeerHandshakeDiagnostics>? = null,
                     config: Config? = null, state: Tunnel.State? = Tunnel.State.UP) =
        BindingAdapters.setRecoveryDiagnostics(view, snapshot, diagnostics, config, state)

    private fun snapshot() = ObservableTunnel.RecoverySnapshot(0, 0, 0, 0, false, false, null, null, null, null,
        ObservableTunnel.RecoveryStatus.IDLE, null, ObservableTunnel.RecoveryTransportState.UNKNOWN, ObservableTunnel.RecoveryPowerState.UNKNOWN)

    private fun text(view: LinearLayout, id: Int) = view.findViewById<TextView>(id).text.toString()

    private fun assertText(view: LinearLayout, id: Int, text: Int) = assertEquals(view.context.getString(text), text(view, id))

    private fun profile(keys: List<Key>): Config = Config.parse(("""
        [Interface]
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        Address = 192.0.2.2/32
    """.trimIndent() + keys.joinToString("") { "\n[Peer]\nPublicKey = ${it.toBase64()}\nAllowedIPs = 192.0.2.1/32\n" }).byteInputStream())

    private fun diagnostics(observation: HandshakeObservation) = ObservableTunnel.PeerHandshakeDiagnostics(observation,
        lastAuthenticated = observation)

    private fun authenticated(session: HandshakeSession, key: Key, time: Long) = HandshakeObservation(session, key,
        HandshakeObservation.Event.AUTHENTICATED, false, null, HandshakeObservation.AddressFamily.UNKNOWN, time, time)

    private fun session(): HandshakeSession = HandshakeSession::class.java.getDeclaredConstructor()
        .apply { isAccessible = true }.newInstance().also {
            HandshakeSession::class.java.getDeclaredMethod("activate").apply { isAccessible = true }.invoke(it)
        }
}
