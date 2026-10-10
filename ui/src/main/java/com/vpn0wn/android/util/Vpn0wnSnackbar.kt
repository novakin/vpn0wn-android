/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.util

import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.updateLayoutParams
import com.google.android.material.snackbar.Snackbar
import com.vpn0wn.android.R

object Vpn0wnSnackbar {
    // Literal dimensions/type settings describe this local design, like XML layout values.
    @Suppress("MagicNumber")
    fun style(snackbar: Snackbar): Snackbar {
        val context = snackbar.view.context
        val density = context.resources.displayMetrics.density
        val background = GradientDrawable().apply {
            cornerRadius = 8f * density
            setColor(ContextCompat.getColor(context, R.color.tunnl_paper_2))
            setStroke((1f * density).toInt().coerceAtLeast(1), ContextCompat.getColor(context, R.color.tunnl_rule_strong))
        }

        snackbar.setBackgroundTintList(null)
        snackbar.view.background = background
        snackbar.view.elevation = 6f * density
        if (snackbar.view.layoutParams is ViewGroup.MarginLayoutParams) {
            snackbar.view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                val margin = (14f * density).toInt()
                leftMargin = margin
                rightMargin = margin
                bottomMargin += margin
            }
        }

        val mono = ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular)
        snackbar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.apply {
            typeface = mono
            textSize = 12f
            letterSpacing = 0.02f
            setTextColor(ContextCompat.getColor(context, R.color.tunnl_ink))
            setCompoundDrawablesWithIntrinsicBounds(signalDot(context, density), null, null, null)
            compoundDrawablePadding = (8f * density).toInt()
        }
        snackbar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_action)?.apply {
            typeface = mono
            textSize = 11f
            letterSpacing = 0.06f
            setTextColor(ContextCompat.getColor(context, R.color.tunnl_signal_deep))
        }
        return snackbar
    }

    // The dot's diameter is local drawing geometry, not a runtime threshold.
    @Suppress("MagicNumber")
    private fun signalDot(context: android.content.Context, density: Float) =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(context, R.color.tunnl_signal))
            val size = (6f * density).toInt()
            setSize(size, size)
        }
}

fun Snackbar.vpn0wnStyle(): Snackbar = Vpn0wnSnackbar.style(this)
