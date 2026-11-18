package com.hisabbaki.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.hisabbaki.app.Prefs
import com.hisabbaki.app.util.Qty

data class ProductCategory(val id: Long, val name: String)

data class Product(
    val id: Long,
    val categoryId: Long,
    val name: String,
    val sku: String,
    val barcode: String,
    val unit: String,
    val imagePath: String,
    val purchasePricePaise: Long,
    val sellingPricePaise: Long,
    val mrpPaise: Long,
    val openingStockMilli: Long,
    val stockMilli: Long,
    val minStockMilli: Long,
    val allowNegative: Boolean,
    val notes: String
) {
    val isLowStock: Boolean
        get() = minStockMilli > 0 && stockMilli <= minStockMilli

    val isOutOfStock: Boolean
        get() = stockMilli <= 0L
}

data class StockMovement(
    val id: Long,
    val productName: String,
    val moveType: String,
    val qtyMilli: Long,
    val balanceMilli: Long,
    val note: String,
    val txTime: Long
)

data class ProductStats(
    val productCount: Int,
    val lowStockCount: Int,
    val outOfStockCount: Int,
    val stockValuePaise: Long,
    val lowStock: List<Product>
)

class DuplicateSkuException : Exception("Duplicate SKU")

/**
 * Blocking inventory methods. Call only through Db.async.
 * Stock rules:
 * - every quantity change writes a stock_movements row (audit trail + history screen),
 * - sales decrease, purchases, returns and manual adjustments increase/decrease,
 * - negative stock is rejected unless the owner allows it (product flag or global setting).
 */
class InventoryRepo private constructor(
    private val appContext: Context,
    private val helper: DatabaseHelper
) {

    companion object {
        const val MOVE_OPENING = "OPENING"
        const val MOVE_PURCHASE = "PURCHASE"
        const val MOVE_SALE = "SALE"
        const val MOVE_SALE_RETURN = "SALE_RETURN"
        const val MOVE_PURCHASE_RETURN = "PURCHASE_RETURN"
        const val MOVE_ADJUSTMENT = "ADJUSTMENT"

        fun get(context: Context): InventoryRepo =
            InventoryRepo(context.applicationContext, DatabaseHelper.get(context))
    }

    // ------------------------------------------------------------ categories

    fun getCategories(businessId: Int): List<ProductCategory> {
        val out = ArrayList<ProductCategory>()
        helper.readableDatabase.rawQuery(
            "SELECT id, name FROM product_categories WHERE business_id = ? ORDER BY name COLLATE NOCASE",
            arrayOf(businessId.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(ProductCategory(c.getLong(0), c.getString(1) ?: ""))
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
                "product_categories", cv, "id = ? AND business_id = ?",
                arrayOf(id.toString(), businessId.toString())
            )
            return id
        }
        val cv = ContentValues()
        cv.put("business_id", businessId)
        cv.put("name", name.trim())
        cv.put("created_at", System.currentTimeMillis())
        val inserted = db.insertWithOnConflict(
            "product_categories", null, cv, SQLiteDatabase.CONFLICT_IGNORE
        )
        if (inserted != -1L) return inserted
        return db.rawQuery(
            "SELECT id FROM product_categories WHERE business_id = ? AND name = ?",
            arrayOf(businessId.toString(), name.trim())
        ).use { if (it.moveToFirst()) it.getLong(0) else -1L }
    }

    fun deleteCategory(businessId: Int, id: Long) {
        helper.writableDatabase.delete(
            "product_categories", "id = ? AND business_id = ?",
            arrayOf(id.toString(), businessId.toString())
        )
    }

    // ------------------------------------------------------------ products

    private fun readProduct(c: Cursor): Product {
        val catIdx = c.getColumnIndexOrThrow("category_id")
        return Product(
            id = c.getLong(0),
            categoryId = if (c.isNull(catIdx)) 0L else c.getLong(catIdx),
            name = c.getString(2) ?: "",
            sku = c.getString(3) ?: "",
            barcode = c.getString(4) ?: "",
            unit = c.getString(5) ?: "pcs",
            imagePath = c.getString(6) ?: "",
            purchasePricePaise = c.getLong(7),
            sellingPricePaise = c.getLong(8),
            mrpPaise = c.getLong(9),
            openingStockMilli = c.getLong(10),
            stockMilli = c.getLong(11),
            minStockMilli = c.getLong(12),
            allowNegative = c.getInt(13) == 1,
            notes = c.getString(14) ?: ""
        )
    }

    private val productColumns =
        "id, category_id, name, sku, barcode, unit, image_path, purchase_price_paise, " +
            "selling_price_paise, mrp_paise, opening_stock_milli, stock_milli, min_stock_milli, " +
            "allow_negative, notes"

    fun getProducts(
        businessId: Int,
        query: String,
        categoryId: Long,
        onlyLowStock: Boolean
    ): List<Product> {
        val where = StringBuilder("WHERE business_id = ?")
        val args = ArrayList<String>()
        args.add(businessId.toString())
        val q = query.trim()
        if (q.isNotEmpty()) {
            where.append(" AND (name LIKE ? ESCAPE '\\' OR sku LIKE ? ESCAPE '\\' OR barcode LIKE ? ESCAPE '\\')")
            val like = likeOf(q)
            args.add(like)
            args.add(like)
            args.add(like)
        }
        if (categoryId != 0L) {
            where.append(" AND category_id = ?")
            args.add(categoryId.toString())
        }
        if (onlyLowStock) {
            where.append(" AND min_stock_milli > 0 AND stock_milli <= min_stock_milli")
        }
        val list = ArrayList<Product>()
        helper.readableDatabase.rawQuery(
            "SELECT $productColumns FROM products $where ORDER BY name COLLATE NOCASE",
            args.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) list.add(readProduct(c))
        }
        return list
    }

    private fun likeOf(q: String): String =
        "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    fun getProduct(businessId: Int, id: Long): Product? {
        var found: Product? = null
        helper.readableDatabase.rawQuery(
            "SELECT $productColumns FROM products WHERE id = ? AND business_id = ?",
            arrayOf(id.toString(), businessId.toString())
        ).use { c -> if (c.moveToFirst()) found = readProduct(c) }
        return found
    }

    fun findByBarcode(businessId: Int, barcode: String): Product? {
        var found: Product? = null
        helper.readableDatabase.rawQuery(
            "SELECT $productColumns FROM products WHERE business_id = ? AND barcode = ? LIMIT 1",
            arrayOf(businessId.toString(), barcode.trim())
        ).use { c -> if (c.moveToFirst()) found = readProduct(c) }
        return found
    }

    /**
     * Inserts (id == 0) or updates a product.
     * Insert = one OPENING movement when the opening stock is not zero.
     * Update = if the opening stock changed, a compensating ADJUSTMENT keeps every
     * later history entry intact.
     */
    fun saveProduct(
        businessId: Int,
        id: Long,
        categoryId: Long,
        name: String,
        sku: String,
        barcode: String,
        unit: String,
        imagePath: String,
        purchasePricePaise: Long,
        sellingPricePaise: Long,
        mrpPaise: Long,
        openingStockMilli: Long,
        minStockMilli: Long,
        allowNegative: Boolean,
        notes: String
    ): Long {
        require(name.isNotBlank()) { "Product name is required" }
        require(purchasePricePaise >= 0 && sellingPricePaise >= 0 && mrpPaise >= 0) {
            "Prices cannot be negative"
        }
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        val cv = ContentValues()
        cv.put("business_id", businessId)
        if (categoryId != 0L) cv.put("category_id", categoryId) else cv.putNull("category_id")
        cv.put("name", name.trim())
        cv.put("sku", sku.trim())
        cv.put("barcode", barcode.trim())
        cv.put("unit", unit)
        cv.put("image_path", imagePath)
        cv.put("purchase_price_paise", purchasePricePaise)
        cv.put("selling_price_paise", sellingPricePaise)
        cv.put("mrp_paise", mrpPaise)
        cv.put("min_stock_milli", minStockMilli)
        cv.put("allow_negative", if (allowNegative) 1 else 0)
        cv.put("notes", notes)
        cv.put("updated_at", now)

        db.beginTransaction()
        try {
            val resultId: Long
            if (id == 0L) {
                cv.put("opening_stock_milli", openingStockMilli)
                cv.put("stock_milli", openingStockMilli)
                cv.put("created_at", now)
                resultId = db.insertOrThrow("products", null, cv)
                if (openingStockMilli != 0L) {
                    insertMovementLocked(db, businessId, resultId, MOVE_OPENING,
                        openingStockMilli, openingStockMilli, "Opening stock",
                        "products", resultId, now)
                }
            } else {
                val previous = getProduct(businessId, id)
                    ?: throw IllegalStateException("Product not found")
                db.update("products", cv, "id = ? AND business_id = ?",
                    arrayOf(id.toString(), businessId.toString()))
                resultId = id
                val openingDelta = openingStockMilli - previous.openingStockMilli
                if (openingDelta != 0L) {
                    val current = previous.stockMilli + openingDelta
                    if (current >= 0 || previous.allowNegative || Prefs.isAllowNegativeStock(appContext)) {
                        insertMovementLocked(db, businessId, id, MOVE_ADJUSTMENT, openingDelta,
                            current, "Opening stock corrected", "products", id, now)
                        val up = ContentValues()
                        up.put("stock_milli", current)
                        db.update("products", up, "id = ?", arrayOf(id.toString()))
                    } else {
                        throw IllegalStateException(
                            "Opening stock cannot go below zero: current stock " +
                                Qty.format(previous.stockMilli, previous.unit)
                        )
                    }
                }
            }
            db.setTransactionSuccessful()
            return resultId
        } finally {
            db.endTransaction()
        }
    }

    fun deleteProduct(businessId: Int, id: Long) {
        helper.writableDatabase.delete("products", "id = ? AND business_id = ?",
            arrayOf(id.toString(), businessId.toString()))
    }

    // ------------------------------------------------------------ stock operations

    /**
     * Applies a signed quantity change and records the movement in one transaction.
     * Fails without any change when the resulting stock would go below zero and
     * negative stock is not allowed.
     */
    fun applyStock(
        businessId: Int,
        productId: Long,
        deltaMilli: Long,
        moveType: String,
        note: String,
        refTable: String,
        refId: Long,
        txTime: Long
    ) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            applyStockLocked(db, businessId, productId, deltaMilli, moveType, note, refTable, refId, txTime)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Same as applyStock for callers already inside a database transaction (uses raw db). */
    fun applyStockLocked(
        db: SQLiteDatabase,
        businessId: Int,
        productId: Long,
        deltaMilli: Long,
        moveType: String,
        note: String,
        refTable: String,
        refId: Long,
        txTime: Long,
        allowNegativeOverride: Boolean = false
    ) {
        val product = db.rawQuery(
            "SELECT $productColumns FROM products WHERE id = ? AND business_id = ?",
            arrayOf(productId.toString(), businessId.toString())
        ).use { c -> if (c.moveToFirst()) readProduct(c) else null }
            ?: throw IllegalStateException("Product not found")

        val newStock = product.stockMilli + deltaMilli
        val negativeAllowed =
            product.allowNegative || allowNegativeOverride || Prefs.isAllowNegativeStock(appContext)
        if (newStock < 0 && !negativeAllowed) {
            throw IllegalStateException(
                "Not enough stock: available " + Qty.format(product.stockMilli, product.unit)
            )
        }

        val cv = ContentValues()
        cv.put("stock_milli", newStock)
        cv.put("updated_at", System.currentTimeMillis())
        db.update("products", cv, "id = ?", arrayOf(productId.toString()))

        insertMovementLocked(db, businessId, productId, moveType, deltaMilli, newStock,
            note, refTable, refId, txTime)
    }

    private fun insertMovementLocked(
        db: SQLiteDatabase,
        businessId: Int,
        productId: Long,
        moveType: String,
        qtyMilli: Long,
        balanceMilli: Long,
        note: String,
        refTable: String,
        refId: Long,
        txTime: Long
    ) {
        val cv = ContentValues()
        cv.put("business_id", businessId)
        cv.put("product_id", productId)
        cv.put("move_type", moveType)
        cv.put("qty_milli", qtyMilli)
        cv.put("balance_milli", balanceMilli)
        cv.put("note", note)
        cv.put("ref_table", refTable)
        cv.put("ref_id", refId)
        cv.put("tx_time", txTime)
        cv.put("created_at", System.currentTimeMillis())
        db.insert("stock_movements", null, cv)
    }

    fun getMovements(businessId: Int, productId: Long, limit: Int = 200): List<StockMovement> {
        val out = ArrayList<StockMovement>()
        helper.readableDatabase.rawQuery(
            "SELECT m.id, IFNULL(p.name, ''), m.move_type, m.qty_milli, m.balance_milli, " +
                "m.note, m.tx_time FROM stock_movements m " +
                "LEFT JOIN products p ON p.id = m.product_id " +
                "WHERE m.product_id = ? AND m.business_id = ? " +
                "ORDER BY m.tx_time DESC, m.id DESC LIMIT ?",
            arrayOf(productId.toString(), businessId.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    StockMovement(
                        id = c.getLong(0),
                        productName = c.getString(1) ?: "",
                        moveType = c.getString(2) ?: "",
                        qtyMilli = c.getLong(3),
                        balanceMilli = c.getLong(4),
                        note = c.getString(5) ?: "",
                        txTime = c.getLong(6)
                    )
                )
            }
        }
        return out
    }

    fun getProductStats(businessId: Int): ProductStats {
        val db = helper.readableDatabase
        val count = scalarLong(db, "SELECT COUNT(*) FROM products WHERE business_id = ?",
            arrayOf(businessId.toString())).toInt()
        val low = scalarLong(
            db,
            "SELECT COUNT(*) FROM products WHERE business_id = ? AND min_stock_milli > 0 " +
                "AND stock_milli <= min_stock_milli AND stock_milli > 0",
            arrayOf(businessId.toString())
        ).toInt()
        val outOf = scalarLong(
            db,
            "SELECT COUNT(*) FROM products WHERE business_id = ? AND stock_milli <= 0",
            arrayOf(businessId.toString())
        ).toInt()
        val value = scalarLong(
            db,
            "SELECT COALESCE(SUM(stock_milli * purchase_price_paise), 0) FROM products " +
                "WHERE business_id = ? AND stock_milli > 0",
            arrayOf(businessId.toString())
        ) / 1000L
        val lowList = getProducts(businessId, "", 0L, true)
        return ProductStats(count, low, outOf, value, lowList)
    }

    /** Best sellers inside the range: quantity sold per product, biggest first. */
    fun getSalesByProduct(businessId: Int, from: Long, to: Long): List<StockMovement> {
        val out = ArrayList<StockMovement>()
        helper.readableDatabase.rawQuery(
            "SELECT m.id, IFNULL(p.name, ''), 'SALE', SUM(m.qty_milli), 0, '', MAX(m.tx_time) " +
                "FROM stock_movements m LEFT JOIN products p ON p.id = m.product_id " +
                "WHERE m.business_id = ? AND m.move_type = 'SALE' " +
                "AND m.tx_time >= ? AND m.tx_time < ? " +
                "GROUP BY m.product_id ORDER BY SUM(m.qty_milli) DESC LIMIT 200",
            arrayOf(businessId.toString(), from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    StockMovement(
                        id = c.getLong(0),
                        productName = c.getString(1) ?: "",
                        moveType = c.getString(2) ?: "",
                        qtyMilli = c.getLong(3),
                        balanceMilli = 0L,
                        note = "",
                        txTime = c.getLong(6)
                    )
                )
            }
        }
        return out
    }

    private fun scalarLong(db: SQLiteDatabase, sql: String, args: Array<String>? = null): Long =
        db.rawQuery(sql, args).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        }
}
