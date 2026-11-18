package com.hisabbaki.app.data

/** A customer (isSupplier = false) or a supplier (isSupplier = true). */
data class Party(
    val id: Long,
    val isSupplier: Boolean,
    val name: String,
    val mobile: String,
    val address: String,
    val openingPaise: Long,
    val notes: String,
    /**
     * Customer: positive = customer owes you. Supplier: positive = you owe supplier.
     */
    val balancePaise: Long
) {
    /** Always from your point of view: positive = you will get, negative = you will give. */
    val forYouPaise: Long
        get() = if (isSupplier) -balancePaise else balancePaise
}

/**
 * Customer types: GAVE (increases balance), GOT (decreases balance).
 * Supplier types: PURCHASE (increases balance), PAYMENT (decreases balance).
 */
object TxTypes {
    fun increase(isSupplier: Boolean): String = if (isSupplier) "PURCHASE" else "GAVE"
    fun decrease(isSupplier: Boolean): String = if (isSupplier) "PAYMENT" else "GOT"
}

data class Tx(
    val id: Long,
    val partyId: Long,
    val isSupplier: Boolean,
    val txType: String,
    val amountPaise: Long,
    val txTime: Long,
    val description: String,
    val referenceNo: String,
    val paymentMethod: String,
    val notes: String,
    val clientUuid: String
)

data class LedgerEntry(val tx: Tx, val runningBalancePaise: Long)

data class LedgerData(
    val party: Party,
    /** Newest first, after filters. Running balance is always computed over the full history. */
    val entries: List<LedgerEntry>,
    val totalIncreasePaise: Long,
    val totalDecreasePaise: Long
)

data class RecentTx(
    val id: Long,
    val partyName: String,
    val txType: String,
    val amountPaise: Long,
    val txTime: Long
)

data class DashboardStats(
    val receivablePaise: Long,
    val payablePaise: Long,
    val customerCount: Int,
    val supplierCount: Int,
    val todayCount: Int,
    val monthCollectionPaise: Long,
    val monthPaymentsPaise: Long,
    val recent: List<RecentTx>
)

class DuplicateMobileException : Exception("Duplicate mobile number")

data class BusinessProfile(
    val name: String,
    val owner: String,
    val mobile: String,
    val address: String,
    val gstin: String,
    val logoPath: String
)

data class ReminderRecord(
    val id: Long,
    val channel: String,
    val amountPaise: Long,
    val message: String,
    val createdAt: Long
)

/** One transaction together with the name of its customer or supplier (used by reports/exports). */
data class TxRow(
    val id: Long,
    val txTime: Long,
    val txType: String,
    val amountPaise: Long,
    val partyName: String,
    val partyIsSupplier: Boolean,
    val description: String,
    val referenceNo: String,
    val paymentMethod: String,
    val notes: String
)

data class PartySummary(
    val name: String,
    val mobile: String,
    val isSupplier: Boolean,
    val increasePaise: Long,
    val decreasePaise: Long,
    val balancePaise: Long
) {
    val forYouPaise: Long
        get() = if (isSupplier) -balancePaise else balancePaise
}
