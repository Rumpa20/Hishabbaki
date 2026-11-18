package com.hisabbaki.app

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class TableRowViews(val view: LinearLayout, val cells: List<TextView>)

object TableRowFactory {
    fun create(ctx: Context, weights: List<Float>, numericFrom: Int, bold: Boolean): TableRowViews {
        val d = ctx.resources.displayMetrics.density
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        row.setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
        if (bold) row.setBackgroundColor(ContextCompat.getColor(ctx, R.color.primary_container))

        val cells = ArrayList<TextView>()
        for (i in weights.indices) {
            val tv = TextView(ctx)
            tv.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weights[i])
            tv.textSize = 12f
            tv.maxLines = 2
            tv.ellipsize = TextUtils.TruncateAt.END
            tv.gravity = if (i >= numericFrom) Gravity.END else Gravity.START
            tv.setPadding((2 * d).toInt(), 0, (2 * d).toInt(), 0)
            tv.setTextColor(
                ContextCompat.getColor(ctx, if (bold) R.color.on_primary_container else R.color.text_primary)
            )
            if (bold) tv.setTypeface(null, Typeface.BOLD)
            row.addView(tv)
            cells.add(tv)
        }
        return TableRowViews(row, cells)
    }
}

class TableAdapter(
    private val weights: List<Float>,
    private val numericFrom: Int,
    private val rows: List<List<String>>,
    private val footer: List<String>?
) : RecyclerView.Adapter<TableAdapter.VH>() {

    class VH(val row: TableRowViews) : RecyclerView.ViewHolder(row.view)

    override fun getItemCount(): Int = rows.size + (if (footer != null) 1 else 0)

    override fun getItemViewType(position: Int): Int = if (position < rows.size) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(TableRowFactory.create(parent.context, weights, numericFrom, viewType == 1))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cells: List<String> = if (position < rows.size) rows[position] else (footer ?: emptyList())
        for (i in holder.row.cells.indices) {
            holder.row.cells[i].text = cells.getOrNull(i) ?: ""
        }
    }
}
