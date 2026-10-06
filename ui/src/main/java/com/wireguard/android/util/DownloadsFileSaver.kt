/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.wireguard.android.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

class DownloadsFileSaver(private val context: ComponentActivity) {
    private lateinit var activityResult: ActivityResultLauncher<String>
    private var futureGrant: CompletableDeferred<Boolean>? = null

    init {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            activityResult = context.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                futureGrant?.complete(granted)
                futureGrant = null
            }
        }
    }

    // Acquisition and write cleanup must preserve the primary failure, including cancellation or Error.
    @Suppress("TooGenericExceptionCaught")
    suspend fun save(name: String, mimeType: String?, overwriteExisting: Boolean, write: (OutputStream) -> Unit): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !requestPermission())
            return null
        return withContext(Dispatchers.IO) {
            val outputFile = createDownload(name, mimeType, overwriteExisting)
            try {
                val fileName = outputFile.openOutputStream().use { stream ->
                    val fileName = outputFile.fileName
                    ensureActive()
                    write(stream)
                    fileName
                }
                ensureActive()
                fileName
            } catch (failure: Throwable) {
                try {
                    outputFile.delete()
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }
    }

    // A rejected launch must release the cached waiter before any failure propagates.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun requestPermission() = withContext(Dispatchers.Main.immediate) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
            return@withContext true
        val grant = futureGrant ?: CompletableDeferred<Boolean>().also {
            futureGrant = it
            try {
                activityResult.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } catch (failure: Throwable) {
                futureGrant = null
                throw failure
            }
        }
        grant.await()
    }

    private fun createDownload(name: String, mimeType: String?, overwriteExisting: Boolean): DownloadsFile {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentResolver = context.contentResolver
            if (overwriteExisting)
                contentResolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "${MediaColumns.DISPLAY_NAME} = ?", arrayOf(name))
            val contentValues = ContentValues()
            contentValues.put(MediaColumns.DISPLAY_NAME, name)
            contentValues.put(MediaColumns.MIME_TYPE, mimeType)
            val contentUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IOException(context.getString(R.string.create_downloads_file_error))
            return DownloadsFile(context, "Download/", contentUri)
        }
        @Suppress("DEPRECATION") val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!path.isDirectory && !path.mkdirs())
            throw IOException(context.getString(R.string.create_output_dir_error))
        return DownloadsFile(context, File(path, name).absolutePath, null)
    }

    private class DownloadsFile(private val context: Context, private val path: String, private val uri: Uri?) {
        private var ownsOutput = uri != null
        val fileName: String
            get() {
                val contentUri = uri ?: return path
                @Suppress("DEPRECATION") val storedPath = queryColumn(contentUri, MediaColumns.DATA)
                return storedPath ?: path + (queryColumn(contentUri, MediaColumns.DISPLAY_NAME) ?: "")
            }

        private fun queryColumn(contentUri: Uri, column: String): String? =
            context.contentResolver.query(contentUri, arrayOf(column), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }

        fun openOutputStream(): OutputStream =
            if (uri != null)
                context.contentResolver.openOutputStream(uri)
                    ?: throw IOException(context.getString(R.string.create_downloads_file_error))
            else
                FileOutputStream(path).also { ownsOutput = true }

        fun delete() {
            if (!ownsOutput)
                return
            val removed = if (uri != null)
                context.contentResolver.delete(uri, null, null) > 0
            else
                File(path).delete()
            if (!removed)
                throw IOException("Incomplete download was not removed")
        }
    }
}
