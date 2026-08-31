package com.billrecord.ledger.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billrecord.ledger.data.local.AccountEntity
import com.billrecord.ledger.data.local.AppDatabase
import com.billrecord.ledger.data.local.BookEntity
import com.billrecord.ledger.data.local.CategoryEntity
import com.billrecord.ledger.data.local.MembershipEntity
import com.billrecord.shared.AccountType
import com.billrecord.shared.BookRole
import com.billrecord.shared.TransactionType
import com.billrecord.ledger.ai.AiDataRepository
import com.billrecord.ledger.ai.FinanceGroupBy
import com.billrecord.ledger.ai.FinanceMetric
import com.billrecord.ledger.ai.FinanceQueryPlan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountingAcceptanceTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: LedgerRepository
    private lateinit var aiRepository: AiDataRepository

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val preferences = AppPreferences(context)
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        repository = LedgerRepository(
            database.ledgerDao(),
            preferences,
            json,
        )
        aiRepository = AiDataRepository(database.ledgerDao(), repository, preferences)
        val now = System.currentTimeMillis()
        database.ledgerDao().upsertBook(BookEntity(BOOK_ID, "账务验收", colorArgb = 0, createdAt = now, updatedAt = now))
        preferences.userId()?.let { userId ->
            database.ledgerDao().upsertMembership(MembershipEntity(BOOK_ID, userId, "验收用户", BookRole.OWNER, now))
        }
        database.ledgerDao().upsertAccount(AccountEntity(CASH_ID, BOOK_ID, "现金", AccountType.CASH, "CNY", icon = "cash", colorArgb = 0, sortOrder = 0, updatedAt = now))
        database.ledgerDao().upsertAccount(AccountEntity(BANK_ID, BOOK_ID, "银行卡", AccountType.DEBIT_CARD, "CNY", icon = "card", colorArgb = 0, sortOrder = 1, updatedAt = now))
        database.ledgerDao().upsertCategory(CategoryEntity(EXPENSE_CATEGORY_ID, BOOK_ID, name = "餐饮", type = TransactionType.EXPENSE, icon = "food", colorArgb = 0, sortOrder = 0, updatedAt = now))
        database.ledgerDao().upsertCategory(CategoryEntity(INCOME_CATEGORY_ID, BOOK_ID, name = "工资", type = TransactionType.INCOME, icon = "income", colorArgb = 0, sortOrder = 0, updatedAt = now))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun expenseIncomeRefundTransferAndBudgetRemainConsistent() {
        runBlocking {
            val now = System.currentTimeMillis()
            val expenseId = repository.createTransaction(RecordInput(BOOK_ID, TransactionType.EXPENSE, 10_000, CASH_ID, EXPENSE_CATEGORY_ID, occurredAt = now))
            val incomeId = repository.createTransaction(RecordInput(BOOK_ID, TransactionType.INCOME, 20_000, CASH_ID, INCOME_CATEGORY_ID, occurredAt = now))
            val refundId = repository.createTransaction(RecordInput(BOOK_ID, TransactionType.REFUND, 3_000, CASH_ID, EXPENSE_CATEGORY_ID, refundOfTransactionId = expenseId, occurredAt = now))
            val transferId = repository.createTransaction(
                RecordInput(
                    BOOK_ID,
                    TransactionType.TRANSFER,
                    5_000,
                    CASH_ID,
                    destinationAccountId = BANK_ID,
                    destinationAmountMinor = 5_000,
                    destinationCurrency = "CNY",
                    occurredAt = now,
                ),
            )

            listOf(expenseId, incomeId, refundId, transferId).forEach { transactionId ->
                assertEquals(0L, database.ledgerDao().getPostings(transactionId).sumOf { it.baseAmountMinor })
            }

            val range = repository.currentMonthRange()
            val summary = repository.observeSummary(BOOK_ID, range.first, range.second).first()
            assertEquals(20_000L, summary.incomeMinor)
            assertEquals(10_000L, summary.expenseMinor)
            assertEquals(3_000L, summary.refundMinor)

            val balances = repository.observeAccountBalances(BOOK_ID).first().associateBy { it.accountId }
            assertEquals(8_000L, balances.getValue(CASH_ID).postingBalanceMinor)
            assertEquals(5_000L, balances.getValue(BANK_ID).postingBalanceMinor)
            assertEquals(13_000L, balances.values.sumOf { it.postingBalanceMinor })

            val budgetId = repository.createBudget(BOOK_ID, "餐饮预算", 50_000, categoryId = EXPENSE_CATEGORY_ID)
            val usage = repository.observeBudgetUsage(BOOK_ID).first().associateBy { it.budgetId }
            assertEquals(7_000L, usage.getValue(budgetId).usedMinor)
        }
    }

    @Test
    fun analyticsFactsReconcileSplitsRefundsAndCategoryFilters() {
        runBlocking {
            val now = System.currentTimeMillis()
            database.ledgerDao().upsertCategory(
                CategoryEntity(SECOND_EXPENSE_CATEGORY_ID, BOOK_ID, name = "交通", type = TransactionType.EXPENSE, icon = "transport", colorArgb = 0, sortOrder = 1, updatedAt = now),
            )
            repository.createTransaction(
                RecordInput(
                    BOOK_ID,
                    TransactionType.EXPENSE,
                    10_000,
                    CASH_ID,
                    occurredAt = now,
                    splits = listOf(
                        RecordSplitInput(EXPENSE_CATEGORY_ID, 4_000),
                        RecordSplitInput(SECOND_EXPENSE_CATEGORY_ID, 6_000),
                    ),
                ),
            )
            repository.createTransaction(RecordInput(BOOK_ID, TransactionType.REFUND, 1_000, CASH_ID, EXPENSE_CATEGORY_ID, occurredAt = now))

            val (start, end) = repository.currentMonthRange()
            val all = repository.observeAnalyticsSummary(AnalyticsFilter(BOOK_ID, start, end)).first()
            assertEquals(10_000L, all.expenseMinor)
            assertEquals(1_000L, all.refundMinor)

            val categories = repository.observeAnalyticsCategories(AnalyticsFilter(BOOK_ID, start, end)).first().associateBy { it.categoryId }
            assertEquals(3_000L, categories.getValue(EXPENSE_CATEGORY_ID).amountMinor)
            assertEquals(6_000L, categories.getValue(SECOND_EXPENSE_CATEGORY_ID).amountMinor)

            val foodOnly = repository.observeAnalyticsSummary(
                AnalyticsFilter(BOOK_ID, start, end, categoryIds = setOf(EXPENSE_CATEGORY_ID)),
            ).first()
            assertEquals(4_000L, foodOnly.expenseMinor)
            assertEquals(1_000L, foodOnly.refundMinor)

            val aiResult = aiRepository.execute(
                FinanceQueryPlan(
                    bookId = BOOK_ID,
                    startAt = start,
                    endAt = end,
                    categoryIds = setOf(EXPENSE_CATEGORY_ID),
                    metric = FinanceMetric.EXPENSE,
                    groupBy = FinanceGroupBy.CATEGORY,
                ),
                aiRepository.catalog(BOOK_ID),
            )
            assertEquals(4_000L, aiResult.expenseMinor)
            assertEquals(1_000L, aiResult.refundMinor)
            assertEquals(3_000L, aiResult.rows.single().netExpenseMinor)
        }
    }

    @Test
    fun tagUsageRankingRenameAndLinkedDeletionRemainConsistent() = runBlocking {
        val now = System.currentTimeMillis()
        val frequent = repository.createTag(BOOK_ID, "常用")
        val other = repository.createTag(BOOK_ID, "备用")
        val frequentMerchant = repository.createMerchant(BOOK_ID, "常用商家")
        val otherMerchant = repository.createMerchant(BOOK_ID, "备用商家")
        val frequentProject = repository.createProject(BOOK_ID, "常用项目")
        val otherProject = repository.createProject(BOOK_ID, "备用项目")
        repeat(3) { index ->
            repository.createTransaction(
                RecordInput(
                    BOOK_ID,
                    TransactionType.EXPENSE,
                    1_000L + index,
                    CASH_ID,
                    EXPENSE_CATEGORY_ID,
                    occurredAt = now - index * 1_000L,
                    tagIds = setOf(frequent),
                    merchantId = frequentMerchant,
                    projectId = frequentProject,
                ),
            )
        }
        val ranking = repository.observeTagUsage(BOOK_ID).first()
        assertEquals(frequent, ranking.first().id)
        assertEquals(3L, ranking.first().usageCount)
        assertEquals(other, ranking.last().id)
        val merchantRanking = repository.observeMerchantUsage(BOOK_ID).first()
        assertEquals(frequentMerchant, merchantRanking.first().id)
        assertEquals(3L, merchantRanking.first().usageCount)
        assertEquals(otherMerchant, merchantRanking.last().id)
        val projectRanking = repository.observeProjectUsage(BOOK_ID).first()
        assertEquals(frequentProject, projectRanking.first().id)
        assertEquals(3L, projectRanking.first().usageCount)
        assertEquals(otherProject, projectRanking.last().id)

        repository.renameTag(frequent, "高频")
        assertEquals("高频", repository.getTags(BOOK_ID).first { it.id == frequent }.name)
        assertEquals(3, repository.deleteTag(frequent))
        assertTrue(repository.getTransactionTags(BOOK_ID).none { it.tagId == frequent })
        assertTrue(repository.getTags(BOOK_ID).none { it.id == frequent })
    }

    private companion object {
        const val BOOK_ID = "book-accounting-acceptance"
        const val CASH_ID = "cash-accounting-acceptance"
        const val BANK_ID = "bank-accounting-acceptance"
        const val EXPENSE_CATEGORY_ID = "expense-category-acceptance"
        const val INCOME_CATEGORY_ID = "income-category-acceptance"
        const val SECOND_EXPENSE_CATEGORY_ID = "second-expense-category-acceptance"
    }
}
