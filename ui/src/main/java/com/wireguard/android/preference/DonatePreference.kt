/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.preference

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AttributeSet
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.wireguard.android.R
import com.wireguard.android.updater.Updater
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.util.activity
import com.wireguard.android.util.tunnlStyle
import androidx.core.net.toUri

class DonatePreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    override fun getSummary() = context.getString(R.string.donate_summary)

    override fun getTitle() = context.getString(R.string.donate_title)

    override fun onClick() {
        /* Google Play Store forbids links to our donation page. */
        if (Updater.installerIsGooglePlay(context)) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.donate_title)
                .setMessage(R.string.donate_google_play_disappointment)
                .show()
            return
        }

        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = "https://www.wireguard.com/donations/".toUri()
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Snackbar.make(activity.findViewById(android.R.id.content), ErrorMessages[e], Snackbar.LENGTH_SHORT).tunnlStyle().show()
        } catch (e: SecurityException) {
            Snackbar.make(activity.findViewById(android.R.id.content), ErrorMessages[e], Snackbar.LENGTH_SHORT).tunnlStyle().show()
        }
    }
}
