# Change log

## 2.0 (version code 6)

Everything below was added on top of the original Hishabbaki phase-4 project
without changing the package name (`com.hisabbaki.app`) or the existing screens.

### Database (schema v2)
- New `businesses` table; every business table now carries `business_id`.
  On update all existing records attach to business 1 automatically — nothing
  is lost.
- New tables: products, product_categories, stock_movements, invoices,
  invoice_items, invoice_payments, expenses, expense_categories, cash_accounts,
  cash_transfers, staff, attendance, audit_log, deleted_records.
- Legacy tables (customers, suppliers, transactions, reminders) gained
  `business_id` + indexes via migration.

### New modules
- **Inventory**: products with SKU/barcode/photo/unit/prices/min-stock,
  barcode & QR scanning, stock history, manual adjustments; stock moves
  automatically with sales, purchases and returns; low/out-of-stock alerts.
- **Expenses**: categories (seeded), payment method, date, recurrence with
  catch-up, photo attachment, reports.
- **Billing**: 10 document types (sales/purchase invoice, proforma, quotation,
  estimate, delivery challan, sales/purchase return, credit/debit note) with
  correct CGST/SGST/IGST maths, search, automatic numbering, per-document
  payments, branded PDF share and print.
- **Cashbook**: accounts (cash/bank/UPI), summary with cash in/out/hand,
  All/Today/Month filters, inter-account transfers, defaults and guards.
- **Dashboard**: overview card with Today/Week/Month/Year filters
  (sales, purchases, expenses, net profit), stock line, quick actions.
- **Reports**: 16 report types, each with date filters and PDF + CSV export.
- **UPI QR**: standard `upi://pay` QR per customer with amount; shareable PNG.
  Payments are only ever recorded manually — never auto-marked.
- **Multi-business**: create/edit/switch/delete businesses; switching changes
  every screen and report; strict SQL isolation; the last business protects
  itself from deletion.
- **Staff**: roster with 5 roles, add/edit/delete, daily attendance,
  salary payments saved as real Salary expenses (visible in reports/P&L).
- **Recycle bin**: deleting a customer, supplier, transaction, expense,
  product or staff member keeps a JSON copy that can be restored or purged.
- **Audit log**: business-level trail of create/update/delete actions.
- **AI assistant**: offline answers computed from the database; optional
  OpenAI-compatible API (user-supplied base URL/model/key, stored on device,
  never hardcoded or uploaded anywhere else).

### Upgraded modules
- **Security**: app lock with 4–8 digit PIN (PBKDF2-hashed), fingerprint
  unlock via BiometricPrompt, 3-minute re-lock.
- **Backup**: manual JSON backup/restore plus automatic daily local backup
  keeping the last 7 copies.
- **Languages**: English, Bengali and Hindi now cover every screen including
  all new ones (307 string keys in parity).

### Fixes during the upgrade
- `android:suffixText` → `app:suffixText` (Material TextInputLayout).
- Invoice line-item edit-in-place, walk-in party handling, tax label mapping.
- Legacy party/transaction/reminder/dashboard queries are now scoped to the
  active business (they predated multi-business and could mix records).

### Toolchain
- Gradle wrapper 8.9, compileSdk/targetSdk 34, minSdk 24, Java 17 — unchanged
  compatibility profile for CodeOnTheGo.
- New dependencies: `androidx.biometric:biometric:1.1.0` only.
