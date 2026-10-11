package com.vpn0wn.android.activity

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.RestrictionsManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.vpn0wn.android.R
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.android.util.AppDataTransfer
import com.vpn0wn.android.util.BiometricAuthenticator
import com.vpn0wn.android.util.RootShell
import com.vpn0wn.android.util.ToolsInstaller
import com.vpn0wn.android.util.UserKnobs
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [SettingsActivityTest.KernelSupport::class, SettingsActivityTest.InstalledTools::class, AppDataTransferSettingsTest.Authentication::class])
@OptIn(ExperimentalCoroutinesApi::class)
class AppDataTransferSettingsTest {
    @get:Rule val folder = TemporaryFolder()
    private val controllers = mutableListOf<ActivityController<SettingsActivity>>()
    private lateinit var testEnvironment: TunnelTestEnvironment

    @Before
    fun resetPlatform() {
        SettingsActivityTest.KernelSupport.available = false
        Authentication.calls = 0
        Authentication.autoCancel = true
        Authentication.callbacks.clear()
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        controllers.forEach { it.pause().stop().destroy() }
        Dispatchers.resetMain()
    }

    @Test
    fun transferRowsReuseNativeLayoutAndDefaultTextAndLaunchZipDocumentPicker() = runTest {
        environment()
        val controller = openSettings()
        val activity = controller.get()
        settle(activity)
        val fragment = fragment(activity)
        val exporter = row(fragment, "export_app_data")
        val importer = row(fragment, "import_app_data")
        assertEquals(R.layout.vpn0wn_preference_item, exporter.layoutResource)
        assertEquals(R.layout.vpn0wn_preference_item, importer.layoutResource)
        assertEquals(activity.getString(R.string.app_data_transfer_export_title), exporter.title)
        assertEquals(activity.getString(R.string.app_data_transfer_export_summary), exporter.summary)
        assertEquals(activity.getString(R.string.app_data_transfer_import_title), importer.title)
        assertEquals(activity.getString(R.string.app_data_transfer_import_summary), importer.summary)
        assertFalse(exporter.isSingleLineTitle)
        assertFalse(importer.isSingleLineTitle)

        importer.performClick()
        val request = shadowOf(activity).nextStartedActivityForResult
        assertNotNull(request)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
        assertEquals(listOf("application/zip"), request.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)!!.toList())
        assertEquals("*/*", request.intent.type)
        activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null)
        assertTrue(importer.isEnabled)
        assertTrue(exporter.isEnabled)
    }

    @Test
    fun initialAdminRestrictionRemovesBothExportsAndKeepsTransferImport() = runTest {
        val env = environment()
        setExportRestriction(env, true)
        val activity = openSettings().get()
        settle(activity)

        val fragment = fragment(activity)
        assertNull(fragment.findPreference<Preference>("zip_exporter"))
        assertNull(fragment.findPreference<Preference>("export_app_data"))
        assertNotNull(fragment.findPreference<Preference>("import_app_data"))
        assertNotNull(fragment.findPreference<Preference>("log_viewer"))
    }

    @Test
    fun newlyAppliedAdminRestrictionRejectsExportBeforeAuthenticationOrBusyState() = runTest {
        val env = environment()
        val activity = openSettings().get()
        settle(activity)
        val fragment = fragment(activity)
        val exporter = row(fragment, "export_app_data")
        val importer = row(fragment, "import_app_data")
        setExportRestriction(env, true)

        exporter.performClick()
        runCurrent()

        assertEquals(0, Authentication.calls)
        assertTrue(exporter.isEnabled)
        assertTrue(importer.isEnabled)
        assertEquals(activity.getString(R.string.app_data_transfer_export_summary), exporter.summary)
        setExportRestriction(env, false)
        exporter.performClick()
        assertEquals(1, Authentication.calls)
        assertTrue(exporter.isEnabled)
        assertTrue(importer.isEnabled)
    }

    @Test
    @Config(sdk = [29])
    fun failedAuthenticationSampleAllowsSamePromptSuccessAndDuplicateSuccessExportsOnlyOnce() = runTest {
        val env = environment()
        env.manager.create("existing", TunnelTestEnvironment.profile())
        val activity = openSettings().get()
        settle(activity)
        val fragment = fragment(activity)
        val exporter = row(fragment, "export_app_data")
        val importer = row(fragment, "import_app_data")
        val output = outputProvider(activity)
        Authentication.autoCancel = false
        exporter.performClick()
        val callback = Authentication.callbacks.single()

        callback(BiometricAuthenticator.Result.Failure(null, "Synthetic failed sample"))

        assertFalse(exporter.isEnabled)
        assertFalse(importer.isEnabled)
        val owner = fragment.viewLifecycleOwner.lifecycleScope.coroutineContext[Job]!!
        val previousJobs = owner.children.toSet()
        callback(BiometricAuthenticator.Result.Success(null))
        val exporting = owner.children.single { it !in previousJobs }
        callback(BiometricAuthenticator.Result.Success(null))
        exporting.join()
        runCurrent()

        assertEquals(1, output.inserts)
        assertEquals(1, output.opens)
        assertTrue(exporter.isEnabled)
        assertTrue(importer.isEnabled)
        output.file.inputStream().use { input ->
            assertEquals(setOf("existing"), AppDataTransfer.read(input).profiles.keys)
        }
    }

    @Test
    @Config(sdk = [29])
    fun oldTerminalAuthenticationCallbackCannotConsumeNewerRequest() = runTest {
        environment()
        val activity = openSettings().get()
        settle(activity)
        val fragment = fragment(activity)
        val exporter = row(fragment, "export_app_data")
        val importer = row(fragment, "import_app_data")
        val output = outputProvider(activity)
        Authentication.autoCancel = false
        exporter.performClick()
        val old = Authentication.callbacks.single()
        old(BiometricAuthenticator.Result.Cancelled)
        exporter.performClick()
        val current = Authentication.callbacks.last()

        old(BiometricAuthenticator.Result.Success(null))
        runCurrent()

        assertEquals(2, Authentication.calls)
        assertEquals(0, output.inserts)
        assertFalse(exporter.isEnabled)
        assertFalse(importer.isEnabled)
        current(BiometricAuthenticator.Result.Cancelled)
        assertTrue(exporter.isEnabled)
        assertTrue(importer.isEnabled)
    }

    @Test
    @Config(sdk = [29])
    fun adminRestrictionAppliedAtOutputAcquisitionPreventsArchiveBytesAndCleansOwnedOutput() = runTest {
        val env = environment()
        env.manager.create("existing", TunnelTestEnvironment.profile())
        val activity = openSettings().get()
        settle(activity)
        val fragment = fragment(activity)
        val exporter = row(fragment, "export_app_data")
        val importer = row(fragment, "import_app_data")
        val output = outputProvider(activity)
        output.onOpened = { setExportRestriction(env, true) }
        Authentication.autoCancel = false
        exporter.performClick()
        val owner = fragment.viewLifecycleOwner.lifecycleScope.coroutineContext[Job]!!
        val previousJobs = owner.children.toSet()
        ShadowLog.clear()

        Authentication.callbacks.single()(BiometricAuthenticator.Result.Success(null))
        owner.children.single { it !in previousJobs }.join()
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, output.inserts)
        assertEquals(1, output.opens)
        assertEquals(1, output.deletes)
        assertEquals(0L, output.bytesAtDeletion)
        assertFalse(output.file.exists())
        assertEquals(setOf("existing"), env.store.configs.keys)
        assertEquals(activity.getString(R.string.app_data_transfer_export_summary), exporter.summary)
        assertTrue(exporter.isEnabled)
        assertTrue(importer.isEnabled)
        assertEquals(activity.getString(R.string.app_data_transfer_error),
            activity.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).text.toString())
        val logs = ShadowLog.getLogsForTag("vpn0wn/AppDataExporter")
        assertEquals(listOf("App data export failed"), logs.map { it.msg })
        assertTrue(logs.all { it.throwable == null })
    }

    @Test
    fun cancellingConfirmationAndClickingItsStaleButtonCannotConsumeNextConfirmation() = runTest {
        val env = environment()
        val activity = openSettings().get()
        settle(activity)
        val fragment = fragment(activity)
        val provider = provider(activity, archiveFile())
        val before = preferences().data.first()
        val read = chooseInput(activity, provider)
        assertFalse(row(fragment, "export_app_data").isEnabled)
        assertFalse(row(fragment, "import_app_data").isEnabled)
        read.join()
        val cancelled = dialog()
        assertTrue(cancelled.isShowing)
        assertTrue(env.store.configs.isEmpty())
        cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(cancelled.isShowing)
        chooseInput(activity, provider).join()
        val current = dialog()
        assertTrue(current.isShowing)

        cancelled.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        runCurrent()

        assertTrue(current.isShowing)
        assertTrue(env.store.configs.isEmpty())
        assertEquals(before.asMap(), preferences().data.first().asMap())
        assertTrue(row(fragment, "export_app_data").isEnabled)
        assertTrue(row(fragment, "import_app_data").isEnabled)
        current.dismiss()
    }

    @Test
    fun destroyingConfirmationOwnerDismissesDialogAndRejectsSavedPositiveButton() = runTest {
        val env = environment()
        val controller = openSettings()
        val activity = controller.get()
        settle(activity)
        chooseInput(activity, provider(activity, archiveFile())).join()
        val confirmation = dialog()
        val confirm = confirmation.getButton(AlertDialog.BUTTON_POSITIVE)
        val before = preferences().data.first()
        controller.pause().stop().destroy()
        controllers.remove(controller)

        assertFalse(confirmation.isShowing)
        confirm.performClick()
        runCurrent()

        assertTrue(env.store.configs.isEmpty())
        assertEquals(before.asMap(), preferences().data.first().asMap())
    }

    @Test
    fun stoppedConfirmationOwnerCannotAdmitImport() = runTest {
        val env = environment()
        val controller = openSettings()
        val activity = controller.get()
        settle(activity)
        chooseInput(activity, provider(activity, archiveFile())).join()
        val confirmation = dialog()
        controller.pause().stop()

        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        runCurrent()

        assertTrue(confirmation.isShowing)
        assertTrue(env.store.configs.isEmpty())
        controller.start().resume()
        confirmation.dismiss()
    }

    @Test
    fun confirmedImportPersistsPortableSettingsReloadsRowsAndLeavesNewProfileDown() = runTest {
        val env = environment()
        val controller = openSettings()
        val activity = controller.get()
        settle(activity)
        val fragment = fragment(activity)
        assertFalse(row(fragment, "allow_remote_control_intents").let { (it as SwitchPreferenceCompat).isChecked })
        chooseInput(activity, provider(activity, archiveFile())).join()
        val owner = fragment.viewLifecycleOwner.lifecycleScope.coroutineContext[Job]!!
        val previousJobs = owner.children.toSet()

        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        owner.children.single { it !in previousJobs }.join()
        runCurrent()

        assertTrue(UserKnobs.allowRemoteControlIntents.first())
        assertTrue(UserKnobs.multipleTunnels.first())
        assertTrue(UserKnobs.restoreOnBoot.first())
        assertTrue(UserKnobs.darkTheme.first())
        assertEquals(setOf("imported"), env.store.configs.keys)
        assertEquals(Tunnel.State.DOWN, env.manager.getTunnels()["imported"]!!.state)
        assertTrue(env.backend.getRunningTunnelNames().isEmpty())
        val saved = Bundle()
        controller.saveInstanceState(saved)
        assertEquals(1, saved.getInt("app_data_import_count", -1))
        controller.recreate()
        val reloaded = controller.get()
        settle(reloaded)
        assertTrue((row(fragment(reloaded), "allow_remote_control_intents") as SwitchPreferenceCompat).isChecked)
        assertTrue((row(fragment(reloaded), "dark_theme") as SwitchPreferenceCompat).isChecked)
    }

    @Test
    fun malformedInputReportsFixedFailureWithoutProfileWritesOrPayloadLogging() = runTest {
        val env = environment()
        val activity = openSettings().get()
        settle(activity)
        val payload = "synthetic-private-input-value"
        val file = folder.newFile("malformed.zip").apply { writeText(payload) }
        val before = preferences().data.first()
        ShadowLog.clear()

        chooseInput(activity, provider(activity, file)).join()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(env.store.configs.isEmpty())
        assertEquals(before.asMap(), preferences().data.first().asMap())
        assertEquals(activity.getString(R.string.app_data_transfer_error),
            activity.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).text.toString())
        val logs = ShadowLog.getLogsForTag("vpn0wn/Settings")
        assertEquals(listOf("App data import failed"), logs.map { it.msg })
        assertTrue(logs.all { it.throwable == null && payload !in it.msg })
        assertTrue(row(fragment(activity), "export_app_data").isEnabled)
        assertTrue(row(fragment(activity), "import_app_data").isEnabled)
    }

    @Test
    fun cancelledInputReadRemainsCancelledWithoutFailureFeedbackAndRestoresRows() = runTest {
        val env = environment()
        val activity = openSettings().get()
        settle(activity)
        val input = provider(activity, archiveFile())
        input.failure = CancellationException("Synthetic input cancellation")
        ShadowLog.clear()

        val read = chooseInput(activity, input)
        read.join()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(read.isCancelled)
        assertTrue(env.store.configs.isEmpty())
        assertTrue(ShadowLog.getLogsForTag("vpn0wn/Settings").isEmpty())
        assertNull(activity.findViewById<View>(com.google.android.material.R.id.snackbar_text))
        assertTrue(row(fragment(activity), "export_app_data").isEnabled)
        assertTrue(row(fragment(activity), "import_app_data").isEnabled)
    }

    @Test
    fun unexpectedInputErrorEscapesWithoutFixedFailureFeedback() {
        val failure = TransferDefect()
        val observed = assertThrows(TransferDefect::class.java) {
            runTest {
                val env = environment()
                val activity = openSettings().get()
                settle(activity)
                val input = provider(activity, archiveFile())
                input.failure = failure
                ShadowLog.clear()
                chooseInput(activity, input).join()
                runCurrent()
                assertTrue(env.store.configs.isEmpty())
                assertTrue(ShadowLog.getLogsForTag("vpn0wn/Settings").isEmpty())
                assertTrue(row(fragment(activity), "export_app_data").isEnabled)
                assertTrue(row(fragment(activity), "import_app_data").isEnabled)
            }
        }
        assertEquals(failure.javaClass, observed.javaClass)
        assertEquals(failure.message, observed.message)
        assertTrue(generateSequence(observed as Throwable) { it.cause }.any { it === failure })
    }

    private fun TestScope.environment(): TunnelTestEnvironment {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        testEnvironment = TunnelTestEnvironment(folder.root, backgroundScope)
        val shell = ProbeShell(testEnvironment.application)
        setApplicationField(testEnvironment, "rootShell", shell)
        setApplicationField(testEnvironment, "toolsInstaller", ToolsInstaller(testEnvironment.application, shell))
        setExportRestriction(testEnvironment, false)
        return testEnvironment
    }

    private fun openSettings(): ActivityController<SettingsActivity> {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        controllers += controller
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
        controller.get().supportFragmentManager.executePendingTransactions()
        return controller
    }

    private suspend fun TestScope.settle(activity: SettingsActivity) {
        activity.supportFragmentManager.executePendingTransactions()
        runCurrent()
        fragment(activity).lifecycleScope.coroutineContext[Job]!!.children.toList().joinAll()
        activity.lifecycleScope.coroutineContext[Job]!!.children.toList().joinAll()
        runCurrent()
    }

    private fun fragment(activity: SettingsActivity) =
        activity.supportFragmentManager.findFragmentById(R.id.settings_content) as SettingsActivity.SettingsFragment

    private fun row(fragment: SettingsActivity.SettingsFragment, key: String) =
        checkNotNull(fragment.findPreference<Preference>(key))

    private fun setExportRestriction(env: TunnelTestEnvironment, disabled: Boolean) {
        val restrictions = env.application.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
        shadowOf(restrictions).setApplicationRestrictions(Bundle().apply { putBoolean("disable_config_export", disabled) })
    }

    private fun setApplicationField(env: TunnelTestEnvironment, name: String, value: Any) {
        com.vpn0wn.android.Application::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(env.application, value)
    }

    private fun preferences() = com.vpn0wn.android.Application.getPreferencesDataStore()

    private fun archiveFile(): File = folder.newFile().apply {
        outputStream().use { output ->
            AppDataTransfer.write(AppDataTransfer.Archive(
                profiles = mapOf("imported" to TunnelTestEnvironment.profile()),
                settings = mapOf("dark_theme" to true, "allow_remote_control_intents" to true,
                    "multiple_tunnels" to true, "restore_on_boot" to true),
                lastUsedTunnel = "imported",
                servers = emptyList(),
            ), output)
        }
    }

    private fun provider(activity: SettingsActivity, file: File) = InputProvider(file).also {
        it.attachInfo(activity, ProviderInfo().apply { authority = "settings-transfer-fixture" })
        ShadowContentResolver.registerProviderInternal("settings-transfer-fixture", it)
    }

    private fun outputProvider(activity: SettingsActivity) = OutputProvider(folder.newFile()).also {
        it.attachInfo(activity, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", it)
    }

    private fun chooseInput(activity: SettingsActivity, provider: InputProvider): Job {
        val fragment = fragment(activity)
        val owner = fragment.viewLifecycleOwner.lifecycleScope.coroutineContext[Job]!!
        val previousJobs = owner.children.toSet()
        row(fragment, "import_app_data").performClick()
        val request = checkNotNull(shadowOf(activity).nextStartedActivityForResult)
        val uri = Uri.parse("content://settings-transfer-fixture/${provider.input.name}")
        assertTrue(activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent().setData(uri)))
        return owner.children.single { it !in previousJobs }
    }

    private fun dialog() = ShadowDialog.getLatestDialog() as AlertDialog

    private class InputProvider(val input: File) : ContentProvider() {
        var failure: Throwable? = null
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/zip"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            failure?.let { throw it }
            return ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private class TransferDefect : Error("Synthetic input defect")

    private class ProbeShell(context: Context) : RootShell(context) {
        var calls = 0
        override fun start() {
            calls++
        }
    }

    private class OutputProvider(val file: File) : ContentProvider() {
        var inserts = 0
        var opens = 0
        var deletes = 0
        var bytesAtDeletion: Long? = null
        var onOpened: (() -> Unit)? = null
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/zip"
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserts++
            return Uri.parse("content://media/external/downloads/1")
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            opens++
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY)
            onOpened?.invoke()
            return descriptor
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(arrayOf(MediaStore.MediaColumns.DATA)).apply { addRow(arrayOf(file.absolutePath)) }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            assertEquals(Uri.parse("content://media/external/downloads/1"), uri)
            deletes++
            bytesAtDeletion = file.length()
            return if (file.delete()) 1 else 0
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    @Implements(BiometricAuthenticator::class)
    class Authentication {
        @Implementation
        fun authenticate(dialogTitleRes: Int, fragment: Fragment, callback: (BiometricAuthenticator.Result) -> Unit) {
            assertEquals(R.string.app_data_transfer_auth_title, dialogTitleRes)
            assertTrue(fragment.isAdded)
            calls++
            callbacks += callback
            if (autoCancel) callback(BiometricAuthenticator.Result.Cancelled)
        }

        companion object {
            var calls = 0
            var autoCancel = true
            val callbacks = mutableListOf<(BiometricAuthenticator.Result) -> Unit>()
        }
    }
}
