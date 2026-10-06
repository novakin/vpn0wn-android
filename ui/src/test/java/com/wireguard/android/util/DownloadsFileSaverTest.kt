package com.wireguard.android.util

import android.app.Application
import android.app.Activity
import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class DownloadsFileSaverTest {
    @get:Rule val folder = TemporaryFolder()
    @After fun resetDispatcher() = Dispatchers.resetMain()

    @Test
    fun metadataFailureRemovesAcquiredRecord() = runTest {
        withProvider { saver, provider ->
            provider.queryFailure = IOException("fixture metadata failure")
            val failure = failureOf { saver.save("download.zip", "application/zip", false) { it.write(1) } }
            assertEquals("fixture metadata failure", failure.message)
            assertEquals(1, provider.cleanupAttempts)
            assertFalse(provider.file.exists())
            assertDescriptorClosed(provider)
        }
    }

    @Test
    fun streamAcquisitionFailureRemovesInsertedRecord() = runTest {
        withProvider { saver, provider ->
            provider.openFailure = IOException("fixture open failure")
            val failure = failureOf { saver.save("download.zip", null, false) {} }
            assertEquals("fixture open failure", failure.message)
            assertEquals(1, provider.cleanupAttempts)
            assertFalse(provider.file.exists())
        }
    }

    @Test
    fun nullStreamRemovesInsertedRecord() = runTest {
        withProvider { saver, provider ->
            provider.nullStream = true
            assertTrue(failureOf { saver.save("download.zip", null, false) {} } is IOException)
            assertEquals(1, provider.cleanupAttempts)
        }
    }

    @Test
    fun nullInsertDoesNotRunWriterOrDeleteUnownedOutput() = runTest {
        withProvider { saver, provider ->
            provider.nullInsert = true
            var wrote = false
            assertTrue(failureOf { saver.save("download.zip", null, false) { wrote = true } } is IOException)
            assertFalse(wrote)
            assertEquals(0, provider.cleanupAttempts)
        }
    }

    @Test
    fun successfulWriteClosesStreamAndPreservesContentsAndOverwriteRequest() = runTest {
        withProvider { saver, provider ->
            var stream: OutputStream? = null
            val path = saver.save("download.zip", "application/zip", true) {
                stream = it
                it.write("fixture".toByteArray())
            }
            assertEquals(provider.file.absolutePath, path)
            assertEquals("fixture", provider.file.readText())
            assertEquals(0, provider.cleanupAttempts)
            assertEquals(listOf("download.zip"), provider.overwriteNames)
            assertTrue(provider.cursors.all { it.isClosed })
            assertClosed(stream!!)
        }
    }

    @Test
    fun missingDataColumnFallsBackToDisplayNameAndClosesBothCursors() = runTest {
        withProvider { saver, provider ->
            provider.pathAvailable = false
            assertEquals("Download/download.zip", saver.save("download.zip", null, false) { it.write(1) })
            assertEquals(2, provider.cursors.size)
            assertTrue(provider.cursors.all { it.isClosed })
        }
    }

    @Test
    fun displayNameQueryFailureClosesStreamAndDeletesRecord() = runTest {
        withProvider { saver, provider ->
            provider.pathAvailable = false
            provider.displayFailure = IOException("fixture display failure")
            assertEquals("fixture display failure", failureOf { saver.save("download.zip", null, false) {} }.message)
            assertEquals(1, provider.cleanupAttempts)
            assertTrue(provider.cursors.single().isClosed)
            assertDescriptorClosed(provider)
        }
    }

    @Test
    fun failedWritePreservesPrimaryErrorAndThrownCleanupFailure() = runTest {
        withProvider { saver, provider ->
            provider.deleteFailure = IOException("fixture cleanup failure")
            var stream: OutputStream? = null
            val failure = failureOf {
                saver.save("download.zip", null, false) {
                    stream = it
                    throw AssertionError("fixture writer defect")
                }
            }
            assertTrue(failure is AssertionError)
            assertEquals("fixture writer defect", failure.message)
            assertCleanupFailure(failure, "fixture cleanup failure")
            assertClosed(stream!!)
        }
    }

    @Test
    fun zeroResultDeletionIsAttachedToPrimaryWriteFailure() = runTest {
        withProvider { saver, provider ->
            provider.deleteResult = 0
            val failure = failureOf { saver.save("download.zip", null, false) { throw IOException("fixture write failure") } }
            assertEquals("fixture write failure", failure.message)
            assertCleanupFailure(failure, "Incomplete download was not removed")
            assertTrue(provider.file.exists())
        }
    }

    @Test
    fun cancellationDuringMetadataClosesAndDeletesBeforeWriterRuns() = runTest {
        withProvider { saver, provider ->
            var wrote = false
            var failure: Throwable? = null
            val job = launch {
                val owner = coroutineContext[Job]!!
                provider.onQuery = { owner.cancel() }
                failure = failureOf { saver.save("download.zip", null, false) { wrote = true } }
            }
            job.join()
            assertTrue(failure is CancellationException)
            assertFalse(wrote)
            assertEquals(1, provider.cleanupAttempts)
            assertDescriptorClosed(provider)
        }
    }

    @Test
    fun cancellationAfterWritingDeletesOutputAndRetainsCleanupFailure() = runTest {
        withProvider { saver, provider ->
            provider.deleteFailure = IOException("fixture cleanup failure")
            var failure: Throwable? = null
            var stream: OutputStream? = null
            val job = launch {
                val owner = coroutineContext[Job]!!
                failure = failureOf {
                    saver.save("download.zip", null, false) {
                        stream = it
                        it.write(1)
                        owner.cancel()
                    }
                }
            }
            job.join()
            assertTrue(failure is CancellationException)
            assertTrue(generateSequence(failure) { it.cause }.any { it.suppressed.any { cleanup -> cleanup.message == "fixture cleanup failure" } })
            assertClosed(stream!!)
            assertEquals(1, provider.cleanupAttempts)
        }
    }

    @Test
    @Config(sdk = [28])
    fun legacySuccessfulOverwriteClosesStreamAndPreservesCompleteFile() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        withLegacy { saver, activity, directory ->
            shadowOf(activity).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            val file = File(directory, "download.zip").apply { writeText("old") }
            var stream: OutputStream? = null
            assertEquals(file.absolutePath, saver.save(file.name, null, true) {
                stream = it
                it.write("new".toByteArray())
            })
            assertEquals("new", file.readText())
            assertClosed(stream!!)
        }
    }

    @Test
    @Config(sdk = [28])
    fun legacyWriteFailureClosesAndRemovesOutput() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        withLegacy { saver, activity, directory ->
            shadowOf(activity).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            var stream: OutputStream? = null
            val failure = failureOf {
                saver.save("download.zip", null, true) {
                    stream = it
                    it.write(1)
                    throw IOException("fixture write failure")
                }
            }
            assertEquals("fixture write failure", failure.message)
            assertFalse(File(directory, "download.zip").exists())
            assertClosed(stream!!)
        }
    }

    @Test
    @Config(sdk = [28])
    fun legacyOpenFailurePreservesPreexistingDirectory() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        withLegacy { saver, activity, directory ->
            shadowOf(activity).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            val existing = File(directory, "download.zip").apply { mkdir() }
            assertTrue(failureOf { saver.save(existing.name, null, true) {} } is IOException)
            assertTrue(existing.isDirectory)
        }
    }

    @Test
    @Config(sdk = [28])
    fun legacyCancellationClosesAndRemovesOutput() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        withLegacy { saver, activity, directory ->
            shadowOf(activity).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            var stream: OutputStream? = null
            var failure: Throwable? = null
            val job = launch {
                val owner = coroutineContext[Job]!!
                failure = failureOf {
                    saver.save("download.zip", null, true) {
                        stream = it
                        it.write(1)
                        owner.cancel()
                    }
                }
            }
            job.join()
            assertTrue(failure is CancellationException)
            assertFalse(File(directory, "download.zip").exists())
            assertClosed(stream!!)
        }
    }

    @Test
    @Config(sdk = [28])
    fun legacyFalseDeletionIsReportedWithoutReplacingWriteFailure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        withLegacy { saver, activity, directory ->
            shadowOf(activity).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            val failure = failureOf {
                saver.save("download.zip", null, true) {
                    File(directory, "download.zip").delete()
                    throw IOException("fixture write failure")
                }
            }
            assertEquals("fixture write failure", failure.message)
            assertCleanupFailure(failure, "Incomplete download was not removed")
        }
    }

    @Test
    @Config(sdk = [28])
    fun deniedPermissionCreatesNoFileAndCanBeRequestedAgain() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        withLegacy { saver, activity, directory ->
            shadowOf(activity).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            repeat(2) {
                var result: String? = "pending"
                var wrote = false
                val job = launch { result = saver.save("download.zip", null, true) { wrote = true } }
                runCurrent()
                val request = shadowOf(activity).lastRequestedPermission
                val denied = Intent().putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
                    .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS, intArrayOf(PackageManager.PERMISSION_DENIED))
                activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, denied)
                job.join()
                assertEquals(null, result)
                assertFalse(wrote)
                assertFalse(File(directory, "download.zip").exists())
            }
        }
    }

    @Test
    @Config(sdk = [28])
    fun cancelledPermissionWaitKeepsResultWithPendingRequestForRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        withLegacy { saver, activity, directory ->
            shadowOf(activity).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            val first = launch { saver.save("download.zip", null, true) { throw AssertionError("Cancelled writer ran") } }
            runCurrent()
            val request = shadowOf(activity).lastRequestedPermission
            first.cancelAndJoin()
            assertFalse(File(directory, "download.zip").exists())
            var result: String? = null
            val retry = launch { result = saver.save("download.zip", null, true) { it.write(1) } }
            runCurrent()
            assertSame(request, shadowOf(activity).lastRequestedPermission)
            val granted = Intent().putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
                .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS, intArrayOf(PackageManager.PERMISSION_GRANTED))
            shadowOf(activity).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, granted)
            retry.join()
            assertEquals(File(directory, "download.zip").absolutePath, result)
            assertTrue(File(directory, "download.zip").exists())
        }
    }

    private suspend fun withProvider(test: suspend (DownloadsFileSaver, OutputProvider) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val provider = OutputProvider(folder.newFile("download.zip"))
        provider.attachInfo(activity, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        try {
            test(DownloadsFileSaver(activity), provider)
        } finally {
            controller.destroy()
        }
    }

    private suspend fun withLegacy(test: suspend (DownloadsFileSaver, ComponentActivity, File) -> Unit) {
        ShadowEnvironment.setExternalStoragePublicDirectory(folder.newFolder("storage").toPath())
        @Suppress("DEPRECATION") val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        directory.mkdirs()
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val saver = DownloadsFileSaver(controller.get())
        controller.setup()
        try {
            test(saver, controller.get(), directory)
        } finally {
            controller.destroy()
        }
    }

    private suspend fun failureOf(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (failure: Throwable) {
            return failure
        }
        throw AssertionError("Expected operation failure")
    }

    private fun assertClosed(stream: OutputStream) {
        assertTrue(runCatching { stream.write(1) }.exceptionOrNull() is IOException)
    }

    private fun assertDescriptorClosed(provider: OutputProvider) {
        assertTrue(runCatching { provider.descriptor!!.fd }.exceptionOrNull() is IllegalStateException)
    }

    private fun assertCleanupFailure(failure: Throwable, message: String) {
        assertTrue(generateSequence(failure) { it.cause }.any { it.suppressed.any { cleanup -> cleanup is IOException && cleanup.message == message } })
    }

    private class OutputProvider(val file: File) : ContentProvider() {
        private val outputUri = Uri.parse("content://media/external/downloads/1")
        var queryFailure: IOException? = null
        var displayFailure: IOException? = null
        var openFailure: IOException? = null
        var deleteFailure: IOException? = null
        var deleteResult: Int? = null
        var nullInsert = false
        var nullStream = false
        var pathAvailable = true
        var onQuery: (() -> Unit)? = null
        var descriptor: ParcelFileDescriptor? = null
        val cursors = mutableListOf<Cursor>()
        val overwriteNames = mutableListOf<String>()
        var cleanupAttempts = 0
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/zip"
        override fun insert(uri: Uri, values: ContentValues?) = if (nullInsert) null else outputUri
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            openFailure?.let { throw it }
            if (nullStream) return null
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY).also { descriptor = it }
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            queryFailure?.let { throw it }
            onQuery?.invoke()
            val column = projection!!.single()
            if (column == MediaStore.MediaColumns.DISPLAY_NAME)
                displayFailure?.let { throw it }
            return MatrixCursor(arrayOf(column)).apply {
                addRow(arrayOf(if (column == MediaStore.MediaColumns.DATA) { if (pathAvailable) file.absolutePath else null } else "download.zip"))
                cursors.add(this)
            }
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            if (uri != outputUri) {
                overwriteNames.add(selectionArgs!!.single())
                return 0
            }
            cleanupAttempts++
            deleteFailure?.let { throw it }
            return deleteResult ?: if (file.delete()) 1 else 0
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
