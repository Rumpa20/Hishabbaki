package com.hisabbaki.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.hisabbaki.app.data.Party
import com.hisabbaki.app.util.Money

class PartyAdapter(
    private val onClick: (Party) -> Unit,
    private val onEdit: (Party) -> Unit,
    private val onDelete: (Party) -> Unit
) : RecyclerView.Adapter<PartyAdapter.VH>() {

    private var items: List<Party> = emptyList()

    fun submit(list: List<Party>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: TextView = v.findViewById(R.id.tvAvatar)
        val name: TextView = v.findViewById(R.id.tvName)
        val mobile: TextView = v.findViewById(R.id.tvMobile)
        val balance: TextView = v.findViewById(R.id.tvBalance)
        val balanceLabel: TextView = v.findViewById(R.id.tvBalanceLabel)
        val more: ImageButton = v.findViewById(R.id.btnMore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_party, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        val ctx = holder.itemView.context

        holder.avatar.text = p.name.trim().take(1).uppercase()
        holder.name.text = p.name
        if (p.mobile.isEmpty()) {
            holder.mobile.visibility = View.GONE
        } else {
            holder.mobile.visibility = View.VISIBLE
            holder.mobile.text = p.mobile
        }

        val forYou = p.forYouPaise
        val colorRes: Int
        val labelRes: Int
        if (forYou > 0) {
            colorRes = R.color.get_green
            labelRes = R.string.you_will_get
        } else if (forYou < 0) {
            colorRes = R.color.give_red
            labelRes = R.string.you_will_give
        } else {
            colorRes = R.color.text_secondary
            labelRes = R.string.settled
        }
        holder.balance.text = Money.format(Math.abs(forYou))
        holder.balance.setTextColor(ContextCompat.getColor(ctx, colorRes))
        holder.balanceLabel.setText(labelRes)

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
