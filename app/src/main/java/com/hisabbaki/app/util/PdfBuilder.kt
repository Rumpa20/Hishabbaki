package com.hisabbaki.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import android.text.TextUtils
import com.hisabbaki.app.R
import com.hisabbaki.app.data.BusinessProfile
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small A4 PDF writer: branded header, info lines, paginated table, footer on every page. */
class PdfBuilder(
    private val ctx: Context,
    private val profile: BusinessProfile,
    private val logo: Bitmap?
) {
    private val pageW = 595
    private val pageH = 842
    private val margin = 36f
    private val contentW = pageW - 2 * margin
    private val rowH = 18f

    private val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var y = 0f
    private var pageNo = 0

    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f
        color = Color.BLACK
    }
    private val bold = TextPaint(text).apply { typeface = Typeface.DEFAULT_BOLD }
    private val heading = TextPaint(text).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = 15f
    }
    private val subHeading = TextPaint(text).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = 13f
        color = Color.rgb(21, 101, 192)
    }
    private val small = TextPaint(text).apply {
        textSize = 8.5f
        color = Color.DKGRAY
    }
    private val linePaint = Paint().apply {
        color = Color.LTGRAY
        strokeWidth = 0.8f
    }
    private val fillPaint = Paint().apply { color = Color.rgb(232, 240, 254) }

    private val bottomLimit: Float
        get() = pageH - margin - 20f

    fun begin() {
        newPage()
        drawHeader()
    }

    private fun newPage() {
        closePage()
        pageNo += 1
        val info = PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create()
        val p = doc.startPage(info)
        page = p
        canvas = p.canvas
        y = margin
    }

    private fun closePage() {
        val p = page ?: return
        val c = canvas ?: return
        val generated = ctx.getString(
            R.string.pdf_generated,
            SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
        )
        c.drawText(generated, margin, pageH - 20f, small)
        val pg = ctx.getString(R.string.pdf_page, pageNo)
        c.drawText(pg, pageW - margin - small.measureText(pg), pageH - 20f, small)
        doc.finishPage(p)
        page = null
        canvas = null
    }

    private fun ellipsized(s: String, paint: TextPaint, width: Float): String =
        TextUtils.ellipsize(s, paint, width, TextUtils.TruncateAt.END).toString()

    private fun drawHeader() {
        val c = canvas ?: return
        var x = margin
        if (logo != null) {
            val size = 44f
            c.drawBitmap(logo, null, RectF(margin, y, margin + size, y + size), null)
            x = margin + size + 10f
        }
        val name = if (profile.name.isBlank()) ctx.getString(R.string.app_name) else profile.name
        val textWidth = pageW - margin - x - 90f
        c.drawText(ellipsized(name, heading, textWidth), x, y + 14f, heading)
        var ly = y + 28f
        if (profile.owner.isNotBlank()) {
            c.drawText(
                ellipsized(ctx.getString(R.string.lbl_owner) + ": " + profile.owner, small, textWidth),
                x, ly, small
            )
            ly += 11f
        }
        if (profile.address.isNotBlank()) {
            c.drawText(ellipsized(profile.address.replace('\n', ' '), small, textWidth), x, ly, small)
            ly += 11f
        }
        val contact = ArrayList<String>()
        if (profile.mobile.isNotBlank()) contact.add(ctx.getString(R.string.lbl_mobile) + ": " + profile.mobile)
        if (profile.gstin.isNotBlank()) contact.add(ctx.getString(R.string.lbl_gstin) + ": " + profile.gstin)
        if (contact.isNotEmpty()) {
            c.drawText(ellipsized(contact.joinToString("  |  "), small, textWidth), x, ly, small)
            ly += 11f
        }
        val brand = ctx.getString(R.string.app_name)
        c.drawText(brand, pageW - margin - small.measureText(brand), y + 10f, small)

        y = Math.max(ly, y + 48f) + 6f
        c.drawLine(margin, y, pageW - margin, y, linePaint)
        y += 16f
    }

    private fun ensure(h: Float) {
        if (page == null) {
            begin()
        } else if (y + h > bottomLimit) {
            newPage()
        }
    }

    fun title(s: String) {
        ensure(26f)
        canvas?.drawText(ellipsized(s, subHeading, contentW), margin, y + 12f, subHeading)
        y += 24f
    }

    fun info(label: String, value: String) {
        ensure(14f)
        val line = if (value.isEmpty()) label else "$label: $value"
        canvas?.drawText(ellipsized(line, text, contentW), margin, y + 9f, text)
        y += 14f
    }

    fun note(s: String) {
        ensure(14f)
        canvas?.drawText(ellipsized(s, small, contentW), margin, y + 9f, small)
        y += 14f
    }

    fun space(h: Float) {
        y += h
    }

    fun table(
        headers: List<String>,
        weights: List<Float>,
        numericFrom: Int,
        rows: List<List<String>>,
        footer: List<String>?
    ) {
        val total = weights.sum()
        val xs = FloatArray(headers.size + 1)
        xs[0] = margin
        for (i in headers.indices) xs[i + 1] = xs[i] + contentW * weights[i] / total

        fun drawRow(cells: List<String>, paint: TextPaint, shaded: Boolean) {
            val c = canvas ?: return
            if (shaded) c.drawRect(margin, y, margin + contentW, y + rowH, fillPaint)
            for (i in headers.indices) {
                val cell = cells.getOrNull(i) ?: ""
                val w = xs[i + 1] - xs[i] - 6f
                val t = ellipsized(cell, paint, w)
                if (i >= numericFrom) {
                    c.drawText(t, xs[i + 1] - 3f - paint.measureText(t), y + 12.5f, paint)
                } else {
                    c.drawText(t, xs[i] + 3f, y + 12.5f, paint)
                }
            }
            c.drawLine(margin, y + rowH, margin + contentW, y + rowH, linePaint)
            y += rowH
        }

        ensure(rowH * 3)
        drawRow(headers, bold, true)
        for (r in rows) {
            if (y + rowH > bottomLimit) {
                newPage()
                drawRow(headers, bold, true)
            }
            drawRow(r, text, false)
        }
        if (footer != null) {
            if (y + rowH > bottomLimit) newPage()
            drawRow(footer, bold, true)
        }
    }

    fun finish(out: OutputStream) {
        if (page == null && pageNo == 0) begin()
        closePage()
        doc.writeTo(out)
        doc.close()
    }
}
