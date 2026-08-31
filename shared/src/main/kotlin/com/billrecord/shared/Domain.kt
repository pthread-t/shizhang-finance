package com.billrecord.shared

import kotlinx.serialization.Serializable

@Serializable
enum class TransactionType { EXPENSE, INCOME, TRANSFER, REFUND, ADJUSTMENT }

@Serializable
enum class AccountType {
    CASH, DEBIT_CARD, CREDIT_CARD, E_WALLET, SAVINGS, STORED_VALUE, INVESTMENT, LOAN, LIABILITY
}

@Serializable
enum class BookRole { OWNER, EDITOR, VIEWER }

@Serializable
enum class ReimbursementStatus { NONE, PENDING, REIMBURSED }

@Serializable
enum class SyncOperationType { UPSERT, DELETE }

@Serializable
enum class SyncEntityType {
    BOOK,
    MEMBERSHIP,
    ACCOUNT,
    CATEGORY,
    TRANSACTION,
    TRANSACTION_SPLIT,
    POSTING,
    TAG,
    TRANSACTION_TAG,
    ATTACHMENT,
    BUDGET,
    RECURRING_RULE,
    INSTALLMENT_PLAN,
    SAVING_GOAL,
    MERCHANT,
    PROJECT,
    SAVED_FILTER,
    AUDIT_EVENT,
}

@Serializable
data class Money(
    val amountMinor: Long,
    val currency: String = "CNY",
)

@Serializable
data class TransactionFilter(
    val bookIds: Set<String> = emptySet(),
    val startEpochMillis: Long? = null,
    val endEpochMillis: Long? = null,
    val types: Set<TransactionType> = emptySet(),
    val categoryIds: Set<String> = emptySet(),
    val accountIds: Set<String> = emptySet(),
    val tagIds: Set<String> = emptySet(),
    val memberIds: Set<String> = emptySet(),
    val merchantIds: Set<String> = emptySet(),
    val projectIds: Set<String> = emptySet(),
    val minimumAmountMinor: Long? = null,
    val maximumAmountMinor: Long? = null,
    val reimbursementStatuses: Set<ReimbursementStatus> = emptySet(),
    val query: String = "",
)
