package com.billrecord.ledger.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billrecord.ledger.data.export.ExportException
import com.billrecord.ledger.data.export.ExportFormat
import com.billrecord.ledger.data.export.ExportManager
import com.billrecord.ledger.data.local.AccountEntity
import com.billrecord.ledger.data.local.AppDatabase
import com.billrecord.ledger.data.local.BookEntity
import com.billrecord.ledger.data.local.CategoryEntity
import com.billrecord.shared.AccountType
import com.billrecord.shared.TransactionFilter
import com.billrecord.shared.TransactionType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.math.RoundingMode
import java.util.zip.ZipFile
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class LargeDatasetAcceptanceTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: LedgerRepository
    private lateinit var exportManager: ExportManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        csvFile().delete()
        xlsxFile().delete()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        repository = LedgerRepository(
            database.ledgerDao(),
            AppPreferences(context),
            json,
        )
        exportManager = ExportManager(repository, database)
    }

    @After
    fun tearDown() {
        database.close()
        csvFile().delete()
        xlsxFile().delete()
    }

    @Test(timeout = 300_000)
    fun hundredThousandRowsSupportFilteringAndStreamingExports() {
        runBlocking {
            seedReferenceData()
            val expectedTotalMinor = insertTransactions(ROW_COUNT)

            val queryDuration = measureTimeMillis {
                val rows = repository.queryForExportPage(
                    TransactionFilter(
                        bookIds = setOf(BOOK_ID),
                        types = setOf(TransactionType.EXPENSE),
                        categoryIds = setOf(CATEGORY_ID),
                        accountIds = setOf(ACCOUNT_ID),
                        minimumAmountMinor = 1,
                        maximumAmountMinor = 10_000,
                        query = "needle",
                    ),
                    limit = 500,
                    offset = 0,
                )
                assertEquals(100, rows.size)
                assertTrue(rows.all { it.note.startsWith("needle-") })
            }
            assertTrue("组合查询耗时 ${queryDuration}ms", queryDuration < 10_000)

            val csv = csvFile()
            try {
                val csvDuration = measureTimeMillis {
                    csv.outputStream().buffered().use { output ->
                        exportManager.export(TransactionFilter(bookIds = setOf(BOOK_ID)), ExportFormat.CSV, output)
                    }
                }
                val csvTotals = readCsvTotals(csv)
                assertEquals(ROW_COUNT, csvTotals.first)
                assertEquals(expectedTotalMinor, csvTotals.second)
                assertTrue("CSV 导出耗时 ${csvDuration}ms", csvDuration < 120_000)
            } finally {
                csv.delete()
            }

            val xlsx = xlsxFile()
            try {
                val xlsxDuration = measureTimeMillis {
                    xlsx.outputStream().buffered().use { output ->
                        exportManager.export(TransactionFilter(bookIds = setOf(BOOK_ID)), ExportFormat.XLSX, output)
                    }
                }
                ZipFile(xlsx).use { zip ->
                    assertTrue(zip.getEntry("xl/worksheets/sheet1.xml") != null)
                    assertTrue(zip.getEntry("xl/worksheets/sheet2.xml") != null)
                }
                assertTrue(xlsx.length() > 1_000_000)
                assertTrue("XLSX 导出耗时 ${xlsxDuration}ms", xlsxDuration < 120_000)
            } finally {
                xlsx.delete()
            }

            val failure = runCatching {
                exportManager.export(
                    TransactionFilter(bookIds = setOf(BOOK_ID)),
                    ExportFormat.CSV,
                    FailingOutputStream(4_096),
                )
            }.exceptionOrNull()
            assertTrue(failure is ExportException)
            assertEquals(
                "导出失败：存储空间不足或目标位置不可写，请释放空间或选择其他目录后重试",
                failure?.message,
            )
        }
    }

    private fun csvFile() = File(context.cacheDir, "large-dataset-acceptance.csv")
    private fun xlsxFile() = File(context.cacheDir, "large-dataset-acceptance.xlsx")

    private suspend fun seedReferenceData() {
        val now = System.currentTimeMillis()
        database.ledgerDao().upsertBook(
            BookEntity(BOOK_ID, "性能验收账本", colorArgb = 0xFF2C6E63, createdAt = now, updatedAt = now),
        )
        database.ledgerDao().upsertAccount(
            AccountEntity(
                ACCOUNT_ID,
                BOOK_ID,
                "现金",
                AccountType.CASH,
                "CNY",
                icon = "payments",
                colorArgb = 0xFF2C6E63,
                sortOrder = 0,
                updatedAt = now,
            ),
        )
        database.ledgerDao().upsertCategory(
            CategoryEntity(
                CATEGORY_ID,
                BOOK_ID,
                name = "餐饮",
                type = TransactionType.EXPENSE,
                icon = "food",
                colorArgb = 0xFFB4553E,
                sortOrder = 0,
                updatedAt = now,
            ),
        )
    }

    private fun insertTransactions(count: Int): Long {
        var expectedTotal = 0L
        val sqlite = database.openHelper.writableDatabase
        sqlite.beginTransaction()
        try {
            val statement = sqlite.compileStatement(
                """
                INSERT INTO transactions(
                    id, bookId, type, amountMinor, currency, baseAmountMinor, exchangeRate,
                    categoryId, accountId, destinationAccountId, destinationAmountMinor,
                    destinationCurrency, memberId, merchantId, projectId, reimbursementStatus,
                    refundOfTransactionId, note, occurredAt, createdAt, updatedAt, version, deletedAt
                ) VALUES (?, ?, 'EXPENSE', ?, 'CNY', ?, '1', ?, ?, NULL, NULL, NULL, 'local',
                    NULL, NULL, 'NONE', NULL, ?, ?, ?, ?, 0, NULL)
                """.trimIndent(),
            )
            repeat(count) { index ->
                val amount = (index % 10_000 + 1).toLong()
                val occurredAt = BASE_TIME + index * 60_000L
                statement.clearBindings()
                statement.bindString(1, "tx-$index")
                statement.bindString(2, BOOK_ID)
                statement.bindLong(3, amount)
                statement.bindLong(4, amount)
                statement.bindString(5, CATEGORY_ID)
                statement.bindString(6, ACCOUNT_ID)
                statement.bindString(7, if (index % 1_000 == 0) "needle-$index" else "日常消费 $index")
                statement.bindLong(8, occurredAt)
                statement.bindLong(9, occurredAt)
                statement.bindLong(10, occurredAt)
                statement.executeInsert()
                expectedTotal += amount
            }
            sqlite.setTransactionSuccessful()
        } finally {
            sqlite.endTransaction()
        }
        return expectedTotal
    }

    private fun readCsvTotals(file: File): Pair<Int, Long> {
        var rows = 0
        var totalMinor = 0L
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            val header = requireNotNull(reader.readLine()).removePrefix("\uFEFF")
            assertTrue(header.startsWith("账本,账本ID,账单ID,时间,类型,金额"))
            while (true) {
                val line = reader.readLine() ?: break
                val amount = line.split(',')[5].toBigDecimal()
                totalMinor += amount.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
                rows++
            }
        }
        return rows to totalMinor
    }

    private companion object {
        const val ROW_COUNT = 100_000
        const val BOOK_ID = "book-large-acceptance"
        const val ACCOUNT_ID = "account-large-acceptance"
        const val CATEGORY_ID = "category-large-acceptance"
        const val BASE_TIME = 1_735_689_600_000L
    }
}

private class FailingOutputStream(private val byteLimit: Int) : OutputStream() {
    private var written = 0

    override fun write(value: Int) {
        ensureCapacity(1)
        written++
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        ensureCapacity(length)
        written += length
    }

    private fun ensureCapacity(nextBytes: Int) {
        if (written + nextBytes > byteLimit) throw IOException("simulated ENOSPC")
    }
}
