/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.SecureRandom
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

internal class VpsBackupArchiveException(val category: Category) : IOException(category.safeMessage) {
    enum class Category(val safeMessage: String) {
        PASSWORD("Backup password is invalid"),
        FORMAT("Backup format is unsupported or invalid"),
        AUTHENTICATION("Backup password is incorrect or the file is damaged"),
        SNAPSHOT("Backup snapshot is invalid"),
        CRYPTO("Backup encryption is unavailable"),
    }
}

internal class VpsBackupArchive(private val random: SecureRandom = SecureRandom()) {
    fun validateNewPassword(password: CharArray) {
        if (password.size !in 1..MAX_PASSWORD_CHARS) fail(VpsBackupArchiveException.Category.PASSWORD)
        passwordBytes(password, creating = true).fill(0)
    }

    suspend fun encrypt(snapshot: VpsBackupResult, password: CharArray): ByteArray = withPassword(password, creating = true) { encoded ->
        val plaintext = serialize(snapshot)
        try {
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val header = header(salt, nonce, plaintext.size + TAG_BYTES)
            withKey(encoded, salt) { key ->
                header + crypt(Cipher.ENCRYPT_MODE, key, nonce, header, plaintext)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    suspend fun decrypt(archive: ByteArray, password: CharArray): VpsBackupResult = withPassword(password, creating = false) { encoded ->
        val header = readHeader(archive)
        withKey(encoded, header.salt) { key ->
            val plaintext = crypt(Cipher.DECRYPT_MODE, key, header.nonce, archive.copyOfRange(0, HEADER_BYTES),
                archive, HEADER_BYTES)
            try {
                parseSnapshot(decode(plaintext))
            } finally {
                plaintext.fill(0)
            }
        }
    }

    private suspend fun <T> withPassword(password: CharArray, creating: Boolean, block: suspend (ByteArray) -> T): T {
        if (password.size !in 1..MAX_PASSWORD_CHARS) fail(VpsBackupArchiveException.Category.PASSWORD)
        val owned = password.copyOf()
        try {
            return withContext(Dispatchers.Default) {
                currentCoroutineContext().ensureActive()
                val encoded = passwordBytes(owned, creating)
                try {
                    val result = block(encoded)
                    currentCoroutineContext().ensureActive()
                    result
                } finally {
                    encoded.fill(0)
                }
            }
        } finally {
            owned.fill('\u0000')
        }
    }

    private suspend fun <T> withKey(password: ByteArray, salt: ByteArray, block: (ByteArray) -> T): T {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(MEMORY_KIB).withIterations(PASSES).withParallelism(LANES).withSalt(salt).build()
        val key = ByteArray(KEY_BYTES)
        try {
            currentCoroutineContext().ensureActive()
            Argon2BytesGenerator().apply { init(parameters) }.generateBytes(password, key)
            currentCoroutineContext().ensureActive()
            return block(key)
        } finally {
            key.fill(0)
            parameters.clear()
        }
    }

    private fun passwordBytes(password: CharArray, creating: Boolean): ByteArray {
        val minimum = if (creating) MIN_NEW_PASSWORD_CODE_POINTS else 1
        if (Character.codePointCount(password, 0, password.size) !in minimum..MAX_PASSWORD_CODE_POINTS)
            fail(VpsBackupArchiveException.Category.PASSWORD)
        val buffer = try {
            Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(password))
        } catch (_: CharacterCodingException) {
            fail(VpsBackupArchiveException.Category.PASSWORD)
        }
        try {
            if (buffer.remaining() > MAX_PASSWORD_BYTES) fail(VpsBackupArchiveException.Category.PASSWORD)
            return ByteArray(buffer.remaining()).also { buffer.get(it) }
        } finally {
            if (buffer.hasArray()) buffer.array().fill(0)
        }
    }

    private fun crypt(mode: Int, key: ByteArray, nonce: ByteArray, header: ByteArray, input: ByteArray, offset: Int = 0): ByteArray = try {
        Cipher.getInstance(TRANSFORMATION).apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(header)
        }.doFinal(input, offset, input.size - offset)
    } catch (_: BadPaddingException) {
        fail(VpsBackupArchiveException.Category.AUTHENTICATION)
    } catch (_: GeneralSecurityException) {
        fail(VpsBackupArchiveException.Category.CRYPTO)
    } catch (_: ProviderException) {
        fail(VpsBackupArchiveException.Category.CRYPTO)
    }

    private fun header(salt: ByteArray, nonce: ByteArray, ciphertextBytes: Int): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN).apply {
            put(MAGIC).put(FORMAT_VERSION).put(CIPHER_SUITE).put(KDF).put(ARGON_VERSION)
            putInt(MEMORY_KIB).putInt(PASSES).putInt(LANES).put(salt).put(nonce).putInt(ciphertextBytes)
        }.array()

    private fun readHeader(archive: ByteArray): Header {
        if (archive.size !in MIN_ARCHIVE_BYTES..MAX_ARCHIVE_BYTES) fail(VpsBackupArchiveException.Category.FORMAT)
        val buffer = ByteBuffer.wrap(archive, 0, HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
        if (!magic.contentEquals(MAGIC) || buffer.get() != FORMAT_VERSION || buffer.get() != CIPHER_SUITE ||
            buffer.get() != KDF || buffer.get() != ARGON_VERSION || buffer.int != MEMORY_KIB ||
            buffer.int != PASSES || buffer.int != LANES
        ) fail(VpsBackupArchiveException.Category.FORMAT)
        val salt = ByteArray(SALT_BYTES).also { buffer.get(it) }
        val nonce = ByteArray(NONCE_BYTES).also { buffer.get(it) }
        val ciphertextBytes = buffer.int.toLong() and UNSIGNED_INT_MASK
        if (ciphertextBytes != archive.size.toLong() - HEADER_BYTES) fail(VpsBackupArchiveException.Category.FORMAT)
        return Header(salt, nonce)
    }

    private fun serialize(snapshot: VpsBackupResult): ByteArray = try {
        ProvisioningJson.encodeBackup(snapshot)
    } catch (_: ProvisioningException) {
        fail(VpsBackupArchiveException.Category.SNAPSHOT)
    }

    private fun decode(plaintext: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plaintext)).toString()
    } catch (_: CharacterCodingException) {
        fail(VpsBackupArchiveException.Category.SNAPSHOT)
    }

    private fun parseSnapshot(json: String): VpsBackupResult = try {
        ProvisioningJson.parseBackup(json)
    } catch (_: ProvisioningException) {
        fail(VpsBackupArchiveException.Category.SNAPSHOT)
    }

    private class Header(val salt: ByteArray, val nonce: ByteArray)

    companion object {
        internal const val HEADER_BYTES = 56
        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / Byte.SIZE_BITS
        internal const val MAX_ARCHIVE_BYTES = HEADER_BYTES + VpsBackupResult.MAX_RESPONSE_BYTES + TAG_BYTES
        private const val KEY_BYTES = 32
        private const val SALT_BYTES = 16
        private const val NONCE_BYTES = 12
        private const val MEMORY_KIB = 19456
        private const val PASSES = 2
        private const val LANES = 1
        private const val MIN_NEW_PASSWORD_CODE_POINTS = 15
        private const val MAX_PASSWORD_CODE_POINTS = 128
        private const val MAX_PASSWORD_CHARS = 256
        private const val MAX_PASSWORD_BYTES = 512
        private const val MIN_ARCHIVE_BYTES = HEADER_BYTES + TAG_BYTES + 1
        private const val UNSIGNED_INT_MASK = 0xffff_ffffL
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION: Byte = 1
        private const val CIPHER_SUITE: Byte = 1
        private const val KDF: Byte = 1
        private const val ARGON_VERSION: Byte = 0x13
        private val MAGIC = "VPN0WNBK".toByteArray(Charsets.US_ASCII)

        private fun fail(category: VpsBackupArchiveException.Category): Nothing = throw VpsBackupArchiveException(category)
    }
}
