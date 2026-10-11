/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Modified for vpn0wn.
 */

package com.vpn0wn.android.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.charset.StandardCharsets
import java.io.IOException

internal object LogcatStream {
    private const val CHANNEL_CAPACITY = 64
    private const val MAX_BATCH_LINES = 4_000
    private const val FLUSH_DELAY_MS = 500L
    private const val NANOS_PER_MILLISECOND = 1_000_000L

    suspend fun read(process: Process, onBatch: suspend (List<String>) -> Unit) {
        try {
            process.outputStream.use {
                process.errorStream.use {
                    process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { stdout ->
                        coroutineScope {
                            val lines = Channel<String>(CHANNEL_CAPACITY)
                            val reader = launch(Dispatchers.IO) {
                                try {
                                    while (true) {
                                        val line = stdout.readLine() ?: break
                                        lines.send(line)
                                    }
                                } catch (e: IOException) {
                                    currentCoroutineContext().ensureActive()
                                    throw e
                                } finally {
                                    lines.close()
                                }
                            }
                            try {
                                deliverBatches(lines, onBatch)
                            } finally {
                                // Destroy before coroutineScope joins the child blocked in readLine().
                                reader.cancel()
                                process.destroy()
                                lines.cancel()
                            }
                        }
                    }
                }
            }
        } finally {
            process.destroy()
        }
    }

    private suspend fun deliverBatches(lines: Channel<String>, onBatch: suspend (List<String>) -> Unit) {
        val batch = ArrayList<String>()
        var deadline = 0L
        while (true) {
            val result = if (batch.isEmpty()) {
                lines.receiveCatching()
            } else {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0L) {
                    null
                } else {
                    val remainingMillis = (remainingNanos / NANOS_PER_MILLISECOND).coerceAtLeast(1L)
                    withTimeoutOrNull(remainingMillis) { lines.receiveCatching() }
                }
            }
            if (result == null || result.isClosed) {
                if (batch.isNotEmpty()) onBatch(batch.toList())
                batch.clear()
                if (result != null) break
            } else {
                if (batch.isEmpty()) deadline = System.nanoTime() + FLUSH_DELAY_MS * NANOS_PER_MILLISECOND
                batch.add(result.getOrThrow())
                if (batch.size >= MAX_BATCH_LINES || System.nanoTime() >= deadline) {
                    onBatch(batch.toList())
                    batch.clear()
                }
            }
        }
    }
}
