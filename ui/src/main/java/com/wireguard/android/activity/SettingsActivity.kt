/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import com.wireguard.android.util.RootShell.RootShellException
import java.io.IOException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import com.wireguard.android.Application
import com.wireguard.android.QuickTileService
import com.wireguard.android.R
import com.wireguard.android.backend.WgQuickBackend
import com.wireguard.android.preference.PreferencesPreferenceDataStore
import com.wireguard.android.util.AdminKnobs
import com.wireguard.android.util.applySafeAreaInsets
import kotlinx.coroutines.Dispatchers
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
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            val view = super.onCreateView(inflater, container, savedInstanceState)
            view.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.tunnl_paper))
            return view
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, key: String?) {
            preferenceManager.preferenceDataStore = PreferencesPreferenceDataStore(lifecycleScope, Application.getPreferencesDataStore())
            addPreferencesFromResource(R.xml.preferences)
            // AndroidX defaults row IDs to 0, which Android resolves as a resource during structure capture.
            clearPreferenceViewIds(preferenceScreen)

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || QuickTileService.isAdded) {
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
