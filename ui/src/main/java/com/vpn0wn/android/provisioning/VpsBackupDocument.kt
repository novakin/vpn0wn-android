/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class VpsBackupDocument(private val resolver: ContentResolver, private val uri: Uri) {
    private val signal = CancellationSignal()
    private val descriptor = AtomicReference<ParcelFileDescriptor?>()

    suspend fun read(): ByteArray = coroutineScope {
        val result = AtomicReference<ByteArray?>()
        val worker = async(Dispatchers.IO) { readOwned().also(result::set) }
        var delivered = false
        try {
            val bytes = worker.await()
            currentCoroutineContext().ensureActive()
            delivered = true
            bytes
        } catch (cancelled: CancellationException) {
            try { signal.cancel() } finally {
                try { descriptor.getAndSet(null)?.close() } catch (cleanup: IOException) { cancelled.addSuppressed(cleanup) }
            }
            throw cancelled
        } finally {
            withContext(NonCancellable) { worker.cancelAndJoin() }
            if (!delivered) result.getAndSet(null)?.fill(0) else result.set(null)
        }
    }

    private suspend fun readOwned(): ByteArray {
        currentCoroutineContext().ensureActive()
        val opened = resolver.openFileDescriptor(uri, "r", signal) ?: throw IOException("Backup input unavailable")
        descriptor.set(opened)
        var result: ByteArray? = null
        try {
            opened.use {
                currentCoroutineContext().ensureActive()
                ParcelFileDescriptor.AutoCloseInputStream(opened).use { stream ->
                    result = readBounded(stream)
                }
            }
            currentCoroutineContext().ensureActive()
            return requireNotNull(result).also { result = null }
        } finally {
            descriptor.compareAndSet(opened, null)
            result?.fill(0)
        }
    }

    private suspend fun readBounded(stream: InputStream): ByteArray {
        val bytes = ByteArray(VpsBackupArchive.MAX_ARCHIVE_BYTES + 1)
        try {
            var count = 0
            var endOfInput = false
            while (count < bytes.size && !endOfInput) {
                currentCoroutineContext().ensureActive()
                val read = stream.read(bytes, count, bytes.size - count)
                when {
                    read < 0 -> endOfInput = true
                    read > 0 -> count += read
                    else -> {
                        val next = stream.read()
                        if (next < 0) endOfInput = true else bytes[count++] = next.toByte()
                    }
                }
            }
            if (count > VpsBackupArchive.MAX_ARCHIVE_BYTES)
                throw VpsBackupArchiveException(VpsBackupArchiveException.Category.FORMAT)
            currentCoroutineContext().ensureActive()
            return bytes.copyOf(count)
        } finally { bytes.fill(0) }
    }
}
