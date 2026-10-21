/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
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

internal class VpsCredentialStorageException(val category: Category) : IOException(category.safeMessage) {
    enum class Category(val safeMessage: String) {
        CORRUPT("Saved SSH credentials are corrupt"),
        KEY_UNAVAILABLE("Saved SSH credential key is unavailable"),
        IO("Saved SSH credential storage is unavailable"),
    }
}

internal class VpsCredentialFile internal constructor(
    private val file: File,
    private val key: (Boolean) -> SecretKey,
    private val output: (File) -> FileOutputStream = ::FileOutputStream,
    private val sync: (FileOutputStream) -> Unit = { it.fd.sync() },
    private val rename: (File, File) -> Unit = ::renameFile,
    private val stat: (File) -> Boolean = ::fileExists,
) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, FILE_NAME), ::androidKey)

    @Synchronized
    fun read(): ByteArray? {
        val bytes = readFile() ?: return null
        if (bytes.size < HEADER.size + IV_BYTES + TAG_BYTES ||
            !bytes.copyOfRange(0, HEADER.size).contentEquals(HEADER)
        ) fail(VpsCredentialStorageException.Category.CORRUPT)
        val cipher = cipher()
        try {
            cipher.init(Cipher.DECRYPT_MODE, loadKey(false), GCMParameterSpec(TAG_BITS, bytes, HEADER.size, IV_BYTES))
            cipher.updateAAD(HEADER)
            return cipher.doFinal(bytes, HEADER.size + IV_BYTES, bytes.size - HEADER.size - IV_BYTES)
        } catch (_: BadPaddingException) {
            fail(VpsCredentialStorageException.Category.CORRUPT)
        } catch (_: IllegalBlockSizeException) {
            fail(VpsCredentialStorageException.Category.CORRUPT)
        } catch (_: GeneralSecurityException) {
            fail(VpsCredentialStorageException.Category.KEY_UNAVAILABLE)
        } catch (_: ProviderException) {
            fail(VpsCredentialStorageException.Category.KEY_UNAVAILABLE)
        }
    }

    @Synchronized
    fun write(plaintext: ByteArray) {
        val previous = read()
        val createKey = previous == null
        previous?.fill(0)
        val cipher = cipher()
        val bytes = try {
            cipher.init(Cipher.ENCRYPT_MODE, loadKey(createKey))
            cipher.updateAAD(HEADER)
            val parameters = cipher.parameters.getParameterSpec(GCMParameterSpec::class.java)
            if (parameters.iv.size != IV_BYTES || parameters.tLen != TAG_BITS)
                fail(VpsCredentialStorageException.Category.KEY_UNAVAILABLE)
            HEADER + parameters.iv + cipher.doFinal(plaintext)
        } catch (_: GeneralSecurityException) {
            fail(VpsCredentialStorageException.Category.KEY_UNAVAILABLE)
        } catch (_: ProviderException) {
            fail(VpsCredentialStorageException.Category.KEY_UNAVAILABLE)
        }
        commit(bytes)
    }

    private fun readFile(): ByteArray? = try {
        if (!stat(file)) null else FileInputStream(file).use { it.readBytes() }
    } catch (_: IOException) {
        fail(VpsCredentialStorageException.Category.IO)
    } catch (_: ErrnoException) {
        fail(VpsCredentialStorageException.Category.IO)
    } catch (_: SecurityException) {
        fail(VpsCredentialStorageException.Category.IO)
    }

    private fun loadKey(create: Boolean): SecretKey = try {
        key(create)
    } catch (e: VpsCredentialStorageException) {
        throw e
    } catch (_: GeneralSecurityException) {
        fail(VpsCredentialStorageException.Category.KEY_UNAVAILABLE)
    } catch (_: IOException) {
        fail(VpsCredentialStorageException.Category.KEY_UNAVAILABLE)
    } catch (_: ProviderException) {
        fail(VpsCredentialStorageException.Category.KEY_UNAVAILABLE)
    } catch (_: SecurityException) {
        fail(VpsCredentialStorageException.Category.KEY_UNAVAILABLE)
    }

    private fun commit(bytes: ByteArray) {
        val temporary = try {
            File.createTempFile("vps-credentials-", ".tmp", file.parentFile)
        } catch (_: IOException) {
            fail(VpsCredentialStorageException.Category.IO)
        } catch (_: SecurityException) {
            fail(VpsCredentialStorageException.Category.IO)
        }
        try {
            Os.chmod(temporary.path, PRIVATE_FILE_MODE)
            output(temporary).use { stream ->
                stream.write(bytes)
                sync(stream)
            }
            rename(temporary, file)
        } catch (_: IOException) {
            fail(VpsCredentialStorageException.Category.IO)
        } catch (_: ErrnoException) {
            fail(VpsCredentialStorageException.Category.IO)
        } catch (_: SecurityException) {
            fail(VpsCredentialStorageException.Category.IO)
        } finally {
            try {
                if (!temporary.delete() && temporary.exists())
                    Log.w(TAG, "Could not remove temporary SSH credential file")
            } catch (_: SecurityException) {
                Log.w(TAG, "Could not remove temporary SSH credential file")
            }
        }
    }

    companion object {
        private const val FILE_NAME = "vps-credentials.v1"
        private const val KEY_ALIAS = "vpn0wn.vps.credentials.v1"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_BITS = 256
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / Byte.SIZE_BITS
        private const val PRIVATE_FILE_MODE = 0x180
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG = "vpn0wn/VpsCredentials"
        private val HEADER = "vpn0wn.vps.credentials\u0001".toByteArray(Charsets.US_ASCII)

        private fun fail(category: VpsCredentialStorageException.Category): Nothing =
            throw VpsCredentialStorageException(category)

        private fun cipher(): Cipher = try {
            Cipher.getInstance(TRANSFORMATION)
        } catch (_: GeneralSecurityException) {
            fail(VpsCredentialStorageException.Category.KEY_UNAVAILABLE)
        } catch (_: ProviderException) {
            fail(VpsCredentialStorageException.Category.KEY_UNAVAILABLE)
        }

        private fun androidKey(create: Boolean): SecretKey {
            val store = KeyStore.getInstance(KEYSTORE)
            store.load(null)
            if (store.containsAlias(KEY_ALIAS)) {
                return store.getKey(KEY_ALIAS, null) as? SecretKey
                    ?: fail(VpsCredentialStorageException.Category.KEY_UNAVAILABLE)
            }
            if (!create) fail(VpsCredentialStorageException.Category.KEY_UNAVAILABLE)
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(KEY_BITS)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            return generator.generateKey()
        }

        private fun fileExists(file: File): Boolean = try {
            Os.stat(file.path)
            true
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ENOENT) false else throw e
        }

        private fun renameFile(source: File, destination: File) = Os.rename(source.path, destination.path)
    }
}
