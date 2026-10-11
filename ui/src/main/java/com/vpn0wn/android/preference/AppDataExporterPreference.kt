/*
 * Copyright © 2026 vpn0wn contributors.
 * SPDX-License-Identifier: Apache-2.0
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
import com.vpn0wn.android.provisioning.VpsServerStore
import com.vpn0wn.android.util.AdminKnobs
import com.vpn0wn.android.util.AppDataTransfer
import com.vpn0wn.android.util.BiometricAuthenticator
import com.vpn0wn.android.util.DownloadsFileSaver
import com.vpn0wn.android.util.activity
import com.vpn0wn.android.util.vpn0wnStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.io.IOException

class AppDataExporterPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    private var exportedFilePath: String? = null
    private var authenticationRequest: Any? = null
    private val downloadsFileSaver = DownloadsFileSaver(activity)

    override fun getTitle(): CharSequence = context.getString(R.string.app_data_transfer_export_title)

    override fun getSummary(): CharSequence = exportedFilePath?.let {
        context.getString(R.string.app_data_transfer_export_success, it)
    } ?: context.getString(R.string.app_data_transfer_export_summary)

    override fun onClick() {
        if (!isEnabled || AdminKnobs.disableConfigExport) return
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_content)
            as? PreferenceFragmentCompat ?: return
        val ownerView = fragment.view ?: return
        if (!fragment.viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val importer = fragment.findPreference<Preference>("import_app_data")
        val request = Any()
        authenticationRequest = request
        isEnabled = false
        importer?.isEnabled = false
        try {
            BiometricAuthenticator.authenticate(R.string.app_data_transfer_auth_title, fragment) { result ->
                if (authenticationRequest !== request) return@authenticate
                if (fragment.view !== ownerView) return@authenticate
                if (result is BiometricAuthenticator.Result.Failure && result.code == null) {
                    if (fragment.viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        Snackbar.make(
                            ownerView,
                            R.string.biometric_auth_error,
                            Snackbar.LENGTH_SHORT,
                        ).vpn0wnStyle().show()
                    }
                    return@authenticate
                }
                authenticationRequest = null
                if (!fragment.viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    isEnabled = true
                    importer?.isEnabled = true
                    return@authenticate
                }
                when (result) {
                    is BiometricAuthenticator.Result.Success,
                    is BiometricAuthenticator.Result.NoAuthenticationConfigured -> {
                        if (AdminKnobs.disableConfigExport) {
                            isEnabled = true
                            importer?.isEnabled = true
                        } else {
                            fragment.viewLifecycleOwner.lifecycleScope.launch {
                                try {
                                    exportAppData(ownerView)
                                } finally {
                                    if (fragment.view === ownerView) {
                                        isEnabled = true
                                        importer?.isEnabled = true
                                    }
                                }
                            }
                        }
                    }
                    is BiometricAuthenticator.Result.Failure -> {
                        isEnabled = true
                        importer?.isEnabled = true
                        showFailure()
                    }
                    is BiometricAuthenticator.Result.Cancelled -> {
                        isEnabled = true
                        importer?.isEnabled = true
                    }
                }
            }
        } catch (failure: RuntimeException) {
            if (authenticationRequest === request) authenticationRequest = null
            if (fragment.view === ownerView) {
                isEnabled = true
                importer?.isEnabled = true
            }
            throw failure
        }
    }

    // ConfigStore may throw checked Exception; cancellation and unexpected runtime defects must propagate.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun exportAppData(ownerView: View) {
        try {
            if (AdminKnobs.disableConfigExport) return
            val archive = Application.getTunnelManager().exportAppData()
            if (AdminKnobs.disableConfigExport) return
            val filePath = downloadsFileSaver.save(
                "vpn0wn-transfer-${System.currentTimeMillis()}.zip",
                "application/zip",
                overwriteExisting = false,
            ) { output -> writeArchive(archive, output) }
            currentCoroutineContext().ensureActive()
            if (filePath != null) {
                exportedFilePath = filePath
                notifyChanged()
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: IOException) {
            showFailureIfCurrent(ownerView)
        } catch (_: AppDataTransfer.InvalidArchiveException) {
            showFailureIfCurrent(ownerView)
        } catch (_: VpsServerStore.InvalidTransferMetadataException) {
            showFailureIfCurrent(ownerView)
        } catch (_: SecurityException) {
            showFailureIfCurrent(ownerView)
        } catch (failure: RuntimeException) {
            throw failure
        } catch (_: Exception) {
            showFailureIfCurrent(ownerView)
        }
    }

    private fun writeArchive(archive: AppDataTransfer.Archive, output: OutputStream) {
        if (AdminKnobs.disableConfigExport) throw SecurityException("Config export prohibited")
        AppDataTransfer.write(archive, output)
    }

    private suspend fun showFailureIfCurrent(ownerView: View) {
        currentCoroutineContext().ensureActive()
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_content) ?: return
        if (fragment.view === ownerView && fragment.viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            showFailure()
        }
    }

    private fun showFailure() {
        Log.e(TAG, "App data export failed")
        Snackbar.make(
            activity.findViewById(android.R.id.content),
            R.string.app_data_transfer_error,
            Snackbar.LENGTH_LONG,
        ).vpn0wnStyle().show()
    }

    companion object {
        private const val TAG = "vpn0wn/AppDataExporter"
    }
}
