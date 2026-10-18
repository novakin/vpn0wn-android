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
import android.widget.HorizontalScrollView
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import com.vpn0wn.android.R
import com.vpn0wn.android.provisioning.VpsBackupArchive
import com.vpn0wn.android.provisioning.VpsBackupDocument
import com.vpn0wn.android.provisioning.VpsBackupInputProvider
import com.vpn0wn.android.provisioning.VpsCredentialFileTest
import com.vpn0wn.android.provisioning.VpsFreshRestoreWorkflowTest
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.Step
import com.vpn0wn.android.provisioning.VpsRestoreFixture
import com.vpn0wn.android.provisioning.VpsRunningRestoreFixture
import com.vpn0wn.android.provisioning.VpsUserProfileFileTest
import com.vpn0wn.crypto.Key
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
class VpsFreshRestoreLayoutTest {
    @get:Rule val folder = TemporaryFolder()

    @After fun teardown() {
        RuntimeEnvironment.setQualifiers("")
        RuntimeEnvironment.setFontScale(1f)
        Dispatchers.resetMain()
    }

    @Test fun fourRealRestoreStepsAndNativeConfirmationRemainReadableAndReachable() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        VpsUserProfileFileTest.resetNativeFiles()
        val cases = listOf(
            Preview("en-day", "en-rUS-w360dp-h800dp-notnight-mdpi", 360, 800, 1f, View.LAYOUT_DIRECTION_LTR),
            Preview("en-night", "en-rUS-w360dp-h800dp-night-mdpi", 360, 800, 1f, View.LAYOUT_DIRECTION_LTR),
            Preview("fr-stress", "fr-ldrtl-w640dp-h320dp-land-notnight-mdpi", 640, 320, 2f, View.LAYOUT_DIRECTION_RTL),
            Preview("ar-compact", "ar-rSA-ldrtl-w320dp-h640dp-port-notnight-mdpi", 320, 640, 2f, View.LAYOUT_DIRECTION_RTL),
        ).flatMap { listOf(it, it.copy(id = "${it.id}-running", running = true)) } +
            Preview("en-long-review", "en-rUS-w360dp-h800dp-notnight-mdpi", 360, 800, 1f, View.LAYOUT_DIRECTION_LTR,
                running = true, large = true)
        for (case in cases) {
            RuntimeEnvironment.setQualifiers(case.qualifiers)
            RuntimeEnvironment.setFontScale(case.fontScale)
            // Robolectric 4.16 reapplies locale direction after combined qualifiers.
            RuntimeEnvironment.setQualifiers(if (case.direction == View.LAYOUT_DIRECTION_RTL) "+ldrtl" else "+ldltr")
            assertResourceConfiguration(RuntimeEnvironment.getApplication().resources.configuration, case)
            val fixture = provisioningLayoutFixture(folder, case.direction)
            try {
                if (case.running) {
                    fixture.peer.configExists = true
                    fixture.peer.restoreCheckOutput = VpsRunningRestoreFixture.response().apply {
                        if (case.large) {
                            val replacement = getJSONObject("plan").getJSONObject("replacement")
                            replacement.put("peerCount", 25).put("revokedCount", 24)
                            replacement.put("users", JSONArray().apply { repeat(24) { index ->
                                put(JSONObject().put("name", "phone_$index".padEnd(32, '_'))
                                    .put("publicKey", Key.fromBytes(ByteArray(32) { index.toByte() }).toBase64()).put("change", "removed"))
                            } })
                            replacement.put("discardedRevoked", JSONArray().apply { repeat(24) { index ->
                                put(JSONObject().put("archiveName", "retired_$index".padEnd(32, '_') + ".conf.00000000-0000-0000-0000-000000000001")
                                    .put("change", if (index < 23) "removed" else "replaced"))
                            } })
                        }
                    }.toString()
                }
                prepare(fixture)
                val complete = fixture.model.state.value
                for (step in Step.entries) {
                    setState(fixture.model, complete.copy(step = step))
                    settle()
                    VpsProvisioningFragment::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(fixture.fragment)
                    layout(fixture.root, case)
                    assertConfiguration(fixture.root, case)
                    val wordmark = requireNotNull(fixture.root.findViewById<android.widget.ImageView>(R.id.vpn0wn_vps_wordmark))
                    assertFalse("Restore retains the unmirrored brand artwork", requireNotNull(wordmark.drawable).isAutoMirrored)
                    assertReachable(wordmark)
                    assertTracker(fixture.root, case)
                    val expectedSummary = fixture.activity.getString(R.string.vpn0wn_restore_step_summary, step.ordinal + 1, STEPS.size,
                        fixture.activity.getString(STEPS[step.ordinal])).uppercase(Locale.ROOT)
                    assertTrue(descendants(fixture.root).filterIsInstance<TextView>().any { it.text.toString() == expectedSummary })
                    val action = fixture.root.findViewById<TextView>(R.id.vpn0wn_vps_bottom_action)
                    assertAction(action)
                    assertReachable(action)
                    if (step == Step.PLAN || step == Step.APPLY) {
                        val identity = descendants(fixture.root).filterIsInstance<TextView>()
                            .single { it.text.toString() == VpsFreshRestoreWorkflowTest.PLAN.serverPublicKey }
                        assertEquals(ResourcesCompat.getFont(identity.context, R.font.jetbrains_mono_regular), identity.typeface)
                        assertEquals("ss01, zero, tnum", identity.fontFeatureSettings)
                    }
                    assertTextReachable(fixture.root)
                    if (case.running && (step == Step.PLAN || step == Step.APPLY)) {
                        for ((name, resource) in listOf("warning" to R.string.vpn0wn_restore_replace_warning,
                            "effects" to R.string.vpn0wn_restore_replace_limits)) {
                            val content = fixture.activity.getString(resource)
                            val consequence = descendants(fixture.root).filterIsInstance<TextView>().single { it.text.toString() == content }
                            captureConsequence(fixture.root, consequence, case, "step-${step.ordinal + 1}", name, content)
                        }
                    }
                    val scroll = descendants(fixture.root).filterIsInstance<ScrollView>().single()
                    captureEdges(fixture.root, scroll, case, "step-${step.ordinal + 1}")
                }
                setState(fixture.model, complete)
                settle()
                val confirmation = requireNotNull(fixture.model.requestRestoreConfirmation())
                VpsProvisioningFragment::class.java.getDeclaredMethod("showRestoreConfirmation",
                    VpsProvisioningViewModel.RestoreConfirmation::class.java, View::class.java)
                    .apply { isAccessible = true }.invoke(fixture.fragment, confirmation, fixture.root)
                val dialog = VpsProvisioningFragment::class.java.getDeclaredField("restoreDialog")
                    .apply { isAccessible = true }.get(fixture.fragment) as AlertDialog
                val decor = requireNotNull(dialog.window).decorView
                layout(decor, case)
                assertConfiguration(decor, case)
                val message = dialog.findViewById<TextView>(android.R.id.message)!!
                val text = message.text.toString()
                if (case.running) {
                    val warning = fixture.activity.getString(R.string.vpn0wn_restore_replace_warning)
                    val effects = fixture.activity.getString(R.string.vpn0wn_restore_replace_limits)
                    assertTrue(text.contains(warning))
                    assertTrue(text.contains(effects))
                    val review = requireNotNull(confirmation.replacement)
                    for (part in review.users.map { it.publicKey } + review.discardedRevoked.map { it.archiveName }) {
                        assertTrue("Running review retains exact public identity $part", text.contains(part))
                        assertTrue("Warning precedes variable differences", text.indexOf(warning) < text.indexOf(part))
                        assertTrue("Effects precede variable differences", text.indexOf(effects) < text.indexOf(part))
                    }
                    for ((name, content) in listOf("warning" to warning, "effects" to effects)) {
                        captureConsequence(decor, message, case, "confirmation", name, content)
                    }
                }
                for (part in listOf(confirmation.ssh.host, confirmation.fingerprint, confirmation.summary.capturedAtUtc,
                    confirmation.plan.serverPublicKey, confirmation.plan.endpoint, confirmation.plan.port, confirmation.plan.cidr, confirmation.plan.dns))
                    assertTrue("Confirmation includes $part", text.contains(part))
                assertFalse(text.contains(VpsFreshRestoreWorkflowTest.SNAPSHOT.config))
                captureFrame(decor, case, "confirmation", "top")
                assertTextReachable(decor)
                for (buttonId in listOf(DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_POSITIVE)) {
                    val button = dialog.getButton(buttonId)
                    assertTrue("Native dialog target retains Android minimum", button.height >= 48 * button.resources.displayMetrics.density)
                    assertReachable(button)
                    val action = if (buttonId == DialogInterface.BUTTON_POSITIVE) "positive" else "cancel"
                    val bounds = Rect().also(button::getDrawingRect)
                    (decor as ViewGroup).offsetDescendantRectToMyCoords(button, bounds)
                    val viewport = Rect(0, 0, decor.width, decor.height)
                    val origin = IntArray(2).also(decor::getLocationOnScreen)
                    val windowFrame = Rect().also(decor::getWindowVisibleDisplayFrame)
                    val attributes = requireNotNull(dialog.window).attributes
                    println("Restore action ${case.id}/$action: bitmap=$viewport action=$bounds " +
                        "rootOrigin=${origin.toList()} visibleWindow=$windowFrame windowLayout=${attributes.width}x${attributes.height}")
                    assertTrue("Complete ${case.id}/$action rectangle fits captured bitmap: $bounds within $viewport", viewport.contains(bounds))
                    captureFrame(decor, case, "confirmation", action)
                }
                val scroll = descendants(decor).firstOrNull { it is ScrollView || it is androidx.core.widget.NestedScrollView }
                    ?: error("Native confirmation must retain scrolling")
                captureEdges(decor, scroll, case, "confirmation")
                val recordsLabel = fixture.activity.getString(R.string.vpn0wn_backup_revoked_count)
                val recordsStart = text.indexOf(recordsLabel, ignoreCase = true)
                assertTrue("Confirmation retains the revocation-record label", recordsStart >= 0)
                val recordsLayout = message.layout
                val firstLine = recordsLayout.getLineForOffset(recordsStart)
                val lastLine = recordsLayout.getLineForOffset(recordsStart + recordsLabel.length - 1)
                assertGlyphReachable(message, Rect(0, recordsLayout.getLineTop(firstLine) + message.totalPaddingTop, message.width,
                    recordsLayout.getLineBottom(lastLine) + message.totalPaddingTop))
                layout(decor, case)
                captureFrame(decor, case, "confirmation", "records")
                dialog.dismiss()
                assertTrue("Synthetic rendering never dispatches a restore", fixture.peer.commands.none { it.contains("'--confirm'") })
            } finally { fixture.close() }
        }
    }

    private suspend fun TestScope.prepare(fixture: VpsProvisioningLayoutFixture) {
        val source = VpsFreshRestoreWorkflowTest
        val bytes = VpsBackupArchive().encrypt(source.SNAPSHOT, source.PASSWORD.toCharArray())
        try { VpsBackupInputProvider(folder.newFile().apply { writeBytes(bytes) }).register(fixture.activity) } finally { bytes.fill(0) }
        requireNotNull(fixture.model.inspectBackup(VpsBackupDocument(fixture.activity.contentResolver, VpsBackupInputProvider.URI), source.PASSWORD.toCharArray())).join()
        assertTrue(fixture.model.beginRestoreFromInspection())
        fixture.peer.restorePlan = source.PLAN
        fixture.model.edit(fixture.model.draft.copy(host = "198.51.100.42", endpoint = source.PLAN.endpoint, port = source.PLAN.port,
            fingerprint = VpsRestoreFixture.pin, authMode = VpsProvisioningViewModel.AuthMode.PASSWORD))
        fixture.model.edit(fixture.model.draft.copy(password = "synthetic-ssh-password".toCharArray()))
        requireNotNull(fixture.model.detect()).join()
        requireNotNull(fixture.model.checkRestore()).join()
        fixture.model.reviewApply()
        settle()
        assertTrue(fixture.model.canRestore)
    }

    private fun setState(model: VpsProvisioningViewModel, state: VpsProvisioningViewModel.State) {
        @Suppress("UNCHECKED_CAST")
        val flow = VpsProvisioningViewModel::class.java.getDeclaredField("mutableState").apply { isAccessible = true }
            .get(model) as MutableStateFlow<VpsProvisioningViewModel.State>
        flow.value = state
    }

    private fun TestScope.assertTracker(root: View, case: Preview) {
        val tracker = descendants(root).filterIsInstance<HorizontalScrollView>().single()
        val row = tracker.getChildAt(0) as ViewGroup
        val columns = (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<ViewGroup>()
        assertEquals("Restore exposes four executable steps", 4, columns.size)
        assertEquals("Restore never shows prototype-only import", 0, descendants(tracker).filterIsInstance<TextView>()
            .count { it.text.toString() == root.context.getString(R.string.vpn0wn_step_import).uppercase(Locale.ROOT) })
        if (case.fontScale == 1f) assertEquals("Normal tracker spans available width", tracker.width, row.width)
        for ((index, column) in columns.withIndex()) {
            val label = descendants(column).filterIsInstance<TextView>().last()
            assertEquals(root.context.getString(STEPS[index]).uppercase(Locale.ROOT), label.text.toString())
            assertTrue("Stage has minimum native width", column.width >= 48 * root.resources.displayMetrics.density)
            assertReachable(label)
        }
    }

    private fun assertAction(action: TextView) {
        val density = action.resources.displayMetrics.density
        assertTrue(action.width >= 48 * density && action.height >= 48 * density)
        assertTrue(action.isFocusable && action.isClickable)
        assertNotNull(action.background)
        assertEquals("ss01, zero, tnum", action.fontFeatureSettings)
        val node = action.createAccessibilityNodeInfo()
        try { assertEquals(android.widget.Button::class.java.name, node.className.toString()); assertTrue(node.isClickable) } finally { node.recycle() }
    }

    private fun TestScope.assertTextReachable(root: View) {
        for (text in descendants(root).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE && it.text.isNotEmpty() }) {
            val painted = requireNotNull(text.layout) { "Native text must be measured: ${text.text}" }
            assertEquals("Complete native text is laid out", text.text.length, painted.getLineEnd(painted.lineCount - 1))
            assertTrue("Complete native text height fits", painted.height <= text.height - text.totalPaddingTop - text.totalPaddingBottom)
            if (text is EditText && text.maxLines == 1) {
                assertEditableGlyphsReachable(text)
                continue
            }
            for (line in 0 until painted.lineCount) {
                assertEquals("No native text ellipsis: id=${text.id}, class=${text.javaClass.name}, text=${text.text}",
                    0, painted.getEllipsisCount(line))
                val glyph = Rect(kotlin.math.floor(painted.getLineLeft(line)).toInt() + text.totalPaddingLeft, painted.getLineTop(line) + text.totalPaddingTop,
                    kotlin.math.ceil(painted.getLineRight(line)).toInt() + text.totalPaddingLeft, painted.getLineBottom(line) + text.totalPaddingTop)
                assertGlyphReachable(text, glyph)
            }
        }
    }

    private fun TestScope.assertEditableGlyphsReachable(text: EditText) {
        val original = text.text.toString()
        val selectionStart = text.selectionStart
        val selectionEnd = text.selectionEnd
        val scrollX = text.scrollX
        val scrollY = text.scrollY
        val painted = requireNotNull(text.layout)
        assertEquals("Single-line input retains all glyphs without ellipsis", 0, painted.getEllipsisCount(0))
        try {
            for (offset in original.indices) {
                text.setSelection(offset + 1)
                text.bringPointIntoView(offset + 1)
                val start = painted.getPrimaryHorizontal(offset)
                val end = painted.getPrimaryHorizontal(offset + 1)
                val glyph = Rect(kotlin.math.floor(minOf(start, end)).toInt() + text.totalPaddingLeft,
                    painted.getLineTop(0) + text.totalPaddingTop,
                    kotlin.math.ceil(maxOf(start, end)).toInt() + text.totalPaddingLeft, painted.getLineBottom(0) + text.totalPaddingTop)
                assertGlyphReachable(text, glyph)
            }
            assertEquals("Caret scrolling preserves the complete input", original, text.text.toString())
        } finally {
            if (selectionStart >= 0 && selectionEnd >= 0) text.setSelection(selectionStart, selectionEnd)
            settle()
            text.scrollTo(scrollX, scrollY)
        }
    }

    private fun TestScope.assertGlyphReachable(text: TextView, glyph: Rect) {
        text.requestRectangleOnScreen(Rect(glyph), true)
        settle()
        val position = IntArray(2).also(text::getLocationOnScreen)
        glyph.offset(position[0] - text.scrollX, position[1] - text.scrollY)
        val visible = Rect()
        assertTrue("Native text remains visible", text.getGlobalVisibleRect(visible))
        assertTrue("Complete glyph is reachable: text=${text.text} glyph=$glyph visible=$visible", visible.contains(glyph))
    }

    private fun TestScope.assertReachable(view: View) {
        view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
        settle()
        val position = IntArray(2).also(view::getLocationOnScreen)
        val visible = Rect()
        assertTrue(view.getGlobalVisibleRect(visible))
        assertTrue("Complete action or label is reachable", visible.contains(Rect(position[0], position[1], position[0] + view.width, position[1] + view.height)))
    }

    private fun assertConfiguration(root: View, case: Preview) {
        assertResourceConfiguration(root.resources.configuration, case)
        assertEquals("Restore root resolves requested direction", case.direction, root.layoutDirection)
    }

    private fun assertResourceConfiguration(configuration: Configuration, case: Preview) {
        assertEquals(case.qualifiers.substringBefore('-'), configuration.locales[0].language)
        assertEquals(case.fontScale, configuration.fontScale, 0f)
        assertEquals("Restore resources retain requested direction", case.direction, configuration.layoutDirection)
        assertEquals(if ("night" in case.qualifiers.split('-')) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
            configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
    }

    private fun TestScope.captureConsequence(root: View, text: TextView, case: Preview, state: String, name: String, content: String) {
        val start = text.text.indexOf(content)
        assertTrue("Consequence is present before capturing", start >= 0)
        val painted = requireNotNull(text.layout)
        for ((edge, offset) in listOf("start" to start, "end" to start + content.length - 1)) {
            val line = painted.getLineForOffset(offset)
            assertGlyphReachable(text, Rect(0, painted.getLineTop(line) + text.totalPaddingTop, text.width,
                painted.getLineBottom(line) + text.totalPaddingTop))
            captureFrame(root, case, state, "$name-$edge")
        }
    }

    private fun TestScope.captureEdges(root: View, scroll: View, case: Preview, state: String) {
        for ((edge, position) in listOf("top" to 0, "bottom" to 100_000)) {
            scroll.scrollTo(0, position)
            layout(root, case)
            captureFrame(root, case, state, edge)
        }
    }

    private fun captureFrame(root: View, case: Preview, state: String, edge: String) {
        assertConfiguration(root, case)
        val project = File(requireNotNull(System.getProperty("user.dir"))).let { if (it.name == "ui") it.parentFile else it }
        val directory = System.getProperty("vpn0wn.restore.previewDir")?.let(::File) ?: File(project, ".artifacts/fresh-restore-previews")
        assertTrue(directory.isDirectory || directory.mkdirs())
        val configuration = root.resources.configuration
        File(directory, "${case.id}-$state-$edge.json").writeText(JSONObject()
            .put("case", case.id).put("state", state).put("edge", edge).put("synthetic", true)
            .put("locale", configuration.locales[0].toLanguageTag()).put("fontScale", configuration.fontScale)
            .put("layoutDirection", root.layoutDirection).put("nightMode", configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            .put("widthPx", root.width).put("heightPx", root.height).toString(2))
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try { root.draw(Canvas(bitmap)); File(directory, "${case.id}-$state-$edge.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    private fun TestScope.layout(view: View, case: Preview) {
        val viewport = view.rootView
        var ready = false
        repeat(3) {
            val density = viewport.resources.displayMetrics.density
            viewport.measure(View.MeasureSpec.makeMeasureSpec((case.width * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((case.height * density).toInt(), View.MeasureSpec.EXACTLY))
            viewport.layout(0, 0, viewport.measuredWidth, viewport.measuredHeight)
            ready = !viewport.viewTreeObserver.dispatchOnPreDraw()
            settle()
        }
        assertTrue("Native pre-draw completes", ready)
    }

    private fun TestScope.settle() { runCurrent(); shadowOf(Looper.getMainLooper()).idle() }
    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }

    private data class Preview(val id: String, val qualifiers: String, val width: Int, val height: Int, val fontScale: Float, val direction: Int,
        val running: Boolean = false, val large: Boolean = false)
    private companion object {
        val STEPS = listOf(R.string.vpn0wn_step_ssh, R.string.vpn0wn_step_detect, R.string.vpn0wn_restore_check_step, R.string.vpn0wn_restore_action)
    }
}
