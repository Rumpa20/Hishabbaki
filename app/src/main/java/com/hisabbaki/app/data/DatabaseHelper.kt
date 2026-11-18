package com.hisabbaki.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DatabaseHelper private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        for (sql in CREATE_STATEMENTS) db.execSQL(sql)
        seedDefaultData(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // One step per version. Never drop user data.
        if (oldVersion < 2) upgradeTo2(db)
    }

    /**
     * v1 -> v2:
     * - businesses table takes over the single business_profile row (row 1 is copied, never lost).
     * - every existing record is attached to business 1 via business_id (users never see a change).
     * - adds invoices, invoice items, invoice payments, products, categories, stock movements,
     *   expenses, expense categories, cash accounts, cash transfers, staff, attendance,
     *   audit log and the recycle bin.
     * - transactions gain attachment_path and source_invoice_id (invoice-generated entries).
     */
    private fun upgradeTo2(db: SQLiteDatabase) {
        for (sql in V2_CREATE_STATEMENTS) db.execSQL(sql)

        val now = System.currentTimeMillis()
        db.execSQL(
            """INSERT INTO businesses
                (id, name, owner_name, mobile, address, gstin, logo_path, upi_id, currency, created_at, updated_at)
                SELECT 1, business_name, owner_name, mobile, address, gstin, logo_path, '', currency,
                    $now, $now
                FROM business_profile"""
        )
        db.execSQL(
            "INSERT INTO businesses (name, created_at, updated_at) SELECT '', $now, $now " +
                "WHERE NOT EXISTS (SELECT 1 FROM businesses)"
        )

        db.execSQL("ALTER TABLE customers ADD COLUMN business_id INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE suppliers ADD COLUMN business_id INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE transactions ADD COLUMN business_id INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE reminders ADD COLUMN business_id INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE transactions ADD COLUMN attachment_path TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE transactions ADD COLUMN account_id INTEGER REFERENCES cash_accounts(id) ON DELETE SET NULL")
        db.execSQL("ALTER TABLE transactions ADD COLUMN source_invoice_id INTEGER REFERENCES invoices(id) ON DELETE SET NULL")

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_customers_business ON customers(business_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_suppliers_business ON suppliers(business_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tx_business ON transactions(business_id, tx_time)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_reminders_business ON reminders(business_id)")

        companionSeedExpenseCategories(db, 1)
    }

    /** Fresh install: the empty default business and the standard expense categories. */
    private fun seedDefaultData(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        db.execSQL("INSERT INTO businesses (name, created_at, updated_at) VALUES ('', $now, $now)")
        companionSeedExpenseCategories(db, 1)
        db.execSQL("INSERT OR IGNORE INTO app_settings (key, value) VALUES ('schema_created', '2')")
    }

    private fun seedExpenseCategoriesFor(businessId: Int) {
        val db = writableDatabase
        val existing = db.rawQuery(
            "SELECT COUNT(*) FROM expense_categories WHERE business_id = ?",
            arrayOf(businessId.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        if (existing == 0) {
            companionSeedExpenseCategories(db, businessId)
        }
    }

    companion object {
        const val DB_NAME = "hisabbaki.db"
        const val DB_VERSION = 2

        val DEFAULT_EXPENSE_CATEGORIES = listOf(
            "Rent", "Electricity", "Salary", "Transport", "Food", "Maintenance", "Other"
        )
        val UNITS = listOf("pcs", "kg", "g", "L", "mL", "m", "box", "pack", "dozen", "bag", "other")

        val INVOICE_TYPES = listOf(
            "SALE", "PURCHASE", "PROFORMA", "QUOTATION", "ESTIMATE",
            "CHALLAN", "SALE_RETURN", "PURCHASE_RETURN", "CREDIT_NOTE", "DEBIT_NOTE"
        )

        @Volatile
        private var instance: DatabaseHelper? = null

        fun get(context: Context): DatabaseHelper =
            instance ?: synchronized(this) {
                instance ?: DatabaseHelper(context.applicationContext).also { instance = it }
            }

        fun dbFile(context: Context): java.io.File =
            context.applicationContext.getDatabasePath(DB_NAME)

        /** Closes the database and forgets the helper so the next get() opens the file again. */
        fun closeAndReset() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }

        /** Seeds the standard categories for a business created later than the schema. */
        fun seedExpenseCategoriesFor(context: Context, businessId: Int) {
            val helper = get(context)
            helper.seedExpenseCategoriesFor(businessId)
        }

        private fun companionSeedExpenseCategories(db: SQLiteDatabase, businessId: Int) {
            val existing = db.rawQuery(
                "SELECT COUNT(*) FROM expense_categories WHERE business_id = ?",
                arrayOf(businessId.toString())
            ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            if (existing > 0) return
            val cv = ContentValues()
            for (name in DEFAULT_EXPENSE_CATEGORIES) {
                cv.clear()
                cv.put("business_id", businessId)
                cv.put("name", name)
                cv.put("created_at", System.currentTimeMillis())
                db.insert("expense_categories", null, cv)
            }
        }

        private val CREATE_STATEMENTS = listOf(
            """CREATE TABLE businesses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL DEFAULT '',
                owner_name TEXT NOT NULL DEFAULT '',
                mobile TEXT NOT NULL DEFAULT '',
                address TEXT NOT NULL DEFAULT '',
                gstin TEXT NOT NULL DEFAULT '',
                logo_path TEXT NOT NULL DEFAULT '',
                upi_id TEXT NOT NULL DEFAULT '',
                currency TEXT NOT NULL DEFAULT 'INR',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""",
            """CREATE TABLE business_profile (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                business_name TEXT NOT NULL DEFAULT '',
                owner_name TEXT NOT NULL DEFAULT '',
                mobile TEXT NOT NULL DEFAULT '',
                address TEXT NOT NULL DEFAULT '',
                gstin TEXT NOT NULL DEFAULT '',
                logo_path TEXT NOT NULL DEFAULT '',
                currency TEXT NOT NULL DEFAULT 'INR',
                language TEXT NOT NULL DEFAULT 'en'
            )""",
            """CREATE TABLE customers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                mobile TEXT NOT NULL DEFAULT '',
                address TEXT NOT NULL DEFAULT '',
                opening_balance_paise INTEGER NOT NULL DEFAULT 0,
                notes TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                business_id INTEGER NOT NULL DEFAULT 1 REFERENCES businesses(id)
            )""",
            """CREATE TABLE suppliers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                mobile TEXT NOT NULL DEFAULT '',
                address TEXT NOT NULL DEFAULT '',
                opening_balance_paise INTEGER NOT NULL DEFAULT 0,
                notes TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                business_id INTEGER NOT NULL DEFAULT 1 REFERENCES businesses(id)
            )""",
            """CREATE TABLE transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                customer_id INTEGER REFERENCES customers(id) ON DELETE CASCADE,
                supplier_id INTEGER REFERENCES suppliers(id) ON DELETE CASCADE,
                tx_type TEXT NOT NULL CHECK (tx_type IN ('GAVE','GOT','PURCHASE','PAYMENT')),
                amount_paise INTEGER NOT NULL CHECK (amount_paise > 0),
                tx_time INTEGER NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                reference_no TEXT NOT NULL DEFAULT '',
                payment_method TEXT NOT NULL DEFAULT 'CASH' CHECK (payment_method IN ('CASH','UPI','BANK','OTHER')),
                notes TEXT NOT NULL DEFAULT '',
                client_uuid TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                business_id INTEGER NOT NULL DEFAULT 1 REFERENCES businesses(id),
                attachment_path TEXT NOT NULL DEFAULT '',
                account_id INTEGER REFERENCES cash_accounts(id) ON DELETE SET NULL,
                source_invoice_id INTEGER REFERENCES invoices(id) ON DELETE SET NULL,
                CHECK (
                    (customer_id IS NOT NULL AND supplier_id IS NULL AND tx_type IN ('GAVE','GOT'))
                    OR
                    (supplier_id IS NOT NULL AND customer_id IS NULL AND tx_type IN ('PURCHASE','PAYMENT'))
                )
            )""",
            """CREATE TABLE reminders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                customer_id INTEGER NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
                amount_paise INTEGER NOT NULL,
                channel TEXT NOT NULL DEFAULT 'WHATSAPP',
                message TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                business_id INTEGER NOT NULL DEFAULT 1 REFERENCES businesses(id)
            )""",
            """CREATE TABLE app_settings (
                key TEXT PRIMARY KEY NOT NULL,
                value TEXT NOT NULL DEFAULT ''
            )""",
            """CREATE TABLE product_categories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                business_id INTEGER NOT NULL REFERENCES businesses(id),
                name TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                UNIQUE (business_id, name)
            )""",
            """CREATE TABLE products (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                business_id INTEGER NOT NULL REFERENCES businesses(id),
                category_id INTEGER REFERENCES product_categories(id) ON DELETE SET NULL,
                name TEXT NOT NULL,
                sku TEXT NOT NULL DEFAULT '',
                barcode TEXT NOT NULL DEFAULT '',
                unit TEXT NOT NULL DEFAULT 'pcs',
                image_path TEXT NOT NULL DEFAULT '',
                purchase_price_paise INTEGER NOT NULL DEFAULT 0,
                selling_price_paise INTEGER NOT NULL DEFAULT 0,
                mrp_paise INTEGER NOT NULL DEFAULT 0,
                opening_stock_milli INTEGER NOT NULL DEFAULT 0,
                stock_milli INTEGER NOT NULL DEFAULT 0,
                min_stock_milli INTEGER NOT NULL DEFAULT 0,
                allow_negative INTEGER NOT NULL DEFAULT 0,
                notes TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                UNIQUE (business_id, sku)
            )""",
            """CREATE TABLE stock_movements (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                business_id INTEGER NOT NULL REFERENCES businesses(id),
                product_id INTEGER NOT NULL REFERENCES products(id) ON DELETE CASCADE,
                move_type TEXT NOT NULL CHECK (move_type IN
                    ('OPENING','PURCHASE','SALE','SALE_RETURN','PURCHASE_RETURN','ADJUSTMENT')),
                qty_milli INTEGER NOT NULL,
                balance_milli INTEGER NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                ref_table TEXT NOT NULL DEFAULT '',
                ref_id INTEGER NOT NULL DEFAULT 0,
                tx_time INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )""",
            """CREATE TABLE invoices (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                business_id INTEGER NOT NULL REFERENCES businesses(id),
                invoice_no TEXT NOT NULL,
                invoice_type TEXT NOT NULL CHECK (invoice_type IN
                    ('SALE','PURCHASE','PROFORMA','QUOTATION','ESTIMATE',
                     'CHALLAN','SALE_RETURN','PURCHASE_RETURN','CREDIT_NOTE','DEBIT_NOTE')),
                party_id INTEGER,
                party_is_supplier INTEGER NOT NULL DEFAULT 0,
                party_name TEXT NOT NULL DEFAULT '',
                party_mobile TEXT NOT NULL DEFAULT '',
                party_address TEXT NOT NULL DEFAULT '',
                invoice_date INTEGER NOT NULL,
                due_date INTEGER NOT NULL DEFAULT 0,
                payment_terms TEXT NOT NULL DEFAULT '',
                is_gst INTEGER NOT NULL DEFAULT 0,
                place_of_supply TEXT NOT NULL DEFAULT '',
                subtotal_paise INTEGER NOT NULL DEFAULT 0,
                discount_paise INTEGER NOT NULL DEFAULT 0,
                tax_paise INTEGER NOT NULL DEFAULT 0,
                total_paise INTEGER NOT NULL DEFAULT 0,
                paid_paise INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'UNPAID' CHECK (status IN ('UNPAID','PARTIAL','PAID')),
                notes TEXT NOT NULL DEFAULT '',
                tx_id INTEGER REFERENCES transactions(id) ON DELETE SET NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                UNIQUE (business_id, invoice_no)
            )""",
            """CREATE TABLE invoice_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                invoice_id INTEGER NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
                product_id INTEGER REFERENCES products(id) ON DELETE SET NULL,
                name TEXT NOT NULL,
                qty_milli INTEGER NOT NULL,
                unit TEXT NOT NULL DEFAULT 'pcs',
                unit_price_paise INTEGER NOT NULL DEFAULT 0,
                discount_paise INTEGER NOT NULL DEFAULT 0,
                tax_rate_bp INTEGER NOT NULL DEFAULT 0,
                cgst_paise INTEGER NOT NULL DEFAULT 0,
                sgst_paise INTEGER NOT NULL DEFAULT 0,
                igst_paise INTEGER NOT NULL DEFAULT 0,
                line_total_paise INTEGER NOT NULL DEFAULT 0,
                cost_paise INTEGER NOT NULL DEFAULT 0
            )""",
            """CREATE TABLE invoice_payments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                invoice_id INTEGER NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
                amount_paise INTEGER NOT NULL,
                method TEXT NOT NULL DEFAULT 'CASH' CHECK (method IN ('CASH','UPI','BANK','OTHER')),
                reference_no TEXT NOT NULL DEFAULT '',
                paid_at INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                tx_id INTEGER REFERENCES transactions(id) ON DELETE CASCADE
            )""",
            """CREATE TABLE expenses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                business_id INTEGER NOT NULL REFERENCES businesses(id),
                category_id INTEGER REFERENCES expense_categories(id) ON DELETE SET NULL,
                title TEXT NOT NULL,
                amount_paise INTEGER NOT NULL CHECK (amount_paise > 0),
                expense_date INTEGER NOT NULL,
                payment_method TEXT NOT NULL DEFAULT 'CASH' CHECK (payment_method IN ('CASH','UPI','BANK','OTHER')),
                notes TEXT NOT NULL DEFAULT '',
                attachment_path TEXT NOT NULL DEFAULT '',
                recurrence TEXT NOT NULL DEFAULT 'NONE' CHECK (recurrence IN ('NONE','DAILY','WEEKLY','MONTHLY')),
                recurring_next INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""",
            """CREATE TABLE expense_categories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                business_id INTEGER NOT NULL REFERENCES businesses(id),
                name TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                UNIQUE (business_id, name)
            )""",
            """CREATE TABLE cash_accounts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                business_id INTEGER NOT NULL REFERENCES businesses(id),
                name TEXT NOT NULL,
                account_type TEXT NOT NULL DEFAULT 'CASH' CHECK (account_type IN ('CASH','BANK','UPI')),
                opening_paise INTEGER NOT NULL DEFAULT 0,
                is_default INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                UNIQUE (business_id, name)
            )""",
            """CREATE TABLE cash_transfers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                business_id INTEGER NOT NULL REFERENCES businesses(id),
                from_account_id INTEGER NOT NULL REFERENCES cash_accounts(id) ON DELETE CASCADE,
                to_account_id INTEGER NOT NULL REFERENCES cash_accounts(id) ON DELETE CASCADE,
                amount_paise INTEGER NOT NULL CHECK (amount_paise > 0),
                note TEXT NOT NULL DEFAULT '',
                tx_time INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )""",
            """CREATE TABLE staff (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                business_id INTEGER NOT NULL REFERENCES businesses(id),
                name TEXT NOT NULL,
                mobile TEXT NOT NULL DEFAULT '',
                role TEXT NOT NULL DEFAULT 'EMPLOYEE'
                    CHECK (role IN ('OWNER','ADMINISTRATOR','MANAGER','ACCOUNTANT','EMPLOYEE')),
                monthly_salary_paise INTEGER NOT NULL DEFAULT 0,
                joined_at INTEGER NOT NULL DEFAULT 0,
                is_active INTEGER NOT NULL DEFAULT 1,
                notes TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""",
            """CREATE TABLE attendance (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                staff_id INTEGER NOT NULL REFERENCES staff(id) ON DELETE CASCADE,
                day_start INTEGER NOT NULL,
                present INTEGER NOT NULL DEFAULT 1,
                note TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                UNIQUE (staff_id, day_start)
            )""",
            """CREATE TABLE audit_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                business_id INTEGER NOT NULL DEFAULT 1,
                action TEXT NOT NULL,
                entity TEXT NOT NULL,
                entity_id INTEGER NOT NULL DEFAULT 0,
                detail TEXT NOT NULL DEFAULT '',
                at INTEGER NOT NULL
            )""",
            """CREATE TABLE deleted_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                business_id INTEGER NOT NULL DEFAULT 1,
                entity TEXT NOT NULL,
                title TEXT NOT NULL,
                payload TEXT NOT NULL,
                deleted_at INTEGER NOT NULL
            )""",
            "CREATE INDEX idx_customers_name ON customers(name)",
            "CREATE INDEX idx_customers_mobile ON customers(mobile)",
            "CREATE INDEX idx_suppliers_name ON suppliers(name)",
            "CREATE INDEX idx_suppliers_mobile ON suppliers(mobile)",
            "CREATE INDEX idx_tx_customer ON transactions(customer_id, tx_time)",
            "CREATE INDEX idx_tx_supplier ON transactions(supplier_id, tx_time)",
            "CREATE INDEX idx_tx_time ON transactions(tx_time)",
            "CREATE INDEX idx_reminders_customer ON reminders(customer_id)",
            "CREATE INDEX idx_product_categories_business ON product_categories(business_id)",
            "CREATE INDEX idx_products_business_name ON products(business_id, name)",
            "CREATE INDEX idx_products_barcode ON products(business_id, barcode)",
            "CREATE INDEX idx_movements_product ON stock_movements(product_id, tx_time)",
            "CREATE INDEX idx_movements_business ON stock_movements(business_id, tx_time)",
            "CREATE INDEX idx_invoices_business ON invoices(business_id, invoice_date)",
            "CREATE INDEX idx_invoices_type ON invoices(business_id, invoice_type)",
            "CREATE INDEX idx_invoice_items_invoice ON invoice_items(invoice_id)",
            "CREATE INDEX idx_invoice_payments_invoice ON invoice_payments(invoice_id)",
            "CREATE INDEX idx_expenses_business ON expenses(business_id, expense_date)",
            "CREATE INDEX idx_expense_categories_business ON expense_categories(business_id)",
            "CREATE INDEX idx_accounts_business ON cash_accounts(business_id)",
            "CREATE INDEX idx_transfers_business ON cash_transfers(business_id, tx_time)",
            "CREATE INDEX idx_staff_business ON staff(business_id)",
            "CREATE INDEX idx_attendance_staff ON attendance(staff_id, day_start)",
            "CREATE INDEX idx_audit_at ON audit_log(at)"
        )

        /**
         * The same new tables/indexes, expressed as "CREATE ... IF NOT EXISTS" copies that are
         * safe to run on a v1 database during the migration.
         */
        private val V2_CREATE_STATEMENTS = listOf(
            """CREATE TABLE IF NOT EXISTS product_categories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                business_id INTEGER NOT NULL REFERENCES businesses(id),
                name TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                UNIQUE (business_id, name)
            )""",
            """CREATE TABLE IF NOT EXISTS products (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                business_id INTEGER NOT NULL REFERENCES businesses(id),
                category_id INTEGER REFERENCES product_categories(id) ON DELETE SET NULL,
                name TEXT NOT NULL,
                sku TEXT NOT NULL DEFAULT '',
                barcode TEXT NOT NULL DEFAULT '',
                unit TEXT NOT NULL DEFAULT 'pcs',
                image_path TEXT NOT NULL DEFAULT '',
                purchase_price_paise INTEGER NOT NULL DEFAULT 0,
                selling_price_paise INTEGER NOT NULL DEFAULT 0,
                mrp_paise INTEGER NOT NULL DEFAULT 0,
                opening_stock_milli INTEGER NOT NULL DEFAULT 0,
                stock_milli INTEGER NOT NULL DEFAULT 0,
                min_stock_milli INTEGER NOT NULL DEFAULT 0,
                allow_negative INTEGER NOT NULL DEFAULT 0,
                notes TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                UNIQUE (business_id, sku)
            )""",
            """CREATE TABLE IF NOT EXISTS stock_movements (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                business_id INTEGER NOT NULL REFERENCES businesses(id),
                product_id INTEGER NOT NULL REFERENCES products(id) ON DELETE CASCADE,
                move_type TEXT NOT NULL CHECK (move_type IN
                    ('OPENING','PURCHASE','SALE','SALE_RETURN','PURCHASE_RETURN','ADJUSTMENT')),
                qty_milli INTEGER NOT NULL,
                balance_milli INTEGER NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                ref_table TEXT NOT NULL DEFAULT '',
                ref_id INTEGER NOT NULL DEFAULT 0,
                tx_time INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )""",
            """CREATE TABLE IF NOT EXISTS invoices (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                business_id INTEGER NOT NULL REFERENCES businesses(id),
                invoice_no TEXT NOT NULL,
                invoice_type TEXT NOT NULL CHECK (invoice_type IN
                    ('SALE','PURCHASE','PROFORMA','QUOTATION','ESTIMATE',
                     'CHALLAN','SALE_RETURN','PURCHASE_RETURN','CREDIT_NOTE','DEBIT_NOTE')),
                party_id INTEGER,
                party_is_supplier INTEGER NOT NULL DEFAULT 0,
                party_name TEXT NOT NULL DEFAULT '',
                party_mobile TEXT NOT NULL DEFAULT '',
                party_address TEXT NOT NULL DEFAULT '',
                invoice_date INTEGER NOT NULL,
                due_date INTEGER NOT NULL DEFAULT 0,
                payment_terms TEXT NOT NULL DEFAULT '',
                is_gst INTEGER NOT NULL DEFAULT 0,
                place_of_supply TEXT NOT NULL DEFAULT '',
                subtotal_paise INTEGER NOT NULL DEFAULT 0,
                discount_paise INTEGER NOT NULL DEFAULT 0,
                tax_paise INTEGER NOT NULL DEFAULT 0,
                total_paise INTEGER NOT NULL DEFAULT 0,
                paid_paise INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'UNPAID' CHECK (status IN ('UNPAID','PARTIAL','PAID')),
                notes TEXT NOT NULL DEFAULT '',
                tx_id INTEGER REFERENCES transactions(id) ON DELETE SET NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                UNIQUE (business_id, invoice_no)
            )""",
            """CREATE TABLE IF NOT EXISTS invoice_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                invoice_id INTEGER NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
                product_id INTEGER REFERENCES products(id) ON DELETE SET NULL,
                name TEXT NOT NULL,
                qty_milli INTEGER NOT NULL,
                unit TEXT NOT NULL DEFAULT 'pcs',
                unit_price_paise INTEGER NOT NULL DEFAULT 0,
                discount_paise INTEGER NOT NULL DEFAULT 0,
                tax_rate_bp INTEGER NOT NULL DEFAULT 0,
                cgst_paise INTEGER NOT NULL DEFAULT 0,
                sgst_paise INTEGER NOT NULL DEFAULT 0,
                igst_paise INTEGER NOT NULL DEFAULT 0,
                line_total_paise INTEGER NOT NULL DEFAULT 0,
                cost_paise INTEGER NOT NULL DEFAULT 0
            )""",
            """CREATE TABLE IF NOT EXISTS invoice_payments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                invoice_id INTEGER NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
                amount_paise INTEGER NOT NULL,
                method TEXT NOT NULL DEFAULT 'CASH' CHECK (method IN ('CASH','UPI','BANK','OTHER')),
                reference_no TEXT NOT NULL DEFAULT '',
                paid_at INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                tx_id INTEGER REFERENCES transactions(id) ON DELETE CASCADE
            )""",
            """CREATE TABLE IF NOT EXISTS expenses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                business_id INTEGER NOT NULL REFERENCES businesses(id),
                category_id INTEGER REFERENCES expense_categories(id) ON DELETE SET NULL,
                title TEXT NOT NULL,
                amount_paise INTEGER NOT NULL CHECK (amount_paise > 0),
                expense_date INTEGER NOT NULL,
                payment_method TEXT NOT NULL DEFAULT 'CASH' CHECK (payment_method IN ('CASH','UPI','BANK','OTHER')),
                notes TEXT NOT NULL DEFAULT '',
                attachment_path TEXT NOT NULL DEFAULT '',
                recurrence TEXT NOT NULL DEFAULT 'NONE' CHECK (recurrence IN ('NONE','DAILY','WEEKLY','MONTHLY')),
                recurring_next INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""",
            """CREATE TABLE IF NOT EXISTS expense_categories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                business_id INTEGER NOT NULL REFERENCES businesses(id),
                name TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                UNIQUE (business_id, name)
            )""",
            """CREATE TABLE IF NOT EXISTS cash_accounts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                business_id INTEGER NOT NULL REFERENCES businesses(id),
                name TEXT NOT NULL,
                account_type TEXT NOT NULL DEFAULT 'CASH' CHECK (account_type IN ('CASH','BANK','UPI')),
                opening_paise INTEGER NOT NULL DEFAULT 0,
                is_default INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                UNIQUE (business_id, name)
            )""",
            """CREATE TABLE IF NOT EXISTS cash_transfers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                business_id INTEGER NOT NULL REFERENCES businesses(id),
                from_account_id INTEGER NOT NULL REFERENCES cash_accounts(id) ON DELETE CASCADE,
                to_account_id INTEGER NOT NULL REFERENCES cash_accounts(id) ON DELETE CASCADE,
                amount_paise INTEGER NOT NULL CHECK (amount_paise > 0),
                note TEXT NOT NULL DEFAULT '',
                tx_time INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )""",
            """CREATE TABLE IF NOT EXISTS staff (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                business_id INTEGER NOT NULL REFERENCES businesses(id),
                name TEXT NOT NULL,
                mobile TEXT NOT NULL DEFAULT '',
                role TEXT NOT NULL DEFAULT 'EMPLOYEE'
                    CHECK (role IN ('OWNER','ADMINISTRATOR','MANAGER','ACCOUNTANT','EMPLOYEE')),
                monthly_salary_paise INTEGER NOT NULL DEFAULT 0,
                joined_at INTEGER NOT NULL DEFAULT 0,
                is_active INTEGER NOT NULL DEFAULT 1,
                notes TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""",
            """CREATE TABLE IF NOT EXISTS attendance (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                staff_id INTEGER NOT NULL REFERENCES staff(id) ON DELETE CASCADE,
                day_start INTEGER NOT NULL,
                present INTEGER NOT NULL DEFAULT 1,
                note TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                UNIQUE (staff_id, day_start)
            )""",
            """CREATE TABLE IF NOT EXISTS audit_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                business_id INTEGER NOT NULL DEFAULT 1,
                action TEXT NOT NULL,
                entity TEXT NOT NULL,
                entity_id INTEGER NOT NULL DEFAULT 0,
                detail TEXT NOT NULL DEFAULT '',
                at INTEGER NOT NULL
            )""",
            """CREATE TABLE IF NOT EXISTS deleted_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                business_id INTEGER NOT NULL DEFAULT 1,
                entity TEXT NOT NULL,
                title TEXT NOT NULL,
                payload TEXT NOT NULL,
                deleted_at INTEGER NOT NULL
            )""",
            "CREATE INDEX IF NOT EXISTS idx_product_categories_business ON product_categories(business_id)",
            "CREATE INDEX IF NOT EXISTS idx_products_business_name ON products(business_id, name)",
            "CREATE INDEX IF NOT EXISTS idx_products_barcode ON products(business_id, barcode)",
            "CREATE INDEX IF NOT EXISTS idx_movements_product ON stock_movements(product_id, tx_time)",
            "CREATE INDEX IF NOT EXISTS idx_movements_business ON stock_movements(business_id, tx_time)",
            "CREATE INDEX IF NOT EXISTS idx_invoices_business ON invoices(business_id, invoice_date)",
            "CREATE INDEX IF NOT EXISTS idx_invoices_type ON invoices(business_id, invoice_type)",
            "CREATE INDEX IF NOT EXISTS idx_invoice_items_invoice ON invoice_items(invoice_id)",
            "CREATE INDEX IF NOT EXISTS idx_invoice_payments_invoice ON invoice_payments(invoice_id)",
            "CREATE INDEX IF NOT EXISTS idx_expenses_business ON expenses(business_id, expense_date)",
            "CREATE INDEX IF NOT EXISTS idx_expense_categories_business ON expense_categories(business_id)",
            "CREATE INDEX IF NOT EXISTS idx_accounts_business ON cash_accounts(business_id)",
            "CREATE INDEX IF NOT EXISTS idx_transfers_business ON cash_transfers(business_id, tx_time)",
            "CREATE INDEX IF NOT EXISTS idx_staff_business ON staff(business_id)",
            "CREATE INDEX IF NOT EXISTS idx_attendance_staff ON attendance(staff_id, day_start)",
            "CREATE INDEX IF NOT EXISTS idx_audit_at ON audit_log(at)"
        )
    }
}
