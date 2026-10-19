/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.fragment

import android.app.Application
import android.content.DialogInterface
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import com.vpn0wn.android.Application as Vpn0wnApplication
import com.vpn0wn.android.R
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.AuthMode
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
internal class VpsHostTrustDialogLayoutTest : VpsProvisioningWorkflowFixture() {
    @After fun resetPreviewConfiguration() {
        RuntimeEnvironment.setQualifiers("")
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test fun trustDialogKeepsCompleteIdentityAndActionsReachableBeforeAuthentication() = runTest {
        for (locale in listOf("en", "fr")) for (night in listOf(false, true)) for (enlarged in listOf(false, true)) {
            val width = if (enlarged) 640 else 320
            val height = if (enlarged) 320 else 640
            val direction = if (enlarged) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            val orientation = if (enlarged) "land" else "port"
            val theme = if (night) "night" else "notnight"
            RuntimeEnvironment.setQualifiers("$locale-w${width}dp-h${height}dp-$orientation-$theme-mdpi")
            RuntimeEnvironment.setFontScale(if (enlarged) 2f else 1f)
            // Robolectric reapplies locale direction after combined qualifiers.
            RuntimeEnvironment.setQualifiers(if (enlarged) "+ldrtl" else "+ldltr")
            assertConfiguration(RuntimeEnvironment.getApplication().resources.configuration, locale, night, enlarged)
            val fixture = fixture()
            try {
                val add = fixture.activity.getString(R.string.vpn0wn_add_server).removePrefix("+ ").trim()
                clickableAncestor(text(fixture.root, add)).performClick()
                settle()
                fixture.model.edit(fixture.model.draft.copy(host = "198.51.100.42", authMode = AuthMode.PASSWORD,
                    password = PASSWORD.toCharArray()))
                requireNotNull(fixture.model.detect()).join()
                settle()
                val confirmation = requireNotNull(fixture.model.state.value.hostTrustConfirmation)
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                assertTrue("Current host trust dialog is showing", dialog.isShowing)
                val root = requireNotNull(dialog.window).decorView
                layoutPreview(root, width, height)
                assertConfiguration(root.resources.configuration, locale, night, enlarged)
                assertEquals("Settled dialog direction", direction, root.layoutDirection)
                val message = requireNotNull(dialog.findViewById<TextView>(android.R.id.message))
                val expected = fixture.activity.getString(R.string.vpn0wn_ssh_trust_confirmation,
                    "198.51.100.42", 22, confirmation.fingerprint)
                assertEquals("Complete identity and trust consequences", expected, message.text.toString())
                assertEquals(PROFILE_PIN, confirmation.fingerprint)
                assertTrue("Full endpoint is present", expected.contains("198.51.100.42:22"))
                assertTrue("Full fingerprint is present", expected.contains(confirmation.fingerprint))
                assertTextReachable(message)
                val title = requireNotNull(dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle))
                assertEquals(fixture.activity.getString(R.string.vpn0wn_ssh_trust_title), title.text.toString())
                assertTextReachable(title)
                for ((button, label) in listOf(DialogInterface.BUTTON_NEGATIVE to android.R.string.cancel,
                    DialogInterface.BUTTON_POSITIVE to R.string.vpn0wn_ssh_trust_and_connect)) {
                    val action = dialog.getButton(button)
                    assertEquals("Complete action label", fixture.activity.getString(label), action.text.toString())
                    val minimum = 48 * action.resources.displayMetrics.density
                    assertTrue("Trust action retains a 48dp target", action.width >= minimum && action.height >= minimum)
                    assertTextReachable(action)
                    assertViewReachable(action, Rect(0, 0, action.width, action.height))
                }
                val scroll = requireNotNull(dialog.findViewById<NestedScrollView>(androidx.appcompat.R.id.scrollView))
                val fingerprintEnd = expected.indexOf(confirmation.fingerprint) + confirmation.fingerprint.length - 1
                val fingerprintLine = message.layout.getLineForOffset(fingerprintEnd)
                val name = "$locale-$theme-${width}x$height-${if (enlarged) "2x-rtl" else "1x-ltr"}"
                scroll.scrollTo(0, 0)
                capture(root, width, height, "$name-top")
                message.requestRectangleOnScreen(Rect(0, message.layout.getLineTop(fingerprintLine), message.width,
                    message.layout.getLineBottom(fingerprintLine)).apply { offset(0, message.totalPaddingTop) }, true)
                capture(root, width, height, "$name-fingerprint")
                scroll.scrollTo(0, scroll.getChildAt(0).height)
                capture(root, width, height, "$name-bottom")
                assertEquals(listOf("198.51.100.42" to 22), fixture.peer.discoveries)
                assertTrue("Preview never creates an authenticated client", fixture.peer.connections.isEmpty())
                assertTrue("Preview dispatches no commands", fixture.peer.commands.isEmpty())
                assertNull(Vpn0wnApplication.getVpsServerStore().trustedHostFingerprint("198.51.100.42", 22))
            } finally { fixture.close() }
        }
    }

    private fun assertConfiguration(configuration: Configuration, locale: String, night: Boolean, enlarged: Boolean) {
        assertEquals("Actual locale", locale, configuration.locales[0].language)
        assertEquals("Actual font scale", if (enlarged) 2f else 1f, configuration.fontScale, 0f)
        assertEquals("Actual resource direction", if (enlarged) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR,
            configuration.layoutDirection)
        assertEquals("Actual theme", if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
            configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
    }

    private fun TestScope.assertTextReachable(view: TextView) {
        val layout = requireNotNull(view.layout)
        assertTrue("Text has painted lines", layout.lineCount > 0)
        assertEquals("Complete text is laid out", view.text.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue("Text height is not clipped", layout.height <= view.height - view.totalPaddingTop - view.totalPaddingBottom)
        for (line in 0 until layout.lineCount) {
            assertEquals("Text is not ellipsized", 0, layout.getEllipsisCount(line))
            val bounds = Rect(floor(layout.getLineLeft(line)).toInt(), layout.getLineTop(line),
                ceil(layout.getLineRight(line)).toInt(), layout.getLineBottom(line))
            bounds.offset(view.totalPaddingLeft, view.totalPaddingTop)
            assertViewReachable(view, bounds, contentBounds = true)
        }
    }

    private fun TestScope.assertViewReachable(view: View, bounds: Rect, contentBounds: Boolean = false) {
        view.requestRectangleOnScreen(Rect(bounds), true)
        settle()
        val location = IntArray(2).also(view::getLocationOnScreen)
        val target = Rect(bounds).apply {
            offset(location[0], location[1])
            if (contentBounds) offset(-view.scrollX, -view.scrollY)
        }
        val visible = Rect()
        assertTrue("Target is visible", view.getGlobalVisibleRect(visible))
        assertTrue("Complete target is reachable: target=$target visible=$visible", visible.contains(target))
    }

    private fun TestScope.layoutPreview(root: View, width: Int, height: Int) {
        var cancelled = true
        repeat(3) {
            val density = root.resources.displayMetrics.density
            root.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            cancelled = root.viewTreeObserver.dispatchOnPreDraw()
            settle()
        }
        assertFalse("Preview draw is not cancelled", cancelled)
    }

    private fun TestScope.capture(root: View, width: Int, height: Int, name: String) {
        layoutPreview(root, width, height)
        val project = File(requireNotNull(System.getProperty("user.dir"))).let { if (it.name == "ui") it.parentFile else it }
        val directory = File(project, ".artifacts/ssh-trust-previews").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
