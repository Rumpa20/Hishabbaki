package com.hisabbaki.app.util

import android.content.Context
import com.hisabbaki.app.Prefs
import com.hisabbaki.app.R
import com.hisabbaki.app.data.BillingRepo
import com.hisabbaki.app.data.ExpenseRepo
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
    const val SALES = "SALES_REP"
    const val PURCHASES = "PURCHASES_REP"
    const val EXPENSES = "EXPENSES_REP"
    const val PNL = "PNL"
    const val CASHBOOK = "CASHBOOK_REP"
    const val INVENTORY = "INVENTORY"
    const val LOW_STOCK = "LOW_STOCK"
    const val PRODUCT_SALES = "PRODUCT_SALES"
    const val DAILY = "DAILY"

    fun titleRes(type: String): Int = when (type) {
        CUSTOMER_WISE -> R.string.report_customer_wise
        SUPPLIER_WISE -> R.string.report_supplier_wise
        CREDIT -> R.string.report_credit
        DEBIT -> R.string.report_debit
        COLLECTION -> R.string.report_collection
        OUTSTANDING -> R.string.report_outstanding
        SALES -> R.string.report_sales
        PURCHASES -> R.string.report_purchases
        EXPENSES -> R.string.report_expenses
        PNL -> R.string.report_pnl
        CASHBOOK -> R.string.report_cashbook
        INVENTORY -> R.string.report_inventory
        LOW_STOCK -> R.string.report_low_stock
        PRODUCT_SALES -> R.string.report_product_sales
        DAILY -> R.string.report_daily
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
            SALES -> invoiceList(ctx, title, "SALE", from, to)
            PURCHASES -> invoiceList(ctx, title, "PURCHASE", from, to)
            EXPENSES -> expenseList(ctx, title, from, to)
            PNL -> profitLoss(ctx, title, from, to)
            CASHBOOK -> cashbook(ctx, title, from, to)
            INVENTORY -> inventory(ctx, title, onlyLow = false)
            LOW_STOCK -> inventory(ctx, title, onlyLow = true)
            PRODUCT_SALES -> productSales(ctx, title, from, to)
            DAILY -> daily(ctx, title, from, to)
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

    private fun invoiceList(
        ctx: Context, title: String, invType: String, from: Long, to: Long
    ): ReportTable {
        val repo = BillingRepo.get(ctx)
        val list = repo.listInvoices(Prefs.currentBusinessId(ctx), "", invType, from, to, false)
        var total = 0L
        var paid = 0L
        val rows = ArrayList<List<Cell>>()
        for (inv in list) {
            total += inv.totalPaise
            paid += inv.paidPaise
            rows.add(
                listOf(
                    Cell.text(Dates.formatShortDate(inv.invoiceDate)),
                    Cell.text(inv.invoiceNo),
                    Cell.text(inv.partyName),
                    Cell.money(inv.totalPaise),
                    Cell.money(inv.paidPaise)
                )
            )
        }
        val info = listOf(
            Pair(ctx.getString(R.string.lbl_period), Dates.periodText(from, to)),
            Pair(ctx.getString(R.string.grand_total), Money.format(total)),
            Pair(ctx.getString(R.string.paid), Money.format(paid)),
            Pair(ctx.getString(R.string.balance_due), Money.format(total - paid))
        )
        val footer = listOf(
            Cell.text(ctx.getString(R.string.total)), Cell.text(""), Cell.text(""),
            Cell.money(total), Cell.money(paid)
        )
        return ReportTable(
            title, info,
            listOf(
                ctx.getString(R.string.col_date), ctx.getString(R.string.invoice_no),
                ctx.getString(R.string.party), ctx.getString(R.string.grand_total),
                ctx.getString(R.string.paid)
            ),
            listOf(0.18f, 0.16f, 0.28f, 0.2f, 0.18f), 3, rows, footer
        )
    }

    private fun expenseList(ctx: Context, title: String, from: Long, to: Long): ReportTable {
        val list = ExpenseRepo.get(ctx).getExpenses(
            Prefs.currentBusinessId(ctx), "", 0L, from, to
        )
        var total = 0L
        val rows = ArrayList<List<Cell>>()
        for (e in list) {
            total += e.amountPaise
            rows.add(
                listOf(
                    Cell.text(Dates.formatShortDate(e.expenseDate)),
                    Cell.text(e.title),
                    Cell.text(e.categoryName),
                    Cell.text(methodLabel(ctx, e.paymentMethod)),
                    Cell.money(e.amountPaise)
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
                ctx.getString(R.string.col_date), ctx.getString(R.string.col_title),
                ctx.getString(R.string.category), ctx.getString(R.string.col_method),
                ctx.getString(R.string.col_amount)
            ),
            listOf(0.18f, 0.26f, 0.2f, 0.16f, 0.2f), 4, rows, footer
        )
    }

    private fun profitLoss(ctx: Context, title: String, from: Long, to: Long): ReportTable {
        val t = BillingRepo.get(ctx).getBusinessTotals(Prefs.currentBusinessId(ctx), from, to)
        val netSales = t.salesPaise - t.salesReturnsPaise
        val netPurchases = t.purchasesPaise - t.purchaseReturnsPaise
        val grossProfit = netSales - t.cogsPaise
        val netProfit = grossProfit - t.expensesPaise
        val rows = ArrayList<List<Cell>>()
        fun add(labelRes: Int, value: Long) {
            rows.add(listOf(Cell.text(ctx.getString(labelRes)), Cell.money(value)))
        }
        add(R.string.dash_sales, t.salesPaise)
        if (t.salesReturnsPaise > 0) add(R.string.dash_less_returns, -t.salesReturnsPaise)
        add(R.string.dash_cogs, -t.cogsPaise)
        rows.add(listOf(Cell.text(ctx.getString(R.string.dash_gross_profit)), Cell.money(grossProfit)))
        add(R.string.dash_expenses, -t.expensesPaise)
        rows.add(listOf(Cell.text(ctx.getString(R.string.dash_net_profit)), Cell.money(netProfit)))
        rows.add(listOf(Cell.text(ctx.getString(R.string.dash_net_purchases)), Cell.money(netPurchases)))
        rows.add(listOf(Cell.text(ctx.getString(R.string.cash_in)), Cell.money(t.cashInPaise)))
        rows.add(listOf(Cell.text(ctx.getString(R.string.cash_out)), Cell.money(t.cashOutPaise)))
        val info = listOf(
            Pair(ctx.getString(R.string.lbl_period), Dates.periodText(from, to)),
            Pair(ctx.getString(R.string.dash_net_profit), Money.format(netProfit))
        )
        return ReportTable(
            title, info,
            listOf(ctx.getString(R.string.lbl_net_customers), ctx.getString(R.string.col_amount)),
            listOf(0.6f, 0.4f), 1, rows, null
        )
    }

    private fun cashbook(ctx: Context, title: String, from: Long, to: Long): ReportTable {
        val flow = com.hisabbaki.app.data.CashbookRepo.get(ctx).getDailyFlow(
            Prefs.currentBusinessId(ctx), from, to
        )
        var tIn = 0L
        var tOut = 0L
        val rows = ArrayList<List<Cell>>()
        for ((day, cashIn, cashOut) in flow) {
            tIn += cashIn
            tOut += cashOut
            rows.add(
                listOf(
                    Cell.text(Dates.formatDate(day)),
                    Cell.money(cashIn), Cell.money(cashOut),
                    Cell.money(cashIn - cashOut)
                )
            )
        }
        val info = listOf(
            Pair(ctx.getString(R.string.lbl_period), Dates.periodText(from, to)),
            Pair(ctx.getString(R.string.cash_in), Money.format(tIn)),
            Pair(ctx.getString(R.string.cash_out), Money.format(tOut))
        )
        val footer = listOf(
            Cell.text(ctx.getString(R.string.total)),
            Cell.money(tIn), Cell.money(tOut), Cell.money(tIn - tOut)
        )
        return ReportTable(
            title, info,
            listOf(
                ctx.getString(R.string.col_date), ctx.getString(R.string.cash_in),
                ctx.getString(R.string.cash_out), ctx.getString(R.string.net)
            ),
            listOf(0.34f, 0.22f, 0.22f, 0.22f), 1, rows, footer
        )
    }

    private fun inventory(ctx: Context, title: String, onlyLow: Boolean): ReportTable {
        val list = com.hisabbaki.app.data.InventoryRepo.get(ctx).getProducts(
            Prefs.currentBusinessId(ctx), "", 0L, onlyLow
        )
        var value = 0L
        val rows = ArrayList<List<Cell>>()
        for (p in list) {
            value += p.stockMilli * p.sellingPricePaise / 1000L
            rows.add(
                listOf(
                    Cell.text(p.name),
                    Cell.text(if (p.sku.isBlank()) "-" else p.sku),
                    Cell.text(
                        java.math.BigDecimal.valueOf(p.stockMilli, 3).stripTrailingZeros().toPlainString() +
                            if (p.unit.isBlank()) "" else " " + p.unit
                    ),
                    Cell.money(p.sellingPricePaise),
                    Cell.money(p.stockMilli * p.sellingPricePaise / 1000L)
                )
            )
        }
        val info = listOf(
            Pair(ctx.getString(R.string.dash_products), list.size.toString()),
            Pair(ctx.getString(R.string.col_stock_value), Money.format(value))
        )
        return ReportTable(
            title, info,
            listOf(
                ctx.getString(R.string.col_name), ctx.getString(R.string.col_sku),
                ctx.getString(R.string.col_stock), ctx.getString(R.string.rate),
                ctx.getString(R.string.col_stock_value)
            ),
            listOf(0.3f, 0.14f, 0.16f, 0.18f, 0.22f), 3, rows, null
        )
    }

    private fun productSales(ctx: Context, title: String, from: Long, to: Long): ReportTable {
        val repo = BillingRepo.get(ctx)
        val list = repo.listInvoices(Prefs.currentBusinessId(ctx), "", "SALE", from, to, false)
        val qty = HashMap<String, Long>()
        val amount = HashMap<String, Long>()
        for (inv in list) {
            val d = repo.getInvoice(Prefs.currentBusinessId(ctx), inv.id) ?: continue
            for (item in d.items) {
                qty[item.name] = (qty[item.name] ?: 0L) + item.qtyMilli
                amount[item.name] = (amount[item.name] ?: 0L) + item.lineTotalPaise
            }
        }
        val names = qty.keys.sortedWith(
            compareByDescending<String> { amount[it] ?: 0L }.thenBy { it.lowercase() }
        )
        var tQty = 0L
        var tAmount = 0L
        val rows = ArrayList<List<Cell>>()
        for (name in names) {
            val q = qty[name] ?: 0L
            val a = amount[name] ?: 0L
            tQty += q
            tAmount += a
            rows.add(
                listOf(
                    Cell.text(name),
                    Cell.text(java.math.BigDecimal.valueOf(q, 3).stripTrailingZeros().toPlainString()),
                    Cell.money(a),
                    Cell.money(if (q > 0) a * 1000L / q else 0L)
                )
            )
        }
        val info = listOf(
            Pair(ctx.getString(R.string.lbl_period), Dates.periodText(from, to)),
            Pair(ctx.getString(R.string.dash_sales), Money.format(tAmount))
        )
        val footer = listOf(
            Cell.text(ctx.getString(R.string.total)), Cell.text(""),
            Cell.money(tAmount), Cell.text("")
        )
        return ReportTable(
            title, info,
            listOf(
                ctx.getString(R.string.col_product), ctx.getString(R.string.qty_label),
                ctx.getString(R.string.dash_sales), ctx.getString(R.string.col_avg_rate)
            ),
            listOf(0.38f, 0.18f, 0.22f, 0.22f), 2, rows, footer
        )
    }

    private fun daily(ctx: Context, title: String, from: Long, to: Long): ReportTable {
        val repo = BillingRepo.get(ctx)
        val invoices = repo.listInvoices(Prefs.currentBusinessId(ctx), "", "SALE", from, to, false)
        val salesByDay = HashMap<Long, Long>()
        for (inv in invoices) {
            val day = com.hisabbaki.app.data.ExpenseRepo.dayStart(inv.invoiceDate)
            salesByDay[day] = (salesByDay[day] ?: 0L) + inv.totalPaise
        }
        val flow = com.hisabbaki.app.data.CashbookRepo.get(ctx).getDailyFlow(
            Prefs.currentBusinessId(ctx), from, to
        )
        val days = (salesByDay.keys + flow.map { it.first }).distinct().sorted()
        var tSales = 0L
        var tIn = 0L
        var tOut = 0L
        val rows = ArrayList<List<Cell>>()
        for (day in days) {
            val sales = salesByDay[day] ?: 0L
            val cashIn = flow.firstOrNull { it.first == day }?.second ?: 0L
            val cashOut = flow.firstOrNull { it.first == day }?.third ?: 0L
            tSales += sales
            tIn += cashIn
            tOut += cashOut
            rows.add(
                listOf(
                    Cell.text(Dates.formatDate(day)),
                    Cell.money(sales),
                    Cell.money(cashIn),
                    Cell.money(cashOut)
                )
            )
        }
        val info = listOf(
            Pair(ctx.getString(R.string.lbl_period), Dates.periodText(from, to)),
            Pair(ctx.getString(R.string.dash_sales), Money.format(tSales)),
            Pair(ctx.getString(R.string.dash_expenses),
                Money.format(BillingRepo.get(ctx).getBusinessTotals(
                    Prefs.currentBusinessId(ctx), from, to).expensesPaise))
        )
        val footer = listOf(
            Cell.text(ctx.getString(R.string.total)),
            Cell.money(tSales), Cell.money(tIn), Cell.money(tOut)
        )
        return ReportTable(
            title, info,
            listOf(
                ctx.getString(R.string.col_date), ctx.getString(R.string.dash_sales),
                ctx.getString(R.string.cash_in), ctx.getString(R.string.cash_out)
            ),
            listOf(0.34f, 0.22f, 0.22f, 0.22f), 1, rows, footer
        )
    }
}
