/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.vpn0wn.android.R
import com.vpn0wn.android.provisioning.VpsDeployServer
import com.vpn0wn.android.provisioning.VpsDeployServerStatus

class VpsTopologyView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        fontFeatureSettings = "ss01, zero, tnum"
    }
    private val monoTypeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular) ?: Typeface.MONOSPACE
    private val metadataLabels = listOf(context.getString(R.string.vpn0wn_label_server),
        context.getString(R.string.vpn0wn_label_endpoint), context.getString(R.string.vpn0wn_metric_cidr))
    private val deviceSublabel = context.getString(R.string.vpn0wn_hint_device).lowercase()
    private val wanSublabel = context.getString(R.string.vpn0wn_node_internet)
    private var metadataValues = emptyList<String>()
    private var serverSublabel = ""
    private var server: VpsDeployServer? = null
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    fun bind(server: VpsDeployServer) {
        this.server = server
        fitText()
        syncAnimator()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitText()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
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
        val server = server ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1).toFloat()
        paint.color = color(R.color.vpn0wn_rule)
        val gridStep = dp(12)
        for (x in 0..w.toInt() step gridStep) {
            for (y in 0..(h * 0.64f).toInt() step gridStep) canvas.drawPoint(x.toFloat(), y.toFloat(), paint)
        }
        val y = h * 0.32f
        val xs = listOf(w * 0.12f, w * 0.5f, w * 0.88f)
        val lineColor = color(if (server.status == VpsDeployServerStatus.ATTENTION) R.color.vpn0wn_error else if (server.status == VpsDeployServerStatus.ONLINE) R.color.vpn0wn_signal_deep else R.color.vpn0wn_rule_strong)
        paint.color = lineColor
        paint.strokeWidth = dp(1).toFloat()
        val dash = floatArrayOf(6f * density, 6f * density)
        val leftPhase = phase * 24f * density
        val rightPhase = when (server.status) {
            VpsDeployServerStatus.ONLINE -> phase * 12f * density
            VpsDeployServerStatus.ATTENTION -> phase * 14f * density
            else -> 0f
        }
        paint.pathEffect = DashPathEffect(dash, leftPhase)
        canvas.drawLine(xs[0] + dp(24), y, xs[1] - dp(28), y, paint)
        paint.pathEffect = DashPathEffect(dash, rightPhase)
        canvas.drawLine(xs[1] + dp(28), y, xs[2] - dp(24), y, paint)
        paint.pathEffect = null
        node(canvas, xs[0], y, context.getString(R.string.vpn0wn_node_device), deviceSublabel, server.status == VpsDeployServerStatus.ONLINE, R.color.vpn0wn_signal_deep, false)
        node(canvas, xs[1], y, context.getString(R.string.vpn0wn_node_vps), serverSublabel, server.status != VpsDeployServerStatus.OFFLINE, server.status.colorRes, true)
        node(canvas, xs[2], y, context.getString(R.string.vpn0wn_node_wan), wanSublabel, server.status == VpsDeployServerStatus.ONLINE, R.color.vpn0wn_signal_deep, false)
        val top = h * 0.66f
        val cellW = w / 3f
        metadataLabels.forEachIndexed { index, label ->
            paint.style = Paint.Style.FILL
            paint.typeface = monoTypeface
            paint.textSize = dp(9).toFloat()
            paint.color = color(R.color.vpn0wn_ink_3)
            canvas.drawText(label, cellW * index + dp(10), top + dp(18), paint)
            paint.textSize = dp(12).toFloat()
            paint.color = color(R.color.vpn0wn_ink)
            canvas.drawText(metadataValues[index], cellW * index + dp(10), top + dp(38), paint)
        }
    }

    // Keep the existing diagram geometry and avoid text-fitting allocations during animation.
    @Suppress("MagicNumber")
    private fun fitText() {
        val server = server ?: return
        paint.typeface = monoTypeface
        paint.textSize = dp(12).toFloat()
        val cellWidth = (width / 3f - dp(20)).coerceAtLeast(0f)
        metadataValues = listOf(server.name, server.endpoint ?: "—", server.cidr).map {
            TextUtils.ellipsize(it, paint, cellWidth, TextUtils.TruncateAt.END).toString()
        }
        paint.textSize = dp(7).toFloat()
        val center = width * 0.5f
        val leftEdge = width * 0.12f + paint.measureText(deviceSublabel) / 2f
        val rightEdge = width * 0.88f - paint.measureText(wanSublabel) / 2f
        val nameWidth = (2f * (minOf(center - leftEdge, rightEdge - center) - dp(8))).coerceAtLeast(0f)
        serverSublabel = TextUtils.ellipsize(server.name, paint, nameWidth, TextUtils.TruncateAt.END).toString()
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun node(canvas: Canvas, x: Float, y: Float, label: String, sub: String, active: Boolean, colorRes: Int, big: Boolean) {
        val r = dp(if (big) 18 else 14).toFloat()
        paint.style = Paint.Style.FILL
        paint.color = color(if (active) R.color.vpn0wn_paper_3 else R.color.vpn0wn_paper)
        canvas.drawCircle(x, y, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1).toFloat()
        paint.color = color(if (active) colorRes else R.color.vpn0wn_rule_strong)
        canvas.drawCircle(x, y, r, paint)
        if (active && big) {
            paint.color = color(colorRes)
            paint.alpha = 90
            canvas.drawCircle(x, y, r + dp(5), paint)
            paint.alpha = 255
        }
        paint.style = Paint.Style.FILL
        canvas.drawCircle(x, y, dp(3).toFloat(), paint)
        paint.typeface = monoTypeface
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = dp(7).toFloat()
        canvas.drawText(label, x, y - r - dp(7), paint)
        paint.color = color(R.color.vpn0wn_ink_3)
        canvas.drawText(sub, x, y + r + dp(12), paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun color(res: Int) = ContextCompat.getColor(context, res)
    private fun dp(value: Int) = (value * density).toInt()

    private fun syncAnimator() {
        if (!isAttachedToWindow) return
        if (server?.status == VpsDeployServerStatus.OFFLINE) {
            animator.cancel()
            phase = 0f
        } else if (!animator.isStarted) {
            animator.start()
        }
    }
}

class VpsTerminalStreamView(context: Context) : TextView(context) {
    private var lines = emptyList<String>()
    private var visible = 0
    private var running = false
    private val tick = object : Runnable {
        override fun run() {
            if (!running || visible >= lines.size) return
            visible++
            text = lines.take(visible).joinToString("\n")
            postDelayed(this, LINE_REVEAL_INTERVAL_MS)
        }
    }

    init {
        typeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular) ?: Typeface.MONOSPACE
        fontFeatureSettings = "ss01, zero, tnum"
        // Literal typography and spacing belong to this terminal presentation.
        @Suppress("MagicNumber")
        val terminalTextSize = 10f
        textSize = terminalTextSize
        setTextColor(ContextCompat.getColor(context, R.color.vpn0wn_ink_2))
        setBackgroundColor(TERMINAL_BACKGROUND_COLOR)
        @Suppress("MagicNumber") val padH = dp(12)
        @Suppress("MagicNumber") val padV = dp(10)
        setPadding(padH, padV, padH, padV)
    }

    fun setLines(lines: List<String>, running: Boolean) {
        removeCallbacks(tick)
        this.lines = lines
        this.running = running
        visible = if (running) 1 else lines.size
        text = lines.take(visible).joinToString("\n")
        if (running) postDelayed(tick, LINE_REVEAL_INTERVAL_MS)
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val LINE_REVEAL_INTERVAL_MS = 340L
        val TERMINAL_BACKGROUND_COLOR = Color.rgb(7, 13, 22)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

class VpsSwitchGlyphView(context: Context) : View(context) {
    var checked = false
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        paint.style = Paint.Style.FILL
        paint.color = ContextCompat.getColor(context, if (checked) R.color.vpn0wn_signal else R.color.vpn0wn_paper_3)
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1).toFloat()
        paint.color = ContextCompat.getColor(context, if (checked) R.color.vpn0wn_signal_deep else R.color.vpn0wn_rule_strong)
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, paint)
        paint.style = Paint.Style.FILL
        paint.color = ContextCompat.getColor(context, if (checked) R.color.vpn0wn_paper else R.color.vpn0wn_ink_2)
        val cx = if (checked) width - r else r
        canvas.drawCircle(cx, r, r - dp(3), paint)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

class VpsCornerLayout(context: Context) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1).toFloat()
        color = ContextCompat.getColor(context, R.color.vpn0wn_ink_3)
    }

    init {
        setWillNotDraw(false)
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = dp(8).toFloat()
        val right = width.toFloat()
        val bottom = height.toFloat()
        canvas.drawLine(0f, 0f, size, 0f, paint)
        canvas.drawLine(0f, 0f, 0f, size, paint)
        canvas.drawLine(right - size, bottom, right, bottom, paint)
        canvas.drawLine(right, bottom - size, right, bottom, paint)
    }

    private fun dp(value: Int) = (value * density).toInt()
}
