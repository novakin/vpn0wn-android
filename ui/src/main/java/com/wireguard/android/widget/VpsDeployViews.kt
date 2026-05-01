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
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.wireguard.android.R
import com.wireguard.android.provisioning.VpsDeployServer
import com.wireguard.android.provisioning.VpsDeployServerStatus

class VpsTopologyView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val monoTypeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular) ?: Typeface.MONOSPACE
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
        syncAnimator()
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
        val server = server ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1).toFloat()
        paint.color = color(R.color.tunnl_rule)
        val gridStep = dp(12)
        for (x in 0..w.toInt() step gridStep) {
            for (y in 0..(h * 0.64f).toInt() step gridStep) canvas.drawPoint(x.toFloat(), y.toFloat(), paint)
        }
        val y = h * 0.32f
        val xs = listOf(w * 0.12f, w * 0.5f, w * 0.88f)
        val lineColor = color(if (server.status == VpsDeployServerStatus.ATTENTION) R.color.tunnl_error else if (server.status == VpsDeployServerStatus.ONLINE) R.color.tunnl_signal_deep else R.color.tunnl_rule_strong)
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
        node(canvas, xs[0], y, context.getString(R.string.tunnl_node_device), context.getString(R.string.tunnl_hint_device).lowercase(), server.status == VpsDeployServerStatus.ONLINE, R.color.tunnl_signal_deep, false)
        node(canvas, xs[1], y, context.getString(R.string.tunnl_node_vps), server.name, server.status != VpsDeployServerStatus.OFFLINE, server.status.colorRes, true)
        node(canvas, xs[2], y, context.getString(R.string.tunnl_node_wan), context.getString(R.string.tunnl_node_internet), server.status == VpsDeployServerStatus.ONLINE, R.color.tunnl_signal_deep, false)
        val top = h * 0.66f
        val cellW = w / 3f
        listOf(context.getString(R.string.tunnl_label_server) to server.name, context.getString(R.string.tunnl_label_endpoint) to server.endpoint, context.getString(R.string.tunnl_metric_cidr) to server.cidr).forEachIndexed { index, pair ->
            paint.style = Paint.Style.FILL
            paint.typeface = monoTypeface
            paint.textSize = dp(9).toFloat()
            paint.color = color(R.color.tunnl_ink_3)
            canvas.drawText(pair.first, cellW * index + dp(10), top + dp(18), paint)
            paint.textSize = dp(12).toFloat()
            paint.color = color(R.color.tunnl_ink)
            canvas.drawText(pair.second, cellW * index + dp(10), top + dp(38), paint)
        }
    }

    private fun node(canvas: Canvas, x: Float, y: Float, label: String, sub: String, active: Boolean, colorRes: Int, big: Boolean) {
        val r = dp(if (big) 18 else 14).toFloat()
        paint.style = Paint.Style.FILL
        paint.color = color(if (active) R.color.tunnl_paper_3 else R.color.tunnl_paper)
        canvas.drawCircle(x, y, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1).toFloat()
        paint.color = color(if (active) colorRes else R.color.tunnl_rule_strong)
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
        paint.color = color(R.color.tunnl_ink_3)
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

class VpsQrCodeView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val qrWriter = QRCodeWriter()
    private var matrix: BitMatrix? = null

    fun bind(value: String) {
        matrix = try {
            qrWriter.encode(
                value,
                BarcodeFormat.QR_CODE,
                QR_SIZE,
                QR_SIZE,
                mapOf(
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN to 1,
                ),
            )
        } catch (_: WriterException) {
            null
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val pad = dp(16).toFloat()
        val matrix = matrix
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(249, 251, 255)
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), dp(8).toFloat(), dp(8).toFloat(), paint)
        if (matrix == null) return
        val cell = minOf((width - pad * 2) / matrix.width, (height - pad * 2) / matrix.height)
        val leftOffset = (width - cell * matrix.width) / 2f
        val topOffset = (height - cell * matrix.height) / 2f
        paint.color = Color.rgb(13, 25, 42)
        for (row in 0 until matrix.height) {
            for (col in 0 until matrix.width) {
                if (!matrix[col, row]) continue
                val left = leftOffset + col * cell
                val top = topOffset + row * cell
                canvas.drawRoundRect(RectF(left, top, left + cell - 1, top + cell - 1), 2f, 2f, paint)
            }
        }
    }

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        private const val QR_SIZE = 29
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
            postDelayed(this, 340)
        }
    }

    init {
        typeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular) ?: Typeface.MONOSPACE
        textSize = 10f
        setTextColor(ContextCompat.getColor(context, R.color.tunnl_ink_2))
        setBackgroundColor(Color.rgb(7, 13, 22))
        val padH = dp(12)
        val padV = dp(10)
        setPadding(padH, padV, padH, padV)
    }

    fun setLines(lines: List<String>, running: Boolean) {
        removeCallbacks(tick)
        this.lines = lines
        this.running = running
        visible = if (running) 1 else lines.size
        text = lines.take(visible).joinToString("\n")
        if (running) postDelayed(tick, 340)
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(tick)
        super.onDetachedFromWindow()
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

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        paint.style = Paint.Style.FILL
        paint.color = ContextCompat.getColor(context, if (checked) R.color.tunnl_signal else R.color.tunnl_paper_3)
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1).toFloat()
        paint.color = ContextCompat.getColor(context, if (checked) R.color.tunnl_signal_deep else R.color.tunnl_rule_strong)
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, paint)
        paint.style = Paint.Style.FILL
        paint.color = ContextCompat.getColor(context, if (checked) R.color.tunnl_paper else R.color.tunnl_ink_2)
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
        color = ContextCompat.getColor(context, R.color.tunnl_ink_3)
    }

    init {
        setWillNotDraw(false)
    }

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
