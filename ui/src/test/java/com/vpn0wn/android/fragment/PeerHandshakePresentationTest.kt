package com.vpn0wn.android.fragment

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.databinding.DataBindingUtil
import androidx.databinding.Observable
import com.vpn0wn.android.BR
import com.vpn0wn.android.R
import com.vpn0wn.android.backend.HandshakeObservation
import com.vpn0wn.android.backend.HandshakeSession
import com.vpn0wn.android.backend.Statistics
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.databinding.BindingAdapters
import com.vpn0wn.android.databinding.TunnelDetailFragmentBinding
import com.vpn0wn.android.databinding.TunnelDetailPeerBinding
import com.vpn0wn.android.model.ObservableTunnel
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.config.Config as TunnelConfig
import com.vpn0wn.config.InetEndpoint
import com.vpn0wn.config.Peer
import com.vpn0wn.crypto.Key
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class PeerHandshakePresentationTest {
    @get:Rule val folder = TemporaryFolder()
    private val controllers = mutableListOf<ActivityController<HostActivity>>()
    private lateinit var environment: TunnelTestEnvironment
    private val firstKey = Key.fromBytes(ByteArray(32) { 1 })
    private val secondKey = Key.fromBytes(ByteArray(32) { 2 })
    private val thirdKey = Key.fromBytes(ByteArray(32) { 3 })

    @After fun tearDown() {
        controllers.forEach { it.pause().stop().destroy() }
        Dispatchers.resetMain()
    }

    @Test fun everyNativeEventAndProtocolRetryKeepsItsOwnMeaning() = runTest {
        val layout = peerLayout()
        val peers = listOf(peer(firstKey))
        val session = session()
        var previous: Map<Key, ObservableTunnel.PeerHandshakeDiagnostics>? = null
        val cases = listOf(
            Triple(HandshakeObservation.Event.ATTEMPT, false, R.string.tunnl_handshake_attempted),
            Triple(HandshakeObservation.Event.ATTEMPT, true, R.string.tunnl_handshake_retry_attempted),
            Triple(HandshakeObservation.Event.SENT, false, R.string.tunnl_handshake_sent),
            Triple(HandshakeObservation.Event.SEND_FAILED, false, R.string.tunnl_handshake_send_failed),
            Triple(HandshakeObservation.Event.AUTHENTICATED, false, R.string.tunnl_handshake_authenticated),
            Triple(HandshakeObservation.Event.EXHAUSTED, true, R.string.tunnl_handshake_exhausted)
        )
        assertEquals(HandshakeObservation.Event.values().toSet(), cases.map { it.first }.toSet())
        BindingAdapters.setDetailPeers(layout, null, null, null, peers, emptyMap(), Tunnel.State.UP)
        val binding = binding(layout)
        binding.statistics = Statistics.PeerStats(2048L, 1024L, 0L)
        for ((event, retry, text) in cases) {
            val current = mapOf(firstKey to diagnostics(observation(session, firstKey, event, retry = retry)))
            BindingAdapters.setDetailPeers(layout, peers, previous, Tunnel.State.UP, peers, current, Tunnel.State.UP)
            assertSame(binding.root, layout.getChildAt(0))
            assertStatus(binding, text)
            assertEquals(View.VISIBLE, binding.transferGrid.visibility)
            assertEquals(View.GONE, binding.latestHandshakeRow.visibility)
            previous = current
        }
    }

    @Test fun unavailableEmptyAndMissingPeerObservationsStayDistinct() = runTest {
        val layout = peerLayout()
        val peers = listOf(peer(firstKey), peer(secondKey), peer(thirdKey))
        val session = session()
        val recorded = mapOf(
            firstKey to diagnostics(observation(session, firstKey, HandshakeObservation.Event.AUTHENTICATED)),
            secondKey to diagnostics(observation(session, secondKey, HandshakeObservation.Event.SEND_FAILED))
        )
        BindingAdapters.setDetailPeers(layout, null, null, null, peers, recorded, Tunnel.State.UP)
        assertStatus(binding(layout, 0), R.string.tunnl_handshake_authenticated)
        assertStatus(binding(layout, 1), R.string.tunnl_handshake_send_failed)
        assertStatus(binding(layout, 2), R.string.tunnl_handshake_unobserved)
        assertUnknownEndpoint(binding(layout, 2))
        BindingAdapters.setDetailPeers(layout, peers, recorded, Tunnel.State.UP, peers, emptyMap(), Tunnel.State.UP)
        for (index in peers.indices) {
            assertStatus(binding(layout, index), R.string.tunnl_handshake_unobserved)
            assertUnknownEndpoint(binding(layout, index))
        }
        binding(layout).statistics = Statistics.PeerStats(0L, 0L, 1L)
        BindingAdapters.setDetailPeers(layout, peers, emptyMap(), Tunnel.State.UP, peers, null, Tunnel.State.UP)
        for (index in peers.indices) {
            assertStatus(binding(layout, index), R.string.tunnl_handshake_unavailable)
            assertUnknownEndpoint(binding(layout, index))
        }
        assertEquals(View.VISIBLE, binding(layout).latestHandshakeRow.visibility)
    }

    @Test fun observedNumericEndpointAndFamilyNeverUseConfiguredHostname() = runTest {
        val layout = peerLayout()
        val peers = listOf(peer(firstKey))
        val session = session()
        val cases = listOf(
            Triple("192.0.2.44:51820", HandshakeObservation.AddressFamily.IPV4, "IPv4"),
            Triple("[2001:db8::44]:51821", HandshakeObservation.AddressFamily.IPV6, "IPv6"),
            Triple("192.0.2.44:51820", HandshakeObservation.AddressFamily.UNKNOWN, "—"),
            Triple(null, HandshakeObservation.AddressFamily.IPV6, "IPv6"),
            Triple(null, HandshakeObservation.AddressFamily.UNKNOWN, "—")
        )
        BindingAdapters.setDetailPeers(layout, null, null, null, peers, emptyMap(), Tunnel.State.UP)
        for ((endpoint, family, familyText) in cases) {
            val current = mapOf(firstKey to diagnostics(observation(session, firstKey, HandshakeObservation.Event.SENT,
                endpoint = endpoint, family = family)))
            BindingAdapters.setDetailPeers(layout, peers, null, Tunnel.State.UP, peers, current, Tunnel.State.UP)
            val binding = binding(layout)
            assertEquals("configured.example.test:51820", binding.endpointText.text.toString())
            assertEquals(endpoint ?: "—", binding.observedEndpointText.text.toString())
            assertEquals(familyText, binding.observedAddressFamilyText.text.toString())
            assertStatus(binding, R.string.tunnl_handshake_sent)
        }
    }

    @Test fun replacingPeerConfigurationRebindsUnchangedDiagnosticsByPublicKey() = runTest {
        val layout = peerLayout()
        val original = listOf(peer(firstKey))
        val session = session()
        val recorded = mapOf(
            firstKey to diagnostics(observation(session, firstKey, HandshakeObservation.Event.AUTHENTICATED)),
            secondKey to diagnostics(observation(session, secondKey, HandshakeObservation.Event.EXHAUSTED))
        )
        BindingAdapters.setDetailPeers(layout, null, null, null, original, recorded, Tunnel.State.UP)
        val old = binding(layout)
        val replacement = listOf(peer(secondKey), peer(firstKey), peer(thirdKey))
        BindingAdapters.setDetailPeers(layout, original, recorded, Tunnel.State.UP, replacement, recorded, Tunnel.State.UP)
        assertEquals(3, layout.childCount)
        assertNotSame(old.root, binding(layout, 1).root)
        assertSame(recorded[secondKey], binding(layout, 0).diagnostics)
        assertSame(recorded[firstKey], binding(layout, 1).diagnostics)
        assertStatus(binding(layout, 0), R.string.tunnl_handshake_exhausted)
        assertStatus(binding(layout, 1), R.string.tunnl_handshake_authenticated)
        assertStatus(binding(layout, 2), R.string.tunnl_handshake_unobserved)
        BindingAdapters.setDetailPeers(layout, replacement, recorded, Tunnel.State.UP, null, recorded, Tunnel.State.UP)
        assertEquals(0, layout.childCount)
    }

    @Test fun downOrMissingStateClearsRecordedFactsEvenWhenPreviousSnapshotIsStillPassed() = runTest {
        val layout = peerLayout()
        val peers = listOf(peer(firstKey))
        val recorded = mapOf(firstKey to diagnostics(observation(session(), firstKey, HandshakeObservation.Event.AUTHENTICATED)))
        BindingAdapters.setDetailPeers(layout, null, null, null, peers, recorded, Tunnel.State.UP)
        for (state in listOf(Tunnel.State.DOWN, null)) {
            BindingAdapters.setDetailPeers(layout, peers, recorded, state, peers, recorded, Tunnel.State.UP)
            assertStatus(binding(layout), R.string.tunnl_handshake_authenticated)
            BindingAdapters.setDetailPeers(layout, peers, recorded, Tunnel.State.UP, peers, recorded, state)
            assertNull(binding(layout).diagnostics)
            assertFalse(binding(layout).diagnosticsAvailable)
            assertStatus(binding(layout), R.string.tunnl_handshake_unavailable)
            assertUnknownEndpoint(binding(layout))
        }
    }

    @Test fun selectionAndConfigReplacementRetainOnlyCurrentPeerFacts() = runTest {
        val fixture = fragmentFixture()
        val firstSession = session()
        val secondSession = session()
        fixture.first.onHandshakeSessionChange(firstSession)
        fixture.second.onHandshakeSessionChange(secondSession)
        fixture.first.onHandshakeObservation(observation(firstSession, firstKey, HandshakeObservation.Event.AUTHENTICATED))
        fixture.second.onHandshakeObservation(observation(secondSession, secondKey, HandshakeObservation.Event.SEND_FAILED))
        runCurrent()
        fixture.binding.executePendingBindings()
        assertStatus(fixture.peer(), R.string.tunnl_handshake_authenticated)
        val snapshot = fixture.first.handshakeDiagnostics
        fixture.first.onConfigChanged(profile(firstKey, "replacement.example.test:51821"))
        fixture.binding.executePendingBindings()
        assertSame(snapshot!![firstKey], fixture.peer().diagnostics)
        assertEquals("replacement.example.test:51821", fixture.peer().endpointText.text.toString())
        assertEquals("192.0.2.44:51820", fixture.peer().observedEndpointText.text.toString())
        fixture.fragment.onSelectedTunnelChanged(fixture.first, fixture.second)
        configJob(fixture.fragment).join()
        fixture.binding.executePendingBindings()
        assertStatus(fixture.peer(), R.string.tunnl_handshake_send_failed)
        fixture.first.onHandshakeObservation(observation(firstSession, firstKey, HandshakeObservation.Event.EXHAUSTED, sequence = 2))
        tunnelCallback(fixture.fragment).onPropertyChanged(fixture.first, BR.handshakeDiagnostics)
        runCurrent()
        fixture.binding.executePendingBindings()
        assertSame(fixture.second, fixture.binding.tunnel)
        assertEquals(secondKey, fixture.peer().item!!.publicKey)
        assertStatus(fixture.peer(), R.string.tunnl_handshake_send_failed)
        fixture.fragment.onSelectedTunnelChanged(fixture.second, null)
        fixture.binding.executePendingBindings()
        assertEquals(0, fixture.binding.peersLayout.childCount)
    }

    @Test fun sessionRetirementReplacementAndDownClearSelectedDiagnostics() = runTest {
        val fixture = fragmentFixture()
        val old = session()
        fixture.first.onHandshakeSessionChange(old)
        fixture.first.onHandshakeObservation(observation(old, firstKey, HandshakeObservation.Event.AUTHENTICATED))
        runCurrent()
        fixture.binding.executePendingBindings()
        assertStatus(fixture.peer(), R.string.tunnl_handshake_authenticated)
        retire(old)
        fixture.first.onHandshakeSessionChange(old)
        runCurrent()
        fixture.binding.executePendingBindings()
        assertStatus(fixture.peer(), R.string.tunnl_handshake_unavailable)
        assertUnknownEndpoint(fixture.peer())
        val current = session()
        fixture.first.onHandshakeSessionChange(current)
        fixture.first.onHandshakeObservation(observation(old, firstKey, HandshakeObservation.Event.AUTHENTICATED, sequence = 2))
        runCurrent()
        fixture.binding.executePendingBindings()
        assertStatus(fixture.peer(), R.string.tunnl_handshake_unobserved)
        assertUnknownEndpoint(fixture.peer())
        fixture.first.onHandshakeObservation(observation(current, firstKey, HandshakeObservation.Event.SENT))
        runCurrent()
        fixture.binding.executePendingBindings()
        assertStatus(fixture.peer(), R.string.tunnl_handshake_sent)
        fixture.first.setStateAsync(Tunnel.State.DOWN)
        runCurrent()
        fixture.binding.executePendingBindings()
        assertStatus(fixture.peer(), R.string.tunnl_handshake_unavailable)
        assertUnknownEndpoint(fixture.peer())
    }

    @Test fun recreatedViewBindsExistingSnapshotAndDestroyedViewRejectsCallbacks() = runTest {
        val fixture = fragmentFixture()
        val session = session()
        fixture.first.onHandshakeSessionChange(session)
        fixture.first.onHandshakeObservation(observation(session, firstKey, HandshakeObservation.Event.SENT))
        runCurrent()
        fixture.binding.executePendingBindings()
        val callback = tunnelCallback(fixture.fragment)
        val detachedPeer = fixture.peer()
        val detachedDiagnostics = detachedPeer.diagnostics
        val manager = fixture.controller.get().supportFragmentManager
        manager.beginTransaction().detach(fixture.fragment).commitNow()
        assertNull(fixture.fragment.view)
        fixture.first.onHandshakeObservation(observation(session, firstKey, HandshakeObservation.Event.AUTHENTICATED, sequence = 2))
        callback.onPropertyChanged(fixture.first, BR.handshakeDiagnostics)
        callback.onPropertyChanged(fixture.first, BR.state)
        runCurrent()
        fixture.binding.executePendingBindings()
        detachedPeer.executePendingBindings()
        assertSame(detachedDiagnostics, detachedPeer.diagnostics)
        assertStatus(detachedPeer, R.string.tunnl_handshake_sent)
        manager.beginTransaction().attach(fixture.fragment).commitNow()
        stopStatsRefresh(fixture.fragment)
        fixture.fragment.onSelectedTunnelChanged(null, fixture.first)
        configJob(fixture.fragment).join()
        val recreated = DataBindingUtil.getBinding<TunnelDetailFragmentBinding>(fixture.fragment.requireView())!!
        recreated.executePendingBindings()
        assertNotSame(fixture.binding.root, recreated.root)
        assertStatus(binding(recreated.peersLayout), R.string.tunnl_handshake_authenticated)
        assertSame(fixture.first.handshakeDiagnostics!![firstKey], binding(recreated.peersLayout).diagnostics)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun nativeDiagnosticsWrapAndRemainReachableAcrossThemesLanguagesAndFontScales() = runTest {
        environment = TunnelTestEnvironment(folder.root, backgroundScope)
        val cases = listOf(
            Preview("portrait-light", 320, 640, 1f, "en", false),
            Preview("portrait-dark", 320, 640, 1f, "en", true),
            Preview("landscape-dark", 640, 320, 1f, "en", true),
            Preview("large-landscape-dark", 640, 320, 2f, "en", true),
            Preview("large-rtl-light", 320, 640, 2f, "ar", false),
            Preview("large-rtl-dark", 320, 640, 2f, "ar", true),
            Preview("large-french-light", 320, 640, 2f, "fr", false),
            Preview("large-french-dark", 320, 640, 2f, "fr", true)
        )
        val endpoint = "[2001:db8:1234:5678:90ab:cdef:1234:5678]:51820"
        for (case in cases) {
            val direction = if (case.locale == "ar") "ldrtl" else "ldltr"
            val orientation = if (case.width > case.height) "land" else "port"
            val night = if (case.night) "night" else "notnight"
            RuntimeEnvironment.setQualifiers("${case.locale}-$direction-w${case.width}dp-h${case.height}dp-$orientation-$night")
            RuntimeEnvironment.setFontScale(case.fontScale)
            val activity = host().get()
            val scroll = NestedScrollView(activity)
            val layout = LinearLayout(activity).apply { this.orientation = LinearLayout.VERTICAL }
            scroll.addView(layout, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            activity.setContentView(scroll)
            val peers = listOf(peer(firstKey))
            val recorded = mapOf(firstKey to diagnostics(observation(session(), firstKey,
                HandshakeObservation.Event.SEND_FAILED, endpoint = endpoint, family = HandshakeObservation.AddressFamily.IPV6)))
            BindingAdapters.setDetailPeers(layout, null, null, null, peers, recorded, Tunnel.State.UP)
            val binding = binding(layout)
            binding.statistics = Statistics.PeerStats(2048L, 1024L, System.currentTimeMillis() - 5000L)
            binding.executePendingBindings()
            measure(scroll, case)
            val expectedDirection = if (case.locale == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            assertEquals(case.name, expectedDirection, binding.root.layoutDirection)
            assertEquals(View.TEXT_DIRECTION_LTR, binding.observedEndpointText.textDirection)
            assertEquals(endpoint, binding.observedEndpointText.text.toString())
            assertStatus(binding, R.string.tunnl_handshake_send_failed)
            assertTextFits(binding.root, case.name)
            for (text in listOf(binding.handshakeObservationText, binding.observedEndpointText, binding.observedAddressFamilyText)) {
                assertEquals(case.name, text.text.length, text.layout.getLineEnd(text.layout.lineCount - 1))
                for (line in 0 until text.layout.lineCount) assertEquals(case.name, 0, text.layout.getEllipsisCount(line))
            }
            if (case.width == 320) assertTrue(case.name, binding.observedEndpointText.lineCount > 1)
            save(scroll, case.name)
            scroll.fullScroll(View.FOCUS_DOWN)
            if (case.fontScale == 2f) assertTrue(case.name, scroll.canScrollVertically(-1))
            val location = IntArray(2)
            binding.observedAddressFamilyText.getLocationInWindow(location)
            val scrollLocation = IntArray(2)
            scroll.getLocationInWindow(scrollLocation)
            assertTrue(case.name, location[1] >= scrollLocation[1])
            assertTrue(case.name, location[1] + binding.observedAddressFamilyText.height <= scrollLocation[1] + scroll.height)
            save(scroll, "${case.name}-bottom")
        }
    }

    private fun TestScope.peerLayout(): LinearLayout {
        environment = TunnelTestEnvironment(folder.root, backgroundScope)
        return LinearLayout(host().get())
    }

    private fun host(): ActivityController<HostActivity> {
        val controller = Robolectric.buildActivity(HostActivity::class.java)
        controllers += controller
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
        return controller
    }

    private suspend fun TestScope.fragmentFixture(): FragmentFixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        environment = TunnelTestEnvironment(folder.root, backgroundScope)
        val first = environment.manager.create("one", profile(firstKey)).apply { setStateAsync(Tunnel.State.UP) }
        val second = environment.manager.create("two", profile(secondKey)).apply { setStateAsync(Tunnel.State.UP) }
        val controller = host()
        val fragment = TunnelDetailFragment()
        controller.get().supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        stopStatsRefresh(fragment)
        fragment.onSelectedTunnelChanged(null, first)
        configJob(fragment).join()
        val binding = DataBindingUtil.getBinding<TunnelDetailFragmentBinding>(fragment.requireView())!!
        binding.executePendingBindings()
        return FragmentFixture(controller, fragment, binding, first, second)
    }

    private fun binding(layout: LinearLayout, index: Int = 0): TunnelDetailPeerBinding =
        DataBindingUtil.getBinding<TunnelDetailPeerBinding>(layout.getChildAt(index))!!.apply { executePendingBindings() }

    private fun assertStatus(binding: TunnelDetailPeerBinding, resource: Int) {
        assertEquals(binding.root.context.getString(resource), binding.handshakeObservationText.text.toString())
    }

    private fun assertUnknownEndpoint(binding: TunnelDetailPeerBinding) {
        assertEquals("—", binding.observedEndpointText.text.toString())
        assertEquals("—", binding.observedAddressFamilyText.text.toString())
    }

    private fun measure(view: View, case: Preview) {
        val density = view.resources.displayMetrics.density
        view.measure(View.MeasureSpec.makeMeasureSpec((case.width * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((case.height * density).toInt(), View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun assertTextFits(view: View, name: String) {
        if (view.visibility != View.VISIBLE) return
        if (view is TextView && view.text.isNotEmpty()) {
            assertTrue("$name: ${view.text}", view.layout.getLineTop(view.layout.lineCount) <= view.height)
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) assertTextFits(view.getChildAt(index), name)
    }

    private fun save(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
        view.draw(canvas)
        val directory = File("../.artifacts/peer-handshake-previews").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun peer(key: Key): Peer = Peer.Builder().setPublicKey(key)
        .setEndpoint(InetEndpoint.parse("configured.example.test:51820")).build()

    private fun profile(key: Key, endpoint: String = "configured.example.test:51820"): TunnelConfig = TunnelConfig.parse("""
        [Interface]
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        Address = 192.0.2.2/32
        [Peer]
        PublicKey = ${key.toBase64()}
        Endpoint = $endpoint
        AllowedIPs = 0.0.0.0/0
    """.trimIndent().byteInputStream())

    private fun diagnostics(observation: HandshakeObservation) = ObservableTunnel.PeerHandshakeDiagnostics(observation)

    private fun observation(
        session: HandshakeSession,
        key: Key,
        event: HandshakeObservation.Event,
        retry: Boolean = false,
        endpoint: String? = "192.0.2.44:51820",
        family: HandshakeObservation.AddressFamily = HandshakeObservation.AddressFamily.IPV4,
        sequence: Long = 1
    ) = HandshakeObservation(session, key, event, retry, endpoint?.let(InetEndpoint::parse), family, sequence, sequence)

    private fun session(): HandshakeSession = HandshakeSession::class.java.getDeclaredConstructor()
        .apply { isAccessible = true }.newInstance().also {
            HandshakeSession::class.java.getDeclaredMethod("activate").apply { isAccessible = true }.invoke(it)
        }

    private fun retire(session: HandshakeSession) {
        HandshakeSession::class.java.getDeclaredMethod("retire").apply { isAccessible = true }.invoke(session)
    }

    private fun configJob(fragment: TunnelDetailFragment): Job =
        TunnelDetailFragment::class.java.getDeclaredField("configJob").apply { isAccessible = true }.get(fragment) as Job

    private fun stopStatsRefresh(fragment: TunnelDetailFragment) {
        TunnelDetailFragment::class.java.getDeclaredMethod("stopStatsRefresh").apply { isAccessible = true }.invoke(fragment)
    }

    private fun tunnelCallback(fragment: TunnelDetailFragment): Observable.OnPropertyChangedCallback =
        TunnelDetailFragment::class.java.getDeclaredField("tunnelCallback").apply { isAccessible = true }
            .get(fragment) as Observable.OnPropertyChangedCallback

    private data class FragmentFixture(
        val controller: ActivityController<HostActivity>,
        val fragment: TunnelDetailFragment,
        val binding: TunnelDetailFragmentBinding,
        val first: ObservableTunnel,
        val second: ObservableTunnel
    ) {
        fun peer(): TunnelDetailPeerBinding = DataBindingUtil.getBinding<TunnelDetailPeerBinding>(binding.peersLayout.getChildAt(0))!!
            .apply { executePendingBindings() }
    }

    private data class Preview(val name: String, val width: Int, val height: Int, val fontScale: Float, val locale: String, val night: Boolean)

    class HostActivity : AppCompatActivity()
}
