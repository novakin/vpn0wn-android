/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class VpsUserProfileFile internal constructor(
    private val file: File,
    private val key: (Boolean) -> SecretKey,
    private val output: (File) -> FileOutputStream = ::FileOutputStream,
    private val sync: (FileOutputStream) -> Unit = { it.fd.sync() },
    private val rename: (File, File) -> Unit = { source, target -> Os.rename(source.path, target.path) },
    private val stat: (File) -> Boolean = ::exists,
) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, FILE_NAME), ::androidKey)

    @Synchronized
    fun read(): ByteArray? {
        val bytes = readFile() ?: return null
        if (bytes.size < HEADER.size + IV_BYTES + TAG_BYTES || !bytes.copyOfRange(0, HEADER.size).contentEquals(HEADER))
            fail(VpsUserProfileException.Category.CORRUPT)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(TAG_BITS, bytes, HEADER.size, IV_BYTES))
            cipher.updateAAD(HEADER)
            return cipher.doFinal(bytes, HEADER.size + IV_BYTES, bytes.size - HEADER.size - IV_BYTES)
        } catch (_: BadPaddingException) {
            fail(VpsUserProfileException.Category.CORRUPT)
        } catch (_: IllegalBlockSizeException) {
            fail(VpsUserProfileException.Category.CORRUPT)
        } catch (_: GeneralSecurityException) {
            fail(VpsUserProfileException.Category.KEY_UNAVAILABLE)
        } catch (_: ProviderException) {
            fail(VpsUserProfileException.Category.KEY_UNAVAILABLE)
        } catch (_: IOException) {
            fail(VpsUserProfileException.Category.KEY_UNAVAILABLE)
        } catch (_: SecurityException) {
            fail(VpsUserProfileException.Category.KEY_UNAVAILABLE)
        }
    }

    @Synchronized
    fun write(plaintext: ByteArray) {
        if (plaintext.size > MAX_PLAINTEXT_BYTES) fail(VpsUserProfileException.Category.LIMIT)
        val previous = read()
        val createKey = previous == null
        previous?.fill(0)
        val bytes = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key(createKey))
            val parameters = cipher.parameters.getParameterSpec(GCMParameterSpec::class.java)
            if (parameters.iv.size != IV_BYTES || parameters.tLen != TAG_BITS) fail(VpsUserProfileException.Category.KEY_UNAVAILABLE)
            cipher.updateAAD(HEADER)
            HEADER + parameters.iv + cipher.doFinal(plaintext)
        } catch (_: GeneralSecurityException) {
            fail(VpsUserProfileException.Category.KEY_UNAVAILABLE)
        } catch (_: ProviderException) {
            fail(VpsUserProfileException.Category.KEY_UNAVAILABLE)
        } catch (_: IOException) {
            fail(VpsUserProfileException.Category.KEY_UNAVAILABLE)
        } catch (_: SecurityException) {
            fail(VpsUserProfileException.Category.KEY_UNAVAILABLE)
        }
        commit(bytes)
    }

    private fun readFile(): ByteArray? = try {
        if (!stat(file)) null else FileInputStream(file).use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(COPY_BYTES)
            var count = input.read(buffer)
            while (count != -1) {
                if (bytes.size() + count > MAX_PLAINTEXT_BYTES + HEADER.size + IV_BYTES + TAG_BYTES)
                    fail(VpsUserProfileException.Category.CORRUPT)
                bytes.write(buffer, 0, count)
                count = input.read(buffer)
            }
            bytes.toByteArray()
        }
    } catch (failure: VpsUserProfileException) {
        throw failure
    } catch (_: IOException) {
        fail(VpsUserProfileException.Category.IO)
    } catch (_: ErrnoException) {
        fail(VpsUserProfileException.Category.IO)
    } catch (_: SecurityException) {
        fail(VpsUserProfileException.Category.IO)
    }

    private fun commit(bytes: ByteArray) {
        val temporary = try {
            File.createTempFile("vps-profiles-", ".tmp", file.parentFile)
        } catch (_: IOException) {
            fail(VpsUserProfileException.Category.IO)
        } catch (_: SecurityException) {
            fail(VpsUserProfileException.Category.IO)
        }
        try {
            Os.chmod(temporary.path, PRIVATE_FILE_MODE)
            output(temporary).use { stream ->
                stream.write(bytes)
                sync(stream)
            }
            rename(temporary, file)
            if (readFile()?.contentEquals(bytes) != true) fail(VpsUserProfileException.Category.IO)
        } catch (failure: VpsUserProfileException) {
            throw failure
        } catch (_: IOException) {
            fail(VpsUserProfileException.Category.IO)
        } catch (_: ErrnoException) {
            fail(VpsUserProfileException.Category.IO)
        } catch (_: SecurityException) {
            fail(VpsUserProfileException.Category.IO)
        } finally {
            try {
                if (!temporary.delete() && temporary.exists()) Log.w(TAG, "Could not remove encrypted profile staging file")
            } catch (_: SecurityException) {
                Log.w(TAG, "Could not remove encrypted profile staging file")
            }
        }
    }

    companion object {
        internal const val MAX_PLAINTEXT_BYTES = 16_777_216
        private const val FILE_NAME = "vps-user-profiles.v1"
        private const val KEY_ALIAS = "vpn0wn.vps.user-profiles.v1"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_BITS = 256
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / Byte.SIZE_BITS
        private const val COPY_BYTES = 8192
        private const val PRIVATE_FILE_MODE = 0x180
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG = "vpn0wn/VpsProfiles"
        private val HEADER = "vpn0wn.vps.user-profiles\u0001".toByteArray(Charsets.US_ASCII)

        private fun fail(category: VpsUserProfileException.Category): Nothing = throw VpsUserProfileException(category)

        private fun androidKey(create: Boolean): SecretKey {
            val store = KeyStore.getInstance(KEYSTORE)
            store.load(null)
            if (store.containsAlias(KEY_ALIAS)) return store.getKey(KEY_ALIAS, null) as? SecretKey
                ?: fail(VpsUserProfileException.Category.KEY_UNAVAILABLE)
            if (!create) fail(VpsUserProfileException.Category.KEY_UNAVAILABLE)
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(KEY_BITS).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            return generator.generateKey()
        }

        private fun exists(file: File): Boolean = try {
            Os.stat(file.path)
            true
        } catch (failure: ErrnoException) {
            if (failure.errno == OsConstants.ENOENT) false else throw failure
        }
    }
}
