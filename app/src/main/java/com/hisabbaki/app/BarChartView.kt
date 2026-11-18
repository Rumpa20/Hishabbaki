package com.hisabbaki.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.hisabbaki.app.util.Money

/** Grouped bar chart: two bars (red = series A, green = series B) per label. */
class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var labels: List<String> = emptyList()
    private var seriesA: LongArray = LongArray(0)
    private var seriesB: LongArray = LongArray(0)

    private val density = resources.displayMetrics.density
    private val paintA = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.give_red)
    }
    private val paintB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.get_green)
    }
    private val axis = Paint().apply {
        color = ContextCompat.getColor(context, R.color.divider)
        strokeWidth = 1f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 10f * density
    }

    fun setData(labels: List<String>, a: LongArray, b: LongArray) {
        this.labels = labels
        this.seriesA = a
        this.seriesB = b
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = labels.size
        if (n == 0 || seriesA.size != n || seriesB.size != n) return

        val left = 8f * density
        val right = width - 8f * density
        val top = 22f * density
        val bottom = height - 22f * density
        if (right <= left || bottom <= top) return

        var maxValue = 1L
        for (i in 0 until n) {
            if (seriesA[i] > maxValue) maxValue = seriesA[i]
            if (seriesB[i] > maxValue) maxValue = seriesB[i]
        }

        canvas.drawText(Money.format(maxValue), left, 12f * density, textPaint)
        canvas.drawLine(left, bottom, right, bottom, axis)

        val slot = (right - left) / n
        val barW = Math.min(slot * 0.36f, 14f * density)
        val plotH = bottom - top
        val step = Math.max(1, Math.ceil(n / 8.0).toInt())

        for (i in 0 until n) {
            val cx = left + slot * (i + 0.5f)
            val hA = plotH * seriesA[i].toFloat() / maxValue.toFloat()
            val hB = plotH * seriesB[i].toFloat() / maxValue.toFloat()
            if (hA > 0f) canvas.drawRoundRect(RectF(cx - barW - 1f, bottom - hA, cx - 1f, bottom), 2f, 2f, paintA)
            if (hB > 0f) canvas.drawRoundRect(RectF(cx + 1f, bottom - hB, cx + barW + 1f, bottom), 2f, 2f, paintB)
            if (i % step == 0) {
                val label = labels[i]
                canvas.drawText(label, cx - textPaint.measureText(label) / 2f, bottom + 14f * density, textPaint)
            }
        }
    }
}
