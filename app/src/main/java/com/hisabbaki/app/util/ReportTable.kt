package com.hisabbaki.app.util

/** One table cell: [text] is shown on screen / in PDF, [raw] goes to CSV (plain numbers for Excel). */
data class Cell(val text: String, val raw: String) {
    companion object {
        fun text(s: String): Cell = Cell(s, s)
        fun money(paise: Long): Cell = Cell(Money.format(paise), Money.toEditText(paise))
    }
}

data class ReportTable(
    val title: String,
    val infoLines: List<Pair<String, String>>,
    val headers: List<String>,
    val weights: List<Float>,
    /** Columns from this index on are right-aligned (amounts). */
    val numericFrom: Int,
    val rows: List<List<Cell>>,
    val footer: List<Cell>?
)
