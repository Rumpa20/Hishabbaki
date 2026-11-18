package com.hisabbaki.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.hisabbaki.app.Prefs
import java.util.Calendar

/** All methods are blocking. Call them only through Db.async (background thread). */
class Repository private constructor(
    private val appContext: Context,
    private val helper: DatabaseHelper
) {

    companion object {
        fun get(context: Context): Repository =
            Repository(context.applicationContext, DatabaseHelper.get(context))

        private fun table(isSupplier: Boolean): String = if (isSupplier) "suppliers" else "customers"

        private fun fkColumn(isSupplier: Boolean): String = if (isSupplier) "supplier_id" else "customer_id"

        // Balance is always derived from opening balance + transactions, never stored.
        // So editing or deleting a transaction can never corrupt a balance.
        private fun balanceExpr(isSupplier: Boolean): String =
            "p.opening_balance_paise + COALESCE((SELECT SUM(CASE t.tx_type WHEN '" +
                TxTypes.increase(isSupplier) +
                "' THEN t.amount_paise ELSE -t.amount_paise END) FROM transactions t WHERE t." +
                fkColumn(isSupplier) + " = p.id), 0)"

        private const val TX_COLUMNS =
            "id, customer_id, supplier_id, tx_type, amount_paise, tx_time, description, " +
                "reference_no, payment_method, notes, client_uuid"
    }

    // ------------------------------------------------------------ parties

    private fun queryParties(
        isSupplier: Boolean,
        where: String,
        args: Array<String>,
        order: String
    ): List<Party> {
        val sql = "SELECT p.id, p.name, p.mobile, p.address, p.opening_balance_paise, p.notes, " +
            balanceExpr(isSupplier) + " AS balance FROM " + table(isSupplier) + " p " +
            where + " ORDER BY " + order
        val out = ArrayList<Party>()
        helper.readableDatabase.rawQuery(sql, if (args.isEmpty()) null else args).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Party(
                        id = c.getLong(0),
                        isSupplier = isSupplier,
                        name = c.getString(1) ?: "",
                        mobile = c.getString(2) ?: "",
                        address = c.getString(3) ?: "",
                        openingPaise = c.getLong(4),
                        notes = c.getString(5) ?: "",
                        balancePaise = c.getLong(6)
                    )
                )
            }
        }
        return out
    }

    fun getParties(isSupplier: Boolean, query: String, sortByBalance: Boolean): List<Party> {
        val q = query.trim()
        val args = ArrayList<String>()
        var where = ""
        if (q.isNotEmpty()) {
            val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            where = "WHERE p.name LIKE ? ESCAPE '\\' OR p.mobile LIKE ? ESCAPE '\\'"
            args.add(like)
            args.add(like)
        }
        val order = if (sortByBalance) {
            "balance DESC, p.name COLLATE NOCASE ASC"
        } else {
            "p.name COLLATE NOCASE ASC"
        }
        return queryParties(isSupplier, where, args.toTypedArray(), order)
    }

    fun getParty(isSupplier: Boolean, id: Long): Party? =
        queryParties(isSupplier, "WHERE p.id = ?", arrayOf(id.toString()), "p.id").firstOrNull()

    /** Inserts (id == 0) or updates a party. Returns the party id. */
    fun saveParty(
        isSupplier: Boolean,
        id: Long,
        name: String,
        mobile: String,
        address: String,
        openingPaise: Long,
        notes: String
    ): Long {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            if (mobile.isNotEmpty() && mobileExists(db, isSupplier, mobile, id)) {
                throw DuplicateMobileException()
            }
            val now = System.currentTimeMillis()
            val cv = ContentValues()
            cv.put("name", name)
            cv.put("mobile", mobile)
            cv.put("address", address)
            cv.put("opening_balance_paise", openingPaise)
            cv.put("notes", notes)
            cv.put("updated_at", now)
            val resultId: Long
            if (id == 0L) {
                cv.put("created_at", now)
                resultId = db.insertOrThrow(table(isSupplier), null, cv)
            } else {
                val rows = db.update(table(isSupplier), cv, "id = ?", arrayOf(id.toString()))
                if (rows == 0) throw IllegalStateException("Record not found")
                resultId = id
            }
            db.setTransactionSuccessful()
            return resultId
        } finally {
            db.endTransaction()
        }
    }

    private fun mobileExists(db: SQLiteDatabase, isSupplier: Boolean, mobile: String, excludeId: Long): Boolean =
        db.rawQuery(
            "SELECT 1 FROM " + table(isSupplier) + " WHERE mobile = ? AND id != ? LIMIT 1",
            arrayOf(mobile, excludeId.toString())
        ).use { it.moveToFirst() }

    fun countPartyTransactions(isSupplier: Boolean, id: Long): Int =
        scalarLong(
            helper.readableDatabase,
            "SELECT COUNT(*) FROM transactions WHERE " + fkColumn(isSupplier) + " = ?",
            arrayOf(id.toString())
        ).toInt()

    /** Deletes a party; their transactions are removed by the foreign-key cascade. */
    fun deleteParty(isSupplier: Boolean, id: Long) {
        helper.writableDatabase.delete(table(isSupplier), "id = ?", arrayOf(id.toString()))
    }

    // ------------------------------------------------------------ transactions

    private fun readTx(c: Cursor): Tx {
        val supplier = !c.isNull(2)
        val partyId = if (supplier) c.getLong(2) else c.getLong(1)
        return Tx(
            id = c.getLong(0),
            partyId = partyId,
            isSupplier = supplier,
            txType = c.getString(3) ?: "",
            amountPaise = c.getLong(4),
            txTime = c.getLong(5),
            description = c.getString(6) ?: "",
            referenceNo = c.getString(7) ?: "",
            paymentMethod = c.getString(8) ?: "CASH",
            notes = c.getString(9) ?: "",
            clientUuid = c.getString(10) ?: ""
        )
    }

    fun getTransaction(id: Long): Tx? =
        helper.readableDatabase.rawQuery(
            "SELECT " + TX_COLUMNS + " FROM transactions WHERE id = ?",
            arrayOf(id.toString())
        ).use { c -> if (c.moveToFirst()) readTx(c) else null }

    /**
     * Inserts (tx.id == 0) or updates a transaction. Saving the same clientUuid twice
     * (for example a double tap) never creates a duplicate entry.
     */
    fun saveTransaction(tx: Tx): Long {
        require(tx.amountPaise > 0L) { "Amount must be positive" }
        require(
            tx.txType == TxTypes.increase(tx.isSupplier) || tx.txType == TxTypes.decrease(tx.isSupplier)
        ) { "Invalid transaction type" }

        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        val cv = ContentValues()
        if (tx.isSupplier) {
            cv.put("supplier_id", tx.partyId)
            cv.putNull("customer_id")
        } else {
            cv.put("customer_id", tx.partyId)
            cv.putNull("supplier_id")
        }
        cv.put("tx_type", tx.txType)
        cv.put("amount_paise", tx.amountPaise)
        cv.put("tx_time", tx.txTime)
        cv.put("description", tx.description)
        cv.put("reference_no", tx.referenceNo)
        cv.put("payment_method", tx.paymentMethod)
        cv.put("notes", tx.notes)
        cv.put("updated_at", now)

        db.beginTransaction()
        try {
            val resultId: Long
            if (tx.id == 0L) {
                cv.put("client_uuid", tx.clientUuid)
                cv.put("created_at", now)
                val inserted = db.insertWithOnConflict(
                    "transactions", null, cv, SQLiteDatabase.CONFLICT_IGNORE
                )
                resultId = if (inserted != -1L) {
                    inserted
                } else {
                    findIdByUuid(db, tx.clientUuid)
                        ?: throw IllegalStateException("Transaction was rejected")
                }
            } else {
                val rows = db.update("transactions", cv, "id = ?", arrayOf(tx.id.toString()))
                if (rows == 0) throw IllegalStateException("Transaction not found")
                resultId = tx.id
            }
            db.setTransactionSuccessful()
            return resultId
        } finally {
            db.endTransaction()
        }
    }

    private fun findIdByUuid(db: SQLiteDatabase, uuid: String): Long? =
        db.rawQuery("SELECT id FROM transactions WHERE client_uuid = ?", arrayOf(uuid))
            .use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    fun deleteTransaction(id: Long) {
        helper.writableDatabase.delete("transactions", "id = ?", arrayOf(id.toString()))
    }

    // ------------------------------------------------------------ ledger

    /**
     * typeFilter: null = all, otherwise one tx_type. fromMs inclusive, toMs exclusive.
     * Returns null if the party does not exist.
     */
    fun getLedger(
        isSupplier: Boolean,
        partyId: Long,
        typeFilter: String?,
        fromMs: Long?,
        toMs: Long?
    ): LedgerData? {
        val party = getParty(isSupplier, partyId) ?: return null
        val increaseType = TxTypes.increase(isSupplier)

        val all = ArrayList<LedgerEntry>()
        var running = party.openingPaise
        var totalIncrease = 0L
        var totalDecrease = 0L

        helper.readableDatabase.rawQuery(
            "SELECT " + TX_COLUMNS + " FROM transactions WHERE " + fkColumn(isSupplier) +
                " = ? ORDER BY tx_time ASC, id ASC",
            arrayOf(partyId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val tx = readTx(c)
                if (tx.txType == increaseType) {
                    running += tx.amountPaise
                    totalIncrease += tx.amountPaise
                } else {
                    running -= tx.amountPaise
                    totalDecrease += tx.amountPaise
                }
                all.add(LedgerEntry(tx, running))
            }
        }

        val filtered = all.filter { e ->
            (typeFilter == null || e.tx.txType == typeFilter) &&
                (fromMs == null || e.tx.txTime >= fromMs) &&
                (toMs == null || e.tx.txTime < toMs)
        }
        return LedgerData(party, filtered.reversed(), totalIncrease, totalDecrease)
    }

    // ------------------------------------------------------------ business profile

    fun getBusinessProfile(): BusinessProfile {
        val core = CoreRepo.get(appContext)
        val b = core.activeBusiness(Prefs.currentBusinessId(appContext))
        return BusinessProfile(
            id = b.id,
            name = b.name,
            owner = b.owner,
            mobile = b.mobile,
            address = b.address,
            gstin = b.gstin,
            logoPath = b.logoPath,
            upiId = b.upiId
        )
    }

    fun saveBusinessProfile(p: BusinessProfile) {
        CoreRepo.get(appContext).saveBusiness(
            if (p.id != 0) p.id else Prefs.currentBusinessId(appContext),
            p.name, p.owner, p.mobile, p.address, p.gstin, p.logoPath, p.upiId, "INR"
        )
    }

    private fun Context.activeBiz(): Int = Prefs.currentBusinessId(this)


    // ------------------------------------------------------------ reminders

    fun addReminder(customerId: Long, amountPaise: Long, channel: String, message: String): Long {
        val cv = ContentValues()
        cv.put("customer_id", customerId)
        cv.put("amount_paise", amountPaise)
        cv.put("channel", channel)
        cv.put("message", message)
        cv.put("created_at", System.currentTimeMillis())
        return helper.writableDatabase.insertOrThrow("reminders", null, cv)
    }

    fun getReminders(customerId: Long): List<ReminderRecord> {
        val out = ArrayList<ReminderRecord>()
        helper.readableDatabase.rawQuery(
            "SELECT id, channel, amount_paise, message, created_at FROM reminders " +
                "WHERE customer_id = ? ORDER BY created_at DESC, id DESC LIMIT 100",
            arrayOf(customerId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ReminderRecord(
                        id = c.getLong(0),
                        channel = c.getString(1) ?: "",
                        amountPaise = c.getLong(2),
                        message = c.getString(3) ?: "",
                        createdAt = c.getLong(4)
                    )
                )
            }
        }
        return out
    }

    // ------------------------------------------------------------ reports

    /** fromMs inclusive, toMs exclusive. Oldest first. */
    fun getTransactionsInRange(fromMs: Long, toMs: Long): List<TxRow> {
        val out = ArrayList<TxRow>()
        helper.readableDatabase.rawQuery(
            "SELECT t.id, t.tx_time, t.tx_type, t.amount_paise, COALESCE(c.name, s.name, ''), " +
                "(t.supplier_id IS NOT NULL), t.description, t.reference_no, t.payment_method, t.notes " +
                "FROM transactions t " +
                "LEFT JOIN customers c ON c.id = t.customer_id " +
                "LEFT JOIN suppliers s ON s.id = t.supplier_id " +
                "WHERE t.tx_time >= ? AND t.tx_time < ? ORDER BY t.tx_time ASC, t.id ASC",
            arrayOf(fromMs.toString(), toMs.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    TxRow(
                        id = c.getLong(0),
                        txTime = c.getLong(1),
                        txType = c.getString(2) ?: "",
                        amountPaise = c.getLong(3),
                        partyName = c.getString(4) ?: "",
                        partyIsSupplier = c.getInt(5) == 1,
                        description = c.getString(6) ?: "",
                        referenceNo = c.getString(7) ?: "",
                        paymentMethod = c.getString(8) ?: "CASH",
                        notes = c.getString(9) ?: ""
                    )
                )
            }
        }
        return out
    }

    /** Per customer/supplier totals inside the range, plus the balance as of now. */
    fun getPartySummaries(isSupplier: Boolean, fromMs: Long, toMs: Long): List<PartySummary> {
        val fk = fkColumn(isSupplier)
        val sumFor = { type: String ->
            "COALESCE((SELECT SUM(t.amount_paise) FROM transactions t WHERE t." + fk +
                " = p.id AND t.tx_type = '" + type + "' AND t.tx_time >= ? AND t.tx_time < ?), 0)"
        }
        val sql = "SELECT p.name, p.mobile, " + sumFor(TxTypes.increase(isSupplier)) + ", " +
            sumFor(TxTypes.decrease(isSupplier)) + ", " + balanceExpr(isSupplier) +
            " FROM " + table(isSupplier) + " p ORDER BY p.name COLLATE NOCASE ASC"
        val args = arrayOf(fromMs.toString(), toMs.toString(), fromMs.toString(), toMs.toString())
        val out = ArrayList<PartySummary>()
        helper.readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out.add(
                    PartySummary(
                        name = c.getString(0) ?: "",
                        mobile = c.getString(1) ?: "",
                        isSupplier = isSupplier,
                        increasePaise = c.getLong(2),
                        decreasePaise = c.getLong(3),
                        balancePaise = c.getLong(4)
                    )
                )
            }
        }
        return out
    }

    // ------------------------------------------------------------ dashboard

    fun getDashboard(): DashboardStats {
        val db = helper.readableDatabase

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val dayStart = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val dayEnd = cal.timeInMillis
        cal.timeInMillis = dayStart
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val monthStart = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        val monthEnd = cal.timeInMillis

        val receivable = scalarLong(
            db,
            "SELECT COALESCE(SUM(b), 0) FROM (SELECT " + balanceExpr(false) +
                " AS b FROM customers p) WHERE b > 0"
        )
        val payable = scalarLong(
            db,
            "SELECT COALESCE(SUM(b), 0) FROM (SELECT " + balanceExpr(true) +
                " AS b FROM suppliers p) WHERE b > 0"
        )
        val customerCount = scalarLong(db, "SELECT COUNT(*) FROM customers").toInt()
        val supplierCount = scalarLong(db, "SELECT COUNT(*) FROM suppliers").toInt()
        val todayCount = scalarLong(
            db,
            "SELECT COUNT(*) FROM transactions WHERE tx_time >= ? AND tx_time < ?",
            arrayOf(dayStart.toString(), dayEnd.toString())
        ).toInt()
        val monthCollection = scalarLong(
            db,
            "SELECT COALESCE(SUM(amount_paise), 0) FROM transactions WHERE tx_type = 'GOT' AND tx_time >= ? AND tx_time < ?",
            arrayOf(monthStart.toString(), monthEnd.toString())
        )
        val monthPayments = scalarLong(
            db,
            "SELECT COALESCE(SUM(amount_paise), 0) FROM transactions WHERE tx_type = 'PAYMENT' AND tx_time >= ? AND tx_time < ?",
            arrayOf(monthStart.toString(), monthEnd.toString())
        )

        val recent = ArrayList<RecentTx>()
        db.rawQuery(
            "SELECT t.id, COALESCE(c.name, s.name, ''), t.tx_type, t.amount_paise, t.tx_time " +
                "FROM transactions t " +
                "LEFT JOIN customers c ON c.id = t.customer_id " +
                "LEFT JOIN suppliers s ON s.id = t.supplier_id " +
                "ORDER BY t.tx_time DESC, t.id DESC LIMIT 5",
            null
        ).use { c ->
            while (c.moveToNext()) {
                recent.add(
                    RecentTx(
                        id = c.getLong(0),
                        partyName = c.getString(1) ?: "",
                        txType = c.getString(2) ?: "",
                        amountPaise = c.getLong(3),
                        txTime = c.getLong(4)
                    )
                )
            }
        }

        return DashboardStats(
            receivablePaise = receivable,
            payablePaise = payable,
            customerCount = customerCount,
            supplierCount = supplierCount,
            todayCount = todayCount,
            monthCollectionPaise = monthCollection,
            monthPaymentsPaise = monthPayments,
            recent = recent
        )
    }

    private fun scalarLong(db: SQLiteDatabase, sql: String, args: Array<String>? = null): Long =
        db.rawQuery(sql, args).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        }
}
