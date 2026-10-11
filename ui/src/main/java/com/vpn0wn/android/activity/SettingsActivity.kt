/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.activity

import com.vpn0wn.android.util.RootShell.RootShellException
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import android.content.ActivityNotFoundException
import android.content.Intent
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
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.vpn0wn.android.Application
import com.vpn0wn.android.R
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
        private var vpnPolicyJob: Job? = null
        private var transferInProgress = false
        private var importDialog: AlertDialog? = null
        private val appDataPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val ownerView = view
            if (uri != null && ownerView != null) readAppData(uri, ownerView)
        }

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            val view = super.onCreateView(inflater, container, savedInstanceState)
            view.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.tunnl_paper))
            return view
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            setTransferEnabled(true)
            val quickTile = findPreference<Preference>("quick_tile") ?: return
            quickTile.isVisible = false
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    quickTile.isVisible = false
                    try {
                        UserKnobs.quickTileAdded.collect { added -> quickTile.isVisible = added != true }
                    } catch (_: IOException) {
                        quickTile.isVisible = false
                        Log.w("WireGuard/Settings", "Unable to load quick tile membership")
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
                preferenceManager.findPreference("restore_on_boot"),
                preferenceManager.findPreference<Preference>("multiple_tunnels")
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
                Log.e("WireGuard/Settings", "App data import recovery incomplete")
                R.string.app_data_transfer_recovery_error
            } else {
                Log.e("WireGuard/Settings", "App data import failed")
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
