package com.hisabbaki.app.util

import android.content.Context
import android.graphics.Bitmap
import com.hisabbaki.app.R
import com.hisabbaki.app.data.BusinessProfile
import com.hisabbaki.app.data.LedgerData
import com.hisabbaki.app.data.TxTypes
import java.io.OutputStream

object PdfExport {

    /** Full ledger statement (oldest first) with opening and closing balance. Blocking. */
    fun ledger(ctx: Context, profile: BusinessProfile, logo: Bitmap?, data: LedgerData, out: OutputStream) {
        val b = PdfBuilder(ctx, profile, logo)
        b.begin()
        val party = data.party
        val supplier = party.isSupplier
        b.title(ctx.getString(if (supplier) R.string.pdf_ledger_supplier else R.string.pdf_ledger_customer))
        b.info(ctx.getString(R.string.col_name), party.name)
        if (party.mobile.isNotBlank()) b.info(ctx.getString(R.string.lbl_mobile), party.mobile)
        if (party.address.isNotBlank()) b.info(ctx.getString(R.string.lbl_address), party.address.replace('\n', ' '))
        b.space(8f)

        fun forYou(balance: Long): Long = if (supplier) -balance else balance

        val rows = ArrayList<List<String>>()
        rows.add(listOf("", ctx.getString(R.string.pdf_opening), "", "", Money.format(forYou(party.openingPaise))))
        for (e in data.entries.reversed()) {
            val tx = e.tx
            val increasing = tx.txType == TxTypes.increase(supplier)
            val label = when (tx.txType) {
                "GAVE" -> R.string.tx_gave
                "GOT" -> R.string.tx_got
                "PURCHASE" -> R.string.tx_purchase
                else -> R.string.tx_payment
            }
            val desc = if (tx.description.isBlank()) ctx.getString(label) else tx.description
            val amount = Money.format(tx.amountPaise)
            rows.add(
                listOf(
                    Dates.formatShortDate(tx.txTime), desc,
                    if (increasing) amount else "", if (increasing) "" else amount,
                    Money.format(forYou(e.runningBalancePaise))
                )
            )
        }
        val footer = listOf(
            "", ctx.getString(R.string.pdf_closing),
            Money.format(data.totalIncreasePaise), Money.format(data.totalDecreasePaise),
            Money.format(party.forYouPaise)
        )
        b.table(
            listOf(
                ctx.getString(R.string.col_date), ctx.getString(R.string.col_description),
                ctx.getString(if (supplier) R.string.tx_purchase else R.string.tx_gave),
                ctx.getString(if (supplier) R.string.tx_payment else R.string.tx_got),
                ctx.getString(R.string.col_balance)
            ),
            listOf(0.17f, 0.31f, 0.18f, 0.18f, 0.2f), 2, rows, footer
        )
        b.space(8f)
        b.note(ctx.getString(R.string.pdf_balance_note))
        b.finish(out)
    }

    /** Any report table (summary, customer-wise, ...). Blocking. */
    fun report(ctx: Context, profile: BusinessProfile, logo: Bitmap?, table: ReportTable, out: OutputStream) {
        val b = PdfBuilder(ctx, profile, logo)
        b.begin()
        b.title(table.title)
        for (info in table.infoLines) b.info(info.first, info.second)
        b.space(8f)
        b.table(
            table.headers, table.weights, table.numericFrom,
            table.rows.map { row -> row.map { it.text } },
            table.footer?.map { it.text }
        )
        b.finish(out)
    }
}
