package com.hisabbaki.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hisabbaki.app.data.ReminderRecord
import com.hisabbaki.app.util.Dates
import com.hisabbaki.app.util.Money

class ReminderAdapter : RecyclerView.Adapter<ReminderAdapter.VH>() {

    private var items: List<ReminderRecord> = emptyList()

    fun submit(list: List<ReminderRecord>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val meta: TextView = v.findViewById(R.id.tvReminderMeta)
        val message: TextView = v.findViewById(R.id.tvReminderMsg)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_reminder, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        val ctx = holder.itemView.context
        val channel = ctx.getString(if (r.channel == "SMS") R.string.channel_sms else R.string.channel_whatsapp)
        holder.meta.text = channel + " · " + Dates.formatDateTime(r.createdAt) + " · " + Money.format(r.amountPaise)
        holder.message.text = r.message
    }
}
