/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.databinding

import android.app.Application
import android.content.res.Configuration
import android.os.Looper
import android.text.InputType
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.vpn0wn.android.R
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.android.viewmodel.ConfigProxy
import com.vpn0wn.config.Config as TunnelConfig
import java.util.Locale
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [24, 28])
class AmneziaKeepaliveBindingTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun actualEditorRetainsRangeAndAcceptsRangeEditsWithoutChangingWireGuardKeyboard() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.AppTheme) }
        controller.setup()
        try {
            val proxy = ConfigProxy(profile("Protocol = AmneziaWG", "20-30"))
            val binding = TunnelEditorPeerBinding.inflate(activity.layoutInflater)
            binding.collection = proxy.peers
            binding.item = proxy.peers.single()
            binding.executePendingBindings()
            val input = binding.persistentKeepaliveText
            assertEquals("20-30", input.text.toString())
            assertEquals(InputType.TYPE_CLASS_TEXT, input.inputType and InputType.TYPE_MASK_CLASS)
            input.setText("30-40")
            binding.executePendingBindings()
            assertEquals("30-40", proxy.resolve().peers.single().amneziaKeepalive.get())

            val ordinary = ConfigProxy(profile("", "25"))
            binding.collection = ordinary.peers
            binding.item = ordinary.peers.single()
            binding.executePendingBindings()
            assertEquals("25", input.text.toString())
            assertEquals(InputType.TYPE_CLASS_NUMBER, input.inputType and InputType.TYPE_MASK_CLASS)
            assertEquals(25, ordinary.resolve().peers.single().persistentKeepalive.get().toInt())
        } finally {
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun actualDetailShowsCompleteLocalizedRangeAndCopyAccessibilityText() = runTest {
        val environment = TunnelTestEnvironment(folder.root, backgroundScope)
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.AppTheme) }
        controller.setup()
        try {
            val peer = profile("Protocol = AmneziaWG", "20-30").peers.single()
            val binding = TunnelDetailPeerBinding.inflate(activity.layoutInflater)
            binding.item = peer
            binding.executePendingBindings()
            assertEquals(View.VISIBLE, binding.persistentKeepaliveRow.visibility)
            assertEquals("every 20-30 seconds", binding.persistentKeepaliveText.text.toString())
            assertTrue(binding.persistentKeepaliveRow.contentDescription.toString().contains("20-30"))
            val french = Configuration(activity.resources.configuration).apply { setLocale(Locale.FRENCH) }
            assertEquals("toutes les 20-30 secondes", BindingAdapters.persistentKeepaliveText(
                activity.createConfigurationContext(french), peer))
            for (zero in listOf("0", "0-0", "000")) {
                val disabled = profile("Protocol = AmneziaWG", zero).peers.single()
                binding.item = disabled
                binding.executePendingBindings()
                assertEquals(View.VISIBLE, binding.persistentKeepaliveRow.visibility)
                assertEquals("disabled", binding.persistentKeepaliveText.text.toString())
                assertTrue(binding.persistentKeepaliveRow.contentDescription.toString().contains("disabled"))
                assertEquals("désactivé", BindingAdapters.persistentKeepaliveText(
                    activity.createConfigurationContext(french), disabled))
            }
        } finally {
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
            environment.close()
        }
    }

    private fun profile(protocol: String, keepalive: String): TunnelConfig = TunnelConfig.parse("""
        [Interface]
        $protocol
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        [Peer]
        PublicKey = AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        PersistentKeepalive = $keepalive
    """.trimIndent().reader().buffered())
}
