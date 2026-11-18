package com.hisabbaki.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.print.PrintManager
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hisabbaki.app.data.Db
import com.hisabbaki.app.data.LedgerData
import com.hisabbaki.app.data.Party
import com.hisabbaki.app.data.Repository
import com.hisabbaki.app.data.Tx
import com.hisabbaki.app.data.TxTypes
import com.hisabbaki.app.util.CsvExport
import com.hisabbaki.app.util.Dates
import com.hisabbaki.app.util.FileOps
import com.hisabbaki.app.util.LogoStore
import com.hisabbaki.app.util.Money
import com.hisabbaki.app.util.PdfExport
import com.hisabbaki.app.util.PdfPrintAdapter
import java.io.File
import java.io.FileOutputStream

/** Ledger of one customer or supplier: balance, totals, filters, transactions and exports. */
class LedgerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PARTY_ID = "party_id"
        const val EXTRA_SUPPLIER = "is_supplier"
        private const val STATE_FROM = "state_from"
        private const val STATE_TO = "state_to"
    }

    private var partyId = 0L
    private var isSupplier = false
    private var party: Party? = null
    private var fromMs: Long? = null
    private var toMs: Long? = null
    private var requestId = 0
    private var adapter: LedgerAdapter? = null

    private lateinit var toolbar: MaterialToolbar
    private lateinit var chipGroup: ChipGroup
    private lateinit var btnDateRange: MaterialButton
    private lateinit var btnClearDate: MaterialButton

    private val pdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> if (uri != null) savePdfTo(uri) }

    private val csvLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> if (uri != null) saveCsvTo(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ledger)

        partyId = intent.getLongExtra(EXTRA_PARTY_ID, 0L)
        isSupplier = intent.getBooleanExtra(EXTRA_SUPPLIER, false)
        if (partyId == 0L) {
            finish()
            return
        }

        if (savedInstanceState != null) {
            val f = savedInstanceState.getLong(STATE_FROM, 0L)
            val t = savedInstanceState.getLong(STATE_TO, 0L)
            if (f != 0L && t != 0L) {
                fromMs = f
                toMs = t
            }
        }

        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.ledger_menu)
        toolbar.overflowIcon = ContextCompat.getDrawable(this, R.drawable.ic_more)
        toolbar.menu.findItem(R.id.action_reminder)?.isVisible = !isSupplier
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_call -> callParty()
                R.id.action_edit_party -> editParty()
                R.id.action_whatsapp -> openWhatsApp()
                R.id.action_reminder -> openReminder()
                R.id.action_share_pdf -> sharePdf()
                R.id.action_download_pdf -> pdfLauncher.launch(pdfFileName())
                R.id.action_print -> printPdf()
                R.id.action_export_csv -> csvLauncher.launch(csvFileName())
            }
            true
        }

        val outLabel = if (isSupplier) R.string.tx_purchase else R.string.tx_gave
        val inLabel = if (isSupplier) R.string.tx_payment else R.string.tx_got

        chipGroup = findViewById(R.id.chipGroupFilter)
        findViewById<Chip>(R.id.chipIncrease).setText(outLabel)
        findViewById<Chip>(R.id.chipDecrease).setText(inLabel)
        chipGroup.setOnCheckedStateChangeListener { _, _ -> load() }

        findViewById<TextView>(R.id.tvTotalIncLabel).setText(outLabel)
        findViewById<TextView>(R.id.tvTotalDecLabel).setText(inLabel)

        val btnOut = findViewById<MaterialButton>(R.id.btnOut)
        val btnIn = findViewById<MaterialButton>(R.id.btnIn)
        btnOut.setText(outLabel)
        btnIn.setText(inLabel)
        btnOut.setOnClickListener { openTx(0L, TxTypes.increase(isSupplier)) }
        btnIn.setOnClickListener { openTx(0L, TxTypes.decrease(isSupplier)) }

        btnDateRange = findViewById(R.id.btnDateRange)
        btnClearDate = findViewById(R.id.btnClearDate)
        btnDateRange.setOnClickListener { showRangePicker() }
        btnClearDate.setOnClickListener {
            fromMs = null
            toMs = null
            updateDateButtons()
            load()
        }
        updateDateButtons()

        val ledgerAdapter = LedgerAdapter(
            isSupplier = isSupplier,
            onClick = { openTx(it.id, it.txType) },
            onEdit = { openTx(it.id, it.txType) },
            onDelete = { confirmDeleteTx(it) }
        )
        adapter = ledgerAdapter
        val rv = findViewById<RecyclerView>(R.id.rvLedger)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = ledgerAdapter
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_FROM, fromMs ?: 0L)
        outState.putLong(STATE_TO, toMs ?: 0L)
    }

    override fun onResume() {
        super.onResume()
        if (partyId != 0L) load()
    }

    private fun currentTypeFilter(): String? = when (chipGroup.checkedChipId) {
        R.id.chipIncrease -> TxTypes.increase(isSupplier)
        R.id.chipDecrease -> TxTypes.decrease(isSupplier)
        else -> null
    }

    private fun load() {
        val appCtx = applicationContext
        val supplier = isSupplier
        val id = partyId
        val typeFilter = currentTypeFilter()
        val from = fromMs
        val to = toMs
        requestId += 1
        val myId = requestId

        Db.async({ Repository.get(appCtx).getLedger(supplier, id, typeFilter, from, to) }) { result ->
            if (isFinishing || isDestroyed || myId != requestId) return@async
            result.onSuccess { data ->
                if (data == null) {
                    Toast.makeText(appCtx, R.string.error_not_found, Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    bind(data)
                }
            }
            result.onFailure {
                Toast.makeText(appCtx, R.string.error_load, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bind(data: LedgerData) {
        party = data.party
        toolbar.title = data.party.name
        toolbar.subtitle = if (data.party.mobile.isEmpty()) null else data.party.mobile

        val forYou = data.party.forYouPaise
        val colorRes: Int
        val labelRes: Int
        if (forYou > 0) {
            colorRes = R.color.get_green
            labelRes = R.string.you_will_get
        } else if (forYou < 0) {
            colorRes = R.color.give_red
            labelRes = R.string.you_will_give
        } else {
            colorRes = R.color.text_secondary
            labelRes = R.string.settled
        }
        findViewById<TextView>(R.id.tvLedgerBalanceLabel).setText(labelRes)
        val balance = findViewById<TextView>(R.id.tvLedgerBalance)
        balance.text = Money.format(Math.abs(forYou))
        balance.setTextColor(ContextCompat.getColor(this, colorRes))

        findViewById<TextView>(R.id.tvTotalInc).text = Money.format(data.totalIncreasePaise)
        findViewById<TextView>(R.id.tvTotalDec).text = Money.format(data.totalDecreasePaise)

        adapter?.submit(data.entries)
        findViewById<View>(R.id.tvLedgerEmpty).visibility =
            if (data.entries.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openTx(txId: Long, txType: String) {
        val intent = Intent(this, TransactionEditActivity::class.java)
        intent.putExtra(TransactionEditActivity.EXTRA_PARTY_ID, partyId)
        intent.putExtra(TransactionEditActivity.EXTRA_SUPPLIER, isSupplier)
        intent.putExtra(TransactionEditActivity.EXTRA_TX_ID, txId)
        intent.putExtra(TransactionEditActivity.EXTRA_TYPE, txType)
        startActivity(intent)
    }

    private fun confirmDeleteTx(tx: Tx) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete)
            .setMessage(R.string.confirm_delete_tx)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteTx(tx) }
            .show()
    }

    private fun deleteTx(tx: Tx) {
        val appCtx = applicationContext
        Db.async({ Repository.get(appCtx).deleteTransaction(tx.id) }) { result ->
            result.onSuccess {
                Toast.makeText(appCtx, R.string.tx_deleted, Toast.LENGTH_SHORT).show()
                if (!isFinishing && !isDestroyed) load()
            }
            result.onFailure {
                Toast.makeText(appCtx, R.string.error_generic, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ------------------------------------------------------------ contact actions

    private fun callParty() {
        val mobile = party?.mobile.orEmpty()
        if (mobile.isEmpty()) {
            Toast.makeText(this, R.string.error_no_mobile, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + mobile)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.error_app_missing, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWhatsApp() {
        val mobile = party?.mobile.orEmpty()
        if (mobile.isEmpty()) {
            Toast.makeText(this, R.string.error_no_mobile, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/91" + mobile)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.error_app_missing, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openReminder() {
        val intent = Intent(this, ReminderActivity::class.java)
        intent.putExtra(ReminderActivity.EXTRA_PARTY_ID, partyId)
        startActivity(intent)
    }

    private fun editParty() {
        val intent = Intent(this, PartyEditActivity::class.java)
        intent.putExtra(PartyEditActivity.EXTRA_ID, partyId)
        intent.putExtra(PartyEditActivity.EXTRA_SUPPLIER, isSupplier)
        startActivity(intent)
    }

    // ------------------------------------------------------------ PDF / CSV

    private fun baseName(): String = FileOps.safeName(party?.name ?: "ledger")

    private fun pdfFileName(): String = baseName() + "_ledger.pdf"

    private fun csvFileName(): String = baseName() + "_ledger.csv"

    /** Builds the full-history PDF statement in cache/shared. Blocking. */
    private fun buildPdfFile(): File {
        val appCtx = applicationContext
        val repo = Repository.get(appCtx)
        val data = repo.getLedger(isSupplier, partyId, null, null, null)
            ?: throw IllegalStateException("Ledger not found")
        val profile = repo.getBusinessProfile()
        val logo = LogoStore.load(appCtx, profile)
        val file = File(FileOps.shareDir(appCtx), FileOps.safeName(data.party.name) + "_ledger.pdf")
        FileOutputStream(file).use { PdfExport.ledger(this, profile, logo, data, it) }
        return file
    }

    private fun sharePdf() {
        val appCtx = applicationContext
        Db.async({ buildPdfFile() }) { result ->
            if (isFinishing || isDestroyed) return@async
            result.onSuccess { FileOps.share(this, it, "application/pdf", getString(R.string.share_ledger)) }
            result.onFailure { Toast.makeText(appCtx, R.string.export_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun savePdfTo(uri: Uri) {
        val appCtx = applicationContext
        Db.async({
            val file = buildPdfFile()
            FileOps.copyFileToUri(appCtx, file, uri)
        }) { result ->
            result.onSuccess { Toast.makeText(appCtx, R.string.export_done, Toast.LENGTH_SHORT).show() }
            result.onFailure { Toast.makeText(appCtx, R.string.export_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun printPdf() {
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

    private fun saveCsvTo(uri: Uri) {
        val appCtx = applicationContext
        val supplier = isSupplier
        val id = partyId
        Db.async({
            val data = Repository.get(appCtx).getLedger(supplier, id, null, null, null)
                ?: throw IllegalStateException("Ledger not found")
            FileOps.writeText(appCtx, uri, CsvExport.ledger(data))
        }) { result ->
            result.onSuccess { Toast.makeText(appCtx, R.string.export_done, Toast.LENGTH_SHORT).show() }
            result.onFailure { Toast.makeText(appCtx, R.string.export_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    // ------------------------------------------------------------ date range filter

    private fun showRangePicker() {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(R.string.date_range)
            .build()
        picker.addOnPositiveButtonClickListener { selection ->
            val start = selection.first
            val end = selection.second
            if (start != null && end != null) {
                val r = Dates.rangeFromPicker(start, end)
                fromMs = r.from
                toMs = r.to
                updateDateButtons()
                load()
            }
        }
        picker.show(supportFragmentManager, "range")
    }

    private fun updateDateButtons() {
        val from = fromMs
        val to = toMs
        if (from == null || to == null) {
            btnDateRange.setText(R.string.date_range)
            btnClearDate.visibility = View.GONE
        } else {
            btnDateRange.text = Dates.periodText(from, to)
            btnClearDate.visibility = View.VISIBLE
        }
    }
}
