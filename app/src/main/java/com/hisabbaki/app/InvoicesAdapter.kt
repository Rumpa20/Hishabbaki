package com.hisabbaki.app

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.hisabbaki.app.data.InvoiceListItem
import com.hisabbaki.app.util.Dates
import com.hisabbaki.app.util.Money

class InvoicesAdapter(
    private val onClick: (InvoiceListItem) -> Unit
) : RecyclerView.Adapter<InvoicesAdapter.VH>() {

    private var items: List<InvoiceListItem> = emptyList()

    fun submit(list: List<InvoiceListItem>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tvInvoiceTitle)
        val sub: TextView = v.findViewById(R.id.tvInvoiceSub)
        val total: TextView = v.findViewById(R.id.tvInvoiceTotal)
        val status: TextView = v.findViewById(R.id.tvInvoiceStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_invoice, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        val ctx = holder.itemView.context

        holder.title.text = e.partyName.ifBlank { Money.format(e.totalPaise) }
        holder.sub.text = typeLabel(ctx, e.invoiceType) + "  •  " + e.invoiceNo + "  •  " +
            Dates.formatDate(e.invoiceDate)
        holder.total.text = Money.format(e.totalPaise)

        val colorRes = when (e.status) {
            "PAID" -> R.color.get_green
            "PARTIAL" -> R.color.primary
            else -> R.color.give_red
        }
        holder.status.text = statusLabel(ctx, e.status)
        holder.status.setTextColor(ContextCompat.getColor(ctx, colorRes))

        holder.itemView.setOnClickListener { onClick(e) }
    }

    fun typeLabel(ctx: Context, type: String): String = typeLabelStatic(ctx, type)

    fun statusLabel(ctx: Context, status: String): String = statusLabelStatic(ctx, status)

    companion object {
        fun typeLabelStatic(ctx: Context, type: String): String = when (type) {
            "SALE" -> ctx.getString(R.string.inv_type_sale)
            "PURCHASE" -> ctx.getString(R.string.inv_type_purchase)
            "PROFORMA" -> ctx.getString(R.string.inv_type_proforma)
            "QUOTATION" -> ctx.getString(R.string.inv_type_quotation)
            "ESTIMATE" -> ctx.getString(R.string.inv_type_estimate)
            "CHALLAN" -> ctx.getString(R.string.inv_type_challan)
            "SALE_RETURN" -> ctx.getString(R.string.inv_type_sale_return)
            "PURCHASE_RETURN" -> ctx.getString(R.string.inv_type_purchase_return)
            "CREDIT_NOTE" -> ctx.getString(R.string.inv_type_credit_note)
            else -> ctx.getString(R.string.inv_type_debit_note)
        }

        fun statusLabelStatic(ctx: Context, status: String): String = when (status) {
            "PAID" -> ctx.getString(R.string.invoice_status_paid)
            "PARTIAL" -> ctx.getString(R.string.invoice_status_partial)
            else -> ctx.getString(R.string.invoice_status_unpaid)
        }
    }
}
