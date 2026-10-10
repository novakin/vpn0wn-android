/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.preference

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import androidx.preference.Preference
import com.google.android.material.snackbar.Snackbar
import com.vpn0wn.android.Application
import com.vpn0wn.android.R
import com.vpn0wn.android.util.AdminKnobs
import com.vpn0wn.android.util.BiometricAuthenticator
import com.vpn0wn.android.util.DownloadsFileSaver
import com.vpn0wn.android.util.ErrorMessages
import com.vpn0wn.android.util.activity
import com.vpn0wn.android.util.lifecycleScope
import com.vpn0wn.android.util.tunnlStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
    private val downloadsFileSaver = DownloadsFileSaver(activity)

    // ConfigStore failures need user feedback while cancellation and unrelated Errors propagate.
    @Suppress("TooGenericExceptionCaught")
    private fun exportZip() {
        lifecycleScope.launch {
            try {
                val tunnels = Application.getTunnelManager().getTunnels().toList()
                exportedFilePath = withContext(Dispatchers.IO) {
                    val configs = supervisorScope {
                        tunnels.map { async { it.getConfigAsync() } }.awaitAll()
                    }
                    require(configs.isNotEmpty()) { context.getString(R.string.no_tunnels_error) }
                    downloadsFileSaver.save("wireguard-export.zip", "application/zip", true) { stream ->
                        ZipOutputStream(stream).use { zip ->
                            for (i in configs.indices) {
                                zip.putNextEntry(ZipEntry(tunnels[i].name + ".conf"))
                                zip.write(configs[i].toWgQuickString().toByteArray(StandardCharsets.UTF_8))
                            }
                            zip.closeEntry()
                        }
                    }
                }
                notifyChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = ErrorMessages[e]
                val message = context.getString(R.string.zip_export_error, error)
                Log.e(TAG, message, e)
                Snackbar.make(
                    activity.findViewById(android.R.id.content),
                    message, Snackbar.LENGTH_LONG
                ).tunnlStyle().show()
            } finally {
                isEnabled = true
            }
        }
    }

    override fun getSummary() =
        if (exportedFilePath == null) context.getString(R.string.zip_export_summary) else context.getString(R.string.zip_export_success, exportedFilePath)

    override fun getTitle() = context.getString(R.string.zip_export_title)

    override fun onClick() {
        if (AdminKnobs.disableConfigExport) return
        val fragment = activity.supportFragmentManager.fragments.first()
        BiometricAuthenticator.authenticate(R.string.biometric_prompt_zip_exporter_title, fragment) {
            when (it) {
                is BiometricAuthenticator.Result.Success, is BiometricAuthenticator.Result.NoAuthenticationConfigured -> {
                    isEnabled = false
                    exportZip()
                }

                is BiometricAuthenticator.Result.Failure -> {
                    Snackbar.make(
                        activity.findViewById(android.R.id.content),
                        it.message,
                        Snackbar.LENGTH_SHORT
                    ).tunnlStyle().show()
                }

                is BiometricAuthenticator.Result.Cancelled -> {}
            }
        }
    }

    companion object {
        private const val TAG = "WireGuard/ZipExporterPreference"
    }
}
