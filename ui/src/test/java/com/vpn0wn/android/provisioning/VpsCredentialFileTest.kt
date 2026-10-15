/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStoreException
import java.security.ProviderException
import java.util.concurrent.CancellationException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [VpsCredentialFileTest.NativeFiles::class])
class VpsCredentialFileTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var file: File
    private lateinit var secretKey: SecretKey
    private val keyRequests = mutableListOf<Boolean>()

    @Before
    fun setUp() {
        file = File(folder.root, "credentials")
        secretKey = newKey()
        NativeFiles.denied = false
        NativeFiles.failChmod = false
        NativeFiles.statPaths.clear()
        NativeFiles.modes.clear()
        NativeFiles.events.clear()
    }

    @Test
    fun missingFileReadsNullWithoutAccessingKeyStore() {
        assertNull(store().read())
        assertTrue(keyRequests.isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun androidConstructionUsesNoBackupDirectory() {
        val directory = folder.newFolder("no-backup")
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getNoBackupFilesDir(): File = directory
        }
        assertNull(VpsCredentialFile(context).read())
        assertEquals(listOf(File(directory, "vps-credentials.v1").path), NativeFiles.statPaths)
    }

    @Test
    fun encryptedBytesRoundTripAcrossInstancesWithoutChangingCallerPlaintext() {
        val plaintext = "synthetic SSH password and private key".toByteArray()
        store().write(plaintext)
        assertArrayEquals(plaintext, store().read())
        assertEquals(listOf(true, false), keyRequests)
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(plaintext.toString(Charsets.UTF_8)))
        assertEquals("synthetic SSH password and private key", plaintext.toString(Charsets.UTF_8))
        assertEquals(listOf(file.name), folder.root.list()!!.toList())
    }

    @Test
    fun replacementUsesFreshIvAndNeverRequestsKeyCreation() {
        val plaintext = "synthetic credential".toByteArray()
        store().write(plaintext)
        val previous = file.readBytes()
        keyRequests.clear()
        store().write(plaintext)
        val replacement = file.readBytes()
        assertFalse(previous.contentEquals(replacement))
        assertFalse(previous.copyOfRange(HEADER_BYTES, HEADER_BYTES + IV_BYTES)
            .contentEquals(replacement.copyOfRange(HEADER_BYTES, HEADER_BYTES + IV_BYTES)))
        assertEquals(listOf(false, false), keyRequests)
        assertArrayEquals(plaintext, store().read())
    }

    @Test
    fun emptyPlaintextStillUsesAuthenticatedEncryption() {
        store().write(byteArrayOf())
        assertArrayEquals(byteArrayOf(), store().read())
        assertEquals(HEADER_BYTES + IV_BYTES + TAG_BYTES, file.length().toInt())
    }

    @Test
    fun independentJceDecryptionRequiresAuthenticatedHeader() {
        val plaintext = "synthetic credential".toByteArray()
        store().write(plaintext)
        val bytes = file.readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val parameters = GCMParameterSpec(128, bytes, HEADER_BYTES, IV_BYTES)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, parameters)
        cipher.updateAAD("vpn0wn.vps.credentials\u0001".toByteArray(Charsets.US_ASCII))
        assertArrayEquals(plaintext, cipher.doFinal(bytes, HEADER_BYTES + IV_BYTES, bytes.size - HEADER_BYTES - IV_BYTES))
        cipher.init(Cipher.DECRYPT_MODE, secretKey, parameters)
        assertThrows(AEADBadTagException::class.java) {
            cipher.doFinal(bytes, HEADER_BYTES + IV_BYTES, bytes.size - HEADER_BYTES - IV_BYTES)
        }
    }

    @Test
    fun lostKeyBlocksReadAndReplacementWithoutChangingCiphertext() {
        store().write("synthetic previous".toByteArray())
        val previous = file.readBytes()
        val requests = mutableListOf<Boolean>()
        val unavailable = VpsCredentialFile(file, { create ->
            requests.add(create)
            throw KeyStoreException("synthetic provider payload")
        })
        assertFailure(VpsCredentialStorageException.Category.KEY_UNAVAILABLE) { unavailable.read() }
        assertFailure(VpsCredentialStorageException.Category.KEY_UNAVAILABLE) {
            unavailable.write("synthetic replacement".toByteArray())
        }
        assertEquals(listOf(false, false), requests)
        assertArrayEquals(previous, file.readBytes())
    }

    @Test
    fun failedKeyCreationPublishesNoFile() {
        val unavailable = VpsCredentialFile(file, { throw KeyStoreException("synthetic secret") })
        assertFailure(VpsCredentialStorageException.Category.KEY_UNAVAILABLE) {
            unavailable.write("synthetic credential".toByteArray())
        }
        assertTrue(folder.root.list()!!.isEmpty())
    }

    @Test
    fun keyProviderFailureIsSafeAndNeverPublishesCiphertext() {
        val unavailable = VpsCredentialFile(file, { throw ProviderException("synthetic provider payload") })
        assertFailure(VpsCredentialStorageException.Category.KEY_UNAVAILABLE) { unavailable.write(byteArrayOf(1)) }
        assertFalse(file.exists())
    }

    @Test
    fun wrongKeyFailsAuthenticationAndCannotOverwritePriorCiphertext() {
        store().write("synthetic previous".toByteArray())
        val previous = file.readBytes()
        val wrongKey = newKey()
        val wrong = VpsCredentialFile(file, { wrongKey })
        assertFailure(VpsCredentialStorageException.Category.CORRUPT) { wrong.read() }
        assertFailure(VpsCredentialStorageException.Category.CORRUPT) {
            wrong.write("synthetic replacement".toByteArray())
        }
        assertArrayEquals(previous, file.readBytes())
        assertArrayEquals("synthetic previous".toByteArray(), store().read())
    }

    @Test
    fun headerVersionNonceCiphertextAndTagTamperingAreRejectedAndPreserved() {
        store().write("synthetic previous".toByteArray())
        val original = file.readBytes()
        for (offset in listOf(0, HEADER_BYTES - 1, HEADER_BYTES, HEADER_BYTES + IV_BYTES, original.lastIndex)) {
            val corrupted = original.clone()
            corrupted[offset] = (corrupted[offset].toInt() xor 1).toByte()
            file.writeBytes(corrupted)
            assertFailure(VpsCredentialStorageException.Category.CORRUPT) { store().read() }
            assertFailure(VpsCredentialStorageException.Category.CORRUPT) {
                store().write("synthetic replacement".toByteArray())
            }
            assertArrayEquals(corrupted, file.readBytes())
        }
    }

    @Test
    fun truncatedCiphertextIsNotTreatedAsMissingAndNeverCreatesKey() {
        for (size in listOf(0, HEADER_BYTES - 1, HEADER_BYTES + IV_BYTES + TAG_BYTES - 1)) {
            file.writeBytes(ByteArray(size))
            keyRequests.clear()
            assertFailure(VpsCredentialStorageException.Category.CORRUPT) { store().read() }
            assertFailure(VpsCredentialStorageException.Category.CORRUPT) { store().write(byteArrayOf(1)) }
            assertTrue(keyRequests.isEmpty())
            assertEquals(size.toLong(), file.length())
        }
    }

    @Test
    fun deniedStatIsIoFailureWithoutKeyCreationOrReplacement() {
        store().write("synthetic previous".toByteArray())
        val original = file.readBytes()
        keyRequests.clear()
        NativeFiles.denied = true
        assertFailure(VpsCredentialStorageException.Category.IO) { store().read() }
        assertFailure(VpsCredentialStorageException.Category.IO) { store().write(byteArrayOf(1)) }
        assertTrue(keyRequests.isEmpty())
        assertArrayEquals(original, file.readBytes())
    }

    @Test
    fun failedReadAfterStatDoesNotBecomeEmptyState() {
        assertTrue(file.mkdir())
        assertFailure(VpsCredentialStorageException.Category.IO) { store().read() }
        assertFailure(VpsCredentialStorageException.Category.IO) { store().write(byteArrayOf(1)) }
        assertTrue(keyRequests.isEmpty())
        assertTrue(file.isDirectory)
    }

    @Test
    fun stagingIsPrivateAndSyncedAndClosedBeforeRename() {
        val events = NativeFiles.events
        val guarded = store(
            output = { temporary ->
                assertEquals(0x180, NativeFiles.modes[temporary.path])
                events.add("open")
                object : FileOutputStream(temporary) {
                    override fun write(bytes: ByteArray) {
                        events.add("write")
                        super.write(bytes)
                    }
                    override fun close() {
                        events.add("close")
                        super.close()
                    }
                }
            },
            sync = { stream -> events.add("sync"); stream.fd.sync() },
        )
        guarded.write("synthetic credential".toByteArray())
        assertEquals(listOf("chmod", "open", "write", "sync", "close", "rename"), events)
        assertEquals(listOf(file.name), folder.root.list()!!.toList())
    }

    @Test
    fun partialWriteFailurePreservesOldCiphertextAndRemovesOwnedTemporary() {
        assertPreserved { store(output = { temporary ->
            object : FileOutputStream(temporary) {
                override fun write(bytes: ByteArray) {
                    super.write(bytes, 0, bytes.size / 2)
                    throw IOException("synthetic write payload")
                }
            }
        }) }
    }

    @Test
    fun syncFailurePreservesOldCiphertextAndRemovesOwnedTemporary() {
        assertPreserved { store(sync = { throw IOException("synthetic sync payload") }) }
    }

    @Test
    fun closeFailurePreservesOldCiphertextAndRemovesOwnedTemporary() {
        assertPreserved { store(output = { temporary ->
            object : FileOutputStream(temporary) {
                override fun close() {
                    super.close()
                    throw IOException("synthetic close payload")
                }
            }
        }) }
    }

    @Test
    fun renameFailurePreservesOldCiphertextAndUnrelatedTemporary() {
        val unrelated = File(folder.root, "retained-evidence.tmp").apply { writeText("synthetic retained") }
        assertPreserved(listOf(file.name, unrelated.name)) {
            VpsCredentialFile(file, ::lookupKey, rename = { _, _ -> throw ErrnoException("rename", OsConstants.EIO) })
        }
        assertEquals("synthetic retained", unrelated.readText())
    }

    @Test
    fun chmodFailurePublishesNoCiphertext() {
        NativeFiles.failChmod = true
        assertFailure(VpsCredentialStorageException.Category.IO) { store().write(byteArrayOf(1)) }
        assertTrue(folder.root.list()!!.isEmpty())
        assertFalse(NativeFiles.events.contains("rename"))
    }

    @Test
    fun failedInitialWriteLeavesNoPublishedFile() {
        val failing = store(sync = { throw IOException("synthetic sync failure") })
        assertFailure(VpsCredentialStorageException.Category.IO) { failing.write(byteArrayOf(1)) }
        assertTrue(folder.root.list()!!.isEmpty())
    }

    @Test
    fun cancellationSurvivesFailedCloseAndTemporaryCleanup() {
        val cancellation = CancellationException("synthetic cancellation")
        val failing = store(output = { temporary ->
            object : FileOutputStream(temporary) {
                override fun write(bytes: ByteArray) { throw cancellation }
                override fun close() {
                    super.close()
                    assertTrue(temporary.delete())
                    assertTrue(temporary.mkdir())
                    File(temporary, "synthetic-child").writeText("synthetic retained")
                    throw IOException("synthetic close failure")
                }
            }
        })
        assertSame(cancellation, assertThrows(CancellationException::class.java) { failing.write(byteArrayOf(1)) })
        assertFalse(file.exists())
        assertEquals(1, cancellation.suppressed.size)
    }

    @Test
    fun errorSurvivesCloseFailureAndPreservesOldCiphertext() {
        store().write("synthetic previous".toByteArray())
        val original = file.readBytes()
        val failure = AssertionError("synthetic error")
        val failing = store(output = { temporary ->
            object : FileOutputStream(temporary) {
                override fun write(bytes: ByteArray) { throw failure }
                override fun close() {
                    super.close()
                    throw IOException("synthetic close failure")
                }
            }
        })
        assertSame(failure, assertThrows(AssertionError::class.java) { failing.write(byteArrayOf(1)) })
        assertArrayEquals(original, file.readBytes())
        assertEquals(listOf(file.name), folder.root.list()!!.toList())
    }

    private fun assertPreserved(expectedFiles: List<String> = listOf(file.name), failing: () -> VpsCredentialFile) {
        store().write("synthetic previous".toByteArray())
        val previous = file.readBytes()
        assertFailure(VpsCredentialStorageException.Category.IO) { failing().write("synthetic replacement".toByteArray()) }
        assertArrayEquals(previous, file.readBytes())
        assertArrayEquals("synthetic previous".toByteArray(), store().read())
        assertEquals(expectedFiles.sorted(), folder.root.list()!!.toList().sorted())
    }

    private fun assertFailure(category: VpsCredentialStorageException.Category, operation: () -> Unit) {
        val exception = assertThrows(VpsCredentialStorageException::class.java, operation)
        assertEquals(category, exception.category)
        assertEquals(category.safeMessage, exception.message)
        assertNull(exception.cause)
        assertTrue(exception.suppressed.isEmpty())
    }

    private fun store(
        output: (File) -> FileOutputStream = ::FileOutputStream,
        sync: (FileOutputStream) -> Unit = { it.fd.sync() },
    ) = VpsCredentialFile(file, ::lookupKey, output = output, sync = sync)

    private fun lookupKey(create: Boolean): SecretKey {
        keyRequests.add(create)
        return secretKey
    }

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Implements(Os::class)
    class NativeFiles private constructor() {
        companion object {
            @JvmField var denied = false
            @JvmField var failChmod = false
            @JvmField val statPaths = mutableListOf<String>()
            @JvmField val modes = mutableMapOf<String, Int>()
            @JvmField val events = mutableListOf<String>()

            @Implementation
            @JvmStatic
            fun stat(path: String): StructStat? {
                statPaths.add(path)
                if (denied) throw ErrnoException("stat", OsConstants.EACCES)
                if (!File(path).exists()) throw ErrnoException("stat", OsConstants.ENOENT)
                return null
            }

            @Implementation
            @JvmStatic
            fun chmod(path: String, mode: Int) {
                if (failChmod) throw ErrnoException("chmod", OsConstants.EACCES)
                modes[path] = mode
                events.add("chmod")
            }

            @Implementation
            @JvmStatic
            fun rename(source: String, destination: String) {
                events.add("rename")
                if (!File(source).renameTo(File(destination))) throw ErrnoException("rename", OsConstants.EIO)
            }
        }
    }

    companion object {
        private const val HEADER_BYTES = 23
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
    }
}
