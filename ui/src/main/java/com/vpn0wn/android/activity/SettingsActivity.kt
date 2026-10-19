/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Modified for vpn0wn.
 */
package com.vpn0wn.android.activity

import com.vpn0wn.android.util.RootShell.RootShellException
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.app.Activity
import android.net.VpnService
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.vpn0wn.android.Application
import com.vpn0wn.android.R
import com.vpn0wn.android.NetworkMonitorService
import com.vpn0wn.android.backend.GoBackend
import com.vpn0wn.android.model.NetworkAutomationController
import com.vpn0wn.android.backend.Backend
import com.vpn0wn.android.backend.WgQuickBackend
import com.vpn0wn.android.preference.PreferencesPreferenceDataStore
import com.vpn0wn.android.provisioning.VpsServerStore
import com.vpn0wn.android.util.AdminKnobs
import com.vpn0wn.android.util.AppDataTransfer
import com.vpn0wn.android.util.applySafeAreaInsets
import com.vpn0wn.android.util.vpn0wnStyle
import com.vpn0wn.android.util.UserKnobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Interface for changing application-global persistent settings.
 */
class SettingsActivity : AppCompatActivity() {
    private var importedProfileCount: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        supportActionBar?.hide()
        findViewById<View>(R.id.settings_root).applySafeAreaInsets()
        findViewById<View>(R.id.settings_back).setOnClickListener { finish() }
        if (supportFragmentManager.findFragmentById(R.id.settings_content) == null) {
            supportFragmentManager.commit {
                add(R.id.settings_content, SettingsFragment())
            }
        }
        val importedCount = savedInstanceState?.getInt(APP_DATA_IMPORT_COUNT, -1) ?: -1
        if (importedCount >= 0) {
            findViewById<View>(android.R.id.content).post {
                if (isFinishing || isDestroyed) return@post
                Snackbar.make(
                    findViewById(android.R.id.content),
                    getString(R.string.app_data_transfer_import_success, importedCount),
                    Snackbar.LENGTH_LONG,
                ).vpn0wnStyle().show()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        importedProfileCount?.let { outState.putInt(APP_DATA_IMPORT_COUNT, it) }
        super.onSaveInstanceState(outState)
    }

    private fun reloadAfterAppDataImport(count: Int) {
        importedProfileCount = count
        recreate()
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private var automationPending = false
        private val automationNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            startAutomation(NetworkMonitorService.ENABLE)
        }
        private val automationConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) enableAutomationAfterConsent() else {
                automationPending = false
                showAutomationFailure()
            }
        }
        private var vpnPolicyJob: Job? = null
        private var transferInProgress = false
        private var importDialog: AlertDialog? = null
        private val appDataPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val ownerView = view
            if (uri != null && ownerView != null) readAppData(uri, ownerView)
        }

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            val view = super.onCreateView(inflater, container, savedInstanceState)
            view.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.vpn0wn_paper))
            return view
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            setTransferEnabled(true)
            bindAutomation()
            bindMultipleTunnels()
            val quickTile = findPreference<Preference>("quick_tile") ?: return
            val owner = viewLifecycleOwner
            quickTile.isVisible = false
            owner.lifecycleScope.launch {
                owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    quickTile.isVisible = false
                    try {
                        UserKnobs.quickTileAdded.collect { added -> quickTile.isVisible = added != true }
                    } catch (_: IOException) {
                        quickTile.isVisible = false
                        Log.w("vpn0wn/Settings", "Unable to load quick tile membership")
                    }
                }
            }
        }

        override fun onDestroyView() {
            val dialog = importDialog
            importDialog = null
            dialog?.dismiss()
            transferInProgress = false
            setTransferEnabled(true)
            super.onDestroyView()
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, key: String?) {
            preferenceManager.preferenceDataStore = PreferencesPreferenceDataStore(lifecycleScope, Application.getPreferencesDataStore())
            addPreferencesFromResource(R.xml.preferences)
            findPreference<Preference>("automation_default")?.setOnPreferenceClickListener {
                chooseAutomationDefault()
                true
            }
            findPreference<Preference>("automation_enable")?.setOnPreferenceClickListener {
                if (!automationPending) {
                    automationPending = true
                    val consent = VpnService.prepare(requireContext())
                    if (consent == null) enableAutomationAfterConsent() else automationConsent.launch(consent)
                }
                true
            }
            findPreference<Preference>("automation_resume")?.setOnPreferenceClickListener {
                startAutomation(NetworkMonitorService.RESUME)
                true
            }
            findPreference<Preference>("automation_disable")?.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.automation_disable)
                    .setMessage(R.string.automation_disable_confirmation)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.automation_disable_confirm_action) { _, _ -> startAutomation(NetworkMonitorService.DISABLE) }
                    .show()
                true
            }
            // AndroidX defaults row IDs to 0, which Android resolves as a resource during structure capture.
            clearPreferenceViewIds(preferenceScreen)

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val quickTile = preferenceManager.findPreference<Preference>("quick_tile")
                quickTile?.parent?.removePreference(quickTile)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val darkTheme = preferenceManager.findPreference<Preference>("dark_theme")
                darkTheme?.parent?.removePreference(darkTheme)
            }
            if (AdminKnobs.disableConfigExport) {
                for (exportKey in listOf("zip_exporter", "export_app_data")) {
                    val exporter = preferenceManager.findPreference<Preference>(exportKey)
                    exporter?.parent?.removePreference(exporter)
                }
            }
            findPreference<Preference>("import_app_data")?.setOnPreferenceClickListener {
                if (transferInProgress || importDialog != null || !it.isEnabled ||
                    findPreference<Preference>("export_app_data")?.isEnabled == false
                ) return@setOnPreferenceClickListener true
                try {
                    appDataPicker.launch(arrayOf("application/zip"))
                } catch (_: ActivityNotFoundException) {
                    showTransferFailure()
                } catch (_: SecurityException) {
                    showTransferFailure()
                }
                true
            }
            val wgQuickOnlyPrefs = arrayOf(
                preferenceManager.findPreference("tools_installer"),
                preferenceManager.findPreference<Preference>("restore_on_boot")
            ).filterNotNull()
            wgQuickOnlyPrefs.forEach { it.isVisible = false }
            preferenceManager.findPreference<Preference>("log_viewer")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), LogViewerActivity::class.java))
                true
            }
            preferenceManager.findPreference<Preference>("licenses")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), LicensesActivity::class.java))
                true
            }
            preferenceManager.findPreference<Preference>("system_vpn_settings")?.setOnPreferenceClickListener {
                openVpnSettings()
                true
            }
            val kernelModuleEnabler = preferenceManager.findPreference<Preference>("kernel_module_enabler")
            val connection = findPreference<PreferenceCategory>("settings_connection")
            connection?.isVisible = false
            removeEmptyCategories()
            lifecycleScope.launch {
                val hasKernelSupport = withContext(Dispatchers.IO) { WgQuickBackend.hasKernelSupport() }
                if (!hasKernelSupport) {
                    kernelModuleEnabler?.parent?.removePreference(kernelModuleEnabler)
                }
                if (Application.getBackend() is WgQuickBackend) {
                    wgQuickOnlyPrefs.forEach { it.isVisible = true }
                } else {
                    wgQuickOnlyPrefs.forEach { it.parent?.removePreference(it) }
                    if (hasKernelSupport) {
                        try {
                            withContext(Dispatchers.IO) { Application.getRootShell().start() }
                        } catch (_: IOException) {
                            kernelModuleEnabler?.parent?.removePreference(kernelModuleEnabler)
                        } catch (_: RootShellException) {
                            kernelModuleEnabler?.parent?.removePreference(kernelModuleEnabler)
                        }
                    }
                }
                connection?.isVisible = true
                removeEmptyCategories()
            }
        }

        override fun onResume() {
            super.onResume()
            refreshVpnPolicy()
            val owner = viewLifecycleOwner
            val context = requireContext().applicationContext
            owner.lifecycleScope.launch {
                // The view's resume event follows this callback, including with Main.immediate.
                owner.lifecycle.withResumed { }
                if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                try {
                    NetworkMonitorService.startIfEnabled(context)
                } catch (_: IOException) {
                    Application.getTunnelManager().networkAutomation.startFailed()
                }
            }
        }

        private fun bindMultipleTunnels() {
            val preference = findPreference<SwitchPreferenceCompat>("multiple_tunnels") ?: return
            val owner = viewLifecycleOwner
            var available = false
            preference.isPersistent = false
            preference.isEnabled = false
            preference.isVisible = false
            owner.lifecycleScope.launch {
                owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    available = false
                    preference.isEnabled = false
                    try {
                        val backend = Application.getBackend()
                        val kernel = backend is WgQuickBackend
                        if (!kernel && backend !is GoBackend) {
                            preference.parent?.removePreference(preference)
                            return@repeatOnLifecycle
                        }
                        preference.setTitle(if (kernel) R.string.multiple_tunnels_title else R.string.multiple_tunnels_saved_title)
                        preference.setSummaryOn(if (kernel) R.string.multiple_tunnels_summary_on else R.string.multiple_tunnels_saved_summary)
                        preference.setSummaryOff(if (kernel) R.string.multiple_tunnels_summary_off else R.string.multiple_tunnels_saved_summary)
                        preference.setOnPreferenceChangeListener { _, value ->
                            if (value == true && !kernel) return@setOnPreferenceChangeListener false
                            owner.lifecycleScope.launch {
                                preference.isEnabled = false
                                try {
                                    Application.getTunnelManager().setMultipleTunnels(value as Boolean)
                                } catch (_: IOException) {
                                    showAutomationFailure()
                                } finally {
                                    preference.isEnabled = available
                                }
                            }
                            false
                        }
                        UserKnobs.multipleTunnels.collect {
                            available = true
                            preference.isChecked = it
                            preference.isVisible = kernel || it
                            preference.isEnabled = true
                        }
                    } catch (_: IOException) {
                        available = false
                        preference.isEnabled = false
                    }
                }
            }
        }

        private fun bindAutomation() {
            val controller = Application.getTunnelManager().networkAutomation
            val owner = viewLifecycleOwner
            owner.lifecycleScope.launch {
                try {
                    controller.refresh()
                } catch (_: IOException) {
                    controller.startFailed()
                }
                owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    try {
                        val backend = Application.getBackend()
                        controller.status.combine(UserKnobs.multipleTunnels) { status, multiple ->
                            status to (backend is GoBackend && !multiple)
                        }.collect { (status, supported) ->
                            automationPending = false
                            val settling = status.settling
                            findPreference<Preference>("automation_default")?.apply {
                                summary = status.defaultName ?: getString(R.string.automation_choose_default)
                                isEnabled = !settling
                            }
                            findPreference<Preference>("automation_status")?.summary = getString(
                                if (!status.enabled && status.exit == com.vpn0wn.android.model.NetworkAutomationState.Exit.NONE && !supported)
                                    R.string.automation_unsupported else automationSummary(status))
                            findPreference<Preference>("automation_enable")?.apply {
                                isVisible = !status.enabled
                                isEnabled = supported && status.defaultName != null && !settling
                            }
                            findPreference<Preference>("automation_resume")?.apply {
                                isVisible = status.enabled
                                isEnabled = !settling
                            }
                            findPreference<Preference>("automation_disable")?.apply {
                                isVisible = status.enabled || status.exit == com.vpn0wn.android.model.NetworkAutomationState.Exit.PENDING
                                isEnabled = !settling
                            }
                        }
                    } catch (_: IOException) {
                        controller.startFailed()
                        findPreference<Preference>("automation_status")?.summary = getString(R.string.automation_action_failed)
                        for (key in listOf("automation_default", "automation_enable", "automation_resume", "automation_disable")) {
                            findPreference<Preference>(key)?.isEnabled = false
                        }
                    }
                }
            }
        }

        private fun automationSummary(status: NetworkAutomationController.Status): Int = when (status.condition) {
            NetworkAutomationController.Condition.DISABLED -> R.string.automation_status_disabled
            NetworkAutomationController.Condition.INACTIVE -> R.string.automation_status_inactive
            NetworkAutomationController.Condition.MONITORING -> R.string.automation_status_monitoring
            NetworkAutomationController.Condition.WAITING_NETWORK -> R.string.automation_status_waiting
            NetworkAutomationController.Condition.OWNED -> R.string.automation_status_owned
            NetworkAutomationController.Condition.MANUAL_HOLD -> R.string.automation_status_manual
            NetworkAutomationController.Condition.PAUSED -> R.string.automation_status_paused
            NetworkAutomationController.Condition.UNRESOLVED -> R.string.automation_status_unresolved
            NetworkAutomationController.Condition.EXIT_PENDING -> R.string.automation_status_exit_pending
            NetworkAutomationController.Condition.EXIT_RETAINED_PIN -> R.string.automation_status_exit_pin
            NetworkAutomationController.Condition.EXIT_NO_TRANSPORT -> R.string.automation_status_exit_unavailable
            NetworkAutomationController.Condition.EXIT_UNKNOWN -> R.string.automation_status_exit_unknown
            NetworkAutomationController.Condition.ANDROID_HANDLING -> R.string.automation_status_android
            NetworkAutomationController.Condition.FAILED -> when {
                status.enabled -> R.string.automation_status_failed
                status.exit != com.vpn0wn.android.model.NetworkAutomationState.Exit.NONE -> R.string.automation_status_exit_failed
                else -> R.string.automation_action_failed
            }
        }

        private fun chooseAutomationDefault() {
            if (automationPending) return
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val tunnels = Application.getTunnelManager().getTunnels().toList()
                    if (tunnels.isEmpty()) {
                        showAutomationFailure()
                        return@launch
                    }
                    val names = tunnels.map { it.name }.toTypedArray()
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.automation_default)
                        .setItems(names) { _, index ->
                            viewLifecycleOwner.lifecycleScope.launch {
                                try {
                                    Application.getTunnelManager().networkAutomation.selectDefault(names[index])
                                } catch (_: IOException) {
                                    showAutomationFailure()
                                }
                            }
                        }.setNegativeButton(R.string.cancel, null).show()
                } catch (_: IOException) {
                    showAutomationFailure()
                }
            }
        }

        private fun enableAutomationAfterConsent() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                automationNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else startAutomation(NetworkMonitorService.ENABLE)
        }

        private fun startAutomation(action: String) {
            automationPending = true
            if (!NetworkMonitorService.start(requireContext(), action)) {
                automationPending = false
                showAutomationFailure()
            }
        }

        private fun showAutomationFailure() {
            view?.let { Snackbar.make(it, R.string.automation_action_failed, Snackbar.LENGTH_LONG).vpn0wnStyle().show() }
        }

        override fun onPause() {
            vpnPolicyJob?.cancel()
            vpnPolicyJob = null
            super.onPause()
        }

        private fun refreshVpnPolicy() {
            val preference = findPreference<Preference>("system_vpn_settings") ?: return
            vpnPolicyJob?.cancel()
            preference.isEnabled = false
            preference.summary = getString(R.string.system_vpn_settings_checking)
            vpnPolicyJob = viewLifecycleOwner.lifecycleScope.launch {
                val backend = Application.getBackend()
                val summary = getString(readVpnPolicySummary(backend))
                val restricted = isVpnSettingsRestricted()
                preference.summary = when {
                    backend is WgQuickBackend -> summary
                    restricted -> getString(R.string.system_vpn_settings_restricted_summary, summary)
                    else -> getString(R.string.system_vpn_settings_setup_summary, summary)
                }
                preference.isEnabled = backend !is WgQuickBackend && !restricted
            }
        }

        // Future acquisition failures need distinct UI categories; interruption and wrapped defects must still propagate.
        @Suppress("ThrowsCount", "SwallowedException")
        private suspend fun readVpnPolicySummary(backend: Backend): Int {
            if (backend is WgQuickBackend) return R.string.system_vpn_settings_kernel_unsupported
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return R.string.system_vpn_settings_api_unavailable
            return withContext(Dispatchers.IO) {
                try {
                    val alwaysOn = backend.isAlwaysOn
                    val lockdown = backend.isLockdownEnabled
                    when {
                        lockdown && !alwaysOn -> R.string.system_vpn_settings_unknown
                        lockdown -> R.string.system_vpn_settings_both_enabled
                        alwaysOn -> R.string.system_vpn_settings_always_on_enabled
                        else -> R.string.system_vpn_settings_both_disabled
                    }
                } catch (_: TimeoutException) {
                    R.string.system_vpn_settings_service_unavailable
                } catch (e: ExecutionException) {
                    when (val cause = e.cause) {
                        is SecurityException -> R.string.system_vpn_settings_read_denied
                        is InterruptedException -> throw CancellationException("VPN policy read interrupted").apply { initCause(cause) }
                        is RuntimeException -> throw cause
                        is Error -> throw cause
                        else -> R.string.system_vpn_settings_read_unavailable
                    }
                } catch (_: SecurityException) {
                    R.string.system_vpn_settings_read_denied
                } catch (e: InterruptedException) {
                    throw CancellationException("VPN policy read interrupted").apply { initCause(e) }
                }
            }
        }

        private fun isVpnSettingsRestricted() =
            requireNotNull(requireContext().getSystemService(UserManager::class.java)).hasUserRestriction(UserManager.DISALLOW_CONFIG_VPN)

        private fun openVpnSettings() {
            if (isVpnSettingsRestricted()) {
                refreshVpnPolicy()
                showVpnSettingsFailure(R.string.system_vpn_settings_admin_restricted)
                return
            }
            try {
                startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                showVpnSettingsFailure(R.string.system_vpn_settings_activity_unavailable)
            } catch (_: SecurityException) {
                showVpnSettingsFailure(R.string.system_vpn_settings_launch_denied)
            }
        }

        private fun showVpnSettingsFailure(message: Int) {
            Snackbar.make(requireView(), message, Snackbar.LENGTH_SHORT).vpn0wnStyle().show()
        }

        private fun readAppData(uri: Uri, ownerView: View) {
            if (transferInProgress || importDialog != null ||
                findPreference<Preference>("export_app_data")?.isEnabled == false
            ) return
            val resolver = requireContext().contentResolver
            transferInProgress = true
            setTransferEnabled(false)
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val archive = withContext(Dispatchers.IO) {
                        resolver.openInputStream(uri)?.use(AppDataTransfer::read)
                            ?: throw IOException("App transfer input unavailable")
                    }
                    if (view === ownerView) confirmAppDataImport(archive, ownerView)
                } catch (_: IOException) {
                    showTransferFailureIfCurrent(ownerView)
                } catch (_: AppDataTransfer.InvalidArchiveException) {
                    showTransferFailureIfCurrent(ownerView)
                } catch (_: VpsServerStore.InvalidTransferMetadataException) {
                    showTransferFailureIfCurrent(ownerView)
                } catch (_: SecurityException) {
                    showTransferFailureIfCurrent(ownerView)
                } finally {
                    if (view === ownerView) {
                        transferInProgress = false
                        setTransferEnabled(true)
                    }
                }
            }
        }

        private fun confirmAppDataImport(archive: AppDataTransfer.Archive, ownerView: View) {
            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.app_data_transfer_confirm_title)
                .setMessage(getString(R.string.app_data_transfer_confirm_message, archive.profiles.size, archive.servers.size))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create()
            dialog.setOnDismissListener {
                if (importDialog === dialog) importDialog = null
            }
            importDialog = dialog
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (importDialog !== dialog || !dialog.isShowing || view !== ownerView || transferInProgress) return@setOnClickListener
                if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@setOnClickListener
                dialog.dismiss()
                importAppData(archive, ownerView)
            }
        }

        // ConfigStore may throw checked Exception; cancellation and unexpected runtime defects must propagate.
        @Suppress("TooGenericExceptionCaught")
        private fun importAppData(archive: AppDataTransfer.Archive, ownerView: View) {
            transferInProgress = true
            setTransferEnabled(false)
            viewLifecycleOwner.lifecycleScope.launch {
                val created = try {
                    Application.getTunnelManager().importAppData(archive)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: AppDataTransfer.IncompleteRecoveryException) {
                    showTransferFailureIfCurrent(ownerView, incompleteRecovery = true)
                    return@launch
                } catch (_: IOException) {
                    showTransferFailureIfCurrent(ownerView)
                    return@launch
                } catch (_: AppDataTransfer.InvalidArchiveException) {
                    showTransferFailureIfCurrent(ownerView)
                    return@launch
                } catch (_: AppDataTransfer.ConflictException) {
                    showTransferFailureIfCurrent(ownerView)
                    return@launch
                } catch (_: VpsServerStore.InvalidTransferMetadataException) {
                    showTransferFailureIfCurrent(ownerView)
                    return@launch
                } catch (_: SecurityException) {
                    showTransferFailureIfCurrent(ownerView)
                    return@launch
                } catch (failure: RuntimeException) {
                    throw failure
                } catch (_: Exception) {
                    showTransferFailureIfCurrent(ownerView)
                    return@launch
                } finally {
                    if (view === ownerView) {
                        transferInProgress = false
                        setTransferEnabled(true)
                    }
                }
                currentCoroutineContext().ensureActive()
                if (view === ownerView) (requireActivity() as SettingsActivity).reloadAfterAppDataImport(created)
            }
        }

        private fun setTransferEnabled(enabled: Boolean) {
            findPreference<Preference>("export_app_data")?.isEnabled = enabled
            findPreference<Preference>("import_app_data")?.isEnabled = enabled
        }

        private suspend fun showTransferFailureIfCurrent(ownerView: View, incompleteRecovery: Boolean = false) {
            currentCoroutineContext().ensureActive()
            if (view === ownerView && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                showTransferFailure(incompleteRecovery)
            }
        }

        private fun showTransferFailure(incompleteRecovery: Boolean = false) {
            val message = if (incompleteRecovery) {
                Log.e("vpn0wn/Settings", "App data import recovery incomplete")
                R.string.app_data_transfer_recovery_error
            } else {
                Log.e("vpn0wn/Settings", "App data import failed")
                R.string.app_data_transfer_error
            }
            val ownerView = view ?: return
            Snackbar.make(ownerView, message, Snackbar.LENGTH_LONG).vpn0wnStyle().show()
        }

        private fun clearPreferenceViewIds(group: PreferenceGroup) {
            for (index in 0 until group.preferenceCount) {
                val preference = group.getPreference(index)
                preference.setViewId(View.NO_ID)
                if (preference is PreferenceGroup) clearPreferenceViewIds(preference)
            }
        }

        private fun removeEmptyCategories() {
            for (index in preferenceScreen.preferenceCount - 1 downTo 0) {
                val category = preferenceScreen.getPreference(index) as? PreferenceCategory ?: continue
                if (category.preferenceCount == 0) {
                    preferenceScreen.removePreference(category)
                }
            }
        }
    }

    companion object {
        private const val APP_DATA_IMPORT_COUNT = "app_data_import_count"
    }
}
