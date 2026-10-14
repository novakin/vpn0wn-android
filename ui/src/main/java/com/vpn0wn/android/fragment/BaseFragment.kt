/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Modified for vpn0wn.
 */
package com.vpn0wn.android.fragment

import android.content.ActivityNotFoundException
import android.app.Activity
import kotlinx.coroutines.CancellationException
import android.content.Context
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModel
import com.google.android.material.snackbar.Snackbar
import com.vpn0wn.android.Application
import com.vpn0wn.android.R
import com.vpn0wn.android.BR
import com.vpn0wn.android.activity.BaseActivity
import com.vpn0wn.android.activity.BaseActivity.OnSelectedTunnelChangedListener
import com.vpn0wn.android.backend.GoBackend
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.databinding.TunnelDetailFragmentBinding
import com.vpn0wn.android.databinding.TunnelListItemBinding
import com.vpn0wn.android.model.ObservableTunnel
import com.vpn0wn.android.util.ErrorMessages
import com.vpn0wn.android.util.vpn0wnStyle
import kotlinx.coroutines.launch

/**
 * Base class for fragments that need to know the currently-selected tunnel. Only does anything when
 * attached to a `BaseActivity`.
 */
abstract class BaseFragment : Fragment(), OnSelectedTunnelChangedListener {
    private val permissionState by viewModels<VpnPermissionViewModel>()
    private val permissionActivityResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request = permissionState.request
        permissionState.request = null
        if (request != null) {
            if (result.resultCode == Activity.RESULT_OK)
                setTunnelStateWithPermissionsResult(request.tunnel, request.checked)
            else
                request.tunnel.notifyPropertyChanged(BR.state)
        }
    }

    protected var selectedTunnel: ObservableTunnel?
        get() = (activity as? BaseActivity)?.selectedTunnel
        protected set(tunnel) {
            (activity as? BaseActivity)?.selectedTunnel = tunnel
        }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        (activity as? BaseActivity)?.addOnSelectedTunnelChangedListener(this)
    }

    override fun onDetach() {
        (activity as? BaseActivity)?.removeOnSelectedTunnelChangedListener(this)
        super.onDetach()
    }

    fun setTunnelState(view: View, checked: Boolean) {
        val tunnel = when (val binding = DataBindingUtil.findBinding<ViewDataBinding>(view)) {
            is TunnelDetailFragmentBinding -> binding.tunnel
            is TunnelListItemBinding -> binding.item
            else -> null
        } ?: return
        val activity = activity ?: return
        fun reportPreparationFailure(error: Exception) {
            val message = activity.getString(R.string.error_prepare, ErrorMessages[error])
            Snackbar.make(view, message, Snackbar.LENGTH_LONG)
                .setAnchorView(view.findViewById(R.id.create_fab))
                .vpn0wnStyle()
                .show()
            Log.e(TAG, message, error)
        }
        lifecycleScope.launch {
            if (Application.getBackend() is GoBackend) {
                try {
                    val intent = GoBackend.VpnService.prepare(activity)
                    if (intent != null) {
                        permissionState.request = VpnPermissionViewModel.Request(tunnel, checked)
                        permissionActivityResultLauncher.launch(intent)
                        return@launch
                    }
                } catch (e: SecurityException) {
                    permissionState.request = null
                    tunnel.notifyPropertyChanged(BR.state)
                    reportPreparationFailure(e)
                    return@launch
                } catch (e: ActivityNotFoundException) {
                    permissionState.request = null
                    tunnel.notifyPropertyChanged(BR.state)
                    reportPreparationFailure(e)
                    return@launch
                }
            }
            setTunnelStateWithPermissionsResult(tunnel, checked)
        }
    }

    // Backend/ConfigStore expose Exception; this operation reports their failures while cancellation and Errors propagate.
    @Suppress("TooGenericExceptionCaught")
    private fun setTunnelStateWithPermissionsResult(tunnel: ObservableTunnel, checked: Boolean) {
        val activity = activity ?: return
        activity.lifecycleScope.launch {
            try {
                tunnel.setStateAsync(Tunnel.State.of(checked))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = ErrorMessages[e]
                val messageResId = if (checked) R.string.error_up else R.string.error_down
                val message = activity.getString(messageResId, error)
                val view = view
                if (view != null)
                    Snackbar.make(view, message, Snackbar.LENGTH_LONG)
                        .setAnchorView(view.findViewById(R.id.create_fab))
                        .vpn0wnStyle()
                        .show()
                else
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                Log.e(TAG, message, e)
            } finally {
                tunnel.notifyPropertyChanged(BR.state)
            }
        }
    }

    companion object {
        private const val TAG = "vpn0wn/BaseFragment"
    }
}

internal class VpnPermissionViewModel : ViewModel() {
    var request: Request? = null

    data class Request(val tunnel: ObservableTunnel, val checked: Boolean)
}
