package com.vpn0wn.android.configStore

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import com.vpn0wn.config.Config
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(application = Application::class, sdk = [28], shadows = [FileConfigStoreTest.RenameShadow::class])
class FileConfigStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var context: WriteContext
    private lateinit var store: FileConfigStore

    @Before
    fun setUp() {
        context = WriteContext(ApplicationProvider.getApplicationContext(), folder.root)
        store = FileConfigStore(context)
        RenameShadow.fail = false
    }

    @Test
    fun successfulCreateAndSaveRoundTrip() {
        store.create("one", profile(51820))
        assertEquals(profile(51820).toWgQuickString(), store.load("one").toWgQuickString())
        store.save("one", profile(51821))
        assertEquals(profile(51821).toWgQuickString(), store.load("one").toWgQuickString())
        assertEquals(setOf("one"), store.enumerate())
        assertEquals(listOf("one.conf"), folder.root.list()!!.toList())
    }

    @Test
    fun failedPartialWritePreservesPreviousConfig() {
        store.create("one", profile(51820))
        val original = File(folder.root, "one.conf").readBytes()
        context.failWrite = true
        assertThrows(IOException::class.java) { store.save("one", profile(51821)) }
        assertArrayEquals(original, File(folder.root, "one.conf").readBytes())
        assertEquals(profile(51820).toWgQuickString(), store.load("one").toWgQuickString())
        assertEquals(listOf("one.conf"), folder.root.list()!!.toList())
    }

    @Test
    fun failedReplacementPreservesPreviousConfig() {
        store.create("one", profile(51820))
        RenameShadow.fail = true
        assertThrows(IOException::class.java) { store.save("one", profile(51821)) }
        assertEquals(profile(51820).toWgQuickString(), store.load("one").toWgQuickString())
        assertEquals(listOf("one.conf"), folder.root.list()!!.toList())
    }

    @Test
    fun failedCreateLeavesNoProfile() {
        context.failWrite = true
        assertThrows(IOException::class.java) { store.create("one", profile(51820)) }
        assertTrue(store.enumerate().isEmpty())
        assertTrue(folder.root.list()!!.isEmpty())
    }

    @Test
    fun failedCreateCommitLeavesNoProfile() {
        RenameShadow.fail = true
        assertThrows(IOException::class.java) { store.create("one", profile(51820)) }
        assertTrue(store.enumerate().isEmpty())
        assertTrue(folder.root.list()!!.isEmpty())
    }

    @Test
    fun duplicateCreateAndRenamePreserveBothProfiles() {
        store.create("one", profile(51820))
        store.create("two", profile(51821))
        assertThrows(IOException::class.java) { store.create("one", profile(51821)) }
        assertThrows(IOException::class.java) { store.rename("one", "two") }
        assertEquals(profile(51820).toWgQuickString(), store.load("one").toWgQuickString())
        assertEquals(profile(51821).toWgQuickString(), store.load("two").toWgQuickString())
    }

    @Test
    fun renameFailureCreatesNoDestinationMarker() {
        store.create("one", profile(51820))
        RenameShadow.fail = true
        assertThrows(IOException::class.java) { store.rename("one", "two") }
        assertEquals(setOf("one"), store.enumerate())
        assertEquals(profile(51820).toWgQuickString(), store.load("one").toWgQuickString())
    }

    @Test
    fun renameAndDeletePreserveNames() {
        store.create("one", profile(51820))
        store.rename("one", "two")
        assertEquals(setOf("two"), store.enumerate())
        assertEquals(profile(51820).toWgQuickString(), store.load("two").toWgQuickString())
        store.delete("two")
        assertTrue(store.enumerate().isEmpty())
        assertThrows(IOException::class.java) { store.save("two", profile(51820)) }
    }

    @Test
    fun concurrentCreateCannotOverwriteTheFirstProfile() {
        context.writing = CountDownLatch(1)
        context.release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit { store.create("one", profile(51820)) }
            assertTrue(context.writing!!.await(5, TimeUnit.SECONDS))
            val secondStarted = CountDownLatch(1)
            val second = executor.submit {
                secondStarted.countDown()
                assertThrows(IOException::class.java) { store.create("one", profile(51821)) }
            }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { second.get(500, TimeUnit.MILLISECONDS) }
            context.release!!.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertEquals(profile(51820).toWgQuickString(), store.load("one").toWgQuickString())
        } finally {
            context.release!!.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun deleteWaitsForSaveAndCannotResurrectProfile() {
        store.create("one", profile(51820))
        context.writing = CountDownLatch(1)
        context.release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val save = executor.submit { store.save("one", profile(51821)) }
            assertTrue(context.writing!!.await(5, TimeUnit.SECONDS))
            val deleteStarted = CountDownLatch(1)
            val delete = executor.submit {
                deleteStarted.countDown()
                store.delete("one")
            }
            assertTrue(deleteStarted.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { delete.get(500, TimeUnit.MILLISECONDS) }
            context.release!!.countDown()
            save.get(5, TimeUnit.SECONDS)
            delete.get(5, TimeUnit.SECONDS)
            assertTrue(store.enumerate().isEmpty())
        } finally {
            context.release!!.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun profile(port: Int): Config = Config.parse("""
        [Interface]
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        ListenPort = $port
        Address = 192.0.2.2/32, 2001:db8::2/128
    """.trimIndent().byteInputStream())

    private class WriteContext(base: Context, private val directory: File) : ContextWrapper(base) {
        var failWrite = false
        var writing: CountDownLatch? = null
        var release: CountDownLatch? = null
        override fun getFilesDir(): File = directory
        override fun fileList(): Array<String> = directory.list()!!
        override fun openFileOutput(name: String, mode: Int): FileOutputStream =
            object : FileOutputStream(File(directory, name)) {
                override fun write(bytes: ByteArray) {
                    if (writing?.count == 1L) {
                        writing!!.countDown()
                        if (release != null && !release!!.await(5, TimeUnit.SECONDS))
                            throw IOException("Write fixture timed out")
                    }
                    if (failWrite) {
                        super.write(bytes, 0, bytes.size / 2)
                        throw IOException("Injected partial write")
                    }
                    super.write(bytes)
                }
            }
    }

    @Implements(Os::class)
    class RenameShadow private constructor() {
        companion object {
            @JvmField var fail = false
            @Implementation
            @JvmStatic
            fun rename(oldPath: String, newPath: String) {
                if (fail || !File(oldPath).renameTo(File(newPath)))
                    throw ErrnoException("rename", OsConstants.EIO)
            }
        }
    }
}
