/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Modified for vpn0wn.
 */

package com.vpn0wn.android.util

import android.app.KeyguardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.vpn0wn.android.R


object BiometricAuthenticator {
    private const val TAG = "vpn0wn/BiometricAuthenticator"

    // Not all devices support strong biometric auth so we're allowing both device credentials as
    // well as weak biometrics.
    private const val allowedAuthenticators = Authenticators.DEVICE_CREDENTIAL or Authenticators.BIOMETRIC_WEAK

    sealed class Result {
        data class Success(val cryptoObject: BiometricPrompt.CryptoObject?) : Result()
        data class Failure(val code: Int?, val message: CharSequence) : Result()
        object NoAuthenticationConfigured : Result()
        object Cancelled : Result()
    }

    @MainThread
    fun authenticate(
        @StringRes dialogTitleRes: Int,
        fragment: Fragment,
        callback: (Result) -> Unit
    ) {
        val activity = fragment.activity
        if (!fragment.isAdded || activity == null || activity.isDestroyed ||
            activity.supportFragmentManager.isStateSaved) {
            callback(Result.Cancelled)
            return
        }
        val context = fragment.requireContext()
        val errorMessage = context.getString(R.string.biometric_auth_error)
        val authenticationStatus = BiometricManager.from(context).canAuthenticate(allowedAuthenticators)
        val keyguardManager = context.getSystemService(KeyguardManager::class.java)
        if (authenticationStatus != BiometricManager.BIOMETRIC_SUCCESS) {
            callback(
                if (keyguardManager != null && !keyguardManager.isDeviceSecure &&
                    (authenticationStatus == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ||
                        authenticationStatus == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE)) {
                    Result.NoAuthenticationConfigured
                } else {
                    Result.Failure(authenticationStatus, errorMessage)
                }
            )
            return
        }
        startNativeAuthentication(activity, context, dialogTitleRes, callback)
    }

    private fun startNativeAuthentication(
        activity: FragmentActivity,
        context: Context,
        @StringRes dialogTitleRes: Int,
        callback: (Result) -> Unit
    ) {
        val resources = context.resources
        val errorMessage = resources.getString(R.string.biometric_auth_error)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(resources.getString(dialogTitleRes))
            .setAllowedAuthenticators(allowedAuthenticators)
            .build()
        val sessions = ViewModelProvider(activity)[NativeSessionViewModel::class.java]
        val session = sessions.acquire(callback)
        if (session == null) {
            callback(Result.Cancelled)
            return
        }
        val authCallback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Log.d(TAG, "BiometricAuthentication error: errorCode=$errorCode, msg=$errString")
                session.report(
                    when (errorCode) {
                        BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON -> {
                            Result.Cancelled
                        }

                        else -> Result.Failure(errorCode, resources.getString(R.string.biometric_auth_error_reason, errString))
                    },
                    terminal = true
                )
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                session.report(Result.Failure(null, errorMessage), terminal = false)
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                session.report(Result.Success(result.cryptoObject), terminal = true)
            }
        }
        var launched = false
        try {
            // AndroidX shares its callback across the Activity; capture native events before Main dispatch.
            BiometricPrompt(activity, { it.run() }, authCallback).authenticate(promptInfo)
            launched = true
        } finally {
            if (!launched) session.discard()
        }
    }

    internal class NativeSessionViewModel : ViewModel() {
        private var pending: NativeSession? = null
        private var cleared = false

        fun acquire(callback: (Result) -> Unit): NativeSession? {
            if (cleared || pending != null) return null
            return NativeSession(this, callback).also { pending = it }
        }

        fun release(session: NativeSession) {
            if (pending === session) pending = null
        }

        override fun onCleared() {
            cleared = true
            pending?.cancel()
        }
    }

    internal class NativeSession(
        private val owner: NativeSessionViewModel,
        callback: (Result) -> Unit
    ) {
        private var callback: ((Result) -> Unit)? = callback
        private val handler = Handler(Looper.getMainLooper())
        private var terminalQueued = false
        private var completed = false

        fun report(result: Result, terminal: Boolean) {
            synchronized(this) {
                if (terminalQueued || completed) return
                if (terminal) terminalQueued = true
                handler.post { dispatch(result, terminal) }
            }
        }

        private fun dispatch(result: Result, terminal: Boolean) {
            val consumer = synchronized(this) {
                if (completed) return
                val consumer = callback
                if (terminal) {
                    completed = true
                    callback = null
                }
                consumer
            } ?: return
            try {
                consumer(result)
            } finally {
                if (terminal) owner.release(this)
            }
        }

        fun cancel() {
            val consumer = synchronized(this) {
                if (completed) return
                completed = true
                callback.also { callback = null }
            } ?: return
            try {
                consumer(Result.Cancelled)
            } finally {
                owner.release(this)
            }
        }

        fun discard() {
            synchronized(this) {
                completed = true
                callback = null
            }
            owner.release(this)
        }
    }
}
