# Database schema (SQLite, DB version 2)

Hand-rolled `SQLiteOpenHelper` (`data/DatabaseHelper.kt`) — no Room, no
annotation processors, CodeOnTheGo-safe. All money amounts are **paise**
(`*_paise`, INTEGER), all quantities are **milli-units** (`*_milli`), tax rates
are basis points (`percentage × 100`).

## Isolation rule

Every business table has `business_id INTEGER NOT NULL DEFAULT 1 REFERENCES
businesses(id)` and every read/write goes through the repository layer with
`business_id = Prefs.currentBusinessId(...)`. The legacy tables were migrated
with `ALTER TABLE ... ADD COLUMN business_id NOT NULL DEFAULT 1` plus indexes,
so an update from the 1.x database keeps all records in business 1.

## businesses
`id` PK · name · owner_name · mobile · address · gstin · logo_path · upi_id ·
currency · created_at · updated_at

## customers / suppliers (identical shape)
`id` PK · business_id FK · name · mobile · address · opening_balance_paise ·
notes · created_at · updated_at · FK(business_id)→businesses

## transactions
`id` PK · business_id FK · customer_id → customers(id) (nullable) ·
supplier_id → suppliers(id) (nullable) · tx_type (`U_GAVE`/`U_GOT` for
customers, mirrored types for suppliers, plus `PAYMENT`) · amount_paise ·
tx_time · description · reference_no · payment_method · notes · client_uuid
(unique — protects against double-tap duplicates) · created_at · updated_at

## reminders
`id` PK · business_id FK · customer_id FK · channel (WHATSAPP/SMS) ·
amount_paise · message · created_at

## product_categories / expense_categories
`id` PK · business_id FK · name · UNIQUE(business_id, name)

## products
`id` PK · business_id FK · category_id (nullable) · name · sku · barcode ·
unit · image_path · purchase_price_paise · selling_price_paise · mrp_paise ·
opening_stock_milli · stock_milli (denormalised, maintained transactionally) ·
min_stock_milli · allow_negative · notes · UNIQUE(business_id, sku)

## stock_movements
`id` PK · business_id FK · product_id FK (cascade) · move_type
(OPENING/PURCHASE/SALE/ADJUST/SALE_RETURN/PURCHASE_RETURN) · qty_milli ·
balance_milli · note · invoice_id (nullable) · tx_time

## invoices
`id` PK · business_id FK · invoice_no UNIQUE(business_id, invoice_no) ·
invoice_type (SALE/PURCHASE/PROFORMA/QUOTATION/ESTIMATE/CHALLAN/
SALE_RETURN/PURCHASE_RETURN/CREDIT_NOTE/DEBIT_NOTE) · customer_id (nullable
= walk-in) · supplier_id (nullable) · invoice_date · due_date ·
subtotal/taxable/discount/tax amounts (paise) · tax_rate_bp ·
inter_state · total_paise · paid_paise (denormalised) · status
(UNPAID/PARTIAL/PAID) · payment_terms · notes

## invoice_items
`id` PK · invoice_id FK (cascade) · product_id (nullable) · description ·
qty_milli · unit_paise · cost_paise (purchase price at sale time, used for
COGS/profit) · discount_paise · tax_rate_bp · tax_paise · total_paise

## invoice_payments
`id` PK · invoice_id FK (cascade) · amount_paise · method · reference_no ·
paid_at

## expenses
`id` PK · business_id FK · category_id (nullable) · title · amount_paise ·
expense_date · payment_method · notes · attachment_path · recurrence
(NONE/DAILY/WEEKLY/MONTHLY) · recurring_next

## cash_accounts
`id` PK · business_id FK · name · account_type (CASH/BANK/UPI) ·
opening_paise · is_default · UNIQUE(business_id, name)

## cash_transfers
`id` PK · business_id FK · from_account_id · to_account_id · amount_paise ·
tx_time · note

## staff
`id` PK · business_id FK · name · mobile · role (OWNER/ADMINISTRATOR/MANAGER/
ACCOUNTANT/EMPLOYEE) · monthly_salary_paise · joined_at · is_active · notes

## attendance
`id` PK · staff_id FK · day_start (local midnight) · present · note ·
UNIQUE(staff_id, day_start)

## audit_log
`id` PK · business_id FK · action (CREATE/UPDATE/DELETE/RESTORE/…) · entity ·
entity_id · detail (≤400 chars) · at

## deleted_records (recycle bin)
`id` PK · business_id FK · entity (customer/supplier/transaction/expense/
product/staff) · title · payload (JSON copy of the deleted row(s)) · deleted_at

## business_profile / app_settings
Legacy key/value stores kept for compatibility with the original app.

## Indexes (created in code)
`customers(business_id)`, `suppliers(business_id)`,
`transactions(business_id, tx_time)`, `reminders(business_id)`, plus FK
columns on invoices/items/payments, stock_movements(product_id, tx_time),
expenses(business_id, expense_date).
