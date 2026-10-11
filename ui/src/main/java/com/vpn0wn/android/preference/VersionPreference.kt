/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Modified for vpn0wn.
 */
package com.vpn0wn.android.preference

import kotlinx.coroutines.CancellationException
import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.util.Log
import android.widget.ImageView
import androidx.core.widget.ImageViewCompat
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.vpn0wn.android.Application
import com.vpn0wn.android.BuildConfig
import com.vpn0wn.android.R
import com.vpn0wn.android.activity.LicensesActivity
import com.vpn0wn.android.backend.Backend
import com.vpn0wn.android.backend.GoBackend
import com.vpn0wn.android.backend.WgQuickBackend
import com.vpn0wn.android.util.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VersionPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    private var versionSummary: String? = null

    override fun getSummary() = versionSummary

    override fun getTitle() = context.getString(R.string.version_title, BuildConfig.VERSION_NAME)

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val icon = holder.findViewById(android.R.id.icon) as? ImageView ?: return
        ImageViewCompat.setImageTintList(icon, null)
    }

    override fun onClick() {
        context.startActivity(Intent(context, LicensesActivity::class.java))
    }

    companion object {
        private const val TAG = "vpn0wn/VersionPreference"
        private fun getBackendPrettyName(context: Context, backend: Backend) = when (backend) {
            is WgQuickBackend -> context.getString(R.string.type_name_kernel_module)
            is GoBackend -> context.getString(R.string.type_name_go_userspace)
            else -> ""
        }
    }

    init {
        lifecycleScope.launch {
            val backend = Application.getBackend()
            versionSummary = getContext().getString(R.string.version_summary_checking, getBackendPrettyName(context, backend).lowercase())
            notifyChanged()
            // Backend.getVersion declares Exception; retain the unknown-version fallback for those failures.
            @Suppress("TooGenericExceptionCaught")
            val checkedVersion = try {
                getContext().getString(R.string.version_summary, getBackendPrettyName(context, backend), withContext(Dispatchers.IO) { backend.version })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read backend version: ${e.javaClass.simpleName}")
                getContext().getString(R.string.version_summary_unknown, getBackendPrettyName(context, backend).lowercase())
            }
            versionSummary = checkedVersion
            notifyChanged()
        }
    }
}
