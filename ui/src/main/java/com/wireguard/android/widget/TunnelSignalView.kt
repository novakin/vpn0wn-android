/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
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

    // Main line: solid when live, dashed when down
    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    // Encrypted segment rect overlay: always dashed, thinner
    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.5f)
        pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(2f)), 0f)
    }
    private val nodeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val nodeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val packetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1500L
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
        val start = paddingStart + dp(4f)
        val end = width - paddingEnd - dp(4f)

        // Encrypted segment: ~31%–69% of width, matching JSX rect x=100 w=120 on 320 wide
        val midStart = start + (end - start) * 0.31f
        val midEnd   = start + (end - start) * 0.69f

        // Main line
        canvas.drawLine(start, cy, end, cy, railPaint)

        // Encrypted segment dashed rect overlay
        canvas.drawRect(midStart, cy - dp(6f), midEnd, cy + dp(6f), segmentPaint)

        // Two endpoint nodes (left and right only — matches JSX: cx=6 and cx=314)
        drawNode(canvas, start, cy)
        drawNode(canvas, end, cy)

        // Single packet when live
        if (live) {
            val x = start + (end - start) * packetProgress
            canvas.drawCircle(x, cy, dp(2.5f), packetPaint)
        }
    }

    private fun drawNode(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, dp(3f), nodeFillPaint)
        canvas.drawCircle(x, y, dp(3f), nodeStrokePaint)
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
        val signal     = ContextCompat.getColor(context, R.color.tunnl_signal)
        val signalDeep = ContextCompat.getColor(context, R.color.tunnl_signal_deep)
        val idle       = ContextCompat.getColor(context, R.color.tunnl_ink_3)
        val ruleStrong = ContextCompat.getColor(context, R.color.tunnl_rule_strong)
        val paper3     = ContextCompat.getColor(context, R.color.tunnl_paper_3)

        railPaint.color    = if (live) signalDeep else idle
        railPaint.pathEffect = if (live) null else DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)
        segmentPaint.color = if (live) signalDeep else idle
        nodeFillPaint.color   = if (live) signal else paper3
        nodeStrokePaint.color = if (live) signalDeep else ruleStrong
        packetPaint.color  = signalDeep
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
