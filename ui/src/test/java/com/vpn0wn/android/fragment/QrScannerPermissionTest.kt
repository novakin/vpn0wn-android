package com.vpn0wn.android.fragment

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.CaptureManager
import com.vpn0wn.android.R
import com.vpn0wn.android.model.TunnelTestEnvironment
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class QrScannerPermissionTest {
    @get:Rule val folder = TemporaryFolder()
    private val hosts = mutableListOf<ActivityController<HostActivity>>()
    private val captures = mutableListOf<ActivityController<CaptureActivity>>()
    private val environments = mutableListOf<TunnelTestEnvironment>()

    @After fun tearDown() {
        captures.forEach { it.destroy() }
        hosts.forEach { it.pause().stop().destroy() }
        environments.clear()
        Dispatchers.resetMain()
        RuntimeEnvironment.setQualifiers("")
    }

    @Test fun denialExplainsMissingPermissionCancelsWithoutImportAndAllowsReopen() = runScannerTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val environment = TunnelTestEnvironment(folder.root, backgroundScope)
        environments += environment
        environment.store.configs["existing"] = TunnelTestEnvironment.profile()
        environment.manager.getTunnels()
        val (activity, home) = host()
        runCurrent()

        repeat(2) {
            val request = launchScanner(activity, home)
            assertEquals(CaptureActivity::class.java.name, request.component!!.className)
            assertFalse(request.getBooleanExtra(Intents.Scan.ORIENTATION_LOCKED, true))
            assertFalse(request.getBooleanExtra(Intents.Scan.BEEP_ENABLED, true))
            assertEquals(activity.getString(R.string.qr_code_hint), request.getStringExtra(Intents.Scan.PROMPT_MESSAGE))
            assertTrue(request.getBooleanExtra(Intents.Scan.SHOW_MISSING_CAMERA_PERMISSION_DIALOG, false))
            assertEquals(activity.getString(R.string.qr_camera_permission_denied),
                request.getStringExtra(Intents.Scan.MISSING_CAMERA_PERMISSION_DIALOG_MESSAGE))
            val capture = capture(request)
            capture.onRequestPermissionsResult(CaptureManager.getCameraPermissionReqCode(),
                arrayOf(Manifest.permission.CAMERA), intArrayOf(PackageManager.PERMISSION_DENIED))
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(activity.getString(R.string.qr_camera_permission_denied), shadowOf(dialog).message.toString())
            assertFalse(capture.isFinishing)
            assertEquals(Activity.RESULT_CANCELED, shadowOf(capture).resultCode)
            val result = shadowOf(capture).resultIntent
            assertTrue(result.getBooleanExtra(Intents.Scan.MISSING_CAMERA_PERMISSION, false))
            assertNull(result.getStringExtra(Intents.Scan.RESULT))
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(capture.isFinishing)
            shadowOf(activity).receiveResult(request, Activity.RESULT_CANCELED, result)
            runCurrent()
            activity.supportFragmentManager.executePendingTransactions()
            assertTrue(activity.supportFragmentManager.fragments.none { it is ConfigNamingDialogFragment })
            assertEquals(setOf("existing"), environment.store.configs.keys.toSet())
        }
    }

    @Test fun ordinaryCancellationAndSuccessfulQrKeepExistingNamingContract() = runScannerTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val environment = TunnelTestEnvironment(folder.root, backgroundScope)
        environments += environment
        environment.manager.getTunnels()
        val (activity, home) = host()
        runCurrent()
        val cancelled = launchScanner(activity, home)
        shadowOf(activity).receiveResult(cancelled, Activity.RESULT_CANCELED, null)
        runCurrent()
        activity.supportFragmentManager.executePendingTransactions()
        assertTrue(activity.supportFragmentManager.fragments.none { it is ConfigNamingDialogFragment })
        assertTrue(environment.store.configs.isEmpty())

        val successful = launchScanner(activity, home)
        val config = TunnelTestEnvironment.profile().toWgQuickString()
        shadowOf(activity).receiveResult(successful, Activity.RESULT_OK, Intent().putExtra(Intents.Scan.RESULT, config))
        runCurrent()
        activity.supportFragmentManager.executePendingTransactions()
        val naming = activity.supportFragmentManager.fragments.filterIsInstance<ConfigNamingDialogFragment>().single()
        assertEquals(config, naming.requireArguments().getString("config_text"))
        assertTrue(environment.store.configs.isEmpty())
        naming.requireDialog().cancel()
    }

    @Test fun localizedPermissionGuidanceDoesNotReplaceGenuineCameraError() = runScannerTest {
        RuntimeEnvironment.setQualifiers("fr")
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val environment = TunnelTestEnvironment(folder.root, backgroundScope)
        environments += environment
        environment.manager.getTunnels()
        val (activity, home) = host()
        runCurrent()
        val request = launchScanner(activity, home)
        assertTrue(request.getStringExtra(Intents.Scan.MISSING_CAMERA_PERMISSION_DIALOG_MESSAGE)!!.startsWith("L'accès"))
        val capture = capture(request)
        val manager = CaptureActivity::class.java.getDeclaredField("capture").apply { isAccessible = true }.get(capture)
        val listener = CaptureManager::class.java.getDeclaredField("stateListener").apply { isAccessible = true }
            .get(manager) as CameraPreview.StateListener
        listener.cameraError(IOException("Injected camera initialization failure"))
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(capture.getString(com.google.zxing.client.android.R.string.zxing_msg_camera_framework_bug),
            shadowOf(dialog).message.toString())
        assertNull(shadowOf(capture).resultIntent)
        dialog.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(capture.isFinishing)
    }

    private fun host(): Pair<HostActivity, TunnelListFragment> {
        val controller = Robolectric.buildActivity(HostActivity::class.java)
        hosts += controller
        val activity = controller.get()
        activity.setTheme(R.style.AppTheme)
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
        controller.setup()
        val home = TunnelListFragment()
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, home, "home").commitNow()
        return activity to home
    }

    private fun launchScanner(activity: HostActivity, home: TunnelListFragment): Intent {
        assertTrue(home.requireView().findViewById<View>(R.id.create_fab).performClick())
        val sheet = home.childFragmentManager.fragments.filterIsInstance<AddTunnelsSheet>().single()
        assertTrue(sheet.requireView().findViewById<View>(R.id.create_from_qrcode).performClick())
        home.childFragmentManager.executePendingTransactions()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(sheet.isAdded)
        return shadowOf(activity).nextStartedActivityForResult.intent
    }

    private fun capture(request: Intent): CaptureActivity {
        val controller = Robolectric.buildActivity(CaptureActivity::class.java, request)
        captures += controller
        controller.create()
        return controller.get()
    }

    private fun runScannerTest(block: suspend TestScope.() -> Unit) = runTest {
        try {
            block()
        } finally {
            captures.forEach { it.destroy() }
            captures.clear()
            hosts.forEach { it.pause().stop().destroy() }
            hosts.clear()
        }
    }

    class HostActivity : AppCompatActivity()
}
