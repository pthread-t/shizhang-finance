package com.billrecord.ledger.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.billrecord.shared.AccountType
import com.billrecord.shared.BookRole
import com.billrecord.shared.ReimbursementStatus
import com.billrecord.shared.SyncEntityType
import com.billrecord.shared.SyncOperationType
import com.billrecord.shared.TransactionType

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val name: String,
    val baseCurrency: String = "CNY",
    val timezone: String = "Asia/Shanghai",
    val monthStartDay: Int = 1,
    val colorArgb: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Long = 0,
    val deletedAt: Long? = null,
)

@Entity(tableName = "memberships", primaryKeys = ["bookId", "userId"], indices = [Index("userId")])
data class MembershipEntity(
    val bookId: String,
    val userId: String,
    val displayName: String,
    val role: BookRole,
    val updatedAt: Long,
    val version: Long = 0,
    val deletedAt: Long? = null,
)

@Entity(tableName = "accounts", indices = [Index("bookId"), Index(value = ["bookId", "sortOrder"])])
data class AccountEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val name: String,
    val type: AccountType,
    val currency: String,
    val openingBalanceMinor: Long = 0,
    val creditLimitMinor: Long? = null,
    val statementDay: Int? = null,
    val repaymentDay: Int? = null,
    val icon: String,
    val colorArgb: Long,
    val sortOrder: Int,
    val archived: Boolean = false,
    val updatedAt: Long,
    val version: Long = 0,
    val deletedAt: Long? = null,
)

@Entity(tableName = "categories", indices = [Index("bookId"), Index("parentId")])
data class CategoryEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val parentId: String? = null,
    val name: String,
    val type: TransactionType,
    val icon: String,
    val colorArgb: Long,
    val sortOrder: Int,
    val archived: Boolean = false,
    val updatedAt: Long,
    val version: Long = 0,
    val deletedAt: Long? = null,
)

@Entity(
    tableName = "transactions",
    indices = [
        Index("bookId"), Index("occurredAt"), Index("categoryId"), Index("accountId"),
        Index("memberId"), Index("merchantId"), Index("projectId"), Index("refundOfTransactionId"),
        Index(value = ["bookId", "occurredAt"]),
    ],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val type: TransactionType,
    val amountMinor: Long,
    val currency: String,
    val baseAmountMinor: Long,
    val exchangeRate: String,
    val categoryId: String? = null,
    val accountId: String,
    val destinationAccountId: String? = null,
    val destinationAmountMinor: Long? = null,
    val destinationCurrency: String? = null,
    val memberId: String? = null,
    val merchantId: String? = null,
    val projectId: String? = null,
    val reimbursementStatus: ReimbursementStatus = ReimbursementStatus.NONE,
    val refundOfTransactionId: String? = null,
    val note: String = "",
    val occurredAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Long = 0,
    val deletedAt: Long? = null,
)

@Entity(tableName = "transaction_splits", indices = [Index("transactionId"), Index("bookId"), Index("categoryId")])
data class TransactionSplitEntity(
    @PrimaryKey val id: String,
    val transactionId: String,
    val bookId: String,
    val categoryId: String? = null,
    val amountMinor: Long,
    val baseAmountMinor: Long,
    val note: String = "",
    val updatedAt: Long,
    val version: Long = 0,
    val deletedAt: Long? = null,
)

@Entity(tableName = "postings", indices = [Index("transactionId"), Index("bookId"), Index("ledgerAccountId")])
data class PostingEntity(
    @PrimaryKey val id: String,
    val transactionId: String,
    val bookId: String,
    val ledgerAccountId: String,
    val amountMinor: Long,
    val currency: String,
    val baseAmountMinor: Long,
    val createdAt: Long,
    val version: Long = 0,
    val deletedAt: Long? = null,
)

@Entity(tableName = "tags", indices = [Index("bookId")])
data class TagEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val name: String,
    val colorArgb: Long,
    val updatedAt: Long,
    val version: Long = 0,
    val deletedAt: Long? = null,
)

@Entity(tableName = "transaction_tags", primaryKeys = ["transactionId", "tagId"], indices = [Index("tagId")])
data class TransactionTagEntity(
    val transactionId: String,
    val tagId: String,
    val bookId: String,
    val updatedAt: Long,
    val version: Long = 0,
    val deletedAt: Long? = null,
)

@Entity(tableName = "attachments", indices = [Index("bookId"), Index("transactionId"), Index("sha256")])
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val transactionId: String,
    val displayName: String,
    val mimeType: String,
    val localUri: String,
    val remoteKey: String? = null,
    val sha256: String,
    val sizeBytes: Long,
    val updatedAt: Long,
    val version: Long = 0,
    val deletedAt: Long? = null,
)

@Entity(tableName = "budgets", indices = [Index("bookId"), Index("categoryId")])
data class BudgetEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val name: String,
    val categoryId: String? = null,
    val period: String,
    val startAt: Long,
    val endAt: Long? = null,
    val amountMinor: Long,
    val currency: String,
    val rollover: Boolean,
    val alertThresholdPercent: Int = 80,
    val updatedAt: Long,
    val version: Long = 0,
    val deletedAt: Long? = null,
)

@Entity(tableName = "recurring_rules", indices = [Index("bookId"), Index("nextRunAt")])
data class RecurringRuleEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val name: String,
    val transactionTemplateJson: String,
    val recurrenceRule: String,
    val nextRunAt: Long,
    val enabled: Boolean,
    val updatedAt: Long,
    val version: Long = 0,
    val deletedAt: Long? = null,
)

@Entity(tableName = "installment_plans", indices = [Index("bookId"), Index("accountId")])
data class InstallmentPlanEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val accountId: String,
    val name: String,
    val totalAmountMinor: Long,
    val installmentCount: Int,
    val completedCount: Int,
    val firstDueAt: Long,
    val recurrenceRule: String,
    val updatedAt: Long,
    val version: Long = 0,
    val deletedAt: Long? = null,
)

@Entity(tableName = "saving_goals", indices = [Index("bookId")])
data class SavingGoalEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val name: String,
    val targetAmountMinor: Long,
    val currentAmountMinor: Long,
    val currency: String,
    val targetAt: Long? = null,
    val isWish: Boolean = false,
    val updatedAt: Long,
    val version: Long = 0,
    val deletedAt: Long? = null,
)

@Entity(tableName = "merchants", indices = [Index("bookId")])
data class MerchantEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val name: String,
    val updatedAt: Long,
    val version: Long = 0,
    val deletedAt: Long? = null,
)

@Entity(tableName = "projects", indices = [Index("bookId")])
data class ProjectEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val name: String,
    val updatedAt: Long,
    val version: Long = 0,
    val deletedAt: Long? = null,
)

@Entity(tableName = "saved_filters", indices = [Index("bookId")])
data class SavedFilterEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val name: String,
    val filterJson: String,
    val updatedAt: Long,
    val version: Long = 0,
    val deletedAt: Long? = null,
)

@Entity(tableName = "outbox_operations", indices = [Index("bookId"), Index("createdAt")])
data class OutboxOperationEntity(
    @PrimaryKey val operationId: String,
    val bookId: String,
    val entityType: SyncEntityType,
    val entityId: String,
    val operation: SyncOperationType,
    val baseVersion: Long,
    val changedFieldsJson: String,
    val payloadJson: String,
    val clientModifiedAt: Long,
    val createdAt: Long,
    val retryCount: Int = 0,
    val lastErrorCode: String? = null,
)

@Entity(tableName = "sync_cursors")
data class SyncCursorEntity(
    @PrimaryKey val bookId: String,
    val cursor: Long,
    val updatedAt: Long,
)

@Entity(tableName = "sync_conflicts", indices = [Index("bookId"), Index("entityId")])
data class SyncConflictEntity(
    @PrimaryKey val operationId: String,
    val bookId: String,
    val entityType: SyncEntityType,
    val entityId: String,
    val conflictingFieldsJson: String,
    val localPayloadJson: String,
    val serverPayloadJson: String,
    val serverVersion: Long,
    val localOperation: SyncOperationType,
    val serverDeleted: Boolean,
    val createdAt: Long,
)

@Entity(tableName = "audit_events", indices = [Index("bookId"), Index("occurredAt")])
data class AuditEventEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val actorId: String,
    val entityType: SyncEntityType,
    val entityId: String,
    val action: String,
    val changedFieldsJson: String,
    val occurredAt: Long,
    val version: Long = 0,
)
