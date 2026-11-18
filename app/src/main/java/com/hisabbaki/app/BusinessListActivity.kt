package com.hisabbaki.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.hisabbaki.app.data.CoreRepo
import com.hisabbaki.app.data.Db

class BusinessListActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_business_list)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        container = findViewById(R.id.businessContainer)
        findViewById<MaterialButton>(R.id.btnAddBusiness).setOnClickListener { showCreateDialog() }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val appCtx = applicationContext
        Db.async({ CoreRepo.get(appCtx).getBusinesses() }) { result ->
            if (isFinishing || isDestroyed) return@async
            result.onSuccess { bind(it) }
            result.onFailure { Toast.makeText(appCtx, R.string.error_load, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun currentId(): Int = Prefs.currentBusinessId(this)

    private fun bind(businesses: List<com.hisabbaki.app.data.Business>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (b in businesses) {
            val row = inflater.inflate(R.layout.item_business_row, container, false)
            val tvName = row.findViewById<TextView>(R.id.tvBusinessName)
            val tvActive = row.findViewById<TextView>(R.id.tvBusinessActive)
            tvName.text = if (b.name.isBlank()) getString(R.string.my_business) else b.name
            tvActive.visibility = if (b.id == currentId()) View.VISIBLE else View.GONE

            row.setOnClickListener { switchTo(b.id) }
            row.findViewById<View>(R.id.btnEditBusiness).setOnClickListener {
                startActivity(
                    Intent(this, BusinessProfileActivity::class.java)
                        .putExtra(BusinessProfileActivity.EXTRA_BUSINESS_ID, b.id)
                )
            }
            row.setOnLongClickListener {
                if (b.id == currentId()) {
                    Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
                } else {
                    confirmDelete(b)
                }
                true
            }
            container.addView(row)
        }
    }

    private fun switchTo(id: Int) {
        if (id == currentId()) return
        Prefs.setCurrentBusinessId(this, id)
        Toast.makeText(this, R.string.business_switched, Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun showCreateDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_new_business, null)
        val etName = view.findViewById<TextInputEditText>(R.id.etBusinessName)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_business)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = etName.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.error_business_name, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val appCtx = applicationContext
                Db.async({ CoreRepo.get(appCtx).saveBusiness(0, name, "", "", "", "", "", "", "INR") }) { res ->
                    if (isFinishing || isDestroyed) return@async
                    res.onSuccess { load() }
                    res.onFailure { e ->
                        Toast.makeText(
                            appCtx, e.message ?: getString(R.string.error_generic), Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(b: com.hisabbaki.app.data.Business) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_business)
            .setMessage(getString(R.string.delete_business_confirm, b.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                val appCtx = applicationContext
                Db.async({ CoreRepo.get(appCtx).deleteBusiness(b.id) }) { res ->
                    if (isFinishing || isDestroyed) return@async
                    res.onSuccess { load() }
                    res.onFailure { e ->
                        Toast.makeText(
                            appCtx, e.message ?: getString(R.string.error_generic), Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
