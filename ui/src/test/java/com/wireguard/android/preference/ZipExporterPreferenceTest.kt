package com.wireguard.android.preference

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.R
import com.wireguard.android.activity.SettingsActivity
import com.wireguard.android.model.TunnelTestEnvironment
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class ZipExporterPreferenceTest {
    @get:Rule val folder = TemporaryFolder()
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun failedWriteRetainsPrimaryFailureAndCleanupErrorAndRestoresControl() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.root, backgroundScope)
        env.manager.create("one", TunnelTestEnvironment.profile())
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.AppTheme) }
        controller.create()
        val provider = FailingOutputProvider(folder.newFile("export.zip"))
        provider.attachInfo(activity, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        val preference = ZipExporterPreference(activity, null)
        runCurrent()
        ShadowLog.clear()
        try {
            val priorJobs = activity.lifecycleScope.coroutineContext[Job]!!.children.toSet()
            preference.isEnabled = false
            ZipExporterPreference::class.java.getDeclaredMethod("exportZip")
                .apply { isAccessible = true }.invoke(preference)
            val export = activity.lifecycleScope.coroutineContext[Job]!!.children.single { it !in priorJobs }
            export.join()
            assertTrue(preference.isEnabled)
            assertEquals(activity.getString(R.string.zip_export_summary), preference.summary)
            assertEquals(1, provider.cleanupAttempts)
            val failure = ShadowLog.getLogsForTag("WireGuard/ZipExporterPreference").single().throwable
            assertTrue(failure is IOException)
            assertTrue(generateSequence(failure) { it.cause }.any { primary ->
                primary.suppressed.any { it is IOException && it.message == "fixture delete failure" }
            })
            assertFalse(failure!!.message == "fixture delete failure")
        } finally {
            controller.destroy()
            env.application.onTerminate()
        }
    }

    @Test
    fun cancelledConfigLoadingSettlesChildrenWithoutSuccessAndRestoresControl() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.root, backgroundScope)
        env.store.configs["one"] = TunnelTestEnvironment.profile()
        env.store.configs["two"] = TunnelTestEnvironment.profile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        env.store.loadEntered["one"] = entered
        env.store.releaseLoad["one"] = release
        env.manager.getTunnels()
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.AppTheme) }
        controller.create()
        val preference = ZipExporterPreference(activity, null)
        runCurrent()
        ShadowLog.clear()
        try {
            val priorJobs = activity.lifecycleScope.coroutineContext[Job]!!.children.toSet()
            preference.isEnabled = false
            ZipExporterPreference::class.java.getDeclaredMethod("exportZip")
                .apply { isAccessible = true }.invoke(preference)
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            val export = activity.lifecycleScope.coroutineContext[Job]!!.children.single { it !in priorJobs }
            export.cancel()
            runCurrent()
            assertFalse(export.isCompleted)
            release.countDown()
            export.join()
            assertTrue(export.isCancelled)
            assertTrue(export.children.none())
            assertTrue(preference.isEnabled)
            assertEquals(activity.getString(R.string.zip_export_summary), preference.summary)
            assertTrue(ShadowLog.getLogsForTag("WireGuard/ZipExporterPreference").isEmpty())
        } finally {
            release.countDown()
            controller.destroy()
            env.application.onTerminate()
        }
    }

    private class FailingOutputProvider(private val file: File) : ContentProvider() {
        private val uri = Uri.parse("content://media/external/downloads/1")
        var cleanupAttempts = 0
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/zip"
        override fun insert(uri: Uri, values: ContentValues?) = this.uri
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(arrayOf(MediaStore.MediaColumns.DATA)).apply { addRow(arrayOf(file.absolutePath)) }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            if (uri == this.uri) {
                cleanupAttempts++
                throw IOException("fixture delete failure")
            }
            return 0
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
