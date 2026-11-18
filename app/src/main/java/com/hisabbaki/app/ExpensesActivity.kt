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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.hisabbaki.app.data.Db
import com.hisabbaki.app.data.Expense
import com.hisabbaki.app.data.ExpenseRepo
import com.hisabbaki.app.util.Money

class ExpensesActivity : AppCompatActivity() {

    private lateinit var adapter: ExpenseAdapter
    private var requestId = 0
    private val searchRunnable = Runnable { load() }

    private lateinit var etSearch: EditText
    private lateinit var tvStats: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_expenses)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        etSearch = findViewById(R.id.etSearch)
        tvStats = findViewById(R.id.tvExpenseStats)

        adapter = ExpenseAdapter(
            onClick = { openEditor(it.id) },
            onDelete = { confirmDelete(it) }
        )
        val rv = findViewById<RecyclerView>(R.id.rvExpenses)
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

        findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipGroupRange)
            .setOnCheckedStateChangeListener { _, _ -> load() }
        findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener { openEditor(0L) }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun bizId() = Prefs.currentBusinessId(this)

    private fun rangeFromChips(): Pair<Long?, Long?> {
        val checked = findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipGroupRange).checkedChipId
        return when (checked) {
            R.id.chipToday -> {
                val start = ExpenseRepo.dayStart(System.currentTimeMillis())
                Pair(start, start + DAY_MS)
            }
            R.id.chipMonth -> {
                val cal = java.util.Calendar.getInstance()
                cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
                val start = ExpenseRepo.dayStart(cal.timeInMillis)
                cal.add(java.util.Calendar.MONTH, 1)
                val end = ExpenseRepo.dayStart(cal.timeInMillis)
                Pair(start, end)
            }
            else -> Pair(null, null)
        }
    }

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }

    private fun load() {
        val appCtx = applicationContext
        val query = etSearch.text.toString()
        val (from, to) = rangeFromChips()
        val businessId = bizId()
        requestId += 1
        val myId = requestId

        Db.async({
            val repo = ExpenseRepo.get(appCtx)
            val list = repo.getExpenses(businessId, query, 0L, from, to)
            val stats = repo.getStats(businessId)
            Triple(list, stats, repo)
        }) { result ->
            if (isFinishing || isDestroyed || myId != requestId) return@async
            result.onSuccess { triple ->
                val list = triple.first
                val stats = triple.second
                adapter.submit(list)
                tvStats.text = getString(
                    R.string.expense_stats_line,
                    Money.format(stats.todayPaise),
                    Money.format(stats.monthPaise)
                )
                val empty = findViewById<TextView>(R.id.tvEmpty)
                if (list.isEmpty()) {
                    empty.setText(if (query.isBlank()) R.string.no_expenses else R.string.no_expenses_search)
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

    private fun openEditor(id: Long) {
        val intent = Intent(this, ExpenseEditActivity::class.java)
        intent.putExtra(ExpenseEditActivity.EXTRA_EXPENSE_ID, id)
        startActivity(intent)
    }

    private fun confirmDelete(e: Expense) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.expense_delete_confirm, e.title))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val appCtx = applicationContext
                Db.async({ ExpenseRepo.get(appCtx).deleteExpense(bizId(), e.id) }) { result ->
                    result.onSuccess {
                        Toast.makeText(appCtx, R.string.expense_deleted, Toast.LENGTH_SHORT).show()
                        if (!isFinishing) load()
                    }
                    result.onFailure {
                        Toast.makeText(appCtx, R.string.error_generic, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }
}
