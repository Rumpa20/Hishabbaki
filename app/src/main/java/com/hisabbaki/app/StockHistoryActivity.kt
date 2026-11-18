package com.hisabbaki.app

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.hisabbaki.app.data.Db
import com.hisabbaki.app.data.InventoryRepo
import com.hisabbaki.app.data.Product
import com.hisabbaki.app.util.Qty

class StockHistoryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PRODUCT_ID = "product_id"
    }

    private var productId = 0L
    private var product: Product? = null
    private lateinit var adapter: StockMovementAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stock_history)

        productId = intent.getLongExtra(EXTRA_PRODUCT_ID, 0L)
        if (productId == 0L) {
            finish()
            return
        }

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        adapter = StockMovementAdapter("pcs")
        val rv = findViewById<RecyclerView>(R.id.rvMovements)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fabAdjust).setOnClickListener { showAdjustDialog() }

        load()
    }

    private fun bizId() = Prefs.currentBusinessId(this)

    private fun load() {
        val appCtx = applicationContext
        val id = productId
        Db.async({
            val repo = InventoryRepo.get(appCtx)
            Pair(repo.getProduct(bizId(), id), repo.getMovements(bizId(), id))
        }) { result ->
            if (isFinishing || isDestroyed) return@async
            result.onSuccess { pair ->
                val p = pair.first
                val movements = pair.second
                if (p == null) {
                    Toast.makeText(appCtx, R.string.error_not_found, Toast.LENGTH_SHORT).show()
                    finish()
                    return@onSuccess
                }
                product = p
                adapter.submit(movements)
                findViewById<TextView>(R.id.tvProductName).text = p.name
                findViewById<TextView>(R.id.tvCurrentStock).text =
                    getString(R.string.current_stock) + ": " + Qty.format(p.stockMilli, p.unit)
                findViewById<TextView>(R.id.tvEmpty).visibility =
                    if (movements.isEmpty()) View.VISIBLE else View.GONE
            }
            result.onFailure {
                Toast.makeText(appCtx, R.string.error_load, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAdjustDialog() {
        val p = product ?: return
        val options = arrayOf(
            getString(R.string.stock_in),
            getString(R.string.stock_out)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.adjust_stock) + " - " + p.name)
            .setItems(options) { _, which -> showQtyDialog(p, which == 0) }
            .show()
    }

    private fun showQtyDialog(p: Product, isIn: Boolean) {
        val view = layoutInflater.inflate(R.layout.dialog_stock_adjust, null)
        val tilQty = view.findViewById<TextInputLayout>(R.id.tilAdjustQty)
        val etQty = view.findViewById<TextInputEditText>(R.id.etAdjustQty)
        val etNote = view.findViewById<TextInputEditText>(R.id.etAdjustNote)
        val appCtx = applicationContext

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (isIn) R.string.stock_in else R.string.stock_out)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            tilQty.error = null
            val milli = Qty.parseToMilli(etQty.text.toString().trim())
            if (milli == null || milli == 0L) {
                tilQty.error = getString(R.string.error_qty)
                return@setOnClickListener
            }
            val delta = if (isIn) milli else -milli
            if (!isIn && p.stockMilli + delta < 0 && !p.allowNegative &&
                !Prefs.isAllowNegativeStock(this)
            ) {
                Toast.makeText(this, R.string.error_not_enough_stock, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val note = etNote.text.toString().trim()
            val now = System.currentTimeMillis()
            dialog.dismiss()
            Db.async({
                InventoryRepo.get(appCtx).applyStock(
                    bizId(), p.id, delta,
                    InventoryRepo.MOVE_ADJUSTMENT,
                    if (note.isEmpty()) appCtx.getString(
                        if (isIn) R.string.stock_in else R.string.stock_out
                    ) else note,
                    "manual_adjust", 0L, now
                )
            }) { result ->
                result.onSuccess {
                    if (!isFinishing && !isDestroyed) load()
                }
                result.onFailure { error ->
                    val msg = error.message
                    Toast.makeText(
                        appCtx, msg ?: appCtx.getString(R.string.error_generic), Toast.LENGTH_LONG
                    ).show()
                    if (!isFinishing && !isDestroyed) load()
                }
            }
        }
    }
}
