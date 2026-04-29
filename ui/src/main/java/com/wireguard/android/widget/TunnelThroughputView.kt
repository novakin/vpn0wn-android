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
        val ruleStrong = ContextCompat.getColor(context, R.color.tunnl_rule_strong)
        val paper3 = ContextCompat.getColor(context, R.color.tunnl_paper_3)
        gridPaint.color = rule
        linePaint.color = if (live) signalDeep else ink3
        linePaint.pathEffect = if (live) null else DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)
        segmentPaint.color = if (live) signalDeep else ink3
        fillPaint.color = Color.argb(36, Color.red(signal), Color.green(signal), Color.blue(signal))
        nodePaint.color = if (live) signal else paper3
        nodeStrokePaint.color = if (live) signalDeep else ruleStrong
        textPaint.color = ink3
        labelPaint.color = ink3

        val left = paddingStart + dp(14f)
        val top = paddingTop + dp(16f)
        val right = width - paddingEnd - dp(14f)
        val bottom = height - paddingBottom - dp(24f)
        val midY = top + (bottom - top) * 0.5f
        val usableW = max(dp(1f), right - left)
        val usableH = max(dp(1f), bottom - top)

        val gridStep = dp(12f)
        var gridX = left
        while (gridX <= right) {
            var gridY = top
            while (gridY <= bottom) {
                canvas.drawPoint(gridX, gridY, gridPaint)
                gridY += gridStep
            }
            gridX += gridStep
        }

        val pathLeft = left + usableW * 0.07f
        val pathRight = right - usableW * 0.07f
        val segmentLeft = left + usableW * 0.34f
        val segmentRight = left + usableW * 0.66f

        gridPaint.pathEffect = null
        gridPaint.color = Color.argb(56, Color.red(ruleStrong), Color.green(ruleStrong), Color.blue(ruleStrong))
        canvas.drawLine(pathLeft, bottom - dp(20f), pathRight, bottom - dp(20f), gridPaint)
        canvas.drawLine(pathLeft, bottom - dp(22f), pathLeft, bottom - dp(18f), gridPaint)
        canvas.drawLine(pathRight, bottom - dp(22f), pathRight, bottom - dp(18f), gridPaint)
        gridPaint.pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(5f)), 0f)
        gridPaint.color = rule

        canvas.drawLine(pathLeft, midY, segmentLeft, midY, linePaint)
        canvas.drawLine(segmentLeft, midY, segmentRight, midY, linePaint)
        canvas.drawLine(segmentRight, midY, pathRight, midY, linePaint)

        val segmentRect = RectF(segmentLeft, midY - dp(9f), segmentRight, midY + dp(9f))
        canvas.drawRect(segmentRect, segmentPaint)

        drawNode(canvas, pathLeft, midY, live)
        drawNode(canvas, pathRight, midY, live)
        if (live) {
            drawPacket(canvas, pathLeft, pathRight, midY, phase, dp(3.5f), 255)
            drawPacket(canvas, pathRight, pathLeft, midY, (phase + 0.66f) % 1f, dp(2.5f), 180)
            drawPacket(canvas, pathLeft, pathRight, midY, (phase + 0.33f) % 1f, dp(2f), 130)
        }

        drawNodeLabel(canvas, pathLeft, midY - dp(24f), "DEVICE", "10.7.0.4", Paint.Align.LEFT)
        drawNodeLabel(canvas, (segmentLeft + segmentRight) / 2f, top + dp(22f), "ENCRYPTED - CHACHA20-POLY1305", "MTU 1420", Paint.Align.CENTER)
        drawNodeLabel(canvas, pathRight, midY - dp(24f), "PEER", "ENDPOINT", Paint.Align.RIGHT)

        canvas.drawText("IN $rxRate", left, height - paddingBottom - dp(7f), textPaint)
        val outText = "OUT $txRate"
        canvas.drawText(outText, right - textPaint.measureText(outText), height - paddingBottom - dp(8f), textPaint)
    }

    private fun drawNode(canvas: Canvas, x: Float, y: Float, isLive: Boolean) {
        if (isLive) {
            canvas.drawCircle(x, y, dp(8f), fillPaint)
        }
        canvas.drawCircle(x, y, dp(4f), nodePaint)
        canvas.drawCircle(x, y, dp(4f), nodeStrokePaint)
    }

    private fun drawPacket(canvas: Canvas, from: Float, to: Float, y: Float, progress: Float, radius: Float, alpha: Int) {
        val x = from + (to - from) * progress
        val oldAlpha = nodePaint.alpha
        nodePaint.alpha = alpha
        canvas.drawCircle(x, y, radius, nodePaint)
        nodePaint.alpha = oldAlpha
    }

    private fun drawNodeLabel(canvas: Canvas, x: Float, y: Float, label: String, sub: String, align: Paint.Align) {
        labelPaint.textAlign = align
        textPaint.textAlign = align
        val clampedX = when (align) {
            Paint.Align.LEFT -> min(x, width - paddingEnd - dp(80f))
            Paint.Align.RIGHT -> max(x, paddingStart + dp(80f))
            else -> x
        }
        canvas.drawText(label, clampedX, y, labelPaint)
        canvas.drawText(sub, clampedX, y + dp(13f), textPaint)
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
