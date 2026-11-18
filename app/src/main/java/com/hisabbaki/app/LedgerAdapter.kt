package com.hisabbaki.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.hisabbaki.app.data.LedgerEntry
import com.hisabbaki.app.data.Tx
import com.hisabbaki.app.data.TxTypes
import com.hisabbaki.app.util.Money
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LedgerAdapter(
    private val isSupplier: Boolean,
    private val onClick: (Tx) -> Unit,
    private val onEdit: (Tx) -> Unit,
    private val onDelete: (Tx) -> Unit
) : RecyclerView.Adapter<LedgerAdapter.VH>() {

    private var items: List<LedgerEntry> = emptyList()
    private val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())

    fun submit(list: List<LedgerEntry>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val desc: TextView = v.findViewById(R.id.tvEntryDesc)
        val meta: TextView = v.findViewById(R.id.tvEntryMeta)
        val amount: TextView = v.findViewById(R.id.tvEntryAmount)
        val balance: TextView = v.findViewById(R.id.tvEntryBalance)
        val more: ImageButton = v.findViewById(R.id.btnEntryMore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_ledger_entry, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        val tx = entry.tx
        val ctx = holder.itemView.context

        val typeLabel = when (tx.txType) {
            "GAVE" -> R.string.tx_gave
            "GOT" -> R.string.tx_got
            "PURCHASE" -> R.string.tx_purchase
            else -> R.string.tx_payment
        }
        val methodLabel = when (tx.paymentMethod) {
            "UPI" -> R.string.pm_upi
            "BANK" -> R.string.pm_bank
            "OTHER" -> R.string.pm_other
            else -> R.string.pm_cash
        }

        holder.desc.text = if (tx.description.isBlank()) ctx.getString(typeLabel) else tx.description
        var meta = dateFormat.format(Date(tx.txTime)) + " · " + ctx.getString(typeLabel) +
            " · " + ctx.getString(methodLabel)
        if (tx.referenceNo.isNotBlank()) meta += " · " + tx.referenceNo
        holder.meta.text = meta

        val increasing = tx.txType == TxTypes.increase(isSupplier)
        holder.amount.text = Money.format(tx.amountPaise)
        holder.amount.setTextColor(
            ContextCompat.getColor(ctx, if (increasing) R.color.give_red else R.color.get_green)
        )

        val forYou = if (isSupplier) -entry.runningBalancePaise else entry.runningBalancePaise
        val balanceColor = when {
            forYou > 0 -> R.color.get_green
            forYou < 0 -> R.color.give_red
            else -> R.color.text_secondary
        }
        holder.balance.text = ctx.getString(R.string.balance_short) + ": " + Money.format(Math.abs(forYou))
        holder.balance.setTextColor(ContextCompat.getColor(ctx, balanceColor))

        holder.itemView.setOnClickListener { onClick(tx) }
        holder.more.setOnClickListener { anchor ->
            val popup = PopupMenu(ctx, anchor)
            popup.menuInflater.inflate(R.menu.customer_item_menu, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_edit -> onEdit(tx)
                    R.id.action_delete -> onDelete(tx)
                }
                true
            }
            popup.show()
        }
    }
}
