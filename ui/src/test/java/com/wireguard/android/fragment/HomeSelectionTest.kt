package com.wireguard.android.fragment

import android.app.Application
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import androidx.core.widget.NestedScrollView
import com.wireguard.android.R
import com.wireguard.android.databinding.TunnelListFragmentBinding
import com.wireguard.android.model.TunnelTestEnvironment
import com.wireguard.android.backend.Tunnel
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.concurrent.TimeUnit
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class HomeSelectionTest {
    @get:Rule val folder = TemporaryFolder()
    private val controllers = mutableListOf<ActivityController<HostActivity>>()
    @After fun tearDown() {
        controllers.forEach { it.pause().stop().destroy() }
        Dispatchers.resetMain()
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.setQualifiers("")
    }

    @Test fun insertingBeforeSelectedTunnelKeepsItsIdentity() = runHomeTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.root, backgroundScope)
        env.store.configs["bravo"] = TunnelTestEnvironment.profile()
        env.store.configs["charlie"] = TunnelTestEnvironment.profile()
        val tunnels = env.manager.getTunnels()
        val controller = Robolectric.buildActivity(HostActivity::class.java)
        controllers += controller
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
        val fragment = TunnelListFragment()
        controller.get().supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        val binding = DataBindingUtil.getBinding<TunnelListFragmentBinding>(fragment.requireView())!!
        try {
            runCurrent()
            layout(binding)
            assertTrue(row(binding, tunnels.indexOf(tunnels["charlie"])).performLongClick())
            layout(binding)
            assertTrue(row(binding, tunnels.indexOf(tunnels["charlie"])).isActivated)
            env.manager.create("alpha", TunnelTestEnvironment.profile())
            runCurrent()
            layout(binding)
            assertTrue("selection must follow charlie after sorted insertion", row(binding, tunnels.indexOf(tunnels["charlie"])).isActivated)
            assertFalse("bravo must not inherit another tunnel's selection", row(binding, tunnels.indexOf(tunnels["bravo"])).isActivated)
            tunnels["charlie"]!!.setNameAsync("aardvark")
            runCurrent()
            layout(binding)
            assertTrue("selection must follow a rename that changes sort order", row(binding, 0).isActivated)
        } finally {
            controller.pause().stop().destroy()
            controllers.remove(controller)
        }
    }

    @Test fun zeroSelectionSelectAllRemovalAndBackStayConsistent() = runHomeTest {
        val f = host("bravo", "charlie")
        row(f.binding, 0).performLongClick()
        layout(f.binding)
        assertEquals(View.GONE, f.binding.createFab.visibility)
        assertEquals(View.GONE, row(f.binding, 1).findViewById<View>(R.id.tunnel_switch).visibility)
        row(f.binding, 0).performClick()
        layout(f.binding)
        assertEquals(View.VISIBLE, f.binding.selectionHeaderContainer.visibility)
        assertFalse(f.binding.selectionDelete.isEnabled)
        assertEquals(f.fragment.getString(R.string.tunnl_selection_status_empty), f.binding.selectionStatus.text.toString())
        f.binding.selectionAll.performClick()
        layout(f.binding)
        assertTrue(row(f.binding, 0).isActivated)
        assertTrue(row(f.binding, 1).isActivated)
        f.env.manager.delete(f.binding.tunnels!!["bravo"]!!)
        runCurrent()
        layout(f.binding)
        assertTrue(row(f.binding, 0).isActivated)
        assertEquals(f.fragment.getString(R.string.tunnl_selection_status_count, 1, 1), f.binding.selectionStatus.text.toString())
        f.controller.get().onBackPressedDispatcher.onBackPressed()
        layout(f.binding)
        assertEquals(View.GONE, f.binding.selectionHeaderContainer.visibility)
        assertEquals(View.VISIBLE, f.binding.createFab.visibility)
        assertEquals(View.VISIBLE, row(f.binding, 0).findViewById<View>(R.id.tunnel_switch).visibility)
    }

    @Test fun recreationRestoresNamesAndKeepsZeroSelectedMode() = runHomeTest {
        val f = host("bravo", "charlie")
        row(f.binding, 1).performLongClick()
        layout(f.binding)
        f.binding.selectionDelete.performClick()
        val oldDialog = dialog(f.fragment)
        f.controller.recreate()
        assertFalse(oldDialog.isShowing)
        runCurrent()
        val restored = f.controller.get().supportFragmentManager.findFragmentByTag("home") as TunnelListFragment
        val binding = DataBindingUtil.getBinding<TunnelListFragmentBinding>(restored.requireView())!!
        layout(binding)
        assertTrue(row(binding, 1).isActivated)
        assertFalse(row(binding, 0).isActivated)
        row(binding, 1).performClick()
        layout(binding)
        f.controller.recreate()
        runCurrent()
        val zero = f.controller.get().supportFragmentManager.findFragmentByTag("home") as TunnelListFragment
        val zeroBinding = DataBindingUtil.getBinding<TunnelListFragmentBinding>(zero.requireView())!!
        layout(zeroBinding)
        assertEquals(View.VISIBLE, zeroBinding.selectionHeaderContainer.visibility)
        assertEquals(View.GONE, zeroBinding.createFab.visibility)
        assertFalse(zeroBinding.selectionDelete.isEnabled)
    }

    @Test fun confirmationCancellationWarnsAndMakesNoMutation() = runHomeTest {
        val f = host("bravo", "charlie")
        val active = f.binding.tunnels!!["charlie"]!!
        active.setStateAsync(Tunnel.State.UP)
        runCurrent()
        layout(f.binding)
        row(f.binding, 1).performLongClick()
        f.binding.selectionDelete.performClick()
        val dialog = dialog(f.fragment)
        assertTrue(dialog.findViewById<TextView>(R.id.delete_sheet_warning)!!.text.contains("charlie"))
        f.controller.get().onBackPressedDispatcher.onBackPressed()
        assertFalse(dialog.isShowing)
        assertEquals(View.VISIBLE, f.binding.selectionHeaderContainer.visibility)
        f.binding.selectionDelete.performClick()
        assertTrue(dialog(f.fragment).findViewById<View>(R.id.delete_sheet_cancel)!!.performClick())
        layout(f.binding)
        assertEquals(setOf("bravo", "charlie"), f.env.store.configs.keys.toSet())
        assertEquals(Tunnel.State.UP, active.state)
        assertTrue(row(f.binding, 1).isActivated)
    }

    @Test fun mixedDeletionReportsActualProgressAndKeepsFailedActiveTunnelSelected() = runHomeTest {
        val f = host("bravo", "charlie")
        val active = f.binding.tunnels!!["charlie"]!!
        active.setStateAsync(Tunnel.State.UP)
        f.env.store.failDeleteNames.add("charlie")
        runCurrent()
        layout(f.binding)
        selectAll(f)
        confirm(f)
        deletion(f.fragment).join()
        runCurrent()
        layout(f.binding)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(setOf("charlie"), f.env.store.configs.keys.toSet())
        assertEquals(Tunnel.State.UP, active.state)
        assertTrue(row(f.binding, 0).isActivated)
        assertTrue(f.binding.selectionDelete.isEnabled)
        assertEquals(View.GONE, f.binding.createFab.visibility)
        assertTrue(message(f.binding).contains("Removed 1 of 2 selected tunnels. 1 remain."))
    }

    @Test fun completeDeletionExitsSelectionAndKeepsUnselectedConfig() = runHomeTest {
        val f = host("alpha", "bravo", "charlie")
        row(f.binding, 1).performLongClick()
        layout(f.binding)
        row(f.binding, 2).performClick()
        confirm(f)
        deletion(f.fragment).join()
        runCurrent()
        layout(f.binding)
        assertEquals(setOf("alpha"), f.env.store.configs.keys.toSet())
        assertEquals(View.GONE, f.binding.selectionHeaderContainer.visibility)
        assertEquals(View.VISIBLE, f.binding.createFab.visibility)
    }

    @Test fun backCancellationSettlesAdmittedDeleteAndStopsRemainingWork() = runHomeTest {
        val f = host("bravo", "charlie")
        f.binding.tunnels!!["bravo"]!!.setStateAsync(Tunnel.State.UP)
        f.env.backend.blockDown = true
        runCurrent()
        layout(f.binding)
        selectAll(f)
        confirm(f)
        val job = deletion(f.fragment)
        runCurrent()
        try {
            assertTrue(withContext(Dispatchers.IO) { f.env.backend.downEntered.await(5, TimeUnit.SECONDS) })
            f.controller.get().onBackPressedDispatcher.onBackPressed()
        } finally {
            f.env.backend.releaseDown.countDown()
        }
        job.join()
        runCurrent()
        layout(f.binding)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(setOf("charlie"), f.env.store.configs.keys.toSet())
        assertTrue(row(f.binding, 0).isActivated)
        assertTrue(f.binding.selectionDelete.isEnabled)
        assertTrue(message(f.binding).contains("Removed 1 of 2 selected tunnels. 1 remain. Deletion cancelled."))
    }

    @Test fun cancellingBeforeCoroutineEntryReleasesBusyStateWithoutDeletion() = runHomeTest {
        val f = host("bravo")
        selectAll(f)
        confirm(f)
        val job = deletion(f.fragment)
        f.controller.get().onBackPressedDispatcher.onBackPressed()
        job.join()
        runCurrent()
        layout(f.binding)
        assertEquals(setOf("bravo"), f.env.store.configs.keys.toSet())
        assertTrue(f.binding.selectionDelete.isEnabled)
        assertTrue(f.binding.selectionClose.isEnabled)
    }

    @Test fun viewRecreationSettlesAdmittedDeleteWithoutStaleFeedback() = runHomeTest {
        val f = host("bravo", "charlie")
        f.binding.tunnels!!["bravo"]!!.setStateAsync(Tunnel.State.UP)
        f.env.backend.blockDown = true
        selectAll(f)
        confirm(f)
        val job = deletion(f.fragment)
        runCurrent()
        val fragments = f.controller.get().supportFragmentManager
        lateinit var restored: TunnelListFragmentBinding
        try {
            assertTrue(withContext(Dispatchers.IO) { f.env.backend.downEntered.await(5, TimeUnit.SECONDS) })
            fragments.beginTransaction().detach(f.fragment).commitNow()
            fragments.beginTransaction().attach(f.fragment).commitNow()
            restored = DataBindingUtil.getBinding(f.fragment.requireView())!!
            runCurrent()
        } finally {
            f.env.backend.releaseDown.countDown()
        }
        job.join()
        runCurrent()
        layout(restored)
        assertEquals(listOf("bravo"), f.env.store.deleteAttempts)
        assertEquals(setOf("charlie"), f.env.store.configs.keys.toSet())
        assertTrue(row(restored, 0).isActivated)
        assertTrue(restored.selectionDelete.isEnabled)
        assertNull(restored.root.findViewById<View>(com.google.android.material.R.id.snackbar_text))
    }

    @Test fun inventoryReplacementInvalidatesConfirmationAndCannotDeleteReplacement() = runHomeTest {
        val f = host("bravo")
        selectAll(f)
        f.binding.selectionDelete.performClick()
        val dialog = dialog(f.fragment)
        val button = dialog.findViewById<View>(R.id.delete_sheet_confirm)!!
        f.env.manager.delete(f.binding.tunnels!!["bravo"]!!)
        val replacement = f.env.manager.create("bravo", TunnelTestEnvironment.profile(51821))
        runCurrent()
        layout(f.binding)
        assertFalse(dialog.isShowing)
        button.performClick()
        runCurrent()
        assertSame(replacement, f.binding.tunnels!!["bravo"])
        assertEquals(51821, f.env.store.configs["bravo"]!!.getInterface().listenPort.get())
        assertFalse(row(f.binding, 0).isActivated)
    }

    @Test fun selectionRowsExposeCheckableSemanticsAndRestoreNormalInset() = runHomeTest {
        val f = host("bravo", "charlie")
        val normalHeader = row(f.binding, 0).findViewById<View>(R.id.tunnel_header)
        val inset = (normalHeader.layoutParams as android.view.ViewGroup.MarginLayoutParams).marginStart
        row(f.binding, 0).performLongClick()
        layout(f.binding)
        for (index in 0..1) {
            val node = AccessibilityNodeInfo.obtain()
            row(f.binding, index).onInitializeAccessibilityNodeInfo(node)
            assertTrue(node.isCheckable)
            assertEquals(index == 0, node.isChecked)
            assertEquals(if (index == 0) "bravo" else "charlie", node.contentDescription.toString())
            node.recycle()
        }
        f.binding.selectionClose.performClick()
        layout(f.binding)
        val restoredHeader = row(f.binding, 0).findViewById<View>(R.id.tunnel_header)
        assertEquals(inset, (restoredHeader.layoutParams as android.view.ViewGroup.MarginLayoutParams).marginStart)
    }

    @Test fun programmerDefectEscapesWithoutSuccessOrLaterDeletion() {
        assertThrows(ArithmeticException::class.java) {
            runHomeTest {
                val f = host("bravo", "charlie")
                f.env.store.deleteDefect = true
                selectAll(f)
                confirm(f)
                deletion(f.fragment).join()
                runCurrent()
                layout(f.binding)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(setOf("bravo", "charlie"), f.env.store.configs.keys.toSet())
                assertEquals(listOf("bravo"), f.env.store.deleteAttempts)
                assertTrue(f.binding.selectionDelete.isEnabled)
                assertNull(f.binding.root.findViewById<View>(com.google.android.material.R.id.snackbar_text))
            }
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun nativeHomeAndConfirmationFitThemesLandscapeLargeTextAndRtl() = runHomeTest {
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
            RuntimeEnvironment.setFontScale(case.scale)
            val f = host("bravo", "charlie")
            try {
                f.binding.tunnels!!["charlie"]!!.setStateAsync(Tunnel.State.UP)
                runCurrent()
                layout(f.binding, case.width, case.height)
                assertEquals(Tunnel.State.UP, f.binding.tunnels!!["charlie"]!!.state)
                assertSingleLineBadge(f.binding, case.name)
                save(f.binding.root, "home-${case.name}")
                f.binding.tunnelList.scrollBy(0, f.binding.tunnelList.computeVerticalScrollRange())
                val lastName = checkNotNull(f.binding.tunnelList.findViewHolderForAdapterPosition(1)).itemView.findViewById<TextView>(R.id.tunnel_name)
                lastName.requestRectangleOnScreen(Rect(0, 0, lastName.width, lastName.height), true)
                layout(f.binding, case.width, case.height)
                val nameLocation = IntArray(2)
                val listLocation = IntArray(2)
                lastName.getLocationInWindow(nameLocation)
                f.binding.tunnelList.getLocationInWindow(listLocation)
                assertTrue("${case.name}: last name reachable", nameLocation[1] >= listLocation[1])
                assertTrue("${case.name}: last name clear", nameLocation[1] + lastName.height <= listLocation[1] + f.binding.tunnelList.height)
                val addLocation = IntArray(2)
                f.binding.createFab.getLocationInWindow(addLocation)
                assertTrue("${case.name}: reserved Add footer", listLocation[1] + f.binding.tunnelList.height <= addLocation[1])
                row(f.binding, 0).performLongClick()
                f.binding.selectionAll.performClick()
                layout(f.binding, case.width, case.height)
                assertEquals(if (case.locale == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR, f.binding.root.layoutDirection)
                for (button in listOf(f.binding.selectionClose, f.binding.selectionDelete, f.binding.selectionAll)) {
                    assertTrue("${case.name}: header target", button.height >= 48 * button.resources.displayMetrics.density)
                }
                assertTextFits(f.binding.selectionHeaderContainer, case.name)
                assertTextFits(f.binding.selectionStatusContainer, case.name)
                assertSingleLineBadge(f.binding, case.name)
                save(f.binding.root, "selected-${case.name}")
                row(f.binding, 0).performClick()
                row(f.binding, 1).performClick()
                layout(f.binding, case.width, case.height)
                save(f.binding.root, "zero-${case.name}")
                f.binding.selectionAll.performClick()
                f.binding.selectionDelete.performClick()
                val dialog = dialog(f.fragment)
                val root = dialog.findViewById<NestedScrollView>(R.id.delete_sheet_scroll)!!
                measure(root, case, boundedHeight = true)
                assertTextFits(root, case.name)
                save(root, "delete-${case.name}")
                root.fullScroll(View.FOCUS_DOWN)
                val confirm = root.findViewById<View>(R.id.delete_sheet_confirm)
                assertTrue(confirm.height >= 48 * root.resources.displayMetrics.density)
                val buttonLocation = IntArray(2)
                val rootLocation = IntArray(2)
                confirm.getLocationInWindow(buttonLocation)
                root.getLocationInWindow(rootLocation)
                assertTrue("${case.name}: confirm reachable", buttonLocation[1] >= rootLocation[1])
                assertTrue("${case.name}: confirm clear", buttonLocation[1] + confirm.height <= rootLocation[1] + root.height)
                if (case.name == "large-landscape-dark") assertTrue(root.scrollY > 0)
                save(root, "delete-${case.name}-bottom")
                dialog.cancel()
            } finally {
                f.controller.pause().stop().destroy()
                controllers.remove(f.controller)
            }
        }
    }

    private fun assertTextFits(view: View, name: String) {
        if (view.visibility != View.VISIBLE) return
        if (view is TextView && view.text.isNotEmpty()) {
            assertTrue("$name: ${view.text}", view.layout.getLineTop(view.layout.lineCount) <= view.height)
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) assertTextFits(view.getChildAt(index), name)
    }

    private fun assertSingleLineBadge(binding: TunnelListFragmentBinding, name: String) {
        for (index in 0 until binding.tunnelList.childCount) {
            val badge = binding.tunnelList.getChildAt(index).findViewById<TextView>(R.id.tunnel_peer_badge)
            assertEquals("$name: ${badge.text}", 1, badge.lineCount)
            assertEquals("NO PEER", badge.text.toString())
        }
    }

    private fun measure(view: View, case: Preview, boundedHeight: Boolean = false) {
        val density = view.resources.displayMetrics.density
        view.measure(View.MeasureSpec.makeMeasureSpec((case.width * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((case.height * density).toInt(), if (boundedHeight) View.MeasureSpec.AT_MOST else View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun save(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
        view.draw(canvas)
        val directory = File("../.artifacts/home-selection-previews").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private data class Preview(val name: String, val width: Int, val height: Int, val scale: Float, val locale: String, val night: Boolean)

    private fun runHomeTest(block: suspend TestScope.() -> Unit) = runTest {
        try {
            block()
        } finally {
            controllers.toList().forEach { it.pause().stop().destroy() }
            controllers.clear()
        }
    }

    private suspend fun TestScope.host(vararg names: String): Fixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.newFolder(), backgroundScope)
        names.forEach { env.store.configs[it] = TunnelTestEnvironment.profile() }
        env.manager.getTunnels()
        val controller = Robolectric.buildActivity(HostActivity::class.java)
        controllers += controller
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
        val fragment = TunnelListFragment()
        controller.get().supportFragmentManager.beginTransaction().add(android.R.id.content, fragment, "home").commitNow()
        val binding = DataBindingUtil.getBinding<TunnelListFragmentBinding>(fragment.requireView())!!
        runCurrent()
        layout(binding)
        return Fixture(env, controller, fragment, binding)
    }

    private fun selectAll(f: Fixture) {
        row(f.binding, 0).performLongClick()
        f.binding.selectionAll.performClick()
        layout(f.binding)
    }

    private fun confirm(f: Fixture) {
        f.binding.selectionDelete.performClick()
        dialog(f.fragment).findViewById<View>(R.id.delete_sheet_confirm)!!.performClick()
    }

    private fun dialog(fragment: TunnelListFragment): BottomSheetDialog =
        TunnelListFragment::class.java.getDeclaredField("deleteSheet").apply { isAccessible = true }.get(fragment) as BottomSheetDialog

    private fun deletion(fragment: TunnelListFragment): Job =
        TunnelListFragment::class.java.getDeclaredField("deleteJob").apply { isAccessible = true }.get(fragment) as Job

    private fun message(binding: TunnelListFragmentBinding): String =
        binding.root.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).text.toString()

    private data class Fixture(val env: TunnelTestEnvironment, val controller: ActivityController<HostActivity>,
        val fragment: TunnelListFragment, val binding: TunnelListFragmentBinding)

    private fun layout(binding: TunnelListFragmentBinding, width: Int = 320, height: Int = 640) {
        shadowOf(Looper.getMainLooper()).idle()
        binding.executePendingBindings()
        val density = binding.root.resources.displayMetrics.density
        binding.root.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
        binding.root.layout(0, 0, binding.root.measuredWidth, binding.root.measuredHeight)
    }

    private fun row(binding: TunnelListFragmentBinding, position: Int): View {
        if (binding.tunnelList.findViewHolderForAdapterPosition(position) == null) {
            binding.tunnelList.scrollToPosition(position)
            val density = binding.root.resources.displayMetrics.density
            layout(binding, (binding.root.width / density).toInt(), (binding.root.height / density).toInt())
        }
        return checkNotNull(binding.tunnelList.findViewHolderForAdapterPosition(position)).itemView
    }

    class HostActivity : AppCompatActivity()
}
