package com.vpn0wn.android.preference

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceFragmentCompat
import com.vpn0wn.android.R
import com.vpn0wn.android.activity.SettingsActivity
import com.vpn0wn.android.activity.SettingsActivityTest
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.android.util.RootShell
import com.vpn0wn.android.util.ToolsInstaller
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
@Config(application = Application::class, sdk = [29], shadows = [SettingsActivityTest.KernelSupport::class, SettingsActivityTest.InstalledTools::class])
@OptIn(ExperimentalCoroutinesApi::class)
class ZipExporterPreferenceTest {
    @get:Rule val folder = TemporaryFolder()
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun successfulExportPreservesProfilesAndRestoresControl() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.root, backgroundScope).also(::prepareSettings)
        val profile = TunnelTestEnvironment.profile()
        env.manager.create("one", profile)
        env.manager.create("two", profile)
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.AppTheme) }
        controller.setup()
        val output = folder.newFile("export.zip")
        val provider = ExportProvider(output, writable = true)
        provider.attachInfo(activity, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        val preference = ZipExporterPreference(activity, null)
        runCurrent()
        try {
            val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_content) as PreferenceFragmentCompat
            val ownerScope = fragment.viewLifecycleOwner.lifecycleScope
            val priorJobs = ownerScope.coroutineContext[Job]!!.children.toSet()
            preference.isEnabled = false
            ZipExporterPreference::class.java.getDeclaredMethod("exportZip", PreferenceFragmentCompat::class.java, View::class.java)
                .apply { isAccessible = true }.invoke(preference, fragment, fragment.requireView())
            ownerScope.coroutineContext[Job]!!.children.single { it !in priorJobs }.join()
            assertTrue(preference.isEnabled)
            assertEquals(activity.getString(R.string.zip_export_success, output.absolutePath), preference.summary)
            assertEquals("vpn0wn-export.zip", provider.insertedValues?.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
            assertEquals("application/zip", provider.insertedValues?.getAsString(MediaStore.MediaColumns.MIME_TYPE))
            ZipFile(output).use { zip ->
                assertEquals(listOf("one.conf", "two.conf"), zip.entries().asSequence().map { it.name }.toList())
                for (name in listOf("one.conf", "two.conf"))
                    assertEquals(profile.toWgQuickString(), zip.getInputStream(zip.getEntry(name)).bufferedReader().use { it.readText() })
            }
            assertEquals(0, provider.cleanupAttempts)
        } finally {
            try {
                controller.destroy()
            } finally {
                env.close()
            }
        }
    }

    @Test
    fun failedWriteRetainsPrimaryFailureAndCleanupErrorAndRestoresControl() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.root, backgroundScope).also(::prepareSettings)
        env.manager.create("one", TunnelTestEnvironment.profile())
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.AppTheme) }
        controller.setup()
        val provider = ExportProvider(folder.newFile("export.zip"))
        provider.attachInfo(activity, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        val preference = ZipExporterPreference(activity, null)
        runCurrent()
        ShadowLog.clear()
        try {
            val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_content) as PreferenceFragmentCompat
            val ownerScope = fragment.viewLifecycleOwner.lifecycleScope
            val priorJobs = ownerScope.coroutineContext[Job]!!.children.toSet()
            preference.isEnabled = false
            ZipExporterPreference::class.java.getDeclaredMethod("exportZip", PreferenceFragmentCompat::class.java, View::class.java)
                .apply { isAccessible = true }.invoke(preference, fragment, fragment.requireView())
            val export = ownerScope.coroutineContext[Job]!!.children.single { it !in priorJobs }
            export.join()
            assertTrue(preference.isEnabled)
            assertEquals(activity.getString(R.string.zip_export_summary), preference.summary)
            assertEquals(1, provider.cleanupAttempts)
            val failure = ShadowLog.getLogsForTag("vpn0wn/ZipExporterPreference").single().throwable
            assertTrue(failure is IOException)
            assertTrue(generateSequence(failure) { it.cause }.any { primary ->
                primary.suppressed.any { it is IOException && it.message == "fixture delete failure" }
            })
            assertFalse(failure!!.message == "fixture delete failure")
        } finally {
            try {
                controller.destroy()
            } finally {
                env.close()
            }
        }
    }

    @Test
    fun cancelledConfigLoadingSettlesChildrenWithoutSuccessAndRestoresControl() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.root, backgroundScope).also(::prepareSettings)
        env.store.configs["one"] = TunnelTestEnvironment.profile()
        env.store.configs["two"] = TunnelTestEnvironment.profile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        env.store.loadEntered["one"] = entered
        env.store.releaseLoad["one"] = release
        env.manager.getTunnels()
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.AppTheme) }
        controller.setup()
        val preference = ZipExporterPreference(activity, null)
        runCurrent()
        ShadowLog.clear()
        try {
            val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_content) as PreferenceFragmentCompat
            val ownerScope = fragment.viewLifecycleOwner.lifecycleScope
            val priorJobs = ownerScope.coroutineContext[Job]!!.children.toSet()
            preference.isEnabled = false
            ZipExporterPreference::class.java.getDeclaredMethod("exportZip", PreferenceFragmentCompat::class.java, View::class.java)
                .apply { isAccessible = true }.invoke(preference, fragment, fragment.requireView())
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            val export = ownerScope.coroutineContext[Job]!!.children.single { it !in priorJobs }
            export.cancel()
            runCurrent()
            assertFalse(export.isCompleted)
            release.countDown()
            export.join()
            assertTrue(export.isCancelled)
            assertTrue(export.children.none())
            assertTrue(preference.isEnabled)
            assertEquals(activity.getString(R.string.zip_export_summary), preference.summary)
            assertTrue(ShadowLog.getLogsForTag("vpn0wn/ZipExporterPreference").isEmpty())
        } finally {
            release.countDown()
            try {
                controller.destroy()
            } finally {
                env.close()
            }
        }
    }

    private fun prepareSettings(env: TunnelTestEnvironment) {
        SettingsActivityTest.KernelSupport.available = false
        val shell = object : RootShell(env.application) { override fun start() = Unit }
        com.vpn0wn.android.Application::class.java.getDeclaredField("rootShell").apply { isAccessible = true }.set(env.application, shell)
        com.vpn0wn.android.Application::class.java.getDeclaredField("toolsInstaller").apply { isAccessible = true }
            .set(env.application, ToolsInstaller(env.application, shell))
        val restrictions = env.application.getSystemService(Context.RESTRICTIONS_SERVICE) as android.content.RestrictionsManager
        org.robolectric.Shadows.shadowOf(restrictions).setApplicationRestrictions(android.os.Bundle())
    }

    private class ExportProvider(private val file: File, private val writable: Boolean = false) : ContentProvider() {
        private val uri = Uri.parse("content://media/external/downloads/1")
        var cleanupAttempts = 0
        var insertedValues: ContentValues? = null
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/zip"
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            insertedValues = values?.let { ContentValues(it) }
            return this.uri
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(file, if (writable) ParcelFileDescriptor.MODE_WRITE_ONLY else ParcelFileDescriptor.MODE_READ_ONLY)
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
