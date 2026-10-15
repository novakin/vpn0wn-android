package com.vpn0wn.android.fragment

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.vpn0wn.android.R
import com.vpn0wn.android.databinding.BindingAdapters
import com.vpn0wn.android.databinding.TunnelDetailFragmentBinding
import com.vpn0wn.android.databinding.TunnelDetailPeerBinding
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.config.Config as TunnelConfig
import com.vpn0wn.config.Interface
import com.vpn0wn.config.Peer
import com.vpn0wn.crypto.Key
import com.vpn0wn.crypto.KeyPair
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 33])
class DetailPublicKeyCopyTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun interfaceCopyUsesCurrentFullPublicKeyAfterConfigAndViewReplacement() = runTest {
        val environment = TunnelTestEnvironment(folder.root, backgroundScope)
        var controller: ActivityController<AppCompatActivity>? = null
        try {
            val hostController = Robolectric.buildActivity(AppCompatActivity::class.java)
            controller = hostController
            val activity = hostController.get().apply { setTheme(R.style.AppTheme) }
            hostController.setup()
            var binding = TunnelDetailFragmentBinding.inflate(activity.layoutInflater)
            activity.setContentView(binding.root)
            val typeface = binding.publicKeyText.typeface
            for (seed in 1..3) {
                if (seed == 3) {
                    binding.unbind()
                    binding = TunnelDetailFragmentBinding.inflate(activity.layoutInflater)
                    activity.setContentView(binding.root)
                }
                val profile = profile(seed)
                binding.config = profile
                binding.executePendingBindings()
                val pair = profile.getInterface().keyPair
                assertCopiedKey(binding.publicKeyText, pair.publicKey.toBase64())
                assertNotEquals(pair.privateKey.toBase64(), clipboard(activity).primaryClip!!.getItemAt(0).text.toString())
                assertEquals(typeface, binding.publicKeyText.typeface)
                val peerBinding = DataBindingUtil.getBinding<TunnelDetailPeerBinding>(binding.peersLayout.getChildAt(0))!!
                assertCopiedKey(peerBinding.publicKeyText, profile.peers.single().publicKey.toBase64())
            }
            binding.config = null
            binding.executePendingBindings()
            assertEmptyKeyDoesNotCopy(binding.publicKeyText)
            assertEquals(0, binding.peersLayout.childCount)
        } finally {
            try {
                controller?.pause()?.stop()?.destroy()
                shadowOf(Looper.getMainLooper()).idle()
            } finally {
                environment.close()
            }
        }
    }

    @Test fun peerCopyRebindsFullKeyAndRetainsPlatformFeedback() = runTest {
        val environment = TunnelTestEnvironment(folder.root, backgroundScope)
        var controller: ActivityController<AppCompatActivity>? = null
        try {
            val hostController = Robolectric.buildActivity(AppCompatActivity::class.java)
            controller = hostController
            val activity = hostController.get().apply { setTheme(R.style.AppTheme) }
            hostController.setup()
            val binding = TunnelDetailPeerBinding.inflate(activity.layoutInflater)
            activity.setContentView(binding.root)
            val typeface = binding.publicKeyText.typeface
            binding.publicKeyText.contentDescription = "Synthetic public key"
            for (seed in 4..5) {
                val peer = Peer.Builder().setPublicKey(key(seed)).build()
                binding.item = peer
                binding.executePendingBindings()
                assertCopiedKey(binding.publicKeyText, peer.publicKey.toBase64())
                assertEquals(typeface, binding.publicKeyText.typeface)
            }
            shadowOf(Looper.getMainLooper()).idle()
            val feedback = activity.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                assertEquals(activity.getString(R.string.copied_to_clipboard, binding.publicKeyText.contentDescription), feedback.text.toString())
            } else {
                assertNull(feedback)
            }
        } finally {
            try {
                controller?.pause()?.stop()?.destroy()
                shadowOf(Looper.getMainLooper()).idle()
            } finally {
                environment.close()
            }
        }
    }

    private fun assertCopiedKey(view: TextView, fullKey: String) {
        val display = BindingAdapters.shortKey(fullKey)
        assertEquals(display, view.text.toString())
        assertNotEquals(fullKey, display)
        assertTrue(view.performClick())
        val clip = clipboard(view.context).primaryClip!!
        assertEquals(fullKey, clip.getItemAt(0).text.toString())
        assertNotEquals(display, clip.getItemAt(0).text.toString())
        assertEquals(view.contentDescription, clip.description.label)
    }

    private fun assertEmptyKeyDoesNotCopy(view: TextView) {
        val service = clipboard(view.context)
        service.setPrimaryClip(ClipData.newPlainText("Sentinel", "Preserved clipboard"))
        assertEquals("—", view.text.toString())
        view.performClick()
        assertEquals("Preserved clipboard", service.primaryClip!!.getItemAt(0).text.toString())
    }

    private fun clipboard(context: android.content.Context): ClipboardManager =
        context.getSystemService(ClipboardManager::class.java)

    private fun key(seed: Int): Key = Key.fromBytes(ByteArray(32) { seed.toByte() })

    private fun profile(seed: Int): TunnelConfig = TunnelConfig.Builder()
        .setInterface(Interface.Builder().setKeyPair(KeyPair(key(seed))).build())
        .addPeer(Peer.Builder().setPublicKey(key(seed + 10)).build())
        .build()
}
