/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.wireguard.android.R
import kotlin.math.max

class TunnelThroughputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.6f)
        pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(5f)), 0f)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(1.8f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10f)
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }
    private var phase = 0f
    private var rxRate = "--"
    private var txRate = "--"

    var live = false
        set(value) {
            if (field == value) return
            field = value
            syncAnimator()
            invalidate()
        }

    fun setRates(rxRate: String, txRate: String) {
        this.rxRate = rxRate
        this.txRate = txRate
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncAnimator()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val signal = ContextCompat.getColor(context, R.color.tunnl_signal)
        val signalDeep = ContextCompat.getColor(context, R.color.tunnl_signal_deep)
        val ink3 = ContextCompat.getColor(context, R.color.tunnl_ink_3)
        val rule = ContextCompat.getColor(context, R.color.tunnl_rule)
        val paper3 = ContextCompat.getColor(context, R.color.tunnl_paper_3)
        gridPaint.color = rule
        linePaint.color = if (live) signalDeep else ink3
        fillPaint.color = Color.argb(36, Color.red(signal), Color.green(signal), Color.blue(signal))
        nodePaint.color = if (live) signal else paper3
        textPaint.color = ink3

        val left = paddingStart + dp(14f)
        val top = paddingTop + dp(16f)
        val right = width - paddingEnd - dp(14f)
        val bottom = height - paddingBottom - dp(28f)
        val midY = (top + bottom) / 2f
        val usableW = max(dp(1f), right - left)
        val usableH = max(dp(1f), bottom - top)

        for (i in 0..4) {
            val y = top + usableH * i / 4f
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        for (i in 0..6) {
            val x = left + usableW * i / 6f
            canvas.drawLine(x, top, x, bottom, gridPaint)
        }

        val rx = Path()
        val tx = Path()
        for (i in 0..40) {
            val t = i / 40f
            val x = left + usableW * t
            val wave = kotlin.math.sin((t * 5f + phase) * Math.PI * 2.0).toFloat()
            val wave2 = kotlin.math.cos((t * 4f + phase * 0.7f) * Math.PI * 2.0).toFloat()
            val rxY = midY - dp(8f) - wave * dp(if (live) 16f else 5f)
            val txY = midY + dp(8f) + wave2 * dp(if (live) 10f else 4f)
            if (i == 0) {
                rx.moveTo(x, rxY)
                tx.moveTo(x, txY)
            } else {
                rx.lineTo(x, rxY)
                tx.lineTo(x, txY)
            }
        }
        canvas.drawPath(rx, linePaint)
        linePaint.alpha = 155
        canvas.drawPath(tx, linePaint)
        linePaint.alpha = 255

        canvas.drawCircle(left, midY, dp(5f), nodePaint)
        canvas.drawCircle(right, midY, dp(5f), nodePaint)
        if (live) {
            val packetX = left + usableW * phase
            canvas.drawCircle(packetX, midY, dp(3f), nodePaint)
        }

        canvas.drawText("IN $rxRate", left, height - paddingBottom - dp(8f), textPaint)
        val outText = "OUT $txRate"
        canvas.drawText(outText, right - textPaint.measureText(outText), height - paddingBottom - dp(8f), textPaint)
    }

    private fun syncAnimator() {
        if (!isAttachedToWindow) return
        if (live && !animator.isStarted) {
            animator.start()
        } else if (!live && animator.isStarted) {
            animator.cancel()
            phase = 0f
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
