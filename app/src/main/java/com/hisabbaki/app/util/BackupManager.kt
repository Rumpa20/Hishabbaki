package com.hisabbaki.app.util

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import com.hisabbaki.app.R
import com.hisabbaki.app.data.DatabaseHelper
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class BackupException(val messageRes: Int) : Exception("Backup problem")

class BackupInfo(
    val file: File,
    val customers: Int,
    val suppliers: Int,
    val transactions: Int
)

/**
 * Local backup / restore of the whole SQLite database file.
 * All functions are blocking and must run through Db.async, which also guarantees that no other
 * database work happens at the same moment.
 */
object BackupManager {

    private val REQUIRED_TABLES = listOf(
        "business_profile", "customers", "suppliers", "transactions", "reminders", "app_settings"
    )
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)

    private fun checkpoint(db: SQLiteDatabase) {
        // Move everything from the write-ahead log into the main file so a plain copy is complete.
        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
    }

    /** Writes a consistent copy of the live database to the chosen location. */
    fun createBackup(ctx: Context, uri: Uri) {
        val db = DatabaseHelper.get(ctx).writableDatabase
        checkpoint(db)
        FileOps.copyFileToUri(ctx, DatabaseHelper.dbFile(ctx), uri)
    }

    /**
     * Copies the chosen file to a private temporary file and validates it completely.
     * Nothing in the live database is touched. Throws BackupException with a message resource.
     */
    fun inspect(ctx: Context, uri: Uri): BackupInfo {
        val temp = File(ctx.cacheDir, "restore_candidate.db")
        temp.delete()
        try {
            val input = ctx.contentResolver.openInputStream(uri)
                ?: throw BackupException(R.string.restore_invalid)
            input.use { i -> FileOutputStream(temp).use { o -> i.copyTo(o) } }
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            temp.delete()
            throw BackupException(R.string.restore_invalid)
        }

        try {
            val header = ByteArray(16)
            val read = FileInputStream(temp).use { it.read(header) }
            if (read < 16 || !header.contentEquals(SQLITE_MAGIC)) throw BackupException(R.string.restore_invalid)
            return validateDatabase(temp)
        } catch (e: BackupException) {
            temp.delete()
            throw e
        } catch (e: Exception) {
            temp.delete()
            throw BackupException(R.string.restore_corrupt)
        }
    }

    private fun validateDatabase(file: File): BackupInfo {
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)

            val integrity = db.rawQuery("PRAGMA integrity_check", null).use {
                if (it.moveToFirst()) it.getString(0) else ""
            }
            if (integrity != "ok") throw BackupException(R.string.restore_corrupt)

            val tables = HashSet<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use {
                while (it.moveToNext()) tables.add(it.getString(0) ?: "")
            }
            if (!tables.containsAll(REQUIRED_TABLES)) throw BackupException(R.string.restore_invalid)

            if (db.version < 1) throw BackupException(R.string.restore_invalid)
            if (db.version > DatabaseHelper.DB_VERSION) throw BackupException(R.string.restore_newer)

            // The columns the app depends on must exist.
            db.rawQuery(
                "SELECT id, name, mobile, address, opening_balance_paise, notes FROM customers LIMIT 1", null
            ).close()
            db.rawQuery(
                "SELECT id, name, mobile, address, opening_balance_paise, notes FROM suppliers LIMIT 1", null
            ).close()
            db.rawQuery(
                "SELECT id, customer_id, supplier_id, tx_type, amount_paise, tx_time, description, " +
                    "reference_no, payment_method, notes, client_uuid FROM transactions LIMIT 1", null
            ).close()
            db.rawQuery(
                "SELECT business_name, owner_name, mobile, address, gstin, logo_path FROM business_profile LIMIT 1",
                null
            ).close()

            return BackupInfo(
                file,
                count(db, "customers"),
                count(db, "suppliers"),
                count(db, "transactions")
            )
        } catch (e: SQLiteException) {
            throw BackupException(R.string.restore_invalid)
        } finally {
            db?.close()
        }
    }

    private fun count(db: SQLiteDatabase, table: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM " + table, null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    /**
     * Replaces the live database with a validated backup. A safety copy of the current data is
     * kept in app storage first, and put back automatically if anything goes wrong.
     */
    fun restore(ctx: Context, info: BackupInfo) {
        val dbFile = DatabaseHelper.dbFile(ctx)

        val liveDb = DatabaseHelper.get(ctx).writableDatabase
        checkpoint(liveDb)
        val safetyDir = File(ctx.filesDir, "backups")
        safetyDir.mkdirs()
        val safety = File(safetyDir, "before_restore.db")
        dbFile.copyTo(safety, overwrite = true)

        DatabaseHelper.closeAndReset()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        File(dbFile.path + "-journal").delete()

        try {
            info.file.copyTo(dbFile, overwrite = true)
            // Open once now so a broken file is found immediately, not on the next screen.
            DatabaseHelper.get(ctx).writableDatabase
        } catch (e: Exception) {
            DatabaseHelper.closeAndReset()
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
            safety.copyTo(dbFile, overwrite = true)
            throw e
        } finally {
            info.file.delete()
        }
    }
}
