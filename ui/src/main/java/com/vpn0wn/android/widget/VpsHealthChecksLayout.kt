/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.widget

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView
import androidx.constraintlayout.helper.widget.Flow
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.vpn0wn.android.R
import com.vpn0wn.android.provisioning.VpsDeployHealthCheck

internal class VpsHealthChecksLayout(context: Context, checks: List<VpsDeployHealthCheck>) : ConstraintLayout(context) {
    init {
        val badgeIds = checks.map { check ->
            val view = badge(check).apply { id = generateViewId() }
            addView(view, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            view.id
        }
        addView(Flow(context).apply {
            id = generateViewId()
            referencedIds = badgeIds.toIntArray()
            setOrientation(Flow.HORIZONTAL)
            setWrapMode(Flow.WRAP_CHAIN)
            setHorizontalStyle(LayoutParams.CHAIN_PACKED)
            setHorizontalBias(0f)
            setVerticalAlign(Flow.VERTICAL_ALIGN_TOP)
            setHorizontalGap(dp(BADGE_GAP_DP))
            setVerticalGap(dp(BADGE_GAP_DP))
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT).apply {
            startToStart = LayoutParams.PARENT_ID
            endToEnd = LayoutParams.PARENT_ID
            topToTop = LayoutParams.PARENT_ID
        })
    }

    private fun badge(check: VpsDeployHealthCheck): TextView {
        val colorRes = when (check.ok) {
            true -> R.color.vpn0wn_signal_deep
            false -> R.color.vpn0wn_error
            null -> R.color.vpn0wn_warn
        }
        val glyph = when (check.ok) {
            true -> "◆"
            false -> "✕"
            null -> "◑"
        }
        val statusColor = ContextCompat.getColor(context, colorRes)
        return TextView(context).apply {
            setTextAppearance(R.style.Vpn0wnMonoSubText)
            typeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular)
            includeFontPadding = false
            text = "$glyph ${check.label}"
            letterSpacing = BADGE_LETTER_SPACING
            gravity = Gravity.CENTER
            minHeight = dp(BADGE_MIN_HEIGHT_DP)
            setTextColor(statusColor)
            setPadding(dp(BADGE_HORIZONTAL_PADDING_DP), dp(BADGE_VERTICAL_PADDING_DP),
                dp(BADGE_HORIZONTAL_PADDING_DP), dp(BADGE_VERTICAL_PADDING_DP))
            background = GradientDrawable().apply {
                cornerRadius = dp(BADGE_RADIUS_DP).toFloat()
                setColor(ContextCompat.getColor(context, R.color.vpn0wn_paper_3))
                setStroke(dp(BADGE_STROKE_DP), statusColor)
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val BADGE_GAP_DP = 6
        const val BADGE_MIN_HEIGHT_DP = 28
        const val BADGE_HORIZONTAL_PADDING_DP = 9
        const val BADGE_VERTICAL_PADDING_DP = 3
        const val BADGE_RADIUS_DP = 999
        const val BADGE_STROKE_DP = 1
        const val BADGE_LETTER_SPACING = 0.05f
    }
}
