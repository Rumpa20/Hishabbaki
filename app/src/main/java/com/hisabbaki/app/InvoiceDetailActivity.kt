package com.hisabbaki.app

import android.content.Intent
import android.os.Bundle
import android.print.PrintManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.hisabbaki.app.data.BillingRepo
import com.hisabbaki.app.data.Db
import com.hisabbaki.app.data.InvoiceDetail
import com.hisabbaki.app.data.InvoicePayment
import com.hisabbaki.app.data.Repository
import com.hisabbaki.app.util.Dates
import com.hisabbaki.app.util.FileOps
import com.hisabbaki.app.util.LogoStore
import com.hisabbaki.app.util.Money
import com.hisabbaki.app.util.PdfExport
import com.hisabbaki.app.util.PdfPrintAdapter
import java.io.File
import java.io.FileOutputStream

class InvoiceDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INVOICE_ID = "invoice_id"
    }

    private lateinit var paymentsAdapter: PaymentsAdapter
    private var invoiceId = 0L
    private var detail: InvoiceDetail? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_invoice_detail)

        invoiceId = intent.getLongExtra(EXTRA_INVOICE_ID, 0L)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.invoice_menu)
        toolbar.overflowIcon = ContextCompat.getDrawable(this, R.drawable.ic_more)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_share_pdf -> { sharePdf(); true }
                R.id.action_print -> { printPdf(); true }
                R.id.action_delete -> { confirmDelete(); true }
                else -> false
            }
        }

        val rv = findViewById<RecyclerView>(R.id.rvPayments)
        rv.layoutManager = LinearLayoutManager(this)
        rv.isNestedScrollingEnabled = false
        paymentsAdapter = PaymentsAdapter { pay -> confirmDeletePayment(pay) }
        rv.adapter = paymentsAdapter

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAddPayment)
            .setOnClickListener { showAddPayment() }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun bizId() = Prefs.currentBusinessId(this)

    private fun load() {
        val appCtx = applicationContext
        val id = invoiceId
        Db.async({ BillingRepo.get(appCtx).getInvoice(bizId(), id) }) { result ->
            if (isFinishing || isDestroyed) return@async
            result.onSuccess { loaded ->
                if (loaded == null) {
                    Toast.makeText(appCtx, R.string.error_not_found, Toast.LENGTH_SHORT).show()
                    finish()
                    return@onSuccess
                }
                detail = loaded
                bind(loaded)
            }
            result.onFailure {
                Toast.makeText(appCtx, R.string.error_load, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bind(d: InvoiceDetail) {
        val inv = d.invoice
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.title = InvoicesAdapter.typeLabelStatic(this, inv.invoiceType)
        toolbar.subtitle = inv.invoiceNo + "  •  " + Dates.formatDate(inv.invoiceDate)

        val total = findViewById<TextView>(R.id.tvTotal)
        val status = findViewById<TextView>(R.id.tvStatus)
        total.text = Money.format(inv.totalPaise)
        status.text = InvoicesAdapter.statusLabelStatic(this, inv.status)
        val colorRes = when (inv.status) {
            "PAID" -> R.color.get_green
            "PARTIAL" -> R.color.primary
            else -> R.color.give_red
        }
        status.setTextColor(ContextCompat.getColor(this, colorRes))
        total.setTextColor(ContextCompat.getColor(this, colorRes))

        findViewById<TextView>(R.id.tvPaid).text = Money.format(inv.paidPaise)
        findViewById<TextView>(R.id.tvBalance).text = Money.format(inv.totalPaise - inv.paidPaise)

        val info = StringBuilder()
        info.append(getString(R.string.invoice_no)).append(": ").append(inv.invoiceNo)
        info.append("\n").append(getString(R.string.invoice_date)).append(": ")
            .append(Dates.formatDate(inv.invoiceDate))
        if (inv.dueDate > 0) {
            info.append("\n").append(getString(R.string.due_date)).append(": ")
                .append(Dates.formatDate(inv.dueDate))
        }
        findViewById<TextView>(R.id.tvDocInfo).text = info

        val party = StringBuilder()
        party.append(getString(R.string.party)).append(": ").append(inv.partyName)
        if (inv.partyMobile.isNotBlank()) party.append("\n").append(inv.partyMobile)
        if (inv.partyAddress.isNotBlank()) party.append("\n").append(inv.partyAddress.replace('\n', ' '))
        findViewById<TextView>(R.id.tvPartyInfo).text = party

        val extra = StringBuilder()
        if (inv.isGst) {
            extra.append(getString(R.string.pdf_tax_invoice))
            if (inv.placeOfSupply.isNotBlank()) {
                extra.append("  •  ").append(getString(R.string.place_of_supply)).append(": ")
                    .append(inv.placeOfSupply)
            }
        }
        findViewById<TextView>(R.id.tvExtraInfo).visibility =
            if (extra.isEmpty()) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.tvExtraInfo).text = extra

        findViewById<TextView>(R.id.tvSubtotal).text = Money.format(inv.subtotalPaise - inv.discountPaise)

        var cgst = 0L
        var igst = 0L
        for (item in d.items) { cgst += item.cgstPaise; igst += item.igstPaise }
        val taxRow = findViewById<View>(R.id.taxRow)
        if (inv.taxPaise > 0) {
            taxRow.visibility = View.VISIBLE
            findViewById<TextView>(R.id.tvTaxLabel).text =
                if (igst > 0) getString(R.string.igst)
                else getString(R.string.cgst) + " + " + getString(R.string.sgst)
            findViewById<TextView>(R.id.tvTaxTotal).text = Money.format(inv.taxPaise)
        } else {
            taxRow.visibility = View.GONE
        }
        findViewById<TextView>(R.id.tvGrandTotal).text = Money.format(inv.totalPaise)

        val terms = StringBuilder()
        if (inv.paymentTerms.isNotBlank()) {
            terms.append(getString(R.string.payment_terms)).append(": ").append(inv.paymentTerms)
        }
        if (inv.notes.isNotBlank()) {
            if (terms.isNotEmpty()) terms.append("\n")
            terms.append(inv.notes)
        }
        val tvTerms = findViewById<TextView>(R.id.tvTermsNotes)
        tvTerms.visibility = if (terms.isEmpty()) View.GONE else View.VISIBLE
        tvTerms.text = terms

        buildItemsRows(d)

        paymentsAdapter.submit(d.payments)
        findViewById<TextView>(R.id.tvNoPayments).visibility =
            if (d.payments.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun buildItemsRows(d: InvoiceDetail) {
        val container = findViewById<LinearLayout>(R.id.itemsContainer)
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (item in d.items) {
            val row = inflater.inflate(R.layout.item_invoice_line, container, false)
            val qty = java.math.BigDecimal.valueOf(item.qtyMilli, 3).stripTrailingZeros().toPlainString() +
                if (item.unit.isBlank()) "" else " " + item.unit
            row.findViewById<TextView>(R.id.tvLineName).text = item.name
            row.findViewById<TextView>(R.id.tvLineTotal).text = Money.format(item.lineTotalPaise)
            val sub = StringBuilder()
            sub.append(qty).append(" × ").append(Money.format(item.unitPricePaise, false))
            if (item.discountPaise > 0) {
                sub.append("  •  ").append(getString(R.string.col_discount)).append(" ")
                    .append(Money.format(item.discountPaise, false))
            }
            if (item.taxRateBp > 0) {
                sub.append("  •  ").append(getString(R.string.tax)).append(" ")
                    .append(item.taxRateBp / 100).append("%")
            }
            row.findViewById<TextView>(R.id.tvLineSub).text = sub
            container.addView(row)
        }
    }

    // ------------------------------------------------------------ payments

    private fun showAddPayment() {
        val view = layoutInflater.inflate(R.layout.dialog_payment_entry, null)
        val tilAmount = view.findViewById<TextInputLayout>(R.id.tilPaymentAmount)
        val etAmount = view.findViewById<TextInputEditText>(R.id.etPaymentAmount)
        val etRef = view.findViewById<TextInputEditText>(R.id.etPaymentReference)
        val chipGroup = view.findViewById<ChipGroup>(R.id.chipGroupMethod)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_payment)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val amountText = etAmount.text.toString().trim()
                if (amountText.isEmpty()) return@setPositiveButton
                val amount = Money.parseToPaise(amountText) ?: 0L
                if (amount <= 0L) return@setPositiveButton
                val method = when (chipGroup.checkedChipId) {
                    R.id.chipUpi -> "UPI"
                    R.id.chipBank -> "BANK"
                    R.id.chipOther -> "OTHER"
                    else -> "CASH"
                }
                val appCtx = applicationContext
                Db.async({
                    BillingRepo.get(appCtx).addInvoicePayment(
                        bizId(), invoiceId, amount, method,
                        etRef.text.toString().trim(), System.currentTimeMillis()
                    )
                }) { result ->
                    if (isFinishing || isDestroyed) return@async
                    result.onSuccess {
                        Toast.makeText(appCtx, R.string.payment_added, Toast.LENGTH_SHORT).show()
                        load()
                    }
                    result.onFailure {
                        Toast.makeText(appCtx, R.string.error_generic, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
        tilAmount.error = null
    }

    private fun confirmDeletePayment(pay: InvoicePayment) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete)
            .setMessage(R.string.delete_payment_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                val appCtx = applicationContext
                Db.async({ BillingRepo.get(appCtx).deleteInvoicePayment(bizId(), invoiceId, pay.id) }) { result ->
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

    // ------------------------------------------------------------ pdf

    private fun baseName(): String {
        val d = detail ?: return "invoice"
        return FileOps.safeName(d.invoice.invoiceNo)
    }

    private fun buildPdfFile(): File {
        val appCtx = applicationContext
        val d = detail ?: throw IllegalStateException("Invoice not loaded")
        val repo = Repository.get(appCtx)
        val profile = repo.getBusinessProfile()
        val logo = LogoStore.load(appCtx, profile)
        val file = File(FileOps.shareDir(appCtx), baseName() + ".pdf")
        FileOutputStream(file).use { PdfExport.invoice(appCtx, profile, logo, d, it) }
        return file
    }

    private fun sharePdf() {
        if (detail == null) return
        val appCtx = applicationContext
        Db.async({ buildPdfFile() }) { result ->
            if (isFinishing || isDestroyed) return@async
            result.onSuccess { FileOps.share(this, it, "application/pdf", getString(R.string.share_invoice)) }
            result.onFailure { Toast.makeText(appCtx, R.string.export_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun printPdf() {
        if (detail == null) return
        val appCtx = applicationContext
        Db.async({ buildPdfFile() }) { result ->
            if (isFinishing || isDestroyed) return@async
            result.onSuccess { file ->
                val jobName = getString(R.string.app_name) + " - " + baseName()
                val pm = getSystemService(PrintManager::class.java)
                if (pm == null) {
                    Toast.makeText(appCtx, R.string.error_app_missing, Toast.LENGTH_SHORT).show()
                } else {
                    pm.print(jobName, PdfPrintAdapter(file, jobName), null)
                }
            }
            result.onFailure { Toast.makeText(appCtx, R.string.export_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun confirmDelete() {
        val d = detail ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete)
            .setMessage(
                getString(
                    R.string.invoice_delete_confirm,
                    InvoicesAdapter.typeLabelStatic(this, d.invoice.invoiceType), d.invoice.invoiceNo
                )
            )
            .setPositiveButton(R.string.delete) { _, _ ->
                val appCtx = applicationContext
                Db.async({ BillingRepo.get(appCtx).deleteInvoice(bizId(), invoiceId) }) { result ->
                    if (isFinishing || isDestroyed) return@async
                    result.onSuccess { deleted ->
                        if (deleted) {
                            Toast.makeText(appCtx, R.string.invoice_deleted, Toast.LENGTH_SHORT).show()
                            finish()
                        } else {
                            Toast.makeText(appCtx, R.string.error_generic, Toast.LENGTH_SHORT).show()
                        }
                    }
                    result.onFailure {
                        Toast.makeText(appCtx, R.string.error_generic, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------ payments list

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val amount: TextView = v.findViewById(R.id.tvPayAmount)
        val meta: TextView = v.findViewById(R.id.tvPayMeta)
        val delete: ImageButton = v.findViewById(R.id.btnPayDelete)
    }

    private inner class PaymentsAdapter(
        private val onDelete: (InvoicePayment) -> Unit
    ) : RecyclerView.Adapter<VH>() {

        private var items: List<InvoicePayment> = emptyList()

        fun submit(list: List<InvoicePayment>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_invoice_payment, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val pay = items[position]
            val ctx = holder.itemView.context
            holder.amount.text = Money.format(pay.amountPaise)
            val method = when (pay.method) {
                "UPI" -> R.string.pm_upi
                "BANK" -> R.string.pm_bank
                "OTHER" -> R.string.pm_other
                else -> R.string.pm_cash
            }
            var meta = Dates.formatDate(pay.paidAt) + "  •  " + ctx.getString(method)
            if (pay.referenceNo.isNotBlank()) meta += "  •  " + pay.referenceNo
            holder.meta.text = meta
            holder.delete.setOnClickListener { onDelete(pay) }
        }
    }
}
