package com.hisabbaki.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.util.Calendar

data class ExpenseCategory(val id: Long, val name: String)

data class Expense(
    val id: Long,
    val categoryId: Long,
    val categoryName: String,
    val title: String,
    val amountPaise: Long,
    val expenseDate: Long,
    val paymentMethod: String,
    val notes: String,
    val attachmentPath: String,
    val recurrence: String,
    val recurringNext: Long
)

data class ExpenseStats(
    val todayPaise: Long,
    val monthPaise: Long,
    val yearPaise: Long,
    val allPaise: Long
)

/** Blocking expense methods. Call only through Db.async. */
class ExpenseRepo private constructor(private val helper: DatabaseHelper) {

    companion object {
        const val REC_NONE = "NONE"
        const val REC_DAILY = "DAILY"
        const val REC_WEEKLY = "WEEKLY"
        const val REC_MONTHLY = "MONTHLY"

        fun get(context: Context): ExpenseRepo = ExpenseRepo(DatabaseHelper.get(context))

        fun dayStart(ms: Long): Long {
            val cal = Calendar.getInstance()
            cal.timeInMillis = ms
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }

    // ------------------------------------------------------------ categories

    fun getCategories(businessId: Int): List<ExpenseCategory> {
        val out = ArrayList<ExpenseCategory>()
        helper.readableDatabase.rawQuery(
            "SELECT id, name FROM expense_categories WHERE business_id = ? ORDER BY name COLLATE NOCASE",
            arrayOf(businessId.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(ExpenseCategory(c.getLong(0), c.getString(1) ?: ""))
        }
        return out
    }

    fun saveCategory(businessId: Int, id: Long, name: String): Long {
        require(name.isNotBlank()) { "Category name is required" }
        val db = helper.writableDatabase
        if (id != 0L) {
            val cv = ContentValues()
            cv.put("name", name.trim())
            db.update(
                "expense_categories", cv, "id = ? AND business_id = ?",
                arrayOf(id.toString(), businessId.toString())
            )
            return id
        }
        val cv = ContentValues()
        cv.put("business_id", businessId)
        cv.put("name", name.trim())
        cv.put("created_at", System.currentTimeMillis())
        val inserted = db.insertWithOnConflict(
            "expense_categories", null, cv, SQLiteDatabase.CONFLICT_IGNORE
        )
        if (inserted != -1L) return inserted
        return db.rawQuery(
            "SELECT id FROM expense_categories WHERE business_id = ? AND name = ?",
            arrayOf(businessId.toString(), name.trim())
        ).use { if (it.moveToFirst()) it.getLong(0) else -1L }
    }

    fun deleteCategory(businessId: Int, id: Long) {
        helper.writableDatabase.delete(
            "expense_categories", "id = ? AND business_id = ?",
            arrayOf(id.toString(), businessId.toString())
        )
    }

    // ------------------------------------------------------------ expenses

    private fun readExpense(c: Cursor): Expense {
        val catIdx = c.getColumnIndex("category_id")
        val nameIdx = c.getColumnIndex("category_name")
        return Expense(
            id = c.getLong(0),
            categoryId = if (catIdx < 0 || c.isNull(catIdx)) 0L else c.getLong(catIdx),
            categoryName = if (nameIdx < 0) "" else (c.getString(nameIdx) ?: ""),
            title = c.getString(c.getColumnIndexOrThrow("title")) ?: "",
            amountPaise = c.getLong(c.getColumnIndexOrThrow("amount_paise")),
            expenseDate = c.getLong(c.getColumnIndexOrThrow("expense_date")),
            paymentMethod = c.getString(c.getColumnIndexOrThrow("payment_method")) ?: "CASH",
            notes = c.getString(c.getColumnIndexOrThrow("notes")) ?: "",
            attachmentPath = c.getString(c.getColumnIndexOrThrow("attachment_path")) ?: "",
            recurrence = c.getString(c.getColumnIndexOrThrow("recurrence")) ?: "NONE",
            recurringNext = c.getLong(c.getColumnIndexOrThrow("recurring_next"))
        )
    }

    private val expenseColumns =
        "e.id, e.category_id, IFNULL(c.name, '') AS category_name, e.title, e.amount_paise, " +
            "e.expense_date, e.payment_method, e.notes, e.attachment_path, e.recurrence, e.recurring_next"

    fun getExpenses(
        businessId: Int,
        query: String,
        categoryId: Long,
        from: Long?,
        to: Long?
    ): List<Expense> {
        val where = StringBuilder("WHERE e.business_id = ?")
        val args = ArrayList<String>()
        args.add(businessId.toString())
        val q = query.trim()
        if (q.isNotEmpty()) {
            where.append(" AND (e.title LIKE ? ESCAPE '\\' OR e.notes LIKE ? ESCAPE '\\')")
            val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            args.add(like)
            args.add(like)
        }
        if (categoryId != 0L) {
            where.append(" AND e.category_id = ?")
            args.add(categoryId.toString())
        }
        if (from != null) {
            where.append(" AND e.expense_date >= ?")
            args.add(from.toString())
        }
        if (to != null) {
            where.append(" AND e.expense_date < ?")
            args.add(to.toString())
        }
        val list = ArrayList<Expense>()
        helper.readableDatabase.rawQuery(
            "SELECT $expenseColumns FROM expenses e " +
                "LEFT JOIN expense_categories c ON c.id = e.category_id " +
                "$where ORDER BY e.expense_date DESC, e.id DESC LIMIT 500",
            args.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) list.add(readExpense(c))
        }
        return list
    }

    fun getExpense(businessId: Int, id: Long): Expense? {
        var found: Expense? = null
        helper.readableDatabase.rawQuery(
            "SELECT $expenseColumns FROM expenses e " +
                "LEFT JOIN expense_categories c ON c.id = e.category_id " +
                "WHERE e.id = ? AND e.business_id = ?",
            arrayOf(id.toString(), businessId.toString())
        ).use { c -> if (c.moveToFirst()) found = readExpense(c) }
        return found
    }

    fun saveExpense(
        businessId: Int,
        id: Long,
        categoryId: Long,
        title: String,
        amountPaise: Long,
        expenseDate: Long,
        paymentMethod: String,
        notes: String,
        attachmentPath: String,
        recurrence: String
    ): Long {
        require(title.isNotBlank()) { "Expense description is required" }
        require(amountPaise > 0) { "Amount must be positive" }
        require(recurrence in listOf(REC_NONE, REC_DAILY, REC_WEEKLY, REC_MONTHLY)) {
            "Invalid recurrence"
        }
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        val cv = ContentValues()
        cv.put("business_id", businessId)
        if (categoryId != 0L) cv.put("category_id", categoryId) else cv.putNull("category_id")
        cv.put("title", title.trim())
        cv.put("amount_paise", amountPaise)
        cv.put("expense_date", expenseDate)
        cv.put("payment_method", paymentMethod)
        cv.put("notes", notes)
        cv.put("attachment_path", attachmentPath)
        cv.put("recurrence", recurrence)
        cv.put("recurring_next", nextRecurrenceTime(expenseDate, recurrence))
        cv.put("updated_at", now)
        if (id == 0L) {
            cv.put("created_at", now)
            return db.insertOrThrow("expenses", null, cv)
        }
        val rows = db.update(
            "expenses", cv, "id = ? AND business_id = ?",
            arrayOf(id.toString(), businessId.toString())
        )
        if (rows == 0) throw IllegalStateException("Expense not found")
        return id
    }

    fun deleteExpense(businessId: Int, id: Long) {
        helper.writableDatabase.delete(
            "expenses", "id = ? AND business_id = ?",
            arrayOf(id.toString(), businessId.toString())
        )
    }

    /**
     * Repeats due recurring expenses as real records (catch-up, once per call).
     * Called when the app starts so recurring expenses materialise even after skipped days.
     */
    fun repeatDueRecurring(businessId: Int): Int {
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        var created = 0
        try {
            val due = ArrayList<Expense>()
            db.rawQuery(
                "SELECT $expenseColumns FROM expenses e " +
                    "LEFT JOIN expense_categories c ON c.id = e.category_id " +
                    "WHERE e.business_id = ? AND e.recurrence != 'NONE' " +
                    "AND e.recurring_next > 0 AND e.recurring_next <= ? LIMIT 100",
                arrayOf(businessId.toString(), now.toString())
            ).use { c ->
                while (c.moveToNext()) due.add(readExpense(c))
            }
            for (e in due) {
                val cv = ContentValues()
                cv.put("business_id", businessId)
                if (e.categoryId != 0L) cv.put("category_id", e.categoryId) else cv.putNull("category_id")
                cv.put("title", e.title)
                cv.put("amount_paise", e.amountPaise)
                cv.put("expense_date", e.recurringNext)
                cv.put("payment_method", e.paymentMethod)
                cv.put("notes", e.notes)
                cv.put("attachment_path", "")
                cv.put("recurrence", e.recurrence)
                cv.put("recurring_next", nextRecurrenceTime(e.recurringNext, e.recurrence))
                cv.put("created_at", now)
                cv.put("updated_at", now)
                db.insert("expenses", null, cv)
                created++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return created
    }

    private fun nextRecurrenceTime(from: Long, recurrence: String): Long {
        if (recurrence == REC_NONE) return 0L
        val cal = Calendar.getInstance()
        cal.timeInMillis = from
        when (recurrence) {
            REC_DAILY -> cal.add(Calendar.DAY_OF_MONTH, 1)
            REC_WEEKLY -> cal.add(Calendar.DAY_OF_MONTH, 7)
            REC_MONTHLY -> cal.add(Calendar.MONTH, 1)
        }
        return cal.timeInMillis
    }

    fun getStats(businessId: Int): ExpenseStats {
        val day = dayStart(System.currentTimeMillis())
        val cal = Calendar.getInstance()
        cal.timeInMillis = day
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val month = cal.timeInMillis
        cal.set(Calendar.DAY_OF_YEAR, 1)
        val year = cal.timeInMillis
        val db = helper.readableDatabase
        fun sum(from: Long): Long {
            db.rawQuery(
                "SELECT COALESCE(SUM(amount_paise), 0) FROM expenses " +
                    "WHERE business_id = ? AND expense_date >= ?",
                arrayOf(businessId.toString(), from.toString())
            ).use { c -> if (c.moveToFirst()) return c.getLong(0) }
            return 0L
        }
        val all = db.rawQuery(
            "SELECT COALESCE(SUM(amount_paise), 0) FROM expenses WHERE business_id = ?",
            arrayOf(businessId.toString())
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        return ExpenseStats(sum(day), sum(month), sum(year), all)
    }

    /** Category totals inside a range (for the expense report / chart). */
    fun getCategoryTotals(businessId: Int, from: Long, to: Long): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>()
        helper.readableDatabase.rawQuery(
            "SELECT IFNULL(c.name, ''), SUM(e.amount_paise) FROM expenses e " +
                "LEFT JOIN expense_categories c ON c.id = e.category_id " +
                "WHERE e.business_id = ? AND e.expense_date >= ? AND e.expense_date < ? " +
                "GROUP BY e.category_id ORDER BY 2 DESC",
            arrayOf(businessId.toString(), from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Pair(c.getString(0) ?: "", c.getLong(1)))
            }
        }
        return out
    }
}
