package com.vpn0wn.android.fragment

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.TextView
import android.widget.LinearLayout
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import androidx.core.widget.NestedScrollView
import com.vpn0wn.android.R
import com.vpn0wn.android.databinding.BindingAdapters
import com.vpn0wn.android.databinding.TunnelListFragmentBinding
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.android.backend.Statistics
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.crypto.Key
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
import org.robolectric.shadows.ShadowChoreographer
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
        assertEquals(f.fragment.getString(R.string.vpn0wn_selection_status_empty), f.binding.selectionStatus.text.toString())
        f.binding.selectionAll.performClick()
        layout(f.binding)
        assertTrue(row(f.binding, 0).isActivated)
        assertTrue(row(f.binding, 1).isActivated)
        f.env.manager.delete(f.binding.tunnels!!["bravo"]!!)
        runCurrent()
        layout(f.binding)
        assertTrue(row(f.binding, 0).isActivated)
        assertEquals(f.fragment.getString(R.string.vpn0wn_selection_status_count, 1, 1), f.binding.selectionStatus.text.toString())
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
        val active = f.binding.tunnels!!["bravo"]!!
        active.setStateAsync(Tunnel.State.UP)
        f.env.store.failDeleteNames.add("bravo")
        runCurrent()
        layout(f.binding)
        selectAll(f)
        confirm(f)
        deletion(f.fragment).join()
        val choreographerWasPaused = ShadowChoreographer.isPaused()
        // Automatic synthetic animation frames can advance past the Snackbar timeout during layout.
        ShadowChoreographer.setPaused(true)
        try {
            runCurrent()
            layout(f.binding)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("bravo", "charlie"), f.env.store.deleteAttempts)
            assertEquals(setOf("bravo"), f.env.store.configs.keys.toSet())
            assertEquals(Tunnel.State.UP, active.state)
            assertTrue(row(f.binding, 0).isActivated)
            assertTrue(f.binding.selectionDelete.isEnabled)
            assertEquals(View.GONE, f.binding.createFab.visibility)
            assertTrue(message(f.binding).contains("Removed 1 of 2 selected tunnels. 1 remain."))
        } finally {
            ShadowChoreographer.setPaused(choreographerWasPaused)
        }
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
        f.binding.selectionAll.performClick()
        layout(f.binding)
        f.binding.selectionDelete.performClick()
        val reopened = dialog(f.fragment)
        button.performClick()
        pendingDeletion(f.fragment)?.join()
        runCurrent()
        layout(f.binding)
        assertTrue("stale confirmation must not admit a newer selection", reopened.isShowing)
        assertSame(replacement, f.binding.tunnels!!["bravo"])
        assertEquals(51821, f.env.store.configs["bravo"]!!.getInterface().listenPort.get())
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
        for (case in homePreviewCases()) {
            val direction = if (case.locale == "ar") "ldrtl" else "ldltr"
            val orientation = if (case.width > case.height) "land" else "port"
            val night = if (case.night) "night" else "notnight"
            RuntimeEnvironment.setQualifiers("${case.locale}-$direction-w${case.width}dp-h${case.height}dp-$orientation-$night")
            RuntimeEnvironment.setFontScale(case.scale)
            val f = host("bravo", "charlie")
            try {
                assertEquals("—", f.binding.homeEndpointHost.text.toString())
                val active = f.binding.tunnels!!["charlie"]!!
                active.setConfigAsync(profileWithPeer(withPsk = true))
                f.env.backend.statisticsReader = { recentStatistics() }
                active.setStateAsync(Tunnel.State.UP)
                runCurrent()
                active.onStatisticsChanged(recentStatistics(), f.env.backend)
                assertEquals("${case.name}: active model endpoint", "198.51.100.42",
                    BindingAdapters.homeEndpointHost(f.binding.tunnels))
                layout(f.binding, case.width, case.height)
                assertEquals(Tunnel.State.UP, f.binding.tunnels!!["charlie"]!!.state)
                assertEquals("198.51.100.42", f.binding.homeEndpointHost.text.toString())
                val targets = listOf(f.binding.homeSearch, f.binding.homeServers, f.binding.homeLogs, f.binding.homeSettings)
                val targetBounds = targets.map { target ->
                    assertTrue("${case.name}: Home action width", target.width >= 48 * target.resources.displayMetrics.density)
                    assertTrue("${case.name}: Home action height", target.height >= 48 * target.resources.displayMetrics.density)
                    assertFullyVisible(target, "${case.name}: Home action")
                    Rect().also { target.getGlobalVisibleRect(it) }
                }
                for (index in 1 until targetBounds.size) {
                    assertFalse("${case.name}: Home actions overlap", Rect.intersects(targetBounds[index - 1], targetBounds[index]))
                }
                assertFalse(f.binding.homeSearch.isEnabled)
                assertTextFits(f.binding.homeHeaderContainer, case.name)
                val summaryLayout = f.binding.homeHeaderSummary.layout
                val wordmark = f.binding.root.context.getString(R.string.vpn0wn_home_wordmark)
                assertEquals("${case.name}: wordmark stays together", summaryLayout.getLineForOffset(0),
                    summaryLayout.getLineForOffset(wordmark.length - 1))
                assertTextFits(f.binding.homeStatusContainer, case.name)
                assertEndpointLayout(f.binding, case.name)
                assertTextFits(f.binding.createFab, case.name)
                assertTrue("${case.name}: Add target", f.binding.createFab.height >= 48 * f.binding.createFab.resources.displayMetrics.density)
                assertCompleteBadge(f.binding, case.name)
                save(f.binding.root, "home-${case.name}")
                dragHomeHeader(f.binding)
                layout(f.binding, case.width, case.height)
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
                assertCompleteBadge(f.binding, case.name)
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

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun nativeEmptyHomeKeepsArtworkAndAddReachableAcrossConfigurations() = runHomeTest {
        for (case in homePreviewCases()) {
            val direction = if (case.locale == "ar") "ldrtl" else "ldltr"
            val orientation = if (case.width > case.height) "land" else "port"
            val night = if (case.night) "night" else "notnight"
            RuntimeEnvironment.setQualifiers("${case.locale}-$direction-w${case.width}dp-h${case.height}dp-$orientation-$night")
            RuntimeEnvironment.setFontScale(case.scale)
            val f = host()
            try {
                layout(f.binding, case.width, case.height)
                assertTrue(f.binding.homeHeaderSummary.contentDescription.startsWith("vpn0wn"))
                assertFullyVisible(f.binding.homeHeaderSummary, "${case.name}: brand")
                val header = f.binding.homeHeaderSummary
                val countStart = f.fragment.getString(R.string.vpn0wn_home_wordmark).length + 1
                assertEquals("${case.name}: complete count stays together",
                    header.layout.getLineForOffset(countStart), header.layout.getLineForOffset(header.text.length - 1))
                for (line in 0 until header.layout.lineCount) {
                    assertEquals("${case.name}: complete count remains visible", 0, header.layout.getEllipsisCount(line))
                }
                assertFullyVisible(f.binding.createFab, "${case.name}: Add")
                assertTextFits(f.binding.homeHeaderContainer, case.name)
                save(f.binding.root, "brand-empty-${case.name}")
            } finally {
                f.controller.pause().stop().destroy()
                controllers.remove(f.controller)
            }
        }
    }

    @Test @Config(sdk = [34]) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun emptyHomeGroupCentersInTheVisibleViewport() = runHomeTest {
        for (case in homePreviewCases().filter { it.height > it.width }) {
            val direction = if (case.locale == "ar") "ldrtl" else "ldltr"
            val night = if (case.night) "night" else "notnight"
            RuntimeEnvironment.setQualifiers("${case.locale}-$direction-w${case.width}dp-h${case.height}dp-port-$night")
            RuntimeEnvironment.setFontScale(case.scale)
            val f = host()
            try {
                f.binding.homeAppBar.setExpanded(true, false)
                layout(f.binding, case.width, case.height)
                layout(f.binding, case.width, case.height)
                assertEmptyStateCentered(f.binding, "${case.name}-expanded")

                dragHomeHeader(f.binding, f.binding.homeAppBar.height / 2f)
                layout(f.binding, case.width, case.height)
                layout(f.binding, case.width, case.height)
                assertTrue("${case.name}: partial header offset", f.binding.homeAppBar.top < 0)
                assertTrue("${case.name}: header remains partly visible", f.binding.homeAppBar.bottom > 0)
                assertEmptyStateCentered(f.binding, "${case.name}-partial")

                f.binding.homeAppBar.setExpanded(false, false)
                layout(f.binding, case.width, case.height)
                layout(f.binding, case.width, case.height)
                assertEmptyStateCentered(f.binding, "${case.name}-collapsed")
            } finally {
                f.controller.pause().stop().destroy()
                controllers.remove(f.controller)
            }
        }
    }

    @Test @Config(sdk = [34]) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun emptyHomeCentersWhenBindingArrivesAfterInitialLayout() = runHomeTest {
        RuntimeEnvironment.setQualifiers("en-ldltr-w320dp-h640dp-port-notnight")
        RuntimeEnvironment.setFontScale(1f)
        val f = host()
        val content = f.binding.logoPlaceholder.parent as LinearLayout
        val appBar = f.binding.homeAppBar
        BindingAdapters.setHomeEmptyAppBar(content, appBar, null)
        layout(f.binding)
        assertTrue(appBar.isLaidOut)
        assertFalse(appBar.isLayoutRequested)
        BindingAdapters.setHomeEmptyAppBar(content, null, appBar)
        assertTrue("late binding initializes padding without a header gesture",
            content.paddingBottom > content.paddingTop)
        layout(f.binding)
        layout(f.binding)
        assertEmptyStateCentered(f.binding, "late-binding-expanded")
    }

    @Test @Config(sdk = [34]) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun emptyHomeKeyboardFocusKeepsInstructionsReadableAndVisible() = runHomeTest {
        for (case in homePreviewCases()) {
            val direction = if (case.locale == "ar") "ldrtl" else "ldltr"
            val orientation = if (case.width > case.height) "land" else "port"
            val night = if (case.night) "night" else "notnight"
            RuntimeEnvironment.setQualifiers("${case.locale}-$direction-w${case.width}dp-h${case.height}dp-$orientation-$night")
            RuntimeEnvironment.setFontScale(case.scale)
            val f = host()
            try {
                f.binding.homeAppBar.setExpanded(false, false)
                layout(f.binding, case.width, case.height)
                val scroll = f.binding.homeEmptyScroll
                scroll.fullScroll(View.FOCUS_DOWN)
                assertTrue(f.binding.createFab.requestFocusFromTouch())
                shadowOf(Looper.getMainLooper()).idle()
                val ink = scroll.context.getColor(R.color.vpn0wn_ink_2)
                val signal = scroll.context.getColor(R.color.vpn0wn_signal)
                val unfocusedInk = renderedColorCount(scroll, ink)
                val unfocusedSignal = renderedColorCount(scroll, signal)
                val descendants = listOf(f.binding.logoPlaceholder, f.binding.homeEmptyPlaceholder)
                val unfocusedBounds = descendants.map { Rect(it.left, it.top, it.right, it.bottom) }
                assertTrue("${case.name}: instruction text renders", unfocusedInk > 0)
                save(f.binding.root, "empty-unfocused-${case.name}")

                assertTrue(scroll.requestFocusFromTouch())
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(scroll.isFocused)
                assertFalse(scroll.isInTouchMode)
                assertTrue("Platform focus remains enabled", scroll.defaultFocusHighlightEnabled)
                assertEquals("${case.name}: focused instructions keep their ink", unfocusedInk, renderedColorCount(scroll, ink))
                assertTrue("${case.name}: keyboard focus is visible", renderedColorCount(scroll, signal) > unfocusedSignal)
                assertEquals("${case.name}: focus does not move descendants", unfocusedBounds,
                    descendants.map { Rect(it.left, it.top, it.right, it.bottom) })
                assertNotNull("The empty container owns its focus background", scroll.background)
                assertTrue(scroll.background.isStateful)
                assertNull("No foreground overlays the instruction", scroll.foreground)
                assertTrue(f.binding.homeEmptyPlaceholder.isEnabled)
                assertEquals(ink, f.binding.homeEmptyPlaceholder.currentTextColor)
                assertFullyVisible(f.binding.createFab, "${case.name}: Add after focus")
                save(f.binding.root, "empty-focused-${case.name}")

                assertTrue(f.binding.createFab.requestFocusFromTouch())
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse(scroll.isFocused)
                assertEquals(unfocusedInk, renderedColorCount(scroll, ink))
                assertEquals(unfocusedSignal, renderedColorCount(scroll, signal))
            } finally {
                f.controller.pause().stop().destroy()
                controllers.remove(f.controller)
            }
        }
    }

    @Test @Config(sdk = [24])
    fun emptyHomeFocusAndAddStayAvailableOnMinimumApi() = runHomeTest {
        val f = host()
        val scroll = f.binding.homeEmptyScroll
        val instructions = f.binding.homeEmptyPlaceholder.text.toString()
        assertTrue(scroll.requestFocusFromTouch())
        assertTrue(scroll.isFocused)
        assertNotNull(scroll.background)
        assertTrue(scroll.background.isStateful)
        assertTrue(f.binding.homeEmptyPlaceholder.isEnabled)
        assertEquals(instructions, f.binding.homeEmptyPlaceholder.text.toString())
        assertTrue(f.binding.createFab.requestFocusFromTouch())
        assertFalse(scroll.isFocused)
        assertTrue(f.binding.createFab.performClick())
        assertNotNull(f.fragment.childFragmentManager.findFragmentByTag("BOTTOM_SHEET"))
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun largeLandscapeHeaderDragRevealsTunnelAndRestoresSelectionActions() = runHomeTest {
        RuntimeEnvironment.setQualifiers("en-ldltr-w640dp-h320dp-land-night")
        RuntimeEnvironment.setFontScale(2f)
        val f = host("bravo", "charlie")
        layout(f.binding, 640, 320)
        val addBounds = Rect().also { assertTrue(f.binding.createFab.getGlobalVisibleRect(it)) }
        dragHomeHeader(f.binding)
        layout(f.binding, 640, 320)
        assertTrue("header drag releases the viewport", f.binding.homeAppBar.bottom <= 0)
        val first = row(f.binding, 0)
        assertFullyVisible(first.findViewById<View>(R.id.tunnel_name), "tunnel name after header drag")
        assertFullyVisible(first.findViewById<View>(R.id.tunnel_switch), "complete toggle after header drag")
        assertFullyVisible(f.binding.createFab, "fixed Add after header drag")
        assertEquals(addBounds, Rect().also { f.binding.createFab.getGlobalVisibleRect(it) })
        first.performLongClick()
        layout(f.binding, 640, 320)
        assertEquals(0, f.binding.homeAppBar.top)
        assertEquals(View.GONE, f.binding.createFab.visibility)
        for (button in listOf(f.binding.selectionClose, f.binding.selectionDelete, f.binding.selectionAll)) {
            assertFullyVisible(button, "selection action after entering from collapsed header")
        }
        f.binding.selectionAll.performClick()
        layout(f.binding, 640, 320)
        f.binding.selectionDelete.performClick()
        val confirmation = dialog(f.fragment)
        assertTrue(confirmation.isShowing)
        assertEquals("bravo\ncharlie", confirmation.findViewById<TextView>(R.id.delete_sheet_names)!!.text.toString())
        confirmation.cancel()
        dragHomeHeader(f.binding)
        layout(f.binding, 640, 320)
        assertTrue(f.binding.homeAppBar.top < 0)
        row(f.binding, 0).performClick()
        layout(f.binding, 640, 320)
        assertTrue("count update preserves scrolling", f.binding.homeAppBar.top < 0)
        f.controller.get().onBackPressedDispatcher.onBackPressed()
        layout(f.binding, 640, 320)
        assertEquals(0, f.binding.homeAppBar.top)
        assertFullyVisible(f.binding.homeLogs, "Logs after selection exit")
        assertFullyVisible(f.binding.homeSettings, "Settings after selection exit")
        assertFullyVisible(f.binding.createFab, "Add after selection exit")
        assertEquals(setOf("bravo", "charlie"), f.env.store.configs.keys.toSet())
        save(f.binding.root, "large-landscape-header-restored")
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun largeLandscapeEmptyHintScrollsAboveFixedAdd() = runHomeTest {
        RuntimeEnvironment.setQualifiers("en-ldltr-w640dp-h320dp-land-night")
        RuntimeEnvironment.setFontScale(2f)
        val f = host()
        layout(f.binding, 640, 320)
        assertEquals(View.GONE, f.binding.tunnelList.visibility)
        dragHomeHeader(f.binding)
        layout(f.binding, 640, 320)
        assertTrue("empty header permits dragging", f.binding.homeAppBar.bottom <= 0)
        f.binding.homeEmptyScroll.fullScroll(View.FOCUS_DOWN)
        layout(f.binding, 640, 320)
        assertFullyVisible(f.binding.homeEmptyPlaceholder, "empty hint after scrolling")
        assertFullyVisible(f.binding.createFab, "fixed Add with empty hint")
        val hintBounds = Rect().also { f.binding.homeEmptyPlaceholder.getGlobalVisibleRect(it) }
        val addBounds = Rect().also { f.binding.createFab.getGlobalVisibleRect(it) }
        assertTrue("empty hint clears Add", hintBounds.bottom <= addBounds.top)
        save(f.binding.root, "empty-large-landscape-scrolled")
    }

    @Test fun homeHeaderActionsKeepExistingNavigationAndDisabledSearch() = runHomeTest {
        val f = host("bravo")
        assertFalse(f.binding.homeSearch.isEnabled)
        assertEquals("vpnøwn · 1 configured", f.binding.homeHeaderSummary.text.toString().replace('\u00a0', ' '))
        assertEquals("vpn0wn · 1 configured", f.binding.homeHeaderSummary.contentDescription.toString())
        f.binding.homeLogs.performClick()
        assertEquals("com.vpn0wn.android.activity.LogViewerActivity",
            shadowOf(f.controller.get()).nextStartedActivity.component!!.className)
        f.binding.homeSettings.performClick()
        assertEquals("com.vpn0wn.android.activity.SettingsActivity",
            shadowOf(f.controller.get()).nextStartedActivity.component!!.className)
        f.binding.createFab.performClick()
        assertNotNull(f.fragment.childFragmentManager.findFragmentByTag("BOTTOM_SHEET"))
    }

    @Test fun cipherAppearsOnlyBesideStatusAndTracksPskChanges() = runHomeTest {
        val f = host("bravo")
        val tunnel = f.binding.tunnels!!["bravo"]!!
        for (withPsk in listOf(false, true)) {
            tunnel.setConfigAsync(profileWithPeer(withPsk))
            runCurrent()
            layout(f.binding)
            val item = row(f.binding, 0)
            val badge = item.findViewById<TextView>(R.id.tunnel_peer_badge)
            val cipher = if (withPsk) "ChaCha20 + PSK" else "ChaCha20"
            val expected = "${f.fragment.getString(R.string.vpn0wn_tunnel_status_inactive)} · $cipher"
            assertEquals(expected, badge.text.toString())
            assertEquals("cipher retains mixed case", expected,
                badge.transformationMethod?.getTransformation(badge.text, badge)?.toString() ?: badge.text.toString())
            assertEquals(item.findViewById<View>(R.id.tunnel_header), badge.parent)
            assertEquals(listOf(badge), textViews(item).filter { it.text.contains("ChaCha20") })
            assertFalse(textViews(item.findViewById(R.id.tunnel_stats)).any { it.text.contains("ChaCha20") })
        }
    }

    private fun profileWithPeer(withPsk: Boolean): com.vpn0wn.config.Config {
        val psk = if (withPsk) "PresharedKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" else ""
        return com.vpn0wn.config.Config.parse("""
            [Interface]
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            [Peer]
            PublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            $psk
            Endpoint = 198.51.100.42:51820
        """.trimIndent().byteInputStream())
    }

    private fun recentStatistics(): Statistics {
        val snapshot = Statistics::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        Statistics::class.java.getDeclaredMethod("add", Key::class.java, Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(snapshot, Key.fromBytes(ByteArray(32)), 0L, 0L, System.currentTimeMillis() - 5_000L)
        return snapshot
    }

    private fun assertEndpointLayout(binding: TunnelListFragmentBinding, name: String) {
        val icon = binding.homeEndpointIcon
        val label = binding.homeEndpointLabel
        val value = binding.homeEndpointHost
        assertFullyVisible(icon, "$name: endpoint icon")
        assertFullyVisible(label, "$name: endpoint label")
        assertFullyVisible(value, "$name: endpoint value")
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, icon.importantForAccessibility)
        assertFalse(icon.isClickable)
        assertEquals(binding.root.context.getString(R.string.vpn0wn_home_endpoint), label.text.toString())
        val iconBounds = Rect().also { icon.getGlobalVisibleRect(it) }
        for (text in listOf(label, value)) {
            val textBounds = Rect().also { text.getGlobalVisibleRect(it) }
            assertFalse("$name: endpoint text overlaps icon", Rect.intersects(iconBounds, textBounds))
            if (binding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                assertTrue("$name: icon at logical end", iconBounds.right <= textBounds.left)
            } else {
                assertTrue("$name: icon at logical end", textBounds.right <= iconBounds.left)
            }
        }
    }

    private fun textViews(view: View): List<TextView> {
        val texts = mutableListOf<TextView>()
        if (view is TextView) texts += view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) texts += textViews(view.getChildAt(index))
        }
        return texts
    }

    private fun assertTextFits(view: View, name: String) {
        if (view.visibility != View.VISIBLE) return
        if (view is TextView && view.text.isNotEmpty()) {
            assertTrue("$name: ${view.text}", view.layout.getLineTop(view.layout.lineCount) <= view.height)
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) assertTextFits(view.getChildAt(index), name)
    }

    private fun assertFullyVisible(view: View, name: String) {
        val bounds = Rect()
        assertTrue("$name is visible", view.getGlobalVisibleRect(bounds))
        assertEquals("$name complete width", view.width, bounds.width())
        assertEquals("$name complete height", view.height, bounds.height())
    }

    private fun assertEmptyStateCentered(binding: TunnelListFragmentBinding, name: String) {
        val viewport = Rect().also { assertTrue(binding.homeEmptyScroll.getGlobalVisibleRect(it)) }
        val artwork = Rect().also { assertTrue(binding.logoPlaceholder.getGlobalVisibleRect(it)) }
        val guidance = Rect().also { assertTrue(binding.homeEmptyPlaceholder.getGlobalVisibleRect(it)) }
        assertFullyVisible(binding.logoPlaceholder, "$name: empty artwork")
        assertFullyVisible(binding.homeEmptyPlaceholder, "$name: empty guidance")
        assertEquals("$name: horizontally centered artwork", viewport.centerX(), artwork.centerX())
        val guidanceLayout = binding.homeEmptyPlaceholder.layout
        for (line in 0 until guidanceLayout.lineCount) {
            assertEquals("$name: centered guidance line $line", guidanceLayout.width / 2f,
                (guidanceLayout.getLineLeft(line) + guidanceLayout.getLineRight(line)) / 2f, 1f)
        }
        assertEquals("$name: vertically centered empty group", viewport.exactCenterY(),
            (artwork.top + guidance.bottom) / 2f, 1f)
        assertFullyVisible(binding.createFab, "$name: fixed Add")
        save(binding.root, "empty-centered-$name")
    }

    private fun dragHomeHeader(binding: TunnelListFragmentBinding, distance: Float = binding.homeAppBar.height * 2f) {
        val container = binding.homeScrollContainer
        val density = container.resources.displayMetrics.density
        val startY = minOf(binding.homeAppBar.bottom, container.height) - 8 * density
        val downTime = SystemClock.uptimeMillis()
        fun dispatch(action: Int, elapsed: Long, y: Float) {
            val event = MotionEvent.obtain(downTime, downTime + elapsed, action, container.width / 2f, y, 0)
            try {
                container.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
        dispatch(MotionEvent.ACTION_DOWN, 0, startY)
        for (step in 1..8) dispatch(MotionEvent.ACTION_MOVE, step * 100L, startY - distance * step / 8f)
        dispatch(MotionEvent.ACTION_UP, 1800, startY - distance)
    }

    private fun assertCompleteBadge(binding: TunnelListFragmentBinding, name: String) {
        for (index in 0 until binding.tunnelList.childCount) {
            val item = binding.tunnelList.getChildAt(index)
            val badge = item.findViewById<TextView>(R.id.tunnel_peer_badge)
            val tunnel = binding.tunnels!![item.findViewById<TextView>(R.id.tunnel_name).text.toString()]!!
            val expectedCipher = if (tunnel.name == "charlie") "ChaCha20 + PSK" else "unbound"
            assertTrue("$name: complete cipher", badge.text.endsWith(" · $expectedCipher"))
            assertEquals("$name: all badge characters laid out", badge.text.length,
                badge.layout.getLineEnd(badge.layout.lineCount - 1))
            assertTextFits(badge, name)
            for (line in 0 until badge.layout.lineCount) {
                assertEquals("$name: no hidden cipher", 0, badge.layout.getEllipsisCount(line))
            }
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

    private fun renderedColorCount(view: View, color: Int): Int {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(view.context.getColor(R.color.vpn0wn_paper))
        canvas.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
        view.draw(canvas)
        val pixels = IntArray(view.width * view.height)
        bitmap.getPixels(pixels, 0, view.width, 0, 0, view.width, view.height)
        bitmap.recycle()
        return pixels.count { it == color }
    }

    private data class Preview(val name: String, val width: Int, val height: Int, val scale: Float, val locale: String, val night: Boolean)

    private fun homePreviewCases() = listOf(
        Preview("portrait-light", 320, 640, 1f, "en", false),
        Preview("portrait-dark", 320, 640, 1f, "en", true),
        Preview("landscape-dark", 640, 320, 1f, "en", true),
        Preview("large-landscape-dark", 640, 320, 2f, "en", true),
        Preview("large-rtl-light", 320, 640, 2f, "ar", false),
        Preview("large-rtl-dark", 320, 640, 2f, "ar", true)
    )

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
        env.manager.getTunnels().forEach { it.getConfigAsync() }
        val controller = Robolectric.buildActivity(HostActivity::class.java)
        controllers += controller
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
        controller.get().supportActionBar?.hide()
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

    private fun deletion(fragment: TunnelListFragment): Job = checkNotNull(pendingDeletion(fragment))

    private fun pendingDeletion(fragment: TunnelListFragment): Job? =
        TunnelListFragment::class.java.getDeclaredField("deleteJob").apply { isAccessible = true }.get(fragment) as? Job

    private fun message(binding: TunnelListFragmentBinding): String =
        binding.root.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).text.toString()

    private data class Fixture(val env: TunnelTestEnvironment, val controller: ActivityController<HostActivity>,
        val fragment: TunnelListFragment, val binding: TunnelListFragmentBinding)

    private fun layout(binding: TunnelListFragmentBinding, width: Int = 320, height: Int = 640) {
        shadowOf(Looper.getMainLooper()).idle()
        binding.executePendingBindings()
        val density = binding.root.resources.displayMetrics.density
        val root = binding.root.rootView
        invalidateMeasurements(root)
        root.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    // Manual preview resizes otherwise retain cached descendant sizes from the activity's earlier traversal.
    private fun invalidateMeasurements(view: View) {
        view.forceLayout()
        if (view is ViewGroup) for (index in 0 until view.childCount) invalidateMeasurements(view.getChildAt(index))
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
