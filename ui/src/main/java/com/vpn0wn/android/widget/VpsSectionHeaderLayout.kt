package com.vpn0wn.android.widget

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

internal class VpsSectionHeaderLayout(
    context: Context,
    private val index: View,
    private val title: View,
    private val divider: View,
    private val hint: View,
) : ViewGroup(context) {
    private var stacked = false
    private var titleRowHeight = 0
    private val wrappedRowGap = (WRAPPED_ROW_GAP_DP * resources.displayMetrics.density).toInt()

    init {
        listOf(index, title, divider, hint).forEach { addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        listOf(index, title, hint).forEach { it.measure(unspecified, unspecified) }
        val naturalWidth = index.measuredWidth + title.measuredWidth + hint.measuredWidth
        val width = resolveSize(naturalWidth + paddingLeft + paddingRight, widthMeasureSpec)
        val contentWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        stacked = naturalWidth > contentWidth
        if (stacked) {
            index.measure(atMost(contentWidth), unspecified)
            val textWidth = (contentWidth - index.measuredWidth).coerceAtLeast(0)
            title.measure(atMost(textWidth), unspecified)
            hint.measure(atMost(textWidth), unspecified)
        }
        val dividerWidth = (contentWidth - index.measuredWidth - title.measuredWidth -
            if (stacked) 0 else hint.measuredWidth).coerceAtLeast(0)
        divider.measure(MeasureSpec.makeMeasureSpec(dividerWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(resources.displayMetrics.density.toInt().coerceAtLeast(1), MeasureSpec.EXACTLY))
        titleRowHeight = max(index.measuredHeight, title.measuredHeight)
        val contentHeight = if (stacked) titleRowHeight + wrappedRowGap + hint.measuredHeight
            else max(titleRowHeight, hint.measuredHeight)
        setMeasuredDimension(width, resolveSize(contentHeight + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rowHeight = if (stacked) titleRowHeight else max(titleRowHeight, hint.measuredHeight)
        place(index, 0, paddingTop + (rowHeight - index.measuredHeight) / 2)
        place(title, index.measuredWidth, paddingTop + (rowHeight - title.measuredHeight) / 2)
        place(divider, index.measuredWidth + title.measuredWidth, paddingTop + (rowHeight - divider.measuredHeight) / 2)
        if (stacked) {
            place(hint, index.measuredWidth, paddingTop + rowHeight + wrappedRowGap)
        } else {
            place(hint, width - paddingLeft - paddingRight - hint.measuredWidth,
                paddingTop + (rowHeight - hint.measuredHeight) / 2)
        }
    }

    private fun place(view: View, start: Int, top: Int) {
        val left = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - paddingRight - start - view.measuredWidth
            else paddingLeft + start
        view.layout(left, top, left + view.measuredWidth, top + view.measuredHeight)
    }

    private fun atMost(width: Int) = MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST)

    private companion object {
        const val WRAPPED_ROW_GAP_DP = 4
    }
}
