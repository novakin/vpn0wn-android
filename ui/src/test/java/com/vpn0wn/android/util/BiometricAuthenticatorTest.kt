package com.vpn0wn.android.util

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelStore
import com.vpn0wn.android.R
import java.util.concurrent.Executor
import java.util.IdentityHashMap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
        PromptShadow.reset()
        controller = Robolectric.buildActivity(AuthenticationActivity::class.java).setup()
        activity = controller.get()
        fragment = Fragment()
        activity.supportFragmentManager.beginTransaction().add(fragment, "authentication").commitNow()
        setDeviceSecure(true)
    }

    @After
    fun tearDown() {
        if (!activity.isDestroyed) controller.pause().stop().destroy()
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
        setDeviceSecure(false)
        for (code in listOf(BiometricPrompt.ERROR_HW_NOT_PRESENT, BiometricPrompt.ERROR_HW_UNAVAILABLE,
            BiometricPrompt.ERROR_NO_BIOMETRICS, BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL)) {
            results.clear()
            authenticate()

            sendPromptEvent { it.onAuthenticationError(code, "Synthetic authentication error") }

            val failure = results.single() as BiometricAuthenticator.Result.Failure
            assertEquals(code, failure.code)
            assertEquals(activity.getString(R.string.biometric_auth_error_reason, "Synthetic authentication error"), failure.message)
        }
    }

    @Test
    fun nativeCancellationRemainsCancellation() {
        for (code in listOf(BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_USER_CANCELED,
            BiometricPrompt.ERROR_NEGATIVE_BUTTON)) {
            results.clear()
            authenticate()

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

    @Test
    fun overlappingFragmentsCannotReplaceThePendingNativeCallback() {
        authenticate()
        val firstCallback = PromptShadow.callback
        val secondResults = mutableListOf<BiometricAuthenticator.Result>()
        val second = addFragment("second")

        BiometricAuthenticator.authenticate(R.string.app_data_transfer_auth_title, second) { secondResults += it }

        assertEquals(listOf(BiometricAuthenticator.Result.Cancelled), secondResults)
        assertEquals(1, PromptShadow.registrations)
        assertTrue(firstCallback === PromptShadow.callback)
        sendPromptEvent { it.onAuthenticationSucceeded(success()) }
        assertEquals(listOf(BiometricAuthenticator.Result.Success(null)), results)
    }

    @Test
    fun queuedNativeSuccessCannotAuthorizeTheNextRequest() {
        authenticate()
        sendPromptEvent(drain = false) { it.onAuthenticationSucceeded(success()) }
        val secondResults = mutableListOf<BiometricAuthenticator.Result>()

        BiometricAuthenticator.authenticate(R.string.app_data_transfer_auth_title, addFragment("second")) { secondResults += it }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(BiometricAuthenticator.Result.Cancelled), secondResults)
        assertEquals(listOf(BiometricAuthenticator.Result.Success(null)), results)
        assertEquals(1, PromptShadow.registrations)
    }

    @Test
    fun preflightFailureCannotReplaceTheOutstandingNativeCallback() {
        authenticate()
        val oldCallback = PromptShadow.callback
        val deniedResults = mutableListOf<BiometricAuthenticator.Result>()
        ManagerShadow.status = BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE

        BiometricAuthenticator.authenticate(R.string.app_data_transfer_auth_title, addFragment("second")) { deniedResults += it }

        assertTrue(deniedResults.single() is BiometricAuthenticator.Result.Failure)
        assertEquals(1, PromptShadow.registrations)
        assertTrue(oldCallback === PromptShadow.callback)
        sendPromptEvent { it.onAuthenticationSucceeded(success()) }
        assertEquals(listOf(BiometricAuthenticator.Result.Success(null)), results)
    }

    @Test
    fun explicitNoLockResultCannotReplaceTheOutstandingNativeCallback() {
        authenticate()
        val oldCallback = PromptShadow.callback
        val noLockResults = mutableListOf<BiometricAuthenticator.Result>()
        setDeviceSecure(false)
        ManagerShadow.status = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED

        BiometricAuthenticator.authenticate(R.string.app_data_transfer_auth_title, addFragment("second")) { noLockResults += it }

        assertEquals(listOf(BiometricAuthenticator.Result.NoAuthenticationConfigured), noLockResults)
        assertEquals(1, PromptShadow.registrations)
        assertTrue(oldCallback === PromptShadow.callback)
        sendPromptEvent { it.onAuthenticationSucceeded(success()) }
        assertEquals(listOf(BiometricAuthenticator.Result.Success(null)), results)
    }

    @Test
    fun failedSampleKeepsTheNativeSessionReserved() {
        authenticate()
        sendPromptEvent { it.onAuthenticationFailed() }
        val secondResults = mutableListOf<BiometricAuthenticator.Result>()

        BiometricAuthenticator.authenticate(R.string.app_data_transfer_auth_title, addFragment("second")) { secondResults += it }

        assertEquals(listOf(BiometricAuthenticator.Result.Cancelled), secondResults)
        sendPromptEvent { it.onAuthenticationSucceeded(success()) }
        assertEquals(2, results.size)
        assertTrue(results.first() is BiometricAuthenticator.Result.Failure)
        assertEquals(BiometricAuthenticator.Result.Success(null), results.last())
    }

    @Test
    fun duplicateOldEventsCannotCompleteANewerSession() {
        authenticate()
        val oldCallback = PromptShadow.callback!!
        sendPromptEvent { it.onAuthenticationSucceeded(success()) }
        results.clear()
        authenticate()

        oldCallback.onAuthenticationSucceeded(success())
        oldCallback.onAuthenticationFailed()
        oldCallback.onAuthenticationError(BiometricPrompt.ERROR_CANCELED, "Synthetic cancellation")
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(results.isEmpty())
        sendPromptEvent { it.onAuthenticationSucceeded(success()) }
        assertEquals(listOf(BiometricAuthenticator.Result.Success(null)), results)
    }

    @Test
    fun terminalCallbackReentryKeepsTheReservationUntilDispatchFinishes() {
        val nestedResults = mutableListOf<BiometricAuthenticator.Result>()
        BiometricAuthenticator.authenticate(R.string.app_data_transfer_auth_title, fragment) {
            results += it
            BiometricAuthenticator.authenticate(R.string.app_data_transfer_auth_title, fragment) { nestedResults += it }
        }

        sendPromptEvent { it.onAuthenticationSucceeded(success()) }

        assertEquals(listOf(BiometricAuthenticator.Result.Cancelled), nestedResults)
        assertEquals(1, PromptShadow.registrations)
        results.clear()
        authenticate()
        assertEquals(2, PromptShadow.registrations)
    }

    @Test
    fun rotationRetainsTheOutstandingNativeSession() {
        authenticate()
        val oldCallback = PromptShadow.callback!!
        controller.configurationChange()
        activity = controller.get()
        fragment = activity.supportFragmentManager.findFragmentByTag("authentication")!!
        val secondResults = mutableListOf<BiometricAuthenticator.Result>()

        BiometricAuthenticator.authenticate(R.string.app_data_transfer_auth_title, fragment) { secondResults += it }

        assertEquals(listOf(BiometricAuthenticator.Result.Cancelled), secondResults)
        assertEquals(1, PromptShadow.registrations)
        oldCallback.onAuthenticationSucceeded(success())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(BiometricAuthenticator.Result.Success(null)), results)
    }

    @Test
    fun separateActivitiesOwnIndependentNativeSessions() {
        authenticate()
        val oldCallback = PromptShadow.callback!!
        val otherController = Robolectric.buildActivity(AuthenticationActivity::class.java).setup()
        try {
            val otherActivity = otherController.get()
            val otherFragment = Fragment()
            otherActivity.supportFragmentManager.beginTransaction().add(otherFragment, "other").commitNow()
            shadowOf(otherActivity.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(true)
            val otherResults = mutableListOf<BiometricAuthenticator.Result>()

            BiometricAuthenticator.authenticate(R.string.app_data_transfer_auth_title, otherFragment) { otherResults += it }

            assertTrue(otherResults.isEmpty())
            assertEquals(2, PromptShadow.registrations)
            sendPromptEvent { it.onAuthenticationSucceeded(success()) }
            oldCallback.onAuthenticationSucceeded(success())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(BiometricAuthenticator.Result.Success(null)), results)
            assertEquals(listOf(BiometricAuthenticator.Result.Success(null)), otherResults)
        } finally {
            otherController.pause().stop().destroy()
        }
    }

    @Test
    fun destroyingActivityCancelsOnceAndSuppressesQueuedSuccess() {
        authenticate()
        val oldCallback = PromptShadow.callback!!
        sendPromptEvent(drain = false) { it.onAuthenticationSucceeded(success()) }

        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        oldCallback.onAuthenticationSucceeded(success())
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(BiometricAuthenticator.Result.Cancelled), results)
    }

    @Test
    fun removedFragmentCannotStartANativeSession() {
        activity.supportFragmentManager.beginTransaction().remove(fragment).commitNow()

        authenticate()

        assertEquals(listOf(BiometricAuthenticator.Result.Cancelled), results)
        assertEquals(0, PromptShadow.registrations)
    }

    @Test
    fun nativeEventsUseCapturedStringsAfterFragmentDetaches() {
        authenticate()
        activity.supportFragmentManager.beginTransaction().remove(fragment).commitNow()

        sendPromptEvent { it.onAuthenticationError(BiometricPrompt.ERROR_HW_UNAVAILABLE, "Synthetic error") }

        val failure = results.single() as BiometricAuthenticator.Result.Failure
        assertEquals(activity.getString(R.string.biometric_auth_error_reason, "Synthetic error"), failure.message)
        assertEquals(1, PromptShadow.activityRegistrations)
    }

    @Test
    fun stateSavedActivityCannotSilentlyStrandANativeSession() {
        controller.saveInstanceState(Bundle())

        authenticate()

        assertEquals(listOf(BiometricAuthenticator.Result.Cancelled), results)
        assertEquals(0, PromptShadow.registrations)
    }

    @Test
    fun constructorFailureReleasesTheReservationAndPropagates() {
        val failure = IllegalStateException("Synthetic constructor failure")
        PromptShadow.constructionFailure = failure
        assertTrue(failure === assertThrows(IllegalStateException::class.java) { authenticate() })
        PromptShadow.constructionFailure = null

        authenticate()
        sendPromptEvent { it.onAuthenticationSucceeded(success()) }

        assertEquals(listOf(BiometricAuthenticator.Result.Success(null)), results)
    }

    @Test
    fun launchFailureReleasesTheReservationAndSuppressesTheOldCallback() {
        val failure = IllegalStateException("Synthetic launch failure")
        PromptShadow.launchFailure = failure
        assertTrue(failure === assertThrows(IllegalStateException::class.java) { authenticate() })
        val oldCallback = PromptShadow.callback!!
        PromptShadow.launchFailure = null

        authenticate()
        oldCallback.onAuthenticationSucceeded(success())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(results.isEmpty())
        sendPromptEvent { it.onAuthenticationSucceeded(success()) }
        assertEquals(listOf(BiometricAuthenticator.Result.Success(null)), results)
    }

    @Test
    fun throwingTerminalCallerStillReleasesTheSession() {
        BiometricAuthenticator.authenticate(R.string.app_data_transfer_auth_title, fragment) {
            throw IllegalStateException("Synthetic caller failure")
        }
        assertThrows(IllegalStateException::class.java) {
            sendPromptEvent { it.onAuthenticationSucceeded(success()) }
        }

        authenticate()
        sendPromptEvent { it.onAuthenticationSucceeded(success()) }

        assertEquals(listOf(BiometricAuthenticator.Result.Success(null)), results)
    }

    private fun addFragment(tag: String): Fragment = Fragment().also {
        activity.supportFragmentManager.beginTransaction().add(it, tag).commitNow()
    }

    private fun success(): BiometricPrompt.AuthenticationResult = BiometricPrompt.AuthenticationResult::class.java
        .getDeclaredConstructor(BiometricPrompt.CryptoObject::class.java, Int::class.javaPrimitiveType)
        .apply { isAccessible = true }
        .newInstance(null, BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL)

    private fun authenticate() {
        BiometricAuthenticator.authenticate(R.string.app_data_transfer_auth_title, fragment) { results += it }
    }

    private fun setDeviceSecure(secure: Boolean) {
        shadowOf(activity.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(secure)
    }

    private fun sendPromptEvent(drain: Boolean = true, event: (BiometricPrompt.AuthenticationCallback) -> Unit) {
        val prompt = PromptShadow.current!!
        prompt.executor.execute { event(prompt.callback) }
        if (drain) shadowOf(Looper.getMainLooper()).idle()
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
            register(fragment.requireActivity(), executor, authenticationCallback)
        }

        @Implementation
        fun __constructor__(activity: FragmentActivity, executor: Executor, authenticationCallback: BiometricPrompt.AuthenticationCallback) {
            activityRegistrations++
            register(activity, executor, authenticationCallback)
        }

        @Implementation
        fun authenticate(info: BiometricPrompt.PromptInfo) {
            launchFailure?.let { throw it }
            promptInfo = info
        }

        private fun register(activity: FragmentActivity, executor: Executor, authenticationCallback: BiometricPrompt.AuthenticationCallback) {
            constructionFailure?.let { throw it }
            registrations++
            current = sessions.getOrPut(activity.viewModelStore) { NativePrompt(executor, authenticationCallback) }
            current!!.executor = executor
            current!!.callback = authenticationCallback
        }

        class NativePrompt(var executor: Executor, var callback: BiometricPrompt.AuthenticationCallback)

        companion object {
            private val sessions = IdentityHashMap<ViewModelStore, NativePrompt>()
            var current: NativePrompt? = null
            val callback: BiometricPrompt.AuthenticationCallback? get() = current?.callback
            var promptInfo: BiometricPrompt.PromptInfo? = null
            var registrations = 0
            var activityRegistrations = 0
            var constructionFailure: RuntimeException? = null
            var launchFailure: RuntimeException? = null

            fun reset() {
                sessions.clear()
                current = null
                promptInfo = null
                registrations = 0
                activityRegistrations = 0
                constructionFailure = null
                launchFailure = null
            }
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
