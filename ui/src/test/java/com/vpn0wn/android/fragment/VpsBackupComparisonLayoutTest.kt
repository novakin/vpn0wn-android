/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.fragment

import android.app.Activity
import android.app.Application
import android.content.DialogInterface
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import com.vpn0wn.android.Application as Vpn0wnApplication
import com.vpn0wn.android.R
import com.vpn0wn.android.activity.AppDataTransferSettingsTest
import com.vpn0wn.android.provisioning.ProvisioningJson
import com.vpn0wn.android.provisioning.VpsBackupArchive
import com.vpn0wn.android.provisioning.VpsBackupExportFixture
import com.vpn0wn.android.provisioning.VpsBackupInputProvider
import com.vpn0wn.android.provisioning.VpsBackupResult
import com.vpn0wn.android.provisioning.VpsCredentialFileTest
import com.vpn0wn.android.provisioning.VpsProfileFixture
import com.vpn0wn.android.provisioning.VpsRevokedBackup
import com.vpn0wn.android.provisioning.VpsUserProfileFileTest
import com.vpn0wn.android.util.BiometricAuthenticator
import java.io.File
import java.util.Locale
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28],
    shadows = [VpsCredentialFileTest.NativeFiles::class, AppDataTransferSettingsTest.Authentication::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class VpsBackupComparisonLayoutTest {
    @get:Rule val folder = TemporaryFolder()

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun savedServerComparisonKeepsCompletePublicResultsReachable() = runTest(timeout = 3.minutes) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val qualifiers = RuntimeEnvironment.getQualifiers()
        val scale = RuntimeEnvironment.getApplication().resources.configuration.fontScale
        val direction = RuntimeEnvironment.getApplication().resources.configuration.layoutDirection
        val locale = Locale.getDefault()
        val backup = backupSnapshot()
        val password = VpsBackupExportFixture.PASSWORD.toCharArray()
        val archive = try { VpsBackupArchive().encrypt(backup, password) } finally { password.fill('\u0000') }
        val previews = listOf(Preview("en", 320, 640, 1f), Preview("fr", 640, 320, 2f, true), Preview("ar", 320, 640, 2f, true))
        try {
            for (preview in previews) for (night in listOf(false, true)) {
                val case = preview.copy(night = night)
                configure(case)
                resetAuthentication()
                VpsUserProfileFileTest.resetNativeFiles()
                val fixture = provisioningLayoutFixture(folder, if (case.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR)
                fixture.activity.supportActionBar?.hide()
                try {
                    val observed = observedSnapshot(backup, case.locale == "en")
                    val saved = openBackupComparisonServer(fixture, VpsProfileFixture.server.copy(name = SERVER_NAME))
                    fixture.peer.backupOutput = ProvisioningJson.encodeBackup(observed).toString(Charsets.UTF_8)
                    val provider = VpsBackupInputProvider(folder.newFile().apply { writeBytes(archive) }).also { it.register(fixture.activity) }
                    val row = requireNotNull(fixture.root.findViewById<View>(R.id.vpn0wn_vps_compare_backup))
                    val entryRoot = fixture.activity.window.decorView
                    layout(entryRoot, case)
                    revealTarget(row, entryRoot, case)
                    assertTrue(row.isEnabled && row.isClickable)
                    assertTrue(row.height >= MINIMUM_TARGET_DP * row.resources.displayMetrics.density)
                    capture(entryRoot, case, "detail-action")
                    row.performClick()
                    settle()
                    val request = requireNotNull(shadowOf(fixture.activity).nextStartedActivityForResult)
                    assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
                    fixture.activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK,
                        Intent().setData(VpsBackupInputProvider.URI))
                    settle()
                    assertEquals(1, AppDataTransferSettingsTest.Authentication.calls)
                    AppDataTransferSettingsTest.Authentication.callbacks.single()(BiometricAuthenticator.Result.Success(null))
                    settle()
                    val unlock = requireNotNull(dialog(fixture))
                    assertSecure(unlock)
                    unlock.findViewById<EditText>(R.id.vpn0wn_backup_import_password)!!.setText(VpsBackupExportFixture.PASSWORD)
                    unlock.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
                    finish(fixture)
                    val summary = requireNotNull(dialog(fixture))
                    assertNotSame(unlock, summary)
                    assertSecure(summary)
                    val summaryRoot = requireNotNull(summary.window).decorView
                    layout(summaryRoot, case)
                    assertNoPrivateText(summaryRoot, backup, observed)
                    captureText(summaryRoot, customContent(summary), case, "summary")
                    val compare = summary.getButton(DialogInterface.BUTTON_POSITIVE)
                    assertEquals(fixture.activity.getString(R.string.vpn0wn_backup_compare_action), compare.text.toString())
                    checkAction(compare, summaryRoot, case, "summary-compare")
                    checkAction(summary.getButton(DialogInterface.BUTTON_NEGATIVE), summaryRoot, case, "summary-close")
                    compare.performClick()
                    finish(fixture)
                    val result = requireNotNull(dialog(fixture))
                    assertNotSame(summary, result)
                    assertSecure(result)
                    val resultRoot = requireNotNull(result.window).decorView
                    layout(resultRoot, case)
                    checkTitle(result, resultRoot, case)
                    val content = requireNotNull(result.findViewById<ViewGroup>(R.id.vpn0wn_backup_comparison_result))
                    assertResult(fixture, content, backup, observed)
                    assertNoPrivateText(resultRoot, backup, observed)
                    captureText(resultRoot, content, case, "result")
                    assertEquals(View.GONE, result.getButton(DialogInterface.BUTTON_POSITIVE).visibility)
                    checkAction(result.getButton(DialogInterface.BUTTON_NEGATIVE), resultRoot, case, "result-close")
                    result.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
                    settle()
                    assertNull(fixture.model.state.value.backupComparison)
                    assertNull(fixture.model.state.value.backupSummary)
                    assertEquals(saved, Vpn0wnApplication.getVpsServerStore().servers.first().single())
                    assertEquals(1, provider.opens)
                    assertTrue(provider.closed)
                    assertEquals(0, provider.mutations)
                    assertEquals(1, fixture.peer.commands.count { it.contains("./'backup.sh'") })
                    assertFalse(fixture.peer.commands.any { command ->
                        listOf("sync-status.sh", "restore.sh", "install.sh", "add-peer.sh", "remove-peer.sh",
                            "revoke-peer.sh", "rotate-peer.sh", "uninstall.sh").any { command.contains("./'$it'") }
                    })
                } finally { fixture.close() }
            }
        } finally {
            archive.fill(0)
            Locale.setDefault(locale)
            RuntimeEnvironment.setQualifiers(qualifiers)
            RuntimeEnvironment.setFontScale(scale)
            RuntimeEnvironment.setQualifiers(if (direction == View.LAYOUT_DIRECTION_RTL) "+ldrtl" else "+ldltr")
        }
    }

    private fun assertResult(fixture: VpsProvisioningLayoutFixture, content: ViewGroup, backup: VpsBackupResult, observed: VpsBackupResult) {
        val matching = backup.config == observed.config
        val expected = listOf(
            R.string.vpn0wn_backup_comparison_server to SERVER_NAME,
            R.string.vpn0wn_backup_comparison_backup_time to backup.capturedAtUtc,
            R.string.vpn0wn_backup_comparison_server_time to observed.capturedAtUtc,
            R.string.vpn0wn_backup_comparison_config to fixture.activity.getString(
                if (matching) R.string.vpn0wn_backup_comparison_match else R.string.vpn0wn_backup_comparison_different),
            R.string.vpn0wn_backup_comparison_only_backup to if (matching) fixture.activity.getString(R.string.vpn0wn_backup_comparison_none) else ONLY_BACKUP.joinToString("\n"),
            R.string.vpn0wn_backup_comparison_only_server to if (matching) fixture.activity.getString(R.string.vpn0wn_backup_comparison_none) else ONLY_SERVER.joinToString("\n"),
            R.string.vpn0wn_backup_comparison_body_different to if (matching) fixture.activity.getString(R.string.vpn0wn_backup_comparison_none) else DIFFERENT_BODY.joinToString("\n"),
        )
        val values = descendants(content).filterIsInstance<TextView>().toList()
        assertEquals(expected.size * 2 + 1, values.size)
        expected.forEachIndexed { index, (label, value) ->
            assertEquals(fixture.activity.getString(label), values[index * 2].text.toString())
            assertEquals(value, values[index * 2 + 1].text.toString())
        }
        for (index in listOf(3, 5, 9, 11, 13)) assertEquals(View.TEXT_DIRECTION_LTR, values[index].textDirection)
        assertEquals(fixture.activity.getString(R.string.vpn0wn_backup_comparison_note), values.last().text.toString())
        assertTrue(descendants(content).none { it is EditText })
    }

    private fun TestScope.captureText(root: View, content: View, case: Preview, prefix: String) {
        val values = descendants(content).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE && it.text.isNotEmpty() }.toList()
        assertTrue(values.isNotEmpty())
        values.forEachIndexed { index, value ->
            assertEquals(ResourcesCompat.getFont(value.context, R.font.jetbrains_mono_regular), value.typeface)
            assertEquals("ss01, zero, tnum", value.fontFeatureSettings)
            assertFalse(value.paint.isFakeBoldText)
            assertFalse("Public comparison text is read-only", value.isClickable || value.isLongClickable || value.isFocusable)
            assertTextFits(value)
            for (line in 0 until value.lineCount) {
                revealLine(value, line, root, case)
                capture(root, case, "$prefix-field-$index-line-$line")
            }
        }
    }

    private fun TestScope.checkAction(button: TextView, root: View, case: Preview, name: String) {
        button.requestRectangleOnScreen(Rect(button.scrollX, button.scrollY,
            button.scrollX + button.width, button.scrollY + button.height), true)
        layout(root, case)
        val minimum = MINIMUM_TARGET_DP * button.resources.displayMetrics.density
        assertTrue(button.isEnabled && button.isClickable && button.isFocusable)
        assertTrue("Native action width", button.width >= minimum)
        assertTrue("Native action height", button.height >= minimum)
        assertTrue("Native action is fully visible", fullyVisible(button))
        assertTextFits(button)
        capture(root, case, name)
    }

    private fun TestScope.checkTitle(dialog: AlertDialog, root: View, case: Preview) {
        val title = requireNotNull(dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle))
        assertEquals(title.context.getString(R.string.vpn0wn_backup_comparison_title), title.text.toString())
        assertFalse("Native comparison title is read-only", title.isClickable || title.isLongClickable || title.isFocusable)
        revealTarget(title, root, case)
        assertTextFits(title)
        capture(root, case, "result-title")
    }

    private fun assertNoPrivateText(root: View, backup: VpsBackupResult, observed: VpsBackupResult) {
        val visible = descendants(root).filterIsInstance<TextView>().joinToString("\n") { it.text }
        assertFalse("Private key is not displayed", visible.contains(VpsProfileFixture.serverKey.privateKey.toBase64()))
        assertFalse("Password is not displayed", visible.contains(VpsBackupExportFixture.PASSWORD))
        for (snapshot in listOf(backup, observed)) {
            assertFalse("Private config is not displayed", visible.contains(snapshot.config))
            snapshot.revoked.forEach { assertFalse("Revoked body is not displayed", visible.contains(it.config)) }
        }
    }

    private fun assertSecure(dialog: AlertDialog) =
        assertTrue(requireNotNull(dialog.window).attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)

    private fun customContent(dialog: AlertDialog): View =
        requireNotNull(dialog.findViewById<View>(androidx.appcompat.R.id.custom))

    private suspend fun TestScope.finish(fixture: VpsProvisioningLayoutFixture) {
        settle()
        (VpsProvisioningFragment::class.java.getDeclaredField("backupImportJob").apply { isAccessible = true }.get(fixture.fragment) as? Job)?.join()
        settle()
    }

    private fun dialog(fixture: VpsProvisioningLayoutFixture): AlertDialog? =
        VpsProvisioningFragment::class.java.getDeclaredField("backupImportDialog").apply { isAccessible = true }.get(fixture.fragment) as? AlertDialog

    private fun TestScope.settle() { runCurrent(); shadowOf(Looper.getMainLooper()).idle() }

    private fun configure(case: Preview) {
        val orientation = if (case.width > case.height) "land" else "port"
        RuntimeEnvironment.setQualifiers("${case.locale}-w${case.width}dp-h${case.height}dp-$orientation-${if (case.night) "night" else "notnight"}-mdpi")
        RuntimeEnvironment.setFontScale(case.scale)
        RuntimeEnvironment.setQualifiers(if (case.rtl) "+ldrtl" else "+ldltr")
    }

    private fun resetAuthentication() {
        AppDataTransferSettingsTest.Authentication.calls = 0
        AppDataTransferSettingsTest.Authentication.autoCancel = false
        AppDataTransferSettingsTest.Authentication.expectedTitleRes = R.string.vpn0wn_backup_import_authenticate
        AppDataTransferSettingsTest.Authentication.callbacks.clear()
    }

    private fun TestScope.layout(root: View, case: Preview) {
        var ready = false
        repeat(3) {
            root.measure(View.MeasureSpec.makeMeasureSpec(case.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(case.height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            ready = !root.viewTreeObserver.dispatchOnPreDraw()
            settle()
        }
        assertTrue("Settled native pre-draw", ready)
        assertConfiguration(root, case)
    }

    private fun assertConfiguration(root: View, case: Preview) {
        val config = root.resources.configuration
        val direction = if (case.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        assertEquals(case.locale, config.locales[0].language)
        assertEquals(case.scale, config.fontScale, 0f)
        assertEquals(direction, config.layoutDirection)
        assertEquals(direction, root.layoutDirection)
        assertEquals(if (case.night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
            config.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertEquals(case.width, root.width)
        assertEquals(case.height, root.height)
    }

    private fun TestScope.revealTarget(view: View, root: View, case: Preview) {
        view.requestRectangleOnScreen(Rect(view.scrollX, view.scrollY, view.scrollX + view.width, view.scrollY + view.height), true)
        layout(root, case)
        assertTrue("Complete native target is visible", fullyVisible(view))
    }

    private fun TestScope.revealLine(view: TextView, line: Int, root: View, case: Preview) {
        val text = view.layout
        val local = Rect(kotlin.math.floor(text.getLineLeft(line)).toInt(), text.getLineTop(line),
            kotlin.math.ceil(text.getLineRight(line)).toInt(), text.getLineBottom(line))
        local.offset(view.totalPaddingLeft - view.scrollX, view.totalPaddingTop - view.scrollY)
        view.requestRectangleOnScreen(Rect(local), true)
        layout(root, case)
        val position = IntArray(2).also(view::getLocationOnScreen)
        val actual = Rect(local).apply { offset(position[0], position[1]) }
        val visible = Rect()
        assertTrue("Native text line is visible", view.getGlobalVisibleRect(visible))
        assertTrue("Complete text line is reachable: $actual within $visible; ${view.text}", visible.contains(actual))
    }

    private fun assertTextFits(view: TextView) {
        val text = requireNotNull(view.layout)
        val width = view.width - view.compoundPaddingLeft - view.compoundPaddingRight
        val height = view.height - view.compoundPaddingTop - view.compoundPaddingBottom
        assertTrue("Complete text characters", text.lineCount > 0 && text.getLineEnd(text.lineCount - 1) == text.text.length)
        assertTrue("Complete text height", text.height <= height)
        for (line in 0 until text.lineCount) {
            assertEquals("No ellipsis: ${view.text}", 0, text.getEllipsisCount(line))
            assertTrue("Complete native line width", text.getLineLeft(line) - view.scrollX >= -INK_TOLERANCE_PX &&
                text.getLineRight(line) - view.scrollX <= width + INK_TOLERANCE_PX)
        }
        val bitmap = Bitmap.createBitmap(width + INK_MARGIN_PX * 2, height + INK_MARGIN_PX * 2, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.translate((INK_MARGIN_PX - view.scrollX).toFloat(), (INK_MARGIN_PX - view.scrollY).toFloat())
            text.draw(canvas)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("Native text glyphs are painted", pixels.any { it ushr ALPHA_SHIFT != 0 })
            assertTrue("Complete painted text glyphs", pixels.indices.all {
                if (pixels[it] ushr ALPHA_SHIFT == 0) true else {
                    val x = it % bitmap.width - INK_MARGIN_PX
                    val y = it / bitmap.width - INK_MARGIN_PX
                    x >= -INK_TOLERANCE_PX && x < width + INK_TOLERANCE_PX && y >= -INK_TOLERANCE_PX && y < height + INK_TOLERANCE_PX
                }
            })
        } finally { bitmap.recycle() }
    }

    private fun fullyVisible(view: View): Boolean {
        val visible = Rect()
        if (!view.getLocalVisibleRect(visible)) return false
        visible.offset(-view.scrollX, -view.scrollY)
        return visible == Rect(0, 0, view.width, view.height)
    }

    private fun capture(root: View, case: Preview, position: String) {
        assertConfiguration(root, case)
        val project = File(requireNotNull(System.getProperty("user.dir"))).let { if (it.name == "ui") it.parentFile else it }
        val output = System.getenv("VPN0WN_BACKUP_COMPARISON_OUTPUT")?.let(::File) ?: File(project, ".artifacts/backup-comparison-previews")
        assertTrue(output.isDirectory || output.mkdirs())
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            File(output, "${case.locale}-${case.width}x${case.height}-${case.scale}-${if (case.night) "night" else "day"}-$position.png")
                .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }

    private fun backupSnapshot(): VpsBackupResult {
        val records = (ONLY_BACKUP + DIFFERENT_BODY).map { VpsRevokedBackup(it, "# synthetic private revoked body $it\n") }
        return VpsBackupExportFixture.snapshot.copy(capturedAtUtc = BACKUP_TIME, revokedCount = records.size, revoked = records)
    }

    private fun observedSnapshot(backup: VpsBackupResult, matching: Boolean): VpsBackupResult {
        if (matching) return backup.copy(capturedAtUtc = SERVER_TIME, revoked = backup.revoked.reversed())
        val records = (ONLY_SERVER + DIFFERENT_BODY).map { VpsRevokedBackup(it, "# changed private revoked body $it\n") }
        return backup.copy(capturedAtUtc = SERVER_TIME, config = backup.config + "# observed comment\n", revokedCount = records.size, revoked = records)
    }

    private data class Preview(val locale: String, val width: Int, val height: Int, val scale: Float,
        val rtl: Boolean = false, val night: Boolean = false)

    companion object {
        private const val SERVER_NAME = "recovery-destination-paris-long-selected-server-name"
        private const val BACKUP_TIME = "2026-10-17T07:00:00Z"
        private const val SERVER_TIME = "2026-10-18T08:30:00Z"
        private const val MINIMUM_TARGET_DP = 48
        private const val INK_MARGIN_PX = 6
        private const val INK_TOLERANCE_PX = 3
        private const val ALPHA_SHIFT = 24
        private val ONLY_BACKUP = listOf("backup-alpha.conf.00000000-0000-4000-8000-000000000001", "backup-beta.conf.00000000-0000-4000-8000-000000000002")
        private val ONLY_SERVER = listOf("server-alpha.conf.00000000-0000-4000-8000-000000000003", "server-beta.conf.00000000-0000-4000-8000-000000000004")
        private val DIFFERENT_BODY = listOf("same-alpha.conf.00000000-0000-4000-8000-000000000005", "same-beta.conf.00000000-0000-4000-8000-000000000006")
    }
}
