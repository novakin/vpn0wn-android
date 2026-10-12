package com.vpn0wn.android.fragment

import android.app.Application
import android.content.Context
import android.content.RestrictionsManager
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.EditText
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.lifecycleScope
import com.vpn0wn.android.R
import com.vpn0wn.android.activity.BaseActivity
import com.vpn0wn.android.activity.AppDataTransferSettingsTest.Authentication
import com.vpn0wn.android.databinding.TunnelEditorFragmentBinding
import com.vpn0wn.android.model.ObservableTunnel
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.android.util.BiometricAuthenticator
import com.vpn0wn.android.viewmodel.ConfigProxy
import com.vpn0wn.config.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = Application::class, sdk = [28], shadows = [Authentication::class])
@OptIn(ExperimentalCoroutinesApi::class)
class TunnelEditorKeyPolicyTest {
    @get:Rule val folder = TemporaryFolder()
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun selectedProfileLoadsPrivateKeyAndPskWithPasswordTransformation() = runTest {
        withEnvironment { env, fixture ->
            val selected = env.manager.create("loaded", secretProfile())
            fixture.fragment.onSelectedTunnelChanged(null, selected)

            runCurrent()
            fixture.settle()

            assertTrue(fixture.binding.privateKeyText.text.isNotEmpty())
            assertTrue(fixture.psk.text.isNotEmpty())
            assertMasked(fixture.binding.privateKeyText)
            assertMasked(fixture.psk)
            assertTrue(Authentication.callbacks.isEmpty())
        }
    }

    @Test
    fun restrictionBeforeFocusBlocksAuthenticationAndEmptyFieldReveal() = runTest {
        withEnvironment { env, fixture ->
            restrict(env, true)
            fixture.fragment.onKeyClick(fixture.binding.privateKeyText)
            fixture.binding.privateKeyText.setText("")
            fixture.fragment.onKeyClick(fixture.binding.privateKeyText)

            assertTrue(Authentication.callbacks.isEmpty())
            assertMasked(fixture.binding.privateKeyText)
        }
    }

    @Test
    fun restrictionDuringAuthenticationPreventsPrivateKeyAndPskReveal() = runTest {
        withEnvironment { env, fixture ->
            fixture.fragment.onKeyClick(fixture.binding.privateKeyText)
            restrict(env, true)

            Authentication.callbacks.single()(BiometricAuthenticator.Result.Success(null))
            fixture.fragment.onKeyClick(fixture.psk)

            assertMasked(fixture.binding.privateKeyText)
            assertMasked(fixture.psk)
            assertEquals(1, Authentication.callbacks.size)
        }
    }

    @Test
    fun laterFocusCannotReuseAuthorizationAfterRestriction() = runTest {
        withEnvironment { env, fixture ->
            fixture.fragment.onKeyClick(fixture.binding.privateKeyText)
            Authentication.callbacks.single()(BiometricAuthenticator.Result.Success(null))
            assertVisible(fixture.binding.privateKeyText)
            restrict(env, true)

            fixture.fragment.onKeyClick(fixture.psk)

            assertMasked(fixture.binding.privateKeyText)
            assertMasked(fixture.psk)
            restrict(env, false)
            fixture.fragment.onKeyClick(fixture.psk)
            assertEquals(2, Authentication.callbacks.size)
            Authentication.callbacks.last()(BiometricAuthenticator.Result.Success(null))
            assertVisible(fixture.psk)
        }
    }

    @Test
    fun selectionChangeRejectsOldPromptAndResetsPreviouslyShownFields() = runTest {
        withEnvironment { env, fixture ->
            val first = env.manager.create("first", secretProfile())
            val second = env.manager.create("second", secretProfile())
            fixture.fragment.onSelectedTunnelChanged(null, first)
            fixture.settle()
            fixture.fragment.onKeyClick(fixture.binding.privateKeyText)
            val oldCallback = Authentication.callbacks.single()
            oldCallback(BiometricAuthenticator.Result.Success(null))
            assertVisible(fixture.binding.privateKeyText)

            fixture.fragment.onSelectedTunnelChanged(first, second)
            fixture.settle()
            oldCallback(BiometricAuthenticator.Result.Success(null))

            assertMasked(fixture.binding.privateKeyText)
            fixture.fragment.onKeyClick(fixture.psk)
            assertEquals(2, Authentication.callbacks.size)
            Authentication.callbacks.last()(BiometricAuthenticator.Result.Success(null))
            assertVisible(fixture.psk)
        }
    }

    @Test
    fun pendingPromptForPreviousSelectionCannotAuthorizeCurrentSelection() = runTest {
        withEnvironment { env, fixture ->
            val first = env.manager.create("first", secretProfile())
            val second = env.manager.create("second", secretProfile())
            fixture.fragment.onSelectedTunnelChanged(null, first)
            fixture.settle()
            fixture.fragment.onKeyClick(fixture.binding.privateKeyText)
            val oldCallback = Authentication.callbacks.single()
            fixture.fragment.onSelectedTunnelChanged(first, second)
            fixture.settle()

            oldCallback(BiometricAuthenticator.Result.Success(null))

            assertMasked(fixture.binding.privateKeyText)
            fixture.fragment.onKeyClick(fixture.binding.privateKeyText)
            assertEquals(2, Authentication.callbacks.size)
        }
    }

    @Test
    fun destroyedViewRejectsOldPromptAndNewViewRequiresAuthentication() = runTest {
        withEnvironment { _, fixture ->
            fixture.fragment.onKeyClick(fixture.binding.privateKeyText)
            val oldKey = fixture.binding.privateKeyText
            val callback = Authentication.callbacks.single()
            val manager = fixture.activity.supportFragmentManager
            manager.beginTransaction().detach(fixture.fragment).commitNow()
            manager.beginTransaction().attach(fixture.fragment).commitNow()
            fixture.bind(secretProfile())

            callback(BiometricAuthenticator.Result.Success(null))

            assertMasked(oldKey)
            assertMasked(fixture.binding.privateKeyText)
            fixture.fragment.onKeyClick(fixture.binding.privateKeyText)
            assertEquals(2, Authentication.callbacks.size)
            Authentication.callbacks.last()(BiometricAuthenticator.Result.Success(null))
            assertVisible(fixture.binding.privateKeyText)
        }
    }

    @Test
    fun removedPeerFieldCannotReceivePendingReveal() = runTest {
        withEnvironment { _, fixture ->
            val oldPsk = fixture.psk
            fixture.fragment.onKeyClick(oldPsk)
            fixture.binding.config!!.peers.clear()
            fixture.binding.executePendingBindings()

            Authentication.callbacks.single()(BiometricAuthenticator.Result.Success(null))

            assertMasked(oldPsk)
            fixture.fragment.onKeyClick(fixture.binding.privateKeyText)
            assertEquals(2, Authentication.callbacks.size)
        }
    }

    @Test
    fun failedSampleKeepsPromptThenAllowsOtherKeysWithoutNewPrompt() = runTest {
        withEnvironment { _, fixture ->
            fixture.fragment.onKeyClick(fixture.binding.privateKeyText)
            val callback = Authentication.callbacks.single()
            callback(BiometricAuthenticator.Result.Failure(null, "Synthetic failed sample"))
            fixture.fragment.onKeyClick(fixture.psk)
            assertEquals(1, Authentication.callbacks.size)
            assertMasked(fixture.binding.privateKeyText)

            callback(BiometricAuthenticator.Result.Success(null))
            fixture.fragment.onKeyClick(fixture.psk)

            assertVisible(fixture.binding.privateKeyText)
            assertVisible(fixture.psk)
            assertEquals(1, Authentication.callbacks.size)
        }
    }

    @Test
    fun cancellationAndTerminalFailureDoNotRevealButNoLockStillWorks() = runTest {
        withEnvironment { _, fixture ->
            for (result in listOf(BiometricAuthenticator.Result.Cancelled,
                BiometricAuthenticator.Result.Failure(1, "Synthetic terminal failure"))) {
                fixture.fragment.onKeyClick(fixture.binding.privateKeyText)
                Authentication.callbacks.last()(result)
                assertMasked(fixture.binding.privateKeyText)
            }
            fixture.fragment.onKeyClick(fixture.binding.privateKeyText)

            Authentication.callbacks.last()(BiometricAuthenticator.Result.NoAuthenticationConfigured)

            assertVisible(fixture.binding.privateKeyText)
        }
    }

    @Test
    fun lateConfigLoadCannotReplaceCurrentSelection() = runTest {
        withEnvironment { env, fixture ->
            val first = env.manager.create("first", TunnelTestEnvironment.profile())
            first.onConfigChanged(null)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            env.store.loadEntered["first"] = entered
            env.store.releaseLoad["first"] = release
            val second = env.manager.create("second", TunnelTestEnvironment.profile(51821))
            try {
                fixture.fragment.onSelectedTunnelChanged(null, first)
                runCurrent()
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                fixture.fragment.onSelectedTunnelChanged(first, second)
                runCurrent()
                release.countDown()

                fixture.settle()

                assertEquals("51821", fixture.binding.config!!.`interface`.listenPort)
                assertEquals("second", fixture.binding.name)
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun loadedSecretsReplaceUnmaskedEmptyFieldWithMaskedFieldAfterRestriction() = runTest {
        withEnvironment { env, fixture ->
            val first = env.manager.create("first", secretProfile())
            first.onConfigChanged(null)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            env.store.loadEntered["first"] = entered
            env.store.releaseLoad["first"] = release
            try {
                fixture.fragment.onSelectedTunnelChanged(null, first)
                fixture.binding.executePendingBindings()
                runCurrent()
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                assertTrue(fixture.binding.privateKeyText.text.isEmpty())
                fixture.fragment.onKeyClick(fixture.binding.privateKeyText)
                assertVisible(fixture.binding.privateKeyText)
                restrict(env, true)
                release.countDown()

                fixture.settle()

                assertTrue(fixture.binding.privateKeyText.text.isNotEmpty())
                assertMasked(fixture.binding.privateKeyText)
                assertMasked(fixture.psk)
                assertTrue(Authentication.callbacks.isEmpty())
            } finally {
                release.countDown()
            }
        }
    }

    private suspend fun TestScope.withEnvironment(block: suspend (TunnelTestEnvironment, Fixture) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        Authentication.callbacks.clear()
        Authentication.calls = 0
        Authentication.autoCancel = false
        Authentication.expectedTitleRes = R.string.biometric_prompt_private_key_title
        val env = TunnelTestEnvironment(folder.root, backgroundScope)
        restrict(env, false)
        val controller = Robolectric.buildActivity(HostActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.AppTheme) }
        try {
            env.manager.getTunnels()
            controller.setup()
            val fragment = TunnelEditorFragment()
            activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
            val fixture = Fixture(activity, fragment)
            fixture.bind(secretProfile())
            Authentication.callbacks.clear()
            block(env, fixture)
        } finally {
            try {
                controller.pause().stop().destroy()
            } finally {
                env.close()
            }
        }
    }

    private class Fixture(val activity: HostActivity, val fragment: TunnelEditorFragment) {
        val binding: TunnelEditorFragmentBinding
            get() = checkNotNull(DataBindingUtil.getBinding(fragment.requireView()))
        val psk: EditText
            get() = binding.mainContainer.findViewById(R.id.pre_shared_key_text)
        fun bind(config: Config) {
            binding.config = ConfigProxy(config)
            binding.executePendingBindings()
        }
        suspend fun settle() {
            fragment.viewLifecycleOwner.lifecycleScope.coroutineContext[Job]!!.children.toList().joinAll()
            binding.executePendingBindings()
        }
    }

    private fun restrict(env: TunnelTestEnvironment, disabled: Boolean) {
        val restrictions = env.application.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
        shadowOf(restrictions).setApplicationRestrictions(Bundle().apply { putBoolean("disable_config_export", disabled) })
    }

    private fun assertMasked(edit: EditText) {
        assertEquals(InputType.TYPE_CLASS_TEXT, edit.inputType and InputType.TYPE_MASK_CLASS)
        assertTrue(edit.transformationMethod is PasswordTransformationMethod)
        if (edit.text.isNotEmpty()) {
            assertFalse(edit.transformationMethod.getTransformation(edit.text, edit).toString() == edit.text.toString())
        }
    }

    private fun assertVisible(edit: EditText) {
        assertEquals(visibleInputType, edit.inputType)
        assertFalse(edit.transformationMethod is PasswordTransformationMethod)
    }

    private fun secretProfile() = Config.parse("""
        [Interface]
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        [Peer]
        PublicKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=
        PresharedKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=
    """.trimIndent().byteInputStream())

    class HostActivity : BaseActivity() {
        override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) = true
    }

    companion object {
        private const val visibleInputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    }
}
