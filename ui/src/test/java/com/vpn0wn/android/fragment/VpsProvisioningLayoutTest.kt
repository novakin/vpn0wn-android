package com.vpn0wn.android.fragment

import android.app.Application
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.vpn0wn.android.Application as Vpn0wnApplication
import com.vpn0wn.android.R
import com.vpn0wn.android.model.TunnelTestEnvironment
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class VpsProvisioningLayoutTest {
    @get:Rule val folder = TemporaryFolder()

    @After fun tearDown() {
        RuntimeEnvironment.setFontScale(1f)
        Dispatchers.resetMain()
    }

    @Test fun credentialModesFitNormalText() = verifyCredentialLayout(1f)

    @Test fun credentialModesFitEnlargedText() = verifyCredentialLayout(2f)

    private fun verifyCredentialLayout(fontScale: Float) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (locale in listOf("en", "fr")) for (night in listOf("night", "notnight")) {
            for ((width, height) in listOf(320 to 640, 640 to 320)) {
                val orientation = if (width > height) "land" else "port"
                RuntimeEnvironment.setQualifiers("$locale-ldltr-w${width}dp-h${height}dp-$orientation-$night-mdpi")
                RuntimeEnvironment.setFontScale(fontScale)
                val environment = TunnelTestEnvironment(folder.newFolder(), backgroundScope)
                Vpn0wnApplication.getPreferencesDataStore().data.first()
                val controller = Robolectric.buildActivity(HostActivity::class.java)
                controller.get().setTheme(R.style.AppTheme)
                controller.setup()
                try {
                    val activity = controller.get()
                    val fragment = VpsProvisioningFragment()
                    activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
                    runCurrent()
                    shadowOf(Looper.getMainLooper()).idle()
                    val root = fragment.requireView()
                    measure(root, width, height)
                    val add = text(root, activity.getString(R.string.vpn0wn_add_server).removePrefix("+ ").trim())
                    assertTrue(clickableAncestor(add).performClick())
                    runCurrent()
                    measure(root, width, height)
                    val host = descendants(root).filterIsInstance<EditText>().single {
                        it.hint.toString() == activity.getString(R.string.vpn0wn_host_placeholder)
                    }
                    host.setText("198.51.100.42")
                    for (password in listOf(true, false)) {
                        val mode = if (password) R.string.vpn0wn_auth_password else R.string.vpn0wn_auth_ssh_key
                        assertTrue(text(root, activity.getString(mode), clickable = true).performClick())
                        runCurrent()
                        measure(root, width, height)
                        val scroll = descendants(root).filterIsInstance<ScrollView>().single()
                        val keyTab = text(root, activity.getString(R.string.vpn0wn_auth_ssh_key), clickable = true)
                        val passwordTab = text(root, activity.getString(R.string.vpn0wn_auth_password), clickable = true)
                        assertEquals("Aligned authentication segments", keyTab.height, passwordTab.height)
                        for (tab in listOf(R.string.vpn0wn_auth_ssh_key, R.string.vpn0wn_auth_password)) {
                            val control = text(root, activity.getString(tab), clickable = true)
                            assertTextFits(control)
                            assertTrue("Android auth touch target", control.height >= 48 * control.resources.displayMetrics.density)
                            assertReachable(control, scroll)
                        }
                        val note = if (password) R.string.vpn0wn_password_ephemeral_note else R.string.vpn0wn_private_key_keystore_note
                        val noteView = text(root, activity.getString(note))
                        assertTextFits(noteView)
                        assertReachable(noteView, scroll)
                        if (!password) {
                            val picker = text(root, activity.getString(R.string.vpn0wn_pick_key_file).uppercase(Locale.ROOT), clickable = true)
                            assertTextFits(picker)
                            assertReachable(picker, scroll)
                        }
                        val restoredHost = descendants(root).filterIsInstance<EditText>().single {
                            it.hint.toString() == activity.getString(R.string.vpn0wn_host_placeholder)
                        }
                        assertEquals("198.51.100.42", restoredHost.text.toString())
                        val next = text(root, activity.getString(R.string.vpn0wn_continue), clickable = true)
                        assertTextFits(next)
                        assertInside(next, root as ViewGroup)
                    }
                    assertTrue(text(root, activity.getString(R.string.vpn0wn_continue), clickable = true).performClick())
                    runCurrent()
                    measure(root, width, height)
                    assertTrue(text(root, activity.getString(R.string.vpn0wn_deploy_step_summary, 2,
                        activity.getString(R.string.vpn0wn_step_detect)).uppercase(Locale.ROOT)).isShown)
                } finally {
                    controller.pause().stop().destroy()
                    environment.close()
                }
            }
        }
    }

    private fun assertTextFits(view: TextView) {
        val layout = requireNotNull(view.layout)
        assertTrue("Complete text: ${view.text}", layout.lineCount > 0 && layout.getLineEnd(layout.lineCount - 1) == view.text.length)
        assertTrue("Text height: ${view.text}", layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
        val contentWidth = view.width - view.compoundPaddingLeft - view.compoundPaddingRight
        for (line in 0 until layout.lineCount) {
            assertEquals("No truncation: ${view.text}", 0, layout.getEllipsisCount(line))
            assertTrue("Text width: ${view.text}", layout.getLineLeft(line) >= 0 && layout.getLineRight(line) <= contentWidth)
        }
    }

    private fun assertReachable(view: View, scroll: ScrollView) {
        assertTrue("Usable scroll viewport", scroll.height >= view.height + scroll.paddingTop + scroll.paddingBottom)
        view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
        assertInside(view, scroll)
    }

    private fun assertInside(view: View, parent: ViewGroup) {
        val position = IntArray(2)
        val viewport = IntArray(2)
        view.getLocationInWindow(position)
        parent.getLocationInWindow(viewport)
        val bounds = Rect(position[0] - viewport[0], position[1] - viewport[1],
            position[0] - viewport[0] + view.width, position[1] - viewport[1] + view.height)
        assertTrue("Horizontally reachable: $bounds", bounds.left >= parent.paddingLeft && bounds.right <= parent.width - parent.paddingRight)
        assertTrue("Vertically reachable: $bounds", bounds.top >= parent.paddingTop && bounds.bottom <= parent.height - parent.paddingBottom)
    }

    private fun text(root: View, value: String, clickable: Boolean = false): TextView =
        descendants(root).filterIsInstance<TextView>().single { it.text.toString() == value && (!clickable || it.isClickable) }

    private fun clickableAncestor(view: View): View {
        var current = view
        while (!current.isClickable) current = current.parent as? View ?: error("Missing clickable ancestor")
        return current
    }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }

    private fun measure(view: View, width: Int, height: Int) {
        val density = view.resources.displayMetrics.density
        view.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    class HostActivity : AppCompatActivity()
}
