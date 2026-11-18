package com.hisabbaki.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.hisabbaki.app.util.Dates
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Invoice type behaviour rules. */
object InvTypes {
    const val SALE = "SALE"
    const val PURCHASE = "PURCHASE"
    const val PROFORMA = "PROFORMA"
    const val QUOTATION = "QUOTATION"
    const val ESTIMATE = "ESTIMATE"
    const val CHALLAN = "CHALLAN"
    const val SALE_RETURN = "SALE_RETURN"
    const val PURCHASE_RETURN = "PURCHASE_RETURN"
    const val CREDIT_NOTE = "CREDIT_NOTE"
    const val DEBIT_NOTE = "DEBIT_NOTE"

    fun all(): List<String> = listOf(
        SALE, PURCHASE, PROFORMA, QUOTATION, ESTIMATE,
        CHALLAN, SALE_RETURN, PURCHASE_RETURN, CREDIT_NOTE, DEBIT_NOTE
    )

    /** Default party side of the document type. */
    fun defaultIsSupplier(type: String): Boolean = when (type) {
        PURCHASE, PURCHASE_RETURN, DEBIT_NOTE -> true
        else -> false
    }

    fun prefix(type: String): String = when (type) {
        SALE -> "INV"
        PURCHASE -> "PINV"
        PROFORMA -> "PRO"
        QUOTATION -> "QUO"
        ESTIMATE -> "EST"
        CHALLAN -> "CHL"
        SALE_RETURN -> "SRN"
        PURCHASE_RETURN -> "PRN"
        CREDIT_NOTE -> "CN"
        else -> "DN"
    }

    /** true = the invoice records a customer payable (bill goes into the customer ledger). */
    fun createsBill(type: String): Boolean = type == SALE || type == PURCHASE

    /** true = the invoice value creates a GOT/PAYMENT ledger entry (money moving back). */
    fun createsReturnEntry(type: String): Boolean =
        type == SALE_RETURN || type == PURCHASE_RETURN || type == CREDIT_NOTE || type == DEBIT_NOTE

    /** true = items move stock (SALE out; others in/out as per sign). */
    fun affectsStock(type: String): Boolean = type != PROFORMA && type != QUOTATION &&
        type != ESTIMATE && type != CREDIT_NOTE && type != DEBIT_NOTE && type != CHALLAN

    fun supplier(t: Invoice): Boolean = t.partyIsSupplier
}

data class InvoiceItem(
    val id: Long,
    val productId: Long,
    val name: String,
    val qtyMilli: Long,
    val unit: String,
    val unitPricePaise: Long,
    val discountPaise: Long,
    val taxRateBp: Int,
    val cgstPaise: Long,
    val sgstPaise: Long,
    val igstPaise: Long,
    val lineTotalPaise: Long,
    val costPaise: Long
)

data class InvoicePayment(
    val id: Long,
    val amountPaise: Long,
    val method: String,
    val referenceNo: String,
    val paidAt: Long
)

data class Invoice(
    val id: Long,
    val businessId: Int,
    val invoiceNo: String,
    val invoiceType: String,
    val partyId: Long,
    val partyIsSupplier: Boolean,
    val partyName: String,
    val partyMobile: String,
    val partyAddress: String,
    val invoiceDate: Long,
    val dueDate: Long,
    val paymentTerms: String,
    val isGst: Boolean,
    val placeOfSupply: String,
    val subtotalPaise: Long,
    val discountPaise: Long,
    val taxPaise: Long,
    val totalPaise: Long,
    val paidPaise: Long,
    val status: String,
    val notes: String,
    val txId: Long
)

data class InvoiceDetail(
    val invoice: Invoice,
    val items: List<InvoiceItem>,
    val payments: List<InvoicePayment>
)

data class InvoiceDraft(
    val id: Long,
    val invoiceType: String,
    val partyId: Long,
    val partyIsSupplier: Boolean,
    val partyName: String,
    val partyMobile: String,
    val partyAddress: String,
    val invoiceDate: Long,
    val dueDate: Long,
    val paymentTerms: String,
    val isGst: Boolean,
    val interState: Boolean,
    val placeOfSupply: String,
    val notes: String,
    val items: List<DraftItem>,
    val paidNowPaise: Long,
    val paidMethod: String,
    val paidReference: String,
    val paidAt: Long
)

data class DraftItem(
    val productId: Long,
    val name: String,
    val qtyMilli: Long,
    val unit: String,
    val unitPricePaise: Long,
    val discountPaise: Long,
    val taxRateBp: Int,
    val costPaise: Long
)

data class InvoiceListItem(
    val id: Long,
    val invoiceNo: String,
    val invoiceType: String,
    val partyName: String,
    val invoiceDate: Long,
    val totalPaise: Long,
    val paidPaise: Long,
    val status: String
)

/** All amounts recalculated from the items themselves; stored values are never trusted. */
object InvoiceMath {
    fun lineTaxable(item: DraftItem): Long {
        val gross = item.qtyMilli * item.unitPricePaise / 1000L
        return (gross - item.discountPaise).coerceAtLeast(0L)
    }

    fun lineTax(taxable: Long, rateBp: Int): Long = taxable * rateBp / 10000L

    fun recalc(
        items: List<DraftItem>,
        isGst: Boolean,
        interState: Boolean
    ): Triple<Long, Long, Long> {
        var subtotal = 0L
        var discount = 0L
        var tax = 0L
        for (item in items) {
            val gross = item.qtyMilli * item.unitPricePaise / 1000L
            subtotal += gross
            discount += item.discountPaise
            if (isGst && item.taxRateBp > 0) {
                tax += lineTax(lineTaxable(item), item.taxRateBp)
            }
        }
        return Triple(subtotal, discount.coerceAtMost(subtotal), tax)
    }
}

/** Blocking billing methods. Call only through Db.async. */
class BillingRepo private constructor(
    private val appContext: Context,
    private val helper: DatabaseHelper
) {

    companion object {
        fun get(context: Context): BillingRepo =
            BillingRepo(context.applicationContext, DatabaseHelper.get(context))
    }

    // ------------------------------------------------------------ numbering

    fun nextInvoiceNo(businessId: Int, type: String): String {
        val key = "inv_counter_${businessId}_$type"
        val db = helper.writableDatabase
        val current = db.rawQuery(
            "SELECT value FROM app_settings WHERE key = ?", arrayOf(key)
        ).use { c -> if (c.moveToFirst()) c.getString(0)?.toLongOrNull() ?: 0L else 0L }
        val next = current + 1
        val cv = ContentValues()
        cv.put("value", next.toString())
        db.insertWithOnConflict("app_settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        return String.format(Locale.US, "%s-%04d", InvTypes.prefix(type), next)
    }

    fun peekNextInvoiceNo(businessId: Int, type: String): String {
        val key = "inv_counter_${businessId}_$type"
        val current = helper.readableDatabase.rawQuery(
            "SELECT value FROM app_settings WHERE key = ?", arrayOf(key)
        ).use { c -> if (c.moveToFirst()) c.getString(0)?.toLongOrNull() ?: 0L else 0L }
        return String.format(Locale.US, "%s-%04d", InvTypes.prefix(type), current + 1)
    }

    // ------------------------------------------------------------ queries

    private fun readInvoice(c: Cursor): Invoice {
        val partyIdx = c.getColumnIndex("party_id")
        val txIdx = c.getColumnIndex("tx_id")
        return Invoice(
            id = c.getLong(0),
            businessId = c.getInt(1),
            invoiceNo = c.getString(2) ?: "",
            invoiceType = c.getString(3) ?: InvTypes.SALE,
            partyId = if (partyIdx < 0 || c.isNull(partyIdx)) 0L else c.getLong(partyIdx),
            partyIsSupplier = c.getInt(4) == 1,
            partyName = c.getString(5) ?: "",
            partyMobile = c.getString(6) ?: "",
            partyAddress = c.getString(7) ?: "",
            invoiceDate = c.getLong(8),
            dueDate = c.getLong(9),
            paymentTerms = c.getString(10) ?: "",
            isGst = c.getInt(11) == 1,
            placeOfSupply = c.getString(12) ?: "",
            subtotalPaise = c.getLong(13),
            discountPaise = c.getLong(14),
            taxPaise = c.getLong(15),
            totalPaise = c.getLong(16),
            paidPaise = c.getLong(17),
            status = c.getString(18) ?: "UNPAID",
            notes = c.getString(19) ?: "",
            txId = if (txIdx < 0 || c.isNull(txIdx)) 0L else c.getLong(txIdx)
        )
    }

    private val invoiceColumns =
        "id, business_id, invoice_no, invoice_type, party_id, party_is_supplier, party_name, " +
            "party_mobile, party_address, invoice_date, due_date, payment_terms, is_gst, " +
            "place_of_supply, subtotal_paise, discount_paise, tax_paise, total_paise, paid_paise, " +
            "status, notes, tx_id"

    fun listInvoices(
        businessId: Int,
        query: String,
        type: String,
        from: Long?,
        to: Long?,
        onlyUnpaid: Boolean
    ): List<InvoiceListItem> {
        val where = StringBuilder("WHERE i.business_id = ?")
        val args = ArrayList<String>()
        args.add(businessId.toString())
        val q = query.trim()
        if (q.isNotEmpty()) {
            where.append(" AND (i.invoice_no LIKE ? OR i.party_name LIKE ?)")
            val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            args.add(like)
            args.add(like)
        }
        if (type.isNotEmpty()) {
            where.append(" AND i.invoice_type = ?")
            args.add(type)
        }
        if (from != null) {
            where.append(" AND i.invoice_date >= ?")
            args.add(from.toString())
        }
        if (to != null) {
            where.append(" AND i.invoice_date < ?")
            args.add(to.toString())
        }
        if (onlyUnpaid) {
            where.append(" AND i.status != 'PAID'")
        }
        val out = ArrayList<InvoiceListItem>()
        helper.readableDatabase.rawQuery(
            "SELECT " + invoiceColumns.split(", ").joinToString(", ") { "i.$it" } +
                " FROM invoices i $where ORDER BY i.invoice_date DESC, i.id DESC LIMIT 300",
            args.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                val inv = readInvoice(c)
                out.add(
                    InvoiceListItem(
                        id = inv.id,
                        invoiceNo = inv.invoiceNo,
                        invoiceType = inv.invoiceType,
                        partyName = inv.partyName,
                        invoiceDate = inv.invoiceDate,
                        totalPaise = inv.totalPaise,
                        paidPaise = inv.paidPaise,
                        status = inv.status
                    )
                )
            }
        }
        return out
    }

    fun getInvoice(businessId: Int, id: Long): InvoiceDetail? {
        val invoice = helper.readableDatabase.rawQuery(
            "SELECT " + invoiceColumns.split(", ").joinToString(", ") { "i.$it" } +
                " FROM invoices i WHERE i.id = ? AND i.business_id = ?",
            arrayOf(id.toString(), businessId.toString())
        ).use { c -> if (c.moveToFirst()) readInvoice(c) else null } ?: return null

        val items = ArrayList<InvoiceItem>()
        helper.readableDatabase.rawQuery(
            "SELECT id, IFNULL(product_id, 0), name, qty_milli, unit, unit_price_paise, " +
                "discount_paise, tax_rate_bp, cgst_paise, sgst_paise, igst_paise, line_total_paise, cost_paise " +
                "FROM invoice_items WHERE invoice_id = ? ORDER BY id",
            arrayOf(id.toString())
        ).use { c ->
            while (c.moveToNext()) {
                items.add(
                    InvoiceItem(
                        id = c.getLong(0),
                        productId = c.getLong(1),
                        name = c.getString(2) ?: "",
                        qtyMilli = c.getLong(3),
                        unit = c.getString(4) ?: "pcs",
                        unitPricePaise = c.getLong(5),
                        discountPaise = c.getLong(6),
                        taxRateBp = c.getInt(7),
                        cgstPaise = c.getLong(8),
                        sgstPaise = c.getLong(9),
                        igstPaise = c.getLong(10),
                        lineTotalPaise = c.getLong(11),
                        costPaise = c.getLong(12)
                    )
                )
            }
        }

        val payments = ArrayList<InvoicePayment>()
        helper.readableDatabase.rawQuery(
            "SELECT id, amount_paise, method, reference_no, paid_at FROM invoice_payments " +
                "WHERE invoice_id = ? ORDER BY paid_at DESC, id DESC",
            arrayOf(id.toString())
        ).use { c ->
            while (c.moveToNext()) {
                payments.add(
                    InvoicePayment(
                        id = c.getLong(0),
                        amountPaise = c.getLong(1),
                        method = c.getString(2) ?: "CASH",
                        referenceNo = c.getString(3) ?: "",
                        paidAt = c.getLong(4)
                    )
                )
            }
        }
        return InvoiceDetail(invoice, items, payments)
    }

    fun getInvoiceByNo(businessId: Int, invoiceNo: String): Invoice? {
        var found: Invoice? = null
        helper.readableDatabase.rawQuery(
            "SELECT " + invoiceColumns.split(", ").joinToString(", ") { "i.$it" } +
                " FROM invoices i WHERE i.business_id = ? AND i.invoice_no = ?",
            arrayOf(businessId.toString(), invoiceNo)
        ).use { c -> if (c.moveToFirst()) found = readInvoice(c) }
        return found
    }

    // ------------------------------------------------------------ save

    /**
     * One transaction-safe save:
     * 1. recompute all money from the draft items (subtotal / discount / CGST / SGST / IGST),
     * 2. write the invoice + items,
     * 3. apply stock movements for stock-affecting types (old items reverted first),
     * 4. keep the party ledger in step (bill entry + payment entries),
     * 5. record the payment made at save time.
     */
    fun saveInvoice(businessId: Int, draft: InvoiceDraft): Long {
        require(draft.items.isNotEmpty()) { "An invoice needs at least one item" }
        require(draft.items.all { it.qtyMilli > 0 }) { "Quantities must be positive" }
        require(draft.items.all { it.unitPricePaise >= 0 && it.discountPaise >= 0 }) {
            "Prices cannot be negative"
        }

        val affectsStock = InvTypes.affectsStock(draft.invoiceType)
        val stockSign = when (draft.invoiceType) {
            InvTypes.SALE, InvTypes.PURCHASE_RETURN, InvTypes.CHALLAN -> -1L
            InvTypes.PURCHASE, InvTypes.SALE_RETURN -> 1L
            else -> 0L
        }

        val (subtotal, discount, tax) = InvoiceMath.recalc(draft.items, draft.isGst, draft.interState)
        val total = subtotal - discount + tax
        require(total > 0) { "Invoice total must be positive" }

        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        val inventory = InventoryRepo.get(appContext)
        try {
            // ---- load previous items (to revert stock on edit)
            var previousItems: List<InvoiceItem> = emptyList()
            var previous: Invoice? = null
            if (draft.id != 0L) {
                previous = getInvoice(businessId, draft.id)?.invoice
                    ?: throw IllegalStateException("Invoice not found")
                previousItems = getInvoice(businessId, draft.id)!!.items
            }

            // ---- stock revert for edits
            if (affectsStock && previousItems.isNotEmpty()) {
                for (item in previousItems) {
                    if (item.productId != 0L && stockSign != 0L) {
                        inventory.applyStockLocked(
                            db, businessId, item.productId, -stockSign * item.qtyMilli,
                            if (stockSign < 0) InventoryRepo.MOVE_SALE_RETURN else InventoryRepo.MOVE_PURCHASE_RETURN,
                            "Reverted (edit): " + previous!!.invoiceNo,
                            "invoices", previous.id, now, true
                        )
                    }
                }
            }

            // ---- invoice row
            val cv = ContentValues()
            cv.put("business_id", businessId)
            cv.put("invoice_no", previous?.invoiceNo ?: nextInvoiceNo(businessId, draft.invoiceType))
            cv.put("invoice_type", draft.invoiceType)
            if (draft.partyId != 0L) cv.put("party_id", draft.partyId) else cv.putNull("party_id")
            cv.put("party_is_supplier", if (draft.partyIsSupplier) 1 else 0)
            cv.put("party_name", draft.partyName)
            cv.put("party_mobile", draft.partyMobile)
            cv.put("party_address", draft.partyAddress)
            cv.put("invoice_date", draft.invoiceDate)
            cv.put("due_date", draft.dueDate)
            cv.put("payment_terms", draft.paymentTerms)
            cv.put("is_gst", if (draft.isGst) 1 else 0)
            cv.put("place_of_supply", draft.placeOfSupply)
            cv.put("subtotal_paise", subtotal)
            cv.put("discount_paise", discount)
            cv.put("tax_paise", tax)
            cv.put("total_paise", total)
            cv.put("notes", draft.notes)
            cv.put("updated_at", now)

            val invoiceId: Long
            if (previous != null) {
                invoiceId = previous.id
                db.update("invoices", cv, "id = ?", arrayOf(invoiceId.toString()))
                db.delete("invoice_items", "invoice_id = ?", arrayOf(invoiceId.toString()))
            } else {
                cv.put("created_at", now)
                invoiceId = db.insertOrThrow("invoices", null, cv)
            }

            // ---- items
            for (item in draft.items) {
                val taxable = InvoiceMath.lineTaxable(item)
                val lineTax = if (draft.isGst && item.taxRateBp > 0)
                    InvoiceMath.lineTax(taxable, item.taxRateBp) else 0L
                val cgst = if (draft.isGst && !draft.interState) lineTax / 2 else 0L
                val sgst = if (draft.isGst && !draft.interState) lineTax - cgst else 0L
                val igst = if (draft.isGst && draft.interState) lineTax else 0L
                val icv = ContentValues()
                icv.put("invoice_id", invoiceId)
                if (item.productId != 0L) icv.put("product_id", item.productId)
                else icv.putNull("product_id")
                icv.put("name", item.name)
                icv.put("qty_milli", item.qtyMilli)
                icv.put("unit", item.unit)
                icv.put("unit_price_paise", item.unitPricePaise)
                icv.put("discount_paise", item.discountPaise)
                icv.put("tax_rate_bp", item.taxRateBp)
                icv.put("cgst_paise", cgst)
                icv.put("sgst_paise", sgst)
                icv.put("igst_paise", igst)
                icv.put("line_total_paise", taxable + lineTax)
                icv.put("cost_paise", item.costPaise)
                db.insert("invoice_items", null, icv)

                // ---- stock effect
                if (affectsStock && item.productId != 0L && stockSign != 0L) {
                    inventory.applyStockLocked(
                        db, businessId, item.productId, stockSign * item.qtyMilli,
                        moveTypeFor(draft.invoiceType), draft.invoiceType + " " + cvEntry(db, invoiceId),
                        "invoices", invoiceId, draft.invoiceDate, true
                    )
                }
            }

            // ---- ledger bill entry (SALE / PURCHASE only, needs a party)
            val billTxId = syncBillEntry(db, businessId, invoiceId, previous, draft, total, now)

            // ---- payment made now
            if (draft.paidNowPaise > 0) {
                addPaymentLocked(db, businessId, invoiceId, draft.paidNowPaise,
                    draft.paidMethod, draft.paidReference, draft.paidAt, now)
            }

            refreshTotals(db, invoiceId)

            db.setTransactionSuccessful()
            return invoiceId
        } finally {
            db.endTransaction()
        }
    }

    private fun moveTypeFor(type: String): String = when (type) {
        InvTypes.SALE -> InventoryRepo.MOVE_SALE
        InvTypes.PURCHASE -> InventoryRepo.MOVE_PURCHASE
        InvTypes.SALE_RETURN -> InventoryRepo.MOVE_SALE_RETURN
        InvTypes.PURCHASE_RETURN -> InventoryRepo.MOVE_PURCHASE_RETURN
        InvTypes.CHALLAN -> InventoryRepo.MOVE_SALE
        else -> InventoryRepo.MOVE_ADJUSTMENT
    }

    /** Creates/updates the ledger bill entry for SALE/PURCHASE invoices. Returns the tx id (0 = none). */
    private fun syncBillEntry(
        db: SQLiteDatabase,
        businessId: Int,
        invoiceId: Long,
        previous: Invoice?,
        draft: InvoiceDraft,
        total: Long,
        now: Long
    ): Long {
        if (!InvTypes.createsBill(draft.invoiceType) || draft.partyId == 0L) {
            // If edited to no longer have a party, drop the old bill entry.
            val oldTx = previous?.txId ?: 0L
            if (oldTx != 0L) db.delete("transactions", "id = ?", arrayOf(oldTx.toString()))
            return 0L
        }
        val isSupplier = draft.partyIsSupplier
        val txType = TxTypes.increase(isSupplier)
        val oldTxId = previous?.txId ?: 0L

        val cv = ContentValues()
        if (isSupplier) {
            cv.put("supplier_id", draft.partyId)
            cv.putNull("customer_id")
        } else {
            cv.put("customer_id", draft.partyId)
            cv.putNull("supplier_id")
        }
        cv.put("tx_type", txType)
        cv.put("amount_paise", total)
        cv.put("tx_time", draft.invoiceDate)
        cv.put("description", "Invoice " + (previous?.invoiceNo ?: peekNo(db, businessId, draft)))
        cv.put("reference_no", previous?.invoiceNo ?: "")
        cv.put("payment_method", "CASH")
        cv.put("notes", draft.notes)
        cv.put("updated_at", now)
        cv.put("business_id", businessId)
        cv.put("source_invoice_id", invoiceId)

        return if (oldTxId != 0L) {
            val rows = db.update("transactions", cv, "id = ?", arrayOf(oldTxId.toString()))
            if (rows == 0) {
                cv.put("client_uuid", UUID.randomUUID().toString())
                cv.put("created_at", now)
                db.insertOrThrow("transactions", null, cv)
            } else {
                oldTxId
            }
        } else {
            cv.put("client_uuid", UUID.randomUUID().toString())
            cv.put("created_at", now)
            db.insertOrThrow("transactions", null, cv)
        }
    }

    private fun peekNo(db: SQLiteDatabase, businessId: Int, draft: InvoiceDraft): String {
        val key = "inv_counter_${businessId}_${draft.invoiceType}"
        val current = db.rawQuery(
            "SELECT value FROM app_settings WHERE key = ?", arrayOf(key)
        ).use { c -> if (c.moveToFirst()) c.getString(0)?.toLongOrNull() ?: 0L else 0L }
        return String.format(Locale.US, "%s-%04d", InvTypes.prefix(draft.invoiceType), current + 1)
    }

    private fun cvEntry(db: SQLiteDatabase, invoiceId: Long): String =
        db.rawQuery("SELECT invoice_no FROM invoices WHERE id = ?", arrayOf(invoiceId.toString()))
            .use { c -> if (c.moveToFirst()) c.getString(0) ?: "" else "" }

    /**
     * Records one payment against the invoice and mirrors it into the party ledger
     * (GOT / PAYMENT), so cash flow and ledgers always agree.
     * Must be called inside a transaction.
     */
    private fun addPaymentLocked(
        db: SQLiteDatabase,
        businessId: Int,
        invoiceId: Long,
        amount: Long,
        method: String,
        reference: String,
        paidAt: Long,
        createdAt: Long
    ) {
        require(amount > 0) { "Payment must be positive" }
        val inv = db.rawQuery(
            "SELECT invoice_no, party_id, party_is_supplier FROM invoices WHERE id = ?",
            arrayOf(invoiceId.toString())
        ).use { c ->
            if (c.moveToFirst()) Triple(c.getString(0) ?: "", c.getLong(1), c.getInt(2) == 1)
            else throw IllegalStateException("Invoice not found")
        }
        val (invoiceNo, partyId, isSupplier) = inv

        val cv = ContentValues()
        cv.put("invoice_id", invoiceId)
        cv.put("amount_paise", amount)
        cv.put("method", method)
        cv.put("reference_no", reference)
        cv.put("paid_at", paidAt)
        cv.put("created_at", createdAt)

        if (partyId != 0L) {
            val txType = TxTypes.decrease(isSupplier)
            val tcv = ContentValues()
            if (isSupplier) {
                tcv.put("supplier_id", partyId)
                tcv.putNull("customer_id")
            } else {
                tcv.put("customer_id", partyId)
                tcv.putNull("supplier_id")
            }
            tcv.put("tx_type", txType)
            tcv.put("amount_paise", amount)
            tcv.put("tx_time", paidAt)
            tcv.put("description", "Payment for $invoiceNo")
            tcv.put("reference_no", reference.ifBlank { invoiceNo })
            tcv.put("payment_method", method)
            tcv.put("notes", "")
            tcv.put("client_uuid", UUID.randomUUID().toString())
            tcv.put("created_at", createdAt)
            tcv.put("updated_at", createdAt)
            tcv.put("business_id", businessId)
            tcv.put("source_invoice_id", invoiceId)
            val txId = db.insertOrThrow("transactions", null, tcv)
            cv.put("tx_id", txId)
        }
        db.insert("invoice_payments", null, cv)
    }

    fun addInvoicePayment(businessId: Int, invoiceId: Long, amount: Long, method: String, reference: String, paidAt: Long) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            addPaymentLocked(db, businessId, invoiceId, amount, method, reference, paidAt,
                System.currentTimeMillis())
            refreshTotals(db, invoiceId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Removes one payment and its mirrored ledger entry, then refreshes the totals. */
    fun deleteInvoicePayment(businessId: Int, invoiceId: Long, paymentId: Long) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val txId = db.rawQuery(
                "SELECT tx_id FROM invoice_payments WHERE id = ? AND invoice_id = ?",
                arrayOf(paymentId.toString(), invoiceId.toString())
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }
            db.delete("invoice_payments", "id = ? AND invoice_id = ?",
                arrayOf(paymentId.toString(), invoiceId.toString()))
            if (txId != 0L) {
                db.delete("transactions", "id = ? AND source_invoice_id = ?",
                    arrayOf(txId.toString(), invoiceId.toString()))
            }
            refreshTotals(db, invoiceId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** paid = sum(payments); status derived. Must be inside a transaction. */
    private fun refreshTotals(db: SQLiteDatabase, invoiceId: Long) {
        val paid = db.rawQuery(
            "SELECT COALESCE(SUM(amount_paise), 0) FROM invoice_payments WHERE invoice_id = ?",
            arrayOf(invoiceId.toString())
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        val total = db.rawQuery(
            "SELECT total_paise FROM invoices WHERE id = ?", arrayOf(invoiceId.toString())
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        val status = when {
            paid <= 0L -> "UNPAID"
            paid >= total -> "PAID"
            else -> "PARTIAL"
        }
        val cv = ContentValues()
        cv.put("paid_paise", paid)
        cv.put("status", status)
        db.update("invoices", cv, "id = ?", arrayOf(invoiceId.toString()))
    }

    /**
     * Deletes a bill/return ledger entry created for this invoice. Real money payments
     * (GOT/PAYMENT entries made by invoice payments) are kept because they are real
     * cash movements; deleting those is done through the payment list on the invoice.
     */
    fun deleteInvoice(businessId: Int, id: Long): Boolean {
        val db = helper.writableDatabase
        db.beginTransaction()
        val inventory = InventoryRepo.get(appContext)
        try {
            val inv = getInvoice(businessId, id) ?: return false.also { db.endTransaction() }
            val affectsStock = InvTypes.affectsStock(inv.invoice.invoiceType)
            val stockSign = when (inv.invoice.invoiceType) {
                InvTypes.SALE, InvTypes.PURCHASE_RETURN, InvTypes.CHALLAN -> -1L
                InvTypes.PURCHASE, InvTypes.SALE_RETURN -> 1L
                else -> 0L
            }
            if (affectsStock && stockSign != 0L) {
                for (item in inv.items) {
                    if (item.productId != 0L) {
                        inventory.applyStockLocked(
                            db, businessId, item.productId, -stockSign * item.qtyMilli,
                            if (stockSign < 0) InventoryRepo.MOVE_SALE_RETURN
                            else InventoryRepo.MOVE_PURCHASE_RETURN,
                            "Invoice deleted: " + inv.invoice.invoiceNo,
                            "invoices", inv.invoice.id, System.currentTimeMillis(), true
                        )
                    }
                }
            }
            val billTx = inv.invoice.txId
            db.delete("invoices", "id = ?", arrayOf(id.toString()))
            if (billTx != 0L) {
                db.delete("transactions", "id = ?", arrayOf(billTx.toString()))
            }
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    // ------------------------------------------------------------ cash-flow aware stats

    /**
     * Sales = SALE invoices (billed value), Purchases = PURCHASE invoices.
     * Cash in = GOT transactions (incl. invoice payments), Cash out = PAYMENT + expenses.
     */
    fun getBusinessTotals(businessId: Int, from: Long, to: Long): BusinessTotals {
        val db = helper.readableDatabase
        fun scalar(sql: String, args: Array<String>): Long =
            db.rawQuery(sql, args).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }

        val b = businessId.toString()
        val f = from.toString()
        val t = to.toString()
        val sales = scalar(
            "SELECT COALESCE(SUM(total_paise), 0) FROM invoices WHERE business_id = ? " +
                "AND invoice_type = 'SALE' AND invoice_date >= ? AND invoice_date < ?",
            arrayOf(b, f, t)
        )
        val purchases = scalar(
            "SELECT COALESCE(SUM(total_paise), 0) FROM invoices WHERE business_id = ? " +
                "AND invoice_type = 'PURCHASE' AND invoice_date >= ? AND invoice_date < ?",
            arrayOf(b, f, t)
        )
        val salesReturns = scalar(
            "SELECT COALESCE(SUM(total_paise), 0) FROM invoices WHERE business_id = ? " +
                "AND invoice_type = 'SALE_RETURN' AND invoice_date >= ? AND invoice_date < ?",
            arrayOf(b, f, t)
        )
        val purchaseReturns = scalar(
            "SELECT COALESCE(SUM(total_paise), 0) FROM invoices WHERE business_id = ? " +
                "AND invoice_type = 'PURCHASE_RETURN' AND invoice_date >= ? AND invoice_date < ?",
            arrayOf(b, f, t)
        )
        val expenses = scalar(
            "SELECT COALESCE(SUM(amount_paise), 0) FROM expenses WHERE business_id = ? " +
                "AND expense_date >= ? AND expense_date < ?",
            arrayOf(b, f, t)
        )
        val cashIn = scalar(
            "SELECT COALESCE(SUM(amount_paise), 0) FROM transactions WHERE business_id = ? " +
                "AND tx_type = 'GOT' AND tx_time >= ? AND tx_time < ?",
            arrayOf(b, f, t)
        )
        val cashOut = scalar(
            "SELECT COALESCE(SUM(amount_paise), 0) FROM transactions WHERE business_id = ? " +
                "AND tx_type = 'PAYMENT' AND tx_time >= ? AND tx_time < ?",
            arrayOf(b, f, t)
        )
        // COGS: purchase price of the items on sale invoices
        val cogs = scalar(
            "SELECT COALESCE(SUM(ii.qty_milli * ii.cost_paise), 0) / 1000 FROM invoice_items ii " +
                "JOIN invoices i ON i.id = ii.invoice_id WHERE i.business_id = ? " +
                "AND i.invoice_type = 'SALE' AND i.invoice_date >= ? AND i.invoice_date < ?",
            arrayOf(b, f, t)
        )
        val receivable = scalar(
            "SELECT COALESCE(SUM(total_paise - paid_paise), 0) FROM invoices " +
                "WHERE business_id = ? AND invoice_type = 'SALE' AND status != 'PAID'",
            arrayOf(b)
        )
        val payable = scalar(
            "SELECT COALESCE(SUM(total_paise - paid_paise), 0) FROM invoices " +
                "WHERE business_id = ? AND invoice_type = 'PURCHASE' AND status != 'PAID'",
            arrayOf(b)
        )
        return BusinessTotals(
            salesPaise = sales,
            purchasesPaise = purchases,
            salesReturnsPaise = salesReturns,
            purchaseReturnsPaise = purchaseReturns,
            expensesPaise = expenses,
            cashInPaise = cashIn,
            cashOutPaise = cashOut,
            cogsPaise = cogs,
            receivablePaise = receivable,
            payablePaise = payable
        )
    }
}

data class BusinessTotals(
    val salesPaise: Long,
    val purchasesPaise: Long,
    val salesReturnsPaise: Long,
    val purchaseReturnsPaise: Long,
    val expensesPaise: Long,
    val cashInPaise: Long,
    val cashOutPaise: Long,
    val cogsPaise: Long,
    val receivablePaise: Long,
    val payablePaise: Long
) {
    /** Revenue - cost of goods sold - operating expenses (sales returns subtracted). */
    val netProfitPaise: Long
        get() = salesPaise - salesReturnsPaise - cogsPaise - expensesPaise
}
