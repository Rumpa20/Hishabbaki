package com.hisabbaki.app.util

/** Quantities are stored as milli-units (1000 = 1.0 unit). No floating point math. */
object Qty {
    private val INPUT_PATTERN = Regex("^[0-9]{1,7}(\\.[0-9]{1,3})?$")

    /** Parses "2.5" into 2500 milli-units. Returns null if invalid. Never negative. */
    fun parseToMilli(input: String): Long? {
        val cleaned = input.trim().replace(",", "")
        if (!INPUT_PATTERN.matches(cleaned)) return null
        return try {
            java.math.BigDecimal(cleaned).movePointRight(3).toLong()
        } catch (e: NumberFormatException) {
            null
        } catch (e: ArithmeticException) {
            null
        }
    }

    /** Plain editable text, e.g. "2.5" */
    fun toEditText(milli: Long): String =
        java.math.BigDecimal.valueOf(milli, 3).stripTrailingZeros().toPlainString()

    /** Compact display, e.g. "2.5 kg" */
    fun format(milli: Long, unit: String): String {
        val value = java.math.BigDecimal.valueOf(milli, 3).stripTrailingZeros().toPlainString()
        return if (unit.isBlank()) value else "$value $unit"
    }
}
