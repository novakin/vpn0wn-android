/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.fragment

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.databinding.DataBindingUtil
import com.vpn0wn.android.R
import com.vpn0wn.android.backend.HandshakeObservation
import com.vpn0wn.android.backend.HandshakeSession
import com.vpn0wn.android.backend.RecoveryResult
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.databinding.TunnelDetailFragmentBinding
import com.vpn0wn.android.databinding.TunnelDetailPeerBinding
import com.vpn0wn.android.model.ObservableTunnel
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.config.Config as TunnelConfig
import com.vpn0wn.config.InetEndpoint
import com.vpn0wn.config.Interface
import com.vpn0wn.config.Peer
import com.vpn0wn.crypto.Key
import com.vpn0wn.crypto.KeyPair
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecoveryReadoutsLayoutTest {
    @get:Rule val folder = TemporaryFolder()
    private val firstPeer = Key.fromBytes(ByteArray(KEY_LENGTH) { 1 })
    private val requestPeer = Key.fromBytes(ByteArray(KEY_LENGTH) { 2 })

    @Test fun boundedRecoveryReadoutsRemainCompleteReachableAndReadOnly() = runTest {
        val environment = TunnelTestEnvironment(folder.root, backgroundScope)
        val originalQualifiers = RuntimeEnvironment.getQualifiers()
        val originalFontScale = RuntimeEnvironment.getApplication().resources.configuration.fontScale
        val originalDirection = RuntimeEnvironment.getApplication().resources.configuration.layoutDirection
        val originalLocale = Locale.getDefault()
        try {
            for (preview in previews()) for (night in listOf(false, true)) {
                val case = preview.copy(night = night)
                configure(case)
                val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
                controller.get().setTheme(R.style.AppTheme)
                controller.setup()
                val activity = controller.get()
                activity.supportActionBar?.hide()
                val binding = TunnelDetailFragmentBinding.inflate(activity.layoutInflater)
                try {
                    val profile = profile()
                    val tunnel = ObservableTunnel(environment.manager, "synthetic", profile, case.state)
                    populate(tunnel, case)
                    binding.config = profile
                    binding.tunnel = tunnel
                    activity.setContentView(binding.root)
                    binding.executePendingBindings()
                    layout(binding.root, case)
                    assertConfiguration(binding.root, case)
                    assertSemantics(binding, case)
                    assertReadouts(binding, case)
                    assertPeers(binding)
                    revealLine(binding.recoveryTitle, 0, binding.root, case)
                    capture(binding.root, case, "top")
                    if (!fullyVisible(binding.recoveryStatus)) {
                        revealReadout(binding.recoveryStatus, binding.root, case)
                        capture(binding.root, case, "status")
                    }
                    revealLine(binding.recoveryTransport, 0, binding.root, case)
                    capture(binding.root, case, "middle")
                    if (case.snapshot?.lastOutcome != null || case.snapshot?.failureCategory != null) {
                        revealReadout(binding.recoveryResult, binding.root, case)
                        capture(binding.root, case, "result")
                        if (!fullyVisible(binding.recoveryResult)) {
                            revealLine(binding.recoveryResult, binding.recoveryResult.lineCount - 1, binding.root, case)
                            capture(binding.root, case, "result-end")
                        }
                        revealReadout(binding.recoveryAuthentication, binding.root, case)
                        capture(binding.root, case, "authentication")
                    }
                    revealLine(binding.recoveryScope, binding.recoveryScope.lineCount - 1, binding.root, case)
                    capture(binding.root, case, "bottom")
                } finally {
                    binding.unbind()
                    controller.pause().stop().destroy()
                }
            }
        } finally {
            RuntimeEnvironment.setQualifiers(originalQualifiers)
            RuntimeEnvironment.setFontScale(originalFontScale)
            RuntimeEnvironment.setQualifiers(if (originalDirection == View.LAYOUT_DIRECTION_RTL) "+ldrtl" else "+ldltr")
            Locale.setDefault(originalLocale)
            environment.close()
        }
    }

    private fun previews() = listOf(
        Preview("unavailable-down", "en", 320, 640, 1f, state = Tunnel.State.DOWN),
        Preview("unavailable-kernel", "ar", 640, 320, 2f, rtl = true),
        Preview("idle-zero", "fr", 320, 640, 1f, snapshot = snapshot(ObservableTunnel.RecoveryStatus.IDLE)),
        Preview("pending-busy-inhibited", "fr", 640, 320, 2f, snapshot = snapshot(
            ObservableTunnel.RecoveryStatus.DROPPED_BUSY).copy(requestPending = true, automaticRecoveryDisabled = true)),
        Preview("exhausted-budget", "ar", 320, 640, 2f, rtl = true, snapshot = snapshot(
            ObservableTunnel.RecoveryStatus.DROPPED_ATTEMPT_LIMIT).copy(chargedAttempts = 2, confirmedAdmissions = 2,
            backendEntries = 4, unknownSettlements = 1)),
        Preview("transport-failed-old-auth", "en", 640, 320, 2f, snapshot = settled(
            RecoveryResult.Outcome.TRANSPORT_FAILED, ObservableTunnel.RecoveryTransportState.UNAVAILABLE),
            authenticationTime = REQUEST_UPTIME - 1),
        Preview("long-french-safe-failure", "fr", 320, 640, 2f, snapshot = settled(
            RecoveryResult.Outcome.INITIATION_SEND_FAILED, ObservableTunnel.RecoveryTransportState.READY)
            .copy(failureCategory = ObservableTunnel.RecoveryFailureCategory.SERVICE_UNAVAILABLE)),
        Preview("dns-failure-en", "en", 320, 640, 1f, snapshot = dnsFailure()),
        Preview("dns-failure-fr-large", "fr", 320, 640, 2f, snapshot = dnsFailure()),
        Preview("dns-failure-rtl-large", "ar", 640, 320, 2f, rtl = true, snapshot = dnsFailure()),
        Preview("later-authentication-multiple-peers", "ar", 640, 320, 1f, rtl = true, snapshot = settled(
            RecoveryResult.Outcome.INITIATION_STARTED, ObservableTunnel.RecoveryTransportState.READY),
            authenticationTime = REQUEST_UPTIME + 1),
    )

    private fun snapshot(status: ObservableTunnel.RecoveryStatus) = ObservableTunnel.RecoverySnapshot(
        confirmedAdmissions = 0, chargedAttempts = 0, backendEntries = 0, unknownSettlements = 0,
        requestPending = false, automaticRecoveryDisabled = false, lastTriggerPeer = null, lastRequestPeer = null,
        lastRequestElapsedRealtimeMillis = null, lastOutcome = null, status = status, failureCategory = null,
        transportState = ObservableTunnel.RecoveryTransportState.UNKNOWN,
        powerState = ObservableTunnel.RecoveryPowerState.UNKNOWN,
    )

    private fun settled(outcome: RecoveryResult.Outcome, transport: ObservableTunnel.RecoveryTransportState) =
        snapshot(ObservableTunnel.RecoveryStatus.COMPLETED).copy(confirmedAdmissions = 1, chargedAttempts = 1,
            backendEntries = 1, lastTriggerPeer = firstPeer, lastRequestPeer = requestPeer,
            lastRequestElapsedRealtimeMillis = REQUEST_UPTIME, lastOutcome = outcome, transportState = transport,
            lastResultRecordedElapsedRealtimeMillis = RESULT_UPTIME)

    private fun dnsFailure() = snapshot(ObservableTunnel.RecoveryStatus.FAILED).copy(
        backendEntries = 1, lastTriggerPeer = firstPeer, lastRequestPeer = requestPeer,
        lastRequestElapsedRealtimeMillis = REQUEST_UPTIME,
        failureCategory = ObservableTunnel.RecoveryFailureCategory.DNS_RESOLUTION,
        lastResultRecordedElapsedRealtimeMillis = RESULT_UPTIME,
    )

    private fun populate(tunnel: ObservableTunnel, case: Preview) {
        if (case.snapshot == null) return
        val session = HandshakeSession::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        HandshakeSession::class.java.getDeclaredMethod("activate").apply { isAccessible = true }.invoke(session)
        @Suppress("UNCHECKED_CAST")
        val sessionOwner = ObservableTunnel::class.java.getDeclaredField("handshakeSession")
            .apply { isAccessible = true }.get(tunnel) as AtomicReference<HandshakeSession?>
        sessionOwner.set(session)
        val diagnostics = linkedMapOf(
            requestPeer to diagnostics(session, requestPeer, case.authenticationTime),
            firstPeer to diagnostics(session, firstPeer, REQUEST_UPTIME + OTHER_AUTH_OFFSET),
        )
        for ((field, value) in mapOf("cachedHandshakeSession" to session, "cachedHandshakeDiagnostics" to diagnostics)) {
            ObservableTunnel::class.java.getDeclaredField(field).apply { isAccessible = true }.set(tunnel, value)
        }
        tunnel.onRecoverySnapshotChanged(session, case.snapshot)
    }

    private fun diagnostics(session: HandshakeSession, peer: Key, authenticatedAt: Long?): ObservableTunnel.PeerHandshakeDiagnostics {
        val authentication = authenticatedAt?.let {
            HandshakeObservation(session, peer, HandshakeObservation.Event.AUTHENTICATED, false,
                InetEndpoint.parse(OBSERVED_ENDPOINT), HandshakeObservation.AddressFamily.IPV6, 1, it)
        }
        val latest = HandshakeObservation(session, peer, HandshakeObservation.Event.SENT, false,
            InetEndpoint.parse(OBSERVED_ENDPOINT), HandshakeObservation.AddressFamily.IPV6, 2, RESULT_UPTIME)
        return ObservableTunnel.PeerHandshakeDiagnostics(latest, lastAuthenticated = authentication)
    }

    private fun assertSemantics(binding: TunnelDetailFragmentBinding, case: Preview) {
        val context = binding.root.context
        val unavailable = context.getString(R.string.vpn0wn_recovery_unavailable)
        if (case.snapshot == null) {
            for (id in VALUE_IDS) assertEquals(unavailable, binding.root.findViewById<TextView>(id).text.toString())
        } else {
            val snapshot = case.snapshot
            assertEquals(context.getString(R.string.vpn0wn_recovery_count, snapshot.chargedAttempts),
                binding.recoveryCountsAttempts.text.toString())
            if (snapshot.status == ObservableTunnel.RecoveryStatus.IDLE) {
                assertEquals(context.getString(R.string.vpn0wn_recovery_idle), binding.recoveryStatus.text.toString())
                assertEquals(context.getString(R.string.vpn0wn_recovery_result_unrecorded), binding.recoveryResult.text.toString())
            }
            if (snapshot.requestPending) assertTrue(binding.recoveryStatus.text.contains(context.getString(R.string.vpn0wn_recovery_pending_fact)))
            if (snapshot.automaticRecoveryDisabled) assertTrue(binding.recoveryStatus.text.contains(context.getString(R.string.vpn0wn_recovery_disabled_fact)))
            if (snapshot.transportState == ObservableTunnel.RecoveryTransportState.UNAVAILABLE) {
                assertEquals(context.getString(R.string.vpn0wn_recovery_transport_unavailable), binding.recoveryTransport.text.toString())
            }
            if (snapshot.failureCategory == ObservableTunnel.RecoveryFailureCategory.DNS_RESOLUTION) {
                assertEquals(context.getString(R.string.vpn0wn_recovery_failure_dns), binding.recoveryResult.text.toString())
            }
            val authentication = if (case.authenticationTime != null && case.authenticationTime > REQUEST_UPTIME)
                context.getString(R.string.vpn0wn_recovery_authenticated_later, case.authenticationTime)
            else context.getString(R.string.vpn0wn_recovery_unknown)
            assertEquals(authentication, binding.recoveryAuthentication.text.toString())
            if (snapshot.lastRequestPeer != null) assertEquals(context.getString(R.string.vpn0wn_peer_title,
                String.format(context.resources.configuration.locales[0], "%02d", 2)), binding.recoveryRequestPeer.text.toString())
        }
    }

    private fun assertReadouts(binding: TunnelDetailFragmentBinding, case: Preview) {
        val root = binding.root
        val content = binding.recoveryCardContent
        val readouts = descendants(content).filterIsInstance<TextView>().toList()
        assertEquals("Ten labels, ten values and scope footer", VALUE_IDS.size * 2 + 1, readouts.size)
        for (text in readouts + binding.recoveryTitle) {
            val regular = text.id !in VALUE_IDS && text !== binding.recoveryTitle
            assertEquals(ResourcesCompat.getFont(text.context, if (regular) R.font.jetbrains_mono_regular
                else R.font.jetbrains_mono_medium), text.typeface)
            assertEquals("ss01, zero, tnum", text.fontFeatureSettings)
            assertFalse("Packaged face is not synthesized", text.paint.isFakeBoldText)
            assertFalse("Read-only presentation", text.isClickable || text.isLongClickable || text.isFocusable)
            val node = text.createAccessibilityNodeInfo()
            try {
                assertFalse("No editable or button semantics", node.isEditable || node.isClickable || node.isLongClickable)
                assertEquals("android.widget.TextView", node.className.toString())
                assertFalse("Readout has no copy or click action", node.actionList.any { it.id in listOf(
                    AccessibilityNodeInfo.ACTION_CLICK, AccessibilityNodeInfo.ACTION_LONG_CLICK, AccessibilityNodeInfo.ACTION_COPY) })
            } finally { node.recycle() }
            assertTextFits(text)
            for (line in 0 until text.lineCount) revealLine(text, line, root, case)
        }
        for (index in readouts.indices) for (other in index + 1 until readouts.size) {
            assertFalse("Readout label/value bounds do not overlap", Rect.intersects(bounds(content, readouts[index]), bounds(content, readouts[other])))
        }
        for (id in VALUE_IDS) assertEquals("Shared activation readout appears once", 1, descendants(root).count { it.id == id })
        assertFalse("Readout card has no action", descendants(content).any { it.isClickable || it.isLongClickable })
        val parent = root as ViewGroup
        assertTrue("New section stays after all peers", bounds(parent, binding.recoveryTitle).top >= bounds(parent, binding.peersLayout).bottom)
    }

    private fun assertPeers(binding: TunnelDetailFragmentBinding) {
        assertEquals("Configured peers remain separate", 2, binding.peersLayout.childCount)
        for (index in 0 until binding.peersLayout.childCount) {
            val peer = requireNotNull(DataBindingUtil.getBinding<TunnelDetailPeerBinding>(binding.peersLayout.getChildAt(index)))
            assertEquals("Existing observed endpoint remains LTR", View.TEXT_DIRECTION_LTR, peer.observedEndpointText.textDirection)
            assertEquals("Existing endpoint copy row remains interactive", true, peer.endpointRow.isClickable && peer.endpointRow.isFocusable)
        }
    }

    private fun assertTextFits(view: TextView) {
        val text = requireNotNull(view.layout)
        val width = view.width - view.compoundPaddingLeft - view.compoundPaddingRight
        val height = view.height - view.compoundPaddingTop - view.compoundPaddingBottom
        assertTrue("All characters laid out: ${view.text}", text.lineCount > 0 && text.getLineEnd(text.lineCount - 1) == text.text.length)
        assertTrue("Complete text height: ${view.text}", text.height <= height)
        for (line in 0 until text.lineCount) {
            assertEquals("No ellipsis: ${view.text}", 0, text.getEllipsisCount(line))
            assertTrue("Complete line width: ${view.text}", text.getLineLeft(line) >= -INK_TOLERANCE_PX &&
                text.getLineRight(line) <= width + INK_TOLERANCE_PX)
        }
        val bitmap = Bitmap.createBitmap(text.width + INK_MARGIN_PX * 2, text.height + INK_MARGIN_PX * 2, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.translate(INK_MARGIN_PX.toFloat(), INK_MARGIN_PX.toFloat())
            text.draw(canvas)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("Native glyphs painted: ${view.text}", pixels.any { it ushr ALPHA_SHIFT != 0 })
            assertTrue("Complete glyph bounds: ${view.text}", pixels.indices.all {
                if (pixels[it] ushr ALPHA_SHIFT == 0) true else {
                    val x = it % bitmap.width - INK_MARGIN_PX
                    val y = it / bitmap.width - INK_MARGIN_PX
                    x >= -INK_TOLERANCE_PX && x < width + INK_TOLERANCE_PX && y >= -INK_TOLERANCE_PX && y < height + INK_TOLERANCE_PX
                }
            })
        } finally { bitmap.recycle() }
    }

    private fun revealLine(view: TextView, line: Int, root: View, case: Preview) {
        val text = requireNotNull(view.layout)
        val top = view.compoundPaddingTop + text.getLineTop(line)
        val bottom = view.compoundPaddingTop + text.getLineBottom(line)
        view.requestRectangleOnScreen(Rect(0, top, view.width, bottom), true)
        layout(root, case)
        val visible = Rect()
        assertTrue("Line is reachable by native scrolling: ${view.text}", view.getGlobalVisibleRect(visible))
        val position = IntArray(2).also(view::getLocationInWindow)
        val lineBounds = Rect(position[0], position[1] + top, position[0] + view.width, position[1] + bottom)
        assertTrue("Complete line visible: ${view.text}; line=$lineBounds visible=$visible scrollY=${scroll(view).scrollY}", visible.contains(lineBounds))
    }

    private fun scroll(view: View): ScrollView =
        generateSequence(view.parent as? View) { it.parent as? View }.filterIsInstance<ScrollView>().single()

    private fun revealReadout(view: TextView, root: View, case: Preview) {
        val scroll = scroll(view)
        val available = scroll.height - scroll.paddingTop - scroll.paddingBottom
        val row = view.parent as View
        val target = if (row.height <= available) row else view
        if (target.height <= available) {
            target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true)
            layout(root, case)
            assertTrue("Complete fitting readout is reachable: ${view.text}", fullyVisible(target))
        } else revealLine(view, 0, root, case)
    }

    private fun fullyVisible(view: View): Boolean {
        val visible = Rect()
        return view.getLocalVisibleRect(visible) && visible == Rect(0, 0, view.width, view.height)
    }

    private fun configure(case: Preview) {
        val orientation = if (case.width > case.height) "land" else "port"
        RuntimeEnvironment.setQualifiers("${case.locale}-w${case.width}dp-h${case.height}dp-$orientation-${if (case.night) "night" else "notnight"}-mdpi")
        RuntimeEnvironment.setFontScale(case.scale)
        RuntimeEnvironment.setQualifiers(if (case.rtl) "+ldrtl" else "+ldltr")
    }

    private fun assertConfiguration(root: View, case: Preview) {
        val config = root.resources.configuration
        assertEquals("Actual requested locale", case.locale, config.locales[0].language)
        assertEquals("Actual requested font scale", case.scale, config.fontScale, 0f)
        val direction = if (case.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        assertEquals("Actual resource direction", direction, config.layoutDirection)
        assertEquals("Actual inherited native direction", direction, root.layoutDirection)
        assertEquals("Actual requested system theme", if (case.night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
            config.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertEquals("Strict requested decor width", case.width, root.rootView.width)
        assertEquals("Strict requested decor height", case.height, root.rootView.height)
        assertEquals("Strict requested content width", case.width, root.width)
        assertEquals("Strict requested content height", case.height, root.height)
    }

    private fun layout(root: View, case: Preview) {
        val viewport = root.rootView
        var ready = false
        repeat(LAYOUT_PASSES) {
            viewport.measure(View.MeasureSpec.makeMeasureSpec(case.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(case.height, View.MeasureSpec.EXACTLY))
            viewport.layout(0, 0, viewport.measuredWidth, viewport.measuredHeight)
            ready = !viewport.viewTreeObserver.dispatchOnPreDraw()
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue("Settled native traversal permits drawing", ready)
    }

    private fun capture(root: View, case: Preview, position: String) {
        layout(root, case)
        assertConfiguration(root, case)
        val project = File(requireNotNull(System.getProperty("user.dir"))).let { if (it.name == "ui") it.parentFile else it }
        val output = System.getenv("VPN0WN_RECOVERY_READOUTS_OUTPUT")?.let(::File)
            ?: File(project, ".artifacts/recovery-readout-previews")
        assertTrue("Recovery preview output available", output.isDirectory || output.mkdirs())
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            File(output, "${case.name}-${case.locale}-${case.width}x${case.height}-${case.scale}-${if (case.night) "night" else "day"}-$position.png")
                .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun bounds(root: ViewGroup, child: View): Rect = Rect(0, 0, child.width, child.height).also {
        root.offsetDescendantRectToMyCoords(child, it)
    }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }

    private fun profile(): TunnelConfig = TunnelConfig.Builder()
        .setInterface(Interface.Builder().setKeyPair(KeyPair(Key.fromBytes(ByteArray(KEY_LENGTH))))
            .parseAddresses("192.0.2.2/32").setListenPort(SYNTHETIC_PORT).build())
        .addPeer(Peer.Builder().setPublicKey(firstPeer).parseEndpoint("198.51.100.42:51820").parseAllowedIPs("0.0.0.0/0").build())
        .addPeer(Peer.Builder().setPublicKey(requestPeer).parseEndpoint("[2001:db8::2]:51820").parseAllowedIPs("::/0").build())
        .build()

    private data class Preview(val name: String, val locale: String, val width: Int, val height: Int, val scale: Float,
        val rtl: Boolean = false, val night: Boolean = false, val state: Tunnel.State = Tunnel.State.UP,
        val snapshot: ObservableTunnel.RecoverySnapshot? = null, val authenticationTime: Long? = null)

    private companion object {
        val VALUE_IDS = listOf(R.id.recovery_status, R.id.recovery_counts_attempts, R.id.recovery_counts_admissions,
            R.id.recovery_counts_requests, R.id.recovery_counts_uncertain, R.id.recovery_transport, R.id.recovery_request_peer,
            R.id.recovery_result, R.id.recovery_result_recorded, R.id.recovery_authentication)
        const val LAYOUT_PASSES = 3
        const val KEY_LENGTH = 32
        const val SYNTHETIC_PORT = 51820
        const val REQUEST_UPTIME = 100_000L
        const val RESULT_UPTIME = 100_100L
        const val OTHER_AUTH_OFFSET = 10L
        const val OBSERVED_ENDPOINT = "[2001:db8:1234:5678::abcd]:51820"
        const val INK_MARGIN_PX = 20
        const val INK_TOLERANCE_PX = 1
        const val ALPHA_SHIFT = 24
    }
}
