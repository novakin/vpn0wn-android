/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Modified for vpn0wn.
 */
package com.vpn0wn.android.activity

import android.view.View
import com.vpn0wn.android.util.applySafeAreaInsets

import android.os.Bundle
import com.vpn0wn.android.Application
import com.vpn0wn.android.R
import com.vpn0wn.android.model.ObservableTunnel

/**
 * Standalone activity for creating tunnels.
 */
class TunnelCreatorActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.tunnel_creator_activity)
        supportActionBar?.hide()
        findViewById<View>(R.id.main_activity_container).applySafeAreaInsets()
        Application.getTunnelManager().onActivityCreated()
    }

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_SAVE_MESSAGE = "save_message"
    }
}
