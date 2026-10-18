/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Modified for vpn0wn.
 */
package com.vpn0wn.android.fragment

import kotlinx.coroutines.CancellationException
import com.vpn0wn.config.BadConfigException
import android.content.Context
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.Gravity
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.os.BundleCompat
import androidx.core.view.MenuProvider
import androidx.core.view.descendants
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.vpn0wn.android.Application
import com.vpn0wn.android.R
import com.vpn0wn.android.activity.TunnelCreatorActivity
import com.vpn0wn.android.activity.MainActivity
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.databinding.TunnelEditorFragmentBinding
import com.vpn0wn.android.model.ObservableTunnel
import com.vpn0wn.android.util.AdminKnobs
import com.vpn0wn.android.util.BiometricAuthenticator
import com.vpn0wn.android.util.ErrorMessages
import com.vpn0wn.android.util.vpn0wnStyle
import com.vpn0wn.android.viewmodel.ConfigProxy
import com.vpn0wn.config.Config
import kotlinx.coroutines.launch

/**
 * Fragment for editing a WireGuard configuration.
 */
class TunnelEditorFragment : BaseFragment(), MenuProvider {
    private var haveShownKeys = false
    private var authenticationRequest: Any? = null
    private var binding: TunnelEditorFragmentBinding? = null
    private var tunnel: ObservableTunnel? = null
    private var restoredTunnelName: String? = null

    private fun onConfigSaved(savedTunnel: Tunnel, throwable: Throwable?) {
        val ctx = activity ?: Application.get()
        if (throwable == null) {
            val message = ctx.getString(R.string.config_save_success, savedTunnel.name)
            Log.d(TAG, message)
            onFinished(message)
        } else {
            val error = ErrorMessages[throwable]
            val message = ctx.getString(R.string.config_save_error, savedTunnel.name, error)
            Log.e(TAG, message, throwable)
            val binding = binding
            if (binding != null)
                Snackbar.make(binding.mainContainer, message, Snackbar.LENGTH_LONG).vpn0wnStyle().show()
            else
                Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.config_editor, menu)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        binding = TunnelEditorFragmentBinding.inflate(inflater, container, false)
        binding?.apply {
            isNew = selectedTunnel == null
            executePendingBindings()
            privateKeyRegenButton.setOnClickListener { config?.`interface`?.generateKeyPair() }
            val configuration = resources.configuration
            if (configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                configuration.screenWidthDp < COMPACT_WINDOW_WIDTH_DP && configuration.fontScale >= ENLARGED_FONT_SCALE) {
                editorInterfacePortRow.orientation = LinearLayout.VERTICAL
                for (row in listOf(editorListenPortRow, editorMtuRow)) {
                    row.layoutParams = (row.layoutParams as LinearLayout.LayoutParams).apply {
                        width = ViewGroup.LayoutParams.MATCH_PARENT
                        weight = 0f
                    }
                }
                editorInterfacePortDivider.layoutParams = editorInterfacePortDivider.layoutParams.apply {
                    height = width
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                }
                editorApplicationsRow.orientation = LinearLayout.VERTICAL
                editorApplicationsContent.layoutParams = (editorApplicationsContent.layoutParams as LinearLayout.LayoutParams).apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    weight = 0f
                }
                setExcludedApplications.layoutParams = (setExcludedApplications.layoutParams as LinearLayout.LayoutParams).apply {
                    gravity = Gravity.END
                    topMargin = resources.getDimensionPixelSize(R.dimen.normal_margin)
                }
            }
        }
        return binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onDestroyView() {
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        haveShownKeys = false
        authenticationRequest = null
        restoredTunnelName = null
        binding = null
        super.onDestroyView()
    }

    private fun onFinished(message: String) {
        // Hide the keyboard; it rarely goes away on its own.
        val activity = activity ?: return
        if (activity is TunnelCreatorActivity) {
            activity.setResult(Activity.RESULT_OK, Intent().putExtra(TunnelCreatorActivity.EXTRA_SAVE_MESSAGE, message))
        }
        val focusedView = activity.currentFocus
        if (focusedView != null) {
            val inputManager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            inputManager?.hideSoftInputFromWindow(
                focusedView.windowToken,
                InputMethodManager.HIDE_NOT_ALWAYS
            )
        }
        parentFragmentManager.popBackStackImmediate()

        // If we just made a new one, save it to select the details page.
        if (selectedTunnel != tunnel)
            selectedTunnel = tunnel
        if (activity is MainActivity) {
            Snackbar.make(activity.findViewById(R.id.main_activity_container), message, Snackbar.LENGTH_SHORT).vpn0wnStyle().show()
        }
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId != R.id.menu_action_save) return false
        saveConfig()
        return true
    }

    fun onBackPressed(@Suppress("UNUSED_PARAMETER") view: View) {
        requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    fun onSavePressed(@Suppress("UNUSED_PARAMETER") view: View) {
        saveConfig()
    }

    // Backend/ConfigStore expose Exception; this operation reports their failures while cancellation and Errors propagate.
    @Suppress("TooGenericExceptionCaught")
    private fun saveConfig() {
        val binding = binding ?: return
        val config = binding.config ?: return
        val newConfig = try {
            config.resolve()
        } catch (e: BadConfigException) {
            val error = ErrorMessages[e]
            val tunnelName = if (tunnel == null) binding.name else tunnel!!.name
            val message = getString(R.string.config_save_error, tunnelName, error)
            Log.e(TAG, message, e)
            Snackbar.make(binding.mainContainer, error, Snackbar.LENGTH_LONG).vpn0wnStyle().show()
            return
        }
        val name = binding.name?.takeIf { it.isNotBlank() } ?: run {
            Snackbar.make(binding.mainContainer, getString(R.string.tunnel_error_invalid_name), Snackbar.LENGTH_LONG).vpn0wnStyle().show()
            return
        }
        val activity = requireActivity()
        val t = tunnel
        val renaming = t != null && t.name != name
        activity.lifecycleScope.launch {
            val createdTunnel = try {
                when {
                    t == null -> {
                        Log.d(TAG, "Attempting to create new tunnel $name")
                        Application.getTunnelManager().create(name, newConfig)
                    }
                    renaming -> {
                        Log.d(TAG, "Attempting to rename tunnel to $name")
                        t!!.setNameAsync(name)
                        null
                    }
                    else -> {
                        Log.d(TAG, "Attempting to save config of " + t!!.name)
                        t.setConfigAsync(newConfig)
                        null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when {
                    t == null -> onTunnelCreated(null, e)
                    renaming -> onTunnelRenamed(t, newConfig, e)
                    else -> onConfigSaved(t, e)
                }
                return@launch
            }
            when {
                t == null -> onTunnelCreated(createdTunnel, null)
                renaming -> onTunnelRenamed(t, newConfig, null)
                else -> onConfigSaved(t, null)
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onRequestSetExcludedIncludedApplications(view: View?) {
        val binding = binding ?: return
        val config = binding.config ?: return
        var isExcluded = true
        var selectedApps = ArrayList(config.`interface`.excludedApplications)
        if (selectedApps.isEmpty()) {
            selectedApps = ArrayList(config.`interface`.includedApplications)
            if (selectedApps.isNotEmpty())
                isExcluded = false
        }
        val fragment = AppListDialogFragment.newInstance(selectedApps, isExcluded)
        childFragmentManager.setFragmentResultListener(AppListDialogFragment.REQUEST_SELECTION, viewLifecycleOwner) { _, bundle ->
            val b = this.binding ?: return@setFragmentResultListener
            val c = b.config ?: return@setFragmentResultListener
            val newSelections = requireNotNull(bundle.getStringArray(AppListDialogFragment.KEY_SELECTED_APPS))
            val excluded = requireNotNull(bundle.getBoolean(AppListDialogFragment.KEY_IS_EXCLUDED))
            if (excluded) {
                c.`interface`.includedApplications.clear()
                c.`interface`.excludedApplications.apply {
                    clear()
                    addAll(newSelections)
                }
            } else {
                c.`interface`.excludedApplications.clear()
                c.`interface`.includedApplications.apply {
                    clear()
                    addAll(newSelections)
                }
            }
        }
        fragment.show(childFragmentManager, null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        binding?.let { outState.putParcelable(KEY_LOCAL_CONFIG, it.config) }
        outState.putString(KEY_ORIGINAL_NAME, tunnel?.name ?: restoredTunnelName)
        super.onSaveInstanceState(outState)
    }

    // Backend/ConfigStore expose Exception; this operation reports their failures while cancellation and Errors propagate.
    @Suppress("TooGenericExceptionCaught")
    override fun onSelectedTunnelChanged(
        oldTunnel: ObservableTunnel?,
        newTunnel: ObservableTunnel?
    ) {
        val restoreDraft = newTunnel != null && newTunnel.name == restoredTunnelName
        restoredTunnelName = null
        resetKeyAuthorization()
        tunnel = newTunnel
        val ownerBinding = binding ?: return
        ownerBinding.isNew = newTunnel == null
        if (restoreDraft) {
            if (ownerBinding.name == null) ownerBinding.name = newTunnel!!.name
            return
        }
        val loadingConfig = ConfigProxy()
        ownerBinding.config = loadingConfig
        if (newTunnel != null) {
            ownerBinding.name = newTunnel.name
            viewLifecycleOwner.lifecycleScope.launch {
                val config = try {
                    newTunnel.getConfigAsync()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load selected tunnel config: ${e.javaClass.simpleName}")
                    return@launch
                }
                if (binding === ownerBinding && tunnel === newTunnel && ownerBinding.config === loadingConfig) {
                    resetKeyAuthorization()
                    ownerBinding.config = ConfigProxy(config)
                }
            }
        } else {
            ownerBinding.name = ""
        }
    }

    private fun onTunnelCreated(newTunnel: ObservableTunnel?, throwable: Throwable?) {
        val ctx = activity ?: Application.get()
        if (throwable == null) {
            tunnel = newTunnel
            val message = ctx.getString(R.string.tunnel_create_success, tunnel!!.name)
            Log.d(TAG, message)
            onFinished(message)
        } else {
            val error = ErrorMessages[throwable]
            val message = ctx.getString(R.string.tunnel_create_error, error)
            Log.e(TAG, message, throwable)
            val binding = binding
            if (binding != null)
                Snackbar.make(binding.mainContainer, message, Snackbar.LENGTH_LONG).vpn0wnStyle().show()
            else
                Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Backend/ConfigStore expose Exception; this operation reports their failures while cancellation and Errors propagate.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun onTunnelRenamed(
        renamedTunnel: ObservableTunnel, newConfig: Config,
        throwable: Throwable?
    ) {
        val ctx = activity ?: Application.get()
        if (throwable == null) {
            val message = ctx.getString(R.string.tunnel_rename_success, renamedTunnel.name)
            Log.d(TAG, message)
            // Now save the rest of configuration changes.
            Log.d(TAG, "Attempting to save config of renamed tunnel " + tunnel!!.name)
            try {
                renamedTunnel.setConfigAsync(newConfig)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onConfigSaved(renamedTunnel, e)
                return
            }
            onConfigSaved(renamedTunnel, null)
        } else {
            val error = ErrorMessages[throwable]
            val message = ctx.getString(R.string.tunnel_rename_error, error)
            Log.e(TAG, message, throwable)
            val binding = binding
            if (binding != null)
                Snackbar.make(binding.mainContainer, message, Snackbar.LENGTH_LONG).vpn0wnStyle().show()
            else
                Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        val binding = binding ?: return
        binding.fragment = this
        if (savedInstanceState == null) {
            onSelectedTunnelChanged(null, selectedTunnel)
        } else {
            tunnel = selectedTunnel
            val config = BundleCompat.getParcelable(savedInstanceState, KEY_LOCAL_CONFIG, ConfigProxy::class.java)
                ?: return
            val originalName = savedInstanceState.getString(KEY_ORIGINAL_NAME)
            val t = tunnel
            if (t != null && t.name != originalName) {
                onSelectedTunnelChanged(null, t)
            } else {
                restoredTunnelName = originalName
                binding.config = config
            }
        }
        super.onViewStateRestored(savedInstanceState)
    }

    fun onKeyClick(view: View) = onKeyFocusChange(view, true)

    fun onKeyFocusChange(view: View, isFocused: Boolean) {
        if (!isFocused) return
        val edit = view as? EditText ?: return
        val ownerBinding = binding ?: return
        if (!isCurrentKeyView(ownerBinding, edit)) return
        if (AdminKnobs.disableConfigExport) {
            resetKeyAuthorization()
            return
        }
        if (authenticationRequest != null) return
        if (edit.inputType == KEY_VISIBLE_INPUT_TYPE) return
        if (!haveShownKeys && edit.text.isNotEmpty()) {
            val request = Any()
            val ownerConfig = ownerBinding.config
            val ownerTunnel = tunnel
            authenticationRequest = request
            try {
                BiometricAuthenticator.authenticate(R.string.biometric_prompt_private_key_title, this) {
                    if (authenticationRequest !== request) return@authenticate
                    if (it is BiometricAuthenticator.Result.Failure && it.code == null) {
                        if (isCurrentKeyView(ownerBinding, edit)) {
                            Snackbar.make(ownerBinding.mainContainer, it.message, Snackbar.LENGTH_SHORT).vpn0wnStyle().show()
                        }
                        return@authenticate
                    }
                    authenticationRequest = null
                    if (!isCurrentKeyView(ownerBinding, edit) || tunnel !== ownerTunnel || ownerBinding.config !== ownerConfig) {
                        return@authenticate
                    }
                    if (AdminKnobs.disableConfigExport) {
                        resetKeyAuthorization()
                        return@authenticate
                    }
                    when (it) {
                        is BiometricAuthenticator.Result.Success, is BiometricAuthenticator.Result.NoAuthenticationConfigured -> {
                            haveShownKeys = true
                            showPrivateKey(edit)
                        }

                        is BiometricAuthenticator.Result.Failure -> {
                            Snackbar.make(ownerBinding.mainContainer, it.message, Snackbar.LENGTH_SHORT).vpn0wnStyle().show()
                        }

                        is BiometricAuthenticator.Result.Cancelled -> {}
                    }
                }
            } catch (failure: RuntimeException) {
                if (authenticationRequest === request) authenticationRequest = null
                throw failure
            }
        } else {
            showPrivateKey(edit)
        }
    }

    private fun isCurrentKeyView(ownerBinding: TunnelEditorFragmentBinding, edit: EditText) =
        binding === ownerBinding && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
            ownerBinding.mainContainer.descendants.any { it === edit }

    private fun resetKeyAuthorization() {
        haveShownKeys = false
        authenticationRequest = null
        binding?.mainContainer?.descendants?.filterIsInstance<EditText>()?.filter {
            it.id == R.id.private_key_text || it.id == R.id.pre_shared_key_text
        }?.forEach {
            setKeyInputType(it, KEY_MASKED_INPUT_TYPE)
        }
    }

    private fun showPrivateKey(edit: EditText) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setKeyInputType(edit, KEY_VISIBLE_INPUT_TYPE)
    }

    private fun setKeyInputType(edit: EditText, inputType: Int) {
        // TextView replaces custom fonts when changing password variations.
        val typeface = edit.typeface
        edit.inputType = inputType
        edit.typeface = typeface
    }

    companion object {
        private const val COMPACT_WINDOW_WIDTH_DP = 600
        private const val ENLARGED_FONT_SCALE = 1.5f
        private const val KEY_MASKED_INPUT_TYPE = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_PASSWORD
        private const val KEY_VISIBLE_INPUT_TYPE = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        private const val KEY_LOCAL_CONFIG = "local_config"
        private const val KEY_ORIGINAL_NAME = "original_name"
        private const val TAG = "vpn0wn/TunnelEditorFragment"
    }
}
