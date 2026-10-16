/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Modified for vpn0wn.
 */
package com.vpn0wn.android.activity

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.CallbackRegistry
import androidx.databinding.CallbackRegistry.NotifierCallback
import androidx.lifecycle.lifecycleScope
import com.vpn0wn.android.Application
import com.vpn0wn.android.R
import com.vpn0wn.android.model.ObservableTunnel
import com.vpn0wn.android.util.ErrorMessages
import com.vpn0wn.android.util.RootShell.RootShellException
import java.io.IOException
import kotlinx.coroutines.launch

/**
 * Base class for activities that need to remember the currently-selected tunnel.
 */
abstract class BaseActivity : AppCompatActivity() {
    private val selectionChangeRegistry = SelectionChangeRegistry()
    private var created = false
    var selectedTunnel: ObservableTunnel? = null
        set(value) {
            val oldTunnel = field
            if (oldTunnel == value) return
            field = value
            if (created) {
                if (!onSelectedTunnelChanged(oldTunnel, value)) {
                    field = oldTunnel
                } else {
                    selectionChangeRegistry.notifyCallbacks(oldTunnel, 0, value)
                }
            }
        }

    fun addOnSelectedTunnelChangedListener(listener: OnSelectedTunnelChangedListener) {
        selectionChangeRegistry.add(listener)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Restore the saved tunnel if there is one; otherwise grab it from the arguments.
        val savedTunnelName = when {
            savedInstanceState != null -> savedInstanceState.getString(KEY_SELECTED_TUNNEL)
            intent != null -> intent.getStringExtra(KEY_SELECTED_TUNNEL)
            else -> null
        }
        if (savedTunnelName != null) {
            lifecycleScope.launch {
                val tunnel = try {
                    Application.getTunnelManager().getTunnels()[savedTunnelName]
                } catch (failure: IOException) {
                    reportInventoryFailure(failure)
                    created = true
                    return@launch
                } catch (failure: RootShellException) {
                    reportInventoryFailure(failure)
                    created = true
                    return@launch
                }
                if (tunnel == null)
                    created = true
                selectedTunnel = tunnel
                created = true
            }
        } else {
            created = true
        }
    }

    private fun reportInventoryFailure(failure: Exception) {
        val message = getString(R.string.error_prepare, ErrorMessages[failure])
        Log.e(TAG, message, failure)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (selectedTunnel != null) outState.putString(KEY_SELECTED_TUNNEL, selectedTunnel!!.name)
        super.onSaveInstanceState(outState)
    }

    protected abstract fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?): Boolean

    fun removeOnSelectedTunnelChangedListener(
        listener: OnSelectedTunnelChangedListener
    ) {
        selectionChangeRegistry.remove(listener)
    }

    interface OnSelectedTunnelChangedListener {
        fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?)
    }

    private class SelectionChangeNotifier : NotifierCallback<OnSelectedTunnelChangedListener, ObservableTunnel, ObservableTunnel>() {
        override fun onNotifyCallback(
            listener: OnSelectedTunnelChangedListener,
            oldTunnel: ObservableTunnel?,
            ignored: Int,
            newTunnel: ObservableTunnel?
        ) {
            listener.onSelectedTunnelChanged(oldTunnel, newTunnel)
        }
    }

    private class SelectionChangeRegistry :
        CallbackRegistry<OnSelectedTunnelChangedListener, ObservableTunnel, ObservableTunnel>(SelectionChangeNotifier())

    companion object {
        private const val TAG = "vpn0wn/BaseActivity"
        private const val KEY_SELECTED_TUNNEL = "selected_tunnel"
    }
}
