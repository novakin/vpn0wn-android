/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.fragment

import android.app.Activity
import android.app.Application
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.vpn0wn.android.Application as Vpn0wnApplication
import com.vpn0wn.android.R
import com.vpn0wn.android.activity.AppDataTransferSettingsTest
import com.vpn0wn.android.provisioning.VpsBackupArchive
import com.vpn0wn.android.provisioning.VpsBackupExportFixture
import com.vpn0wn.android.provisioning.VpsBackupInputProvider
import com.vpn0wn.android.provisioning.VpsCredentialFileTest
import com.vpn0wn.android.provisioning.VpsProfileFixture
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel
import com.vpn0wn.android.provisioning.VpsUserProfileFileTest
import com.vpn0wn.android.util.BiometricAuthenticator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28],
    shadows = [VpsCredentialFileTest.NativeFiles::class, AppDataTransferSettingsTest.Authentication::class])
@OptIn(ExperimentalCoroutinesApi::class)
class VpsBackupImportWorkflowTest {
    @get:Rule val folder = TemporaryFolder()

    @Before fun resetAuthentication() {
        VpsUserProfileFileTest.resetNativeFiles()
        AppDataTransferSettingsTest.Authentication.calls = 0
        AppDataTransferSettingsTest.Authentication.autoCancel = false
        AppDataTransferSettingsTest.Authentication.expectedTitleRes = R.string.vpn0wn_backup_import_authenticate
        AppDataTransferSettingsTest.Authentication.callbacks.clear()
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun emptyFleetPickerSurvivesExpectedStopAndAuthenticatesOnceAfterViewStarts() = runTest {
        val fixture = fixture()
        try {
            val provider = input(fixture)
            val request = request(fixture)
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
            assertArrayEquals(arrayOf("*/*"), request.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
            assertEquals("*/*", request.intent.type)
            assertEquals(0, AppDataTransferSettingsTest.Authentication.calls)
            fixture.controller.pause().stop()
            deliver(fixture, request.requestCode)
            settle()
            assertEquals(0, AppDataTransferSettingsTest.Authentication.calls)
            fixture.controller.start().resume()
            settle()
            assertEquals(1, AppDataTransferSettingsTest.Authentication.calls)
            assertEquals(0, provider.opens)
            val callback = AppDataTransferSettingsTest.Authentication.callbacks.single()
            callback(BiometricAuthenticator.Result.NoAuthenticationConfigured)
            settle()
            val dialog = requireNotNull(dialog(fixture))
            callback(BiometricAuthenticator.Result.Success(null))
            deliver(fixture, request.requestCode)
            settle()
            assertSame(dialog, dialog(fixture))
            assertEquals(1, AppDataTransferSettingsTest.Authentication.calls)
            assertEquals(0, provider.opens)
            dialog.dismiss()
            settle()
        } finally { fixture.close() }
    }

    @Test fun successfulAuthenticationWaitsForTheCapturedViewToRestart() = runTest {
        val fixture = fixture()
        try {
            val provider = input(fixture)
            val request = request(fixture)
            deliver(fixture, request.requestCode)
            settle()
            val callback = AppDataTransferSettingsTest.Authentication.callbacks.last()
            fixture.controller.pause().stop()
            callback(BiometricAuthenticator.Result.Success(null))
            callback(BiometricAuthenticator.Result.Cancelled)
            settle()
            assertNull(dialog(fixture))
            assertEquals(0, provider.opens)
            fixture.controller.start().resume()
            settle()
            assertNotNull(dialog(fixture))
            assertEquals(0, provider.opens)
            callback(BiometricAuthenticator.Result.Success(null))
            settle()
            assertEquals(1, AppDataTransferSettingsTest.Authentication.calls)
        } finally { fixture.close() }
    }

    @Test fun retiredViewFencesNewPickerUntilItsOutstandingResultDrains() = runTest {
        val fixture = fixture()
        try {
            val request = request(fixture)
            fixture.activity.supportFragmentManager.beginTransaction().detach(fixture.fragment).commitNow()
            fixture.activity.supportFragmentManager.beginTransaction().attach(fixture.fragment).commitNow()
            settle()
            assertFalse(entry(fixture).isEnabled)
            entry(fixture).performClick()
            assertNull(shadowOf(fixture.activity).nextStartedActivityForResult)
            deliver(fixture, request.requestCode)
            settle()
            assertEquals(0, AppDataTransferSettingsTest.Authentication.calls)
            assertNull(dialog(fixture))
            assertTrue(entry(fixture).isEnabled)
            val next = request(fixture)
            deliver(fixture, next.requestCode)
            settle()
            assertEquals(1, AppDataTransferSettingsTest.Authentication.calls)
        } finally { fixture.close() }
    }

    @Test fun recreationRetainsOnlyTheOutstandingFenceAndDiscardsTheOldResult() = runTest {
        val fixture = fixture()
        try {
            val request = request(fixture)
            fixture.controller.recreate()
            settle()
            assertFalse(entry(fixture).isEnabled)
            deliver(fixture, request.requestCode)
            settle()
            assertEquals(0, AppDataTransferSettingsTest.Authentication.calls)
            assertNull(dialog(fixture))
            assertTrue(entry(fixture).isEnabled)
        } finally { fixture.close() }
    }

    @Test fun cancelledPickerAndDeniedAuthenticationNeverOpenAProvider() = runTest {
        val fixture = fixture()
        try {
            val provider = input(fixture)
            var request = request(fixture)
            fixture.activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null)
            settle()
            assertTrue(entry(fixture).isEnabled)
            assertEquals(0, AppDataTransferSettingsTest.Authentication.calls)
            request = request(fixture)
            deliver(fixture, request.requestCode)
            settle()
            val callback = AppDataTransferSettingsTest.Authentication.callbacks.last()
            callback(BiometricAuthenticator.Result.Failure(7, "Synthetic denied authentication"))
            settle()
            callback(BiometricAuthenticator.Result.Success(null))
            settle()
            assertNull(dialog(fixture))
            assertEquals(0, provider.opens)
            assertTrue(entry(fixture).isEnabled)
        } finally { fixture.close() }
    }

    @Test fun nativeMaskedReaderPasswordRetriesThenPublishesOnlySafeSummary() = runTest {
        val fixture = fixture()
        try {
            val provider = input(fixture)
            val dialog = openPassword(fixture)
            val password = dialog.findViewById<EditText>(R.id.vpn0wn_backup_import_password)!!
            assertFalse(password.isSaveEnabled)
            password.setText("synthetic visible text")
            assertTrue(password.transformationMethod is PasswordTransformationMethod)
            val node = password.createAccessibilityNodeInfo()
            try { assertTrue(node.isPassword); assertTrue(node.isEditable) } finally { node.recycle() }
            val checkbox = descendants(dialog.window!!.decorView).filterIsInstance<CheckBox>().single()
            checkbox.performClick()
            assertNull(password.transformationMethod)
            checkbox.performClick()
            assertTrue(password.transformationMethod is PasswordTransformationMethod)
            assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            password.setText("x")
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            finish(fixture)
            assertSame(dialog, dialog(fixture))
            assertNotNull(password.error)
            assertTrue(password.isEnabled)
            assertEquals(VpsProvisioningViewModel.BackupFailure.AUTHENTICATION, fixture.model.state.value.backupFailure)
            password.setText(VpsBackupExportFixture.PASSWORD)
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            finish(fixture)
            assertEquals("", password.text.toString())
            assertFalse(dialog.isShowing)
            val summary = requireNotNull(dialog(fixture))
            val visible = descendants(summary.window!!.decorView).filterIsInstance<TextView>().joinToString("\n") { it.text }
            assertTrue(visible.contains(VpsBackupExportFixture.snapshot.capturedAtUtc))
            assertTrue(visible.contains(VpsProfileFixture.serverKey.publicKey.toBase64()))
            assertFalse(visible.contains(VpsProfileFixture.serverKey.privateKey.toBase64()))
            assertFalse(visible.contains(VpsBackupExportFixture.snapshot.config))
            assertFalse(visible.contains(VpsBackupExportFixture.PASSWORD))
            assertEquals(2, provider.opens)
            assertEquals(0, provider.mutations)
            assertTrue(Vpn0wnApplication.getVpsServerStore().servers.first().isEmpty())
            assertTrue(fixture.peer.commands.isEmpty())
            summary.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            settle()
            assertNull(dialog(fixture))
            assertNull(fixture.model.state.value.backupSummary)
        } finally { fixture.close() }
    }

    @Test fun passwordAndUriNeverEnterSavedStateAndStopRetiresTheSecretSession() = runTest {
        val fixture = fixture()
        try {
            input(fixture)
            val dialog = openPassword(fixture)
            val password = dialog.findViewById<EditText>(R.id.vpn0wn_backup_import_password)!!
            password.setText(VpsBackupExportFixture.PASSWORD)
            val saved = Bundle()
            fixture.controller.saveInstanceState(saved)
            val parcel = Parcel.obtain()
            val bytes = try { parcel.writeBundle(saved); parcel.marshall() } finally { parcel.recycle() }
            for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE)) {
                assertFalse(bytes.toString(charset).contains(VpsBackupExportFixture.PASSWORD))
                assertFalse(bytes.toString(charset).contains(VpsBackupInputProvider.URI.toString()))
                assertFalse(bytes.toString(charset).contains(VpsBackupExportFixture.snapshot.config))
            }
            fixture.controller.pause().stop()
            settle()
            assertEquals("", password.text.toString())
            assertFalse(dialog.isShowing)
            assertNull(dialog(fixture))
            assertNull(fixture.model.state.value.backupSummary)
            fixture.controller.start().resume()
            settle()
            assertTrue(entry(fixture).isEnabled)
        } finally { fixture.close() }
    }

    @Test fun cancellationRetiresFormButKeepsAdmissionBusyUntilProviderDrains() = runTest {
        val fixture = fixture()
        val released = CountDownLatch(1)
        var pending: Job? = null
        try {
            val provider = input(fixture)
            val entered = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            provider.beforeOpen = { signal ->
                signal!!.setOnCancelListener { cancelled.countDown() }
                entered.countDown()
                check(released.await(10, TimeUnit.SECONDS))
            }
            val dialog = openPassword(fixture)
            val password = dialog.findViewById<EditText>(R.id.vpn0wn_backup_import_password)!!
            password.setText(VpsBackupExportFixture.PASSWORD)
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            settle()
            pending = job(fixture)
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            settle()
            assertTrue(withContext(Dispatchers.IO) { cancelled.await(10, TimeUnit.SECONDS) })
            assertEquals("", password.text.toString())
            assertFalse(dialog.isShowing)
            assertNull(dialog(fixture))
            assertTrue(fixture.model.state.value.busy)
            assertFalse(entry(fixture).isEnabled)
            assertNull(fixture.model.state.value.backupSummary)
            released.countDown()
            pending?.join()
            settle()
            assertFalse(fixture.model.state.value.busy)
            assertTrue(provider.closed)
            assertFalse(provider.descriptor.fileDescriptor.valid())
            assertTrue(entry(fixture).isEnabled)
            assertTrue(fixture.peer.commands.isEmpty())
        } finally {
            released.countDown()
            withContext(NonCancellable) { pending?.cancelAndJoin() }
            fixture.close()
        }
    }

    private suspend fun TestScope.fixture(): VpsProvisioningLayoutFixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return provisioningLayoutFixture(folder, View.LAYOUT_DIRECTION_LTR)
    }

    private suspend fun input(fixture: VpsProvisioningLayoutFixture): VpsBackupInputProvider {
        val password = VpsBackupExportFixture.PASSWORD.toCharArray()
        val bytes = try { VpsBackupArchive().encrypt(VpsBackupExportFixture.snapshot, password) } finally { password.fill('\u0000') }
        return VpsBackupInputProvider(folder.newFile().apply { writeBytes(bytes) }).also { it.register(fixture.activity) }
    }

    private fun TestScope.request(fixture: VpsProvisioningLayoutFixture): org.robolectric.shadows.ShadowActivity.IntentForResult {
        assertTrue(entry(fixture).isEnabled)
        entry(fixture).performClick()
        settle()
        return requireNotNull(shadowOf(fixture.activity).nextStartedActivityForResult)
    }

    private fun deliver(fixture: VpsProvisioningLayoutFixture, requestCode: Int) {
        fixture.activity.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(VpsBackupInputProvider.URI))
    }

    private fun TestScope.openPassword(fixture: VpsProvisioningLayoutFixture): AlertDialog {
        val request = request(fixture)
        deliver(fixture, request.requestCode)
        settle()
        AppDataTransferSettingsTest.Authentication.callbacks.last()(BiometricAuthenticator.Result.NoAuthenticationConfigured)
        settle()
        return requireNotNull(dialog(fixture))
    }

    private fun active(fixture: VpsProvisioningLayoutFixture) = fixture.activity.supportFragmentManager.fragments.filterIsInstance<VpsProvisioningFragment>().single()
    private fun entry(fixture: VpsProvisioningLayoutFixture): View = active(fixture).requireView().findViewById(R.id.vpn0wn_vps_import_backup)
    private fun dialog(fixture: VpsProvisioningLayoutFixture) = VpsProvisioningFragment::class.java.getDeclaredField("backupImportDialog")
        .apply { isAccessible = true }.get(active(fixture)) as? AlertDialog
    private fun job(fixture: VpsProvisioningLayoutFixture) = VpsProvisioningFragment::class.java.getDeclaredField("backupImportJob")
        .apply { isAccessible = true }.get(active(fixture)) as? Job
    private suspend fun TestScope.finish(fixture: VpsProvisioningLayoutFixture) { settle(); job(fixture)?.join(); settle() }
    private fun TestScope.settle() { runCurrent(); shadowOf(Looper.getMainLooper()).idle() }
    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }
}
