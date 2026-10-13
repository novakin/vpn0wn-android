/*
 * Copyright © 2026 vpn0wn contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.util

import com.vpn0wn.config.BadConfigException
import com.vpn0wn.config.Config
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.ensureActive

internal object TunnelImportInput {
    const val MAX_PROFILE_BYTES = 1_048_576
    const val MAX_SOURCE_BYTES = 16_777_216
    const val MAX_EXPANDED_BYTES = 16_777_216
    const val MAX_PROFILES = 256
    const val MAX_ZIP_ENTRIES = 512
    private const val COPY_BUFFER_BYTES = 8192

    class LimitExceededException : IOException("Tunnel import exceeds supported limits")

    data class Batch(
        val profiles: List<Pair<String, Config>>,
        val failures: List<Throwable>,
        val appDataTransfer: Boolean,
    )

    fun read(input: InputStream, name: String, isZip: Boolean, context: CoroutineContext): Batch {
        val profiles = ArrayList<Pair<String, Config>>()
        val failures = ArrayList<Throwable>()
        var appDataTransfer = false
        try {
            val source = LimitedInputStream(input, if (isZip) MAX_SOURCE_BYTES else MAX_PROFILE_BYTES, context)
            if (isZip) {
                ZipInputStream(source).use { zip -> appDataTransfer = readZip(zip, profiles, failures, context) }
            } else {
                source.use {
                    val output = ByteArrayOutputStream()
                    copyEntry(it, output, MAX_PROFILE_BYTES, context)
                    profiles.add(name to parseProfile(output.toByteArray(), context))
                }
            }
        } catch (e: IOException) {
            failures.add(e)
        } catch (e: BadConfigException) {
            failures.add(e)
        } catch (e: IllegalArgumentException) {
            failures.add(e)
        } catch (e: SecurityException) {
            failures.add(e)
        }
        return Batch(profiles, failures, appDataTransfer)
    }

    fun parseText(text: String): Config {
        if (text.length > MAX_PROFILE_BYTES) throw LimitExceededException()
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_PROFILE_BYTES) throw LimitExceededException()
        return parseProfile(bytes, EmptyCoroutineContext)
    }

    private fun readZip(
        zip: ZipInputStream,
        profiles: MutableList<Pair<String, Config>>,
        failures: MutableList<Throwable>,
        context: CoroutineContext,
    ): Boolean {
        var expanded = 0
        var entries = 0
        var attemptedProfiles = 0
        var entry = zip.nextEntry
        while (entry != null) {
            context.ensureActive()
            if (++entries > MAX_ZIP_ENTRIES) throw LimitExceededException()
            if (entry.name == AppDataTransfer.MANIFEST) return true
            val entryName = entry.name.substringAfterLast('/')
            val isProfile = !entry.isDirectory && entryName.lowercase().endsWith(".conf")
            if (isProfile && ++attemptedProfiles > MAX_PROFILES) throw LimitExceededException()
            val output = if (isProfile) ByteArrayOutputStream() else null
            val limit = minOf(if (isProfile) MAX_PROFILE_BYTES else MAX_EXPANDED_BYTES, MAX_EXPANDED_BYTES - expanded)
            expanded += copyEntry(zip, output, limit, context)
            if (output != null) {
                try {
                    profiles.add(entryName.dropLast(".conf".length) to parseProfile(output.toByteArray(), context))
                } catch (e: BadConfigException) {
                    failures.add(e)
                } catch (e: IOException) {
                    failures.add(e)
                }
            }
            entry = zip.nextEntry
        }
        return false
    }

    private fun copyEntry(input: InputStream, output: ByteArrayOutputStream?, limit: Int, context: CoroutineContext): Int {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var size = 0
        var count = input.read(buffer, 0, minOf(buffer.size, limit + 1))
        while (count != -1) {
            context.ensureActive()
            if (count == 0) throw IOException("Unable to read tunnel input")
            if (count > limit - size) throw LimitExceededException()
            output?.write(buffer, 0, count)
            size += count
            count = input.read(buffer, 0, minOf(buffer.size, limit - size + 1))
        }
        return size
    }

    private fun parseProfile(bytes: ByteArray, context: CoroutineContext): Config =
        ProfileReader(bytes.inputStream(), context).use { Config.parse(it) }

    private class LimitedInputStream(
        private val input: InputStream,
        private val limit: Int,
        private val context: CoroutineContext,
    ) : InputStream() {
        private var size = 0

        override fun read(): Int {
            context.ensureActive()
            val value = input.read()
            if (value != -1 && ++size > limit) throw LimitExceededException()
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            context.ensureActive()
            val count = input.read(buffer, offset, minOf(length, limit - size + 1))
            if (count == 0 && length > 0) throw IOException("Unable to read tunnel input")
            if (count > limit - size) throw LimitExceededException()
            if (count != -1) size += count
            return count
        }

        override fun close() = input.close()
    }

    private class ProfileReader(input: InputStream, private val context: CoroutineContext) :
        BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)) {
        override fun readLine(): String? {
            context.ensureActive()
            return super.readLine()
        }
    }
}
