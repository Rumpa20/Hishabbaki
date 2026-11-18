package com.hisabbaki.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.hisabbaki.app.data.BillingRepo
import com.hisabbaki.app.data.Db
import com.hisabbaki.app.data.InvTypes

class InvoicesActivity : AppCompatActivity() {

    private lateinit var adapter: InvoicesAdapter
    private var requestId = 0
    private val searchRunnable = Runnable { load() }

    private var selectedType = ""
    private lateinit var etSearch: EditText
    private lateinit var chipType: Chip

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_invoices)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        etSearch = findViewById(R.id.etSearch)
        chipType = findViewById(R.id.chipType)

        adapter = InvoicesAdapter { item ->
            val intent = Intent(this, InvoiceDetailActivity::class.java)
            intent.putExtra(InvoiceDetailActivity.EXTRA_INVOICE_ID, item.id)
            startActivity(intent)
        }
        val rv = findViewById<RecyclerView>(R.id.rvInvoices)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                window.decorView.removeCallbacks(searchRunnable)
                window.decorView.postDelayed(searchRunnable, 250)
            }
        })

        findViewById<Chip>(R.id.chipUnpaid).setOnCheckedChangeListener { _, _ -> load() }
        chipType.setOnClickListener { showTypeFilter() }
        findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener { showNewTypeChooser() }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun bizId() = Prefs.currentBusinessId(this)

    private fun showTypeFilter() {
        val types = InvTypes.all()
        val labels = ArrayList<String>()
        labels.add(getString(R.string.all_types))
        labels.addAll(typeLabels(types))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.doc_type)
            .setItems(labels.toTypedArray()) { _, which ->
                selectedType = if (which == 0) "" else types[which - 1]
                chipType.text =
                    if (selectedType.isEmpty()) getString(R.string.all_types)
                    else labels[which]
                load()
            }
            .show()
    }

    private fun typeLabels(types: List<String>): List<String> =
        types.map { InvoicesAdapter.typeLabelStatic(this, it) }

    private fun showNewTypeChooser() {
        val types = InvTypes.all()
        val labels = typeLabels(types).toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_invoice)
            .setItems(labels) { _, which ->
                val intent = Intent(this, InvoiceEditActivity::class.java)
                intent.putExtra(InvoiceEditActivity.EXTRA_INVOICE_ID, 0L)
                intent.putExtra(InvoiceEditActivity.EXTRA_TYPE, types[which])
                startActivity(intent)
            }
            .show()
    }

    private fun load() {
        val appCtx = applicationContext
        val query = etSearch.text.toString()
        val type = selectedType
        val onlyUnpaid = findViewById<Chip>(R.id.chipUnpaid).isChecked
        val businessId = bizId()
        requestId += 1
        val myId = requestId

        Db.async({
            BillingRepo.get(appCtx).listInvoices(businessId, query, type, null, null, onlyUnpaid)
        }) { result ->
            if (isFinishing || isDestroyed || myId != requestId) return@async
            result.onSuccess { list ->
                adapter.submit(list)
                val empty = findViewById<TextView>(R.id.tvEmpty)
                if (list.isEmpty()) {
                    empty.setText(if (query.isBlank()) R.string.no_invoices else R.string.no_invoices_search)
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
}
