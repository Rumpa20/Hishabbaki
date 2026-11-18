package com.hisabbaki.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.hisabbaki.app.data.BillingRepo
import com.hisabbaki.app.data.Db
import com.hisabbaki.app.data.DraftItem
import com.hisabbaki.app.data.InvTypes
import com.hisabbaki.app.data.InvoiceDetail
import com.hisabbaki.app.data.InvoiceDraft
import com.hisabbaki.app.data.InventoryRepo
import com.hisabbaki.app.data.Product
import com.hisabbaki.app.data.Repository
import com.hisabbaki.app.util.Dates
import com.hisabbaki.app.util.Money
import com.hisabbaki.app.util.Qty
import java.util.Calendar
import java.util.TimeZone

class InvoiceEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INVOICE_ID = "invoice_id"
        const val EXTRA_TYPE = "invoice_type"
        private val TAX_STEPS = intArrayOf(0, 5, 12, 18, 28)
    }

    private class EditItem(
        var productId: Long,
        var name: String,
        var qtyMilli: Long,
        var unit: String,
        var unitPricePaise: Long,
        var discountPaise: Long,
        var taxRateBp: Int,
        var costPaise: Long
    )

    private var invoiceId = 0L
    private var invoiceType = InvTypes.SALE
    private var partyId = 0L
    private var invoiceDate = 0L
    private var dueDate = 0L
    private val items = ArrayList<EditItem>()
    private var productsCache: List<Product> = emptyList()
    private var pickerDialog: androidx.appcompat.app.AlertDialog? = null

    private lateinit var tilPartyName: TextInputLayout
    private lateinit var etPartyName: TextInputEditText
    private lateinit var etPartyMobile: TextInputEditText
    private lateinit var etPartyAddress: TextInputEditText
    private lateinit var btnDate: MaterialButton
    private lateinit var btnDueDate: MaterialButton
    private lateinit var switchGst: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var switchInterState: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var tilPlaceOfSupply: TextInputLayout
    private lateinit var etPlaceOfSupply: TextInputEditText
    private lateinit var etPaymentTerms: TextInputEditText
    private lateinit var etNotes: TextInputEditText
    private lateinit var etReceived: TextInputEditText
    private lateinit var etReference: TextInputEditText
    private lateinit var chipGroupMethod: ChipGroup
    private lateinit var itemsContainer: LinearLayout
    private lateinit var tvItemsEmpty: TextView
    private lateinit var tvSubtotal: TextView
    private lateinit var tvTaxLabel: TextView
    private lateinit var tvTaxTotal: TextView
    private lateinit var tvGrandTotal: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_invoice_edit)

        invoiceId = intent.getLongExtra(EXTRA_INVOICE_ID, 0L)
        invoiceType = intent.getStringExtra(EXTRA_TYPE) ?: InvTypes.SALE

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setTitle(if (invoiceId == 0L) R.string.new_invoice else R.string.edit_invoice)
        toolbar.setNavigationOnClickListener { finish() }

        tilPartyName = findViewById(R.id.tilPartyName)
        etPartyName = findViewById(R.id.etPartyName)
        etPartyMobile = findViewById(R.id.etPartyMobile)
        etPartyAddress = findViewById(R.id.etPartyAddress)
        btnDate = findViewById(R.id.btnDate)
        btnDueDate = findViewById(R.id.btnDueDate)
        switchGst = findViewById(R.id.switchGst)
        switchInterState = findViewById(R.id.switchInterState)
        tilPlaceOfSupply = findViewById(R.id.tilPlaceOfSupply)
        etPlaceOfSupply = findViewById(R.id.etPlaceOfSupply)
        etPaymentTerms = findViewById(R.id.etPaymentTerms)
        etNotes = findViewById(R.id.etNotes)
        etReceived = findViewById(R.id.etReceived)
        etReference = findViewById(R.id.etReference)
        chipGroupMethod = findViewById(R.id.chipGroupMethod)
        itemsContainer = findViewById(R.id.itemsContainer)
        tvItemsEmpty = findViewById(R.id.tvItemsEmpty)
        tvSubtotal = findViewById(R.id.tvSubtotal)
        tvTaxLabel = findViewById(R.id.tvTaxLabel)
        tvTaxTotal = findViewById(R.id.tvTaxTotal)
        tvGrandTotal = findViewById(R.id.tvGrandTotal)

        invoiceDate = System.currentTimeMillis()
        updateDateButtons()

        switchGst.setOnCheckedChangeListener { _, _ -> refreshGstFields() }
        findViewById<MaterialButton>(R.id.btnPickParty).setOnClickListener { showPartyPicker() }
        btnDate.setOnClickListener { showDatePicker(false) }
        btnDueDate.setOnClickListener { showDatePicker(true) }

        findViewById<MaterialButton>(R.id.btnAddProduct).setOnClickListener { showProductPicker() }
        findViewById<MaterialButton>(R.id.btnAddCustom).setOnClickListener {
            showItemDialog(null, null, null, null, null, 0L, null, 0L)
        }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }

        if (invoiceId != 0L) load() else refreshGstFields()
    }

    private fun bizId() = Prefs.currentBusinessId(this)

    private fun load() {
        val appCtx = applicationContext
        val id = invoiceId
        Db.async({ BillingRepo.get(appCtx).getInvoice(bizId(), id) }) { result ->
            if (isFinishing || isDestroyed) return@async
            result.onSuccess { detail ->
                if (detail == null) {
                    Toast.makeText(appCtx, R.string.error_not_found, Toast.LENGTH_SHORT).show()
                    finish()
                    return@onSuccess
                }
                bind(detail)
            }
            result.onFailure {
                Toast.makeText(appCtx, R.string.error_load, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bind(d: InvoiceDetail) {
        val inv = d.invoice
        invoiceType = inv.invoiceType
        partyId = inv.partyId
        etPartyName.setText(inv.partyName)
        etPartyMobile.setText(inv.partyMobile)
        etPartyAddress.setText(inv.partyAddress)
        invoiceDate = inv.invoiceDate
        dueDate = inv.dueDate
        etPaymentTerms.setText(inv.paymentTerms)
        etNotes.setText(inv.notes)
        switchGst.isChecked = inv.isGst
        etPlaceOfSupply.setText(inv.placeOfSupply)
        items.clear()
        for (item in d.items) {
            items.add(
                EditItem(
                    item.productId, item.name, item.qtyMilli, item.unit,
                    item.unitPricePaise, item.discountPaise, item.taxRateBp, item.costPaise
                )
            )
        }
        updateDateButtons()
        refreshGstFields()
        rebuildItems()
    }

    private fun refreshGstFields() {
        val gst = switchGst.isChecked
        switchInterState.visibility = if (gst) View.VISIBLE else View.GONE
        tilPlaceOfSupply.visibility = if (gst) View.VISIBLE else View.GONE
        recalcSummary()
    }

    private fun updateDateButtons() {
        btnDate.text = Dates.formatDate(invoiceDate)
        btnDueDate.text = if (dueDate == 0L) getString(R.string.recur_none) else Dates.formatDate(dueDate)
    }

    private fun showDatePicker(forDue: Boolean) {
        val base = if (forDue && dueDate != 0L) dueDate else invoiceDate
        val cal = Calendar.getInstance()
        cal.timeInMillis = base
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utc.clear()
        utc.set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))

        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.select_date)
            .setSelection(utc.timeInMillis)
            .build()
        picker.addOnPositiveButtonClickListener { selection ->
            val day = Dates.utcDateToLocalDayStart(selection)
            if (forDue) {
                dueDate = if (dueDate == day) 0L else day
            } else {
                invoiceDate = day
                if (dueDate != 0L && dueDate < invoiceDate) dueDate = invoiceDate
            }
            updateDateButtons()
        }
        picker.show(supportFragmentManager, "inv_date")
    }

    // ------------------------------------------------------------ items

    private fun showProductPicker() {
        val appCtx = applicationContext
        Db.async({ InventoryRepo.get(appCtx).getProducts(bizId(), "", 0L, false) }) { result ->
            if (isFinishing || isDestroyed) return@async
            result.onSuccess { list ->
                productsCache = list
                if (list.isEmpty()) {
                    Toast.makeText(appCtx, R.string.no_products, Toast.LENGTH_SHORT).show()
                    return@onSuccess
                }
                showProductPickerDialog(list)
            }
            result.onFailure {
                Toast.makeText(appCtx, R.string.error_load, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showProductPickerDialog(all: List<Product>) {
        val view = layoutInflater.inflate(R.layout.dialog_product_picker, null)
        val etSearch = view.findViewById<EditText>(R.id.etPickerSearch)
        val rv = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvPicker)
        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        val adapter = ProductAdapter(
            onClick = { product ->
                pickerDialog?.dismiss()
                showItemDialog(
                    product.id, product.name, product.stockMilli, product.unit,
                    product.sellingPricePaise, 0L, sellingTaxBp(), product.purchasePricePaise
                )
            },
            onEdit = {},
            onDelete = {}
        )
        rv.adapter = adapter
        adapter.submit(all)

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s.toString()
                adapter.submit(
                    if (q.isBlank()) all else all.filter {
                        it.name.contains(q, true) || it.sku.contains(q, true) || it.barcode.contains(q, true)
                    }
                )
            }
        })

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_product)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .show()
        pickerDialog = dialog
    }

    private fun sellingTaxBp(): Int = 0

    private fun showItemDialog(
        productId: Long?,
        name: String?,
        qtyHintMilli: Long?,
        unit: String?,
        rateHint: Long?,
        discountHint: Long?,
        taxBp: Int?,
        costHint: Long?,
        editIndex: Int = -1
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_item_entry, null)
        val tilName = view.findViewById<TextInputLayout>(R.id.tilItemName)
        val etName = view.findViewById<TextInputEditText>(R.id.etItemName)
        val etQty = view.findViewById<TextInputEditText>(R.id.etItemQty)
        val etRate = view.findViewById<TextInputEditText>(R.id.etItemRate)
        val etDiscount = view.findViewById<TextInputEditText>(R.id.etItemDiscount)
        val tilRate = view.findViewById<TextInputLayout>(R.id.tilItemRate)
        val actTax = view.findViewById<AutoCompleteTextView>(R.id.actItemTax)

        if (productId != null && productId != 0L) { etName.setText(name); etName.isEnabled = false }
        etRate.setText(if (rateHint != null && rateHint > 0) Money.toEditText(rateHint) else "")

        val taxLabels = TAX_STEPS.map { it.toString() + "%" }
        actTax.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, taxLabels))
        var selectedBp = taxBp ?: 0
        val labelForBp = { bp: Int ->
            val idx = TAX_STEPS.indexOf(bp / 100).takeIf { it >= 0 } ?: 0
            taxLabels[idx]
        }
        actTax.setText(labelForBp(selectedBp), false)
        actTax.setOnItemClickListener { _, _, position, _ ->
            selectedBp = TAX_STEPS[position] * 100
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_item)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .show()

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val itemName = etName.text.toString().trim()
            if (itemName.isEmpty()) { tilName.error = getString(R.string.error_product_name); return@setOnClickListener }
            val qty = Qty.parseToMilli(etQty.text.toString().trim())
            if (qty == null || qty == 0L) return@setOnClickListener
            val rateText = etRate.text.toString().trim()
            if (rateText.isEmpty()) { tilRate.error = getString(R.string.error_generic); return@setOnClickListener }
            val ratePaise = Money.parseToPaise(rateText)
            if (ratePaise == null || ratePaise == 0L) { tilRate.error = getString(R.string.error_generic); return@setOnClickListener }
            val discountText = etDiscount.text.toString().trim()
            val discount = if (discountText.isEmpty()) 0L else Money.parseToPaise(discountText) ?: 0L

            val newItem = EditItem(
                productId ?: 0L, itemName, qty,
                unit ?: "", ratePaise, discount, selectedBp, costHint ?: 0L
            )
            if (editIndex >= 0 && editIndex < items.size) items[editIndex] = newItem else items.add(newItem)
            rebuildItems()
            dialog.dismiss()
        }
    }

    private fun rebuildItems() {
        itemsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        tvItemsEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

        for (i in items.indices) {
            val item = items[i]
            val row = inflater.inflate(R.layout.item_invoice_edit_row, itemsContainer, false)
            val tvName = row.findViewById<TextView>(R.id.tvRowName)
            val tvQty = row.findViewById<TextView>(R.id.tvRowQty)
            val tvAmount = row.findViewById<TextView>(R.id.tvRowAmount)
            val btnRemove = row.findViewById<ImageButton>(R.id.btnRemove)
            val btnEdit = row.findViewById<ImageButton>(R.id.btnEditRow)

            tvName.text = item.name
            tvQty.text = Qty.format(item.qtyMilli, item.unit) + " x " + Money.format(item.unitPricePaise)
            val draft = toDraftItem(item)
            val total = Money.format(draft.qtyMilli * draft.unitPricePaise / 1000L - draft.discountPaise)
            tvAmount.text = total

            btnRemove.setOnClickListener {
                items.removeAt(i)
                rebuildItems()
            }
            btnEdit.setOnClickListener {
                showItemDialog(
                    if (item.productId != 0L) item.productId else null,
                    item.name, item.qtyMilli, item.unit,
                    item.unitPricePaise, item.discountPaise, item.taxRateBp, item.costPaise, i
                )
            }
            itemsContainer.addView(row)
        }
        recalcSummary()
    }

    private fun toDraftItem(item: EditItem): DraftItem = DraftItem(
        productId = item.productId,
        name = item.name,
        qtyMilli = item.qtyMilli,
        unit = item.unit,
        unitPricePaise = item.unitPricePaise,
        discountPaise = item.discountPaise,
        taxRateBp = item.taxRateBp,
        costPaise = item.costPaise
    )

    private fun recalcSummary() {
        val drafts = items.map { toDraftItem(it) }
        val (subtotal, discount, tax) = com.hisabbaki.app.data.InvoiceMath.recalc(
            drafts, switchGst.isChecked, switchInterState.isChecked
        )
        tvSubtotal.text = Money.format(subtotal - discount)
        tvTaxTotal.text = Money.format(tax)
        tvTaxLabel.text = if (switchGst.isChecked && tax > 0) {
            getString(if (switchInterState.isChecked) R.string.igst else R.string.cgst) + " + " +
                getString(if (switchInterState.isChecked) R.string.igst else R.string.sgst)
        } else {
            getString(R.string.tax)
        }
        tvGrandTotal.text = Money.format(subtotal - discount + tax)
    }

    // ------------------------------------------------------------ party

    private fun showPartyPicker() {
        val appCtx = applicationContext
        val supplier = InvTypes.defaultIsSupplier(invoiceType)
        Db.async({ Repository.get(appCtx).getParties(supplier, "", false) }) { result ->
            if (isFinishing || isDestroyed) return@async
            result.onSuccess { parties ->
                val options = ArrayList<String>()
                options.add(getString(R.string.walk_in))
                options.addAll(parties.map { it.name })
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.select_party)
                    .setItems(options.toTypedArray()) { _, which ->
                        if (which == 0) {
                            partyId = 0L
                            etPartyName.setText("")
                            etPartyMobile.setText("")
                            etPartyAddress.setText("")
                        } else {
                            val p = parties[which - 1]
                            partyId = p.id
                            etPartyName.setText(p.name)
                            etPartyMobile.setText(p.mobile)
                            etPartyAddress.setText(p.address)
                        }
                    }
                    .show()
            }
            result.onFailure {
                Toast.makeText(appCtx, R.string.error_load, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ------------------------------------------------------------ save

    private fun methodFromChips(): String = when (chipGroupMethod.checkedChipId) {
        R.id.chipUpi -> "UPI"
        R.id.chipBank -> "BANK"
        R.id.chipOther -> "OTHER"
        else -> "CASH"
    }

    private fun save() {
        tilPartyName.error = null
        val partyName = etPartyName.text.toString().trim()
        if (partyName.isEmpty()) {
            tilPartyName.error = getString(R.string.error_party_name)
            return
        }
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.error_invoice_no_items, Toast.LENGTH_LONG).show()
            return
        }

        val receivedText = etReceived.text.toString().trim()
        val received = if (receivedText.isEmpty()) 0L else Money.parseToPaise(receivedText) ?: 0L

        val draft = InvoiceDraft(
            id = invoiceId,
            invoiceType = invoiceType,
            partyId = partyId,
            partyIsSupplier = InvTypes.defaultIsSupplier(invoiceType),
            partyName = partyName,
            partyMobile = etPartyMobile.text.toString().trim(),
            partyAddress = etPartyAddress.text.toString().trim(),
            invoiceDate = invoiceDate,
            dueDate = dueDate,
            paymentTerms = etPaymentTerms.text.toString().trim(),
            isGst = switchGst.isChecked,
            interState = switchInterState.isChecked,
            placeOfSupply = etPlaceOfSupply.text.toString().trim(),
            notes = etNotes.text.toString().trim(),
            items = items.map { toDraftItem(it) },
            paidNowPaise = received,
            paidMethod = methodFromChips(),
            paidReference = etReference.text.toString().trim(),
            paidAt = System.currentTimeMillis()
        )

        val appCtx = applicationContext
        Db.async({ BillingRepo.get(appCtx).saveInvoice(bizId(), draft) }) { result ->
            if (isFinishing || isDestroyed) return@async
            result.onSuccess {
                Toast.makeText(appCtx, R.string.invoice_saved, Toast.LENGTH_SHORT).show()
                finish()
            }
            result.onFailure { error ->
                val msg = error.message
                Toast.makeText(appCtx, msg ?: getString(R.string.error_generic), Toast.LENGTH_LONG).show()
            }
        }
    }
}
