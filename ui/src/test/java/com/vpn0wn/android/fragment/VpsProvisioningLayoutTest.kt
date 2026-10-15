package com.vpn0wn.android.fragment

import android.app.Application
import android.graphics.Rect
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
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
import kotlinx.coroutines.test.advanceUntilIdle
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
import org.robolectric.android.controller.ActivityController
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
            for (direction in listOf("ldltr", "ldrtl")) for ((width, height) in listOf(320 to 640, 640 to 320)) {
                val orientation = if (width > height) "land" else "port"
                RuntimeEnvironment.setQualifiers("$locale-$direction-w${width}dp-h${height}dp-$orientation-$night-mdpi")
                RuntimeEnvironment.setFontScale(fontScale)
                val environment = TunnelTestEnvironment(folder.newFolder(), backgroundScope)
                var controller: ActivityController<HostActivity>? = null
                try {
                    Vpn0wnApplication.getPreferencesDataStore().data.first()
                    val hostController = Robolectric.buildActivity(HostActivity::class.java)
                    controller = hostController
                    hostController.get().setTheme(R.style.AppTheme)
                    hostController.setup()
                    val activity = hostController.get()
                    activity.window.decorView.layoutDirection = if (direction == "ldrtl") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    val fragment = VpsProvisioningFragment()
                    activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
                    runCurrent()
                    shadowOf(Looper.getMainLooper()).idle()
                    val root = fragment.requireView() as ViewGroup
                    measure(root, width, height)
                    assertEquals("Requested layout direction", if (direction == "ldrtl") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR,
                        root.layoutDirection)
                    val add = text(root, activity.getString(R.string.vpn0wn_add_server).removePrefix("+ ").trim())
                    assertEquals("Localized fleet icon names", setOf(activity.getString(R.string.vpn0wn_navigate_back),
                        activity.getString(R.string.vpn0wn_logs_title), activity.getString(R.string.settings)),
                        descendants(root).filterIsInstance<ImageButton>().map { it.contentDescription.toString() }.toSet())
                    for (icon in descendants(root).filterIsInstance<ImageButton>()) {
                        assertAccessibleAction(icon, ImageButton::class.java.name)
                        assertTrue("Localized icon name", !icon.contentDescription.isNullOrBlank())
                        assertEquals("Existing visible icon width", 16, icon.width - icon.paddingLeft - icon.paddingRight)
                        assertEquals("Existing visible icon height", 16, icon.height - icon.paddingTop - icon.paddingBottom)
                    }
                    assertAccessibleAction(clickableAncestor(add), android.widget.Button::class.java.name)
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
                            val selected = tab == mode
                            assertAccessibleAction(control, android.widget.RadioButton::class.java.name, selected)
                            assertEquals("Selected auth mode exposed", selected, control.isSelected)
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
                            assertAccessibleAction(picker, android.widget.Button::class.java.name)
                            assertReachable(picker, scroll)
                        }
                        val restoredHost = descendants(root).filterIsInstance<EditText>().single {
                            it.hint.toString() == activity.getString(R.string.vpn0wn_host_placeholder)
                        }
                        assertEquals("198.51.100.42", restoredHost.text.toString())
                        val port = descendants(root).filterIsInstance<EditText>().single {
                            (it.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER
                        }
                        assertEquals("22", port.text.toString())
                        assertTrue("Numeric port retains a rendered text layout", port.layout.lineCount > 0)
                        val portWidth = port.width - port.compoundPaddingLeft - port.compoundPaddingRight
                        assertTrue("Numeric port glyphs remain in its visible horizontal content", port.layout.getLineLeft(0) - port.scrollX >= 0 &&
                            port.layout.getLineRight(0) - port.scrollX <= portWidth)
                        assertReachable(port, scroll)
                        val next = text(root, activity.getString(R.string.vpn0wn_continue), clickable = true)
                        assertTextFits(next)
                        assertAccessibleAction(next, android.widget.Button::class.java.name)
                        assertInside(next, root)
                    }
                    var trust = clickableAncestor(text(root, activity.getString(R.string.vpn0wn_trust_server_key)))
                    assertAccessibleAction(trust, android.widget.Switch::class.java.name, true)
                    assertTrue("Toggle has its localized name", trust.contentDescription.toString().contains(activity.getString(R.string.vpn0wn_trust_server_key)))
                    assertTrue(trust.performClick())
                    runCurrent()
                    measure(root, width, height)
                    trust = clickableAncestor(text(root, activity.getString(R.string.vpn0wn_trust_server_key)))
                    assertAccessibleAction(trust, android.widget.Switch::class.java.name, false)
                    assertTrue(trust.performClick())
                    runCurrent()
                    val stages = listOf(R.string.vpn0wn_step_ssh, R.string.vpn0wn_step_detect, R.string.vpn0wn_step_plan,
                        R.string.vpn0wn_step_apply, R.string.vpn0wn_step_import)
                    for (stage in stages.indices) {
                        measure(root, width, height)
                        assertTrue(text(root, activity.getString(R.string.vpn0wn_deploy_step_summary, stage + 1,
                            activity.getString(stages[stage])).uppercase(Locale.ROOT)).isShown)
                        assertTrackerFitsAndIsReachable(root, stage)
                        if (fontScale == 1f) {
                            val tracker = descendants(root).filterIsInstance<HorizontalScrollView>().single()
                            assertEquals("Normal tracker fills available width without overflow", tracker.width, tracker.getChildAt(0).width)
                        }
                        if (stage == 3) {
                            assertTrue("Continue waits for the existing apply preview", !text(root,
                                activity.getString(R.string.vpn0wn_continue), clickable = true).isEnabled)
                            val apply = text(root, activity.getString(R.string.vpn0wn_apply_plan_button))
                            assertTextFits(apply)
                            assertReachable(apply, descendants(root).filterIsInstance<ScrollView>().single())
                            assertTrue(clickableAncestor(apply).performClick())
                            runCurrent()
                            advanceUntilIdle()
                            measure(root, width, height)
                        }
                        val actionResource = when (stage) {
                            2 -> R.string.vpn0wn_confirm_apply
                            4 -> R.string.vpn0wn_finish_close
                            else -> R.string.vpn0wn_continue
                        }
                        val action = text(root, activity.getString(actionResource), clickable = true)
                        assertTextFits(action)
                        assertAccessibleAction(action, android.widget.Button::class.java.name)
                        assertInside(action, root)
                        assertTrue("Current stage action enabled", action.isEnabled)
                        assertTrue(action.performClick())
                        runCurrent()
                    }
                } finally {
                    try {
                        controller?.pause()?.stop()?.destroy()
                    } finally {
                        environment.close()
                    }
                }
            }
        }
    }

    private fun assertAccessibleAction(view: View, role: String, checked: Boolean? = null) {
        val density = view.resources.displayMetrics.density
        assertTrue("Android action target", view.width >= 48 * density && view.height >= 48 * density)
        assertTrue("Keyboard focusable action", view.isFocusable)
        assertTrue("Native pressed/focus feedback", view.foreground != null && view.foreground.isStateful)
        val node = view.createAccessibilityNodeInfo()
        try {
            assertEquals("Native action role", role, node.className.toString())
            assertTrue("Accessible click action", node.isClickable)
            if (checked != null) {
                assertTrue("Authentication choice is checkable", node.isCheckable)
                assertEquals("Authentication state exposed", checked, node.isChecked)
            }
        } finally {
            node.recycle()
        }
    }

    private fun assertTrackerFitsAndIsReachable(root: View, activeStage: Int) {
        val tracker = descendants(root).filterIsInstance<HorizontalScrollView>().single()
        val row = tracker.getChildAt(0) as ViewGroup
        val columns = (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<ViewGroup>()
        assertEquals("All five stages remain available", 5, columns.size)
        assertTrue("Tracker fills its viewport or uses native overflow", row.width >= tracker.width)
        assertTrue("Step widths remain evenly distributed", columns.maxOf { it.width } - columns.minOf { it.width } <= 1)
        assertEquals("Steps span content from the first gutter", row.paddingLeft, columns.minOf { it.left })
        assertEquals("Steps span content through the final gutter", row.width - row.paddingRight, columns.maxOf { it.right })
        assertInside(columns[activeStage], tracker)
        for (column in columns) {
            val badgeRow = column.getChildAt(0) as ViewGroup
            val badge = (0 until badgeRow.childCount).map { badgeRow.getChildAt(it) }.filterIsInstance<TextView>().single()
            val label = (0 until column.childCount).map { column.getChildAt(it) }.filterIsInstance<TextView>().last()
            assertTextFits(badge)
            assertTextFits(label)
            assertEquals("Stage names remain single line", 1, label.lineCount)
            assertTrue("Existing minimum step width", column.width >= 48 * column.resources.displayMetrics.density)
            assertTrue("Existing minimum badge", badge.width >= 22 * badge.resources.displayMetrics.density &&
                badge.height >= 22 * badge.resources.displayMetrics.density)
            tracker.requestChildRectangleOnScreen(row, Rect(column.left, column.top, column.right, column.bottom), true)
            assertInside(column, tracker)
        }
    }

    private fun assertTextFits(view: TextView) {
        val layout = requireNotNull(view.layout)
        assertTrue("Complete text: ${view.text}", layout.lineCount > 0 && layout.getLineEnd(layout.lineCount - 1) == view.text.length)
        assertTrue("Text height: ${view.text}; view=${view.width}x${view.height}; layout=${layout.width}x${layout.height}; " +
            "lines=${layout.lineCount}; verticalPadding=${view.compoundPaddingTop + view.compoundPaddingBottom}",
            layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
        val contentWidth = view.width - view.compoundPaddingLeft - view.compoundPaddingRight
        for (line in 0 until layout.lineCount) {
            assertEquals("No truncation: ${view.text}", 0, layout.getEllipsisCount(line))
            assertTrue("Text width: ${view.text}; line=$line; scrollX=${view.scrollX}; " +
                "glyphs=${layout.getLineLeft(line)}..${layout.getLineRight(line)}; content=$contentWidth",
                layout.getLineLeft(line) - view.scrollX >= 0 && layout.getLineRight(line) - view.scrollX <= contentWidth)
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
