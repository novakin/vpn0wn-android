/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.fragment

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class VpsBackupPickerLifecycleTest {
    @Test
    fun pendingFragmentResultArrivesBeforeItsViewStarts() {
        val controller = Robolectric.buildActivity(PickerActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val fragment = PickerFragment()
            activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment, "picker").commitNow()
            fragment.open()
            val request = shadowOf(activity).nextStartedActivityForResult
            controller.pause().stop()
            assertEquals(Lifecycle.State.CREATED, fragment.viewLifecycleOwner.lifecycle.currentState)
            assertTrue(activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent().setData(INPUT)))
            assertTrue(fragment.results.isEmpty())
            controller.start().resume()
            assertEquals(listOf(INPUT), fragment.results)
            assertEquals(listOf(Lifecycle.State.CREATED), fragment.callbackStates)
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun recreationAndAnotherLaunchReuseTheOriginalResultRoute() {
        val controller = Robolectric.buildActivity(PickerActivity::class.java).setup().visible()
        try {
            var activity = controller.get()
            val original = PickerFragment()
            activity.supportFragmentManager.beginTransaction().add(android.R.id.content, original, "picker").commitNow()
            original.open()
            val first = shadowOf(activity).nextStartedActivityForResult
            controller.recreate()
            activity = controller.get()
            val replacement = activity.supportFragmentManager.findFragmentByTag("picker") as PickerFragment
            assertTrue(replacement !== original)
            replacement.open()
            val second = shadowOf(activity).nextStartedActivityForResult
            assertEquals(first.requestCode, second.requestCode)
            assertTrue(activity.activityResultRegistry.dispatchResult(first.requestCode, Activity.RESULT_OK, Intent().setData(INPUT)))
            assertEquals(listOf(INPUT), replacement.results)
        } finally { controller.pause().stop().destroy() }
    }

    class PickerActivity : FragmentActivity()

    class PickerFragment : Fragment() {
        val results = mutableListOf<Uri?>()
        val callbackStates = mutableListOf<Lifecycle.State>()
        private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            callbackStates += viewLifecycleOwner.lifecycle.currentState
            results += uri
        }

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = FrameLayout(requireContext())

        fun open() { picker.launch(arrayOf("*/*")) }
    }

    private companion object {
        val INPUT: Uri = Uri.parse("content://backup-fixture/archive")
    }
}
