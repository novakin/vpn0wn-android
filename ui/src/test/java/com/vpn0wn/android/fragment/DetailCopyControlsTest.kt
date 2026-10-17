/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.fragment

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.vpn0wn.android.R
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.databinding.BindingAdapters
import com.vpn0wn.android.databinding.TunnelDetailFragmentBinding
import com.vpn0wn.android.databinding.TunnelDetailPeerBinding
import com.vpn0wn.android.model.ObservableTunnel
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.config.Attribute
import com.vpn0wn.config.Config as TunnelConfig
import com.vpn0wn.config.Interface
import com.vpn0wn.config.Peer
import com.vpn0wn.crypto.Key
import com.vpn0wn.crypto.KeyPair
import java.io.File
import java.util.Locale
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
class DetailCopyControlsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun completeCurrentValuesCopyFromReachableRowsAcrossNativeConfigurations() = runTest {
        val originalQualifiers = RuntimeEnvironment.getQualifiers()
        val originalFontScale = RuntimeEnvironment.getApplication().resources.configuration.fontScale
        val originalLocale = Locale.getDefault()
        val environment = TunnelTestEnvironment(folder.root, backgroundScope)
        try {
            for ((width, height, scale) in listOf(Triple(320, 640, 1f), Triple(320, 640, 2f), Triple(640, 320, 2f))) {
                for (night in listOf(false, true)) {
                    val rtl = scale > 1f
                    val direction = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    val locale = if (rtl) "ar" else "en"
                    RuntimeEnvironment.setQualifiers("$locale-${if (rtl) "ldrtl" else "ldltr"}-w${width}dp-h${height}dp-${if (width > height) "land" else "port"}-${if (night) "night" else "notnight"}-mdpi")
                    RuntimeEnvironment.setFontScale(scale)
                    // Robolectric reapplies locale direction after combined qualifiers.
                    RuntimeEnvironment.setQualifiers(if (rtl) "+ldrtl" else "+ldltr")
                    val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
                    val activity = controller.get().apply { setTheme(R.style.AppTheme) }
                    controller.setup()
                    try {
                        activity.window.decorView.layoutDirection = direction
                        val binding = TunnelDetailFragmentBinding.inflate(activity.layoutInflater)
                        activity.setContentView(binding.root)
                        for (seed in 1..2) {
                            val profile = profile(seed)
                            val name = "home-lab-$seed"
                            binding.config = profile
                            binding.tunnel = ObservableTunnel(environment.manager, name, profile, Tunnel.State.DOWN)
                            binding.executePendingBindings()
                            assertTrue("Addresses copy is ready after one populated bind", binding.addressesRow.isClickable)
                            shadowOf(Looper.getMainLooper()).idle()
                            measure(binding.root, width, height)
                            assertEquals(direction, binding.root.layoutDirection)
                            assertEquals(direction, binding.root.resources.configuration.layoutDirection)
                            assertEquals(locale, binding.root.resources.configuration.locales[0].language)
                            assertEquals(scale, binding.root.resources.configuration.fontScale)
                            assertEquals(if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
                                binding.root.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                            val scroll = descendants(binding.root).filterIsInstance<ScrollView>().single()
                            val rows = listOf(
                                Triple(binding.interfaceNameRow, binding.interfaceNameText, name),
                                Triple(binding.addressesRow, binding.addressesText, Attribute.join(profile.getInterface().addresses)),
                                Triple(binding.dnsServersRow, binding.dnsServersText, Attribute.join(profile.getInterface().dnsServers.map { it.hostAddress })),
                                Triple(binding.dnsSearchDomainsRow, binding.dnsSearchDomainsText, "corp.example"),
                                Triple(binding.listenPortRow, binding.listenPortText, (51820 + seed).toString()),
                                Triple(binding.mtuRow, binding.mtuText, (1420 + seed).toString()),
                                Triple(binding.applicationsRow, binding.applicationsText, activity.resources.getQuantityString(R.plurals.n_excluded_applications, 1, 1)),
                            )
                            val labels = listOf(R.string.tunnel_name, R.string.addresses, R.string.dns_servers,
                                R.string.dns_search_domains, R.string.listen_port, R.string.mtu, R.string.applications)
                            rows.forEachIndexed { index, (row, value, expected) ->
                                assertCopy(row, value, expected, activity.getString(labels[index]), scroll)
                            }
                            val peer = profile.peers.single()
                            val peerBinding = requireNotNull(DataBindingUtil.getBinding<TunnelDetailPeerBinding>(binding.peersLayout.getChildAt(0)))
                            val peerRows = listOf(
                                Triple(peerBinding.endpointRow, peerBinding.endpointText, peer.endpoint.get().toString()),
                                Triple(peerBinding.allowedIpsRow, peerBinding.allowedIpsText, Attribute.join(peer.allowedIps)),
                                Triple(peerBinding.persistentKeepaliveRow, peerBinding.persistentKeepaliveText,
                                    activity.resources.getQuantityString(R.plurals.persistent_keepalive_seconds_unit, 25 + seed, 25 + seed)),
                            )
                            val peerLabels = listOf(R.string.endpoint, R.string.allowed_ips, R.string.persistent_keepalive)
                            peerRows.forEachIndexed { index, (row, value, expected) ->
                                assertCopy(row, value, expected, activity.getString(peerLabels[index]), scroll)
                            }
                            if (seed == 2) {
                                val capture = "$width-${if (rtl) "rtl" else "ltr"}-$scale-${if (night) "night" else "day"}"
                                save(peerBinding.endpointRow, "$capture-endpoint")
                                scroll.scrollTo(0, 0)
                                save(binding.root, "$capture-detail")
                            }
                            peerBinding.item = null
                            peerBinding.executePendingBindings()
                            shadowOf(Looper.getMainLooper()).idle()
                            assertNull("Cleared peer remains unavailable after native scheduling", peerBinding.item)
                            for ((index, row) in listOf(peerBinding.endpointRow, peerBinding.allowedIpsRow, peerBinding.persistentKeepaliveRow).withIndex()) {
                                val label = activity.getString(peerLabels[index])
                                assertEquals("$label hides with its unavailable peer", View.GONE, row.visibility)
                                assertFalse("$label retires its unavailable copy action", row.isClickable || row.isFocusable || row.hasOnClickListeners())
                                val node = row.createAccessibilityNodeInfo()
                                try {
                                    assertEquals("Unavailable $label clears its former value", "$label: ", node.contentDescription.toString())
                                    assertFalse("Unavailable action is not clickable", node.isClickable)
                                } finally { node.recycle() }
                            }
                        }
                        binding.unbind()
                    } finally { controller.pause().stop().destroy() }
                }
            }
        } finally {
            try { environment.close() } finally {
                RuntimeEnvironment.setQualifiers(originalQualifiers)
                RuntimeEnvironment.setFontScale(originalFontScale)
                Locale.setDefault(originalLocale)
            }
        }
    }

    private fun assertCopy(row: View, value: TextView, expected: String, label: String, scroll: ScrollView) {
        row.requestRectangleOnScreen(Rect(0, 0, row.width, row.height), true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(expected, value.text.toString())
        assertTrue("$label is the single copy owner for $expected", row.isClickable && row.isFocusable && row.hasOnClickListeners())
        assertFalse("Value has no competing action", value.isClickable || value.hasOnClickListeners())
        assertTrue("Native row feedback", requireNotNull(row.foreground).isStateful)
        assertTrue("Existing row supplies 48dp target", row.height >= 48 * row.resources.displayMetrics.density)
        val visible = Rect()
        assertTrue("$label has visible bounds", row.getGlobalVisibleRect(visible))
        val geometry = "$label: row=${row.width}x${row.height}, visible=$visible, " +
            "scroll=${scroll.width}x${scroll.height}@${scroll.scrollY}, root=${row.rootView.width}x${row.rootView.height}"
        assertEquals("Complete reachable height; $geometry", row.height, visible.height())
        assertEquals("Complete reachable width; $geometry", row.width, visible.width())
        assertTrue("Row remains in native scroll content", descendants(scroll).any { it === row })
        val node = value.createAccessibilityNodeInfo()
        try { assertEquals("Complete accessible value", expected, node.text.toString()) } finally { node.recycle() }
        val expectedDescription = "$label: $expected"
        val actionNode = row.createAccessibilityNodeInfo()
        try {
            assertEquals("Action node carries complete current value", expectedDescription, actionNode.contentDescription.toString())
            assertTrue("Action node is clickable", actionNode.isClickable)
        } finally { actionNode.recycle() }
        val service = row.context.getSystemService(ClipboardManager::class.java)
        service.setPrimaryClip(ClipData.newPlainText("Sentinel", "unchanged"))
        assertTrue(row.performClick())
        assertEquals(expected, service.primaryClip!!.getItemAt(0).text.toString())
        assertEquals("Clipboard label carries the same complete context", expectedDescription, service.primaryClip!!.description.label.toString())
        row.isEnabled = false
        BindingAdapters.setCopyText(row, expected)
        assertFalse("Disabled copy settles at existing adapter", row.isClickable || row.isFocusable || row.performClick())
        row.isEnabled = true
        BindingAdapters.setCopyText(row, expected)
    }

    private fun profile(seed: Int): TunnelConfig = TunnelConfig.Builder()
        .setInterface(Interface.Builder().setKeyPair(KeyPair(Key.fromBytes(ByteArray(32) { seed.toByte() })))
            .parseAddresses("10.8.$seed.2/32, 2001:db8::$seed/128").parseDnsServers("1.1.1.1, 9.9.9.9")
            .addDnsSearchDomain("corp.example").setListenPort(51820 + seed).setMtu(1420 + seed)
            .excludeApplication("com.example.synthetic").build())
        .addPeer(Peer.Builder().setPublicKey(Key.fromBytes(ByteArray(32) { (seed + 10).toByte() }))
            .parseEndpoint("[2001:db8::${seed + 10}]:${51820 + seed}").parseAllowedIPs("0.0.0.0/0, ::/0, 10.80.0.0/16")
            .setPersistentKeepalive(25 + seed).build()).build()

    private fun measure(view: View, width: Int, height: Int) {
        val viewport = view.rootView
        var readyToDraw = false
        repeat(3) {
            viewport.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            viewport.layout(0, 0, viewport.measuredWidth, viewport.measuredHeight)
            readyToDraw = !viewport.viewTreeObserver.dispatchOnPreDraw()
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue("Native decor traversal accepted", readyToDraw)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun save(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val directory = File("../.artifacts/detail-copy-previews").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
