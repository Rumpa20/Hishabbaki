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
import com.hisabbaki.app.data.Db
import com.hisabbaki.app.data.InventoryRepo
import com.hisabbaki.app.data.Product
import com.hisabbaki.app.data.ProductCategory
import com.hisabbaki.app.data.ProductStats
import com.hisabbaki.app.util.Money
import com.google.android.material.floatingactionbutton.FloatingActionButton

class ProductsActivity : AppCompatActivity() {

    private lateinit var adapter: ProductAdapter
    private var requestId = 0
    private val searchRunnable = Runnable { load() }

    private var categories: List<ProductCategory> = emptyList()
    private var stats: ProductStats? = null
    private var selectedCategoryId: Long = 0L

    private lateinit var etSearch: EditText
    private lateinit var chipCategory: Chip
    private lateinit var chipLowStock: Chip
    private lateinit var tvStats: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_products)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        etSearch = findViewById(R.id.etSearch)
        chipCategory = findViewById(R.id.chipCategory)
        chipLowStock = findViewById(R.id.chipLowStock)
        tvStats = findViewById(R.id.tvProductStats)

        adapter = ProductAdapter(
            onClick = { openProduct(it) },
            onEdit = { openEditor(it.id) },
            onDelete = { confirmDelete(it) }
        )
        val rv = findViewById<RecyclerView>(R.id.rvProducts)
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

        chipLowStock.setOnCheckedChangeListener { _, _ -> load() }
        chipCategory.setOnClickListener { showCategoryFilter() }

        findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener { openEditor(0L) }
    }

    override fun onResume() {
        super.onResume()
        loadCategories()
        load()
    }

    private fun bizId() = Prefs.currentBusinessId(this)

    private fun loadCategories() {
        val appCtx = applicationContext
        Db.async({ InventoryRepo.get(appCtx).getCategories(bizId()) }) { result ->
            if (isFinishing || isDestroyed) return@async
            result.onSuccess { list ->
                categories = list
            }
        }
    }

    private fun showCategoryFilter() {
        val options = ArrayList<String>()
        options.add(getString(R.string.all_categories))
        options.addAll(categories.map { it.name })
        val ids = ArrayList<Long>()
        ids.add(0L)
        ids.addAll(categories.map { it.id })
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.category)
            .setItems(options.toTypedArray()) { _, which ->
                selectedCategoryId = ids[which]
                chipCategory.text =
                    if (selectedCategoryId == 0L) getString(R.string.all_categories)
                    else categories.firstOrNull { it.id == selectedCategoryId }?.name ?: getString(R.string.all_categories)
                load()
            }
            .show()
    }

    private fun load() {
        val appCtx = applicationContext
        val query = etSearch.text.toString()
        val onlyLow = chipLowStock.isChecked
        val categoryId = selectedCategoryId
        val businessId = bizId()
        requestId += 1
        val myId = requestId

        Db.async({
            val repo = InventoryRepo.get(appCtx)
            val list = repo.getProducts(businessId, query, categoryId, onlyLow)
            Pair(list, repo.getProductStats(businessId))
        }) { result ->
            if (isFinishing || isDestroyed || myId != requestId) return@async
            result.onSuccess { (list, productStats) ->
                stats = productStats
                adapter.submit(list)
                tvStats.text = getString(
                    R.string.product_stats_line,
                    productStats.productCount,
                    productStats.lowStockCount,
                    Money.format(productStats.stockValuePaise)
                )
                val empty = findViewById<TextView>(R.id.tvEmpty)
                if (list.isEmpty()) {
                    empty.setText(if (query.isBlank()) R.string.no_products else R.string.no_products_search)
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

    private fun openProduct(p: Product) {
        val intent = Intent(this, StockHistoryActivity::class.java)
        intent.putExtra(StockHistoryActivity.EXTRA_PRODUCT_ID, p.id)
        startActivity(intent)
    }

    private fun openEditor(id: Long) {
        val intent = Intent(this, ProductEditActivity::class.java)
        intent.putExtra(ProductEditActivity.EXTRA_PRODUCT_ID, id)
        startActivity(intent)
    }

    private fun confirmDelete(p: Product) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.product_delete_confirm, p.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val appCtx = applicationContext
                Db.async({ InventoryRepo.get(appCtx).deleteProduct(bizId(), p.id) }) { result ->
                    result.onSuccess {
                        Toast.makeText(appCtx, R.string.product_deleted, Toast.LENGTH_SHORT).show()
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
