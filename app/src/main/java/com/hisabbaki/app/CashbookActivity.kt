package com.hisabbaki.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.hisabbaki.app.data.AccountBalance
import com.hisabbaki.app.data.CashAccount
import com.hisabbaki.app.data.CashbookRepo
import com.hisabbaki.app.data.CashSummary
import com.hisabbaki.app.data.Db
import com.hisabbaki.app.data.ExpenseRepo
import com.hisabbaki.app.util.Dates
import com.hisabbaki.app.util.Money

class CashbookActivity : AppCompatActivity() {

    private var requestId = 0
    private var accounts: List<AccountBalance> = emptyList()

    private lateinit var tvStats: TextView
    private lateinit var tvCashIn: TextView
    private lateinit var tvCashOut: TextView
    private lateinit var tvCashHand: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cashbook)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        tvStats = findViewById(R.id.tvCashStats)
        tvCashIn = findViewById(R.id.tvCashIn)
        tvCashOut = findViewById(R.id.tvCashOut)
        tvCashHand = findViewById(R.id.tvCashHand)

        findViewById<ChipGroup>(R.id.chipGroupRange)
            .setOnCheckedStateChangeListener { _, _ -> load() }
        findViewById<MaterialButton>(R.id.btnAddAccount).setOnClickListener {
            showAccountDialog(null)
        }
        findViewById<MaterialButton>(R.id.btnTransfer).setOnClickListener {
            val cashAccounts = accounts.map { it.account }
            if (cashAccounts.size < 2) {
                Toast.makeText(this, R.string.need_two_accounts, Toast.LENGTH_SHORT).show()
            } else {
                showTransferDialog(cashAccounts)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun bizId() = Prefs.currentBusinessId(this)

    private fun rangeFromChips(): Pair<Long, Long> {
        val checked = findViewById<ChipGroup>(R.id.chipGroupRange).checkedChipId
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
            else -> Pair(0L, Long.MAX_VALUE)
        }
    }

    private fun load() {
        val appCtx = applicationContext
        val (from, to) = rangeFromChips()
        val businessId = bizId()
        requestId += 1
        val myId = requestId

        Db.async({
            val repo = CashbookRepo.get(appCtx)
            Pair(repo.getSummary(businessId, from, to), repo.getTransfers(businessId, from, to))
        }) { result ->
            if (isFinishing || isDestroyed || myId != requestId) return@async
            result.onSuccess { pair ->
                accounts = pair.first.accounts
                bindSummary(pair.first)
                bindTransfers(pair.second)
            }
            result.onFailure {
                Toast.makeText(appCtx, R.string.error_load, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bindSummary(summary: CashSummary) {
        tvCashIn.text = Money.format(summary.totalInPaise)
        tvCashOut.text = Money.format(summary.totalOutPaise)
        tvCashHand.text = Money.format(summary.totalCashPaise)
        tvStats.text = getString(
            R.string.cash_stats_line,
            Money.format(summary.totalInPaise),
            Money.format(summary.totalOutPaise),
            Money.format(summary.totalCashPaise)
        )

        val container = findViewById<LinearLayout>(R.id.accountsContainer)
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        accounts = summary.accounts
        for (balance in accounts) {
            val a = balance.account
            val row = inflater.inflate(R.layout.item_account_row, container, false)
            val tvType = row.findViewById<TextView>(R.id.tvAccountType)
            tvType.text = typeLabel(a.accountType)
            row.findViewById<TextView>(R.id.tvAccountName).text = a.name
            row.findViewById<TextView>(R.id.tvAccountBalance).text = Money.format(balance.balancePaise)
            row.findViewById<TextView>(R.id.tvAccountOpening).text =
                getString(R.string.opening_balance) + ": " + Money.format(a.openingPaise)
            val tvDefault = row.findViewById<TextView>(R.id.tvAccountDefault)
            tvDefault.visibility = if (a.isDefault) View.VISIBLE else View.GONE

            row.setOnClickListener { showAccountDialog(a) }
            row.setOnLongClickListener {
                confirmDeleteAccount(a)
                true
            }
            container.addView(row)
        }
    }

    private fun typeLabel(type: String): String = when (type) {
        "BANK" -> getString(R.string.pm_bank)
        "UPI" -> getString(R.string.pm_upi)
        else -> getString(R.string.pm_cash)
    }

    private fun bindTransfers(transfers: List<com.hisabbaki.app.data.CashTransfer>) {
        val container = findViewById<LinearLayout>(R.id.transfersContainer)
        container.removeAllViews()
        findViewById<TextView>(R.id.tvNoTransfers).visibility =
            if (transfers.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        for (t in transfers) {
            val row = inflater.inflate(R.layout.item_transfer_row, container, false)
            row.findViewById<TextView>(R.id.tvTransferRoute).text =
                t.fromName + " → " + t.toName
            var meta = Dates.formatDateTime(t.txTime)
            if (t.note.isNotBlank()) meta += "  •  " + t.note
            row.findViewById<TextView>(R.id.tvTransferMeta).text = meta
            row.findViewById<TextView>(R.id.tvTransferAmount).text = Money.format(t.amountPaise)
            row.setOnLongClickListener {
                confirmDeleteTransfer(t)
                true
            }
            container.addView(row)
        }
    }

    // ------------------------------------------------------------ account dialog

    private fun showAccountDialog(existing: CashAccount?) {
        val view = layoutInflater.inflate(R.layout.dialog_account_edit, null)
        val tilName = view.findViewById<TextInputLayout>(R.id.tilAccountName)
        val etName = view.findViewById<TextInputEditText>(R.id.etAccountName)
        val etOpening = view.findViewById<TextInputEditText>(R.id.etAccountOpening)
        val actType = view.findViewById<AutoCompleteTextView>(R.id.actAccountType)
        val switchDefault = view.findViewById<MaterialSwitch>(R.id.switchAccountDefault)

        val types = listOf("CASH", "BANK", "UPI")
        val labels = types.map { typeLabel(it) }
        actType.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))

        if (existing == null) {
            actType.setText(labels[0], false)
        } else {
            etName.setText(existing.name)
            actType.setText(typeLabel(existing.accountType), false)
            etOpening.setText(Money.toEditText(existing.openingPaise))
            switchDefault.isChecked = existing.isDefault
        }
        actType.setOnItemClickListener { _, _, position, _ -> actType.tag = types[position] }
        actType.tag = if (existing == null) "CASH" else existing.accountType

        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.add_account else R.string.edit_account)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .show()
            .also { dialog ->
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val name = etName.text.toString().trim()
                    if (name.isEmpty()) {
                        tilName.error = getString(R.string.error_account_name)
                        return@setOnClickListener
                    }
                    val openingText = etOpening.text.toString().trim()
                    val opening =
                        if (openingText.isEmpty()) 0L else Money.parseToPaise(openingText) ?: 0L
                    val type = (actType.tag as? String) ?: "CASH"
                    val appCtx = applicationContext
                    Db.async({
                        CashbookRepo.get(appCtx).saveAccount(
                            bizId(), existing?.id ?: 0L, name, type, opening, switchDefault.isChecked
                        )
                    }) { result ->
                        if (isFinishing || isDestroyed) return@async
                        result.onSuccess {
                            Toast.makeText(appCtx, R.string.account_saved, Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            load()
                        }
                        result.onFailure { error ->
                            Toast.makeText(
                                appCtx,
                                error.message ?: getString(R.string.error_generic),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
    }

    private fun confirmDeleteAccount(a: CashAccount) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete)
            .setMessage(R.string.delete_account_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                val appCtx = applicationContext
                Db.async({ CashbookRepo.get(appCtx).deleteAccount(bizId(), a.id) }) { result ->
                    if (isFinishing || isDestroyed) return@async
                    result.onSuccess { load() }
                    result.onFailure { error ->
                        Toast.makeText(
                            appCtx,
                            error.message ?: getString(R.string.error_generic),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------ transfer dialog

    private fun showTransferDialog(accounts: List<CashAccount>) {
        val view = layoutInflater.inflate(R.layout.dialog_transfer, null)
        val actFrom = view.findViewById<AutoCompleteTextView>(R.id.actTransferFrom)
        val actTo = view.findViewById<AutoCompleteTextView>(R.id.actTransferTo)
        val tilAmount = view.findViewById<TextInputLayout>(R.id.tilTransferAmount)
        val etAmount = view.findViewById<TextInputEditText>(R.id.etTransferAmount)
        val etNote = view.findViewById<TextInputEditText>(R.id.etTransferNote)

        val names = accounts.map { it.name }
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        actFrom.setAdapter(spinnerAdapter)
        actTo.setAdapter(spinnerAdapter)
        actFrom.setText(names[0], false)
        actTo.setText(names[if (names.size > 1) 1 else 0], false)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.make_transfer)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .show()
            .also { dialog ->
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val amountText = etAmount.text.toString().trim()
                    if (amountText.isEmpty()) {
                        tilAmount.error = getString(R.string.error_generic)
                        return@setOnClickListener
                    }
                    val amount = Money.parseToPaise(amountText) ?: 0L
                    if (amount <= 0L) {
                        tilAmount.error = getString(R.string.error_generic)
                        return@setOnClickListener
                    }
                    val from = accounts.getOrNull(names.indexOf(actFrom.text.toString()))
                    val to = accounts.getOrNull(names.indexOf(actTo.text.toString()))
                    if (from == null || to == null || from.id == to.id) {
                        Toast.makeText(this, R.string.need_two_accounts, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val appCtx = applicationContext
                    Db.async({
                        CashbookRepo.get(appCtx).transfer(
                            bizId(), from.id, to.id, amount,
                            etNote.text.toString().trim(), System.currentTimeMillis()
                        )
                    }) { result ->
                        if (isFinishing || isDestroyed) return@async
                        result.onSuccess {
                            Toast.makeText(appCtx, R.string.transfer_recorded, Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            load()
                        }
                        result.onFailure { error ->
                            Toast.makeText(
                                appCtx,
                                error.message ?: getString(R.string.error_generic),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
    }

    private fun confirmDeleteTransfer(t: com.hisabbaki.app.data.CashTransfer) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete)
            .setMessage(R.string.delete_transfer_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                val appCtx = applicationContext
                Db.async({ CashbookRepo.get(appCtx).deleteTransfer(bizId(), t.id) }) { result ->
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

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
