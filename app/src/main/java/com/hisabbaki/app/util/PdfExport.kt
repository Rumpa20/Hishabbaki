package com.hisabbaki.app.util

import android.content.Context
import android.graphics.Bitmap
import com.hisabbaki.app.R
import com.hisabbaki.app.data.BusinessProfile
import com.hisabbaki.app.data.InvoiceDetail
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

    /** Invoice / quotation / challan etc. as a branded A4 PDF. Blocking. */
    fun invoice(ctx: Context, profile: BusinessProfile, logo: Bitmap?, detail: InvoiceDetail, out: OutputStream) {
        val b = PdfBuilder(ctx, profile, logo)
        val inv = detail.invoice
        b.begin()
        b.title(typeTitle(ctx, inv.invoiceType, inv.isGst))
        b.info(ctx.getString(R.string.col_no), inv.invoiceNo)
        b.info(ctx.getString(R.string.invoice_date), Dates.formatDate(inv.invoiceDate))
        if (inv.dueDate > 0) b.info(ctx.getString(R.string.due_date), Dates.formatDate(inv.dueDate))
        b.info(ctx.getString(R.string.party), inv.partyName)
        if (inv.partyMobile.isNotBlank()) b.info(ctx.getString(R.string.lbl_mobile), inv.partyMobile)
        if (inv.partyAddress.isNotBlank()) b.info(ctx.getString(R.string.lbl_address), inv.partyAddress.replace('\n', ' '))
        if (inv.isGst && inv.placeOfSupply.isNotBlank()) {
            b.info(ctx.getString(R.string.place_of_supply), inv.placeOfSupply)
            if (profile.gstin.isNotBlank()) b.info("GSTIN", profile.gstin)
        }
        b.space(6f)

        val rows = ArrayList<List<String>>()
        for (item in detail.items) {
            val qty = java.math.BigDecimal.valueOf(item.qtyMilli, 3).stripTrailingZeros().toPlainString() +
                if (item.unit.isBlank()) "" else " " + item.unit
            val taxLabel = if (item.taxRateBp > 0) (item.taxRateBp / 100).toString() + "%" else "-"
            rows.add(
                listOf(
                    item.name, qty, Money.format(item.unitPricePaise, false),
                    Money.format(item.discountPaise, false), taxLabel,
                    Money.format(item.lineTotalPaise - item.cgstPaise - item.sgstPaise - item.igstPaise, false),
                    Money.format(item.lineTotalPaise, false)
                )
            )
        }
        b.table(
            listOf(
                ctx.getString(R.string.col_item), ctx.getString(R.string.qty_label),
                ctx.getString(R.string.rate), ctx.getString(R.string.col_discount),
                ctx.getString(R.string.tax), ctx.getString(R.string.taxable), ctx.getString(R.string.col_amount)
            ),
            listOf(0.26f, 0.12f, 0.13f, 0.12f, 0.08f, 0.14f, 0.15f), 2, rows, null
        )

        b.space(8f)
        var cgst = 0L
        var sgst = 0L
        var igst = 0L
        for (item in detail.items) {
            cgst += item.cgstPaise
            sgst += item.sgstPaise
            igst += item.igstPaise
        }
        b.info(ctx.getString(R.string.subtotal), Money.format(inv.subtotalPaise - inv.discountPaise))
        if (inv.discountPaise > 0) b.info(ctx.getString(R.string.col_discount), "-" + Money.format(inv.discountPaise))
        if (inv.isGst && inv.taxPaise > 0) {
            if (igst > 0) {
                b.info(ctx.getString(R.string.igst), Money.format(igst))
            } else {
                b.info(ctx.getString(R.string.cgst), Money.format(cgst))
                b.info(ctx.getString(R.string.sgst), Money.format(sgst))
            }
        }
        b.info(ctx.getString(R.string.grand_total), Money.format(inv.totalPaise))
        b.info(ctx.getString(R.string.paid), Money.format(inv.paidPaise))
        b.info(ctx.getString(R.string.balance_due), Money.format(inv.totalPaise - inv.paidPaise))
        if (inv.paymentTerms.isNotBlank()) b.info(ctx.getString(R.string.payment_terms), inv.paymentTerms)
        if (inv.notes.isNotBlank()) b.note(inv.notes)
        b.finish(out)
    }

    private fun typeTitle(ctx: Context, type: String, gst: Boolean): String {
        val res = when (type) {
            "SALE" -> if (gst) R.string.pdf_tax_invoice else R.string.pdf_invoice
            "PURCHASE" -> R.string.inv_type_purchase
            "PROFORMA" -> R.string.inv_type_proforma
            "QUOTATION" -> R.string.inv_type_quotation
            "ESTIMATE" -> R.string.inv_type_estimate
            "CHALLAN" -> R.string.inv_type_challan
            "SALE_RETURN" -> R.string.inv_type_sale_return
            "PURCHASE_RETURN" -> R.string.inv_type_purchase_return
            "CREDIT_NOTE" -> R.string.inv_type_credit_note
            else -> R.string.inv_type_debit_note
        }
        return ctx.getString(res)
    }
}
