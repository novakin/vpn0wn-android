/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class LogcatStreamTest {
    @Test
    fun destroyInducedReadFailureCannotReplaceCallerCancellation() = runBlocking {
        val process = ControlledProcess(destroyReadFailure = IOException("synthetic destroy failure"))
        supervisorScope {
            val job = async(Dispatchers.IO) { LogcatStream.read(process) { error("no input") } }
            assertTrue(process.stdout.started.await(2, TimeUnit.SECONDS))
            withTimeout(2_000) { job.cancelAndJoin() }
            val result = runCatching { job.await() }
            assertTrue(result.exceptionOrNull() is CancellationException)
        }
        process.assertClosed()
    }

    @Test
    fun destroyInducedReadFailureCannotReplaceDeliveryDefect() = runBlocking {
        val process = ControlledProcess(destroyReadFailure = IOException("synthetic destroy failure"))
        process.stdout.line("synthetic sparse row")
        val failure = IllegalStateException("synthetic delivery defect")
        val result = runCatching { withTimeout(3_000) { LogcatStream.read(process) { throw failure } } }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertSame(failure, generateSequence(result.exceptionOrNull()) { it.cause }.last())
        process.assertClosed()
    }

    @Test
    fun nativeProcessWaitingForOutputTerminatesOnCancellation() = runBlocking {
        assumeTrue(File("/bin/sh").canExecute())
        val process = ProcessBuilder("/bin/sh", "-c", "printf 'synthetic native row\\n'; exec sleep 30").start()
        val delivered = CompletableDeferred<List<String>>()
        val job = async(Dispatchers.IO) { LogcatStream.read(process) { delivered.complete(it) } }
        try {
            assertEquals(listOf("synthetic native row"), withTimeout(3_000) { delivered.await() })
            withTimeout(3_000) { job.cancelAndJoin() }
            assertTrue(process.waitFor(2, TimeUnit.SECONDS))
            assertFalse(process.isAlive)
        } finally {
            process.destroyForcibly()
            job.cancelAndJoin()
        }
    }

    @Test
    fun continuousRowsCannotMoveFirstRowDeadline() = runBlocking {
        val process = ControlledProcess()
        val delivered = CompletableDeferred<List<String>>()
        val job = async(Dispatchers.IO) { LogcatStream.read(process) { delivered.complete(it) } }
        val producer = async(Dispatchers.IO) {
            while (true) {
                process.stdout.line("synthetic continuous row")
                delay(50)
            }
        }
        try {
            assertTrue(withTimeout(2_000) { delivered.await() }.isNotEmpty())
            assertTrue(producer.isActive)
        } finally {
            producer.cancelAndJoin()
            withTimeout(2_000) { job.cancelAndJoin() }
        }
        process.assertClosed()
    }

    @Test
    fun sparseRowFlushesWhileReaderStillWaitsForNextLine() = runBlocking {
        val process = ControlledProcess()
        val batches = Channel<List<String>>(Channel.UNLIMITED)
        val job = async(Dispatchers.IO) { LogcatStream.read(process) { batches.send(it) } }
        try {
            process.stdout.line("synthetic isolated row")
            assertEquals(listOf("synthetic isolated row"), withTimeout(2_000) { batches.receive() })
            assertFalse(process.destroyed)
            process.stdout.line("continuation only")
            assertEquals(listOf("continuation only"), withTimeout(2_000) { batches.receive() })
        } finally {
            withTimeout(2_000) { job.cancelAndJoin() }
        }
        process.assertClosed()
    }

    @Test
    fun eofFlushesRemainderAndLargeBurstRespectsBatchLimit() = runBlocking {
        val process = ControlledProcess()
        val expected = List(5_000) { "synthetic $it" }
        expected.forEach(process.stdout::line)
        process.stdout.finish()
        val batches = ArrayList<List<String>>()
        withTimeout(5_000) { LogcatStream.read(process) { batches.add(it) } }
        assertEquals(expected, batches.flatten())
        assertTrue(batches.all { it.size <= 4_000 })
        process.assertClosed()
    }

    @Test
    fun cancellationDestroysProcessBeforeJoiningBlockedReader() = runBlocking {
        val process = ControlledProcess()
        val job = async(Dispatchers.IO) { LogcatStream.read(process) { error("no input") } }
        assertTrue(process.stdout.started.await(2, TimeUnit.SECONDS))
        withTimeout(2_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        process.assertClosed()
    }

    @Test
    fun cancellationSettlesReaderWithFullChannelAndSuspendedDelivery() = runBlocking {
        val process = ControlledProcess()
        repeat(10_000) { process.stdout.line("synthetic $it") }
        val delivering = CompletableDeferred<Unit>()
        val job = async(Dispatchers.IO) {
            LogcatStream.read(process) {
                delivering.complete(Unit)
                delay(Long.MAX_VALUE)
            }
        }
        withTimeout(5_000) { delivering.await() }
        withTimeout(2_000) { job.cancelAndJoin() }
        process.assertClosed()
    }

    @Test
    fun readerIoFailureIsNotNormalEof() = runBlocking {
        val process = ControlledProcess()
        val failure = IOException("synthetic read failure")
        process.stdout.failure = failure
        process.stdout.fail()
        supervisorScope {
            val job = async(Dispatchers.IO) { LogcatStream.read(process) { error("no complete batch") } }
            val result = runCatching { withTimeout(2_000) { job.await() } }
            assertTrue(result.exceptionOrNull() is IOException)
            assertSame(failure, generateSequence(result.exceptionOrNull()) { it.cause }.last())
        }
        process.assertClosed()
    }

    @Test
    fun programmingFailureInDeliveryPropagatesAndClosesProcess() = runBlocking {
        val process = ControlledProcess()
        process.stdout.line("synthetic")
        process.stdout.finish()
        val failure = IllegalStateException("synthetic defect")
        val result = runCatching { LogcatStream.read(process) { throw failure } }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertSame(failure, generateSequence(result.exceptionOrNull()) { it.cause }.last())
        process.assertClosed()
    }

    private class ControlledProcess(private val destroyReadFailure: IOException? = null) : Process() {
        val stdout = ControlledInput()
        private var errorClosed = false
        private var inputClosed = false
        @Volatile var destroyed = false
        private val stderr = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() { errorClosed = true; super.close() }
        }
        private val stdin = object : ByteArrayOutputStream() {
            override fun close() { inputClosed = true; super.close() }
        }
        override fun getInputStream() = stdout
        override fun getErrorStream() = stderr
        override fun getOutputStream() = stdin
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun destroy() {
            destroyed = true
            if (destroyReadFailure != null) {
                stdout.failure = destroyReadFailure
                stdout.fail()
            } else stdout.finish()
        }
        fun assertClosed() {
            assertTrue(destroyed)
            assertTrue(stdout.closed)
            assertTrue(errorClosed)
            assertTrue(inputClosed)
        }
    }

    private class ControlledInput : InputStream() {
        private val bytes = LinkedBlockingQueue<Int>()
        val started = CountDownLatch(1)
        @Volatile var closed = false
        var failure = IOException("synthetic")
        fun line(value: String) { (value + "\n").toByteArray(Charsets.UTF_8).forEach { bytes.add(it.toInt() and 0xff) } }
        fun finish() { bytes.offer(-1) }
        fun fail() { bytes.offer(-2) }
        override fun read(): Int {
            started.countDown()
            val value = bytes.take()
            if (value == -2) throw failure
            return value
        }
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val first = read()
            if (first < 0) return -1
            target[offset] = first.toByte()
            var count = 1
            while (count < length && (bytes.peek() ?: -1) >= 0) {
                target[offset + count] = bytes.remove().toByte()
                count++
            }
            return count
        }
        override fun close() { closed = true; finish() }
    }
}
