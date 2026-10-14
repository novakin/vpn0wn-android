/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Modified for vpn0wn.
 */
package com.vpn0wn.android.fragment

import kotlinx.coroutines.CancellationException
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vpn0wn.android.Application
import com.vpn0wn.android.R
import com.vpn0wn.android.databinding.ConfigNamingDialogFragmentBinding
import com.vpn0wn.config.BadConfigException
import com.vpn0wn.config.Config
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

class ConfigNamingDialogFragment : DialogFragment() {
    private var binding: ConfigNamingDialogFragmentBinding? = null
    private lateinit var config: Config
    private var pendingDismiss = false

    // Backend/ConfigStore expose Exception; this operation reports their failures while cancellation and Errors propagate.
    @Suppress("TooGenericExceptionCaught")
    private fun createTunnelAndDismiss() {
        val binding = binding ?: return
        val dialog = dialog as? AlertDialog ?: return
        val createButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        if (!createButton.isEnabled) return
        val name = binding.tunnelNameText.text.toString()
        createButton.isEnabled = false
        binding.tunnelNameText.isEnabled = false
        binding.tunnelNameTextLayout.error = null
        lifecycleScope.launch {
            try {
                Application.getTunnelManager().create(name, config)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (this@ConfigNamingDialogFragment.binding === binding)
                    binding.tunnelNameTextLayout.error = e.message
                return@launch
            } finally {
                if (this@ConfigNamingDialogFragment.binding === binding) {
                    createButton.isEnabled = true
                    binding.tunnelNameText.isEnabled = true
                }
            }
            if (this@ConfigNamingDialogFragment.binding === binding) {
                if (isStateSaved) pendingDismiss = true
                else dismiss()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val configText = requireArguments().getString(KEY_CONFIG_TEXT)
        val configBytes = configText!!.toByteArray(StandardCharsets.UTF_8)
        config = try {
            Config.parse(ByteArrayInputStream(configBytes))
        } catch (e: BadConfigException) {
            throw IllegalArgumentException("Invalid config passed to ${javaClass.simpleName}", e)
        } catch (e: IOException) {
            throw IllegalArgumentException("Invalid config passed to ${javaClass.simpleName}", e)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val activity = requireActivity()
        val alertDialogBuilder = MaterialAlertDialogBuilder(activity)
        alertDialogBuilder.setTitle(R.string.import_from_qr_code)
        binding = ConfigNamingDialogFragmentBinding.inflate(activity.layoutInflater, null, false)
        binding?.apply {
            executePendingBindings()
            alertDialogBuilder.setView(root)
        }
        alertDialogBuilder.setPositiveButton(R.string.create_tunnel, null)
        alertDialogBuilder.setNegativeButton(R.string.cancel) { _, _ -> dismiss() }
        val dialog = alertDialogBuilder.create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        (dialog as? AlertDialog)?.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener { createTunnelAndDismiss() }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    override fun onDismiss(dialog: DialogInterface) {
        binding = null
        super.onDismiss(dialog)
    }

    override fun onResume() {
        super.onResume()
        if (pendingDismiss) {
            pendingDismiss = false
            dismiss()
        }
    }

    companion object {
        private const val KEY_CONFIG_TEXT = "config_text"

        fun newInstance(configText: String?): ConfigNamingDialogFragment {
            val extras = Bundle()
            extras.putString(KEY_CONFIG_TEXT, configText)
            val fragment = ConfigNamingDialogFragment()
            fragment.arguments = extras
            return fragment
        }
    }
}
