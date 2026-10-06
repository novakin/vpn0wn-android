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

    enum class TunnelMode { UP, CONNECTING, ERROR, DOWN }

    private val density = resources.displayMetrics.density
    private var signal = 0
    private var signalDeep = 0
    private var warn = 0
    private var error = 0
    private var idle = 0
    private var ruleStrong = 0
    private var paper3 = 0
    private var signalFill = 0
    private val railIdlePathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)

    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        strokeCap = Paint.Cap.ROUND
    }
    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.5f)
        pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(2f)), 0f)
    }
    private val nodeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val nodeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            packetProgress = it.animatedValue as Float
            invalidate()
        }
    }
    private var packetProgress = 0f

    var mode: TunnelMode = TunnelMode.DOWN
        set(value) {
            if (field == value) return
            field = value
            syncPaints()
            syncAnimator()
            invalidate()
        }

    init {
        syncColors()
        setWillNotDraw(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncColors()
        syncPaints()
        syncAnimator()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cy = height / 2f
        val start = paddingStart + dp(4f)
        val end = width - paddingEnd - dp(4f)

        val midStart = start + (end - start) * 0.31f
        val midEnd   = start + (end - start) * 0.69f

        canvas.drawLine(start, cy, end, cy, railPaint)
        canvas.drawRect(midStart, cy - dp(6f), midEnd, cy + dp(6f), segmentPaint)
        drawNode(canvas, start, cy)
        drawNode(canvas, end, cy)

        if (mode == TunnelMode.UP) {
            drawPacket(canvas, start, end, cy, packetProgress, dp(2.7f), 255, signalDeep)
            drawPacket(canvas, end, start, cy, (packetProgress + 0.66f) % 1f, dp(2.1f), 190, signal)
            drawPacket(canvas, start, end, cy, (packetProgress + 0.33f) % 1f, dp(1.7f), 135, signalDeep)
        } else if (mode == TunnelMode.CONNECTING) {
            val rangeEnd = start + (end - start) * 0.5f
            val x = start + (rangeEnd - start) * packetProgress
            canvas.drawCircle(x, cy, dp(2.5f), packetPaint)
        }
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun drawNode(canvas: Canvas, x: Float, y: Float) {
        if (mode == TunnelMode.UP)
            canvas.drawCircle(x, y, dp(6.5f), nodeGlowPaint)
        canvas.drawCircle(x, y, dp(3f), nodeFillPaint)
        canvas.drawCircle(x, y, dp(3f), nodeStrokePaint)
    }

    private fun drawPacket(canvas: Canvas, from: Float, to: Float, y: Float, progress: Float, radius: Float, alpha: Int, color: Int) {
        val x = from + (to - from) * progress
        packetPaint.color = color
        packetPaint.alpha = alpha
        canvas.drawCircle(x, y, radius, packetPaint)
    }

    private fun syncAnimator() {
        if (!isAttachedToWindow) return
        val shouldAnimate = mode == TunnelMode.UP || mode == TunnelMode.CONNECTING
        // Connecting animates 2× faster (0.9s) to signal urgency; UP uses relaxed 1.5s
        val targetDuration = if (mode == TunnelMode.CONNECTING) CONNECTING_ANIMATION_DURATION_MS else CONNECTED_ANIMATION_DURATION_MS
        if (shouldAnimate) {
            if (animator.duration != targetDuration) {
                animator.cancel()
                animator.duration = targetDuration
                packetProgress = 0f
            }
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
            packetProgress = 0f
        }
    }

    private fun syncColors() {
        signal     = ContextCompat.getColor(context, R.color.tunnl_signal)
        signalDeep = ContextCompat.getColor(context, R.color.tunnl_signal_deep)
        warn       = ContextCompat.getColor(context, R.color.tunnl_warn)
        error      = ContextCompat.getColor(context, R.color.tunnl_error)
        idle       = ContextCompat.getColor(context, R.color.tunnl_ink_3)
        ruleStrong = ContextCompat.getColor(context, R.color.tunnl_rule_strong)
        paper3     = ContextCompat.getColor(context, R.color.tunnl_paper_3)
        signalFill = Color.argb(NODE_GLOW_ALPHA, Color.red(signal), Color.green(signal), Color.blue(signal))
    }

    private fun syncPaints() {
        if (!isAttachedToWindow) return
        railPaint.color = when (mode) {
            TunnelMode.UP         -> signalDeep
            TunnelMode.CONNECTING -> warn
            TunnelMode.ERROR      -> error
            TunnelMode.DOWN       -> idle
        }
        // Solid line only when fully UP and handshake confirmed; dashed otherwise
        railPaint.pathEffect = if (mode == TunnelMode.UP) null
                               else railIdlePathEffect
        segmentPaint.color = when (mode) {
            TunnelMode.UP    -> signalDeep
            TunnelMode.ERROR -> error
            else             -> idle
        }
        nodeFillPaint.color = when (mode) {
            TunnelMode.UP         -> signal
            TunnelMode.CONNECTING -> warn
            TunnelMode.ERROR      -> error
            TunnelMode.DOWN       -> paper3
        }
        nodeGlowPaint.color = signalFill
        nodeStrokePaint.color = when (mode) {
            TunnelMode.UP         -> signalDeep
            TunnelMode.CONNECTING -> warn
            TunnelMode.ERROR      -> error
            TunnelMode.DOWN       -> ruleStrong
        }
        packetPaint.color = if (mode == TunnelMode.CONNECTING) warn else signalDeep
        packetPaint.alpha = OPAQUE_ALPHA
    }

    private companion object {
        const val CONNECTING_ANIMATION_DURATION_MS = 900L
        const val CONNECTED_ANIMATION_DURATION_MS = 1500L
        const val NODE_GLOW_ALPHA = 36
        const val OPAQUE_ALPHA = 255
    }

    private fun dp(value: Float): Float = value * density
}
