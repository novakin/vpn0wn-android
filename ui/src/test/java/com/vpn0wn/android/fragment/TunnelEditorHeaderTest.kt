/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.fragment

import android.app.Application
import android.app.Activity
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.lifecycleScope
import com.vpn0wn.android.R
import com.vpn0wn.android.activity.BaseActivity
import com.vpn0wn.android.activity.TunnelCreatorActivity
import com.vpn0wn.android.databinding.TunnelEditorFragmentBinding
import com.vpn0wn.android.model.ObservableTunnel
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.android.viewmodel.ConfigProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class TunnelEditorHeaderTest {
    @get:Rule val folder = TemporaryFolder()
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun labeledSaveCreatesValidatedProfileThroughExistingOwner() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.root, backgroundScope)
        env.manager.getTunnels()
        val controller = Robolectric.buildActivity(TunnelCreatorActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.AppTheme) }
        controller.setup()
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.editor_fragment) as TunnelEditorFragment
        val binding = DataBindingUtil.getBinding<TunnelEditorFragmentBinding>(fragment.requireView())!!
        try {
            assertTrue("Editor Close has native feedback", requireNotNull(binding.editorCloseButton.background).isStateful)
            binding.config = ConfigProxy(TunnelTestEnvironment.profile(51821))
            binding.name = "qa-editor"
            binding.executePendingBindings()
            assertEquals(activity.getString(R.string.vpn0wn_editor_new_tunnel), binding.editorTitle.text.toString())
            assertTrue(binding.editorSaveButton.performClick())
            runCurrent()
            activity.lifecycleScope.coroutineContext[Job]!!.children.toList().joinAll()
            assertEquals(51821, env.store.configs["qa-editor"]!!.getInterface().listenPort.get())
            assertTrue(activity.isFinishing)
            assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
            assertEquals(activity.getString(R.string.tunnel_create_success, "qa-editor"),
                shadowOf(activity).resultIntent.getStringExtra(TunnelCreatorActivity.EXTRA_SAVE_MESSAGE))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun labeledSaveFailureKeepsEditorAndExistingProfile() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.root, backgroundScope)
        val original = TunnelTestEnvironment.profile(51820)
        val tunnel = env.manager.create("qa-editor", original)
        val controller = Robolectric.buildActivity(HostActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.AppTheme) }
        controller.setup()
        val fragment = TunnelEditorFragment()
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        val binding = DataBindingUtil.getBinding<TunnelEditorFragmentBinding>(fragment.requireView())!!
        try {
            fragment.onSelectedTunnelChanged(null, tunnel)
            runCurrent()
            binding.config = ConfigProxy(TunnelTestEnvironment.profile(51821))
            binding.executePendingBindings()
            assertEquals("qa-editor", binding.editorTitle.text.toString())
            env.store.failSave = true
            assertTrue(binding.editorSaveButton.performClick())
            runCurrent()
            activity.lifecycleScope.coroutineContext[Job]!!.children.toList().joinAll()
            assertSame(original, env.store.configs["qa-editor"])
            assertSame(fragment, activity.supportFragmentManager.findFragmentById(android.R.id.content))
            assertTrue(binding.editorSaveButton.isEnabled)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    class HostActivity : BaseActivity() {
        override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) = true
    }
}
