# Backup & restore guide

Hishabbaki stores everything in one local SQLite database
(`hisabbaki.db`, WAL mode). Backup and restore work completely offline.

## Manual backup (SAF copy)

- **Settings → Backup → Backup now** — writes a consistent copy of the live
  database (WAL is checkpointed first, so the copy is complete) to any folder
  you pick with the system file picker. Recommended: Google Drive folder or
  USB OTG/SD card.
- The backup contains every business, ledger, invoice, product, expense,
  cashbook, staff record and your recycle bin.

## Restore (validated in two steps)

- **Settings → Backup → Restore** — pick a `hisabbaki_backup_*.db` file.
  The app first copies it locally and validates it (correct schema; a backup
  from a *newer* database version is refused with a clear message).
- Before replacing the live database it makes a **safety copy of your current
  data**; if anything fails mid-restore the original database is put back.

## Automatic daily backup

- **Settings → "Automatic daily backup"** switch: when enabled, a backup is
  made at most once per day while the app is used.
- Files go to the app's own external files directory:
  `Android/data/com.hisabbaki.app/files/backups/hisabbaki_backup_YYYY-MM-DD.db`
  — readable via a file manager, wiped if the app is uninstalled. The last
  **7 copies** are kept; older ones are rotated out.
- These automatic copies are a safety net, not off-device storage — for real
  protection copy a manual backup to Drive/SD regularly.

## Updating the app without losing data

1. Keep the same signing key (Android then treats the release as an update).
2. Install the new APK over the old one — the database migrates
   (v1 → v2 attaches all existing records to business 1). Nothing is deleted.
3. Belt-and-braces: make a manual backup before updating; it can be restored
   into any 2.x build.

## CSV / data export

- Settings → **Export data** writes CSV tables (customers, suppliers,
  transactions, plus the new modules through the report exporters) —
  openable in Excel/Sheets.
- Every report also exports its own CSV and PDF.

## What is not stored in the backup file

- The app-lock PIN (only a PBKDF2 hash lives on the device; after restore you
  may need to set the PIN again if the restored file has none).
- AI API configuration is kept in shared preferences; re-enter it after
  restoring on a new device.
