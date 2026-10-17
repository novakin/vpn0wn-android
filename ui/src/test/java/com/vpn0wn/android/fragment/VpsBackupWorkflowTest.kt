/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.fragment

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.DialogInterface
import android.content.RestrictionsManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.vpn0wn.android.Application as Vpn0wnApplication
import com.vpn0wn.android.R
import com.vpn0wn.android.activity.AppDataTransferSettingsTest
import com.vpn0wn.android.provisioning.ProvisioningJson
import com.vpn0wn.android.provisioning.StoredVpsServer
import com.vpn0wn.android.provisioning.VpsBackupArchive
import com.vpn0wn.android.provisioning.VpsBackupExportFixture
import com.vpn0wn.android.provisioning.VpsCredential
import com.vpn0wn.android.provisioning.VpsCredentialFileTest
import com.vpn0wn.android.provisioning.VpsProfileFixture
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel
import com.vpn0wn.android.provisioning.VpsUserProfileFileTest
import com.vpn0wn.android.util.BiometricAuthenticator
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

// Backup crypto and SDK sandboxes stay in a separate worker from the existing profile workflow matrix.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class VpsBackupWorkflowTest {
    @get:Rule val folder = TemporaryFolder()

    @After fun tearDown() { Dispatchers.resetMain() }

    @Before fun resetAuthentication() {
        AppDataTransferSettingsTest.Authentication.calls = 0
        AppDataTransferSettingsTest.Authentication.autoCancel = false
        AppDataTransferSettingsTest.Authentication.expectedTitleRes = R.string.vpn0wn_backup_authenticate
        AppDataTransferSettingsTest.Authentication.callbacks.clear()
    }

    @Test
    @Config(shadows = [VpsCredentialFileTest.NativeFiles::class, AppDataTransferSettingsTest.Authentication::class])
    fun backupPasswordsStayMaskedUntilExplicitlyShownAndRemaskTogether() = runTest {
        VpsUserProfileFileTest.resetNativeFiles()
        val fixture = fixture()
        try {
            prepareBackup(fixture)
            click(fixture, R.string.vpn0wn_action_backup)
            AppDataTransferSettingsTest.Authentication.callbacks.last()(BiometricAuthenticator.Result.NoAuthenticationConfigured)
            settle()
            val dialog = requireNotNull(backupDialog(fixture))
            val inputs = listOf(dialog.findViewById<EditText>(R.id.vpn0wn_backup_password)!!,
                dialog.findViewById<EditText>(R.id.vpn0wn_backup_confirm_password)!!)
            for (input in inputs) {
                input.setText(VpsBackupExportFixture.PASSWORD)
                assertPasswordMasked(input)
            }
            val reveal = text(dialog.window!!.decorView, fixture.activity.getString(R.string.vpn0wn_backup_show_passwords)) as CheckBox
            reveal.performClick()
            for (input in inputs) {
                assertNull(input.transformationMethod)
                assertEquals(VpsBackupExportFixture.PASSWORD, input.text.toString())
            }
            reveal.performClick()
            inputs.forEach(::assertPasswordMasked)
            assertFalse(fixture.peer.commands.any { it.contains("./'backup.sh'") })
        } finally { fixture.close() }
    }

    @Test
    @Config(sdk = [29], shadows = [VpsCredentialFileTest.NativeFiles::class, AppDataTransferSettingsTest.Authentication::class])
    fun backupExportRequiresAuthenticationAndConfirmedPasswordThenWritesRecoverableCiphertext() = runTest {
        VpsUserProfileFileTest.resetNativeFiles()
        val fixture = fixture()
        try {
            prepareBackup(fixture)
            val output = BackupOutputProvider(folder.newFile()).apply { attachInfo(fixture.activity, ProviderInfo().apply { authority = "media" }) }
            ShadowContentResolver.registerProviderInternal("media", output)
            click(fixture, R.string.vpn0wn_action_backup)
            val callback = AppDataTransferSettingsTest.Authentication.callbacks.last()
            callback(BiometricAuthenticator.Result.Failure(null, "Synthetic failed sample"))
            settle()
            assertNull(backupDialog(fixture))
            assertEquals(0, output.inserts)
            callback(BiometricAuthenticator.Result.Success(null))
            settle()
            val dialog = requireNotNull(backupDialog(fixture))
            val password = dialog.findViewById<EditText>(R.id.vpn0wn_backup_password)!!
            val repeated = dialog.findViewById<EditText>(R.id.vpn0wn_backup_confirm_password)!!
            assertFalse(password.isSaveEnabled)
            assertFalse(repeated.isSaveEnabled)
            password.setText(VpsBackupExportFixture.PASSWORD)
            repeated.setText("different synthetic password")
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            assertNotNull(repeated.error)
            assertFalse(fixture.peer.commands.any { it.contains("./'backup.sh'") })
            repeated.setText(VpsBackupExportFixture.PASSWORD)
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            finishBackup(fixture)
            assertEquals(1, output.inserts)
            assertTrue(output.name!!.endsWith(".vpn0wnbackup"))
            assertEquals(VpsBackupExportFixture.snapshot, VpsBackupArchive().decrypt(output.file.readBytes(), VpsBackupExportFixture.PASSWORD.toCharArray()))
            assertEquals("", password.text.toString())
            assertEquals("", repeated.text.toString())
            assertNull(backupDialog(fixture))
            assertEquals(fixture.peer.connections.size, fixture.peer.closes.get())
            callback(BiometricAuthenticator.Result.Success(null))
            finishBackup(fixture)
            assertEquals(1, output.inserts)
        } finally { fixture.close() }
    }

    @Test
    @Config(shadows = [VpsCredentialFileTest.NativeFiles::class, AppDataTransferSettingsTest.Authentication::class])
    fun backupPasswordNeverEntersSavedHierarchyAndRecreationRetiresOldAuthentication() = runTest {
        VpsUserProfileFileTest.resetNativeFiles()
        val fixture = fixture()
        try {
            prepareBackup(fixture)
            click(fixture, R.string.vpn0wn_action_backup)
            val callback = AppDataTransferSettingsTest.Authentication.callbacks.last()
            callback(BiometricAuthenticator.Result.Success(null))
            settle()
            val dialog = requireNotNull(backupDialog(fixture))
            val input = dialog.findViewById<EditText>(R.id.vpn0wn_backup_password)!!
            input.setText(VpsBackupExportFixture.PASSWORD)
            val saved = Bundle()
            fixture.controller.saveInstanceState(saved)
            val parcel = Parcel.obtain()
            val bytes = try { parcel.writeBundle(saved); parcel.marshall() } finally { parcel.recycle() }
            for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE)) assertFalse(bytes.toString(charset).contains(VpsBackupExportFixture.PASSWORD))
            fixture.controller.recreate()
            settle()
            assertEquals("", input.text.toString())
            assertFalse(dialog.isShowing)
            callback(BiometricAuthenticator.Result.Success(null))
            settle()
            assertNull(backupDialog(fixture))
            assertFalse(fixture.peer.commands.any { it.contains("./'backup.sh'") })
        } finally { fixture.close() }
    }

    @Test
    @Config(sdk = [29], shadows = [VpsCredentialFileTest.NativeFiles::class, AppDataTransferSettingsTest.Authentication::class])
    fun backupCancellationDrainsRemoteOperationAndClearsEditors() = runTest {
        VpsUserProfileFileTest.resetNativeFiles()
        val fixture = fixture()
        try {
            prepareBackup(fixture)
            fixture.peer.blockBackup = CompletableDeferred()
            click(fixture, R.string.vpn0wn_action_backup)
            AppDataTransferSettingsTest.Authentication.callbacks.last()(BiometricAuthenticator.Result.NoAuthenticationConfigured)
            settle()
            val dialog = requireNotNull(backupDialog(fixture))
            val input = dialog.findViewById<EditText>(R.id.vpn0wn_backup_password)!!
            input.setText(VpsBackupExportFixture.PASSWORD)
            dialog.findViewById<EditText>(R.id.vpn0wn_backup_confirm_password)!!.setText(VpsBackupExportFixture.PASSWORD)
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            settle()
            assertTrue("Backup start was not admitted: ${fixture.model.state.value.problem}", fixture.model.state.value.busy)
            val dispatched = coroutineScope {
                val settled = async { fixture.model.state.first { !it.busy } }
                try {
                    select {
                        fixture.peer.backupEntered.onAwait { true }
                        settled.onAwait { false }
                    }
                } finally { settled.cancelAndJoin() }
            }
            assertTrue("Backup settled before snapshot dispatch: problem=${fixture.model.state.value.problem} statusCalls=${profileStatusCommands(fixture)}", dispatched)
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            settle()
            assertFalse("Cancel dismisses the password form", dialog.isShowing)
            fixture.model.state.first { !it.busy }
            finishBackup(fixture)
            assertEquals("", input.text.toString())
            assertEquals(fixture.peer.connections.size, fixture.peer.closes.get())
            assertEquals(VpsProvisioningViewModel.Problem.CANCELLED, fixture.model.state.value.problem)
            assertNull(backupDialog(fixture))
        } finally { fixture.close() }
    }

    @Test
    @Config(shadows = [VpsCredentialFileTest.NativeFiles::class, AppDataTransferSettingsTest.Authentication::class])
    fun backupRestrictionAtConfirmationRetiresAuthorizationAndClearsPassword() = runTest {
        VpsUserProfileFileTest.resetNativeFiles()
        val fixture = fixture()
        try {
            prepareBackup(fixture)
            click(fixture, R.string.vpn0wn_action_backup)
            AppDataTransferSettingsTest.Authentication.callbacks.last()(BiometricAuthenticator.Result.Success(null))
            settle()
            val dialog = requireNotNull(backupDialog(fixture))
            val password = dialog.findViewById<EditText>(R.id.vpn0wn_backup_password)!!
            password.setText(VpsBackupExportFixture.PASSWORD)
            dialog.findViewById<EditText>(R.id.vpn0wn_backup_confirm_password)!!.setText(VpsBackupExportFixture.PASSWORD)
            setProfileExportRestriction(fixture, true)
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            settle()
            assertFalse(dialog.isShowing)
            assertEquals("", password.text.toString())
            assertNull(backupDialog(fixture))
            assertFalse(fixture.peer.commands.any { it.contains("./'backup.sh'") })
        } finally { fixture.close() }
    }

    @Test
    @Config(sdk = [29], shadows = [VpsCredentialFileTest.NativeFiles::class, AppDataTransferSettingsTest.Authentication::class])
    fun backupRestrictionAtProviderBoundaryDeletesEmptyOwnedOutput() = runTest {
        VpsUserProfileFileTest.resetNativeFiles()
        val fixture = fixture()
        try {
            prepareBackup(fixture)
            val output = BackupOutputProvider(folder.newFile()).apply {
                onOpened = { setProfileExportRestriction(fixture, true) }
                attachInfo(fixture.activity, ProviderInfo().apply { authority = "media" })
            }
            ShadowContentResolver.registerProviderInternal("media", output)
            click(fixture, R.string.vpn0wn_action_backup)
            AppDataTransferSettingsTest.Authentication.callbacks.last()(BiometricAuthenticator.Result.Success(null))
            settle()
            val dialog = requireNotNull(backupDialog(fixture))
            dialog.findViewById<EditText>(R.id.vpn0wn_backup_password)!!.setText(VpsBackupExportFixture.PASSWORD)
            dialog.findViewById<EditText>(R.id.vpn0wn_backup_confirm_password)!!.setText(VpsBackupExportFixture.PASSWORD)
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            finishBackup(fixture)
            assertEquals(1, output.opens)
            assertEquals(1, output.deletes)
            assertEquals(0L, output.bytesAtDeletion)
            assertFalse(output.file.exists())
        } finally { fixture.close() }
    }

    private suspend fun TestScope.prepareBackup(fixture: VpsProvisioningLayoutFixture) {
        AppDataTransferSettingsTest.Authentication.expectedTitleRes = R.string.vpn0wn_backup_authenticate
        val store = Vpn0wnApplication.getVpsServerStore()
        store.upsert(VpsProfileFixture.server)
        store.saveCredential(VpsProfileFixture.server, PROFILE_PIN, VpsCredential.Password(PASSWORD.toCharArray()))
        fixture.peer.statusOutput = VpsBackupExportFixture.status()
        fixture.peer.backupOutput = ProvisioningJson.encodeBackup(VpsBackupExportFixture.snapshot).toString(Charsets.UTF_8)
        requireNotNull(fixture.model.syncStatus(VpsProfileFixture.server)).join()
        val current = store.servers.first().single()
        awaitRenderedServer(fixture, current)
        openServer(fixture, current)
    }

    private fun assertPasswordMasked(input: EditText) {
        assertTrue("Secret text uses native password transformation", input.transformationMethod is PasswordTransformationMethod)
        assertFalse("Rendered secret is masked", input.transformationMethod.getTransformation(input.text, input).toString() == input.text.toString())
        assertEquals(1, input.maxLines)
        val node = input.createAccessibilityNodeInfo()
        try {
            assertTrue("Accessibility identifies password", node.isPassword)
            assertTrue("Accessibility identifies editable input", node.isEditable)
        } finally { node.recycle() }
    }

    private fun backupDialog(fixture: VpsProvisioningLayoutFixture): AlertDialog? = VpsProvisioningFragment::class.java.getDeclaredField("backupDialog")
        .apply { isAccessible = true }.get(fixture.activity.supportFragmentManager.fragments.filterIsInstance<VpsProvisioningFragment>().single()) as? AlertDialog

    private suspend fun TestScope.finishBackup(fixture: VpsProvisioningLayoutFixture) {
        settle()
        val job = VpsProvisioningFragment::class.java.getDeclaredField("backupJob").apply { isAccessible = true }.get(fixture.fragment) as? Job
        job?.join()
        settle()
    }

    private suspend fun TestScope.awaitRenderedServer(fixture: VpsProvisioningLayoutFixture, expected: StoredVpsServer) {
        val root = fixture.root as ViewGroup
        val projection = VpsProvisioningFragment::class.java.getDeclaredField("storedServers").apply { isAccessible = true }
        val published = CompletableDeferred<Unit>()
        fun observe() {
            val records = projection.get(fixture.fragment) as List<*>
            if (records.singleOrNull { (it as StoredVpsServer).id == expected.id } == expected &&
                descendants(root).filterIsInstance<TextView>().any { it.text.toString() == expected.name }) published.complete(Unit)
        }
        root.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) = observe()
            override fun onChildViewRemoved(parent: View?, child: View?) = Unit
        })
        try {
            observe()
            settle()
            withContext(Dispatchers.Default) { withTimeout(10_000) { published.await() } }
            settle()
        } finally { root.setOnHierarchyChangeListener(null) }
    }


    private class BackupOutputProvider(val file: File) : ContentProvider() {
        var inserts = 0
        var opens = 0
        var deletes = 0
        var name: String? = null
        var bytesAtDeletion: Long? = null
        var onOpened: (() -> Unit)? = null
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserts++
            name = values!!.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
            return Uri.parse("content://media/external/downloads/1")
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            opens++
            val descriptor = ParcelFileDescriptor.open(file, if (mode == "r") ParcelFileDescriptor.MODE_READ_ONLY else ParcelFileDescriptor.MODE_WRITE_ONLY)
            if (mode != "r") onOpened?.invoke()
            return descriptor
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(requireNotNull(projection)).apply {
                addRow(projection.map { if (it == MediaStore.MediaColumns.DISPLAY_NAME) name else file.absolutePath })
            }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            assertEquals(Uri.parse("content://media/external/downloads/1"), uri)
            deletes++
            bytesAtDeletion = file.length()
            return if (file.delete()) 1 else 0
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private fun TestScope.openServer(fixture: VpsProvisioningLayoutFixture, server: StoredVpsServer) {
        clickableAncestor(text(fixture.root, server.name)).performClick()
        settle()
    }

    private suspend fun TestScope.fixture(): VpsProvisioningLayoutFixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return provisioningLayoutFixture(folder, View.LAYOUT_DIRECTION_LTR)
    }

    private fun setProfileExportRestriction(fixture: VpsProvisioningLayoutFixture, disabled: Boolean) {
        val restrictions = fixture.activity.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
        shadowOf(restrictions).setApplicationRestrictions(Bundle().apply { putBoolean("disable_config_export", disabled) })
    }

    private fun profileStatusCommands(fixture: VpsProvisioningLayoutFixture) = fixture.peer.commands.count { it.contains("./'sync-status.sh'") }

    private fun TestScope.click(fixture: VpsProvisioningLayoutFixture, resource: Int) {
        clickableAncestor(text(fixture.root, fixture.activity.getString(resource))).performClick()
        settle()
    }

    private fun TestScope.settle() {
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun text(root: View, value: String): TextView = descendants(root).filterIsInstance<TextView>().first { it.text.toString() == value }

    private fun clickableAncestor(view: View): View = if (view.isClickable) view else clickableAncestor(view.parent as View)

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }

    private companion object {
        const val PASSWORD = "synthetic-ssh-password"
        val PROFILE_PIN = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    }
}
