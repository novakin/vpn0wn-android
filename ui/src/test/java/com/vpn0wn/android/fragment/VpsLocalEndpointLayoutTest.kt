/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.fragment

import android.app.Application
import android.content.DialogInterface
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import com.vpn0wn.android.Application as Vpn0wnApplication
import com.vpn0wn.android.R
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.android.provisioning.StoredVpsServer
import com.vpn0wn.android.provisioning.VpsCredentialFileTest
import com.vpn0wn.android.provisioning.VpsLocalEndpointUpdate
import com.vpn0wn.android.provisioning.VpsProfileFixture
import com.vpn0wn.android.provisioning.VpsUserProfileFileTest
import com.vpn0wn.config.InetEndpoint
import java.io.File
import java.util.Locale
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
@Config(application = Application::class, sdk = [28], shadows = [VpsCredentialFileTest.NativeFiles::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class VpsLocalEndpointLayoutTest {
    @get:Rule val folder = TemporaryFolder()

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun nativeReviewKeepsPublicContextAndCompleteReachableText() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val qualifiers = RuntimeEnvironment.getQualifiers()
        val scale = RuntimeEnvironment.getApplication().resources.configuration.fontScale
        val direction = RuntimeEnvironment.getApplication().resources.configuration.layoutDirection
        val locale = Locale.getDefault()
        val previews = listOf(Preview("en", 320, 640, 1f), Preview("fr", 640, 320, 2f, true), Preview("ar", 320, 640, 2f, true))
        try {
            for (preview in previews) for (night in listOf(false, true)) {
                val case = preview.copy(night = night)
                val orientation = if (case.width > case.height) "land" else "port"
                RuntimeEnvironment.setQualifiers("${case.locale}-w${case.width}dp-h${case.height}dp-$orientation-${if (night) "night" else "notnight"}-mdpi")
                RuntimeEnvironment.setFontScale(case.scale)
                RuntimeEnvironment.setQualifiers(if (case.rtl) "+ldrtl" else "+ldltr")
                VpsUserProfileFileTest.resetNativeFiles()
                val fixture = provisioningLayoutFixture(folder, if (case.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR)
                fixture.activity.supportActionBar?.hide()
                try {
                    val stored = openLocalEndpointUser(fixture)
                    Vpn0wnApplication.getTunnelManager().create("phone-local", VpsProfileFixture.profile().config)
                    Vpn0wnApplication.getTunnelManager().create("other", TunnelTestEnvironment.profile())
                    captureActionAndChooser(fixture, case)
                    val dialog = review(fixture, stored)
                    val root = requireNotNull(dialog.window).decorView
                    layout(root, case)
                    assertConfiguration(root, case)
                    val scroll = requireNotNull(dialog.findViewById<ScrollView>(R.id.vpn0wn_local_endpoint_review))
                    val values = descendants(scroll).filterIsInstance<TextView>().toList()
                    assertEquals("Six native label/value rows", 12, values.size)
                    for (value in values) {
                        assertEquals(ResourcesCompat.getFont(value.context, R.font.jetbrains_mono_regular), value.typeface)
                        assertEquals("ss01, zero, tnum", value.fontFeatureSettings)
                        assertFalse(value.paint.isFakeBoldText)
                        assertFalse("Review is read-only", value.isClickable || value.isLongClickable || value.isFocusable)
                        assertTextFits(value)
                        for (line in 0 until value.lineCount) revealLine(value, line, root, case)
                    }
                    for (socket in listOf(OLD_ENDPOINT, NEW_ENDPOINT)) {
                        val value = values.single { it.text.toString() == socket }
                        assertEquals("Numeric endpoint remains LTR", View.TEXT_DIRECTION_LTR, value.textDirection)
                    }
                    capture(root, case, "actions")
                    for ((name, id) in listOf("confirm" to DialogInterface.BUTTON_POSITIVE, "cancel" to DialogInterface.BUTTON_NEGATIVE)) {
                        val button = dialog.getButton(id)
                        button.requestRectangleOnScreen(Rect(button.scrollX, button.scrollY,
                            button.scrollX + button.width, button.scrollY + button.height), true)
                        layout(root, case)
                        assertTrue(button.isEnabled && button.isClickable && button.isFocusable)
                        val minimum = MINIMUM_TARGET_DP * button.resources.displayMetrics.density
                        assertTrue("Native dialog target width", button.width >= minimum)
                        assertTrue("Native dialog target height", button.height >= minimum)
                        assertTrue("Native action fully visible: ${visibility(button)}", fullyVisible(button))
                        assertTextFits(button)
                        capture(root, case, "action-$name")
                    }
                    val panel = scroll.getChildAt(0) as ViewGroup
                    for (index in values.indices) for (other in index + 1 until values.size) {
                        assertFalse("Label/value rows do not overlap", Rect.intersects(bounds(panel, values[index]), bounds(panel, values[other])))
                    }
                    scroll.scrollTo(0, 0)
                    assertEquals("Review body reaches its top", 0, scroll.scrollY)
                    assertTrue("Complete name row is visible at the top", fullyVisible(values[0]) && fullyVisible(values[1]))
                    capture(root, case, "top")
                    for ((name, text) in listOf("server-key" to SERVER_KEY, "client-key" to CLIENT_KEY, "old-socket" to OLD_ENDPOINT, "new-socket" to NEW_ENDPOINT)) {
                        val value = values.single { it.text.toString() == text }
                        revealValue(value, root, case)
                        capture(root, case, name)
                    }
                    val scope = values.single { it.text.toString() == fixture.activity.getString(R.string.vpn0wn_local_endpoint_scope) }
                    val linesPerFrame = maxOf(1, (scroll.height - scroll.paddingTop - scroll.paddingBottom) /
                        maxOf(1, scope.layout.getLineBottom(0) - scope.layout.getLineTop(0)) - 1)
                    for (line in 0 until scope.lineCount step linesPerFrame) {
                        revealLine(scope, minOf(scope.lineCount - 1, line + linesPerFrame - 1), root, case)
                        capture(root, case, "scope-$line")
                    }
                } finally { fixture.close() }
            }
        } finally {
            RuntimeEnvironment.setQualifiers(qualifiers)
            RuntimeEnvironment.setFontScale(scale)
            RuntimeEnvironment.setQualifiers(if (direction == View.LAYOUT_DIRECTION_RTL) "+ldrtl" else "+ldltr")
            Locale.setDefault(locale)
        }
    }

    private suspend fun review(fixture: VpsProvisioningLayoutFixture, server: StoredVpsServer): AlertDialog {
        val tunnel = requireNotNull(Vpn0wnApplication.getTunnelManager().getTunnels()["phone-local"])
        val profile = tunnel.getConfigAsync()
        val review = VpsLocalEndpointUpdate.create(tunnel, profile, SERVER_KEY, CLIENT_KEY, InetEndpoint.parse(NEW_ENDPOINT))
        val actionClass = Class.forName("com.vpn0wn.android.fragment.VpsProvisioningFragment\$EndpointAction")
        val action = actionClass.getDeclaredConstructor(StoredVpsServer::class.java, String::class.java, String::class.java, View::class.java)
            .apply { isAccessible = true }.newInstance(server, SERVER_KEY, CLIENT_KEY, fixture.root)
        actionClass.getDeclaredField("review").apply { isAccessible = true }.set(action, review)
        VpsProvisioningFragment::class.java.getDeclaredField("endpointAction").apply { isAccessible = true }.set(fixture.fragment, action)
        VpsProvisioningFragment::class.java.getDeclaredMethod("showEndpointReview", actionClass, VpsLocalEndpointUpdate::class.java)
            .apply { isAccessible = true }.invoke(fixture.fragment, action, review)
        return VpsProvisioningFragment::class.java.getDeclaredField("endpointDialog").apply { isAccessible = true }
            .get(fixture.fragment) as AlertDialog
    }

    private fun TestScope.captureActionAndChooser(fixture: VpsProvisioningLayoutFixture, case: Preview) {
        val root = fixture.root.rootView
        layout(root, case)
        assertConfiguration(root, case)
        val action = requireNotNull(fixture.root.findViewById<View>(R.id.vpn0wn_vps_local_endpoint_update))
        action.requestRectangleOnScreen(Rect(0, 0, action.width, action.height), true)
        layout(root, case)
        val label = descendants(action).filterIsInstance<TextView>().single()
        assertEquals(fixture.activity.getString(R.string.vpn0wn_local_endpoint_update), label.text.toString())
        assertEquals(ResourcesCompat.getFont(label.context, R.font.jetbrains_mono_regular), label.typeface)
        assertEquals("ss01, zero, tnum", label.fontFeatureSettings)
        assertTextFits(label)
        assertTrue("Actual User row is fully reachable", fullyVisible(action))
        assertTrue("Actual User row is operable", action.isEnabled && action.isClickable && action.isFocusable)
        val minimum = MINIMUM_TARGET_DP * action.resources.displayMetrics.density
        assertTrue("Actual User row has Android target height", action.height >= minimum)
        val parent = action.parent as View
        assertEquals("Actual User row spans available width", parent.width - parent.paddingLeft - parent.paddingRight, action.width)
        val node = action.createAccessibilityNodeInfo()
        try {
            assertEquals("android.widget.Button", node.className.toString())
            assertTrue(node.isClickable && node.isEnabled)
        } finally { node.recycle() }
        capture(root, case, "user-action")
        action.performClick()
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        val chooser = VpsProvisioningFragment::class.java.getDeclaredField("endpointDialog").apply { isAccessible = true }
            .get(fixture.fragment) as AlertDialog
        val chooserRoot = requireNotNull(chooser.window).decorView
        layout(chooserRoot, case)
        assertConfiguration(chooserRoot, case)
        val list = chooser.listView
        assertEquals("Both eligible and ineligible names stay selectable", setOf("other", "phone-local"),
            (0 until list.adapter.count).map { list.adapter.getItem(it).toString() }.toSet())
        assertNotNull("Native chooser owns selection", list.onItemClickListener)
        assertEquals("Both native choices are laid out", 2, list.childCount)
        for (index in 0 until list.childCount) {
            val choice = list.getChildAt(index)
            assertTrue("Complete native choice is visible", fullyVisible(choice))
            assertTrue("Native choice target height", choice.height >= minimum)
            for (text in descendants(choice).filterIsInstance<TextView>()) assertTextFits(text)
        }
        val cancel = chooser.getButton(DialogInterface.BUTTON_NEGATIVE)
        assertTrue("Chooser Cancel is fully visible: ${visibility(cancel)}", fullyVisible(cancel))
        assertTrue("Chooser Cancel target height", cancel.height >= minimum)
        assertTextFits(cancel)
        capture(chooserRoot, case, "chooser")
        cancel.performClick()
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun TestScope.layout(root: View, case: Preview) {
        var ready = false
        repeat(LAYOUT_PASSES) {
            root.measure(View.MeasureSpec.makeMeasureSpec(case.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(case.height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            ready = !root.viewTreeObserver.dispatchOnPreDraw()
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue("Native traversal permits drawing", ready)
    }

    private fun assertConfiguration(root: View, case: Preview) {
        val config = root.resources.configuration
        assertEquals(case.locale, config.locales[0].language)
        assertEquals(case.scale, config.fontScale, 0f)
        val direction = if (case.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        assertEquals(direction, config.layoutDirection)
        assertEquals(direction, root.layoutDirection)
        assertEquals(if (case.night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
            config.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertEquals("Strict dialog window width", case.width, root.width)
        assertEquals("Strict dialog window height", case.height, root.height)
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
        assertTrue("Native line is reachable: ${view.text}", view.getGlobalVisibleRect(visible))
        assertTrue("Complete line is visible: $actual within $visible; ${view.text}", visible.contains(actual))
    }

    private fun TestScope.revealValue(value: TextView, root: View, case: Preview) {
        val scroll = generateSequence(value.parent as? View) { it.parent as? View }.filterIsInstance<ScrollView>().single()
        val target = (value.parent as View).takeIf { it.height <= scroll.height - scroll.paddingTop - scroll.paddingBottom } ?: value
        if (target.height <= scroll.height - scroll.paddingTop - scroll.paddingBottom) {
            target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true)
            layout(root, case)
            assertTrue("Complete fitting review row visible", fullyVisible(target))
        } else revealLine(value, value.lineCount - 1, root, case)
    }

    private fun assertTextFits(view: TextView) {
        val text = requireNotNull(view.layout)
        val width = view.width - view.compoundPaddingLeft - view.compoundPaddingRight
        val height = view.height - view.compoundPaddingTop - view.compoundPaddingBottom
        assertTrue("Complete characters: ${view.text}", text.lineCount > 0 && text.getLineEnd(text.lineCount - 1) == text.text.length)
        assertTrue("Complete text height: ${view.text}", text.height <= height)
        for (line in 0 until text.lineCount) {
            assertEquals("No ellipsis: ${view.text}; ${visibility(view)} line=${text.getLineLeft(line)},${text.getLineRight(line)} layout=${text.width}x${text.height}",
                0, text.getEllipsisCount(line))
            assertTrue("Complete line width: ${view.text}", text.getLineLeft(line) - view.scrollX >= -INK_TOLERANCE_PX &&
                text.getLineRight(line) - view.scrollX <= width + INK_TOLERANCE_PX)
        }
        val bitmap = Bitmap.createBitmap(width + INK_MARGIN_PX * 2, height + INK_MARGIN_PX * 2, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.translate((INK_MARGIN_PX - view.scrollX).toFloat(), (INK_MARGIN_PX - view.scrollY).toFloat())
            text.draw(canvas)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("Native glyphs painted", pixels.any { it ushr ALPHA_SHIFT != 0 })
            assertTrue("Complete painted glyphs: ${view.text}", pixels.indices.all {
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
        // Android's local-visible rectangle includes the view's own content scroll.
        visible.offset(-view.scrollX, -view.scrollY)
        return visible == Rect(0, 0, view.width, view.height)
    }

    private fun visibility(view: View): String {
        val local = Rect()
        val global = Rect()
        view.getLocalVisibleRect(local)
        view.getGlobalVisibleRect(global)
        return "local=$local global=$global ancestors=" + generateSequence(view) { it.parent as? View }
            .joinToString { "${it.javaClass.simpleName}(${it.left},${it.top},${it.right},${it.bottom}; scroll=${it.scrollX},${it.scrollY})" }
    }

    private fun capture(root: View, case: Preview, position: String) {
        assertConfiguration(root, case)
        val project = File(requireNotNull(System.getProperty("user.dir"))).let { if (it.name == "ui") it.parentFile else it }
        val output = System.getenv("VPN0WN_LOCAL_ENDPOINT_OUTPUT")?.let(::File) ?: File(project, ".artifacts/local-endpoint-previews")
        assertTrue(output.isDirectory || output.mkdirs())
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            File(output, "${case.locale}-${case.width}x${case.height}-${case.scale}-${if (case.night) "night" else "day"}-$position.png")
                .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun bounds(root: ViewGroup, child: View): Rect = Rect(0, 0, child.width, child.height).also { root.offsetDescendantRectToMyCoords(child, it) }
    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }
    private data class Preview(val locale: String, val width: Int, val height: Int, val scale: Float,
        val rtl: Boolean = false, val night: Boolean = false)

    private companion object {
        val SERVER_KEY = VpsProfileFixture.serverKey.publicKey.toBase64()
        val CLIENT_KEY = VpsProfileFixture.client.publicKey.toBase64()
        const val OLD_ENDPOINT = "203.0.113.10:51820"
        const val NEW_ENDPOINT = "[2001:db8::55]:51821"
        const val MINIMUM_TARGET_DP = 48
        const val LAYOUT_PASSES = 3
        const val INK_MARGIN_PX = 20
        const val INK_TOLERANCE_PX = 1
        const val ALPHA_SHIFT = 24
    }
}
