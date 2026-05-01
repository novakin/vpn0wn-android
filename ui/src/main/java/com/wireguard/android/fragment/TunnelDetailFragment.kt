/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
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
import com.wireguard.android.util.QuantityFormatter
import kotlinx.coroutines.Job
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
    private val tunnelCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (propertyId == BR.state) {
                lifecycleScope.launch { updateStats() }
                if (sender is ObservableTunnel && sender.state == Tunnel.State.UP) {
                    refreshSelectedStats()
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
            val baseHeaderTopPadding = detailHeaderContainer.paddingTop
            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                detailHeaderContainer.updatePadding(top = baseHeaderTopPadding + systemBars.top)
                insets
            }
            ViewCompat.requestApplyInsets(root)
        }
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onDestroyView() {
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

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
        val binding = binding ?: return
        binding.tunnel = newTunnel
        observedTunnel?.removeOnPropertyChangedCallback(tunnelCallback)
        observedTunnel = newTunnel
        newTunnel?.addOnPropertyChangedCallback(tunnelCallback)
        if (newTunnel == null) {
            binding.config = null
        } else {
            lifecycleScope.launch {
                try {
                    binding.config = newTunnel.getConfigAsync()
                } catch (_: Exception) {
                    binding.config = null
                }
            }
        }
        lastState = null
        refreshSelectedStats()
        lifecycleScope.launch { updateStats() }
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
        try {
            val statistics = tunnel.getStatisticsAsync()
            for (i in 0 until binding.peersLayout.childCount) {
                val peer: TunnelDetailPeerBinding = DataBindingUtil.getBinding(binding.peersLayout.getChildAt(i))
                    ?: continue
                peer.peerTitle.text = "PEER #${(i + 1).toString().padStart(2, '0')}"
                val publicKey = peer.item!!.publicKey
                val peerStats = statistics.peer(publicKey)
                if (peerStats == null || (peerStats.rxBytes == 0L && peerStats.txBytes == 0L)) {
                    peer.transferLabel.visibility = View.GONE
                    peer.transferText.visibility = View.GONE
                } else {
                    peer.transferText.text = getString(
                        R.string.transfer_rx_tx,
                        QuantityFormatter.formatBytes(peerStats.rxBytes),
                        QuantityFormatter.formatBytes(peerStats.txBytes)
                    )
                    peer.transferLabel.visibility = View.VISIBLE
                    peer.transferText.visibility = View.VISIBLE
                }
                if (peerStats == null || peerStats.latestHandshakeEpochMillis == 0L) {
                    peer.latestHandshakeLabel.visibility = View.GONE
                    peer.latestHandshakeText.visibility = View.GONE
                } else {
                    peer.latestHandshakeText.text = QuantityFormatter.formatEpochAgo(peerStats.latestHandshakeEpochMillis)
                    peer.latestHandshakeLabel.visibility = View.VISIBLE
                    peer.latestHandshakeText.visibility = View.VISIBLE
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to update tunnel statistics", e)
            for (i in 0 until binding.peersLayout.childCount) {
                val peer: TunnelDetailPeerBinding = DataBindingUtil.getBinding(binding.peersLayout.getChildAt(i))
                    ?: continue
                peer.transferLabel.visibility = View.GONE
                peer.transferText.visibility = View.GONE
                peer.latestHandshakeLabel.visibility = View.GONE
                peer.latestHandshakeText.visibility = View.GONE
            }
        }
    }

    private fun refreshSelectedStats() {
        val tunnel = binding?.tunnel ?: return
        if (!isResumed || tunnel.state != Tunnel.State.UP) return
        lifecycleScope.launch {
            try {
                tunnel.getStatisticsAsync()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to refresh tunnel statistics", e)
            }
        }
    }

    private fun startStatsRefresh() {
        if (statsJob?.isActive == true) return
        statsJob = lifecycleScope.launch {
            while (true) {
                updateStats()
                delay(1000)
            }
        }
    }

    private fun stopStatsRefresh() {
        statsJob?.cancel()
        statsJob = null
    }
}
