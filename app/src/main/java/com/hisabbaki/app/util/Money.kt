package com.hisabbaki.app.util

import java.math.BigDecimal

/** All money in the app is stored as Long paise (1 rupee = 100 paise). No floating point. */
object Money {
    const val SYMBOL = "₹"

    private val INPUT_PATTERN = Regex("^[0-9]{1,10}(\\.[0-9]{1,2})?$")

    /** Parses text like "1,250.50" into paise. Returns null if invalid. Never negative. */
    fun parseToPaise(input: String): Long? {
        val cleaned = input.trim().replace(",", "")
        if (!INPUT_PATTERN.matches(cleaned)) return null
        return try {
            BigDecimal(cleaned).movePointRight(2).toLong()
        } catch (e: NumberFormatException) {
            null
        } catch (e: ArithmeticException) {
            null
        }
    }

    /** Formats paise as Indian grouping, e.g. ₹1,23,456.50 */
    fun format(paise: Long, withSymbol: Boolean = true): String {
        val negative = paise < 0
        val abs = if (paise == Long.MIN_VALUE) Long.MAX_VALUE else Math.abs(paise)
        val rupees = abs / 100
        val fraction = (abs % 100).toInt()
        val body = groupIndian(rupees.toString()) + "." + fraction.toString().padStart(2, '0')
        return (if (negative) "-" else "") + (if (withSymbol) SYMBOL else "") + body
    }

    /** Plain editable text, e.g. "1250.50" */
    fun toEditText(paise: Long): String = BigDecimal.valueOf(paise, 2).toPlainString()

    private fun groupIndian(digits: String): String {
        if (digits.length <= 3) return digits
        val last3 = digits.substring(digits.length - 3)
        var rest = digits.substring(0, digits.length - 3)
        val parts = ArrayList<String>()
        while (rest.length > 2) {
            parts.add(0, rest.substring(rest.length - 2))
            rest = rest.substring(0, rest.length - 2)
        }
        if (rest.isNotEmpty()) parts.add(0, rest)
        return parts.joinToString(",") + "," + last3
    }
}
