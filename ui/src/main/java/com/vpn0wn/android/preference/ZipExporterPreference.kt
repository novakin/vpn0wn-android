/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Modified for vpn0wn.
 */
package com.vpn0wn.android.preference

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.snackbar.Snackbar
import com.vpn0wn.android.Application
import com.vpn0wn.android.R
import com.vpn0wn.android.util.AdminKnobs
import com.vpn0wn.android.util.BiometricAuthenticator
import com.vpn0wn.android.util.DownloadsFileSaver
import com.vpn0wn.android.util.ErrorMessages
import com.vpn0wn.android.util.activity
import com.vpn0wn.android.util.vpn0wnStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Preference implementing a button that asynchronously exports config zips.
 */
class ZipExporterPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    private var exportedFilePath: String? = null
    private var authenticationRequest: Any? = null
    private val downloadsFileSaver = DownloadsFileSaver(activity)

    // ConfigStore failures need user feedback while cancellation and unrelated Errors propagate.
    @Suppress("TooGenericExceptionCaught")
    private fun exportZip(fragment: PreferenceFragmentCompat, ownerView: View) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                ensureExportAllowed(fragment, ownerView)
                val tunnels = Application.getTunnelManager().getTunnels().toList()
                exportedFilePath = withContext(Dispatchers.IO) {
                    val configs = supervisorScope {
                        tunnels.map { async { it.getConfigAsync() } }.awaitAll()
                    }
                    require(configs.isNotEmpty()) { context.getString(R.string.no_tunnels_error) }
                    ensureExportAllowed(fragment, ownerView)
                    downloadsFileSaver.save("vpn0wn-export.zip", "application/zip", true) { stream ->
                        ensureExportAllowed(fragment, ownerView)
                        ZipOutputStream(stream).use { zip ->
                            for (i in configs.indices) {
                                ensureExportAllowed(fragment, ownerView)
                                zip.putNextEntry(ZipEntry(tunnels[i].name + ".conf"))
                                val configBytes = configs[i].toWgQuickString().toByteArray(StandardCharsets.UTF_8)
                                ensureExportAllowed(fragment, ownerView)
                                zip.write(configBytes)
                            }
                            zip.closeEntry()
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                notifyChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = ErrorMessages[e]
                val message = context.getString(R.string.zip_export_error, error)
                Log.e(TAG, message, e)
                if (isCurrentView(fragment, ownerView)) {
                    Snackbar.make(ownerView, message, Snackbar.LENGTH_LONG).vpn0wnStyle().show()
                }
            } finally {
                if (fragment.view === ownerView) isEnabled = true
            }
        }
    }

    override fun getSummary() =
        if (exportedFilePath == null) context.getString(R.string.zip_export_summary) else context.getString(R.string.zip_export_success, exportedFilePath)

    override fun getTitle() = context.getString(R.string.zip_export_title)

    override fun onDetached() {
        authenticationRequest = null
        isEnabled = true
        super.onDetached()
    }

    override fun onClick() {
        if (!isEnabled || AdminKnobs.disableConfigExport) return
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_content)
            as? PreferenceFragmentCompat ?: return
        val ownerView = fragment.view ?: return
        if (!isCurrentView(fragment, ownerView)) return
        val request = Any()
        authenticationRequest = request
        isEnabled = false
        try {
            BiometricAuthenticator.authenticate(R.string.biometric_prompt_zip_exporter_title, fragment) {
                if (authenticationRequest !== request || fragment.view !== ownerView) return@authenticate
                if (it is BiometricAuthenticator.Result.Failure && it.code == null) {
                    if (isCurrentView(fragment, ownerView)) {
                        Snackbar.make(ownerView, it.message, Snackbar.LENGTH_SHORT).vpn0wnStyle().show()
                    }
                    return@authenticate
                }
                authenticationRequest = null
                if (!isCurrentView(fragment, ownerView) || AdminKnobs.disableConfigExport) {
                    isEnabled = true
                    return@authenticate
                }
                when (it) {
                    is BiometricAuthenticator.Result.Success, is BiometricAuthenticator.Result.NoAuthenticationConfigured -> {
                        exportZip(fragment, ownerView)
                    }

                    is BiometricAuthenticator.Result.Failure -> {
                        isEnabled = true
                        Snackbar.make(ownerView, it.message, Snackbar.LENGTH_SHORT).vpn0wnStyle().show()
                    }

                    is BiometricAuthenticator.Result.Cancelled -> isEnabled = true
                }
            }
        } catch (failure: RuntimeException) {
            if (authenticationRequest === request) authenticationRequest = null
            if (fragment.view === ownerView) isEnabled = true
            throw failure
        }
    }

    private fun isCurrentView(fragment: PreferenceFragmentCompat, ownerView: View) =
        fragment.view === ownerView && fragment.viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun ensureExportAllowed(fragment: PreferenceFragmentCompat, ownerView: View) {
        if (!isCurrentView(fragment, ownerView)) throw CancellationException("Export view is no longer active")
        if (AdminKnobs.disableConfigExport) throw SecurityException("Config export prohibited")
    }

    companion object {
        private const val TAG = "vpn0wn/ZipExporterPreference"
    }
}
