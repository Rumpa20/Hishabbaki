package com.hisabbaki.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.util.Calendar
import java.util.TreeSet

data class CashAccount(
    val id: Long,
    val name: String,
    val accountType: String,
    val openingPaise: Long,
    val isDefault: Boolean
)

data class AccountBalance(
    val account: CashAccount,
    val cashInPaise: Long,
    val cashOutPaise: Long,
    val balancePaise: Long
)

data class CashSummary(
    val accounts: List<AccountBalance>,
    val totalInPaise: Long,
    val totalOutPaise: Long,
    val totalCashPaise: Long
)

data class CashTransfer(
    val id: Long,
    val fromName: String,
    val toName: String,
    val amountPaise: Long,
    val note: String,
    val txTime: Long
)

/**
 * Cashbook: accounts derive their balance from
 * account opening + customer payments in (GOT) + supplier payments out (PAYMENT)
 * + expenses paid by method + transfers between accounts.
 * Cash-in/out is never stored twice; everything is calculated from real records.
 */
class CashbookRepo private constructor(private val helper: DatabaseHelper) {

    companion object {
        fun get(context: Context): CashbookRepo = CashbookRepo(DatabaseHelper.get(context))
    }

    fun getAccounts(businessId: Int): List<CashAccount> {
        val out = ArrayList<CashAccount>()
        helper.readableDatabase.rawQuery(
            "SELECT id, name, account_type, opening_paise, is_default FROM cash_accounts " +
                "WHERE business_id = ? ORDER BY is_default DESC, name COLLATE NOCASE",
            arrayOf(businessId.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(readAccount(c))
        }
        return out
    }

    private fun readAccount(c: Cursor): CashAccount = CashAccount(
        id = c.getLong(0),
        name = c.getString(1) ?: "",
        accountType = c.getString(2) ?: "CASH",
        openingPaise = c.getLong(3),
        isDefault = c.getInt(4) == 1
    )

    fun getAccount(businessId: Int, id: Long): CashAccount? {
        var found: CashAccount? = null
        helper.readableDatabase.rawQuery(
            "SELECT id, name, account_type, opening_paise, is_default FROM cash_accounts " +
                "WHERE id = ? AND business_id = ?",
            arrayOf(id.toString(), businessId.toString())
        ).use { c -> if (c.moveToFirst()) found = readAccount(c) }
        return found
    }

    /** Balances for the range: opening up to 'from', in/out inside [from, to). */
    fun getSummary(businessId: Int, from: Long, to: Long): CashSummary {
        val db = helper.readableDatabase
        val accounts = getAccounts(businessId)
        val list = ArrayList<AccountBalance>()
        var totalIn = 0L
        var totalOut = 0L
        var totalCash = 0L
        for (a in accounts) {
            val inflow = inflowFor(db, businessId, a, from, to)
            val outflow = outflowFor(db, businessId, a, from, to)
            val balance = balanceAt(db, businessId, a, to)
            list.add(AccountBalance(a, inflow, outflow, balance))
            totalIn += inflow
            totalOut += outflow
            if (a.accountType == "CASH") totalCash += balance
        }
        return CashSummary(list, totalIn, totalOut, totalCash)
    }

    private fun methodTypes(account: CashAccount): List<String> = when (account.accountType) {
        "BANK" -> listOf("BANK")
        "UPI" -> listOf("UPI")
        else -> listOf("CASH", "OTHER")
    }

    private fun inflowFor(
        db: SQLiteDatabase,
        businessId: Int,
        account: CashAccount,
        from: Long,
        to: Long
    ): Long {
        val methods = methodTypes(account)
        val placeholders = methods.joinToString(",") { "?" }
        val args = ArrayList<String>()
        args.add(businessId.toString())
        args.add(from.toString())
        args.add(to.toString())
        args.add(account.id.toString())
        args.addAll(methods)
        return db.rawQuery(
            "SELECT COALESCE(SUM(amount_paise), 0) FROM transactions WHERE business_id = ? " +
                "AND tx_type = 'GOT' AND tx_time >= ? AND tx_time < ? " +
                "AND (account_id = ? OR (account_id IS NULL AND payment_method IN ($placeholders)))",
            args.toTypedArray()
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    }

    private fun outflowFor(
        db: SQLiteDatabase,
        businessId: Int,
        account: CashAccount,
        from: Long,
        to: Long
    ): Long {
        val methods = methodTypes(account)
        val placeholders = methods.joinToString(",") { "?" }
        var sum = 0L
        val args = ArrayList<String>()
        args.add(businessId.toString())
        args.add(from.toString())
        args.add(to.toString())
        args.add(account.id.toString())
        args.addAll(methods)
        sum += db.rawQuery(
            "SELECT COALESCE(SUM(amount_paise), 0) FROM transactions WHERE business_id = ? " +
                "AND tx_type = 'PAYMENT' AND tx_time >= ? AND tx_time < ? " +
                "AND (account_id = ? OR (account_id IS NULL AND payment_method IN ($placeholders)))",
            args.toTypedArray()
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        sum += db.rawQuery(
            "SELECT COALESCE(SUM(amount_paise), 0) FROM expenses WHERE business_id = ? " +
                "AND expense_date >= ? AND expense_date < ? AND payment_method IN ($placeholders)",
            arrayOf(businessId.toString(), from.toString(), to.toString(), *methods.toTypedArray())
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        return sum
    }

    /** Balance after all activity before 'to'. */
    private fun balanceAt(db: SQLiteDatabase, businessId: Int, account: CashAccount, to: Long): Long {
        val methods = methodTypes(account)
        val placeholders = methods.joinToString(",") { "?" }
        val args = ArrayList<String>()
        args.add(businessId.toString())
        args.add(to.toString())
        args.add(account.id.toString())
        args.addAll(methods)
        val scoped =
            "(account_id = ? OR (account_id IS NULL AND payment_method IN ($placeholders)))"
        var balance = 0L
        db.rawQuery(
            "SELECT COALESCE(SUM(amount_paise), 0) FROM transactions WHERE business_id = ? " +
                "AND tx_time < ? AND tx_type = 'GOT' AND $scoped",
            args.toTypedArray()
        ).use { c -> if (c.moveToFirst()) balance += c.getLong(0) }
        db.rawQuery(
            "SELECT COALESCE(SUM(amount_paise), 0) FROM transactions WHERE business_id = ? " +
                "AND tx_time < ? AND tx_type = 'PAYMENT' AND $scoped",
            args.toTypedArray()
        ).use { c -> if (c.moveToFirst()) balance -= c.getLong(0) }
        val expArgs = ArrayList<String>()
        expArgs.add(businessId.toString())
        expArgs.add(to.toString())
        expArgs.addAll(methods)
        db.rawQuery(
            "SELECT COALESCE(SUM(amount_paise), 0) FROM expenses WHERE business_id = ? " +
                "AND expense_date < ? AND payment_method IN ($placeholders)",
            expArgs.toTypedArray()
        ).use { c -> if (c.moveToFirst()) balance -= c.getLong(0) }
        // transfers to this account add, from this account subtract
        balance += db.rawQuery(
            "SELECT COALESCE(SUM(amount_paise), 0) FROM cash_transfers WHERE business_id = ? " +
                "AND to_account_id = ? AND tx_time < ?",
            arrayOf(businessId.toString(), account.id.toString(), to.toString())
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        balance -= db.rawQuery(
            "SELECT COALESCE(SUM(amount_paise), 0) FROM cash_transfers WHERE business_id = ? " +
                "AND from_account_id = ? AND tx_time < ?",
            arrayOf(businessId.toString(), account.id.toString(), to.toString())
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        return account.openingPaise + balance
    }

    fun saveAccount(
        businessId: Int,
        id: Long,
        name: String,
        accountType: String,
        openingPaise: Long,
        isDefault: Boolean
    ): Long {
        require(name.isNotBlank()) { "Account name is required" }
        require(accountType in listOf("CASH", "BANK", "UPI")) { "Invalid account type" }
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        val cv = ContentValues()
        cv.put("business_id", businessId)
        cv.put("name", name.trim())
        cv.put("account_type", accountType)
        cv.put("opening_paise", openingPaise)
        cv.put("is_default", if (isDefault) 1 else 0)
        cv.put("updated_at", now)
        db.beginTransaction()
        try {
            if (isDefault) {
                db.execSQL("UPDATE cash_accounts SET is_default = 0 WHERE business_id = " + businessId)
            }
            val resultId: Long
            if (id == 0L) {
                cv.put("created_at", now)
                resultId = db.insertOrThrow("cash_accounts", null, cv)
            } else {
                val rows = db.update("cash_accounts", cv, "id = ? AND business_id = ?",
                    arrayOf(id.toString(), businessId.toString()))
                if (rows == 0) throw IllegalStateException("Account not found")
                resultId = id
            }
            db.setTransactionSuccessful()
            return resultId
        } finally {
            db.endTransaction()
        }
    }

    fun deleteAccount(businessId: Int, id: Long) {
        val count = helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM cash_accounts WHERE business_id = ?",
            arrayOf(businessId.toString())
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        if (count <= 1) throw IllegalStateException("The last account cannot be deleted")
        helper.writableDatabase.delete("cash_accounts", "id = ? AND business_id = ?",
            arrayOf(id.toString(), businessId.toString()))
    }

    /** Transfers money between two accounts inside one transaction. */
    fun transfer(
        businessId: Int,
        fromAccountId: Long,
        toAccountId: Long,
        amountPaise: Long,
        note: String,
        txTime: Long
    ) {
        require(fromAccountId != toAccountId) { "Choose two different accounts" }
        require(amountPaise > 0) { "Amount must be positive" }
        val cv = ContentValues()
        cv.put("business_id", businessId)
        cv.put("from_account_id", fromAccountId)
        cv.put("to_account_id", toAccountId)
        cv.put("amount_paise", amountPaise)
        cv.put("note", note)
        cv.put("tx_time", txTime)
        cv.put("created_at", System.currentTimeMillis())
        helper.writableDatabase.insertOrThrow("cash_transfers", null, cv)
    }

    fun deleteTransfer(businessId: Int, id: Long) {
        helper.writableDatabase.delete("cash_transfers", "id = ? AND business_id = ?",
            arrayOf(id.toString(), businessId.toString()))
    }

    fun getTransfers(businessId: Int, from: Long, to: Long): List<CashTransfer> {
        val out = ArrayList<CashTransfer>()
        helper.readableDatabase.rawQuery(
            "SELECT t.id, IFNULL(a.name, ''), IFNULL(b.name, ''), t.amount_paise, t.note, t.tx_time " +
                "FROM cash_transfers t " +
                "LEFT JOIN cash_accounts a ON a.id = t.from_account_id " +
                "LEFT JOIN cash_accounts b ON b.id = t.to_account_id " +
                "WHERE t.business_id = ? AND t.tx_time >= ? AND t.tx_time < ? " +
                "ORDER BY t.tx_time DESC, t.id DESC LIMIT 200",
            arrayOf(businessId.toString(), from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    CashTransfer(
                        id = c.getLong(0),
                        fromName = c.getString(1) ?: "",
                        toName = c.getString(2) ?: "",
                        amountPaise = c.getLong(3),
                        note = c.getString(4) ?: "",
                        txTime = c.getLong(5)
                    )
                )
            }
        }
        return out
    }

    /** Daily cash in / out inside the range (any account), oldest first. */
    fun getDailyFlow(businessId: Int, from: Long, to: Long): List<Triple<Long, Long, Long>> {
        val out = ArrayList<Triple<Long, Long, Long>>()
        val inMap = HashMap<Long, Long>()
        val outMap = HashMap<Long, Long>()
        val db = helper.readableDatabase
        db.rawQuery(
            "SELECT tx_time, amount_paise FROM transactions WHERE business_id = ? " +
                "AND tx_type = 'GOT' AND tx_time >= ? AND tx_time < ?",
            arrayOf(businessId.toString(), from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val day = dayStart(c.getLong(0))
                inMap[day] = (inMap[day] ?: 0L) + c.getLong(1)
            }
        }
        db.rawQuery(
            "SELECT tx_time, amount_paise FROM transactions WHERE business_id = ? " +
                "AND tx_type = 'PAYMENT' AND tx_time >= ? AND tx_time < ?",
            arrayOf(businessId.toString(), from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val day = dayStart(c.getLong(0))
                outMap[day] = (outMap[day] ?: 0L) + c.getLong(1)
            }
        }
        db.rawQuery(
            "SELECT expense_date, amount_paise FROM expenses WHERE business_id = ? " +
                "AND expense_date >= ? AND expense_date < ?",
            arrayOf(businessId.toString(), from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val day = dayStart(c.getLong(0))
                outMap[day] = (outMap[day] ?: 0L) + c.getLong(1)
            }
        }
        val days = TreeSet<Long>()
        days.addAll(inMap.keys)
        days.addAll(outMap.keys)
        for (day in days) {
            out.add(Triple(day, inMap[day] ?: 0L, outMap[day] ?: 0L))
        }
        return out
    }

    private fun dayStart(ms: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ms
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Ensures the standard Cash account exists (called after migration / business creation). */
    fun ensureDefaultAccount(businessId: Int) {
        val db = helper.writableDatabase
        val count = db.rawQuery(
            "SELECT COUNT(*) FROM cash_accounts WHERE business_id = ?",
            arrayOf(businessId.toString())
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        if (count > 0) return
        val now = System.currentTimeMillis()
        val cv = ContentValues()
        cv.put("business_id", businessId)
        cv.put("name", "Cash in Hand")
        cv.put("account_type", "CASH")
        cv.put("opening_paise", 0L)
        cv.put("is_default", 1)
        cv.put("created_at", now)
        cv.put("updated_at", now)
        db.insert("cash_accounts", null, cv)
    }
}
