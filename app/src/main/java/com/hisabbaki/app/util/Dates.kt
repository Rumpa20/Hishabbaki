package com.hisabbaki.app.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class Range(val from: Long, val to: Long)

object Dates {
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    private fun zero(c: Calendar) {
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
    }

    fun today(): Range {
        val c = Calendar.getInstance()
        zero(c)
        val from = c.timeInMillis
        c.add(Calendar.DAY_OF_MONTH, 1)
        return Range(from, c.timeInMillis)
    }

    fun thisWeek(): Range {
        val c = Calendar.getInstance()
        c.firstDayOfWeek = Calendar.MONDAY
        zero(c)
        c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        val from = c.timeInMillis
        c.add(Calendar.DAY_OF_MONTH, 7)
        return Range(from, c.timeInMillis)
    }

    fun thisMonth(): Range {
        val c = Calendar.getInstance()
        zero(c)
        c.set(Calendar.DAY_OF_MONTH, 1)
        val from = c.timeInMillis
        c.add(Calendar.MONTH, 1)
        return Range(from, c.timeInMillis)
    }

    fun thisYear(): Range {
        val c = Calendar.getInstance()
        zero(c)
        c.set(Calendar.DAY_OF_YEAR, 1)
        val from = c.timeInMillis
        c.add(Calendar.YEAR, 1)
        return Range(from, c.timeInMillis)
    }

    /** The Material date picker returns UTC midnight; convert that calendar day to local midnight. */
    fun utcDateToLocalDayStart(utcMillis: Long): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utc.timeInMillis = utcMillis
        val local = Calendar.getInstance()
        local.clear()
        local.set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        return local.timeInMillis
    }

    /** From a picked (startUtc, endUtc) day range to a local [from, to) range. */
    fun rangeFromPicker(startUtc: Long, endUtc: Long): Range {
        val from = utcDateToLocalDayStart(startUtc)
        val endDayStart = utcDateToLocalDayStart(endUtc)
        val c = Calendar.getInstance()
        c.timeInMillis = endDayStart
        c.add(Calendar.DAY_OF_MONTH, 1)
        return Range(from, c.timeInMillis)
    }

    fun formatDate(ms: Long): String = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(ms)

    fun formatShortDate(ms: Long): String = SimpleDateFormat("dd MMM yy", Locale.getDefault()).format(ms)

    fun formatCsv(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(ms)

    fun formatDateTime(ms: Long): String =
        SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(ms)

    fun periodText(from: Long, to: Long): String = formatDate(from) + " - " + formatDate(to - 1L)
}

/** Time buckets used by the chart and the summary report: 4-hour blocks, days, or months. */
class Buckets(
    val starts: LongArray,
    private val end: Long,
    val shortLabels: List<String>,
    val fullLabels: List<String>
) {
    val size: Int
        get() = starts.size

    /** Index of the bucket that contains time t, or -1 if outside the range. */
    fun indexOf(t: Long): Int {
        if (starts.isEmpty() || t < starts[0] || t >= end) return -1
        val i = java.util.Arrays.binarySearch(starts, t)
        return if (i >= 0) i else -i - 2
    }

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L

        fun build(from: Long, to: Long): Buckets {
            val starts = ArrayList<Long>()
            val shortL = ArrayList<String>()
            val fullL = ArrayList<String>()
            val cal = Calendar.getInstance()
            cal.timeInMillis = from
            val locale = Locale.getDefault()
            val span = to - from

            if (span <= DAY_MS + 2L * 60L * 60L * 1000L) {
                val fShort = SimpleDateFormat("HH", locale)
                val fFull = SimpleDateFormat("HH:mm", locale)
                for (i in 0 until 6) {
                    val s = cal.timeInMillis
                    cal.add(Calendar.HOUR_OF_DAY, 4)
                    starts.add(s)
                    shortL.add(fShort.format(s))
                    fullL.add(fFull.format(s) + " - " + fFull.format(cal.timeInMillis))
                }
            } else if (span <= 32L * DAY_MS) {
                val fShort = SimpleDateFormat("d", locale)
                val fFull = SimpleDateFormat("dd MMM yyyy", locale)
                while (cal.timeInMillis < to) {
                    val s = cal.timeInMillis
                    starts.add(s)
                    shortL.add(fShort.format(s))
                    fullL.add(fFull.format(s))
                    cal.add(Calendar.DAY_OF_MONTH, 1)
                }
            } else {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val manyMonths = span > 400L * DAY_MS
                val fShort = SimpleDateFormat(if (manyMonths) "MMM yy" else "MMM", locale)
                val fFull = SimpleDateFormat("MMM yyyy", locale)
                while (cal.timeInMillis < to) {
                    val s = cal.timeInMillis
                    starts.add(s)
                    shortL.add(fShort.format(s))
                    fullL.add(fFull.format(s))
                    cal.add(Calendar.MONTH, 1)
                }
            }
            return Buckets(starts.toLongArray(), to, shortL, fullL)
        }
    }
}
