# Hishabbaki — Ultimate Business Management (v2.0)

Hishabbaki is a complete offline-first business management app for Indian small
businesses: digital khata (customer/supplier ledgers), GST billing, inventory,
purchases, expenses, cashbook, 16 reports, UPI QR collection, multiple
businesses, staff management, backup/restore, app lock, and an optional AI
assistant — with full English / Bengali / Hindi support.

Package name: `com.hisabbaki.app` (unchanged from the original project).
Built for **CodeOnTheGo**: Gradle 8.9, no Compose, no kapt/annotation
processors, no desktop-only tooling.

## Feature map

| Module | Where in the app |
|---|---|
| A. Business dashboard | Home tab — Today/Week/Month/Year overview card, quick actions, stock line |
| B. Digital khata | Khata tab — customers + suppliers, ledgers, running balances |
| C. Payment reminders | Customer ledger — WhatsApp/SMS share intents with manual confirmation, reminder history |
| D. Billing & invoices (10 doc types, GST) | Billing tab — sales/purchase/proforma/quotation/estimate/challan/returns/notes |
| E. Inventory | Settings → Inventory — products, barcode scanning, stock history, low-stock alerts |
| F. Purchases | Billing tab — PURCHASE invoices update stock automatically |
| G. Expenses | Settings → Expenses — categories, recurrence, attachments |
| H. Cashbook | Settings → Cashbook — accounts, cash in/out, transfers |
| I. Reports (16 types) | Reports tab — PDF + CSV export for every report |
| J. UPI collection | Customer ledger → UPI QR — payment is only ever recorded manually |
| K. Multiple businesses | Settings → Businesses — strict per-business isolation |
| L. Staff | Settings → Staff — roles, attendance, salary as real expenses |
| M. Backup & restore | Settings → Backup — JSON backup/restore + daily auto-backup |
| N. Security | Settings → App lock — 4–8 digit PIN (PBKDF2), fingerprint |
| O. Languages | Settings → Language — English, বাংলা, हिन्दी (system default) |
| P. Extras | Recycle bin, audit log, barcode/QR scanning, photos on records, dark/light theme |
| Q. AI assistant | Settings → AI business assistant — offline answers from your data; optional user-configured API |

## Core principles

- **All numbers come from your database.** No sample data, no mocked values.
- **Money is stored in paise** (integer) to avoid floating-point errors;
  quantities in thousandths of a unit.
- **Balances are always derived**, never stored, so editing/deleting records
  can never corrupt a balance.
- **Payments are never auto-marked**: opening a UPI app proves nothing; a
  payment is recorded only when you confirm it.
- **Multiple businesses are strictly isolated** at the SQL level; every query
  filters on the active `business_id`.

## Where to go next

- [BUILD_INSTRUCTIONS.md](BUILD_INSTRUCTIONS.md) — build on a PC or in CodeOnTheGo
- [CODEONTHEGO_BUILD_GUIDE.md](CODEONTHEGO_BUILD_GUIDE.md) — phone-only workflow
- [DATABASE_SCHEMA.md](DATABASE_SCHEMA.md) — tables, keys, isolation rules
- [BACKUP_RESTORE_GUIDE.md](BACKUP_RESTORE_GUIDE.md) — how backup/restore works
- [CHANGELOG.md](CHANGELOG.md) — what changed since the original version

## External services (all optional)

| Service | Needed for | Configuration |
|---|---|---|
| OpenAI-compatible chat API | AI assistant replies beyond offline mode | Settings → AI business assistant → menu → AI API configuration. You provide base URL, model name and your own API key. No key is hardcoded; requests go only to the URL you enter. |
| Nothing else | — | Backups, reports, PDFs, QR codes, scanning all work fully offline. No payment gateway, Firebase, or cloud account is required. |

## Release APK

`app/build/outputs/apk/release/app-release-unsigned.apk` is produced by
`assembleRelease`. Sign it (see BUILD_INSTRUCTIONS) before installing a
release build over the existing app so data updates in place.
