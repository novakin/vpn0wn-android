/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import android.app.Application
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class LogExportTest {
    private val provider = LogViewerActivity.ExportedLogContentProvider()

    @Test
    fun successfulExportWritesUtf8AndClosesOutput() {
        val output = ExportOutput()
        val bytes = "synthetic UTF-8 é\n".toByteArray(Charsets.UTF_8)
        provider.writeLog(output, bytes)
        assertArrayEquals(bytes, output.toByteArray())
        assertTrue(output.closed)
    }

    @Test
    fun brokenPipeClosesOutputAndLogsOnlyFixedDiagnostic() {
        val output = ExportOutput(writeFailure = IOException("synthetic-private-value"))
        ShadowLog.clear()
        provider.writeLog(output, byteArrayOf(1))
        assertTrue(output.closed)
        assertTrue(ShadowLog.getLogs().any { it.msg.contains("receiver may have closed it") })
        assertFalse(ShadowLog.getLogs().any { it.msg.contains("synthetic-private-value") || it.throwable != null })
    }

    @Test
    fun closeIoFailureGetsDiagnostic() {
        val output = ExportOutput(closeFailure = IOException("synthetic close failure"))
        ShadowLog.clear()
        provider.writeLog(output, byteArrayOf(1))
        assertTrue(output.closed)
        assertTrue(ShadowLog.getLogs().any { it.msg.contains("pipe write failed") })
    }

    @Test
    fun cancellationAndProgrammingFailuresPropagateWithCleanupSuppressed() {
        for (failure in listOf(CancellationException("synthetic cancellation"), IllegalStateException("synthetic defect"))) {
            val closeFailure = IOException("synthetic close failure")
            val output = ExportOutput(writeFailure = failure, closeFailure = closeFailure)
            val result = runCatching { provider.writeLog(output, byteArrayOf(1)) }
            assertSame(failure, result.exceptionOrNull())
            assertTrue(output.closed)
            assertArrayEquals(arrayOf(closeFailure), failure.suppressed)
        }
    }

    private class ExportOutput(private val writeFailure: Exception? = null, private val closeFailure: IOException? = null) : ByteArrayOutputStream() {
        var closed = false
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            writeFailure?.let { throw it }
            super.write(bytes, offset, length)
        }
        override fun close() {
            closed = true
            closeFailure?.let { throw it }
            super.close()
        }
    }
}
