/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Modified for vpn0wn.
 */

package com.vpn0wn.android.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.fragment.app.FragmentManager
import com.vpn0wn.android.Application
import com.vpn0wn.android.R
import com.vpn0wn.android.fragment.ConfigNamingDialogFragment
import com.vpn0wn.android.model.ObservableTunnel
import com.vpn0wn.config.BadConfigException
import com.vpn0wn.config.Config
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object TunnelImporter {
    // Awaited creations use ConfigStore's generic Exception contract; cancellation and Errors escape.
    @Suppress("TooGenericExceptionCaught")
    suspend fun importTunnel(contentResolver: ContentResolver, uri: Uri, messageCallback: (CharSequence) -> Unit) = withContext(Dispatchers.IO) {
        val context = Application.get().applicationContext
        val profiles = ArrayList<Pair<String, Config>>()
        val throwables = ArrayList<Throwable>()
        var appDataTransfer = false
        try {
            var name = ""
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0)
            }
            if (name.isEmpty()) name = Uri.decode(uri.lastPathSegment).orEmpty()
            name = name.substringAfterLast('/')
            require(name.isNotEmpty()) { context.getString(R.string.illegal_filename_error, name) }
            val isZip = name.lowercase().endsWith(".zip")
            if (name.lowercase().endsWith(".conf")) {
                name = name.dropLast(".conf".length)
            } else {
                require(isZip) { context.getString(R.string.bad_extension_error) }
            }

            val input = contentResolver.openInputStream(uri) ?: throw IOException("Unable to open tunnel input")
            val batch = TunnelImportInput.read(input, name, isZip, coroutineContext)
            profiles.addAll(batch.profiles)
            throwables.addAll(batch.failures)
            appDataTransfer = batch.appDataTransfer
            require(appDataTransfer || profiles.isNotEmpty() || throwables.isNotEmpty()) { context.getString(R.string.no_configs_error) }
        } catch (e: IOException) {
            throwables.add(e)
        } catch (e: IllegalArgumentException) {
            throwables.add(e)
        } catch (e: SecurityException) {
            throwables.add(e)
        }

        if (appDataTransfer) {
            withContext(Dispatchers.Main.immediate) {
                messageCallback(context.getString(R.string.app_data_transfer_use_settings))
            }
            return@withContext
        }
        val tunnels = profiles.mapNotNull { (name, config) ->
            coroutineContext.ensureActive()
            try {
                Application.getTunnelManager().create(name, config)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throwables.add(e)
                null
            }
        }
        withContext(Dispatchers.Main.immediate) { onTunnelImportFinished(tunnels, throwables, messageCallback) }
    }

    fun importTunnel(parentFragmentManager: FragmentManager, configText: String, messageCallback: (CharSequence) -> Unit) {
        try {
            TunnelImportInput.parseText(configText)
        } catch (e: BadConfigException) {
            onTunnelImportFinished(emptyList(), listOf(e), messageCallback)
            return
        } catch (e: IOException) {
            onTunnelImportFinished(emptyList(), listOf(e), messageCallback)
            return
        }
        try {
            ConfigNamingDialogFragment.newInstance(configText).show(parentFragmentManager, null)
        } catch (e: IllegalStateException) {
            onTunnelImportFinished(emptyList(), listOf(e), messageCallback)
        }
    }

    private fun onTunnelImportFinished(tunnels: List<ObservableTunnel>, throwables: Collection<Throwable>, messageCallback: (CharSequence) -> Unit) {
        val context = Application.get().applicationContext
        var message = ""
        for (throwable in throwables) {
            val error = ErrorMessages[throwable]
            message = context.getString(R.string.import_error, error)
            Log.e(TAG, "Tunnel import failed: ${throwable.javaClass.simpleName}")
        }
        if (tunnels.size == 1 && throwables.isEmpty())
            message = context.getString(R.string.import_success, tunnels[0].name)
        else if (tunnels.isEmpty() && throwables.size == 1)
        else if (throwables.isEmpty())
            message = context.resources.getQuantityString(
                R.plurals.import_total_success,
                tunnels.size, tunnels.size
            )
        else if (!throwables.isEmpty())
            message = context.resources.getQuantityString(
                R.plurals.import_partial_success,
                tunnels.size + throwables.size,
                tunnels.size, tunnels.size + throwables.size
            )

        messageCallback(message)
    }

    private const val TAG = "vpn0wn/TunnelImporter"
}
