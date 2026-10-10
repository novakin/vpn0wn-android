package com.vpn0wn.android.util

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.os.Looper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.vpn0wn.android.R
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [
    BiometricAuthenticatorTest.ManagerShadow::class,
    BiometricAuthenticatorTest.PromptShadow::class,
])
class BiometricAuthenticatorTest {
    private lateinit var controller: ActivityController<AuthenticationActivity>
    private lateinit var activity: AuthenticationActivity
    private lateinit var fragment: Fragment
    private val results = mutableListOf<BiometricAuthenticator.Result>()

    @Before
    fun setUp() {
        ManagerShadow.status = BiometricManager.BIOMETRIC_SUCCESS
        ManagerShadow.authenticators = null
        PromptShadow.callback = null
        PromptShadow.callbackExecutor = null
        PromptShadow.promptInfo = null
        controller = Robolectric.buildActivity(AuthenticationActivity::class.java).setup()
        activity = controller.get()
        fragment = Fragment()
        activity.supportFragmentManager.beginTransaction().add(fragment, "authentication").commitNow()
        setDeviceSecure(true)
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
    }

    @Test
    @Config(sdk = [24, 29, 30])
    fun configuredCredentialUsesNativePromptWithWeakBiometricOrCredential() {
        authenticate()

        assertTrue(results.isEmpty())
        assertEquals(Authenticators.DEVICE_CREDENTIAL or Authenticators.BIOMETRIC_WEAK, ManagerShadow.authenticators)
        assertEquals(Authenticators.DEVICE_CREDENTIAL or Authenticators.BIOMETRIC_WEAK, PromptShadow.promptInfo!!.allowedAuthenticators)
        assertEquals(activity.getString(R.string.app_data_transfer_auth_title), PromptShadow.promptInfo!!.title)
    }

    @Test
    fun configuredCredentialCannotBypassAnyPreflightFailure() {
        for (status in preflightFailures) {
            results.clear()
            ManagerShadow.status = status

            authenticate()

            val failure = results.single() as BiometricAuthenticator.Result.Failure
            assertEquals(status, failure.code)
            assertNull(PromptShadow.promptInfo)
        }
    }

    @Test
    @Config(sdk = [24, 29, 30])
    fun noConfiguredCredentialAllowsOnlyExplicitAuthenticationAbsence() {
        setDeviceSecure(false)
        for (status in listOf(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED, BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE)) {
            results.clear()
            ManagerShadow.status = status

            authenticate()

            assertEquals(listOf(BiometricAuthenticator.Result.NoAuthenticationConfigured), results)
            assertNull(PromptShadow.promptInfo)
        }
    }

    @Test
    fun nonsecureDeviceDoesNotTurnUnknownOrUnavailableAuthenticationIntoAuthorization() {
        setDeviceSecure(false)
        for (status in transientOrUnsupportedFailures) {
            results.clear()
            ManagerShadow.status = status

            authenticate()

            val failure = results.single() as BiometricAuthenticator.Result.Failure
            assertEquals(status, failure.code)
            assertNull(PromptShadow.promptInfo)
        }
    }

    @Test
    fun absentKeyguardServiceCannotAuthorizeExport() {
        activity.keyguardAvailable = false
        ManagerShadow.status = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED

        authenticate()

        val failure = results.single() as BiometricAuthenticator.Result.Failure
        assertEquals(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED, failure.code)
        assertNull(PromptShadow.promptInfo)
    }

    @Test
    fun runtimeHardwareAndEnrollmentErrorsNeverAuthorizeEvenAfterCredentialRemoval() {
        authenticate()
        setDeviceSecure(false)
        for (code in listOf(BiometricPrompt.ERROR_HW_NOT_PRESENT, BiometricPrompt.ERROR_HW_UNAVAILABLE,
            BiometricPrompt.ERROR_NO_BIOMETRICS, BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL)) {
            results.clear()

            sendPromptEvent { it.onAuthenticationError(code, "Synthetic authentication error") }

            val failure = results.single() as BiometricAuthenticator.Result.Failure
            assertEquals(code, failure.code)
            assertEquals(activity.getString(R.string.biometric_auth_error_reason, "Synthetic authentication error"), failure.message)
        }
    }

    @Test
    fun nativeCancellationRemainsCancellation() {
        authenticate()
        for (code in listOf(BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_USER_CANCELED,
            BiometricPrompt.ERROR_NEGATIVE_BUTTON)) {
            results.clear()

            sendPromptEvent { it.onAuthenticationError(code, "Synthetic cancellation") }

            assertEquals(listOf(BiometricAuthenticator.Result.Cancelled), results)
        }
    }

    @Test
    fun failedSampleRemainsNonterminalAndSamePromptCanSucceed() {
        authenticate()
        val success = BiometricPrompt.AuthenticationResult::class.java
            .getDeclaredConstructor(BiometricPrompt.CryptoObject::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(null, BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL)
        sendPromptEvent { it.onAuthenticationFailed() }
        sendPromptEvent { it.onAuthenticationSucceeded(success) }

        val failure = results.first() as BiometricAuthenticator.Result.Failure
        assertNull(failure.code)
        assertEquals(activity.getString(R.string.biometric_auth_error), failure.message)
        assertEquals(BiometricAuthenticator.Result.Success(null), results.last())
        assertEquals(2, results.size)
    }

    private fun authenticate() {
        BiometricAuthenticator.authenticate(R.string.app_data_transfer_auth_title, fragment) { results += it }
    }

    private fun setDeviceSecure(secure: Boolean) {
        shadowOf(activity.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(secure)
    }

    private fun sendPromptEvent(event: (BiometricPrompt.AuthenticationCallback) -> Unit) {
        PromptShadow.callbackExecutor!!.execute { event(PromptShadow.callback!!) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    class AuthenticationActivity : FragmentActivity() {
        var keyguardAvailable = true

        override fun getSystemService(name: String): Any? =
            if (name == Context.KEYGUARD_SERVICE && !keyguardAvailable) null else super.getSystemService(name)
    }

    @Implements(value = BiometricManager::class, isInAndroidSdk = false)
    class ManagerShadow {
        @Implementation
        fun canAuthenticate(allowedAuthenticators: Int): Int {
            authenticators = allowedAuthenticators
            return status
        }

        companion object {
            var status = BiometricManager.BIOMETRIC_SUCCESS
            var authenticators: Int? = null
        }
    }

    @Implements(value = BiometricPrompt::class, isInAndroidSdk = false)
    class PromptShadow {
        @Implementation
        fun __constructor__(fragment: Fragment, executor: Executor, authenticationCallback: BiometricPrompt.AuthenticationCallback) {
            assertTrue(fragment.isAdded)
            callbackExecutor = executor
            callback = authenticationCallback
        }

        @Implementation
        fun authenticate(info: BiometricPrompt.PromptInfo) {
            promptInfo = info
        }

        companion object {
            var callback: BiometricPrompt.AuthenticationCallback? = null
            var callbackExecutor: Executor? = null
            var promptInfo: BiometricPrompt.PromptInfo? = null
        }
    }

    companion object {
        private val transientOrUnsupportedFailures = listOf(
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
        )
        private val preflightFailures = transientOrUnsupportedFailures + listOf(
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
        )
    }
}
