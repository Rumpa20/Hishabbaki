package com.hisabbaki.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hisabbaki.app.data.Db
import com.hisabbaki.app.data.Party
import com.hisabbaki.app.data.Repository

/** Shows the customer list (isSupplier = false) or the supplier list (isSupplier = true). */
class PartiesFragment : Fragment(R.layout.fragment_parties) {

    companion object {
        private const val ARG_QUERY = "query"
        private const val ARG_SUPPLIER = "is_supplier"

        fun newInstance(isSupplier: Boolean, query: String): PartiesFragment {
            val f = PartiesFragment()
            val b = Bundle()
            b.putBoolean(ARG_SUPPLIER, isSupplier)
            b.putString(ARG_QUERY, query)
            f.arguments = b
            return f
        }
    }

    private var adapter: PartyAdapter? = null
    private var requestId = 0
    private val searchRunnable = Runnable { load() }

    private val isSupplier: Boolean
        get() = arguments?.getBoolean(ARG_SUPPLIER, false) ?: false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.tvTitle).setText(
            if (isSupplier) R.string.suppliers else R.string.customers
        )

        val partyAdapter = PartyAdapter(
            onClick = { openLedger(it) },
            onEdit = { edit(it) },
            onDelete = { confirmDelete(it) }
        )
        adapter = partyAdapter

        val rv = view.findViewById<RecyclerView>(R.id.rvCustomers)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = partyAdapter

        val etSearch = view.findViewById<EditText>(R.id.etSearch)
        etSearch.setHint(if (isSupplier) R.string.search_hint_supplier else R.string.search_hint)
        if (savedInstanceState == null) {
            val initial = arguments?.getString(ARG_QUERY).orEmpty()
            if (initial.isNotEmpty()) etSearch.setText(initial)
        }
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                view.removeCallbacks(searchRunnable)
                view.postDelayed(searchRunnable, 250)
            }
        })

        view.findViewById<ChipGroup>(R.id.chipGroupSort).setOnCheckedStateChangeListener { _, _ ->
            load()
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroyView() {
        view?.removeCallbacks(searchRunnable)
        adapter = null
        super.onDestroyView()
    }

    private fun load() {
        val root = view ?: return
        val supplier = isSupplier
        val query = root.findViewById<EditText>(R.id.etSearch).text.toString()
        val byBalance = root.findViewById<ChipGroup>(R.id.chipGroupSort).checkedChipId == R.id.chipBalance
        val appCtx = requireContext().applicationContext
        requestId += 1
        val myId = requestId

        Db.async({ Repository.get(appCtx).getParties(supplier, query, byBalance) }) { result ->
            val v = view
            if (!isAdded || v == null || myId != requestId) return@async
            result.onSuccess { list ->
                adapter?.submit(list)
                val empty = v.findViewById<TextView>(R.id.tvEmpty)
                if (list.isEmpty()) {
                    val msg = if (query.isBlank()) {
                        if (supplier) R.string.no_suppliers else R.string.no_customers
                    } else {
                        R.string.no_search_results
                    }
                    empty.setText(msg)
                    empty.visibility = View.VISIBLE
                } else {
                    empty.visibility = View.GONE
                }
            }
            result.onFailure {
                Toast.makeText(appCtx, R.string.error_load, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openLedger(p: Party) {
        val intent = Intent(requireContext(), LedgerActivity::class.java)
        intent.putExtra(LedgerActivity.EXTRA_PARTY_ID, p.id)
        intent.putExtra(LedgerActivity.EXTRA_SUPPLIER, p.isSupplier)
        startActivity(intent)
    }

    private fun edit(p: Party) {
        val intent = Intent(requireContext(), PartyEditActivity::class.java)
        intent.putExtra(PartyEditActivity.EXTRA_ID, p.id)
        intent.putExtra(PartyEditActivity.EXTRA_SUPPLIER, p.isSupplier)
        startActivity(intent)
    }

    private fun confirmDelete(p: Party) {
        val appCtx = requireContext().applicationContext
        Db.async({ Repository.get(appCtx).countPartyTransactions(p.isSupplier, p.id) }) { result ->
            if (!isAdded) return@async
            val count = result.getOrDefault(0)
            val message = if (count > 0) {
                getString(R.string.confirm_delete_party_with_tx, p.name, count)
            } else {
                getString(R.string.confirm_delete_party, p.name)
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete)
                .setMessage(message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ -> doDelete(p) }
                .show()
        }
    }

    private fun doDelete(p: Party) {
        val appCtx = requireContext().applicationContext
        Db.async({ Repository.get(appCtx).deleteParty(p.isSupplier, p.id) }) { result ->
            result.onSuccess {
                Toast.makeText(appCtx, R.string.party_deleted, Toast.LENGTH_SHORT).show()
                if (isAdded) load()
            }
            result.onFailure {
                Toast.makeText(appCtx, R.string.error_generic, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
