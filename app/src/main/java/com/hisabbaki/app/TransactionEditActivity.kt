package com.hisabbaki.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.hisabbaki.app.data.Db
import com.hisabbaki.app.data.Repository
import com.hisabbaki.app.data.Tx
import com.hisabbaki.app.data.TxTypes
import com.hisabbaki.app.util.Money
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Add or edit one transaction (You gave / You got for customers, Purchase / Payment for suppliers). */
class TransactionEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PARTY_ID = "party_id"
        const val EXTRA_SUPPLIER = "is_supplier"
        const val EXTRA_TX_ID = "tx_id"
        const val EXTRA_TYPE = "tx_type"
        private const val STATE_TIME = "state_time"
        private const val STATE_UUID = "state_uuid"
    }

    private var partyId = 0L
    private var isSupplier = false
    private var txId = 0L
    private var txTime = 0L
    private var clientUuid = ""

    private lateinit var tilAmount: TextInputLayout
    private lateinit var etAmount: TextInputEditText
    private lateinit var etDescription: TextInputEditText
    private lateinit var etReference: TextInputEditText
    private lateinit var etNotes: TextInputEditText
    private lateinit var toggle: MaterialButtonToggleGroup
    private lateinit var chipMethods: ChipGroup
    private lateinit var btnDate: MaterialButton
    private lateinit var btnTime: MaterialButton
    private lateinit var btnSave: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transaction_edit)

        partyId = intent.getLongExtra(EXTRA_PARTY_ID, 0L)
        isSupplier = intent.getBooleanExtra(EXTRA_SUPPLIER, false)
        txId = intent.getLongExtra(EXTRA_TX_ID, 0L)
        if (partyId == 0L && txId == 0L) {
            finish()
            return
        }

        txTime = savedInstanceState?.getLong(STATE_TIME) ?: System.currentTimeMillis()
        clientUuid = savedInstanceState?.getString(STATE_UUID) ?: UUID.randomUUID().toString()

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setTitle(if (txId == 0L) R.string.add_transaction else R.string.edit_transaction)

        tilAmount = findViewById(R.id.tilAmount)
        etAmount = findViewById(R.id.etAmount)
        etDescription = findViewById(R.id.etDescription)
        etReference = findViewById(R.id.etReference)
        etNotes = findViewById(R.id.etTxNotes)
        toggle = findViewById(R.id.toggleType)
        chipMethods = findViewById(R.id.chipGroupMethod)
        btnDate = findViewById(R.id.btnDate)
        btnTime = findViewById(R.id.btnTime)
        btnSave = findViewById(R.id.btnSave)

        findViewById<MaterialButton>(R.id.btnTxOut).setText(
            if (isSupplier) R.string.tx_purchase else R.string.tx_gave
        )
        findViewById<MaterialButton>(R.id.btnTxIn).setText(
            if (isSupplier) R.string.tx_payment else R.string.tx_got
        )

        if (savedInstanceState == null) {
            val type = intent.getStringExtra(EXTRA_TYPE).orEmpty()
            val decrease = type == TxTypes.decrease(isSupplier)
            toggle.check(if (decrease) R.id.btnTxIn else R.id.btnTxOut)
            chipMethods.check(R.id.chipCash)
        }

        btnDate.setOnClickListener { showDatePicker() }
        btnTime.setOnClickListener { showTimePicker() }
        btnSave.setOnClickListener { save() }
        updateDateTimeButtons()

        if (txId != 0L && savedInstanceState == null) loadTransaction()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_TIME, txTime)
        outState.putString(STATE_UUID, clientUuid)
    }

    private fun loadTransaction() {
        val appCtx = applicationContext
        val id = txId
        Db.async({ Repository.get(appCtx).getTransaction(id) }) { result ->
            if (isFinishing || isDestroyed) return@async
            val tx = result.getOrNull()
            if (tx == null) {
                Toast.makeText(appCtx, R.string.error_not_found, Toast.LENGTH_SHORT).show()
                finish()
                return@async
            }
            partyId = tx.partyId
            isSupplier = tx.isSupplier
            txTime = tx.txTime
            clientUuid = tx.clientUuid
            toggle.check(if (tx.txType == TxTypes.decrease(tx.isSupplier)) R.id.btnTxIn else R.id.btnTxOut)
            etAmount.setText(Money.toEditText(tx.amountPaise))
            etDescription.setText(tx.description)
            etReference.setText(tx.referenceNo)
            etNotes.setText(tx.notes)
            chipMethods.check(
                when (tx.paymentMethod) {
                    "UPI" -> R.id.chipUpi
                    "BANK" -> R.id.chipBank
                    "OTHER" -> R.id.chipOther
                    else -> R.id.chipCash
                }
            )
            updateDateTimeButtons()
        }
    }

    private fun updateDateTimeButtons() {
        btnDate.text = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(txTime)
        btnTime.text = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(txTime)
    }

    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        cal.timeInMillis = txTime
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utc.clear()
        utc.set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))

        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.select_date)
            .setSelection(utc.timeInMillis)
            .build()
        picker.addOnPositiveButtonClickListener { selection ->
            val picked = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            picked.timeInMillis = selection
            val local = Calendar.getInstance()
            local.timeInMillis = txTime
            local.set(
                picked.get(Calendar.YEAR),
                picked.get(Calendar.MONTH),
                picked.get(Calendar.DAY_OF_MONTH)
            )
            txTime = local.timeInMillis
            updateDateTimeButtons()
        }
        picker.show(supportFragmentManager, "date")
    }

    private fun showTimePicker() {
        val cal = Calendar.getInstance()
        cal.timeInMillis = txTime
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_12H)
            .setHour(cal.get(Calendar.HOUR_OF_DAY))
            .setMinute(cal.get(Calendar.MINUTE))
            .setTitleText(R.string.select_time)
            .build()
        picker.addOnPositiveButtonClickListener {
            val local = Calendar.getInstance()
            local.timeInMillis = txTime
            local.set(Calendar.HOUR_OF_DAY, picker.hour)
            local.set(Calendar.MINUTE, picker.minute)
            local.set(Calendar.SECOND, 0)
            local.set(Calendar.MILLISECOND, 0)
            txTime = local.timeInMillis
            updateDateTimeButtons()
        }
        picker.show(supportFragmentManager, "time")
    }

    private fun save() {
        tilAmount.error = null
        val paise = Money.parseToPaise(etAmount.text.toString())
        if (paise == null || paise <= 0L) {
            tilAmount.error = getString(R.string.error_tx_amount)
            return
        }

        val type = if (toggle.checkedButtonId == R.id.btnTxIn) {
            TxTypes.decrease(isSupplier)
        } else {
            TxTypes.increase(isSupplier)
        }
        val method = when (chipMethods.checkedChipId) {
            R.id.chipUpi -> "UPI"
            R.id.chipBank -> "BANK"
            R.id.chipOther -> "OTHER"
            else -> "CASH"
        }

        val tx = Tx(
            id = txId,
            partyId = partyId,
            isSupplier = isSupplier,
            txType = type,
            amountPaise = paise,
            txTime = txTime,
            description = etDescription.text.toString().trim(),
            referenceNo = etReference.text.toString().trim(),
            paymentMethod = method,
            notes = etNotes.text.toString().trim(),
            clientUuid = clientUuid
        )

        btnSave.isEnabled = false
        val appCtx = applicationContext
        Db.async({ Repository.get(appCtx).saveTransaction(tx) }) { result ->
            if (isFinishing || isDestroyed) return@async
            result.onSuccess {
                Toast.makeText(appCtx, R.string.tx_saved, Toast.LENGTH_SHORT).show()
                finish()
            }
            result.onFailure {
                btnSave.isEnabled = true
                Toast.makeText(appCtx, R.string.error_generic, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
