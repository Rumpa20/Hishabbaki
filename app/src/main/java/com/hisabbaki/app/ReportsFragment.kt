package com.hisabbaki.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.MaterialDatePicker
import com.hisabbaki.app.data.Db
import com.hisabbaki.app.data.Repository
import com.hisabbaki.app.util.Buckets
import com.hisabbaki.app.util.Dates
import com.hisabbaki.app.util.Money
import com.hisabbaki.app.util.Range
import com.hisabbaki.app.util.ReportBuilder

class ReportsFragment : Fragment(R.layout.fragment_reports) {

    companion object {
        private const val STATE_FROM = "custom_from"
        private const val STATE_TO = "custom_to"
    }

    private class Overview(
        val gave: Long,
        val got: Long,
        val purchase: Long,
        val payment: Long,
        val labels: List<String>,
        val chartGave: LongArray,
        val chartGot: LongArray
    )

    private var customFrom = 0L
    private var customTo = 0L
    private var currentRange: Range = Dates.thisMonth()
    private var requestId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val month = Dates.thisMonth()
        customFrom = savedInstanceState?.getLong(STATE_FROM, month.from) ?: month.from
        customTo = savedInstanceState?.getLong(STATE_TO, month.to) ?: month.to
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_FROM, customFrom)
        outState.putLong(STATE_TO, customTo)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val chips = view.findViewById<ChipGroup>(R.id.chipGroupPeriod)
        if (savedInstanceState == null) chips.check(R.id.chipMonth)
        chips.setOnCheckedStateChangeListener { _, _ -> load() }
        view.findViewById<View>(R.id.chipCustom).setOnClickListener { showRangePicker() }

        val rows = listOf(
            Pair(R.id.rowSummary, ReportBuilder.SUMMARY),
            Pair(R.id.rowCustomerWise, ReportBuilder.CUSTOMER_WISE),
            Pair(R.id.rowSupplierWise, ReportBuilder.SUPPLIER_WISE),
            Pair(R.id.rowCredit, ReportBuilder.CREDIT),
            Pair(R.id.rowDebit, ReportBuilder.DEBIT),
            Pair(R.id.rowCollection, ReportBuilder.COLLECTION),
            Pair(R.id.rowOutstanding, ReportBuilder.OUTSTANDING)
        )
        for (r in rows) {
            view.findViewById<View>(r.first).setOnClickListener { openReport(r.second) }
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun rangeForSelection(root: View): Range =
        when (root.findViewById<ChipGroup>(R.id.chipGroupPeriod).checkedChipId) {
            R.id.chipToday -> Dates.today()
            R.id.chipWeek -> Dates.thisWeek()
            R.id.chipYear -> Dates.thisYear()
            R.id.chipCustom -> Range(customFrom, customTo)
            else -> Dates.thisMonth()
        }

    private fun showRangePicker() {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(R.string.date_range)
            .build()
        picker.addOnPositiveButtonClickListener { selection ->
            val start = selection.first
            val end = selection.second
            if (start != null && end != null) {
                val r = Dates.rangeFromPicker(start, end)
                customFrom = r.from
                customTo = r.to
                load()
            }
        }
        picker.show(parentFragmentManager, "report_range")
    }

    private fun load() {
        val root = view ?: return
        val range = rangeForSelection(root)
        currentRange = range
        root.findViewById<TextView>(R.id.tvPeriod).text = Dates.periodText(range.from, range.to)

        val appCtx = requireContext().applicationContext
        requestId += 1
        val myId = requestId
        Db.async({ computeOverview(appCtx, range) }) { result ->
            val v = view
            if (!isAdded || v == null || myId != requestId) return@async
            result.onSuccess { bind(v, it) }
            result.onFailure {
                Toast.makeText(appCtx, R.string.error_load, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun computeOverview(appCtx: android.content.Context, range: Range): Overview {
        val tx = Repository.get(appCtx).getTransactionsInRange(range.from, range.to)
        val buckets = Buckets.build(range.from, range.to)
        val n = buckets.size
        val chartGave = LongArray(n)
        val chartGot = LongArray(n)
        var gave = 0L
        var got = 0L
        var purchase = 0L
        var payment = 0L
        for (t in tx) {
            val i = buckets.indexOf(t.txTime)
            when (t.txType) {
                "GAVE" -> {
                    gave += t.amountPaise
                    if (i >= 0) chartGave[i] += t.amountPaise
                }
                "GOT" -> {
                    got += t.amountPaise
                    if (i >= 0) chartGot[i] += t.amountPaise
                }
                "PURCHASE" -> purchase += t.amountPaise
                else -> payment += t.amountPaise
            }
        }
        return Overview(gave, got, purchase, payment, buckets.shortLabels, chartGave, chartGot)
    }

    private fun bind(v: View, o: Overview) {
        v.findViewById<TextView>(R.id.tvRepGave).text = Money.format(o.gave)
        v.findViewById<TextView>(R.id.tvRepGot).text = Money.format(o.got)
        v.findViewById<TextView>(R.id.tvRepPurchase).text = Money.format(o.purchase)
        v.findViewById<TextView>(R.id.tvRepPayment).text = Money.format(o.payment)
        v.findViewById<BarChartView>(R.id.barChart).setData(o.labels, o.chartGave, o.chartGot)
    }

    private fun openReport(type: String) {
        val intent = Intent(requireContext(), ReportDetailActivity::class.java)
        intent.putExtra(ReportDetailActivity.EXTRA_TYPE, type)
        intent.putExtra(ReportDetailActivity.EXTRA_FROM, currentRange.from)
        intent.putExtra(ReportDetailActivity.EXTRA_TO, currentRange.to)
        startActivity(intent)
    }
}
