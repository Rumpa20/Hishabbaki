package com.hisabbaki.app

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hisabbaki.app.data.Expense
import com.hisabbaki.app.util.Dates
import com.hisabbaki.app.util.Money

class ExpenseAdapter(
    private val onClick: (Expense) -> Unit,
    private val onDelete: (Expense) -> Unit
) : RecyclerView.Adapter<ExpenseAdapter.VH>() {

    private var items: List<Expense> = emptyList()

    fun submit(list: List<Expense>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: TextView = v.findViewById(R.id.tvAvatar)
        val title: TextView = v.findViewById(R.id.tvTitle)
        val sub: TextView = v.findViewById(R.id.tvSub)
        val amount: TextView = v.findViewById(R.id.tvAmount)
        val date: TextView = v.findViewById(R.id.tvDate)
        val more: ImageButton = v.findViewById(R.id.btnMore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_expense, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        val ctx = holder.itemView.context

        holder.avatar.text = e.title.trim().take(1).uppercase()
        holder.title.text = e.title
        val subParts = ArrayList<String>()
        if (e.categoryName.isNotEmpty()) subParts.add(e.categoryName)
        if (e.recurrence != "NONE") subParts.add(recLabel(ctx, e.recurrence))
        if (subParts.isEmpty()) {
            holder.sub.visibility = View.GONE
        } else {
            holder.sub.visibility = View.VISIBLE
            holder.sub.text = subParts.joinToString("  •  ")
        }
        holder.amount.text = "-" + Money.format(e.amountPaise)
        holder.date.text = Dates.formatDate(e.expenseDate)

        holder.itemView.setOnClickListener { onClick(e) }
        holder.more.setOnClickListener { anchor ->
            val popup = PopupMenu(ctx, anchor)
            popup.menuInflater.inflate(R.menu.customer_item_menu, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_edit -> onClick(e)
                    R.id.action_delete -> onDelete(e)
                }
                true
            }
            popup.show()
        }
    }

    private fun recLabel(ctx: Context, rec: String): String = when (rec) {
        "DAILY" -> ctx.getString(R.string.recur_daily)
        "WEEKLY" -> ctx.getString(R.string.recur_weekly)
        "MONTHLY" -> ctx.getString(R.string.recur_monthly)
        else -> ""
    }
}
