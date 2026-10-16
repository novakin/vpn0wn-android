/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Modified for vpn0wn.
 */
package com.vpn0wn.android.activity

import com.vpn0wn.android.util.applySafeAreaInsets

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.appcompat.app.ActionBar
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.commit
import com.vpn0wn.android.R
import com.vpn0wn.android.fragment.TunnelDetailFragment
import com.vpn0wn.android.fragment.TunnelEditorFragment
import com.vpn0wn.android.fragment.VpsProvisioningFragment
import com.vpn0wn.android.model.ObservableTunnel

/**
 * CRUD interface for WireGuard tunnels. This activity serves as the main entry point to the
 * WireGuard application, and contains several fragments for listing, viewing details of, and
 * editing the configuration and interface state of WireGuard tunnels.
 */
class MainActivity : BaseActivity(), FragmentManager.OnBackStackChangedListener {
    private var actionBar: ActionBar? = null
    private var isTwoPaneLayout = false
    private var backPressedCallback: OnBackPressedCallback? = null

    private fun handleBackPressed() {
        val backStackEntries = supportFragmentManager.backStackEntryCount
        // If the two-pane layout does not have an editor open, going back should exit the app.
        if (isTwoPaneLayout && backStackEntries <= 1) {
            finish()
            return
        }

        if (backStackEntries >= 1)
            supportFragmentManager.popBackStack()

        // Deselect the current tunnel on navigating back from the detail pane to the one-pane list.
        if (backStackEntries == 1)
            selectedTunnel = null
    }

    override fun onBackStackChanged() {
        val backStackEntries = supportFragmentManager.backStackEntryCount
        val background = if (backStackEntries == 0) R.color.vpn0wn_paper else R.color.vpn0wn_paper_2
        findViewById<View>(R.id.main_activity_container).setBackgroundResource(background)
        // Edge-to-edge ignores statusBarColor on Android 15+.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            @Suppress("DEPRECATION")
            window.statusBarColor = getColor(background)
        }
        backPressedCallback?.isEnabled = backStackEntries >= 1
        if (actionBar == null) return
        actionBar!!.hide()
        // Do not show the home menu when the two-pane layout is at the detail view (see above).
        val minBackStackEntries = if (isTwoPaneLayout) 2 else 1
        actionBar!!.setDisplayHomeAsUpEnabled(backStackEntries >= minBackStackEntries)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)
        findViewById<View>(R.id.main_activity_container).applySafeAreaInsets()
        actionBar = supportActionBar
        isTwoPaneLayout = findViewById<View?>(R.id.master_detail_wrapper) != null
        supportFragmentManager.addOnBackStackChangedListener(this)
        backPressedCallback = onBackPressedDispatcher.addCallback(this) { handleBackPressed() }
        onBackStackChanged()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_activity, menu)
        return true
    }

    fun openTunnelEditor() {
        supportFragmentManager.commit {
            replace(if (isTwoPaneLayout) R.id.detail_container else R.id.list_detail_container, TunnelEditorFragment())
            setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            addToBackStack(null)
        }
    }

    fun shouldShowDetailBackButton(): Boolean =
        !isTwoPaneLayout || supportFragmentManager.backStackEntryCount > 1

    fun openServers() {
        val container = if (isTwoPaneLayout) R.id.detail_container else R.id.list_detail_container
        if (supportFragmentManager.isStateSaved || supportFragmentManager.findFragmentById(container) is VpsProvisioningFragment) return
        supportFragmentManager.commit {
            replace(container, VpsProvisioningFragment())
            setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            addToBackStack(null)
        }
    }

    fun selectTunnel(tunnel: ObservableTunnel) {
        val fragmentManager = supportFragmentManager
        if (fragmentManager.isStateSaved) return
        val container = if (isTwoPaneLayout) R.id.detail_container else R.id.list_detail_container
        if (fragmentManager.findFragmentById(container) is VpsProvisioningFragment)
            fragmentManager.popBackStackImmediate()
        selectedTunnel = tunnel
    }

    fun onDetailBackPressed() {
        if (shouldShowDetailBackButton())
            onBackPressedDispatcher.onBackPressed()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                // The back arrow in the action bar should act the same as the back button.
                onBackPressedDispatcher.onBackPressed()
                true
            }

            R.id.menu_action_edit -> {
                openTunnelEditor()
                true
            }
            // This menu item is handled by the editor fragment.
            R.id.menu_action_save -> false
            R.id.menu_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSelectedTunnelChanged(
        oldTunnel: ObservableTunnel?,
        newTunnel: ObservableTunnel?
    ): Boolean {
        val fragmentManager = supportFragmentManager
        if (fragmentManager.isStateSaved) {
            return false
        }

        val backStackEntries = fragmentManager.backStackEntryCount
        val container = if (isTwoPaneLayout) R.id.detail_container else R.id.list_detail_container
        if (newTunnel == null) {
            // Clear everything off the back stack (all editors and detail fragments).
            fragmentManager.popBackStackImmediate(0, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        } else if (backStackEntries == BACK_STACK_DETAIL_AND_EDITOR && fragmentManager.findFragmentById(container) is TunnelEditorFragment) {
            // Pop the editor off the back stack to reveal the detail fragment. Use the immediate
            // method to avoid the editor picking up the new tunnel while it is still visible.
            fragmentManager.popBackStackImmediate()
        } else if (backStackEntries == 0) {
            // Create and show a new detail fragment.
            fragmentManager.commit {
                add(container, TunnelDetailFragment())
                setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                addToBackStack(null)
            }
        }
        return true
    }

    companion object {
        private const val BACK_STACK_DETAIL_AND_EDITOR = 2
    }
}
