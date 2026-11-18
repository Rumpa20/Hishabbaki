package com.hisabbaki.app

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.hisabbaki.app.data.StockMovement
import com.hisabbaki.app.util.Dates
import com.hisabbaki.app.util.Qty

class StockMovementAdapter(
    private val unit: String
) : RecyclerView.Adapter<StockMovementAdapter.VH>() {

    private var items: List<StockMovement> = emptyList()

    fun submit(list: List<StockMovement>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val moveType: TextView = v.findViewById(R.id.tvMoveType)
        val qty: TextView = v.findViewById(R.id.tvQty)
        val note: TextView = v.findViewById(R.id.tvNote)
        val balance: TextView = v.findViewById(R.id.tvBalance)
        val time: TextView = v.findViewById(R.id.tvTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_stock_movement, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val ctx = holder.itemView.context

        holder.moveType.text = typeName(ctx, m.moveType)
        val positive = m.qtyMilli >= 0L
        holder.qty.text = (if (positive) "+" else "-") + Qty.format(Math.abs(m.qtyMilli), unit)
        holder.qty.setTextColor(
            ContextCompat.getColor(ctx, if (positive) R.color.get_green else R.color.give_red)
        )
        if (m.note.isEmpty()) {
            holder.note.visibility = View.INVISIBLE
        } else {
            holder.note.visibility = View.VISIBLE
            holder.note.text = m.note
        }
        holder.balance.text = ctx.getString(R.string.balance_label) + " " + Qty.format(m.balanceMilli, unit)
        holder.time.text = Dates.formatDateTime(m.txTime)
    }

    private fun typeName(ctx: Context, moveType: String): String = when (moveType) {
        "OPENING" -> ctx.getString(R.string.move_opening)
        "PURCHASE" -> ctx.getString(R.string.move_purchase)
        "SALE" -> ctx.getString(R.string.move_sale)
        "SALE_RETURN" -> ctx.getString(R.string.move_sale_return)
        "PURCHASE_RETURN" -> ctx.getString(R.string.move_purchase_return)
        else -> ctx.getString(R.string.move_adjustment)
    }
}
