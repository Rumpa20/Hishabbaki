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
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.hisabbaki.app.data.CoreRepo
import com.hisabbaki.app.data.Db
import com.hisabbaki.app.data.ExpenseRepo
import com.hisabbaki.app.data.Staff
import com.hisabbaki.app.data.StaffRoles
import com.hisabbaki.app.data.StaffSummary
import com.hisabbaki.app.util.Dates
import com.hisabbaki.app.util.Money
import java.util.Calendar

class StaffActivity : AppCompatActivity() {

    private var requestId = 0
    private var summaries: List<StaffSummary> = emptyList()
    private lateinit var container: LinearLayout
    private lateinit var tvNoStaff: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_staff)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        container = findViewById(R.id.staffContainer)
        tvNoStaff = findViewById(R.id.tvNoStaff)
        findViewById<MaterialButton>(R.id.btnAddStaff).setOnClickListener { showStaffDialog(null) }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun bizId(): Int = Prefs.currentBusinessId(this)

    private fun monthRange(): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val start = ExpenseRepo.dayStart(cal.timeInMillis)
        cal.add(Calendar.MONTH, 1)
        val end = ExpenseRepo.dayStart(cal.timeInMillis)
        return Pair(start, end)
    }

    private fun load() {
        val appCtx = applicationContext
        val (from, to) = monthRange()
        val businessId = bizId()
        requestId += 1
        val myId = requestId

        Db.async({ CoreRepo.get(appCtx).getStaffSummaries(businessId, from, to) }) { result ->
            if (isFinishing || isDestroyed || myId != requestId) return@async
            result.onSuccess { bind(it) }
            result.onFailure {
                Toast.makeText(appCtx, R.string.error_load, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun roleLabel(role: String): String = when (role) {
        StaffRoles.OWNER -> getString(R.string.role_owner)
        StaffRoles.ADMINISTRATOR -> getString(R.string.role_administrator)
        StaffRoles.MANAGER -> getString(R.string.role_manager)
        StaffRoles.ACCOUNTANT -> getString(R.string.role_accountant)
        else -> getString(R.string.role_employee)
    }

    private fun bind(list: List<StaffSummary>) {
        summaries = list
        container.removeAllViews()
        tvNoStaff.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        for (s in list) {
            val staff = s.staff
            val row = inflater.inflate(R.layout.item_staff_row, container, false)
            row.findViewById<TextView>(R.id.tvStaffName).text = staff.name
            row.findViewById<TextView>(R.id.tvStaffRole).text = roleLabel(staff.role)
            val tvActive = row.findViewById<TextView>(R.id.tvStaffActive)
            tvActive.visibility = if (staff.isActive) View.VISIBLE else View.GONE

            var meta = getString(R.string.monthly_salary) + ": "
            meta += Money.format(staff.monthlySalaryPaise, withSymbol = false)
            if (staff.mobile.isNotBlank()) meta += "  \u2022  " + staff.mobile
            if (staff.joinedAt > 0L) {
                meta += "\n" + getString(R.string.joined_on, Dates.formatDate(staff.joinedAt))
            }
            row.findViewById<TextView>(R.id.tvStaffMeta).text = meta

            row.findViewById<TextView>(R.id.tvStaffMonth).text = getString(
                R.string.staff_month_line,
                s.presentDays,
                s.absentDays,
                Money.format(s.salaryPaidPaise)
            )

            row.findViewById<MaterialButton>(R.id.btnStaffAttend).setOnClickListener {
                showAttendanceDialog(staff)
            }
            row.findViewById<MaterialButton>(R.id.btnStaffPay).setOnClickListener {
                showPayDialog(staff)
            }
            row.findViewById<View>(R.id.btnEditStaff).setOnClickListener {
                showStaffDialog(staff)
            }
            row.setOnLongClickListener {
                confirmDelete(staff)
                true
            }
            container.addView(row)
        }
    }

    // ------------------------------------------------------------ add / edit

    private fun showStaffDialog(existing: Staff?) {
        val view = layoutInflater.inflate(R.layout.dialog_staff_edit, null)
        val tilName = view.findViewById<TextInputLayout>(R.id.tilStaffName)
        val etName = view.findViewById<TextInputEditText>(R.id.etStaffName)
        val etMobile = view.findViewById<TextInputEditText>(R.id.etStaffMobile)
        val actRole = view.findViewById<AutoCompleteTextView>(R.id.actStaffRole)
        val etSalary = view.findViewById<TextInputEditText>(R.id.etStaffSalary)
        val switchActive = view.findViewById<MaterialSwitch>(R.id.switchStaffActive)
        val etNotes = view.findViewById<TextInputEditText>(R.id.etStaffNotes)

        val roles = StaffRoles.all()
        val labels = roles.map { roleLabel(it) }
        actRole.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        actRole.setOnItemClickListener { _, _, position, _ -> actRole.tag = roles[position] }

        if (existing == null) {
            actRole.setText(labels.last(), false)
            actRole.tag = roles.last()
            switchActive.isChecked = true
        } else {
            etName.setText(existing.name)
            etMobile.setText(existing.mobile)
            actRole.setText(roleLabel(existing.role), false)
            actRole.tag = existing.role
            etSalary.setText(Money.toEditText(existing.monthlySalaryPaise))
            switchActive.isChecked = existing.isActive
            etNotes.setText(existing.notes)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.add_staff else R.string.edit_staff)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .show()
            .also { dialog ->
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val name = etName.text.toString().trim()
                    if (name.isEmpty()) {
                        tilName.error = getString(R.string.error_staff_name)
                        return@setOnClickListener
                    }
                    val salaryText = etSalary.text.toString().trim()
                    val salary =
                        if (salaryText.isEmpty()) 0L else Money.parseToPaise(salaryText) ?: 0L
                    val role = (actRole.tag as? String) ?: StaffRoles.EMPLOYEE
                    val appCtx = applicationContext
                    Db.async({
                        CoreRepo.get(appCtx).saveStaff(
                            bizId(), existing?.id ?: 0L, name,
                            etMobile.text.toString().trim(), role, salary,
                            existing?.joinedAt ?: System.currentTimeMillis(),
                            switchActive.isChecked, etNotes.text.toString().trim()
                        )
                    }) { result ->
                        if (isFinishing || isDestroyed) return@async
                        result.onSuccess {
                            Toast.makeText(appCtx, R.string.staff_saved, Toast.LENGTH_SHORT).show()
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

    // ------------------------------------------------------------ attendance

    private fun showAttendanceDialog(staff: Staff) {
        val options = arrayOf(getString(R.string.mark_present), getString(R.string.mark_absent))
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.attendance) + " - " + staff.name)
            .setItems(options) { _, which ->
                val present = which == 0
                val appCtx = applicationContext
                Db.async({
                    CoreRepo.get(appCtx).markAttendance(
                        staff.id, ExpenseRepo.dayStart(System.currentTimeMillis()), present
                    )
                }) { result ->
                    if (isFinishing || isDestroyed) return@async
                    result.onSuccess {
                        Toast.makeText(appCtx, R.string.staff_saved, Toast.LENGTH_SHORT).show()
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
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------ salary payment

    private fun showPayDialog(staff: Staff) {
        val view = layoutInflater.inflate(R.layout.dialog_staff_pay, null)
        val tilAmount = view.findViewById<TextInputLayout>(R.id.tilSalaryAmount)
        val etAmount = view.findViewById<TextInputEditText>(R.id.etSalaryAmount)
        val actMethod = view.findViewById<AutoCompleteTextView>(R.id.actSalaryMethod)
        val etNote = view.findViewById<TextInputEditText>(R.id.etSalaryNote)

        view.findViewById<TextView>(R.id.tvStaffPayInfo).text = getString(
            R.string.pay_salary_info, staff.name, Money.format(staff.monthlySalaryPaise)
        )
        etAmount.setText(Money.toEditText(staff.monthlySalaryPaise))

        val methods = listOf("CASH", "BANK", "UPI")
        val methodLabels = methods.map {
            when (it) {
                "BANK" -> getString(R.string.pm_bank)
                "UPI" -> getString(R.string.pm_upi)
                else -> getString(R.string.pm_cash)
            }
        }
        actMethod.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, methodLabels)
        )
        actMethod.setOnItemClickListener { _, _, position, _ -> actMethod.tag = methods[position] }
        actMethod.setText(methodLabels[0], false)
        actMethod.tag = "CASH"

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pay_salary)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .show()
            .also { dialog ->
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val amountText = etAmount.text.toString().trim()
                    val amount = if (amountText.isEmpty()) 0L else Money.parseToPaise(amountText)
                    if (amount == null || amount <= 0L) {
                        tilAmount.error = getString(R.string.error_salary_amount)
                        return@setOnClickListener
                    }
                    val method = (actMethod.tag as? String) ?: "CASH"
                    val appCtx = applicationContext
                    Db.async({
                        CoreRepo.get(appCtx).paySalary(
                            bizId(), staff, amount, method, etNote.text.toString().trim()
                        )
                    }) { result ->
                        if (isFinishing || isDestroyed) return@async
                        result.onSuccess {
                            Toast.makeText(appCtx, R.string.salary_paid, Toast.LENGTH_SHORT).show()
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

    // ------------------------------------------------------------ delete

    private fun confirmDelete(staff: Staff) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_staff)
            .setMessage(getString(R.string.delete_staff_confirm, staff.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                val appCtx = applicationContext
                Db.async({ CoreRepo.get(appCtx).deleteStaff(bizId(), staff.id) }) { result ->
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
}
