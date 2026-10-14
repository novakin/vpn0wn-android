/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Modified for vpn0wn.
 */
package com.vpn0wn.android.util

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.view.View
import android.widget.TextView
import androidx.core.content.getSystemService
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.vpn0wn.android.R

/**
 * Standalone utilities for interacting with the system clipboard.
 */
object ClipboardUtils {
    @JvmStatic
    fun copyTextView(view: View) {
        val text = when (view) {
            is TextInputEditText -> view.editableText
            is TextView -> view.text
            else -> return
        }
        copyText(view, text)
    }

    @JvmStatic
    fun copyText(view: View, text: CharSequence?) {
        if (text.isNullOrEmpty()) return
        val label = when (view) {
            is TextInputEditText -> view.hint
            is TextView -> view.contentDescription
            else -> return
        }
        val service = view.context.getSystemService<ClipboardManager>() ?: return
        service.setPrimaryClip(ClipData.newPlainText(label, text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Snackbar.make(view, view.context.getString(R.string.copied_to_clipboard, label), Snackbar.LENGTH_LONG)
                .vpn0wnStyle()
                .show()
        }
    }
}
