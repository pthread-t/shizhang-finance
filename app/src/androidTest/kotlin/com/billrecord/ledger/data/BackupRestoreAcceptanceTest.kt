package com.billrecord.ledger.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billrecord.ledger.data.backup.BackupManager
import com.billrecord.ledger.data.local.AccountEntity
import com.billrecord.ledger.data.local.AppDatabase
import com.billrecord.ledger.data.local.BookEntity
import com.billrecord.ledger.data.local.CategoryEntity
import com.billrecord.shared.AccountType
import com.billrecord.shared.TransactionType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackupRestoreAcceptanceTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: LedgerRepository
    private lateinit var backupManager: BackupManager
    private lateinit var attachmentFile: File

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        repository = LedgerRepository(
            database.ledgerDao(),
            AppPreferences(context),
            json,
        )
        backupManager = BackupManager(context, database, json)

        val now = System.currentTimeMillis()
        database.ledgerDao().upsertBook(BookEntity(BOOK_ID, "备份验收", colorArgb = 0, createdAt = now, updatedAt = now))
        database.ledgerDao().upsertAccount(AccountEntity(ACCOUNT_ID, BOOK_ID, "现金", AccountType.CASH, "CNY", icon = "cash", colorArgb = 0, sortOrder = 0, updatedAt = now))
        database.ledgerDao().upsertCategory(CategoryEntity(CATEGORY_ID, BOOK_ID, name = "餐饮", type = TransactionType.EXPENSE, icon = "food", colorArgb = 0, sortOrder = 0, updatedAt = now))
        repository.createTransaction(RecordInput(BOOK_ID, TransactionType.EXPENSE, 1_234, ACCOUNT_ID, CATEGORY_ID, note = "backup acceptance", occurredAt = now))

        attachmentFile = File(context.filesDir, "attachments/$BOOK_ID/backup-acceptance.bin")
        attachmentFile.parentFile?.mkdirs()
        attachmentFile.writeBytes(ORIGINAL_ATTACHMENT)
    }

    @After
    fun tearDown() {
        database.close()
        attachmentFile.delete()
    }

    @Test
    fun encryptedBackupPreviewsAndRestoresLogicalDataAndAttachments() {
        runBlocking {
            val output = ByteArrayOutputStream()
            val manifest = backupManager.create(PASSWORD.toCharArray(), output)
            assertEquals(1, manifest.attachmentCount)
            assertEquals(1L, manifest.tableCounts.getValue("transactions"))

            database.clearAllTables()
            attachmentFile.writeBytes(MODIFIED_ATTACHMENT)

            val preview = backupManager.preview(PASSWORD.toCharArray(), ByteArrayInputStream(output.toByteArray()))
            assertTrue(preview.changedTables > 0)

            val restored = backupManager.restore(
                PASSWORD.toCharArray(),
                ByteArrayInputStream(output.toByteArray()),
                replace = true,
            )
            assertEquals(manifest.tableCounts, restored.tableCounts)
            assertNotNull(database.ledgerDao().getBook(BOOK_ID))
            assertEquals(1, database.ledgerDao().getAllTransactions(BOOK_ID).size)
            assertArrayEquals(ORIGINAL_ATTACHMENT, attachmentFile.readBytes())
        }
    }

    private companion object {
        const val BOOK_ID = "book-backup-acceptance"
        const val ACCOUNT_ID = "account-backup-acceptance"
        const val CATEGORY_ID = "category-backup-acceptance"
        const val PASSWORD = "acceptance-password"
        val ORIGINAL_ATTACHMENT = "original attachment".toByteArray()
        val MODIFIED_ATTACHMENT = "modified attachment".toByteArray()
    }
}
