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
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.wireguard.android.R
import kotlin.math.max
import kotlin.math.min

class TunnelThroughputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private var signal = 0
    private var signalDeep = 0
    private var ink3 = 0
    private var rule = 0
    private var ruleStrong = 0
    private var paper3 = 0
    private var warn = 0
    private var signalFill = 0
    private var ruleStrongDim = 0
    private val idleLinePathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)
    private val segmentRect = RectF()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.6f)
        pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(5f)), 0f)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(1.4f)
    }
    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.9f)
        pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(2f)), 0f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val nodeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.SQUARE
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.6f)
    }
    private val packetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10f)
        typeface = Typeface.MONOSPACE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(9.5f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
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
    private val connectingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            connectingPhase = it.animatedValue as Float
            invalidate()
        }
    }

    private var phase = 0f
    private var connectingPhase = 0f
    private var rxRate = "--"
    private var txRate = "--"
    private var deviceAddress = "—"
    private var peerEndpoint = "—"
    private var mtu = "—"

    var live = false
        set(value) {
            if (field == value) return
            field = value
            syncAnimators()
            invalidate()
        }

    var connecting = false
        set(value) {
            if (field == value) return
            field = value
            syncAnimators()
            invalidate()
        }

    fun setRates(rxRate: String, txRate: String) {
        this.rxRate = rxRate
        this.txRate = txRate
        invalidate()
    }

    fun setAddress(address: String) {
        this.deviceAddress = address
        invalidate()
    }

    fun setEndpoint(endpoint: String) {
        this.peerEndpoint = endpoint
        invalidate()
    }

    fun setMtu(mtu: String) {
        this.mtu = mtu
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncColors()
        syncAnimators()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        connectingAnimator.cancel()
        super.onDetachedFromWindow()
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        gridPaint.color       = rule
        linePaint.color       = if (live) signalDeep else ink3
        linePaint.pathEffect  = if (live) null else idleLinePathEffect
        segmentPaint.color    = if (live) signalDeep else ink3
        fillPaint.color       = signalFill
        nodePaint.color       = if (live) signal else paper3
        nodeStrokePaint.color = if (live) signalDeep else ruleStrong
        warnPaint.color       = warn
        cornerPaint.color     = ruleStrong
        dimPaint.color        = ruleStrongDim
        textPaint.color       = ink3
        labelPaint.color      = ink3

        val left   = paddingStart.toFloat()
        val top    = paddingTop.toFloat()
        val right  = width - paddingEnd.toFloat()
        val bottom = height - paddingBottom.toFloat()

        val contentLeft   = left + dp(14f)
        val contentTop    = top + dp(16f)
        val contentRight  = right - dp(14f)
        val contentBottom = bottom - dp(24f)
        val midY = contentTop + (contentBottom - contentTop) * 0.5f
        val usableW = max(dp(1f), contentRight - contentLeft)

        // Eng-corners bracket marks (top-left + bottom-right)
        val arm = dp(12f)
        canvas.drawLine(left, top + arm, left, top, cornerPaint)
        canvas.drawLine(left, top, left + arm, top, cornerPaint)
        canvas.drawLine(right - arm, bottom, right, bottom, cornerPaint)
        canvas.drawLine(right, bottom, right, bottom - arm, cornerPaint)

        // Dot grid
        val gridStep = dp(12f)
        var gridX = contentLeft
        while (gridX <= contentRight) {
            var gridY = contentTop
            while (gridY <= contentBottom) {
                canvas.drawPoint(gridX, gridY, gridPaint)
                gridY += gridStep
            }
            gridX += gridStep
        }

        val pathLeft     = contentLeft + usableW * 0.07f
        val pathRight    = contentRight - usableW * 0.07f
        val segmentLeft  = contentLeft + usableW * 0.34f
        val segmentRight = contentLeft + usableW * 0.66f

        // Dimension lines (decorative)
        canvas.drawLine(pathLeft, contentBottom - dp(20f), pathRight, contentBottom - dp(20f), dimPaint)
        canvas.drawLine(pathLeft, contentBottom - dp(22f), pathLeft, contentBottom - dp(18f), dimPaint)
        canvas.drawLine(pathRight, contentBottom - dp(22f), pathRight, contentBottom - dp(18f), dimPaint)

        // Wire path
        canvas.drawLine(pathLeft, midY, segmentLeft, midY, linePaint)
        canvas.drawLine(segmentLeft, midY, segmentRight, midY, linePaint)
        canvas.drawLine(segmentRight, midY, pathRight, midY, linePaint)

        // Encrypted segment rect overlay
        segmentRect.set(segmentLeft, midY - dp(9f), segmentRight, midY + dp(9f))
        canvas.drawRect(segmentRect, segmentPaint)

        // Endpoint nodes
        drawNode(canvas, pathLeft, midY)
        drawNode(canvas, pathRight, midY)

        // Bidirectional packets when live
        if (live) {
            drawPacket(canvas, pathLeft, pathRight, midY, phase, dp(3.5f), 255, nodePaint.color)
            drawPacket(canvas, pathRight, pathLeft, midY, (phase + 0.66f) % 1f, dp(2.5f), 180, nodePaint.color)
            drawPacket(canvas, pathLeft, pathRight, midY, (phase + 0.33f) % 1f, dp(2f), 130, nodePaint.color)
        }

        // Connecting: warn-color packet to midpoint only
        if (connecting && !live) {
            val midX = (pathLeft + pathRight) / 2f
            val x = pathLeft + (midX - pathLeft) * connectingPhase
            canvas.drawCircle(x, midY, dp(3f), warnPaint)
        }

        // Node labels
        drawNodeLabel(canvas, pathLeft, midY - dp(24f), "DEVICE", deviceAddress, Paint.Align.LEFT)
        drawNodeLabel(canvas, (segmentLeft + segmentRight) / 2f, contentTop + dp(22f), "ENCRYPTED · CHACHA20-POLY1305", "MTU $mtu", Paint.Align.CENTER)
        drawNodeLabel(canvas, pathRight, midY - dp(24f), "PEER", peerEndpoint, Paint.Align.RIGHT)

        // Footer: ↓ / ↑
        canvas.drawText("↓ $rxRate", contentLeft, height - paddingBottom - dp(7f), textPaint)
        val upText = "↑ $txRate"
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(upText, contentRight, height - paddingBottom - dp(7f), textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun drawNode(canvas: Canvas, x: Float, y: Float) {
        if (live) canvas.drawCircle(x, y, dp(8f), fillPaint)
        canvas.drawCircle(x, y, dp(4f), nodePaint)
        canvas.drawCircle(x, y, dp(4f), nodeStrokePaint)
    }

    private fun drawPacket(canvas: Canvas, from: Float, to: Float, y: Float, progress: Float, radius: Float, alpha: Int, color: Int) {
        val x = from + (to - from) * progress
        packetPaint.color = color
        packetPaint.alpha = alpha
        canvas.drawCircle(x, y, radius, packetPaint)
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun drawNodeLabel(canvas: Canvas, x: Float, y: Float, label: String, sub: String, align: Paint.Align) {
        labelPaint.textAlign = align
        textPaint.textAlign  = align
        val clampedX = when (align) {
            Paint.Align.LEFT  -> min(x, width - paddingEnd - dp(80f))
            Paint.Align.RIGHT -> max(x, paddingStart + dp(80f))
            else -> x
        }
        canvas.drawText(label, clampedX, y, labelPaint)
        canvas.drawText(sub, clampedX, y + dp(13f), textPaint)
        labelPaint.textAlign = Paint.Align.LEFT
        textPaint.textAlign  = Paint.Align.LEFT
    }

    private fun syncAnimators() {
        if (!isAttachedToWindow) return
        if (live) {
            connectingAnimator.cancel()
            if (!animator.isStarted) animator.start()
        } else if (connecting) {
            animator.cancel()
            phase = 0f
            if (!connectingAnimator.isStarted) connectingAnimator.start()
        } else {
            animator.cancel()
            connectingAnimator.cancel()
            phase = 0f
            connectingPhase = 0f
        }
    }

    private fun syncColors() {
        signal     = ContextCompat.getColor(context, R.color.tunnl_diagram_node)
        signalDeep = ContextCompat.getColor(context, R.color.tunnl_diagram_line)
        ink3       = ContextCompat.getColor(context, R.color.tunnl_ink_3)
        rule       = ContextCompat.getColor(context, R.color.tunnl_rule)
        ruleStrong = ContextCompat.getColor(context, R.color.tunnl_rule_strong)
        paper3     = ContextCompat.getColor(context, R.color.tunnl_paper_3)
        warn       = ContextCompat.getColor(context, R.color.tunnl_warn)
        signalFill = Color.argb(NODE_GLOW_ALPHA, Color.red(signal), Color.green(signal), Color.blue(signal))
        ruleStrongDim = Color.argb(DIMMED_RULE_ALPHA, Color.red(ruleStrong), Color.green(ruleStrong), Color.blue(ruleStrong))
    }

    private companion object {
        const val NODE_GLOW_ALPHA = 36
        const val DIMMED_RULE_ALPHA = 56
    }

    private fun dp(value: Float): Float = value * density
}
