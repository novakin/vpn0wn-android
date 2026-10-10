/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import java.util.concurrent.TimeUnit
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.databinding.DataBindingUtil
import androidx.databinding.Observable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.BR
import com.wireguard.android.R
import com.wireguard.android.activity.MainActivity
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.databinding.TunnelDetailFragmentBinding
import com.wireguard.android.databinding.TunnelDetailPeerBinding
import com.wireguard.android.model.ObservableTunnel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TunnelDetailFragment : BaseFragment(), MenuProvider {

    companion object {
        private const val TAG = "WireGuard/TunnelDetailFragment"
    }
    private var binding: TunnelDetailFragmentBinding? = null
    private var lastState: Tunnel.State? = null
    private var observedTunnel: ObservableTunnel? = null
    private var statsJob: Job? = null
    private var configJob: Job? = null
    private val tunnelCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            val binding = binding ?: return
            if (sender !is ObservableTunnel || sender !== observedTunnel || sender !== binding.tunnel) return
            when (propertyId) {
                BR.config -> binding.config = sender.peekConfig()
                BR.state -> {
                    viewLifecycleOwner.lifecycleScope.launch { updateStats() }
                    if (sender.state == Tunnel.State.UP) {
                        refreshSelectedStats()
                    }
                }
            }
        }
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return false
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.tunnel_detail, menu)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        binding = TunnelDetailFragmentBinding.inflate(inflater, container, false)
        binding?.executePendingBindings()
        return binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.hide()
        binding?.apply {
            val showBackButton = (activity as? MainActivity)?.shouldShowDetailBackButton() != false
            detailBackButton.visibility = if (showBackButton) View.VISIBLE else View.INVISIBLE
        }
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onDestroyView() {
        configJob?.cancel()
        configJob = null
        stopStatsRefresh()
        observedTunnel?.removeOnPropertyChangedCallback(tunnelCallback)
        observedTunnel = null
        binding = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.hide()
        startStatsRefresh()
    }

    fun onBackPressed(@Suppress("UNUSED_PARAMETER") view: View) {
        (activity as? MainActivity)?.onDetailBackPressed()
            ?: requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    fun onEditPressed(@Suppress("UNUSED_PARAMETER") view: View) {
        (requireActivity() as? MainActivity)?.openTunnelEditor()
    }

    // Backend/ConfigStore expose Exception; this operation reports their failures while cancellation and Errors propagate.
    @Suppress("TooGenericExceptionCaught")
    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
        val binding = binding ?: return
        configJob?.cancel()
        binding.config = null
        binding.tunnel = newTunnel
        observedTunnel?.removeOnPropertyChangedCallback(tunnelCallback)
        observedTunnel = newTunnel
        newTunnel?.addOnPropertyChangedCallback(tunnelCallback)
        if (newTunnel == null) {
            binding.config = null
        } else {
            configJob = viewLifecycleOwner.lifecycleScope.launch {
                val config = try {
                    newTunnel.getConfigAsync()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (this@TunnelDetailFragment.binding === binding && observedTunnel === newTunnel)
                    binding.config = config
            }
        }
        lastState = null
        refreshSelectedStats()
        viewLifecycleOwner.lifecycleScope.launch { updateStats() }
    }

    override fun onStop() {
        stopStatsRefresh()
        super.onStop()
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        binding ?: return
        binding!!.fragment = this
        onSelectedTunnelChanged(null, selectedTunnel)
        super.onViewStateRestored(savedInstanceState)
    }

    // View/state guards prevent stale updates; Backend statistics failures are reported and cancellation propagates.
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private suspend fun updateStats() {
        val binding = binding ?: return
        val tunnel = binding.tunnel ?: return
        if (!isResumed) return
        val state = tunnel.state
        if (state == Tunnel.State.TOGGLE) {
            lastState = state
            return
        }
        if (state != Tunnel.State.UP && lastState != null && lastState == state) return
        lastState = state
        val statistics = try {
            if (state == Tunnel.State.UP) tunnel.getStatisticsAsync() else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (this.binding !== binding || binding.tunnel !== tunnel || !isResumed)
                return
            Log.w(TAG, "Failed to update tunnel statistics", e)
            for (i in 0 until binding.peersLayout.childCount) {
                val peer: TunnelDetailPeerBinding = DataBindingUtil.getBinding(binding.peersLayout.getChildAt(i))
                    ?: continue
                peer.statistics = null
            }
            return
        }
        if (this.binding !== binding || binding.tunnel !== tunnel || tunnel.state != state || !isResumed)
            return
        for (i in 0 until binding.peersLayout.childCount) {
            val peer: TunnelDetailPeerBinding = DataBindingUtil.getBinding(binding.peersLayout.getChildAt(i))
                ?: continue
            peer.peerTitle.text = "PEER #${(i + 1).toString().padStart(2, '0')}"
            peer.statistics = peer.item?.publicKey?.let { statistics?.peer(it) }
        }
    }

    // Backend/ConfigStore expose Exception; this operation reports their failures while cancellation and Errors propagate.
    @Suppress("TooGenericExceptionCaught")
    private fun refreshSelectedStats() {
        val tunnel = binding?.tunnel ?: return
        if (!isResumed || tunnel.state != Tunnel.State.UP) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                tunnel.getStatisticsAsync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh tunnel statistics", e)
            }
        }
    }

    private fun startStatsRefresh() {
        if (statsJob?.isActive == true) return
        statsJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                updateStats()
                delay(TimeUnit.SECONDS.toMillis(1))
            }
        }
    }

    private fun stopStatsRefresh() {
        statsJob?.cancel()
        statsJob = null
    }
}
