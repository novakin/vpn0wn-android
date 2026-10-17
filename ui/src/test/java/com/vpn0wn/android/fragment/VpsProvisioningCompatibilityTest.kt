/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.fragment

import android.app.Application
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.vpn0wn.android.R
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// Robolectric retains SDK asset maps within each class; keep API-24/28 checks separate from API-29 workflows.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class VpsProvisioningCompatibilityTest {
    @get:Rule val folder = TemporaryFolder()

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    @Config(sdk = [24, 28])
    fun technicalLabelsUseBundledRegularFontAcrossSupportedApis() = runTest {
        val fixture = fixture()
        try {
            val title = text(fixture.root, fixture.activity.getString(R.string.vpn0wn_servers_title).lowercase(java.util.Locale.ROOT))
            assertFalse("Existing title omits extra font padding", title.includeFontPadding)
            val add = text(fixture.root, fixture.activity.getString(R.string.vpn0wn_add_server).removePrefix("+ ").trim())
            assertTrue(clickableAncestor(add).performClick())
            settle()
            val label = text(fixture.root, fixture.activity.getString(R.string.vpn0wn_label_host).uppercase(java.util.Locale.ROOT))
            assertEquals("Bundled regular face", ResourcesCompat.getFont(fixture.activity, R.font.jetbrains_mono_regular), label.typeface)
            assertEquals("Shared mono features", "ss01, zero, tnum", label.fontFeatureSettings)
            assertFalse("Existing label omits extra font padding", label.includeFontPadding)
            assertTrue(fixture.peer.connections.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Config(sdk = [24, 28])
    @Test fun busyStatusCanCancelAndARealPeerCannotExportAPlaceholderProfile() = runTest {
        val fixture = fixture()
        try {
            fillAccess(fixture)
            click(fixture, R.string.vpn0wn_continue)
            fixture.model.state.first { !it.busy && it.detect != null }
            fixture.model.leave()
            settle()
            clickServer(fixture)
            fixture.peer.blockStatus = CompletableDeferred()
            val refresh = fixture.root.findViewById<View>(R.id.vpn0wn_vps_refresh_status)
            assertButtonAction(refresh)
            assertTrue(refresh.requestFocus())
            assertTrue(refresh.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
            assertTrue(refresh.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)))
            fixture.peer.statusEntered.await()
            settle()
            assertTrue(text(fixture.root, fixture.activity.getString(R.string.vpn0wn_cancel_check)).isShown)
            val cancel = fixture.root.findViewById<View>(R.id.vpn0wn_vps_refresh_status)
            assertButtonAction(cancel)
            cancel.performClick()
            fixture.model.state.first { !it.busy }
            fixture.peer.blockStatus = null
            settle()
            fixture.root.findViewById<View>(R.id.vpn0wn_vps_refresh_status).performClick()
            fixture.model.state.first { !it.busy && it.statusSnapshots.isNotEmpty() }
            settle()
            clickableAncestor(text(fixture.root, "phone")).performClick()
            settle()
            assertNotNull(text(fixture.root, "synthetic-peer-public-key"))
            click(fixture, R.string.vpn0wn_action_show_qr)
            assertNotNull(text(fixture.activity.window.decorView, fixture.activity.getString(R.string.vpn0wn_profile_unavailable)))
            assertFalse(fixture.peer.commands.any { it.contains("./'add-peer.sh'") || it.contains("./'backup.sh'") })
        } finally {
            fixture.peer.blockStatus?.complete(Unit)
            fixture.close()
        }
    }

    private fun assertButtonAction(view: View) {
        assertTrue(view.isFocusable)
        assertTrue(view.background != null && view.background.isStateful)
        assertNull(view.foreground)
        val node = view.createAccessibilityNodeInfo()
        try {
            assertEquals(Button::class.java.name, node.className.toString())
            assertTrue(node.isClickable)
            assertTrue(node.isEnabled)
        } finally {
            node.recycle()
        }
    }

    private suspend fun TestScope.fixture(): VpsProvisioningLayoutFixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return provisioningLayoutFixture(folder, View.LAYOUT_DIRECTION_LTR)
    }

    private fun TestScope.fillAccess(fixture: VpsProvisioningLayoutFixture) {
        val add = text(fixture.root, fixture.activity.getString(R.string.vpn0wn_add_server).removePrefix("+ ").trim())
        clickableAncestor(add).performClick()
        settle()
        input(fixture.root, fixture.activity.getString(R.string.vpn0wn_host_placeholder)).setText("198.51.100.42")
        input(fixture.root, "SHA256:…").setText("SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() }))
        click(fixture, R.string.vpn0wn_auth_password)
        input(fixture.root, fixture.activity.getString(R.string.vpn0wn_password_placeholder)).setText("synthetic-rotation-password")
    }

    private suspend fun TestScope.clickServer(fixture: VpsProvisioningLayoutFixture) {
        clickableAncestor(text(fixture.root, "198.51.100.42")).performClick()
        settle()
        fixture.model.state.first { !it.busy }
        settle()
        assertTrue(fixture.root.findViewById<View>(R.id.vpn0wn_vps_refresh_status).isEnabled)
    }

    private fun TestScope.click(fixture: VpsProvisioningLayoutFixture, resource: Int) {
        clickableAncestor(text(fixture.root, fixture.activity.getString(resource))).performClick()
        settle()
    }

    private fun TestScope.settle() {
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun input(root: View, hint: String): EditText = descendants(root).filterIsInstance<EditText>().single { it.hint.toString() == hint }
    private fun text(root: View, value: String): TextView = descendants(root).filterIsInstance<TextView>().first { it.text.toString() == value }
    private fun clickableAncestor(view: View): View = if (view.isClickable) view else clickableAncestor(view.parent as View)
    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }
}
