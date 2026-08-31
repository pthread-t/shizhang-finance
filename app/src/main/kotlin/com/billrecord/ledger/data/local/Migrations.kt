package com.billrecord.ledger.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS `transaction_splits` (
                `id` TEXT NOT NULL,
                `transactionId` TEXT NOT NULL,
                `bookId` TEXT NOT NULL,
                `categoryId` TEXT,
                `amountMinor` INTEGER NOT NULL,
                `baseAmountMinor` INTEGER NOT NULL,
                `note` TEXT NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `version` INTEGER NOT NULL,
                `deletedAt` INTEGER,
                PRIMARY KEY(`id`)
            )""",
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_transaction_splits_transactionId` ON `transaction_splits` (`transactionId`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_transaction_splits_bookId` ON `transaction_splits` (`bookId`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_transaction_splits_categoryId` ON `transaction_splits` (`categoryId`)")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS `ai_provider_profiles` (
                `id` TEXT NOT NULL, `displayName` TEXT NOT NULL, `providerKind` TEXT NOT NULL,
                `baseUrl` TEXT NOT NULL, `model` TEXT NOT NULL, `enabled` INTEGER NOT NULL,
                `isDefault` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )""",
        )
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS `ai_conversations` (
                `id` TEXT NOT NULL, `bookId` TEXT NOT NULL, `title` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`)
            )""",
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_conversations_bookId` ON `ai_conversations` (`bookId`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_conversations_updatedAt` ON `ai_conversations` (`updatedAt`)")
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS `ai_messages` (
                `id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `role` TEXT NOT NULL,
                `text` TEXT NOT NULL, `status` TEXT NOT NULL, `providerProfileId` TEXT,
                `model` TEXT, `queryPlanJson` TEXT, `aggregateJson` TEXT, `chartJson` TEXT,
                `errorMessage` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`)
            )""",
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_messages_conversationId` ON `ai_messages` (`conversationId`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_messages_createdAt` ON `ai_messages` (`createdAt`)")
    }
}
