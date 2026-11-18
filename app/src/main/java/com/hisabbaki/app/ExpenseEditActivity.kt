package com.hisabbaki.app

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.hisabbaki.app.data.Db
import com.hisabbaki.app.data.Expense
import com.hisabbaki.app.data.ExpenseRepo
import com.hisabbaki.app.util.Dates
import com.hisabbaki.app.util.Money
import java.io.File
import java.util.Calendar
import java.util.TimeZone

class ExpenseEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EXPENSE_ID = "expense_id"
    }

    private var expenseId = 0L
    private var expenseDate = 0L
    private var selectedCategoryId = 0L
    private var categories: List<com.hisabbaki.app.data.ExpenseCategory> = emptyList()
    private var recurrence = ExpenseRepo.REC_NONE
    private var attachmentPath = ""

    private lateinit var tilTitle: TextInputLayout
    private lateinit var tilAmount: TextInputLayout
    private lateinit var etTitle: TextInputEditText
    private lateinit var etAmount: TextInputEditText
    private lateinit var etNotes: TextInputEditText
    private lateinit var actCategory: AutoCompleteTextView
    private lateinit var actRecurrence: AutoCompleteTextView
    private lateinit var btnDate: MaterialButton
    private lateinit var chipGroupMethod: ChipGroup

    private val attachmentPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) importAttachment(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_expense_edit)

        expenseId = intent.getLongExtra(EXTRA_EXPENSE_ID, 0L)
        val editing = expenseId != 0L

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setTitle(if (editing) R.string.edit_expense else R.string.add_expense)
        toolbar.setNavigationOnClickListener { finish() }

        tilTitle = findViewById(R.id.tilTitle)
        tilAmount = findViewById(R.id.tilAmount)
        etTitle = findViewById(R.id.etTitle)
        etAmount = findViewById(R.id.etAmount)
        etNotes = findViewById(R.id.etNotes)
        actCategory = findViewById(R.id.actCategory)
        actRecurrence = findViewById(R.id.actRecurrence)
        btnDate = findViewById(R.id.btnDate)
        chipGroupMethod = findViewById(R.id.chipGroupMethod)

        expenseDate = ExpenseRepo.dayStart(System.currentTimeMillis())
        updateDateButton()

        val recLabels = arrayOf(
            getString(R.string.recur_none),
            getString(R.string.recur_daily),
            getString(R.string.recur_weekly),
            getString(R.string.recur_monthly)
        )
        val recValues = arrayOf(ExpenseRepo.REC_NONE, ExpenseRepo.REC_DAILY, ExpenseRepo.REC_WEEKLY, ExpenseRepo.REC_MONTHLY)
        actRecurrence.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, recLabels))
        actRecurrence.setText(recLabels[0], false)
        actRecurrence.setOnItemClickListener { _, _, position, _ ->
            recurrence = recValues[position]
        }

        actCategory.setOnClickListener { showCategoryPicker() }

        btnDate.setOnClickListener { showDatePicker() }
        findViewById<MaterialButton>(R.id.btnAttach).setOnClickListener { attachmentPicker.launch("image/*") }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }

        loadCategories()
        if (editing) load()
    }

    private fun bizId() = Prefs.currentBusinessId(this)

    private fun load() {
        val appCtx = applicationContext
        val id = expenseId
        Db.async({ ExpenseRepo.get(appCtx).getExpense(bizId(), id) }) { result ->
            if (isFinishing || isDestroyed) return@async
            result.onSuccess { e ->
                if (e == null) {
                    Toast.makeText(appCtx, R.string.error_not_found, Toast.LENGTH_SHORT).show()
                    finish()
                    return@onSuccess
                }
                etTitle.setText(e.title)
                etAmount.setText(Money.toEditText(e.amountPaise))
                etNotes.setText(e.notes)
                expenseDate = e.expenseDate
                selectedCategoryId = e.categoryId
                recurrence = e.recurrence
                attachmentPath = e.attachmentPath
                when (e.paymentMethod) {
                    "UPI" -> chipGroupMethod.check(R.id.chipUpi)
                    "BANK" -> chipGroupMethod.check(R.id.chipBank)
                    "OTHER" -> chipGroupMethod.check(R.id.chipOther)
                    else -> chipGroupMethod.check(R.id.chipCash)
                }
                val recLabels = arrayOf(
                    getString(R.string.recur_none),
                    getString(R.string.recur_daily),
                    getString(R.string.recur_weekly),
                    getString(R.string.recur_monthly)
                )
                val idx = when (e.recurrence) {
                    ExpenseRepo.REC_DAILY -> 1
                    ExpenseRepo.REC_WEEKLY -> 2
                    ExpenseRepo.REC_MONTHLY -> 3
                    else -> 0
                }
                actRecurrence.setText(recLabels[idx], false)
                updateDateButton()
                updateAttachButton()
            }
            result.onFailure {
                Toast.makeText(appCtx, R.string.error_load, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadCategories() {
        val appCtx = applicationContext
        Db.async({ ExpenseRepo.get(appCtx).getCategories(bizId()) }) { result ->
            if (isFinishing || isDestroyed) return@async
            result.onSuccess {
                categories = it
                actCategory.setText(
                    categories.firstOrNull { c -> c.id == selectedCategoryId }?.name
                        ?: getString(R.string.category_none), false
                )
            }
        }
    }

    private fun updateCategoryLabel() {
        actCategory.setText(
            categories.firstOrNull { it.id == selectedCategoryId }?.name
                ?: getString(R.string.category_none), false
        )
    }

    private fun showCategoryPicker() {
        val options = ArrayList<String>()
        options.add(getString(R.string.category_none))
        options.addAll(categories.map { it.name })
        options.add(getString(R.string.new_category))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.category)
            .setItems(options.toTypedArray()) { dialog, which ->
                if (which == options.size - 1) {
                    dialog.dismiss()
                    promptNewCategory()
                } else if (which == 0) {
                    selectedCategoryId = 0L
                    updateCategoryLabel()
                } else {
                    selectedCategoryId = categories[which - 1].id
                    updateCategoryLabel()
                }
            }
            .show()
    }

    private fun promptNewCategory() {
        val input = TextInputEditText(this)
        input.setHint(R.string.category)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_category)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val appCtx = applicationContext
                Db.async({ ExpenseRepo.get(appCtx).saveCategory(bizId(), 0L, name) }) { result ->
                    result.onSuccess { id ->
                        val biz = bizId()
                        Db.async({ ExpenseRepo.get(appCtx).getCategories(biz) }) { r ->
                            if (isFinishing || isDestroyed) return@async
                            r.onSuccess {
                                categories = it
                                selectedCategoryId = id
                                updateCategoryLabel()
                            }
                        }
                    }
                    result.onFailure {
                        Toast.makeText(appCtx, R.string.error_generic, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        cal.timeInMillis = expenseDate
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utc.clear()
        utc.set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))

        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.select_date)
            .setSelection(utc.timeInMillis)
            .build()
        picker.addOnPositiveButtonClickListener { selection ->
            expenseDate = Dates.utcDateToLocalDayStart(selection)
            updateDateButton()
        }
        picker.show(supportFragmentManager, "expense_date")
    }

    private fun updateDateButton() {
        btnDate.text = Dates.formatDate(expenseDate)
    }

    private fun updateAttachButton() {
        findViewById<MaterialButton>(R.id.btnAttach).text =
            if (attachmentPath.isEmpty()) getString(R.string.choose_photo)
            else getString(R.string.attachment_added)
    }

    private fun importAttachment(uri: Uri) {
        val appCtx = applicationContext
        try {
            val dir = File(appCtx.filesDir, "expenses")
            dir.mkdirs()
            val dest = File(dir, "expense_${System.currentTimeMillis()}.jpg")
            appCtx.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            if (dest.length() > 0) {
                attachmentPath = dest.absolutePath
                updateAttachButton()
            }
        } catch (e: Exception) {
            Toast.makeText(appCtx, R.string.error_generic, Toast.LENGTH_SHORT).show()
        }
    }

    private fun methodFromChips(): String = when (chipGroupMethod.checkedChipId) {
        R.id.chipUpi -> "UPI"
        R.id.chipBank -> "BANK"
        R.id.chipOther -> "OTHER"
        else -> "CASH"
    }

    private fun save() {
        tilTitle.error = null
        tilAmount.error = null

        val title = etTitle.text.toString().trim()
        if (title.isEmpty()) {
            tilTitle.error = getString(R.string.error_expense_title)
            return
        }
        val amountText = etAmount.text.toString().trim()
        if (amountText.isEmpty()) {
            tilAmount.error = getString(R.string.error_amount)
            return
        }
        val amount = Money.parseToPaise(amountText)
        if (amount == null || amount <= 0L) {
            tilAmount.error = getString(R.string.error_amount)
            return
        }

        val appCtx = applicationContext
        val id = expenseId
        val categoryId = selectedCategoryId
        val date = expenseDate
        val method = methodFromChips()
        val notes = etNotes.text.toString().trim()
        val rec = recurrence
        val attach = attachmentPath

        Db.async({
            ExpenseRepo.get(appCtx).saveExpense(
                bizId(), id, categoryId, title, amount, date, method, notes, attach, rec
            )
        }) { result ->
            if (isFinishing || isDestroyed) return@async
            result.onSuccess {
                Toast.makeText(appCtx, R.string.expense_saved, Toast.LENGTH_SHORT).show()
                finish()
            }
            result.onFailure { error ->
                val msg = error.message
                Toast.makeText(appCtx, msg ?: getString(R.string.error_generic), Toast.LENGTH_LONG).show()
            }
        }
    }
}
