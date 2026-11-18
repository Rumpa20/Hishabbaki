package com.hisabbaki.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hisabbaki.app.data.CoreRepo
import com.hisabbaki.app.data.Db
import com.hisabbaki.app.data.DeletedRecord
import com.hisabbaki.app.util.Dates

class RecycleBinActivity : AppCompatActivity() {

    private var requestId = 0
    private lateinit var container: LinearLayout
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recycle_bin)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        container = findViewById(R.id.binContainer)
        tvEmpty = findViewById(R.id.tvEmptyBin)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun bizId(): Int = Prefs.currentBusinessId(this)

    private fun load() {
        val appCtx = applicationContext
        val businessId = bizId()
        requestId += 1
        val myId = requestId

        Db.async({ CoreRepo.get(appCtx).getBinItems(businessId) }) { result ->
            if (isFinishing || isDestroyed || myId != requestId) return@async
            result.onSuccess { bind(it) }
            result.onFailure {
                Toast.makeText(appCtx, R.string.error_load, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun entityLabel(entity: String): String = when (entity) {
        "customer" -> getString(R.string.customer_who)
        "supplier" -> getString(R.string.supplier_who)
        "expense" -> getString(R.string.expenses)
        "product" -> getString(R.string.products)
        "transaction" -> getString(R.string.transactions_label)
        "staff" -> getString(R.string.staff)
        else -> entity
    }

    private fun bind(items: List<DeletedRecord>) {
        container.removeAllViews()
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        for (item in items) {
            val row = inflater.inflate(R.layout.item_bin_row, container, false)
            row.findViewById<TextView>(R.id.tvBinTitle).text =
                if (item.title.isBlank()) getString(R.string.ai_none) else item.title
            row.findViewById<TextView>(R.id.tvBinMeta).text =
                Dates.formatDateTime(item.deletedAt)
            row.findViewById<TextView>(R.id.tvBinEntity).text = entityLabel(item.entity)

            row.setOnClickListener { showItemDialog(item) }
            container.addView(row)
        }
    }

    private fun showItemDialog(item: DeletedRecord) {
        MaterialAlertDialogBuilder(this)
            .setTitle(item.title.ifBlank { getString(R.string.recycle_bin) })
            .setMessage(payloadPreview(item))
            .setPositiveButton(R.string.bin_restore) { _, _ -> restoreItem(item) }
            .setNeutralButton(R.string.delete, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
            .also { dialog ->
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    confirmPurge(item)
                    dialog.dismiss()
                }
            }
    }

    private fun payloadPreview(item: DeletedRecord): String {
        val deleted = getString(R.string.bin_deleted_at) + ": " + Dates.formatDateTime(item.deletedAt)
        val type = getString(R.string.bin_type) + ": " + entityLabel(item.entity)
        val payload = if (item.payload.isBlank()) "" else "\n\n" + item.payload
        return deleted + "\n" + type + payload
    }

    private fun restoreItem(item: DeletedRecord) {
        val appCtx = applicationContext
        Db.async({ CoreRepo.get(appCtx).restoreBinItem(bizId(), item.id) }) { result ->
            if (isFinishing || isDestroyed) return@async
            result.onSuccess { error ->
                if (error.isEmpty()) {
                    Toast.makeText(appCtx, R.string.bin_restore_done, Toast.LENGTH_SHORT).show()
                    load()
                } else {
                    Toast.makeText(
                        appCtx,
                        if (error == "PARTY_MISSING") {
                            getString(R.string.bin_party_missing)
                        } else {
                            error
                        },
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            result.onFailure {
                Toast.makeText(appCtx, R.string.bin_restore_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmPurge(item: DeletedRecord) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bin_purge)
            .setMessage(getString(R.string.bin_purge_confirm, item.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                val appCtx = applicationContext
                Db.async({ CoreRepo.get(appCtx).removeBinItem(item.id) }) { result ->
                    if (isFinishing || isDestroyed) return@async
                    result.onSuccess { load() }
                    result.onFailure {
                        Toast.makeText(appCtx, R.string.error_generic, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
