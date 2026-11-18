package com.hisabbaki.app.util

import com.hisabbaki.app.data.LedgerData
import com.hisabbaki.app.data.Party
import com.hisabbaki.app.data.TxRow
import com.hisabbaki.app.data.TxTypes

/** Builds CSV text that opens in Excel / Google Sheets. Amounts are plain numbers (rupees). */
object CsvExport {
    private val NUMBER = Regex("^-?[0-9]+(\\.[0-9]+)?$")

    private fun escape(value: String): String {
        var s = value
        // Stop spreadsheet formula injection from names / descriptions that start with = + - @
        if (s.isNotEmpty() && (s[0] == '=' || s[0] == '+' || s[0] == '-' || s[0] == '@') && !NUMBER.matches(s)) {
            s = "'" + s
        }
        val needsQuotes = s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    private fun line(cols: List<String>): String = cols.joinToString(",") { escape(it) } + "\r\n"

    private fun typeName(type: String): String = when (type) {
        "GAVE" -> "You gave"
        "GOT" -> "You got"
        "PURCHASE" -> "Purchase"
        else -> "Payment"
    }

    fun parties(list: List<Party>): String {
        val sb = StringBuilder()
        sb.append(line(listOf("Name", "Mobile", "Address", "Opening balance (+get / -give)", "Balance (+get / -give)", "Notes")))
        for (p in list) {
            val opening = if (p.isSupplier) -p.openingPaise else p.openingPaise
            sb.append(
                line(
                    listOf(
                        p.name, p.mobile, p.address,
                        Money.toEditText(opening), Money.toEditText(p.forYouPaise), p.notes
                    )
                )
            )
        }
        return sb.toString()
    }

    fun transactions(list: List<TxRow>): String {
        val sb = StringBuilder()
        sb.append(line(listOf("Date", "Party type", "Party", "Type", "Amount", "Payment method", "Reference", "Description", "Notes")))
        for (t in list) {
            sb.append(
                line(
                    listOf(
                        Dates.formatCsv(t.txTime),
                        if (t.partyIsSupplier) "Supplier" else "Customer",
                        t.partyName, typeName(t.txType), Money.toEditText(t.amountPaise),
                        t.paymentMethod, t.referenceNo, t.description, t.notes
                    )
                )
            )
        }
        return sb.toString()
    }

    /** Full ledger, oldest first, with running balance (+ you will get / - you will give). */
    fun ledger(data: LedgerData): String {
        val supplier = data.party.isSupplier
        fun forYou(balance: Long): Long = if (supplier) -balance else balance
        val sb = StringBuilder()
        sb.append(line(listOf("Date", "Type", "Description", "Reference", "Payment method", "Amount", "Balance (+get / -give)", "Notes")))
        sb.append(line(listOf("", "Opening balance", "", "", "", "", Money.toEditText(forYou(data.party.openingPaise)), "")))
        for (e in data.entries.reversed()) {
            val t = e.tx
            sb.append(
                line(
                    listOf(
                        Dates.formatCsv(t.txTime), typeName(t.txType), t.description, t.referenceNo,
                        t.paymentMethod, Money.toEditText(t.amountPaise),
                        Money.toEditText(forYou(e.runningBalancePaise)), t.notes
                    )
                )
            )
        }
        sb.append(line(listOf("", "Closing balance", "", "", "", "", Money.toEditText(data.party.forYouPaise), "")))
        return sb.toString()
    }

    fun table(t: ReportTable): String {
        val sb = StringBuilder()
        sb.append(line(listOf(t.title)))
        for (info in t.infoLines) sb.append(line(listOf(info.first, info.second)))
        sb.append(line(emptyList()))
        sb.append(line(t.headers))
        for (row in t.rows) sb.append(line(row.map { it.raw }))
        val footer = t.footer
        if (footer != null) sb.append(line(footer.map { it.raw }))
        return sb.toString()
    }

    @Suppress("unused")
    private fun unusedTypes() = TxTypes.increase(false)
}
