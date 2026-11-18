package com.hisabbaki.app.util

import android.content.Context
import com.hisabbaki.app.R
import com.hisabbaki.app.data.Repository
import com.hisabbaki.app.data.TxRow

object ReportBuilder {
    const val SUMMARY = "SUMMARY"
    const val CUSTOMER_WISE = "CUSTOMER_WISE"
    const val SUPPLIER_WISE = "SUPPLIER_WISE"
    const val CREDIT = "CREDIT"
    const val DEBIT = "DEBIT"
    const val COLLECTION = "COLLECTION"
    const val OUTSTANDING = "OUTSTANDING"

    fun titleRes(type: String): Int = when (type) {
        CUSTOMER_WISE -> R.string.report_customer_wise
        SUPPLIER_WISE -> R.string.report_supplier_wise
        CREDIT -> R.string.report_credit
        DEBIT -> R.string.report_debit
        COLLECTION -> R.string.report_collection
        OUTSTANDING -> R.string.report_outstanding
        else -> R.string.report_summary
    }

    /** Blocking. Call from a background thread. */
    fun build(ctx: Context, repo: Repository, type: String, from: Long, to: Long): ReportTable {
        val title = ctx.getString(titleRes(type))
        return when (type) {
            CUSTOMER_WISE -> partyWise(ctx, repo, title, false, from, to)
            SUPPLIER_WISE -> partyWise(ctx, repo, title, true, from, to)
            CREDIT -> txList(ctx, repo, title, "GAVE", from, to)
            DEBIT -> txList(ctx, repo, title, "PURCHASE", from, to)
            COLLECTION -> txList(ctx, repo, title, "GOT", from, to)
            OUTSTANDING -> outstanding(ctx, repo, title)
            else -> summary(ctx, repo, title, from, to)
        }
    }

    private fun summary(ctx: Context, repo: Repository, title: String, from: Long, to: Long): ReportTable {
        val tx = repo.getTransactionsInRange(from, to)
        val buckets = Buckets.build(from, to)
        val n = buckets.size
        val gave = LongArray(n)
        val got = LongArray(n)
        val purchase = LongArray(n)
        val payment = LongArray(n)
        var tGave = 0L
        var tGot = 0L
        var tPurchase = 0L
        var tPayment = 0L
        for (t in tx) {
            val i = buckets.indexOf(t.txTime)
            when (t.txType) {
                "GAVE" -> {
                    tGave += t.amountPaise
                    if (i >= 0) gave[i] += t.amountPaise
                }
                "GOT" -> {
                    tGot += t.amountPaise
                    if (i >= 0) got[i] += t.amountPaise
                }
                "PURCHASE" -> {
                    tPurchase += t.amountPaise
                    if (i >= 0) purchase[i] += t.amountPaise
                }
                else -> {
                    tPayment += t.amountPaise
                    if (i >= 0) payment[i] += t.amountPaise
                }
            }
        }
        val dash = repo.getDashboard()

        val info = listOf(
            Pair(ctx.getString(R.string.lbl_period), Dates.periodText(from, to)),
            Pair(ctx.getString(R.string.tx_gave), Money.format(tGave)),
            Pair(ctx.getString(R.string.tx_got), Money.format(tGot)),
            Pair(ctx.getString(R.string.tx_purchase), Money.format(tPurchase)),
            Pair(ctx.getString(R.string.tx_payment), Money.format(tPayment)),
            Pair(ctx.getString(R.string.lbl_net_customers), Money.format(tGave - tGot)),
            Pair(ctx.getString(R.string.lbl_net_suppliers), Money.format(tPurchase - tPayment)),
            Pair(ctx.getString(R.string.total_receivable), Money.format(dash.receivablePaise)),
            Pair(ctx.getString(R.string.total_payable), Money.format(dash.payablePaise))
        )

        val rows = ArrayList<List<Cell>>()
        for (i in 0 until n) {
            if (gave[i] + got[i] + purchase[i] + payment[i] == 0L) continue
            rows.add(
                listOf(
                    Cell.text(buckets.fullLabels[i]),
                    Cell.money(gave[i]), Cell.money(got[i]),
                    Cell.money(purchase[i]), Cell.money(payment[i])
                )
            )
        }
        val footer = listOf(
            Cell.text(ctx.getString(R.string.total)),
            Cell.money(tGave), Cell.money(tGot), Cell.money(tPurchase), Cell.money(tPayment)
        )
        return ReportTable(
            title, info,
            listOf(
                ctx.getString(R.string.lbl_period), ctx.getString(R.string.tx_gave),
                ctx.getString(R.string.tx_got), ctx.getString(R.string.tx_purchase),
                ctx.getString(R.string.tx_payment)
            ),
            listOf(0.28f, 0.18f, 0.18f, 0.18f, 0.18f), 1, rows, footer
        )
    }

    private fun partyWise(
        ctx: Context, repo: Repository, title: String, isSupplier: Boolean, from: Long, to: Long
    ): ReportTable {
        val list = repo.getPartySummaries(isSupplier, from, to)
            .filter { it.increasePaise != 0L || it.decreasePaise != 0L || it.balancePaise != 0L }
        var tInc = 0L
        var tDec = 0L
        var tBal = 0L
        val rows = ArrayList<List<Cell>>()
        for (p in list) {
            tInc += p.increasePaise
            tDec += p.decreasePaise
            tBal += p.forYouPaise
            rows.add(
                listOf(
                    Cell.text(p.name), Cell.text(p.mobile),
                    Cell.money(p.increasePaise), Cell.money(p.decreasePaise), Cell.money(p.forYouPaise)
                )
            )
        }
        val info = listOf(
            Pair(ctx.getString(R.string.lbl_period), Dates.periodText(from, to)),
            Pair(ctx.getString(R.string.pdf_balance_note), "")
        )
        val footer = listOf(
            Cell.text(ctx.getString(R.string.total)), Cell.text(""),
            Cell.money(tInc), Cell.money(tDec), Cell.money(tBal)
        )
        return ReportTable(
            title, info,
            listOf(
                ctx.getString(R.string.col_name), ctx.getString(R.string.col_mobile),
                ctx.getString(if (isSupplier) R.string.tx_purchase else R.string.tx_gave),
                ctx.getString(if (isSupplier) R.string.tx_payment else R.string.tx_got),
                ctx.getString(R.string.col_balance)
            ),
            listOf(0.26f, 0.2f, 0.18f, 0.18f, 0.18f), 2, rows, footer
        )
    }

    private fun methodLabel(ctx: Context, method: String): String = ctx.getString(
        when (method) {
            "UPI" -> R.string.pm_upi
            "BANK" -> R.string.pm_bank
            "OTHER" -> R.string.pm_other
            else -> R.string.pm_cash
        }
    )

    private fun txList(
        ctx: Context, repo: Repository, title: String, type: String, from: Long, to: Long
    ): ReportTable {
        val list: List<TxRow> = repo.getTransactionsInRange(from, to).filter { it.txType == type }
        var total = 0L
        val rows = ArrayList<List<Cell>>()
        for (t in list) {
            total += t.amountPaise
            rows.add(
                listOf(
                    Cell.text(Dates.formatShortDate(t.txTime)),
                    Cell.text(t.partyName),
                    Cell.text(t.description),
                    Cell.text(methodLabel(ctx, t.paymentMethod)),
                    Cell.money(t.amountPaise)
                )
            )
        }
        val info = listOf(
            Pair(ctx.getString(R.string.lbl_period), Dates.periodText(from, to)),
            Pair(ctx.getString(R.string.total), Money.format(total))
        )
        val footer = listOf(
            Cell.text(ctx.getString(R.string.total)), Cell.text(""), Cell.text(""), Cell.text(""),
            Cell.money(total)
        )
        return ReportTable(
            title, info,
            listOf(
                ctx.getString(R.string.col_date), ctx.getString(R.string.col_name),
                ctx.getString(R.string.col_description), ctx.getString(R.string.col_method),
                ctx.getString(R.string.col_amount)
            ),
            listOf(0.2f, 0.22f, 0.26f, 0.14f, 0.18f), 4, rows, footer
        )
    }

    private fun outstanding(ctx: Context, repo: Repository, title: String): ReportTable {
        val customers = repo.getParties(false, "", true).filter { it.forYouPaise > 0L }
        val suppliers = repo.getParties(true, "", true).filter { it.forYouPaise < 0L }
        var receivable = 0L
        var payable = 0L
        val rows = ArrayList<List<Cell>>()
        for (p in customers) {
            receivable += p.forYouPaise
            rows.add(
                listOf(
                    Cell.text(ctx.getString(R.string.customers)), Cell.text(p.name),
                    Cell.text(p.mobile), Cell.money(p.forYouPaise)
                )
            )
        }
        for (p in suppliers) {
            payable += -p.forYouPaise
            rows.add(
                listOf(
                    Cell.text(ctx.getString(R.string.suppliers)), Cell.text(p.name),
                    Cell.text(p.mobile), Cell.money(-p.forYouPaise)
                )
            )
        }
        val info = listOf(
            Pair(ctx.getString(R.string.lbl_as_of), Dates.formatDate(System.currentTimeMillis())),
            Pair(ctx.getString(R.string.total_receivable), Money.format(receivable)),
            Pair(ctx.getString(R.string.total_payable), Money.format(payable))
        )
        return ReportTable(
            title, info,
            listOf(
                ctx.getString(R.string.col_type), ctx.getString(R.string.col_name),
                ctx.getString(R.string.col_mobile), ctx.getString(R.string.col_amount)
            ),
            listOf(0.2f, 0.32f, 0.24f, 0.24f), 3, rows, null
        )
    }
}
