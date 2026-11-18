package com.hisabbaki.app.data

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
        db.execSQL("INSERT INTO business_profile (id) VALUES (1)")
        db.execSQL("INSERT OR IGNORE INTO app_settings (key, value) VALUES ('schema_created', '1')")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future schema migrations go here, one step per version. Never drop user data.
    }

    companion object {
        const val DB_NAME = "hisabbaki.db"
        const val DB_VERSION = 1

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

        private val CREATE_STATEMENTS = listOf(
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
                updated_at INTEGER NOT NULL
            )""",
            """CREATE TABLE suppliers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                mobile TEXT NOT NULL DEFAULT '',
                address TEXT NOT NULL DEFAULT '',
                opening_balance_paise INTEGER NOT NULL DEFAULT 0,
                notes TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
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
                created_at INTEGER NOT NULL
            )""",
            """CREATE TABLE app_settings (
                key TEXT PRIMARY KEY NOT NULL,
                value TEXT NOT NULL DEFAULT ''
            )""",
            "CREATE INDEX idx_customers_name ON customers(name)",
            "CREATE INDEX idx_customers_mobile ON customers(mobile)",
            "CREATE INDEX idx_suppliers_name ON suppliers(name)",
            "CREATE INDEX idx_suppliers_mobile ON suppliers(mobile)",
            "CREATE INDEX idx_tx_customer ON transactions(customer_id, tx_time)",
            "CREATE INDEX idx_tx_supplier ON transactions(supplier_id, tx_time)",
            "CREATE INDEX idx_tx_time ON transactions(tx_time)",
            "CREATE INDEX idx_reminders_customer ON reminders(customer_id)"
        )
    }
}
