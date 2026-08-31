package com.billrecord.ledger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        BookEntity::class,
        MembershipEntity::class,
        AccountEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
        TransactionSplitEntity::class,
        PostingEntity::class,
        TagEntity::class,
        TransactionTagEntity::class,
        AttachmentEntity::class,
        BudgetEntity::class,
        RecurringRuleEntity::class,
        InstallmentPlanEntity::class,
        SavingGoalEntity::class,
        MerchantEntity::class,
        ProjectEntity::class,
        SavedFilterEntity::class,
        OutboxOperationEntity::class,
        SyncCursorEntity::class,
        SyncConflictEntity::class,
        AuditEventEntity::class,
        AiProviderProfileEntity::class,
        AiConversationEntity::class,
        AiMessageEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao
}
