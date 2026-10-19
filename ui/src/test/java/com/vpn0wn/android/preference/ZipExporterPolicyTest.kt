package com.vpn0wn.android.preference

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.RestrictionsManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.vpn0wn.android.R
import com.vpn0wn.android.activity.SettingsActivity
import com.vpn0wn.android.activity.SettingsActivityTest
import com.vpn0wn.android.activity.AppDataTransferSettingsTest.Authentication
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.android.util.BiometricAuthenticator
import com.vpn0wn.android.util.RootShell
import com.vpn0wn.android.util.ToolsInstaller
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], shadows = [
    SettingsActivityTest.KernelSupport::class,
    SettingsActivityTest.InstalledTools::class,
    Authentication::class,
])
@OptIn(ExperimentalCoroutinesApi::class)
class ZipExporterPolicyTest {
    @get:Rule val folder = TemporaryFolder()
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun restrictionBeforeClickDoesNotAuthenticateOrCreateOutput() = runTest {
        withEnvironment { env ->
            val fixture = settings()
            restrict(env, true)

            fixture.preference.performClick()
            runCurrent()

            assertTrue(Authentication.callbacks.isEmpty())
            assertEquals(0, fixture.provider.inserts)
            assertTrue(fixture.preference.isEnabled)
        }
    }

    @Test
    fun restrictionDuringAuthenticationPreventsOutputAndRestoresControl() = runTest {
        withEnvironment { env ->
            env.manager.create("one", TunnelTestEnvironment.profile())
            val fixture = settings()
            fixture.preference.performClick()
            restrict(env, true)

            Authentication.callbacks.single()(BiometricAuthenticator.Result.Success(null))
            settle(fixture)

            assertEquals(0, fixture.provider.inserts)
            assertTrue(fixture.preference.isEnabled)
        }
    }

    @Test
    fun restrictionAfterAuthenticationBeforeCoroutinePreventsOutput() = runTest {
        withEnvironment { env ->
            env.manager.create("one", TunnelTestEnvironment.profile())
            val fixture = settings()
            fixture.preference.performClick()
            Authentication.callbacks.single()(BiometricAuthenticator.Result.Success(null))
            restrict(env, true)

            settle(fixture)

            assertEquals(0, fixture.provider.inserts)
            assertTrue(fixture.preference.isEnabled)
        }
    }

    @Test
    fun restrictionDuringConfigLoadingPreventsOutput() = runTest {
        withEnvironment { env ->
            blockedLoad(env) { release, entered ->
                val fixture = settings()
                fixture.preference.performClick()
                Authentication.callbacks.single()(BiometricAuthenticator.Result.Success(null))
                runCurrent()
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                restrict(env, true)
                release.countDown()

                settle(fixture)

                assertEquals(0, fixture.provider.inserts)
                assertTrue(fixture.preference.isEnabled)
            }
        }
    }

    @Test
    fun restrictionAtStreamAcquisitionWritesNoSecretsAndCleansOwnedOutput() = runTest {
        withEnvironment { env ->
            env.manager.create("one", TunnelTestEnvironment.profile())
            val fixture = settings()
            fixture.provider.onOpened = { restrict(env, true) }
            fixture.preference.performClick()
            Authentication.callbacks.single()(BiometricAuthenticator.Result.Success(null))

            settle(fixture)

            assertEquals(1, fixture.provider.inserts)
            assertEquals(1, fixture.provider.deletes)
            assertEquals(0L, fixture.provider.bytesAtDeletion)
            assertTrue(fixture.preference.isEnabled)
            assertEquals(fixture.activity.getString(R.string.zip_export_summary), fixture.preference.summary)
        }
    }

    @Test
    fun failedSampleKeepsSamePromptAndDuplicateSuccessExportsOnce() = runTest {
        withEnvironment { env ->
            val profile = TunnelTestEnvironment.profile()
            env.manager.create("one", profile)
            env.manager.create("two", profile)
            val fixture = settings()
            fixture.preference.performClick()
            val callback = Authentication.callbacks.single()
            callback(BiometricAuthenticator.Result.Failure(null, "Synthetic failed sample"))
            fixture.preference.performClick()
            assertEquals(1, Authentication.callbacks.size)
            assertFalse(fixture.preference.isEnabled)
            callback(BiometricAuthenticator.Result.Success(null))
            callback(BiometricAuthenticator.Result.Success(null))

            settle(fixture)

            assertEquals(1, fixture.provider.inserts)
            assertTrue(fixture.preference.isEnabled)
            ZipFile(fixture.provider.file).use { zip ->
                assertEquals(listOf("one.conf", "two.conf"), zip.entries().asSequence().map { it.name }.toList())
                for (name in listOf("one.conf", "two.conf")) {
                    assertEquals(profile.toWgQuickString(), zip.getInputStream(zip.getEntry(name)).bufferedReader().use { it.readText() })
                }
            }
        }
    }

    @Test
    fun cancellationAndTerminalFailureDoNotExportButNoLockStillWorks() = runTest {
        withEnvironment { env ->
            env.manager.create("one", TunnelTestEnvironment.profile())
            val fixture = settings()
            for (result in listOf(BiometricAuthenticator.Result.Cancelled,
                BiometricAuthenticator.Result.Failure(1, "Synthetic terminal failure"))) {
                fixture.preference.performClick()
                Authentication.callbacks.last()(result)
                assertTrue(fixture.preference.isEnabled)
                assertEquals(0, fixture.provider.inserts)
            }
            fixture.preference.performClick()
            Authentication.callbacks.last()(BiometricAuthenticator.Result.NoAuthenticationConfigured)

            settle(fixture)

            assertEquals(1, fixture.provider.inserts)
            assertTrue(fixture.preference.isEnabled)
        }
    }

    @Test
    fun authenticationForDestroyedViewCannotExportIntoReplacementView() = runTest {
        withEnvironment { env ->
            env.manager.create("one", TunnelTestEnvironment.profile())
            val fixture = settings()
            fixture.preference.performClick()
            val callback = Authentication.callbacks.single()
            val manager = fixture.activity.supportFragmentManager
            manager.beginTransaction().detach(fixture.fragment).commitNow()
            manager.beginTransaction().attach(fixture.fragment).commitNow()
            runCurrent()
            val currentPreference = checkNotNull(fixture.fragment.findPreference<Preference>("zip_exporter"))
            val replacement = fixture.copy(preference = currentPreference,
                steadyViewJobs = fixture.fragment.viewLifecycleOwner.lifecycleScope.coroutineContext[Job]!!.children.toSet())

            callback(BiometricAuthenticator.Result.Success(null))
            settle(replacement)

            assertEquals(0, fixture.provider.inserts)
            assertTrue(currentPreference.isEnabled)
            currentPreference.performClick()
            assertEquals(2, Authentication.callbacks.size)
            callback(BiometricAuthenticator.Result.Success(null))
            assertFalse(currentPreference.isEnabled)
            Authentication.callbacks.last()(BiometricAuthenticator.Result.Success(null))
            settle(replacement)
            assertEquals(1, fixture.provider.inserts)
        }
    }

    @Test
    fun destroyedViewCancelsPendingConfigLoadWithoutOutput() = runTest {
        withEnvironment { env ->
            blockedLoad(env) { release, entered ->
                val fixture = settings()
                fixture.preference.performClick()
                Authentication.callbacks.single()(BiometricAuthenticator.Result.Success(null))
                runCurrent()
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                val export = fixture.fragment.viewLifecycleOwner.lifecycleScope.coroutineContext[Job]!!.children
                    .filter { it !in fixture.steadyViewJobs }.single()
                fixture.activity.supportFragmentManager.beginTransaction().detach(fixture.fragment).commitNow()
                release.countDown()
                export.join()

                assertTrue(export.isCancelled)
                assertEquals(0, fixture.provider.inserts)
            }
        }
    }

    private val controllers = mutableListOf<ActivityController<SettingsActivity>>()

    private suspend fun TestScope.withEnvironment(block: suspend (TunnelTestEnvironment) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        Authentication.callbacks.clear()
        Authentication.calls = 0
        Authentication.autoCancel = false
        Authentication.expectedTitleRes = R.string.biometric_prompt_zip_exporter_title
        SettingsActivityTest.KernelSupport.available = false
        val env = TunnelTestEnvironment(folder.root, backgroundScope)
        val shell = object : RootShell(env.application) { override fun start() = Unit }
        com.vpn0wn.android.Application::class.java.getDeclaredField("rootShell").apply { isAccessible = true }.set(env.application, shell)
        com.vpn0wn.android.Application::class.java.getDeclaredField("toolsInstaller").apply { isAccessible = true }
            .set(env.application, ToolsInstaller(env.application, shell))
        restrict(env, false)
        try {
            block(env)
        } finally {
            try {
                controllers.forEach { it.pause().stop().destroy() }
            } finally {
                controllers.clear()
                env.close()
            }
        }
    }

    private suspend fun TestScope.settings(): Fixture {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        controllers += controller
        val activity = controller.get().apply { setTheme(R.style.AppTheme) }
        controller.setup()
        activity.supportFragmentManager.executePendingTransactions()
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_content) as SettingsActivity.SettingsFragment
        runCurrent()
        fragment.lifecycleScope.coroutineContext[Job]!!.children.toList().joinAll()
        activity.lifecycleScope.coroutineContext[Job]!!.children.toList().joinAll()
        val provider = ExportProvider(folder.newFile()).also {
            it.attachInfo(activity, ProviderInfo().apply { authority = "media" })
            ShadowContentResolver.registerProviderInternal("media", it)
        }
        val steadyViewJobs = fragment.viewLifecycleOwner.lifecycleScope.coroutineContext[Job]!!.children.toSet()
        return Fixture(activity, fragment, checkNotNull(fragment.findPreference("zip_exporter")), provider, steadyViewJobs)
    }

    private suspend fun TestScope.settle(fixture: Fixture) {
        runCurrent()
        fixture.fragment.viewLifecycleOwner.lifecycleScope.coroutineContext[Job]!!.children
            .filter { it !in fixture.steadyViewJobs }.toList().joinAll()
        runCurrent()
    }

    private suspend fun blockedLoad(env: TunnelTestEnvironment, block: suspend (CountDownLatch, CountDownLatch) -> Unit) {
        env.store.configs["one"] = TunnelTestEnvironment.profile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        env.store.loadEntered["one"] = entered
        env.store.releaseLoad["one"] = release
        env.manager.getTunnels()
        try {
            block(release, entered)
        } finally {
            release.countDown()
        }
    }

    private fun restrict(env: TunnelTestEnvironment, disabled: Boolean) {
        val restrictions = env.application.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
        shadowOf(restrictions).setApplicationRestrictions(Bundle().apply { putBoolean("disable_config_export", disabled) })
    }

    private data class Fixture(val activity: SettingsActivity, val fragment: SettingsActivity.SettingsFragment,
        val preference: Preference, val provider: ExportProvider, val steadyViewJobs: Set<Job>)

    private class ExportProvider(val file: File) : ContentProvider() {
        private val outputUri = Uri.parse("content://media/external/downloads/1")
        var inserts = 0
        var deletes = 0
        var bytesAtDeletion: Long? = null
        var onOpened: (() -> Unit)? = null
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/zip"
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserts++
            return outputUri
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            onOpened?.invoke()
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(arrayOf(MediaStore.MediaColumns.DATA)).apply { addRow(arrayOf(file.absolutePath)) }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            if (uri != outputUri) return 0
            deletes++
            bytesAtDeletion = file.length()
            return if (file.delete()) 1 else 0
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
