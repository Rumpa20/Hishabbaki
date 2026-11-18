package com.hisabbaki.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hisabbaki.app.data.BillingRepo
import com.hisabbaki.app.data.DashboardStats
import com.hisabbaki.app.data.Db
import com.hisabbaki.app.data.InventoryRepo
import com.hisabbaki.app.data.ProductStats
import com.hisabbaki.app.data.Repository
import com.hisabbaki.app.data.TxTypes
import com.hisabbaki.app.util.Dates
import com.hisabbaki.app.util.Money
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : Fragment(R.layout.fragment_home) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.btnAddCustomer).setOnClickListener { openPartyEditor(false) }
        view.findViewById<View>(R.id.btnAddSupplier).setOnClickListener { openPartyEditor(true) }
        view.findViewById<View>(R.id.btnAddTransaction).setOnClickListener { addTransaction() }

        view.findViewById<View>(R.id.btnQuickProducts).setOnClickListener {
            startActivity(Intent(requireContext(), ProductsActivity::class.java))
        }
        view.findViewById<View>(R.id.btnQuickBilling).setOnClickListener {
            startActivity(Intent(requireContext(), InvoicesActivity::class.java))
        }
        view.findViewById<View>(R.id.btnQuickCashbook).setOnClickListener {
            startActivity(Intent(requireContext(), CashbookActivity::class.java))
        }

        view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipGroupHomePeriod)
            .setOnCheckedStateChangeListener { _, _ -> loadOverview() }

        val etSearch = view.findViewById<EditText>(R.id.etSearch)
        etSearch.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                (activity as? MainActivity)?.openCustomers(v.text.toString())
                true
            } else {
                false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        load()
        loadOverview()
    }

    private fun bizId() = Prefs.currentBusinessId(requireContext())

    private fun overviewRange(): com.hisabbaki.app.util.Range =
        when (view?.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipGroupHomePeriod)?.checkedChipId) {
            R.id.chipHToday -> Dates.today()
            R.id.chipHWeek -> Dates.thisWeek()
            R.id.chipHYear -> Dates.thisYear()
            else -> Dates.thisMonth()
        }

    private var overviewRequestId = 0

    private fun loadOverview() {
        val root = view ?: return
        val range = overviewRange()
        val appCtx = requireContext().applicationContext
        overviewRequestId += 1
        val myId = overviewRequestId
        val businessId = bizId()

        Db.async({
            val totals = BillingRepo.get(appCtx).getBusinessTotals(businessId, range.from, range.to)
            val stock = InventoryRepo.get(appCtx).getProductStats(businessId)
            Pair(totals, stock)
        }) { result ->
            val v = view
            if (!isAdded || v == null || myId != overviewRequestId) return@async
            result.onSuccess { pair ->
                val t = pair.first
                val s = pair.second
                v.findViewById<TextView>(R.id.tvSales).text = Money.format(t.salesPaise)
                v.findViewById<TextView>(R.id.tvPurchases).text = Money.format(t.purchasesPaise)
                v.findViewById<TextView>(R.id.tvExpenses).text = Money.format(t.expensesPaise)
                val profit = v.findViewById<TextView>(R.id.tvProfit)
                profit.text = Money.format(t.netProfitPaise)
                profit.setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        if (t.netProfitPaise >= 0) R.color.get_green else R.color.give_red
                    )
                )
                v.findViewById<TextView>(R.id.tvStockLine).text = getString(
                    R.string.dash_stock_line,
                    s.productCount, s.lowStockCount, Money.format(s.stockValuePaise)
                )
            }
            result.onFailure {
                Toast.makeText(appCtx, R.string.error_load, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openPartyEditor(isSupplier: Boolean) {
        val intent = Intent(requireContext(), PartyEditActivity::class.java)
        intent.putExtra(PartyEditActivity.EXTRA_SUPPLIER, isSupplier)
        startActivity(intent)
    }

    private fun addTransaction() {
        val items = arrayOf(getString(R.string.customers), getString(R.string.suppliers))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_transaction)
            .setItems(items) { _, which -> pickParty(which == 1) }
            .show()
    }

    private fun pickParty(isSupplier: Boolean) {
        val appCtx = requireContext().applicationContext
        Db.async({ Repository.get(appCtx).getParties(isSupplier, "", false) }) { result ->
            if (!isAdded) return@async
            val list = result.getOrNull().orEmpty()
            if (list.isEmpty()) {
                Toast.makeText(appCtx, R.string.add_party_first, Toast.LENGTH_SHORT).show()
                return@async
            }
            val names = list.map { it.name }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.select_party)
                .setItems(names) { _, index ->
                    val party = list[index]
                    val intent = Intent(requireContext(), TransactionEditActivity::class.java)
                    intent.putExtra(TransactionEditActivity.EXTRA_PARTY_ID, party.id)
                    intent.putExtra(TransactionEditActivity.EXTRA_SUPPLIER, isSupplier)
                    intent.putExtra(TransactionEditActivity.EXTRA_TYPE, TxTypes.increase(isSupplier))
                    startActivity(intent)
                }
                .show()
        }
    }

    private fun load() {
        if (view == null) return
        val appCtx = requireContext().applicationContext
        Db.async({ Repository.get(appCtx).getDashboard() }) { result ->
            val root = view
            if (!isAdded || root == null) return@async
            result.onSuccess { bind(root, it) }
            result.onFailure {
                Toast.makeText(appCtx, R.string.error_load, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bind(v: View, s: DashboardStats) {
        v.findViewById<TextView>(R.id.tvReceivable).text = Money.format(s.receivablePaise)
        v.findViewById<TextView>(R.id.tvPayable).text = Money.format(s.payablePaise)
        v.findViewById<TextView>(R.id.tvCustomers).text = s.customerCount.toString()
        v.findViewById<TextView>(R.id.tvSuppliers).text = s.supplierCount.toString()
        v.findViewById<TextView>(R.id.tvToday).text = s.todayCount.toString()
        v.findViewById<TextView>(R.id.tvMonthCollection).text = Money.format(s.monthCollectionPaise)
        v.findViewById<TextView>(R.id.tvMonthPayments).text = Money.format(s.monthPaymentsPaise)
        v.findViewById<TextView>(R.id.tvMonthNet).text =
            Money.format(s.monthCollectionPaise - s.monthPaymentsPaise)

        val container = v.findViewById<LinearLayout>(R.id.recentContainer)
        val empty = v.findViewById<TextView>(R.id.tvRecentEmpty)
        container.removeAllViews()
        if (s.recent.isEmpty()) {
            empty.visibility = View.VISIBLE
            return
        }
        empty.visibility = View.GONE

        val inflater = LayoutInflater.from(requireContext())
        val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        for (tx in s.recent) {
            val row = inflater.inflate(R.layout.item_recent_tx, container, false)
            val typeLabel = when (tx.txType) {
                "GAVE" -> R.string.tx_gave
                "GOT" -> R.string.tx_got
                "PURCHASE" -> R.string.tx_purchase
                else -> R.string.tx_payment
            }
            val decreasing = tx.txType == "GOT" || tx.txType == "PAYMENT"
            row.findViewById<TextView>(R.id.tvTxName).text = tx.partyName
            row.findViewById<TextView>(R.id.tvTxMeta).text =
                getString(typeLabel) + " · " + dateFormat.format(Date(tx.txTime))
            val amount = row.findViewById<TextView>(R.id.tvTxAmount)
            amount.text = Money.format(tx.amountPaise)
            amount.setTextColor(
                ContextCompat.getColor(requireContext(), if (decreasing) R.color.get_green else R.color.give_red)
            )
            container.addView(row)
        }
    }
}
