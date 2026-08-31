package com.billrecord.ledger.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        requireNotNull(AppDatabase::class.java.canonicalName),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2PreservesExistingRowsAndAddsSplits() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """INSERT INTO books(id,name,baseCurrency,timezone,monthStartDay,colorArgb,createdAt,updatedAt,version,deletedAt)
                   VALUES ('book-1','旧账本','CNY','Asia/Shanghai',1,0,1,1,0,NULL)""",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).use { database ->
            database.query("SELECT name FROM books WHERE id='book-1'").use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getString(0) == "旧账本")
            }
            database.query("SELECT COUNT(*) FROM transaction_splits").use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getInt(0) == 0)
            }
        }
    }

    @Test
    fun migrate2To3PreservesLedgerAndAddsLocalAiTables() {
        helper.createDatabase(TEST_DB_V2, 2).apply {
            execSQL(
                """INSERT INTO books(id,name,baseCurrency,timezone,monthStartDay,colorArgb,createdAt,updatedAt,version,deletedAt)
                   VALUES ('book-ai','AI 迁移','CNY','Asia/Shanghai',1,0,1,1,0,NULL)""",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB_V2, 3, true, MIGRATION_2_3).use { database ->
            database.query("SELECT name FROM books WHERE id='book-ai'").use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getString(0) == "AI 迁移")
            }
            listOf("ai_provider_profiles", "ai_conversations", "ai_messages").forEach { table ->
                database.query("SELECT COUNT(*) FROM `$table`").use { cursor -> check(cursor.moveToFirst() && cursor.getInt(0) == 0) }
            }
        }
    }

    private companion object {
        const val TEST_DB = "migration-test"
        const val TEST_DB_V2 = "migration-test-v2"
    }
}
