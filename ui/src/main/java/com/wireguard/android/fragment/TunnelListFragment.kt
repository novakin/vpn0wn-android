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
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.databinding.Observable
import androidx.databinding.ObservableList
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.client.android.Intents
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
import com.wireguard.android.model.TunnelManager
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.util.QrCodeFromFileScanner
import com.wireguard.android.util.TunnelImporter
import com.wireguard.android.util.tunnlStyle
import com.wireguard.android.widget.MultiselectableRelativeLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
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
    private val selectedTunnels = linkedSetOf<ObservableTunnel>()
    private var selectionMode = false
    private var deleteSheet: BottomSheetDialog? = null
    private var deleteSnapshot = emptyList<ObservableTunnel>()
    private var deleteJob: Job? = null
    private var deleting = false
    private var backPressedCallback: OnBackPressedCallback? = null
    private var binding: TunnelListFragmentBinding? = null
    private var bindingInvalidatePending = false
    private var selectionReconcilePending = false
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
            if (propertyId == BR.state || propertyId == BR.name) refreshDeleteWarning()
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
            reconcileSelection(sender)
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        backPressedCallback = requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (deleting) deleteJob?.cancel()
            else if (deleteSheet != null) deleteSheet?.dismiss()
            else exitSelectionMode()
        }
        backPressedCallback?.isEnabled = selectionMode
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
                                    .addExtra(Intents.Scan.SHOW_MISSING_CAMERA_PERMISSION_DIALOG, true)
                                    .addExtra(Intents.Scan.MISSING_CAMERA_PERMISSION_DIALOG_MESSAGE, getString(R.string.qr_camera_permission_denied))
                            )
                        }
                    }
                }
                bottomSheet.showNow(childFragmentManager, "BOTTOM_SHEET")
            }
            executePendingBindings()
        }
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
        deleteSheet?.dismiss()
        disconnectTunnelObservers()
        backPressedCallback = null
        binding = null
        bindingInvalidatePending = false
        selectionReconcilePending = false
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(SELECTION_MODE, selectionMode)
        outState.putStringArrayList(SELECTED_NAMES, ArrayList(selectedTunnels.map { it.name }))
    }

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
        val currentBinding = binding ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val tunnels = Application.getTunnelManager().getTunnels()
            if (binding !== currentBinding || selectionMode) return@launch
            if (newTunnel != null) viewForTunnel(newTunnel, tunnels)?.setSingleSelected(true)
            if (oldTunnel != null) viewForTunnel(oldTunnel, tunnels)?.setSingleSelected(false)
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        val currentBinding = binding ?: return
        currentBinding.fragment = this
        selectionMode = savedInstanceState?.getBoolean(SELECTION_MODE) ?: selectionMode
        val restoredNames = savedInstanceState?.getStringArrayList(SELECTED_NAMES)
        viewLifecycleOwner.lifecycleScope.launch {
            val tunnels = Application.getTunnelManager().getTunnels()
            if (binding !== currentBinding) return@launch
            if (restoredNames != null) {
                selectedTunnels.clear()
                selectedTunnels.addAll(tunnels.filter { it.name in restoredNames })
            }
            currentBinding.tunnels = tunnels
            reconnectTunnelObservers(tunnels)
        }
        currentBinding.rowConfigurationHandler = object : RowConfigurationHandler<TunnelListItemBinding, ObservableTunnel> {
            override fun onConfigureRow(binding: TunnelListItemBinding, item: ObservableTunnel, position: Int) {
                binding.fragment = this@TunnelListFragment
                binding.selectionMode = selectionMode
                binding.selected = item in selectedTunnels
                binding.root.setOnClickListener {
                    if (!selectionMode) {
                        selectedTunnel = item
                    } else {
                        toggleItemSelection(item)
                    }
                }
                binding.root.setOnLongClickListener {
                    toggleItemSelection(item)
                    true
                }
                if (selectionMode)
                    (binding.root as MultiselectableRelativeLayout).setMultiSelected(item in selectedTunnels)
                else
                    (binding.root as MultiselectableRelativeLayout).setSingleSelected(selectedTunnel == item)
                binding.executePendingBindings()
            }
        }
        updateSelectionUi()
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
        reconcileSelection(tunnels)
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
            if (binding !== currentBinding) return@post
            bindingInvalidatePending = false
            if (selectionReconcilePending) {
                selectionReconcilePending = false
                selectedTunnels.retainAll(observedTunnels.orEmpty().toSet())
                updateSelectionUi()
            }
            currentBinding.invalidateAll()
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
                .setAnchorView(if (binding.createFab.visibility == View.VISIBLE) binding.createFab else null)
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

    private fun toggleItemSelection(tunnel: ObservableTunnel) {
        if (deleting || observedTunnels?.contains(tunnel) != true) return
        selectionMode = true
        if (tunnel in selectedTunnels) selectedTunnels.remove(tunnel) else selectedTunnels.add(tunnel)
        updateSelectionUi()
    }

    private fun exitSelectionMode() {
        if (deleting) return
        deleteSheet?.dismiss()
        selectionMode = false
        selectedTunnels.clear()
        updateSelectionUi()
    }

    fun onExitSelection(@Suppress("UNUSED_PARAMETER") view: View) = exitSelectionMode()

    fun onSelectAll(@Suppress("UNUSED_PARAMETER") view: View) {
        if (!selectionMode || deleting) return
        selectedTunnels.addAll(observedTunnels.orEmpty())
        updateSelectionUi()
    }

    fun onDeleteSelection(@Suppress("UNUSED_PARAMETER") view: View) {
        if (deleting || selectedTunnels.isEmpty() || deleteSheet != null) return
        val snapshot = selectedTunnels.filter { it in observedTunnels.orEmpty() }
        if (snapshot.isEmpty()) return
        val dialog = BottomSheetDialog(requireContext())
        val sheet = layoutInflater.inflate(R.layout.tunnel_delete_bottom_sheet, null)
        deleteSnapshot = snapshot
        sheet.findViewById<TextView>(R.id.delete_sheet_title).text = resources.getQuantityString(
            R.plurals.tunnl_delete_sheet_title, deleteSnapshot.size, deleteSnapshot.size)
        sheet.findViewById<TextView>(R.id.delete_sheet_names).text = deleteSnapshot.joinToString("\n") { it.name }
        sheet.findViewById<View>(R.id.delete_sheet_cancel).setOnClickListener { dialog.dismiss() }
        sheet.findViewById<View>(R.id.delete_sheet_confirm).setOnClickListener {
            if (deleteSheet !== dialog || !dialog.isShowing) return@setOnClickListener
            dialog.dismiss()
            deleteSelectedTunnels(snapshot)
        }
        dialog.setContentView(sheet)
        dialog.setOnDismissListener {
            if (deleteSheet === dialog) {
                deleteSheet = null
                deleteSnapshot = emptyList()
            }
        }
        deleteSheet = dialog
        dialog.show()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        refreshDeleteWarning()
    }

    private fun refreshDeleteWarning() {
        val dialog = deleteSheet ?: return
        dialog.findViewById<TextView>(R.id.delete_sheet_names)?.text = deleteSnapshot.joinToString("\n") { it.name }
        val active = deleteSnapshot.filter { it.state != Tunnel.State.DOWN }
        dialog.findViewById<TextView>(R.id.delete_sheet_warning)?.apply {
            visibility = if (active.isEmpty()) View.GONE else View.VISIBLE
            text = getString(R.string.tunnl_delete_stop_warning) + "\n" + active.joinToString("\n") { it.name }
        }
    }

    private fun reconcileSelection(tunnels: List<ObservableTunnel>) {
        if (deleteSnapshot.any { it !in tunnels }) deleteSheet?.dismiss()
        refreshDeleteWarning()
        selectionReconcilePending = true
        invalidateBindingSoon()
    }

    private fun updateSelectionUi() {
        backPressedCallback?.isEnabled = selectionMode
        val currentBinding = binding ?: return
        val selectionChanged = (currentBinding.selectionHeaderContainer.visibility == View.VISIBLE) != selectionMode
        currentBinding.homeHeaderContainer.visibility = if (selectionMode) View.GONE else View.VISIBLE
        currentBinding.homeStatusContainer.visibility = if (selectionMode) View.GONE else View.VISIBLE
        currentBinding.selectionHeaderContainer.visibility = if (selectionMode) View.VISIBLE else View.GONE
        currentBinding.selectionStatusContainer.visibility = if (selectionMode) View.VISIBLE else View.GONE
        currentBinding.createFab.visibility = if (selectionMode) View.GONE else View.VISIBLE
        if (selectionChanged) currentBinding.homeAppBar.setExpanded(true, false)
        currentBinding.selectionTitle.text = resources.getQuantityString(R.plurals.tunnl_selection_title, selectedTunnels.size, selectedTunnels.size)
        currentBinding.selectionStatus.text = if (deleting) getString(R.string.tunnl_delete_in_progress)
            else if (selectedTunnels.isEmpty()) getString(R.string.tunnl_selection_status_empty)
            else getString(R.string.tunnl_selection_status_count, selectedTunnels.size, observedTunnels?.size ?: 0)
        currentBinding.selectionDelete.isEnabled = selectedTunnels.isNotEmpty() && !deleting
        currentBinding.selectionDelete.visibility = if (selectedTunnels.isEmpty()) View.INVISIBLE else View.VISIBLE
        currentBinding.selectionClose.isEnabled = !deleting
        currentBinding.selectionAll.isEnabled = !deleting && !observedTunnels.isNullOrEmpty()
        currentBinding.tunnelList.adapter?.let { it.notifyItemRangeChanged(0, it.itemCount) }
    }

    // Backend/ConfigStore expose Exception; report each failed delete without abandoning successful siblings.
    @Suppress("TooGenericExceptionCaught")
    private fun deleteSelectedTunnels(snapshot: List<ObservableTunnel>) {
        if (deleting || snapshot.isEmpty()) return
        val inventory = observedTunnels ?: return
        val currentBinding = binding ?: return
        deleting = true
        updateSelectionUi()
        deleteJob = viewLifecycleOwner.lifecycleScope.launch {
            var failure: Exception? = null
            var completed = false
            fun recordFailure(error: Exception) {
                if (failure == null) failure = error
                Log.e(TAG, "Tunnel deletion failed", error)
            }
            try {
                for (tunnel in snapshot) {
                    currentCoroutineContext().ensureActive()
                    try {
                        tunnel.deleteAsync()
                    } catch (e: SecurityException) {
                        recordFailure(e)
                    } catch (e: TunnelManager.NotManagedException) {
                        recordFailure(e)
                    } catch (e: RuntimeException) {
                        throw e
                    } catch (e: Exception) {
                        recordFailure(e)
                    }
                }
                completed = true
            } catch (e: CancellationException) {
                failure = e
                throw e
            } finally {
                deleting = false
                deleteJob = null
                reconcileSelection(inventory)
                if (binding === currentBinding && (completed || failure != null)) {
                    val remaining = snapshot.count { it in inventory }
                    val removed = snapshot.size - remaining
                    if (failure == null && remaining == 0) {
                        exitSelectionMode()
                        showSnackbar(resources.getQuantityString(R.plurals.delete_success, removed, removed))
                    } else {
                        val reason = if (failure is CancellationException) getString(R.string.tunnl_delete_cancelled)
                            else failure?.let { ErrorMessages[it] }.orEmpty()
                        showSnackbar(getString(R.string.tunnl_delete_progress, removed, snapshot.size, remaining, reason))
                    }
                }
            }
        }
        val job = deleteJob ?: return
        job.invokeOnCompletion {
            if (deleteJob === job) {
                deleteJob = null
                deleting = false
                updateSelectionUi()
            }
        }
    }

    companion object {
        private const val SELECTION_MODE = "SELECTION_MODE"
        private const val SELECTED_NAMES = "SELECTED_NAMES"
        private const val TAG = "WireGuard/TunnelListFragment"
    }
}
