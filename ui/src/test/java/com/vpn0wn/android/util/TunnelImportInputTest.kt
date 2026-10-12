package com.vpn0wn.android.util

import com.vpn0wn.config.BadConfigException
import com.vpn0wn.config.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class TunnelImportInputTest {
    private val profile = "[Interface]\nPrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\n"

    @Test
    fun exactProfileBytesAndLongCommentLineRemainValid() {
        val text = paddedProfile(TunnelImportInput.MAX_PROFILE_BYTES)
        val input = TrackingInput(text.byteInputStream())
        val result = read(input)
        assertEquals(listOf("one"), result.profiles.map { it.first })
        assertTrue(result.failures.isEmpty())
        assertEquals(TunnelImportInput.MAX_PROFILE_BYTES, input.consumed)
        assertEquals(1, input.closes)
        assertEquals(result.profiles.single().second.toWgQuickString(), TunnelImportInput.parseText(text).toWgQuickString())
    }

    @Test
    fun oversizedAndEndlessSingleLinesStopAtProfileByteBudget() {
        for (delegate in listOf(
            (profile + "#" + "x".repeat(TunnelImportInput.MAX_PROFILE_BYTES)).byteInputStream(),
            EndlessInput(),
        )) {
            val input = TrackingInput(delegate)
            val result = read(input)
            assertTrue(result.profiles.isEmpty())
            assertLimit(result)
            assertEquals(TunnelImportInput.MAX_PROFILE_BYTES + 1, input.consumed)
            assertEquals(1, input.closes)
        }
    }

    @Test
    fun textBoundsCountUtf8BytesBeforeParsingAndKeepMultiPeerGrammar() {
        val utf8 = profile + "#" + "é".repeat(TunnelImportInput.MAX_PROFILE_BYTES / 2)
        val failure = assertThrows(TunnelImportInput.LimitExceededException::class.java) { TunnelImportInput.parseText(utf8) }
        assertNull(failure.cause)
        assertFalse(failure.message.orEmpty().contains("é"))
        val peers = "[Peer]\nPublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\nAllowedIPs = 192.0.2.0/24\n"
        val expected = Config.parse((profile + peers.repeat(2)).byteInputStream())
        assertEquals(expected.toWgQuickString(), TunnelImportInput.parseText(profile + peers.repeat(2)).toWgQuickString())
        val longAllowedIps = "[Peer]\nPublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\nAllowedIPs = " +
            List(2_000) { "192.0.2.0/24" }.joinToString(", ") + "\n"
        assertEquals(1, TunnelImportInput.parseText(profile + longAllowedIps).peers.size)
    }

    @Test
    fun expandedExactBoundaryProfilesImportAndOneExtraByteStops() {
        val body = paddedProfile(TunnelImportInput.MAX_PROFILE_BYTES)
        val entries = (1..16).map { "p$it.conf" to body }
        val exact = readZip(entries)
        assertEquals(16, exact.profiles.size)
        assertTrue(exact.failures.isEmpty())
        val exceeded = readZip(entries + ("notes.txt" to "x"))
        assertEquals(16, exceeded.profiles.size)
        assertLimit(exceeded)
    }

    @Test
    fun ignoredAndInvalidBodiesCountTowardExpandedBudget() {
        val ignored = "x".repeat(TunnelImportInput.MAX_PROFILE_BYTES)
        val exact = readZip((1..16).map { "notes$it.txt" to ignored })
        assertTrue(exact.failures.isEmpty())
        val invalidPrefix = "invalid\n#"
        val invalid = invalidPrefix + "x".repeat(TunnelImportInput.MAX_PROFILE_BYTES - invalidPrefix.length)
        val exceeded = readZip((1..16).map { "invalid$it.conf" to invalid } + ("valid.conf" to profile))
        assertTrue(exceeded.profiles.isEmpty())
        assertEquals(17, exceeded.failures.size)
        assertTrue(exceeded.failures.dropLast(1).all { it is BadConfigException })
        assertTrue(exceeded.failures.last() is TunnelImportInput.LimitExceededException)
        val oversizedIgnored = read(oversizedIgnoredZip().inputStream(), isZip = true)
        assertLimit(oversizedIgnored)
    }

    @Test
    fun unsupportedLongLineRemainsAParseFailureWithinByteBudget() {
        val input = TrackingInput(zip(listOf("invalid.conf" to "x".repeat(TunnelImportInput.MAX_PROFILE_BYTES))).inputStream())

        val result = read(input, isZip = true)

        assertTrue(result.profiles.isEmpty())
        assertTrue(result.failures.single() is BadConfigException)
        assertEquals(1, input.closes)
    }

    @Test
    fun oversizedProfilePreservesEarlierValidEntryAndClosesArchive() {
        val input = TrackingInput(zip(listOf("first.conf" to profile, "too-big.conf" to paddedProfile(TunnelImportInput.MAX_PROFILE_BYTES + 1))).inputStream())
        val result = read(input, isZip = true)
        assertEquals(listOf("first"), result.profiles.map { it.first })
        assertLimit(result)
        assertEquals(1, input.closes)
    }

    @Test
    fun profileAttemptsAndFailureRetentionHaveExactBoundaries() {
        val exact = readZip((1..TunnelImportInput.MAX_PROFILES).map { "p$it.conf" to profile })
        assertEquals(TunnelImportInput.MAX_PROFILES, exact.profiles.size)
        assertTrue(exact.failures.isEmpty())
        val exceeded = readZip((1..TunnelImportInput.MAX_PROFILES + 1).map { "p$it.conf" to profile })
        assertEquals(TunnelImportInput.MAX_PROFILES, exceeded.profiles.size)
        assertLimit(exceeded)
        val invalid = readZip((1..TunnelImportInput.MAX_PROFILES + 1).map { "p$it.conf" to "invalid" })
        assertEquals(TunnelImportInput.MAX_PROFILES + 1, invalid.failures.size)
        assertTrue(invalid.failures.last() is TunnelImportInput.LimitExceededException)
    }

    @Test
    fun zipEntryCountIncludesEmptyIgnoredFilesAndDirectories() {
        val entries = (1..TunnelImportInput.MAX_ZIP_ENTRIES).map { (if (it % 2 == 0) "directory$it/" else "note$it.txt") to "" }
        assertTrue(readZip(entries).failures.isEmpty())
        assertLimit(readZip(entries + ("extra.txt" to "")))
    }

    @Test
    fun compressedSourceHeadersCannotBypassSourceBudget() {
        val header = ByteArray(30).apply {
            this[0] = 0x50; this[1] = 0x4b; this[2] = 3; this[3] = 4
            this[4] = 20
            this[26] = 0xff.toByte(); this[27] = 0xff.toByte()
        }
        val record = header + ByteArray(65_535) { 'n'.code.toByte() }
        val entries = Collections.enumeration(List(TunnelImportInput.MAX_PROFILES + 1) { record.inputStream() })
        val input = TrackingInput(SequenceInputStream(entries))
        val result = read(input, isZip = true)
        assertLimit(result)
        assertEquals(TunnelImportInput.MAX_SOURCE_BYTES + 1, input.consumed)
        assertEquals(1, input.closes)
    }

    @Test
    fun cancellationDuringSingleProfileAndExpandedIgnoredEntryClosesOwnedInput() {
        for (isZip in listOf(false, true)) {
            val job = Job()
            val delegate = if (isZip) zip(listOf("notes.txt" to "x".repeat(1_048_576))).inputStream() else EndlessInput()
            lateinit var input: TrackingInput
            val cancelAfterSourceBytes = if (isZip) 128 else 8192
            input = TrackingInput(delegate) { if (input.consumed >= cancelAfterSourceBytes) job.cancel() }
            assertThrows(CancellationException::class.java) { TunnelImportInput.read(input, "one", isZip, job) }
            assertEquals(1, input.closes)
        }
    }

    @Test
    fun inputFailureAndProgrammingErrorBothCloseTheOwnedStream() {
        for (failure in listOf(IOException("read failure"), AssertionError("defect"))) {
            val input = TrackingInput(object : InputStream() { override fun read(): Int = throw failure })
            if (failure is IOException) {
                assertEquals(listOf(failure), read(input).failures)
            } else {
                assertEquals(failure, assertThrows(AssertionError::class.java) { read(input) })
            }
            assertEquals(1, input.closes)
        }
    }

    @Test
    fun zeroProgressInputFailsAndClosesInsteadOfSpinning() {
        for (isZip in listOf(false, true)) {
            val input = TrackingInput(object : InputStream() {
                override fun read() = 0
                override fun read(buffer: ByteArray, offset: Int, length: Int) = 0
            })
            val result = read(input, isZip)
            assertEquals("Unable to read tunnel input", result.failures.single().message)
            assertEquals(1, input.closes)
        }
    }

    @Test
    fun decodedLineEndingsRetainBufferedReaderGrammar() {
        for (ending in listOf("\n", "\r", "\r\n")) {
            assertEquals(Config.parse(profile.byteInputStream()).toWgQuickString(),
                TunnelImportInput.parseText(profile.replace("\n", ending) + ending).toWgQuickString())
        }
    }

    @Test
    fun rootTransferMarkerAfterBoundedProfilesStillRedirects() {
        val result = readZip(listOf("first.conf" to paddedProfile(TunnelImportInput.MAX_PROFILE_BYTES), AppDataTransfer.MANIFEST to "{}"))
        assertTrue(result.appDataTransfer)
        assertEquals(1, result.profiles.size)
        assertTrue(result.failures.isEmpty())
    }

    private fun read(input: InputStream, isZip: Boolean = false) =
        TunnelImportInput.read(input, "one", isZip, EmptyCoroutineContext)

    private fun readZip(entries: List<Pair<String, String>>) = read(zip(entries).inputStream(), isZip = true)

    private fun paddedProfile(bytes: Int): String = profile + "#" + "x".repeat(bytes - profile.toByteArray(StandardCharsets.UTF_8).size - 1)

    private fun assertLimit(result: TunnelImportInput.Batch) {
        assertEquals(1, result.failures.size)
        val failure = result.failures.single()
        assertTrue(failure is TunnelImportInput.LimitExceededException)
        assertEquals("Tunnel import exceeds supported limits", failure.message)
        assertNull(failure.cause)
    }

    private fun zip(entries: List<Pair<String, String>>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun oversizedIgnoredZip(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("notes.txt"))
            val buffer = ByteArray(8192) { 'x'.code.toByte() }
            repeat(TunnelImportInput.MAX_EXPANDED_BYTES / buffer.size) { zip.write(buffer) }
            zip.write(buffer, 0, TunnelImportInput.MAX_EXPANDED_BYTES % buffer.size + 1)
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private class EndlessInput : InputStream() {
        override fun read() = 'x'.code
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            buffer.fill('x'.code.toByte(), offset, offset + length)
            return length
        }
    }

    private class TrackingInput(private val input: InputStream, private val afterRead: () -> Unit = {}) : InputStream() {
        var consumed = 0
        var closes = 0
        override fun read(): Int = input.read().also {
            if (it != -1) consumed++
            afterRead()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = input.read(buffer, offset, length).also {
            if (it != -1) consumed += it
            afterRead()
        }
        override fun close() { closes++; input.close() }
    }
}
