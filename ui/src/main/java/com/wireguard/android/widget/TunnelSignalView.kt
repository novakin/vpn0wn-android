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
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.wireguard.android.R

class TunnelSignalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(3f)), 0f)
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val packetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            packetProgress = it.animatedValue as Float
            invalidate()
        }
    }
    private var packetProgress = 0f

    var live = false
        set(value) {
            if (field == value) return
            field = value
            syncAnimator()
            invalidate()
        }

    init {
        setWillNotDraw(false)
        syncColors()
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
        syncColors()
        val cy = height / 2f
        val start = paddingStart + dp(3f)
        val end = width - paddingEnd - dp(3f)
        val midStart = start + (end - start) * 0.32f
        val midEnd = start + (end - start) * 0.70f

        canvas.drawLine(start, cy, end, cy, railPaint)
        canvas.drawLine(midStart, cy + dp(5f), midEnd, cy + dp(5f), railPaint)
        drawNode(canvas, start, cy)
        drawNode(canvas, midStart, cy)
        drawNode(canvas, midEnd, cy)
        drawNode(canvas, end, cy)

        if (live) {
            val x = start + (end - start) * packetProgress
            canvas.drawCircle(x, cy, dp(2.5f), packetPaint)
            canvas.drawCircle(x, cy, dp(7f), glowPaint)
        }
    }

    private fun drawNode(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, dp(if (live) 3f else 2.5f), nodePaint)
    }

    private fun syncAnimator() {
        if (!isAttachedToWindow) return
        if (live && !animator.isStarted) {
            animator.start()
        } else if (!live && animator.isStarted) {
            animator.cancel()
            packetProgress = 0f
        }
    }

    private fun syncColors() {
        val signal = ContextCompat.getColor(context, R.color.tunnl_signal)
        val signalDeep = ContextCompat.getColor(context, R.color.tunnl_signal_deep)
        val idle = ContextCompat.getColor(context, R.color.tunnl_ink_3)
        railPaint.color = if (live) signalDeep else idle
        nodePaint.color = if (live) signal else ContextCompat.getColor(context, R.color.tunnl_paper_3)
        packetPaint.color = signalDeep
        glowPaint.color = Color.argb(52, Color.red(signal), Color.green(signal), Color.blue(signal))
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
