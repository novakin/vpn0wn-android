/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import com.wireguard.android.util.RootShell.RootShellException
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import com.google.android.material.snackbar.Snackbar
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.WgQuickBackend
import com.wireguard.android.preference.PreferencesPreferenceDataStore
import com.wireguard.android.util.AdminKnobs
import com.wireguard.android.util.applySafeAreaInsets
import com.wireguard.android.util.tunnlStyle
import com.wireguard.android.util.UserKnobs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Interface for changing application-global persistent settings.
 */
class SettingsActivity : AppCompatActivity() {
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
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private var vpnPolicyJob: Job? = null

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            val view = super.onCreateView(inflater, container, savedInstanceState)
            view.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.tunnl_paper))
            return view
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
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
                val zipExporter = preferenceManager.findPreference<Preference>("zip_exporter")
                zipExporter?.parent?.removePreference(zipExporter)
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
            Snackbar.make(requireView(), message, Snackbar.LENGTH_SHORT).tunnlStyle().show()
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
}
