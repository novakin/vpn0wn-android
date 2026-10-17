/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.fragment

import android.app.Application
import android.content.DialogInterface
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.vpn0wn.android.R
import com.vpn0wn.android.provisioning.StoredVpsServer
import com.vpn0wn.android.provisioning.VpsProfileFixture
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Native graphics needs its own worker to avoid retaining another SDK sandbox in workflow tests.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class VpsBackupDialogLayoutTest {
    @get:Rule val folder = TemporaryFolder()

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun backupDialogKeepsGuidanceLabelsAndActionsReachableAtSupportedNativeSizes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val cases = listOf("en-rUS-w360dp-h800dp-notnight-mdpi", "en-rUS-w360dp-h800dp-night-mdpi", "fr-ldrtl-w640dp-h320dp-land-notnight-mdpi")
        try {
            for ((index, qualifiers) in cases.withIndex()) {
                RuntimeEnvironment.setQualifiers(qualifiers)
                RuntimeEnvironment.setFontScale(if (index == 2) 2f else 1f)
                val fixture = provisioningLayoutFixture(folder, if (index == 2) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR)
                try {
                    val dialog = showPasswordForm(fixture)

                    val root = dialog.window!!.decorView
                    root.layoutDirection = if (index == 2) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    val width = if (index == 2) 640 else 360
                    val height = if (index == 2) 320 else 800
                    assertEquals(if (index == 2) "fr" else "en", root.resources.configuration.locales[0].language)
                    assertEquals(if (index == 2) 2f else 1f, root.resources.configuration.fontScale, 0f)
                    assertEquals(if (index == 2) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR, root.layoutDirection)
                    assertEquals(if (index == 1) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO,
                        root.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    var ready = false
                    repeat(3) {
                        measure(root, width, height)
                        ready = !root.viewTreeObserver.dispatchOnPreDraw()
                        settle()
                    }
                    assertTrue("Backup preview draw is not cancelled", ready)
                    for (id in listOf(R.string.vpn0wn_backup_password_note, R.string.vpn0wn_backup_password,
                        R.string.vpn0wn_backup_confirm_password, R.string.vpn0wn_backup_show_passwords)) {
                        val value = fixture.activity.getString(id)
                        val label = if (id == R.string.vpn0wn_backup_password || id == R.string.vpn0wn_backup_confirm_password)
                            value.uppercase(java.util.Locale.ROOT) else value
                        assertProfileTextReachable(text(root, label))
                    }
                    for (id in listOf(R.id.vpn0wn_backup_password, R.id.vpn0wn_backup_confirm_password)) {
                        val input = dialog.findViewById<EditText>(id)!!
                        assertTrue(input.requestFocus())
                        assertProfileViewReachable(input)
                    }
                    for (button in listOf(DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_POSITIVE)) {
                        val action = dialog.getButton(button)
                        assertTrue(action.height >= 48 * action.resources.displayMetrics.density)
                        assertProfileViewReachable(action)
                        assertProfileTextReachable(action)
                    }
                    val project = File(requireNotNull(System.getProperty("user.dir"))).let { if (it.name == "ui") it.parentFile else it }
                    val directory = File(project, ".artifacts/backup-export-previews").apply { mkdirs() }
                    val guidance = dialog.findViewById<TextView>(R.id.vpn0wn_backup_progress)!!
                    val scroll = generateSequence(guidance.parent as View) { it.parent as? View }.filterIsInstance<ScrollView>().first()
                    for ((edge, position) in listOf("top" to 0, "bottom" to scroll.getChildAt(0).height)) {
                        scroll.scrollTo(0, position)
                        repeat(3) {
                            measure(root, width, height)
                            ready = !root.viewTreeObserver.dispatchOnPreDraw()
                            settle()
                        }
                        assertTrue("Backup capture draw is not cancelled", ready)
                        val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
                        try {
                            root.draw(android.graphics.Canvas(bitmap))
                            File(directory, "password-$index-$edge.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        } finally { bitmap.recycle() }
                    }
                } finally { fixture.close() }
            }
        } finally { RuntimeEnvironment.setFontScale(1f) }
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

    private fun showPasswordForm(fixture: VpsProvisioningLayoutFixture): AlertDialog {
        val type = VpsProvisioningFragment::class.java.declaredClasses.single { it.simpleName == "BackupAction" }
        val action = type.getDeclaredConstructor(StoredVpsServer::class.java, View::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(VpsProfileFixture.server, fixture.root, false)
        VpsProvisioningFragment::class.java.getDeclaredField("backupAction").apply { isAccessible = true }.set(fixture.fragment, action)
        VpsProvisioningFragment::class.java.getDeclaredMethod("showBackupPassword", type).apply { isAccessible = true }.invoke(fixture.fragment, action)
        return VpsProvisioningFragment::class.java.getDeclaredField("backupDialog").apply { isAccessible = true }.get(fixture.fragment) as AlertDialog
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
