/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import com.google.zxing.ReaderException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import android.content.Intent
import android.app.Activity
import android.content.res.Resources
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.databinding.Observable
import androidx.databinding.ObservableList
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.wireguard.android.Application
import com.wireguard.android.BR
import com.wireguard.android.R
import com.wireguard.android.activity.LogViewerActivity
import com.wireguard.android.activity.SettingsActivity
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.activity.TunnelCreatorActivity
import com.wireguard.android.databinding.ObservableKeyedRecyclerViewAdapter.RowConfigurationHandler
import com.wireguard.android.databinding.TunnelListFragmentBinding
import com.wireguard.android.databinding.TunnelListItemBinding
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.updater.SnackbarUpdateShower
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.util.QrCodeFromFileScanner
import com.wireguard.android.util.TunnelImporter
import com.wireguard.android.util.tunnlStyle
import com.wireguard.android.widget.MultiselectableRelativeLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Fragment containing a list of known WireGuard tunnels. It allows creating and deleting tunnels.
 */
class TunnelListFragment : BaseFragment() {
    private val tunnelCreatorResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val message = result.data?.getStringExtra(TunnelCreatorActivity.EXTRA_SAVE_MESSAGE) ?: return@registerForActivityResult
        binding?.let { Snackbar.make(it.mainContainer, message, Snackbar.LENGTH_SHORT).tunnlStyle().show() }
    }
    private val actionModeListener = ActionModeListener()
    private var actionMode: ActionMode? = null
    private var backPressedCallback: OnBackPressedCallback? = null
    private var binding: TunnelListFragmentBinding? = null
    private var bindingInvalidatePending = false
    private var observedTunnels: ObservableList<ObservableTunnel>? = null
    private var statsJob: Job? = null
    private val tunnelStateCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (propertyId == BR.state || propertyId == BR.config || propertyId == BR.statistics) {
                invalidateBindingSoon()
            }
            if (propertyId == BR.state && sender is ObservableTunnel && sender.state == Tunnel.State.UP) {
                refreshTunnelStats(sender)
            }
        }
    }
    private val tunnelListCallback = object : ObservableList.OnListChangedCallback<ObservableList<ObservableTunnel>>() {
        override fun onChanged(sender: ObservableList<ObservableTunnel>) {
            reconnectTunnelObservers(sender)
        }

        override fun onItemRangeChanged(sender: ObservableList<ObservableTunnel>, positionStart: Int, itemCount: Int) {
            reconnectTunnelObservers(sender)
        }

        override fun onItemRangeInserted(sender: ObservableList<ObservableTunnel>, positionStart: Int, itemCount: Int) {
            reconnectTunnelObservers(sender)
        }

        override fun onItemRangeMoved(sender: ObservableList<ObservableTunnel>, fromPosition: Int, toPosition: Int, itemCount: Int) {
            invalidateBindingSoon()
        }

        override fun onItemRangeRemoved(sender: ObservableList<ObservableTunnel>, positionStart: Int, itemCount: Int) {
            reconnectTunnelObservers(sender)
        }
    }
    private val tunnelFileImportResultLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { data ->
        if (data == null) return@registerForActivityResult
        val activity = activity ?: return@registerForActivityResult
        val contentResolver = activity.contentResolver ?: return@registerForActivityResult
        activity.lifecycleScope.launch {
            if (QrCodeFromFileScanner.validContentType(contentResolver, data)) {
                fun reportScanFailure(error: Exception) {
                    val message = activity.resources.getString(R.string.import_error, ErrorMessages[error])
                    Log.e(TAG, message, error)
                    showSnackbar(message)
                }
                val result = try {
                    val qrCodeFromFileScanner = QrCodeFromFileScanner(contentResolver, QRCodeReader())
                    qrCodeFromFileScanner.scan(data)
                } catch (e: IOException) {
                    reportScanFailure(e)
                    return@launch
                } catch (e: ReaderException) {
                    reportScanFailure(e)
                    return@launch
                } catch (e: IllegalArgumentException) {
                    reportScanFailure(e)
                    return@launch
                } catch (e: SecurityException) {
                    reportScanFailure(e)
                    return@launch
                }
                TunnelImporter.importTunnel(parentFragmentManager, result.text) { showSnackbar(it) }
            } else {
                TunnelImporter.importTunnel(contentResolver, data) { showSnackbar(it) }
            }
        }
    }

    private val qrImportResultLauncher = registerForActivityResult(ScanContract()) { result ->
        val qrCode = result.contents
        val activity = activity
        if (qrCode != null && activity != null) {
            activity.lifecycleScope.launch { TunnelImporter.importTunnel(parentFragmentManager, qrCode) { showSnackbar(it) } }
        }
    }

    private val snackbarUpdateShower = SnackbarUpdateShower(this)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState != null) {
            val checkedItems = savedInstanceState.getIntegerArrayList(CHECKED_ITEMS)
            if (checkedItems != null) {
                for (i in checkedItems) actionModeListener.setItemChecked(i, true)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        binding = TunnelListFragmentBinding.inflate(inflater, container, false)
        val bottomSheet = AddTunnelsSheet()
        binding?.apply {
            createFab.setOnClickListener {
                if (childFragmentManager.findFragmentByTag("BOTTOM_SHEET") != null)
                    return@setOnClickListener
                childFragmentManager.setFragmentResultListener(AddTunnelsSheet.REQUEST_KEY_NEW_TUNNEL, viewLifecycleOwner) { _, bundle ->
                    when (bundle.getString(AddTunnelsSheet.REQUEST_METHOD)) {
                        AddTunnelsSheet.REQUEST_CREATE -> {
                            tunnelCreatorResultLauncher.launch(Intent(requireActivity(), TunnelCreatorActivity::class.java))
                        }

                        AddTunnelsSheet.REQUEST_IMPORT -> {
                            tunnelFileImportResultLauncher.launch("*/*")
                        }

                        AddTunnelsSheet.REQUEST_SCAN -> {
                            qrImportResultLauncher.launch(
                                ScanOptions()
                                    .setOrientationLocked(false)
                                    .setBeepEnabled(false)
                                    .setPrompt(getString(R.string.qr_code_hint))
                            )
                        }
                    }
                }
                bottomSheet.showNow(childFragmentManager, "BOTTOM_SHEET")
            }
            executePendingBindings()
            snackbarUpdateShower.attach(mainContainer, createFab)
        }
        backPressedCallback = requireActivity().onBackPressedDispatcher.addCallback(this) { actionMode?.finish() }
        backPressedCallback?.isEnabled = false

        return binding?.root
    }

    override fun onResume() {
        super.onResume()
        startStatsRefresh()
    }

    override fun onStop() {
        stopStatsRefresh()
        super.onStop()
    }

    override fun onDestroyView() {
        disconnectTunnelObservers()
        binding = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putIntegerArrayList(CHECKED_ITEMS, actionModeListener.getCheckedItems())
    }

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
        binding ?: return
        lifecycleScope.launch {
            val tunnels = Application.getTunnelManager().getTunnels()
            if (newTunnel != null) viewForTunnel(newTunnel, tunnels)?.setSingleSelected(true)
            if (oldTunnel != null) viewForTunnel(oldTunnel, tunnels)?.setSingleSelected(false)
        }
    }

    private fun onTunnelDeletionFinished(count: Int, throwable: Throwable?) {
        val message: String
        val ctx = activity ?: Application.get()
        if (throwable == null) {
            message = ctx.resources.getQuantityString(R.plurals.delete_success, count, count)
        } else {
            val error = ErrorMessages[throwable]
            message = ctx.resources.getQuantityString(R.plurals.delete_error, count, count, error)
            Log.e(TAG, message, throwable)
        }
        showSnackbar(message)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        binding ?: return
        binding!!.fragment = this
        lifecycleScope.launch {
            val tunnels = Application.getTunnelManager().getTunnels()
            binding!!.tunnels = tunnels
            reconnectTunnelObservers(tunnels)
            binding?.invalidateAll()
        }
        binding!!.rowConfigurationHandler = object : RowConfigurationHandler<TunnelListItemBinding, ObservableTunnel> {
            override fun onConfigureRow(binding: TunnelListItemBinding, item: ObservableTunnel, position: Int) {
                binding.fragment = this@TunnelListFragment
                binding.root.setOnClickListener {
                    if (actionMode == null) {
                        selectedTunnel = item
                    } else {
                        actionModeListener.toggleItemChecked(position)
                    }
                }
                binding.root.setOnLongClickListener {
                    actionModeListener.toggleItemChecked(position)
                    true
                }
                if (actionMode != null)
                    (binding.root as MultiselectableRelativeLayout).setMultiSelected(actionModeListener.checkedItems.contains(position))
                else
                    (binding.root as MultiselectableRelativeLayout).setSingleSelected(selectedTunnel == item)
            }
        }
    }

    private fun reconnectTunnelObservers(tunnels: ObservableList<ObservableTunnel>) {
        val previous = observedTunnels
        if (previous != null) {
            previous.removeOnListChangedCallback(tunnelListCallback)
            for (tunnel in previous) {
                tunnel.removeOnPropertyChangedCallback(tunnelStateCallback)
            }
        }
        observedTunnels = tunnels
        tunnels.removeOnListChangedCallback(tunnelListCallback)
        tunnels.addOnListChangedCallback(tunnelListCallback)
        for (tunnel in tunnels) {
            tunnel.removeOnPropertyChangedCallback(tunnelStateCallback)
            tunnel.addOnPropertyChangedCallback(tunnelStateCallback)
            refreshTunnelStats(tunnel)
        }
        binding?.invalidateAll()
    }

    private fun refreshTunnelStats(tunnel: ObservableTunnel) {
        if (!isResumed || tunnel.state != Tunnel.State.UP) return
        lifecycleScope.launch {
            refreshTunnelStatsNow(tunnel)
        }
    }

    private fun invalidateBindingSoon() {
        val currentBinding = binding ?: return
        if (bindingInvalidatePending) return
        bindingInvalidatePending = true
        currentBinding.root.post {
            bindingInvalidatePending = false
            if (binding === currentBinding) currentBinding.invalidateAll()
        }
    }

    private fun startStatsRefresh() {
        if (statsJob?.isActive == true) return
        statsJob = lifecycleScope.launch {
            while (true) {
                val tunnels = observedTunnels?.toList().orEmpty()
                if (tunnels.none { it.state == Tunnel.State.TOGGLE }) {
                    for (tunnel in tunnels) {
                        refreshTunnelStatsNow(tunnel)
                    }
                }
                delay(TimeUnit.SECONDS.toMillis(1))
            }
        }
    }

    private fun stopStatsRefresh() {
        statsJob?.cancel()
        statsJob = null
    }

    // Backend/ConfigStore expose Exception; this operation reports their failures while cancellation and Errors propagate.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun refreshTunnelStatsNow(tunnel: ObservableTunnel) {
        if (!isResumed || tunnel.state != Tunnel.State.UP) return
        try {
            tunnel.getStatisticsAsync()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Stats refresh failed", e)
        }
    }

    private fun disconnectTunnelObservers() {
        observedTunnels?.let { tunnels ->
            tunnels.removeOnListChangedCallback(tunnelListCallback)
            for (tunnel in tunnels) {
                tunnel.removeOnPropertyChangedCallback(tunnelStateCallback)
            }
        }
        observedTunnels = null
    }

    private fun showSnackbar(message: CharSequence) {
        val binding = binding
        if (binding != null)
            Snackbar.make(binding.mainContainer, message, Snackbar.LENGTH_LONG)
                .setAnchorView(binding.createFab)
                .tunnlStyle()
                .show()
        else
            Toast.makeText(activity ?: Application.get(), message, Toast.LENGTH_SHORT).show()
    }

    fun onOpenLogs(@Suppress("UNUSED_PARAMETER") view: View) {
        startActivity(Intent(requireContext(), LogViewerActivity::class.java))
    }

    fun onOpenSettings(@Suppress("UNUSED_PARAMETER") view: View) {
        startActivity(Intent(requireContext(), SettingsActivity::class.java))
    }

    private fun viewForTunnel(tunnel: ObservableTunnel, tunnels: List<*>): MultiselectableRelativeLayout? {
        return binding?.tunnelList?.findViewHolderForAdapterPosition(tunnels.indexOf(tunnel))?.itemView as? MultiselectableRelativeLayout
    }

    private inner class ActionModeListener : ActionMode.Callback {
        val checkedItems: MutableCollection<Int> = HashSet()
        private var resources: Resources? = null

        fun getCheckedItems(): ArrayList<Int> {
            return ArrayList(checkedItems)
        }

        // Backend/ConfigStore expose Exception; this operation reports their failures while cancellation and Errors propagate.
        @Suppress("TooGenericExceptionCaught")
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.menu_action_delete -> {
                    val activity = activity ?: return true
                    val copyCheckedItems = HashSet(checkedItems)
                    binding?.createFab?.apply {
                        visibility = View.VISIBLE
                        scaleX = 1f
                        scaleY = 1f
                    }
                    activity.lifecycleScope.launch {
                        try {
                            val tunnels = Application.getTunnelManager().getTunnels()
                            val tunnelsToDelete = ArrayList<ObservableTunnel>()
                            for (position in copyCheckedItems) tunnelsToDelete.add(tunnels[position])
                            val deletedCount = supervisorScope {
                                tunnelsToDelete.map { async { it.deleteAsync() } }.awaitAll().size
                            }
                            onTunnelDeletionFinished(deletedCount, null)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            onTunnelDeletionFinished(0, e)
                        }
                    }
                    checkedItems.clear()
                    mode.finish()
                    true
                }

                R.id.menu_action_select_all -> {
                    lifecycleScope.launch {
                        val tunnels = Application.getTunnelManager().getTunnels()
                        for (i in 0 until tunnels.size) {
                            setItemChecked(i, true)
                        }
                    }
                    true
                }

                else -> false
            }
        }

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            actionMode = mode
            backPressedCallback?.isEnabled = true
            if (activity != null) {
                resources = activity!!.resources
            }
            animateFab(binding?.createFab, false)
            mode.menuInflater.inflate(R.menu.tunnel_list_action_mode, menu)
            binding?.tunnelList?.adapter?.let { it.notifyItemRangeChanged(0, it.itemCount) }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            backPressedCallback?.isEnabled = false
            resources = null
            animateFab(binding?.createFab, true)
            checkedItems.clear()
            binding?.tunnelList?.adapter?.let { it.notifyItemRangeChanged(0, it.itemCount) }
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            updateTitle(mode)
            return false
        }

        fun setItemChecked(position: Int, checked: Boolean) {
            if (checked) {
                checkedItems.add(position)
            } else {
                checkedItems.remove(position)
            }
            val adapter = if (binding == null) null else binding!!.tunnelList.adapter
            if (actionMode == null && !checkedItems.isEmpty() && activity != null) {
                (activity as AppCompatActivity).startSupportActionMode(this)
            } else if (actionMode != null && checkedItems.isEmpty()) {
                actionMode!!.finish()
            }
            adapter?.notifyItemChanged(position)
            updateTitle(actionMode)
        }

        fun toggleItemChecked(position: Int) {
            setItemChecked(position, !checkedItems.contains(position))
        }

        private fun updateTitle(mode: ActionMode?) {
            if (mode == null) {
                return
            }
            val count = checkedItems.size
            if (count == 0) {
                mode.title = ""
            } else {
                mode.title = resources?.getQuantityString(R.plurals.delete_title, count, count) ?: return
            }
        }

        private fun animateFab(view: View?, show: Boolean) {
            view ?: return
            val animation = AnimationUtils.loadAnimation(
                context, if (show) R.anim.scale_up else R.anim.scale_down
            )
            animation.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationRepeat(animation: Animation?) = Unit

                override fun onAnimationEnd(animation: Animation?) {
                    if (!show) view.visibility = View.GONE
                }

                override fun onAnimationStart(animation: Animation?) {
                    if (show) view.visibility = View.VISIBLE
                }
            })
            view.startAnimation(animation)
        }
    }

    companion object {
        private const val CHECKED_ITEMS = "CHECKED_ITEMS"
        private const val TAG = "WireGuard/TunnelListFragment"
    }
}
