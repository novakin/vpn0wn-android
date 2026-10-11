package com.vpn0wn.android.fragment

import android.app.Application
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.vpn0wn.android.R
import com.vpn0wn.android.backend.Statistics
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.databinding.TunnelDetailFragmentBinding
import com.vpn0wn.android.databinding.TunnelDetailPeerBinding
import com.vpn0wn.android.model.ObservableTunnel
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.android.util.QuantityFormatter
import com.vpn0wn.config.Peer
import com.vpn0wn.crypto.Key
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
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
class AddPeerPresentationTest {
    @get:Rule val folder = TemporaryFolder()
    private val controllers = mutableListOf<ActivityController<HostActivity>>()
    private lateinit var environment: TunnelTestEnvironment
    private val publicKey = Key.fromBytes(ByteArray(32) { 1 })

    @After fun tearDown() {
        controllers.forEach { it.pause().stop().destroy() }
    }

    @Test fun nativeClicksDismissAndDispatchExactlyOneExistingAction() {
        val activity = host()
        val requests = mutableListOf<String>()
        activity.supportFragmentManager.setFragmentResultListener(AddTunnelsSheet.REQUEST_KEY_NEW_TUNNEL, activity) { _, bundle ->
            requests += bundle.getString(AddTunnelsSheet.REQUEST_METHOD)!!
        }
        val actions = listOf(
            R.id.create_from_qrcode to AddTunnelsSheet.REQUEST_SCAN,
            R.id.create_from_file to AddTunnelsSheet.REQUEST_IMPORT,
            R.id.create_empty to AddTunnelsSheet.REQUEST_CREATE
        )
        for ((id, request) in actions) {
            val sheet = showSheet(activity)
            val root = sheet.requireView() as NestedScrollView
            val order = (root.getChildAt(0) as ViewGroup).let { content ->
                (0 until content.childCount).map { content.getChildAt(it).id }.filter { it in actions.map { it.first } }
            }
            assertEquals(actions.map { it.first }, order)
            val button = root.findViewById<View>(id)
            assertEquals(Button::class.java.name, button.createAccessibilityNodeInfo().className)
            assertTrue(button.contentDescription.isNotBlank())
            assertTrue(button.isClickable && button.isFocusable)
            assertTrue(button.performClick())
            activity.supportFragmentManager.executePendingTransactions()
            assertFalse(sheet.isAdded)
            assertEquals(request, requests.last())
        }
        assertEquals(actions.map { it.second }, requests)
    }

    @Test fun cameraAbsenceHidesQrAndLeavesFileAndManualAvailable() {
        val activity = host(camera = false)
        val sheet = showSheet(activity)
        val root = sheet.requireView()
        assertEquals(View.GONE, root.findViewById<View>(R.id.create_from_qrcode).visibility)
        assertFalse(root.findViewById<View>(R.id.create_from_qrcode).isEnabled)
        assertTrue(root.findViewById<View>(R.id.create_from_file).isEnabled)
        assertTrue(root.findViewById<View>(R.id.create_empty).isEnabled)
    }

    @Test fun cancellationAndCollapseDoNotDispatchImportActions() {
        val activity = host()
        val requests = mutableListOf<String>()
        activity.supportFragmentManager.setFragmentResultListener(AddTunnelsSheet.REQUEST_KEY_NEW_TUNNEL, activity) { _, bundle ->
            requests += bundle.getString(AddTunnelsSheet.REQUEST_METHOD)!!
        }
        val cancelled = showSheet(activity)
        cancelled.requireDialog().cancel()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        activity.supportFragmentManager.executePendingTransactions()
        assertFalse(cancelled.isAdded)
        val collapsed = showSheet(activity)
        val dialog = collapsed.requireDialog() as BottomSheetDialog
        assertEquals(BottomSheetBehavior.STATE_EXPANDED, dialog.behavior.state)
        dialog.behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        shadowOf(android.os.Looper.getMainLooper()).idle()
        activity.supportFragmentManager.executePendingTransactions()
        assertFalse(collapsed.isAdded)
        assertTrue(requests.isEmpty())
    }

    @Test fun recreationDismissesOldSheetWithoutSendingAnAction() {
        val activity = host()
        showSheet(activity)
        controllers.last().recreate()
        val recreated = controllers.last().get()
        recreated.supportFragmentManager.executePendingTransactions()
        assertTrue(recreated.supportFragmentManager.fragments.none { it is AddTunnelsSheet })
        assertNotNull(showSheet(recreated).view)
    }

    @Test fun peerSnapshotBindsSeparateLocalizedCountersAndHandshake() = runTest {
        environment = TunnelTestEnvironment(folder.root, backgroundScope)
        val activity = host()
        val binding = peer(activity)
        activity.setContentView(binding.root)
        binding.statistics = Statistics.PeerStats(2048L, 1024L, System.currentTimeMillis() - 5000L)
        binding.executePendingBindings()
        assertEquals(QuantityFormatter.formatBytes(2048), binding.rxText.text.toString())
        assertEquals(QuantityFormatter.formatBytes(1024), binding.txText.text.toString())
        assertEquals(View.VISIBLE, binding.transferGrid.visibility)
        assertEquals(View.VISIBLE, binding.latestHandshakeRow.visibility)
        assertTrue(binding.latestHandshakeText.text.isNotEmpty())
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        for (cell in listOf(binding.rxText, binding.txText)) {
            assertTrue(cell.contentDescription.toString().contains(cell.text.toString()))
            assertTrue(cell.performClick())
            val clip = clipboard.primaryClip!!
            assertEquals(cell.text.toString(), clip.getItemAt(0).text.toString())
            assertEquals(cell.contentDescription.toString(), clip.description.label.toString())
        }
    }

    @Test fun missingAndZeroStatisticsClearOldCellsAndWholeRows() = runTest {
        environment = TunnelTestEnvironment(folder.root, backgroundScope)
        val binding = peer(host())
        for (empty in listOf(null, Statistics.PeerStats(0L, 0L, 0L))) {
            binding.statistics = Statistics.PeerStats(2048L, 1024L, 1L)
            binding.executePendingBindings()
            binding.statistics = empty
            binding.executePendingBindings()
            assertEquals(View.GONE, binding.transferGrid.visibility)
            assertEquals(View.GONE, binding.latestHandshakeRow.visibility)
            assertEquals(QuantityFormatter.formatBytes(0), binding.rxText.text.toString())
            assertEquals(QuantityFormatter.formatBytes(0), binding.txText.text.toString())
            assertEquals("", binding.latestHandshakeText.text.toString())
        }
    }

    @Test fun oneDirectionAndHandshakeOnlySnapshotsHaveIndependentVisibility() = runTest {
        environment = TunnelTestEnvironment(folder.root, backgroundScope)
        val binding = peer(host())
        binding.statistics = Statistics.PeerStats(0L, 2048L, 0L)
        binding.executePendingBindings()
        assertEquals(View.VISIBLE, binding.transferGrid.visibility)
        assertEquals(QuantityFormatter.formatBytes(0), binding.rxText.text.toString())
        assertEquals(View.GONE, binding.latestHandshakeRow.visibility)
        binding.statistics = Statistics.PeerStats(0L, 0L, 1L)
        binding.executePendingBindings()
        assertEquals(View.GONE, binding.transferGrid.visibility)
        assertEquals(View.VISIBLE, binding.latestHandshakeRow.visibility)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun nativeTopologyHeaderKeepsCompleteLabelsSeparatedAtSmallWidthsAndLargeText() = runTest {
        environment = TunnelTestEnvironment(folder.root, backgroundScope)
        val config = TunnelTestEnvironment.profile()
        val tunnel = ObservableTunnel(environment.manager, "home-lab", config, Tunnel.State.UP)
        val statistics = Statistics::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        Statistics::class.java.getDeclaredMethod("add", Key::class.java, Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(statistics, publicKey, 2048L, 1024L, System.currentTimeMillis())
        val cases = listOf(
            Preview("portrait", 320, 640, 1f, "en", false),
            Preview("rtl-portrait", 320, 640, 1f, "ar", false),
            Preview("large-portrait", 320, 640, 2f, "en", false),
            Preview("large-rtl-portrait", 320, 640, 2f, "ar", false),
            Preview("pixel-rtl-portrait", 411, 891, 2f, "ar", false),
            Preview("large-landscape", 480, 320, 2f, "en", false),
            Preview("large-rtl-landscape", 480, 320, 2f, "ar", false)
        )
        for (preview in cases) for (nightMode in listOf(false, true)) {
            val case = preview.copy(name = "${preview.name}-${if (nightMode) "dark" else "light"}", night = nightMode)
            val direction = if (case.locale == "ar") "ldrtl" else "ldltr"
            val orientation = if (case.width > case.height) "land" else "port"
            val night = if (case.night) "night" else "notnight"
            RuntimeEnvironment.setQualifiers("${case.locale}-$direction-w${case.width}dp-h${case.height}dp-$orientation-$night")
            RuntimeEnvironment.setFontScale(case.fontScale)
            val activity = host()
            val binding = TunnelDetailFragmentBinding.inflate(activity.layoutInflater)
            activity.setContentView(binding.root)
            binding.config = config
            binding.tunnel = tunnel
            val header = (binding.throughputView.parent as ViewGroup).getChildAt(0) as ViewGroup
            val title = header.getChildAt(0) as TextView
            val status = header.getChildAt(1) as TextView
            val expectedDirection = if (case.locale == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            for ((snapshot, statusResource) in listOf(statistics to R.string.vpn0wn_mode_up, null to R.string.vpn0wn_mode_connecting)) {
                tunnel.onStatisticsChanged(snapshot)
                binding.executePendingBindings()
                measure(binding.root, case)
                assertEquals(case.name, expectedDirection, header.layoutDirection)
                assertEquals("FIG.01 - TUNNEL TOPOLOGY", title.text.toString())
                assertEquals(activity.getString(statusResource), status.text.toString())
                assertHeaderTextFits(title, case.name)
                assertHeaderTextFits(status, case.name)
                val titleLines = textLines(title)
                val statusLines = textLines(status)
                val minimumVisibleGap = 4 * activity.resources.displayMetrics.density
                for (titleLine in titleLines) for (statusLine in statusLines) {
                    if (titleLine.top >= statusLine.bottom || statusLine.top >= titleLine.bottom) continue
                    val gap = if (expectedDirection == View.LAYOUT_DIRECTION_RTL)
                        titleLine.left - statusLine.right else statusLine.left - titleLine.right
                    assertTrue("${case.name}: ${status.text} gap $gap", gap >= minimumVisibleGap)
                }
                val statusName = if (snapshot == null) "connecting" else "up"
                save(header, "topology-${case.name}-$statusName")
            }
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun nativeOutputWrapsAndScrollsInSmallLandscapeAndLargeRtlConfigurations() = runTest {
        environment = TunnelTestEnvironment(folder.root, backgroundScope)
        val cases = listOf(
            Preview("portrait-light", 320, 640, 1f, "en", false),
            Preview("portrait-dark", 320, 640, 1f, "en", true),
            Preview("landscape-dark", 640, 320, 1f, "en", true),
            Preview("large-landscape-dark", 640, 320, 2f, "en", true),
            Preview("large-rtl-light", 320, 640, 2f, "ar", false),
            Preview("large-rtl-dark", 320, 640, 2f, "ar", true)
        )
        for (case in cases) {
            val direction = if (case.locale == "ar") "ldrtl" else "ldltr"
            val orientation = if (case.width > case.height) "land" else "port"
            val night = if (case.night) "night" else "notnight"
            RuntimeEnvironment.setQualifiers("${case.locale}-$direction-w${case.width}dp-h${case.height}dp-$orientation-$night")
            RuntimeEnvironment.setFontScale(case.fontScale)
            val activity = host()
            val sheet = showSheet(activity)
            val root = sheet.requireView() as NestedScrollView
            measure(root, case)
            val expectedDirection = if (case.locale == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            assertEquals("${case.name}: Add direction", expectedDirection, root.layoutDirection)
            for (id in listOf(R.id.create_from_qrcode, R.id.create_from_file, R.id.create_empty)) {
                val button = root.findViewById<View>(id)
                assertTrue(button.height >= 48 * activity.resources.displayMetrics.density)
            }
            assertTextFits(root, case.name)
            save(root, "add-${case.name}")
            root.fullScroll(View.FOCUS_DOWN)
            if (case.name == "large-landscape-dark") {
                assertTrue(root.canScrollVertically(-1))
                assertTrue(root.scrollY > 0)
            }
            val last = root.findViewById<View>(R.id.create_empty)
            val location = IntArray(2)
            last.getLocationInWindow(location)
            val rootLocation = IntArray(2)
            root.getLocationInWindow(rootLocation)
            assertTrue(location[1] >= rootLocation[1])
            assertTrue(location[1] + last.height <= rootLocation[1] + root.height - root.paddingBottom)
            save(root, "add-${case.name}-bottom")
            val peer = peer(activity)
            activity.setContentView(peer.root)
            peer.statistics = Statistics.PeerStats(2048L, 1024L, System.currentTimeMillis() - 5000L)
            peer.executePendingBindings()
            measure(peer.root, case, boundedHeight = false)
            assertEquals("${case.name}: peer direction", expectedDirection, peer.root.layoutDirection)
            for (cell in listOf(peer.rxText, peer.txText)) {
                assertTrue(cell.height >= 48 * activity.resources.displayMetrics.density)
                assertTrue(cell.isClickable)
            }
            assertTextFits(peer.root, case.name)
            save(peer.root, "peer-${case.name}")
        }
    }

    private fun host(camera: Boolean = true): HostActivity {
        val controller = Robolectric.buildActivity(HostActivity::class.java)
        controllers += controller
        controller.get().setTheme(R.style.AppTheme)
        shadowOf(controller.get().packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, camera)
        controller.setup()
        return controller.get()
    }

    private fun showSheet(activity: HostActivity): AddTunnelsSheet {
        val sheet = AddTunnelsSheet()
        sheet.showNow(activity.supportFragmentManager, "add")
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return sheet
    }

    private fun peer(activity: HostActivity): TunnelDetailPeerBinding =
        TunnelDetailPeerBinding.inflate(activity.layoutInflater).apply {
            item = Peer.Builder().setPublicKey(publicKey).build()
            executePendingBindings()
        }

    private fun measure(view: View, case: Preview, boundedHeight: Boolean = true) {
        val density = view.resources.displayMetrics.density
        view.measure(View.MeasureSpec.makeMeasureSpec((case.width * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((case.height * density).toInt(), if (boundedHeight) View.MeasureSpec.AT_MOST else View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun assertTextFits(view: View, name: String) {
        if (view.visibility != View.VISIBLE) return
        if (view is TextView && view.text.isNotEmpty()) {
            assertTrue("$name: ${view.text}", view.layout.getLineTop(view.layout.lineCount) <= view.height)
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) assertTextFits(view.getChildAt(index), name)
    }

    private fun assertHeaderTextFits(view: TextView, name: String) {
        val layout = view.layout
        assertEquals("$name: ${view.text}", view.text.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue("$name: ${view.text} height", layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
        for (line in 0 until layout.lineCount) {
            assertEquals("$name: ${view.text} ellipsis", 0, layout.getEllipsisCount(line))
            assertTrue("$name: ${view.text} left", layout.getLineLeft(line) >= 0)
            assertTrue("$name: ${view.text} right", layout.getLineRight(line) <= view.width - view.compoundPaddingLeft - view.compoundPaddingRight)
        }
    }

    private fun textLines(view: TextView): List<RectF> = (0 until view.layout.lineCount).map { line ->
        RectF(view.left + view.totalPaddingLeft + view.layout.getLineLeft(line),
            (view.top + view.totalPaddingTop + view.layout.getLineTop(line)).toFloat(),
            view.left + view.totalPaddingLeft + view.layout.getLineRight(line),
            (view.top + view.totalPaddingTop + view.layout.getLineBottom(line)).toFloat())
    }

    private fun save(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
        view.draw(canvas)
        val directory = File("../.artifacts/add-peer-previews").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private data class Preview(val name: String, val width: Int, val height: Int, val fontScale: Float, val locale: String, val night: Boolean)
    class HostActivity : AppCompatActivity()
}
