/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStoreException
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [VpsCredentialFileTest.NativeFiles::class])
class VpsUserProfileFileTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var file: File
    private lateinit var key: SecretKey
    private val keyRequests = mutableListOf<Boolean>()

    @Before
    fun setUp() {
        file = File(folder.root, "profiles")
        key = newKey()
        resetNativeFiles()
    }

    @Test
    fun missingFileDoesNotAccessKeyAndAndroidConstructorUsesSeparateNoBackupFile() {
        assertNull(storage().read())
        assertTrue(keyRequests.isEmpty())
        val directory = folder.newFolder("no-backup")
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getNoBackupFilesDir(): File = directory
        }
        assertNull(VpsUserProfileFile(context).read())
        assertEquals(File(directory, "vps-user-profiles.v1").path, VpsCredentialFileTest.NativeFiles.statPaths.last())
    }

    @Test
    fun independentDecryptionAuthenticatesHeaderAndReplacementUsesFreshNonce() {
        val plaintext = VpsUserProfileRecords.encode(listOf(VpsProfileFixture.profile()))
        storage().write(plaintext)
        val first = file.readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val parameters = GCMParameterSpec(128, first, HEADER.size, IV_BYTES)
        cipher.init(Cipher.DECRYPT_MODE, key, parameters)
        cipher.updateAAD(HEADER)
        assertArrayEquals(plaintext, cipher.doFinal(first, HEADER.size + IV_BYTES, first.size - HEADER.size - IV_BYTES))
        cipher.init(Cipher.DECRYPT_MODE, key, parameters)
        assertThrows(AEADBadTagException::class.java) {
            cipher.doFinal(first, HEADER.size + IV_BYTES, first.size - HEADER.size - IV_BYTES)
        }
        keyRequests.clear()
        storage().write(plaintext)
        assertFalse(first.contentEquals(file.readBytes()))
        assertEquals(listOf(false, false), keyRequests)
        assertArrayEquals(plaintext, storage().read())
        val ciphertext = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(ciphertext.contains(VpsProfileFixture.client.privateKey.toBase64()))
        assertFalse(ciphertext.contains(VpsProfileFixture.psk.toBase64()))
        assertArrayEquals(plaintext, VpsUserProfileRecords.encode(listOf(VpsProfileFixture.profile())))
    }

    @Test
    fun wrongOrLostKeyCannotReadOrReplaceExistingFile() {
        storage().write(byteArrayOf(1))
        val previous = file.readBytes()
        val wrong = VpsUserProfileFile(file, { newKey() })
        assertFailure(VpsUserProfileException.Category.CORRUPT) { wrong.read() }
        assertFailure(VpsUserProfileException.Category.CORRUPT) { wrong.write(byteArrayOf(2)) }
        val missing = VpsUserProfileFile(file, { throw KeyStoreException("synthetic secret") })
        assertFailure(VpsUserProfileException.Category.KEY_UNAVAILABLE) { missing.read() }
        assertFailure(VpsUserProfileException.Category.KEY_UNAVAILABLE) { missing.write(byteArrayOf(2)) }
        assertArrayEquals(previous, file.readBytes())
    }

    @Test
    fun headerNonceCiphertextTagAndTruncationFailuresPreserveCiphertext() {
        storage().write(byteArrayOf(1))
        val original = file.readBytes()
        val samples = listOf(0, HEADER.lastIndex, HEADER.size, HEADER.size + IV_BYTES, original.lastIndex)
            .map { offset -> original.clone().apply { this[offset] = (this[offset].toInt() xor 1).toByte() } } +
            listOf(byteArrayOf(), original.copyOf(HEADER.size - 1), original.copyOf(original.size - 1))
        samples.forEach { bytes ->
            file.writeBytes(bytes)
            assertFailure(VpsUserProfileException.Category.CORRUPT) { storage().read() }
            assertFailure(VpsUserProfileException.Category.CORRUPT) { storage().write(byteArrayOf(2)) }
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test
    fun unreadableExistingFileCannotBecomeMissingStateOrCreateKey() {
        assertTrue(file.mkdir())
        assertFailure(VpsUserProfileException.Category.IO) { storage().write(byteArrayOf(1)) }
        assertTrue(keyRequests.isEmpty())
        assertTrue(file.delete())
        VpsCredentialFileTest.NativeFiles.denied = true
        assertFailure(VpsUserProfileException.Category.IO) { storage().write(byteArrayOf(1)) }
        assertTrue(keyRequests.isEmpty())
    }

    @Test
    fun writesOnlyPrivateCiphertextAndSyncsClosesBeforeRename() {
        val events = VpsCredentialFileTest.NativeFiles.events
        storage(
            output = { temporary ->
                assertEquals(0x180, VpsCredentialFileTest.NativeFiles.modes[temporary.path])
                events.add("open")
                object : FileOutputStream(temporary) {
                    override fun write(bytes: ByteArray) {
                        assertTrue(bytes.copyOf(HEADER.size).contentEquals(HEADER))
                        events.add("write")
                        super.write(bytes)
                    }
                    override fun close() {
                        events.add("close")
                        super.close()
                    }
                }
            },
            sync = { stream ->
                events.add("sync")
                stream.fd.sync()
            },
        ).write(byteArrayOf(1))
        assertEquals(listOf("chmod", "open", "write", "sync", "close", "rename"), events)
        assertEquals(listOf(file.name), folder.root.list()!!.toList())
    }

    @Test
    fun openPartialWriteSyncCloseAndRenameFailuresPreservePreviousFileAndUnrelatedEvidence() {
        val unrelated = File(folder.root, "unrelated.tmp").apply { writeText("retained") }
        storage().write(byteArrayOf(1))
        val previous = file.readBytes()
        val failing = listOf(
            storage(output = { throw IOException("synthetic open secret") }),
            storage(output = { temporary -> object : FileOutputStream(temporary) {
                override fun write(bytes: ByteArray) {
                    super.write(bytes, 0, bytes.size / 2)
                    throw IOException("synthetic write secret")
                }
            } }),
            storage(sync = { throw IOException("synthetic sync secret") }),
            storage(output = { temporary -> object : FileOutputStream(temporary) {
                override fun close() {
                    super.close()
                    throw IOException("synthetic close secret")
                }
            } }),
            VpsUserProfileFile(file, { key }, rename = { _, _ -> throw ErrnoException("rename", OsConstants.EIO) }),
        )
        failing.forEach { storage ->
            assertFailure(VpsUserProfileException.Category.IO) { storage.write(byteArrayOf(2)) }
            assertArrayEquals(previous, file.readBytes())
            assertEquals(setOf(file.name, unrelated.name), folder.root.list()!!.toSet())
        }
    }

    @Test
    fun keyCreationAndChmodFailuresPublishNothing() {
        val missing = VpsUserProfileFile(file, { throw KeyStoreException("synthetic secret") })
        assertFailure(VpsUserProfileException.Category.KEY_UNAVAILABLE) { missing.write(byteArrayOf(1)) }
        VpsCredentialFileTest.NativeFiles.failChmod = true
        assertFailure(VpsUserProfileException.Category.IO) { storage().write(byteArrayOf(1)) }
        assertTrue(folder.root.list()!!.isEmpty())
    }

    @Test
    fun oversizedPlaintextIsRejectedBeforeAnyKeyOrFileEffect() {
        assertFailure(VpsUserProfileException.Category.LIMIT) {
            storage().write(ByteArray(VpsUserProfileFile.MAX_PLAINTEXT_BYTES + 1))
        }
        assertTrue(keyRequests.isEmpty())
        assertTrue(folder.root.list()!!.isEmpty())
    }

    @Test
    fun failedReadbackReportsUnconfirmedWriteAndPreservesCommittedCiphertext() {
        storage().write(byteArrayOf(1))
        val failing = VpsUserProfileFile(file, { key }, stat = { path ->
            if (VpsCredentialFileTest.NativeFiles.events.lastOrNull() == "rename")
                throw SecurityException("synthetic readback secret")
            path.exists()
        })
        VpsCredentialFileTest.NativeFiles.events.clear()
        assertFailure(VpsUserProfileException.Category.IO) { failing.write(byteArrayOf(2)) }
        assertArrayEquals(byteArrayOf(2), storage().read())
        assertEquals(listOf(file.name), folder.root.list()!!.toList())
    }

    @Test
    fun cancellationAndProgrammingErrorsRemainDistinctAndLeavePreviousFileIntact() {
        storage().write(byteArrayOf(1))
        val previous = file.readBytes()
        val cancellation = CancellationException("synthetic cancellation")
        val error = AssertionError("synthetic error")
        for (failure in listOf(cancellation, error)) {
            val failing = storage(output = { temporary -> object : FileOutputStream(temporary) {
                override fun write(bytes: ByteArray) { throw failure }
            } })
            val actual = assertThrows(failure.javaClass) { failing.write(byteArrayOf(2)) }
            assertSame(failure, actual)
            assertArrayEquals(previous, file.readBytes())
            assertEquals(listOf(file.name), folder.root.list()!!.toList())
        }
    }

    private fun storage(
        output: (File) -> FileOutputStream = ::FileOutputStream,
        sync: (FileOutputStream) -> Unit = { it.fd.sync() },
    ) = VpsUserProfileFile(file, { create ->
        keyRequests.add(create)
        key
    }, output = output, sync = sync)

    private fun assertFailure(category: VpsUserProfileException.Category, operation: () -> Unit) {
        val failure = assertThrows(VpsUserProfileException::class.java, operation)
        assertEquals(category, failure.category)
        assertEquals(category.message, failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    companion object {
        private val HEADER = "vpn0wn.vps.user-profiles\u0001".toByteArray(Charsets.US_ASCII)
        private const val IV_BYTES = 12

        fun resetNativeFiles() {
            VpsCredentialFileTest.NativeFiles.denied = false
            VpsCredentialFileTest.NativeFiles.failChmod = false
            VpsCredentialFileTest.NativeFiles.statPaths.clear()
            VpsCredentialFileTest.NativeFiles.modes.clear()
            VpsCredentialFileTest.NativeFiles.events.clear()
        }
    }
}
