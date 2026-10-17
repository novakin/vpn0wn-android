/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.fragment

import android.app.Application
import android.content.DialogInterface
import android.graphics.Rect
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.vpn0wn.android.R
import com.vpn0wn.android.provisioning.VpsProfileFixture
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.BackupSummary
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class VpsBackupImportDialogLayoutTest {
    @get:Rule val folder = TemporaryFolder()
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun unlockAndSummaryKeepCompleteNativeTextAndActionsReachable() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val cases = listOf("en-rUS-w360dp-h800dp-notnight-mdpi", "en-rUS-w360dp-h800dp-night-mdpi", "fr-ldrtl-w640dp-h320dp-land-notnight-mdpi")
        try {
            for ((index, qualifiers) in cases.withIndex()) {
                RuntimeEnvironment.setQualifiers(qualifiers)
                RuntimeEnvironment.setFontScale(if (index == 2) 2f else 1f)
                for (summary in listOf(false, true)) {
                    val fixture = provisioningLayoutFixture(folder, if (index == 2) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR)
                    try {
                        val dialog = showForm(fixture, summary)
                        val root = dialog.window!!.decorView
                        root.layoutDirection = if (index == 2) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                        val width = if (index == 2) 640 else 360
                        val height = if (index == 2) 320 else 800
                        var ready = false
                        repeat(3) { measure(root, width, height); ready = !root.viewTreeObserver.dispatchOnPreDraw(); settle() }
                        assertTrue(ready)
                        assertEquals(if (index == 2) "fr" else "en", root.resources.configuration.locales[0].language)
                        assertEquals(if (index == 2) 2f else 1f, root.resources.configuration.fontScale, 0f)
                        assertEquals(if (index == 1) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO,
                            root.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        val body = descendants(requireNotNull(dialog.findViewById<View>(androidx.appcompat.R.id.custom))).filterIsInstance<ScrollView>().single()
                        descendants(body).filterIsInstance<TextView>().forEach { assertProfileTextReachable(it) }
                        if (!summary) assertProfileViewReachable(dialog.findViewById<EditText>(R.id.vpn0wn_backup_import_password)!!)
                        val buttons = if (summary) listOf(DialogInterface.BUTTON_POSITIVE) else listOf(DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_POSITIVE)
                        for (id in buttons) {
                            val button = dialog.getButton(id)
                            assertTrue(button.height >= 48 * button.resources.displayMetrics.density)
                            assertProfileViewReachable(button)
                            assertProfileTextReachable(button)
                        }
                        val project = File(requireNotNull(System.getProperty("user.dir"))).let { if (it.name == "ui") it.parentFile else it }
                        val directory = File(project, ".artifacts/backup-import-previews").apply { mkdirs() }
                        for ((edge, position) in listOf("top" to 0, "bottom" to body.getChildAt(0).height)) {
                            body.scrollTo(0, position)
                            repeat(3) { measure(root, width, height); ready = !root.viewTreeObserver.dispatchOnPreDraw(); settle() }
                            assertTrue(ready)
                            val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
                            try {
                                root.draw(android.graphics.Canvas(bitmap))
                                val kind = if (summary) "summary" else "password"
                                File(directory, "$kind-$index-$edge.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                            } finally { bitmap.recycle() }
                        }
                    } finally { fixture.close() }
                }
            }
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    private fun showForm(fixture: VpsProvisioningLayoutFixture, summary: Boolean): AlertDialog {
        val type = VpsProvisioningFragment::class.java.declaredClasses.single { it.simpleName == "BackupImportAction" }
        val action = type.getDeclaredConstructor(View::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(fixture.root, false, false)
        VpsProvisioningFragment::class.java.getDeclaredField("backupImportAction").apply { isAccessible = true }.set(fixture.fragment, action)
        if (summary) {
            val value = BackupSummary("2026-10-17T09:00:00Z", "wg0", 1, 12, VpsProfileFixture.serverKey.publicKey.toBase64())
            VpsProvisioningFragment::class.java.getDeclaredMethod("showBackupSummary", BackupSummary::class.java)
                .apply { isAccessible = true }.invoke(fixture.fragment, value)
        } else {
            VpsProvisioningFragment::class.java.getDeclaredMethod("showBackupUnlock", type, Uri::class.java)
                .apply { isAccessible = true }.invoke(fixture.fragment, action, Uri.parse("content://layout-fixture/archive"))
        }
        return VpsProvisioningFragment::class.java.getDeclaredField("backupImportDialog").apply { isAccessible = true }.get(fixture.fragment) as AlertDialog
    }

    private fun TestScope.assertProfileViewReachable(view: View) {
        assertTrue("Target is measured", view.width > 0 && view.height > 0)
        view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
        settle()
        val visible = Rect()
        assertTrue("Target is visible", view.getGlobalVisibleRect(visible))
        val location = IntArray(2).also(view::getLocationOnScreen)
        val target = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        // Material text fields may report the enclosing layout's larger focus rectangle.
        assertTrue("Complete target is reachable: target=$target visible=$visible", visible.contains(target))
    }

    private fun TestScope.assertProfileTextReachable(view: TextView) {
        assertNotNull("Text has native layout", view.layout)
        val layout = view.layout
        assertTrue("Text has painted lines", layout.lineCount > 0)
        assertEquals("Complete text is laid out", view.text.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue("Complete text height is painted", layout.height <= view.height - view.totalPaddingTop - view.totalPaddingBottom)
        for (line in 0 until layout.lineCount) {
            assertEquals("No text is ellipsized", 0, layout.getEllipsisCount(line))
            val glyphBounds = Rect(kotlin.math.floor(layout.getLineLeft(line)).toInt(), layout.getLineTop(line),
                kotlin.math.ceil(layout.getLineRight(line)).toInt(), layout.getLineBottom(line))
            val requested = Rect(glyphBounds).apply { offset(view.totalPaddingLeft, view.totalPaddingTop) }
            view.requestRectangleOnScreen(Rect(requested), true)
            settle()
            val lineBounds = Rect(glyphBounds).apply { offset(view.totalPaddingLeft - view.scrollX, view.totalPaddingTop - view.scrollY) }
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            lineBounds.offset(location[0], location[1])
            val visible = Rect()
            assertTrue("Text line is visible", view.getGlobalVisibleRect(visible))
            assertTrue("Complete text line is reachable: line=$line requested=$requested checked=$lineBounds visible=$visible " +
                "view=${view.width}x${view.height} layoutWidth=${view.layout.width} " +
                "glyphs=${view.layout.getLineLeft(line)}..${view.layout.getLineRight(line)} " +
                "scroll=${view.scrollX},${view.scrollY} origin=${location.joinToString()}", visible.contains(lineBounds))
        }
    }

    private fun TestScope.settle() {
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun measure(view: View, width: Int, height: Int) {
        val density = view.resources.displayMetrics.density
        view.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun text(root: View, value: String): TextView = descendants(root).filterIsInstance<TextView>().single { it.text.toString() == value }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }
}
