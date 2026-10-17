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
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.databinding.DataBindingUtil
import com.vpn0wn.android.R
import com.vpn0wn.android.backend.Statistics
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.databinding.LogViewerActivityBinding
import com.vpn0wn.android.databinding.TunnelDetailFragmentBinding
import com.vpn0wn.android.databinding.TunnelDetailPeerBinding
import com.vpn0wn.android.databinding.TunnelEditorFragmentBinding
import com.vpn0wn.android.databinding.TunnelEditorPeerBinding
import com.vpn0wn.android.model.ObservableTunnel
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.android.viewmodel.ConfigProxy
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
class LocalizedCopyLayoutTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun editorLabelsAndNativeFieldsRemainReachable() = previews { activity, case ->
        val binding = TunnelEditorFragmentBinding.inflate(activity.layoutInflater)
        val config = ConfigProxy().apply { addPeer() }
        binding.config = config
        binding.isNew = true
        activity.setContentView(binding.root)
        binding.executePendingBindings()
        try {
            layout(binding.root, case)
            assertConfiguration(binding.root, case)
            capture(binding.root, case, "editor-top")
            for (label in listOf(R.string.vpn0wn_local_side, R.string.name, R.string.private_key, R.string.public_key,
                R.string.addresses, R.string.dns_servers, R.string.listen_port, R.string.applications,
                R.string.pre_shared_key, R.string.endpoint, R.string.allowed_ips, R.string.persistent_keepalive)) {
                labels(binding.root, label, case)
            }
            for (button in listOf(binding.editorSaveButton, binding.privateKeyRegenButton,
                binding.setExcludedApplications, binding.addPeerButton)) {
                reveal(button, binding.root, case)
                assertTarget(button)
                assertTextFits(button)
            }
            val peer = requireNotNull(DataBindingUtil.getBinding<TunnelEditorPeerBinding>(binding.peersLayout.getChildAt(0)))
            assertEquals(activity.getString(R.string.vpn0wn_paste_base64_key), peer.publicKeyText.hint.toString())
            assertEquals(activity.getString(R.string.vpn0wn_endpoint_hint), peer.endpointText.hint.toString())
            assertEquals(activity.getString(R.string.vpn0wn_remove_peer), peer.delete.text.toString())
            reveal(peer.delete, binding.root, case)
            assertTarget(peer.delete)
            assertTextFits(peer.delete)
            for (field in listOf(binding.interfaceNameText, binding.dnsServersText, peer.endpointText,
                peer.allowedIpsText, peer.persistentKeepaliveText)) {
                reveal(field, binding.root, case)
                assertTrue("Native field accepts focus", field.requestFocus())
                assertMono(field, R.font.jetbrains_mono_regular)
                val node = field.createAccessibilityNodeInfo()
                try { assertTrue("Native field remains editable", node.isEditable) } finally { node.recycle() }
                field.clearFocus()
            }
            binding.interfaceNameText.setText("synthetic")
            peer.endpointText.setText("198.51.100.42:51820")
            binding.executePendingBindings()
            assertEquals("synthetic", binding.name)
            assertEquals("198.51.100.42:51820", config.peers.single().endpoint)
            reveal(binding.setExcludedApplications, binding.root, case)
            capture(binding.root, case, "editor-applications")
            reveal(peer.persistentKeepaliveText, binding.root, case)
            capture(binding.root, case, "editor-peer")
        } finally { binding.unbind() }
    }

    @Test fun detailLabelsAndCompleteApplicationPhrasesFit() = runTest {
        val environment = TunnelTestEnvironment(folder.root, backgroundScope)
        try {
            previews { activity, case ->
                val profile = profile()
                val binding = TunnelDetailFragmentBinding.inflate(activity.layoutInflater)
                val tunnel = ObservableTunnel(environment.manager, "synthetic", profile, Tunnel.State.UP)
                tunnel.onStatisticsChanged(statistics(profile.peers.single().publicKey))
                binding.config = profile
                binding.tunnel = tunnel
                activity.setContentView(binding.root)
                binding.executePendingBindings()
                try {
                    layout(binding.root, case)
                    assertConfiguration(binding.root, case)
                    capture(binding.root, case, "detail-top")
                    for (label in listOf(R.string.vpn0wn_tunnel_topology, R.string.vpn0wn_download, R.string.vpn0wn_upload,
                        R.string.name, R.string.public_key, R.string.addresses,
                        R.string.dns_servers, R.string.dns_search_domains, R.string.listen_port, R.string.allowed_ips,
                        R.string.persistent_keepalive)) {
                        labels(binding.root, label, case)
                    }
                    val phrase = activity.resources.getQuantityString(R.plurals.set_excluded_applications, 1, 1)
                    val applicationLabel = descendants(binding.applicationsRow).filterIsInstance<TextView>()
                        .single { it.text.toString() == phrase }
                    reveal(applicationLabel, binding.root, case)
                    assertTextFits(applicationLabel)
                    assertMono(applicationLabel, R.font.jetbrains_mono_regular)
                    reveal(binding.dnsSearchDomainsRow, binding.root, case)
                    capture(binding.root, case, "detail-dns")
                    val peer = requireNotNull(DataBindingUtil.getBinding<TunnelDetailPeerBinding>(binding.peersLayout.getChildAt(0)))
                    assertEquals(activity.getString(R.string.vpn0wn_peer_title, "01"), peer.peerTitle.text.toString())
                    for (row in listOf(binding.applicationsRow, peer.allowedIpsRow, peer.persistentKeepaliveRow)) {
                        reveal(row, binding.root, case)
                        assertTarget(row)
                    }
                    capture(binding.root, case, "detail-peer")
                } finally { binding.unbind() }
            }
        } finally { environment.close() }
    }

    @Test fun logsKeepTranslatedFilterAndTechnicalLevelCodes() = previews { activity, case ->
        val binding = LogViewerActivityBinding.inflate(activity.layoutInflater)
        activity.setContentView(binding.root)
        layout(binding.root, case)
        assertConfiguration(binding.root, case)
        labels(binding.root, R.string.vpn0wn_log_stream_subtitle, case)
        val title = descendants(binding.root).filterIsInstance<TextView>()
            .single { it.text.toString() == activity.getString(R.string.log_viewer_title) }
        assertFullyVisible(title)
        assertTextFits(title)
        assertMono(title, R.font.jetbrains_mono_medium)
        assertTextFits(binding.liveToggle)
        assertEquals(activity.getString(R.string.vpn0wn_filter_all), binding.filterAll.text.toString())
        reveal(binding.filterAll, binding.root, case)
        assertTextFits(binding.filterAll)
        assertEquals("DEBUG", binding.filterDebug.text.toString())
        assertEquals("INFO", binding.filterInfo.text.toString())
        assertEquals("WARN", binding.filterWarn.text.toString())
        assertEquals("ERROR", binding.filterError.text.toString())
        for (button in listOf(binding.backButton, binding.refreshButton, binding.shareButton, binding.liveToggle)) {
            assertTarget(button)
            assertFullyVisible(button)
        }
        capture(binding.root, case, "logs")
    }

    private fun previews(check: (AppCompatActivity, Preview) -> Unit) {
        val originalQualifiers = RuntimeEnvironment.getQualifiers()
        val originalFontScale = RuntimeEnvironment.getApplication().resources.configuration.fontScale
        val originalDirection = RuntimeEnvironment.getApplication().resources.configuration.layoutDirection
        val originalLocale = Locale.getDefault()
        val cases = listOf(
            Preview("en-compact", locale = "en", width = 320, height = 640, scale = 1f),
            Preview("fr-compact", locale = "fr", width = 320, height = 640, scale = 1.3f),
            Preview("fr-short", locale = "fr", width = 640, height = 320, scale = 1.3f),
            Preview("fr-rtl-compact", locale = "fr", width = 320, height = 640, scale = 1.3f, rtl = true),
            Preview("fr-rtl-short", locale = "fr", width = 640, height = 320, scale = 1.3f, rtl = true),
        )
        try {
            for (preview in cases) for (night in listOf(false, true)) {
                val case = preview.copy(night = night)
                val orientation = if (case.width > case.height) "land" else "port"
                RuntimeEnvironment.setQualifiers("${case.locale}-w${case.width}dp-h${case.height}dp-$orientation-${if (night) "night" else "notnight"}-mdpi")
                RuntimeEnvironment.setFontScale(case.scale)
                // Robolectric reapplies locale direction when font configuration changes.
                RuntimeEnvironment.setQualifiers(if (case.rtl) "+ldrtl" else "+ldltr")
                val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
                controller.get().setTheme(R.style.AppTheme)
                controller.setup()
                controller.get().supportActionBar?.hide()
                try { check(controller.get(), case) } finally { controller.pause().stop().destroy() }
            }
        } finally {
            RuntimeEnvironment.setQualifiers(originalQualifiers)
            RuntimeEnvironment.setFontScale(originalFontScale)
            RuntimeEnvironment.setQualifiers(if (originalDirection == View.LAYOUT_DIRECTION_RTL) "+ldrtl" else "+ldltr")
            Locale.setDefault(originalLocale)
        }
    }

    private fun assertConfiguration(root: View, case: Preview) {
        val config = root.resources.configuration
        assertEquals(case.locale, config.locales[0].language)
        assertEquals(case.scale, config.fontScale, 0f)
        val direction = if (case.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        assertEquals("Effective resource direction", direction, config.layoutDirection)
        assertEquals("Inherited native direction", direction, root.layoutDirection)
        assertEquals(if (case.night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
            config.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertEquals("Requested window width", case.width, root.rootView.width)
        assertEquals("Requested window height", case.height, root.rootView.height)
        assertEquals(case.width, root.width)
        assertEquals(case.height, root.height)
    }

    private fun labels(root: View, resource: Int, case: Preview) {
        val value = root.context.getString(resource)
        val labels = descendants(root).filterIsInstance<TextView>().filter { it.text.toString() == value && it.isShown }.toList()
        assertTrue("Rendered label exists: $value", labels.isNotEmpty())
        for (label in labels) {
            reveal(label, root, case)
            assertTextFits(label)
            assertMono(label, R.font.jetbrains_mono_regular)
        }
    }

    private fun reveal(view: View, root: View, case: Preview) {
        view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
        layout(root, case)
        assertFullyVisible(view)
    }

    private fun assertFullyVisible(view: View) {
        val visible = Rect()
        val reachable = view.getGlobalVisibleRect(visible)
        val position = IntArray(2).also(view::getLocationInWindow)
        val bounds = Rect(position[0], position[1], position[0] + view.width, position[1] + view.height)
        val scroll = generateSequence(view.parent as? View) { it.parent as? View }.filterIsInstance<ScrollView>()
            .joinToString { "${it.width}x${it.height}@scrollY=${it.scrollY}" }
        val diagnostic = "${view.javaClass.simpleName} id=${view.id} text=${(view as? TextView)?.text}; " +
            "bounds=$bounds visible=$visible decor=${view.rootView.width}x${view.rootView.height} scroll=$scroll"
        assertTrue("Native content is reachable; $diagnostic", reachable)
        assertEquals("Complete visible width; $diagnostic", view.width, visible.width())
        assertEquals("Complete visible height; $diagnostic", view.height, visible.height())
    }

    private fun assertTarget(view: View) {
        val minimum = MINIMUM_TARGET_DP * view.resources.displayMetrics.density
        assertTrue("Native target remains operable", view.isEnabled && view.isClickable && view.isFocusable)
        assertTrue("Android target width", view.width >= minimum)
        assertTrue("Android target height", view.height >= minimum)
    }

    private fun assertMono(view: TextView, font: Int) {
        assertEquals(ResourcesCompat.getFont(view.context, font), view.typeface)
        assertEquals("ss01, zero, tnum", view.fontFeatureSettings)
    }

    private fun assertTextFits(view: TextView) {
        val text = requireNotNull(view.layout)
        val width = view.width - view.compoundPaddingLeft - view.compoundPaddingRight
        val height = view.height - view.compoundPaddingTop - view.compoundPaddingBottom
        assertTrue("Complete label: ${view.text}", text.lineCount > 0 && text.getLineEnd(text.lineCount - 1) == text.text.length)
        assertTrue("Complete label height: ${view.text}", text.height <= height)
        for (line in 0 until text.lineCount) {
            assertEquals("No label truncation: ${view.text}", 0, text.getEllipsisCount(line))
            assertTrue("Label stays within content width: ${view.text}",
                text.getLineLeft(line) - view.scrollX >= -INK_TOLERANCE_PX && text.getLineRight(line) - view.scrollX <= width + INK_TOLERANCE_PX)
        }
        val bitmap = Bitmap.createBitmap(text.width + INK_MARGIN_PX * 2, text.height + INK_MARGIN_PX * 2, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.translate(INK_MARGIN_PX.toFloat() - view.scrollX, INK_MARGIN_PX.toFloat() - view.scrollY)
            text.draw(canvas)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("Native label paints glyphs: ${view.text}", pixels.any { it ushr ALPHA_SHIFT != 0 })
            assertTrue("Painted glyphs stay within the field: ${view.text}", pixels.indices.all {
                if (pixels[it] ushr ALPHA_SHIFT == 0) true else {
                    val x = it % bitmap.width - INK_MARGIN_PX
                    val y = it / bitmap.width - INK_MARGIN_PX
                    x >= -INK_TOLERANCE_PX && x < width + INK_TOLERANCE_PX && y >= -INK_TOLERANCE_PX && y < height + INK_TOLERANCE_PX
                }
            })
        } finally { bitmap.recycle() }
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

    private fun capture(root: View, case: Preview, name: String) {
        layout(root, case)
        assertConfiguration(root, case)
        val project = File(requireNotNull(System.getProperty("user.dir"))).let { if (it.name == "ui") it.parentFile else it }
        val output = System.getenv("VPN0WN_LOCALIZED_COPY_OUTPUT")?.let(::File)
            ?: File(project, ".artifacts/localized-copy-previews")
        assertTrue("Preview output directory is available", output.isDirectory || output.mkdirs())
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            File(output, "${case.name}-${if (case.night) "night" else "day"}-$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }

    private fun profile(): TunnelConfig = TunnelConfig.Builder()
        .setInterface(Interface.Builder().setKeyPair(KeyPair(Key.fromBytes(ByteArray(KEY_LENGTH))))
            .parseAddresses("192.0.2.2/32").parseDnsServers("1.1.1.1").addDnsSearchDomain("corp.example")
            .setListenPort(SYNTHETIC_PORT).excludeApplication("com.example.synthetic").build())
        .addPeer(Peer.Builder().setPublicKey(Key.fromBytes(ByteArray(KEY_LENGTH) { 1 }))
            .parseEndpoint("198.51.100.42:51820").parseAllowedIPs("0.0.0.0/0, ::/0")
            .setPersistentKeepalive(SYNTHETIC_KEEPALIVE_SECONDS).build()).build()

    private fun statistics(key: Key): Statistics {
        val snapshot = Statistics::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        Statistics::class.java.getDeclaredMethod("add", Key::class.java, Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(snapshot, key, 0L, 0L, System.currentTimeMillis())
        return snapshot
    }

    private data class Preview(val name: String, val locale: String, val width: Int, val height: Int, val scale: Float,
        val rtl: Boolean = false, val night: Boolean = false)

    private companion object {
        const val MINIMUM_TARGET_DP = 48
        const val LAYOUT_PASSES = 3
        const val INK_MARGIN_PX = 20
        const val INK_TOLERANCE_PX = 1
        const val ALPHA_SHIFT = 24
        const val KEY_LENGTH = 32
        const val SYNTHETIC_PORT = 51820
        const val SYNTHETIC_KEEPALIVE_SECONDS = 25
    }
}
