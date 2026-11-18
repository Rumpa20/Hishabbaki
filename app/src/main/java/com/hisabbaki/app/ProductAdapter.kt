package com.hisabbaki.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.Context
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.hisabbaki.app.data.Product
import com.hisabbaki.app.util.Money
import com.hisabbaki.app.util.Qty

class ProductAdapter(
    private val onClick: (Product) -> Unit,
    private val onEdit: (Product) -> Unit,
    private val onDelete: (Product) -> Unit
) : RecyclerView.Adapter<ProductAdapter.VH>() {

    private var items: List<Product> = emptyList()

    fun submit(list: List<Product>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: TextView = v.findViewById(R.id.tvAvatar)
        val name: TextView = v.findViewById(R.id.tvName)
        val sub: TextView = v.findViewById(R.id.tvSub)
        val stock: TextView = v.findViewById(R.id.tvStock)
        val price: TextView = v.findViewById(R.id.tvPrice)
        val more: ImageButton = v.findViewById(R.id.btnMore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_product, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        val ctx = holder.itemView.context

        holder.avatar.text = p.name.trim().take(1).uppercase()
        holder.name.text = p.name

        val subParts = ArrayList<String>()
        if (p.sku.isNotEmpty()) subParts.add(p.sku)
        if (p.barcode.isNotEmpty()) subParts.add(p.barcode)
        if (subParts.isEmpty()) {
            holder.sub.visibility = View.GONE
        } else {
            holder.sub.visibility = View.VISIBLE
            holder.sub.text = subParts.joinToString("  •  ")
        }

        when {
            p.isOutOfStock -> {
                holder.stock.text = ctx.getString(R.string.out_of_stock_badge)
                holder.stock.setTextColor(ContextCompat.getColor(ctx, R.color.give_red))
            }
            p.isLowStock -> {
                holder.stock.text = Qty.format(p.stockMilli, p.unit)
                holder.stock.setTextColor(ContextCompat.getColor(ctx, R.color.give_red))
            }
            else -> {
                holder.stock.text = Qty.format(p.stockMilli, p.unit)
                holder.stock.setTextColor(ContextCompat.getColor(ctx, R.color.get_green))
            }
        }

        if (p.sellingPricePaise > 0L) {
            holder.price.visibility = View.VISIBLE
            holder.price.text = Money.format(p.sellingPricePaise)
        } else {
            holder.price.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onClick(p) }
        holder.more.setOnClickListener { anchor ->
            val popup = PopupMenu(ctx, anchor)
            popup.menuInflater.inflate(R.menu.customer_item_menu, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_edit -> onEdit(p)
                    R.id.action_delete -> onDelete(p)
                }
                true
            }
            popup.show()
        }
    }
}
