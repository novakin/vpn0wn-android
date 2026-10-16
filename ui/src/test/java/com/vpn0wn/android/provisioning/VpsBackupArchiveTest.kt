/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VpsBackupArchiveTest {
    private val fixture = requireNotNull(javaClass.getResourceAsStream("/vps-backup-archive-v1.json"))
        .bufferedReader().use { JSONObject(it.readText()) }
    private val archive get() = hex(fixture.getString("archive"))
    private val password get() = fixture.getJSONArray("vectors").getJSONObject(0).getString("password").toCharArray()
    private val key get() = hex(fixture.getJSONArray("vectors").getJSONObject(0).getString("key"))
    private val snapshot get() = ProvisioningJson.parseBackup(fixture.getString("plaintext"))

    @Test fun independentlyEncryptedArchiveRetainsExactSnapshotAndCallerPassword() = runTest {
        val supplied = password
        val original = supplied.copyOf()
        assertEquals(snapshot, VpsBackupArchive().decrypt(archive, supplied))
        assertArrayEquals(original, supplied)
        assertEquals("VpsBackupResult(redacted)", snapshot.toString())
        assertEquals("VpsRevokedBackup(redacted)", snapshot.revoked.single().toString())
    }

    @Test fun encryptionMatchesIndependentUnicodeAndMaximumPasswordKeys() = runTest {
        val vectors = fixture.getJSONArray("vectors")
        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            val supplied = vector.getString("password").toCharArray()
            val expectedKey = hex(vector.getString("key"))
            try {
                assertArrayEquals(hex(vector.getString("utf8")), String(supplied).toByteArray(Charsets.UTF_8))
                val encrypted = VpsBackupArchive(SequenceRandom()).encrypt(snapshot, supplied)
                assertArrayEquals(archive.copyOfRange(SALT_OFFSET, LENGTH_OFFSET), encrypted.copyOfRange(SALT_OFFSET, LENGTH_OFFSET))
                val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(expectedKey, "AES"), GCMParameterSpec(TAG_BITS,
                        encrypted.copyOfRange(NONCE_OFFSET, LENGTH_OFFSET)))
                    updateAAD(encrypted, 0, VpsBackupArchive.HEADER_BYTES)
                }
                val plaintext = cipher.doFinal(encrypted, VpsBackupArchive.HEADER_BYTES, encrypted.size - VpsBackupArchive.HEADER_BYTES)
                try { assertEquals(snapshot, ProvisioningJson.parseBackup(String(plaintext, Charsets.UTF_8))) }
                finally { plaintext.fill(0) }
            } finally {
                supplied.fill('\u0000')
                expectedKey.fill(0)
            }
        }
    }

    @Test fun ordinaryEncryptionUsesFreshSaltAndNonce() = runTest {
        val codec = VpsBackupArchive()
        val first = codec.encrypt(snapshot, password)
        val second = codec.encrypt(snapshot, password)
        assertFalse(first.copyOfRange(SALT_OFFSET, NONCE_OFFSET).contentEquals(second.copyOfRange(SALT_OFFSET, NONCE_OFFSET)))
        assertFalse(first.copyOfRange(NONCE_OFFSET, LENGTH_OFFSET).contentEquals(second.copyOfRange(NONCE_OFFSET, LENGTH_OFFSET)))
        assertEquals(snapshot, codec.decrypt(first, password))
        assertEquals(snapshot, codec.decrypt(second, password))
    }

    @Test fun rejectsUnknownCostsVersionsLengthOverflowTruncationAndTrailingData() = runTest {
        for (offset in (0 until SALT_OFFSET) + (LENGTH_OFFSET until VpsBackupArchive.HEADER_BYTES)) {
            val changed = archive.also { it[offset] = (it[offset].toInt() xor BYTE_MASK).toByte() }
            expect(VpsBackupArchiveException.Category.FORMAT) { VpsBackupArchive().decrypt(changed, password) }
        }
        for (changed in listOf(archive.copyOf(archive.size - 1), archive + byteArrayOf(0), byteArrayOf(),
            ByteArray(VpsBackupArchive.MAX_ARCHIVE_BYTES + 1))) {
            expect(VpsBackupArchiveException.Category.FORMAT) { VpsBackupArchive().decrypt(changed, password) }
        }
    }

    @Test fun authenticatesSaltNonceCiphertextAndTagWithOneSafePasswordFailure() = runTest {
        for (offset in listOf(SALT_OFFSET, NONCE_OFFSET, VpsBackupArchive.HEADER_BYTES, archive.lastIndex)) {
            val changed = archive.also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            expect(VpsBackupArchiveException.Category.AUTHENTICATION) { VpsBackupArchive().decrypt(changed, password) }
        }
        expect(VpsBackupArchiveException.Category.AUTHENTICATION) {
            VpsBackupArchive().decrypt(archive, "wrong backup password".toCharArray())
        }
        expect(VpsBackupArchiveException.Category.AUTHENTICATION) { VpsBackupArchive().decrypt(archive, charArrayOf('x')) }
    }

    @Test fun rejectsMalformedOrOutOfPolicyPasswordsWithoutRandomness() = runTest {
        val invalid = listOf(charArrayOf(), "short password".toCharArray(), CharArray(MAX_CODE_POINTS + 1) { 'a' },
            CharArray(MAX_CODE_UNITS + 1) { 'a' }, ("long enough password" + '\ud800').toCharArray(),
            ("long enough password" + '\udc00').toCharArray())
        for (supplied in invalid) expect(VpsBackupArchiveException.Category.PASSWORD) {
            VpsBackupArchive(RejectingRandom()).encrypt(snapshot, supplied)
        }
    }

    @Test fun rejectsInvalidSnapshotsAndEscapingOverflowBeforeEncryption() = runTest {
        val invalid = listOf(snapshot.copy(snapshotVersion = 2), snapshot.copy(revokedCount = 0),
            snapshot.copy(config = "not a managed snapshot"), snapshot.copy(config = snapshot.config + '\u0000'),
            snapshot.copy(config = snapshot.config + '\ud800'), snapshot.copy(revoked = snapshot.revoked + snapshot.revoked),
            snapshot.copy(config = "# TUNNL_MANAGED 1\n" + "a".repeat(VpsBackupResult.MAX_CONFIG_BYTES)),
            snapshot.copy(config = "# TUNNL_MANAGED 1\n" + "\u0001".repeat(ESCAPING_OVERFLOW_CHARACTERS)))
        for (changed in invalid) expect(VpsBackupArchiveException.Category.SNAPSHOT) {
            VpsBackupArchive(RejectingRandom()).encrypt(changed, password)
        }
    }

    @Test fun acceptsExactMaximumAuthenticatedPlaintextAndRejectsAuthenticatedInvalidData() = runTest {
        val json = fixture.getString("plaintext").toByteArray(Charsets.UTF_8)
        val maximum = json + ByteArray(VpsBackupResult.MAX_RESPONSE_BYTES - json.size) { ' '.code.toByte() }
        val encrypted = authenticated(maximum)
        assertEquals(VpsBackupArchive.MAX_ARCHIVE_BYTES, encrypted.size)
        assertEquals(snapshot, VpsBackupArchive().decrypt(encrypted, password))
        for (plaintext in listOf(hex("c328"), "{}".toByteArray(),
            fixture.getString("plaintext").replace("synthetic-private-key", "synthetic-private-key\\ud800").toByteArray(Charsets.UTF_8),
            fixture.getString("plaintext").replace("\"snapshotVersion\":1", "\"snapshotVersion\":2").toByteArray(Charsets.UTF_8))) {
            expect(VpsBackupArchiveException.Category.SNAPSHOT) { VpsBackupArchive().decrypt(authenticated(plaintext), password) }
        }
    }

    @Test fun cancellationDrainsAdmittedWorkerWithoutReturningAnArchive() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val random = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                entered.countDown()
                check(release.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Archive fixture worker timed out" }
                bytes.fill(0)
            }
        }
        val supplied = password
        val original = supplied.copyOf()
        val pending = async { VpsBackupArchive(random).encrypt(snapshot, supplied) }
        try {
            runCurrent()
            assertTrue(withContext(Dispatchers.IO) { entered.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS) })
            pending.cancel()
            release.countDown()
            try { pending.await(); fail("Cancelled archive returned") } catch (_: CancellationException) { assertTrue(pending.isCancelled) }
        } finally {
            release.countDown()
            withContext(NonCancellable) { pending.cancelAndJoin() }
        }
        assertArrayEquals(original, supplied)
    }

    private fun authenticated(plaintext: ByteArray): ByteArray {
        val header = archive.copyOfRange(0, VpsBackupArchive.HEADER_BYTES)
        ByteBuffer.wrap(header).putInt(LENGTH_OFFSET, plaintext.size + TAG_BYTES)
        val secret = key
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(TAG_BITS,
                    header.copyOfRange(NONCE_OFFSET, LENGTH_OFFSET)))
                updateAAD(header)
            }
            return header + cipher.doFinal(plaintext)
        } finally { secret.fill(0) }
    }

    private suspend fun expect(category: VpsBackupArchiveException.Category, block: suspend () -> Unit) {
        try { block(); fail("Expected archive failure") } catch (failure: VpsBackupArchiveException) {
            assertEquals(category, failure.category)
            assertEquals(category.safeMessage, failure.message)
            assertNull(failure.cause)
        }
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(HEX_RADIX).toByte() }.toByteArray()

    private class SequenceRandom : SecureRandom() {
        private var next = 0
        override fun nextBytes(bytes: ByteArray) {
            bytes.indices.forEach { bytes[it] = (next++).toByte() }
        }
    }

    private class RejectingRandom : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) = error("Invalid archive input reached randomness")
    }

    companion object {
        private const val SALT_OFFSET = 24
        private const val NONCE_OFFSET = 40
        private const val LENGTH_OFFSET = 52
        private const val TAG_BITS = 128
        private const val TAG_BYTES = 16
        private const val BYTE_MASK = 0xff
        private const val HEX_RADIX = 16
        private const val MAX_CODE_POINTS = 128
        private const val MAX_CODE_UNITS = 256
        private const val ESCAPING_OVERFLOW_CHARACTERS = 140_000
        private const val WORKER_TIMEOUT_SECONDS = 5L
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
