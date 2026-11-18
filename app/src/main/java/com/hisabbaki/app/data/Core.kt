package com.hisabbaki.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.hisabbaki.app.util.Dates
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

data class Business(
    val id: Int,
    val name: String,
    val owner: String,
    val mobile: String,
    val address: String,
    val gstin: String,
    val logoPath: String,
    val upiId: String,
    val currency: String
)

data class AuditEntry(
    val id: Long,
    val action: String,
    val entity: String,
    val entityId: Long,
    val detail: String,
    val at: Long
)

data class DeletedRecord(
    val id: Long,
    val entity: String,
    val title: String,
    val payload: String,
    val deletedAt: Long
)

data class Staff(
    val id: Long,
    val name: String,
    val mobile: String,
    val role: String,
    val monthlySalaryPaise: Long,
    val joinedAt: Long,
    val isActive: Boolean,
    val notes: String
)

data class AttendanceDay(
    val id: Long,
    val staffId: Long,
    val dayStart: Long,
    val present: Boolean,
    val note: String
)

data class StaffSummary(
    val staff: Staff,
    val presentDays: Int,
    val absentDays: Int,
    val salaryPaidPaise: Long
)

object StaffRoles {
    const val OWNER = "OWNER"
    const val ADMINISTRATOR = "ADMINISTRATOR"
    const val MANAGER = "MANAGER"
    const val ACCOUNTANT = "ACCOUNTANT"
    const val EMPLOYEE = "EMPLOYEE"

    fun all(): List<String> = listOf(OWNER, ADMINISTRATOR, MANAGER, ACCOUNTANT, EMPLOYEE)
    /** Activities that need an Owner/Administrator (sensitive finance screens). */
    fun isSensitive(action: String): Boolean = action in setOf(
        "BUSINESS_EDIT", "STAFF_EDIT", "RESTORE", "EXPORT", "DELETE_PERMANENT", "SETTINGS_UNLOCK"
    )
}

/** Businesses, staff, attendance, audit log and the recycle bin. Blocking; use Db.async. */
class CoreRepo private constructor(
    private val appContext: Context,
    private val helper: DatabaseHelper
) {

    companion object {
        fun get(context: Context): CoreRepo =
            CoreRepo(context.applicationContext, DatabaseHelper.get(context))
    }

    // ------------------------------------------------------------ businesses

    fun getBusinesses(): List<Business> {
        val out = ArrayList<Business>()
        helper.readableDatabase.rawQuery(
            "SELECT id, name, owner_name, mobile, address, gstin, logo_path, upi_id, currency " +
                "FROM businesses ORDER BY id ASC",
            null
        ).use { c ->
            while (c.moveToNext()) out.add(readBusiness(c))
        }
        return out
    }

    fun getBusiness(id: Int): Business? {
        var found: Business? = null
        helper.readableDatabase.rawQuery(
            "SELECT id, name, owner_name, mobile, address, gstin, logo_path, upi_id, currency " +
                "FROM businesses WHERE id = ?",
            arrayOf(id.toString())
        ).use { c -> if (c.moveToFirst()) found = readBusiness(c) }
        return found
    }

    /** Active business; falls back to business 1 when missing. Never null. */
    fun activeBusiness(businessId: Int): Business {
        getBusiness(businessId)?.let { return it }
        return getBusiness(1)
            ?: Business(1, "", "", "", "", "", "", "", "INR")
    }

    private fun readBusiness(c: Cursor): Business = Business(
        id = c.getInt(0),
        name = c.getString(1) ?: "",
        owner = c.getString(2) ?: "",
        mobile = c.getString(3) ?: "",
        address = c.getString(4) ?: "",
        gstin = c.getString(5) ?: "",
        logoPath = c.getString(6) ?: "",
        upiId = c.getString(7) ?: "",
        currency = c.getString(8) ?: "INR"
    )

    fun saveBusiness(
        id: Int,
        name: String,
        owner: String,
        mobile: String,
        address: String,
        gstin: String,
        logoPath: String,
        upiId: String,
        currency: String
    ): Int {
        require(name.isNotBlank()) { "Business name is required" }
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        val cv = ContentValues()
        cv.put("name", name.trim())
        cv.put("owner_name", owner)
        cv.put("mobile", mobile)
        cv.put("address", address)
        cv.put("gstin", gstin)
        cv.put("logo_path", logoPath)
        cv.put("upi_id", upiId.trim())
        cv.put("currency", currency)
        cv.put("updated_at", now)
        if (id != 0) {
            val rows = db.update("businesses", cv, "id = ?", arrayOf(id.toString()))
            if (rows == 0) throw IllegalStateException("Business not found")
            log(id, "UPDATE", "business", id.toLong(), name)
            return id
        }
        cv.put("created_at", now)
        val newId = db.insertOrThrow("businesses", null, cv).toInt()
        DatabaseHelper.seedExpenseCategoriesFor(appContext, newId)
        CashbookRepo.get(appContext).ensureDefaultAccount(newId)
        log(newId, "CREATE", "business", newId.toLong(), name)
        return newId
    }

    /**
     * Deletes a business and every record that belongs to it. The UI must confirm
     * with the user before calling this, and a backup recommendation is shown first.
     */
    fun deleteBusiness(id: Int) {
        if (id == 1) throw IllegalStateException("The first business cannot be deleted")
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.rawQuery("SELECT COUNT(*) FROM businesses", null).use { c ->
                if (c.moveToFirst() && c.getInt(0) <= 1) {
                    throw IllegalStateException("The last business cannot be deleted")
                }
            }
            val dbId = id.toString()
            db.delete("transactions", "business_id = ?", arrayOf(dbId))
            db.delete("reminders", "business_id = ?", arrayOf(dbId))
            db.delete("customers", "business_id = ?", arrayOf(dbId))
            db.delete("suppliers", "business_id = ?", arrayOf(dbId))
            db.execSQL("DELETE FROM stock_movements WHERE business_id = ?", arrayOf(dbId))
            db.execSQL(
                "DELETE FROM invoice_items WHERE invoice_id IN " +
                    "(SELECT id FROM invoices WHERE business_id = ?)", arrayOf(dbId)
            )
            db.execSQL(
                "DELETE FROM invoice_payments WHERE invoice_id IN " +
                    "(SELECT id FROM invoices WHERE business_id = ?)", arrayOf(dbId)
            )
            db.execSQL("DELETE FROM invoices WHERE business_id = ?", arrayOf(dbId))
            db.execSQL("DELETE FROM expenses WHERE business_id = ?", arrayOf(dbId))
            db.execSQL("DELETE FROM cash_transfers WHERE business_id = ?", arrayOf(dbId))
            db.execSQL("DELETE FROM cash_accounts WHERE business_id = ?", arrayOf(dbId))
            db.execSQL("DELETE FROM products WHERE business_id = ?", arrayOf(dbId))
            db.execSQL("DELETE FROM product_categories WHERE business_id = ?", arrayOf(dbId))
            db.execSQL("DELETE FROM expense_categories WHERE business_id = ?", arrayOf(dbId))
            db.execSQL(
                "DELETE FROM attendance WHERE staff_id IN " +
                    "(SELECT id FROM staff WHERE business_id = ?)", arrayOf(dbId)
            )
            db.execSQL("DELETE FROM staff WHERE business_id = ?", arrayOf(dbId))
            db.execSQL("DELETE FROM deleted_records WHERE business_id = ?", arrayOf(dbId))
            db.execSQL("DELETE FROM businesses WHERE id = ?", arrayOf(dbId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ------------------------------------------------------------ audit log

    fun log(businessId: Int, action: String, entity: String, entityId: Long, detail: String) {
        if (detail.length > 500) return
        val cv = ContentValues()
        cv.put("business_id", businessId)
        cv.put("action", action)
        cv.put("entity", entity)
        cv.put("entity_id", entityId)
        cv.put("detail", detail.take(400))
        cv.put("at", System.currentTimeMillis())
        helper.writableDatabase.insert("audit_log", null, cv)
    }

    fun getAuditLog(businessId: Int, limit: Int = 300): List<AuditEntry> {
        val out = ArrayList<AuditEntry>()
        helper.readableDatabase.rawQuery(
            "SELECT id, action, entity, entity_id, detail, at FROM audit_log " +
                "WHERE business_id = ? ORDER BY at DESC, id DESC LIMIT ?",
            arrayOf(businessId.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    AuditEntry(
                        id = c.getLong(0),
                        action = c.getString(1) ?: "",
                        entity = c.getString(2) ?: "",
                        entityId = c.getLong(3),
                        detail = c.getString(4) ?: "",
                        at = c.getLong(5)
                    )
                )
            }
        }
        return out
    }

    // ------------------------------------------------------------ recycle bin

    fun saveToBin(businessId: Int, entity: String, title: String, payload: String) {
        val cv = ContentValues()
        cv.put("business_id", businessId)
        cv.put("entity", entity)
        cv.put("title", title)
        cv.put("payload", payload)
        cv.put("deleted_at", System.currentTimeMillis())
        helper.writableDatabase.insert("deleted_records", null, cv)
    }

    fun getBinItems(businessId: Int): List<DeletedRecord> {
        val out = ArrayList<DeletedRecord>()
        helper.readableDatabase.rawQuery(
            "SELECT id, entity, title, payload, deleted_at FROM deleted_records " +
                "WHERE business_id = ? ORDER BY deleted_at DESC, id DESC LIMIT 200",
            arrayOf(businessId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    DeletedRecord(
                        id = c.getLong(0),
                        entity = c.getString(1) ?: "",
                        title = c.getString(2) ?: "",
                        payload = c.getString(3) ?: "",
                        deletedAt = c.getLong(4)
                    )
                )
            }
        }
        return out
    }

    fun getBinItem(businessId: Int, id: Long): DeletedRecord? {
        var found: DeletedRecord? = null
        helper.readableDatabase.rawQuery(
            "SELECT id, entity, title, payload, deleted_at FROM deleted_records " +
                "WHERE id = ? AND business_id = ?",
            arrayOf(id.toString(), businessId.toString())
        ).use { c -> if (c.moveToFirst()) found = readBin(c) }
        return found
    }

    private fun readBin(c: Cursor): DeletedRecord = DeletedRecord(
        id = c.getLong(0),
        entity = c.getString(1) ?: "",
        title = c.getString(2) ?: "",
        payload = c.getString(3) ?: "",
        deletedAt = c.getLong(4)
    )

    fun removeBinItem(id: Long) {
        helper.writableDatabase.delete("deleted_records", "id = ?", arrayOf(id.toString()))
    }

    /** Restores a bin record as a new record in the business. Returns "" on success. */
    fun restoreBinItem(businessId: Int, id: Long): String {
        val item = getBinItem(businessId, id) ?: return "Record not found"
        return try {
            when (item.entity) {
                "customer", "supplier" -> restoreParty(businessId, item.entity, item.payload)
                "expense" -> restoreExpense(businessId, item.payload)
                "staff" -> restoreStaff(businessId, item.payload)
                "product" -> restoreProduct(businessId, item.payload)
                "transaction" -> restoreTransaction(businessId, item.payload)
            }
            removeBinItem(id)
            ""
        } catch (e: IllegalStateException) {
            e.message ?: "Restore failed"
        } catch (e: Exception) {
            "Restore failed"
        }
    }

    private fun restoreParty(businessId: Int, entity: String, payload: String) {
        val root = JSONObject(payload)
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        val cv = ContentValues()
        cv.put("business_id", businessId)
        cv.put("name", root.optString("name"))
        cv.put("mobile", root.optString("mobile"))
        cv.put("address", root.optString("address"))
        cv.put("opening_balance_paise", root.optLong("opening"))
        cv.put("notes", root.optString("notes"))
        cv.put("created_at", now)
        cv.put("updated_at", now)
        db.beginTransaction()
        try {
            val partyId = db.insertOrThrow(
                if (entity == "supplier") "suppliers" else "customers", null, cv
            )
            val txArray = root.optJSONArray("transactions") ?: JSONArray()
            for (i in 0 until txArray.length()) {
                val t = txArray.getJSONObject(i)
                val tcv = ContentValues()
                tcv.put("business_id", businessId)
                if (entity == "supplier") {
                    tcv.put("supplier_id", partyId)
                    tcv.putNull("customer_id")
                } else {
                    tcv.put("customer_id", partyId)
                    tcv.putNull("supplier_id")
                }
                tcv.put("tx_type", t.optString("t"))
                tcv.put("amount_paise", t.optLong("a"))
                tcv.put("tx_time", t.optLong("tm"))
                tcv.put("description", t.optString("d"))
                tcv.put("reference_no", t.optString("r"))
                tcv.put("payment_method", t.optString("m", "CASH"))
                tcv.put("notes", t.optString("n"))
                tcv.put("client_uuid", java.util.UUID.randomUUID().toString())
                tcv.put("created_at", now)
                tcv.put("updated_at", now)
                db.insertOrThrow("transactions", null, tcv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        log(businessId, "RESTORE", entity, -1L, root.optString("name"))
    }

    private fun restoreExpense(businessId: Int, payload: String) {
        val root = JSONObject(payload)
        val expenseRepo = ExpenseRepo.get(appContext)
        val catName = root.optString("category")
        val catId = if (catName.isBlank()) {
            0L
        } else {
            expenseRepo.getCategories(businessId)
                .firstOrNull { it.name.equals(catName, ignoreCase = true) }?.id
                ?: expenseRepo.saveCategory(businessId, 0L, catName)
        }
        val amount = root.optLong("amount")
        expenseRepo.saveExpense(
            businessId, 0L, catId, root.optString("title"),
            if (amount > 0) amount else 1L,
            root.optLong("date"), root.optString("method", "CASH"),
            root.optString("notes"), root.optString("attachment"),
            root.optString("recurrence", ExpenseRepo.REC_NONE)
        )
    }

    private fun restoreStaff(businessId: Int, payload: String) {
        val root = JSONObject(payload)
        saveStaff(
            businessId, 0L, root.optString("name"), root.optString("mobile"),
            root.optString("role", StaffRoles.EMPLOYEE), root.optLong("salary"),
            root.optLong("joined"), root.optBoolean("active", true), root.optString("notes")
        )
    }

    private fun restoreProduct(businessId: Int, payload: String) {
        val root = JSONObject(payload)
        val inventory = InventoryRepo.get(appContext)
        val sku = root.optString("sku")
        val attempt = { skuValue: String ->
            inventory.saveProduct(
                businessId, 0L, 0L, root.optString("name"), skuValue, root.optString("barcode"),
                root.optString("unit", "pcs"), root.optString("image"), root.optLong("buy"),
                root.optLong("sell"), root.optLong("mrp"), root.optLong("stock"),
                root.optLong("min"), root.optBoolean("negative"), root.optString("notes")
            )
        }
        try {
            attempt(sku)
        } catch (e: DuplicateSkuException) {
            attempt("$sku-R")
        }
    }

    private fun restoreTransaction(businessId: Int, payload: String) {
        val root = JSONObject(payload)
        val isSupplier = root.optInt("s") == 1
        val partyId = root.optLong("p")
        val table = if (isSupplier) "suppliers" else "customers"
        val exists = helper.readableDatabase.rawQuery(
            "SELECT 1 FROM $table WHERE id = ? AND business_id = ?",
            arrayOf(partyId.toString(), businessId.toString())
        ).use { it.moveToFirst() }
        if (!exists) throw IllegalStateException("PARTY_MISSING")
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        val cv = ContentValues()
        cv.put("business_id", businessId)
        if (isSupplier) {
            cv.put("supplier_id", partyId)
            cv.putNull("customer_id")
        } else {
            cv.put("customer_id", partyId)
            cv.putNull("supplier_id")
        }
        cv.put("tx_type", root.optString("t"))
        cv.put("amount_paise", root.optLong("a"))
        cv.put("tx_time", root.optLong("tm"))
        cv.put("description", root.optString("d"))
        cv.put("reference_no", root.optString("r"))
        cv.put("payment_method", root.optString("m", "CASH"))
        cv.put("notes", root.optString("n"))
        cv.put("client_uuid", java.util.UUID.randomUUID().toString())
        cv.put("created_at", now)
        cv.put("updated_at", now)
        val newId = db.insertOrThrow("transactions", null, cv)
        log(businessId, "RESTORE", "transaction", newId, "")
    }

    // ------------------------------------------------------------ staff

    fun getStaff(businessId: Int, activeOnly: Boolean = false): List<Staff> {
        val where = "WHERE business_id = ?" + if (activeOnly) " AND is_active = 1" else ""
        val out = ArrayList<Staff>()
        helper.readableDatabase.rawQuery(
            "SELECT id, name, mobile, role, monthly_salary_paise, joined_at, is_active, notes " +
                "FROM staff $where ORDER BY name COLLATE NOCASE",
            arrayOf(businessId.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(readStaff(c))
        }
        return out
    }

    private fun readStaff(c: Cursor): Staff = Staff(
        id = c.getLong(0),
        name = c.getString(1) ?: "",
        mobile = c.getString(2) ?: "",
        role = c.getString(3) ?: StaffRoles.EMPLOYEE,
        monthlySalaryPaise = c.getLong(4),
        joinedAt = c.getLong(5),
        isActive = c.getInt(6) == 1,
        notes = c.getString(7) ?: ""
    )

    fun saveStaff(
        businessId: Int,
        id: Long,
        name: String,
        mobile: String,
        role: String,
        monthlySalaryPaise: Long,
        joinedAt: Long,
        isActive: Boolean,
        notes: String
    ): Long {
        require(name.isNotBlank()) { "Staff name is required" }
        require(role in StaffRoles.all()) { "Invalid role" }
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        val cv = ContentValues()
        cv.put("business_id", businessId)
        cv.put("name", name.trim())
        cv.put("mobile", mobile)
        cv.put("role", role)
        cv.put("monthly_salary_paise", monthlySalaryPaise)
        cv.put("joined_at", joinedAt)
        cv.put("is_active", if (isActive) 1 else 0)
        cv.put("notes", notes)
        cv.put("updated_at", now)
        if (id == 0L) {
            cv.put("created_at", now)
            val newId = db.insertOrThrow("staff", null, cv)
            log(businessId, "CREATE", "staff", newId, name)
            return newId
        }
        val rows = db.update("staff", cv, "id = ? AND business_id = ?",
            arrayOf(id.toString(), businessId.toString()))
        if (rows == 0) throw IllegalStateException("Staff not found")
        log(businessId, "UPDATE", "staff", id, name)
        return id
    }

    fun deleteStaff(businessId: Int, id: Long) {
        getStaffById(businessId, id)?.let { staff ->
            saveToBin(
                businessId, "staff", staff.name,
                JSONObject()
                    .put("name", staff.name)
                    .put("mobile", staff.mobile)
                    .put("role", staff.role)
                    .put("salary", staff.monthlySalaryPaise)
                    .put("joined", staff.joinedAt)
                    .put("active", staff.isActive)
                    .put("notes", staff.notes)
                    .toString()
            )
        }
        helper.writableDatabase.delete("staff", "id = ? AND business_id = ?",
            arrayOf(id.toString(), businessId.toString()))
        log(businessId, "DELETE", "staff", id, "")
    }

    private fun getStaffById(businessId: Int, id: Long): Staff? =
        getStaff(businessId).firstOrNull { it.id == id }

    fun getAttendance(staffId: Long, from: Long, to: Long): List<AttendanceDay> {
        val out = ArrayList<AttendanceDay>()
        helper.readableDatabase.rawQuery(
            "SELECT id, staff_id, day_start, present, note FROM attendance " +
                "WHERE staff_id = ? AND day_start >= ? AND day_start < ? ORDER BY day_start ASC",
            arrayOf(staffId.toString(), from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    AttendanceDay(
                        id = c.getLong(0),
                        staffId = c.getLong(1),
                        dayStart = c.getLong(2),
                        present = c.getInt(3) == 1,
                        note = c.getString(4) ?: ""
                    )
                )
            }
        }
        return out
    }

    /** Marks present/absent for one day (idempotent). */
    fun markAttendance(staffId: Long, dayStart: Long, present: Boolean) {
        val db = helper.writableDatabase
        val cv = ContentValues()
        cv.put("staff_id", staffId)
        cv.put("day_start", dayStart)
        cv.put("present", if (present) 1 else 0)
        val existing = db.rawQuery(
            "SELECT id FROM attendance WHERE staff_id = ? AND day_start = ?",
            arrayOf(staffId.toString(), dayStart.toString())
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        if (existing == 0L) {
            cv.put("created_at", System.currentTimeMillis())
            db.insertOrThrow("attendance", null, cv)
        } else {
            cv.remove("staff_id")
            cv.remove("day_start")
            db.update("attendance", cv, "id = ?", arrayOf(existing.toString()))
        }
    }

    /** Salary payments are real expenses in the Salary category (visible in reports). */
    fun paySalary(businessId: Int, staff: Staff, amountPaise: Long, method: String, note: String): Long {
        val title = "Salary: " + staff.name
        val expenseRepo = ExpenseRepo.get(appContext)
        val salaryCategory = findOrCreateSalaryCategory(businessId, expenseRepo)
        val id = expenseRepo.saveExpense(
            businessId, 0L, salaryCategory, title, amountPaise,
            Dates.today().from, method, note, "", ExpenseRepo.REC_NONE
        )
        log(businessId, "CREATE", "salary", id, staff.name)
        return id
    }

    private fun findOrCreateSalaryCategory(businessId: Int, repo: ExpenseRepo): Long {
        val cats = repo.getCategories(businessId)
        cats.firstOrNull { it.name.equals("Salary", ignoreCase = true) }?.let { return it.id }
        return repo.saveCategory(businessId, 0L, "Salary")
    }

    fun getStaffSummaries(businessId: Int, from: Long, to: Long): List<StaffSummary> {
        val out = ArrayList<StaffSummary>()
        val db = helper.readableDatabase
        for (s in getStaff(businessId)) {
            var present = 0
            var absent = 0
            getAttendance(s.id, from, to).forEach { if (it.present) present++ else absent++ }
            var paid = 0L
            db.rawQuery(
                "SELECT e.title, e.amount_paise FROM expenses e " +
                    "LEFT JOIN expense_categories c ON c.id = e.category_id " +
                    "WHERE e.business_id = ? AND c.name = 'Salary' " +
                    "AND e.expense_date >= ? AND e.expense_date < ?",
                arrayOf(businessId.toString(), from.toString(), to.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    if ((c.getString(0) ?: "").endsWith(s.name, ignoreCase = true)) {
                        paid += c.getLong(1)
                    }
                }
            }
            out.add(StaffSummary(s, present, absent, paid))
        }
        return out
    }
}
