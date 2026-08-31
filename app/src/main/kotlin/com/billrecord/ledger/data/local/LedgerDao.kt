package com.billrecord.ledger.data.local

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import com.billrecord.shared.SyncEntityType
import kotlinx.coroutines.flow.Flow

data class MonthlySummary(
    val incomeMinor: Long,
    val expenseMinor: Long,
    val refundMinor: Long,
)

data class CategoryTotal(
    val categoryId: String?,
    val categoryName: String?,
    val amountMinor: Long,
)

data class AccountBalance(
    val accountId: String,
    val openingBalanceMinor: Long,
    val postingBalanceMinor: Long,
)

data class TransactionTagSummary(
    val transactionId: String,
    val tagNames: String,
    val tagIds: String,
)

data class TagUsage(
    val id: String,
    val bookId: String,
    val name: String,
    val colorArgb: Long,
    val updatedAt: Long,
    val version: Long,
    val deletedAt: Long?,
    val recentUsageCount: Long,
    val usageCount: Long,
    val lastUsedAt: Long,
)

data class DimensionUsage(
    val id: String,
    val name: String,
    val recentUsageCount: Long,
    val usageCount: Long,
    val lastUsedAt: Long,
)

data class PeriodSummary(
    val period: String,
    val incomeMinor: Long,
    val expenseMinor: Long,
    val refundMinor: Long,
)

data class DailyExpense(
    val day: String,
    val amountMinor: Long,
)

data class DimensionTotal(
    val id: String?,
    val name: String?,
    val amountMinor: Long,
)

data class BudgetUsage(
    val budgetId: String,
    val usedMinor: Long,
)

@Dao
interface LedgerDao {
    @Query("SELECT * FROM ai_provider_profiles ORDER BY isDefault DESC, updatedAt DESC")
    fun observeAiProviderProfiles(): Flow<List<AiProviderProfileEntity>>

    @Query("SELECT * FROM ai_provider_profiles WHERE enabled = 1 AND isDefault = 1 LIMIT 1")
    suspend fun getDefaultAiProviderProfile(): AiProviderProfileEntity?

    @Query("SELECT * FROM ai_provider_profiles WHERE id = :id LIMIT 1")
    suspend fun getAiProviderProfile(id: String): AiProviderProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAiProviderProfile(value: AiProviderProfileEntity)

    @Query("UPDATE ai_provider_profiles SET isDefault = CASE WHEN id = :id THEN 1 ELSE 0 END, updatedAt = :updatedAt")
    suspend fun setDefaultAiProviderProfile(id: String, updatedAt: Long)

    @Query("DELETE FROM ai_provider_profiles WHERE id = :id")
    suspend fun deleteAiProviderProfile(id: String)

    @Query("SELECT * FROM ai_conversations WHERE bookId = :bookId ORDER BY updatedAt DESC")
    fun observeAiConversations(bookId: String): Flow<List<AiConversationEntity>>

    @Query("SELECT * FROM ai_conversations WHERE id = :id LIMIT 1")
    suspend fun getAiConversation(id: String): AiConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAiConversation(value: AiConversationEntity)

    @Query("DELETE FROM ai_messages WHERE conversationId = :conversationId")
    suspend fun deleteAiMessages(conversationId: String)

    @Query("DELETE FROM ai_conversations WHERE id = :conversationId")
    suspend fun deleteAiConversationRow(conversationId: String)

    @Transaction
    suspend fun deleteAiConversation(conversationId: String) {
        deleteAiMessages(conversationId)
        deleteAiConversationRow(conversationId)
    }

    @Query("SELECT * FROM ai_messages WHERE conversationId = :conversationId ORDER BY createdAt, id")
    fun observeAiMessages(conversationId: String): Flow<List<AiMessageEntity>>

    @Query("SELECT * FROM ai_messages WHERE id = :id LIMIT 1")
    suspend fun getAiMessage(id: String): AiMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAiMessage(value: AiMessageEntity)

    @Query("DELETE FROM ai_messages")
    suspend fun clearAiMessages()

    @Query("DELETE FROM ai_conversations")
    suspend fun clearAiConversations()

    @Transaction
    suspend fun clearAiHistory() {
        clearAiMessages()
        clearAiConversations()
    }

    @RawQuery(observedEntities = [TransactionEntity::class, TransactionSplitEntity::class])
    fun observeAnalyticsSummary(query: SupportSQLiteQuery): Flow<MonthlySummary>

    @RawQuery(observedEntities = [TransactionEntity::class, TransactionSplitEntity::class, CategoryEntity::class])
    fun observeAnalyticsCategories(query: SupportSQLiteQuery): Flow<List<CategoryTotal>>

    @RawQuery(observedEntities = [TransactionEntity::class, TransactionSplitEntity::class])
    fun observeAnalyticsTrend(query: SupportSQLiteQuery): Flow<List<PeriodSummary>>

    @RawQuery(observedEntities = [TransactionEntity::class, TransactionSplitEntity::class])
    fun observeAnalyticsDaily(query: SupportSQLiteQuery): Flow<List<DailyExpense>>

    @RawQuery(observedEntities = [TransactionEntity::class, TransactionSplitEntity::class, MembershipEntity::class])
    fun observeAnalyticsMembers(query: SupportSQLiteQuery): Flow<List<DimensionTotal>>

    @RawQuery(observedEntities = [TransactionEntity::class, TransactionSplitEntity::class, TransactionTagEntity::class, TagEntity::class])
    fun observeAnalyticsTags(query: SupportSQLiteQuery): Flow<List<DimensionTotal>>

    @Query("SELECT * FROM books WHERE deletedAt IS NULL ORDER BY createdAt")
    fun observeBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books ORDER BY createdAt")
    suspend fun getAllBooksIncludingDeleted(): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :id AND deletedAt IS NULL")
    suspend fun getBook(id: String): BookEntity?

    @Query("SELECT * FROM books WHERE deletedAt IS NULL ORDER BY createdAt LIMIT 1")
    suspend fun getFirstBook(): BookEntity?

    @Query("SELECT COUNT(*) FROM books WHERE deletedAt IS NULL")
    suspend fun countBooks(): Int

    @Query("SELECT * FROM accounts WHERE bookId = :bookId AND deletedAt IS NULL AND archived = 0 ORDER BY sortOrder, name")
    fun observeAccounts(bookId: String): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE bookId = :bookId AND deletedAt IS NULL AND archived = 0 ORDER BY sortOrder, name")
    suspend fun getAccounts(bookId: String): List<AccountEntity>

    @Query("SELECT * FROM categories WHERE bookId = :bookId AND type = :type AND deletedAt IS NULL AND archived = 0 ORDER BY sortOrder, name")
    fun observeCategories(bookId: String, type: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE bookId = :bookId AND deletedAt IS NULL AND archived = 0 ORDER BY type, sortOrder, name")
    suspend fun getCategories(bookId: String): List<CategoryEntity>

    @Query("SELECT * FROM transactions WHERE bookId = :bookId AND deletedAt IS NULL ORDER BY occurredAt DESC LIMIT :limit")
    fun observeRecentTransactions(bookId: String, limit: Int = 20): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE bookId = :bookId AND deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeDeletedTransactions(bookId: String): Flow<List<TransactionEntity>>

    @RawQuery(observedEntities = [TransactionEntity::class])
    fun pageTransactions(query: SupportSQLiteQuery): PagingSource<Int, TransactionEntity>

    @RawQuery(observedEntities = [TransactionEntity::class])
    suspend fun queryTransactions(query: SupportSQLiteQuery): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getTransaction(id: String): TransactionEntity?

    @Query("SELECT * FROM postings WHERE transactionId = :transactionId")
    suspend fun getPostings(transactionId: String): List<PostingEntity>

    @Query("SELECT * FROM transaction_splits WHERE transactionId = :transactionId")
    suspend fun getTransactionSplits(transactionId: String): List<TransactionSplitEntity>

    @Query("SELECT * FROM transaction_splits WHERE deletedAt IS NULL AND transactionId IN (:transactionIds) ORDER BY transactionId, id")
    suspend fun getTransactionSplits(transactionIds: List<String>): List<TransactionSplitEntity>

    @Query("SELECT DISTINCT transactionId FROM transaction_splits WHERE bookId = :bookId AND deletedAt IS NULL")
    fun observeSplitTransactionIds(bookId: String): Flow<List<String>>

    @Query("SELECT * FROM transactions WHERE bookId = :bookId AND deletedAt IS NULL ORDER BY occurredAt")
    suspend fun getAllTransactions(bookId: String): List<TransactionEntity>

    @Query("SELECT COUNT(*) FROM transactions WHERE bookId = :bookId AND amountMinor = :amountMinor AND occurredAt = :occurredAt AND accountId = :accountId AND note = :note AND deletedAt IS NULL")
    suspend fun countMatchingTransactions(bookId: String, amountMinor: Long, occurredAt: Long, accountId: String, note: String): Int

    @Query(
        """
        SELECT
          COALESCE(SUM(CASE WHEN type = 'INCOME' THEN baseAmountMinor ELSE 0 END), 0) AS incomeMinor,
          COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN baseAmountMinor ELSE 0 END), 0) AS expenseMinor,
          COALESCE(SUM(CASE WHEN type = 'REFUND' THEN baseAmountMinor ELSE 0 END), 0) AS refundMinor
        FROM transactions
        WHERE bookId = :bookId AND deletedAt IS NULL AND occurredAt >= :startAt AND occurredAt < :endAt
        """,
    )
    fun observeSummary(bookId: String, startAt: Long, endAt: Long): Flow<MonthlySummary>

    @Query(
        """
        SELECT
          COALESCE(SUM(CASE WHEN type = 'INCOME' THEN baseAmountMinor ELSE 0 END), 0) AS incomeMinor,
          COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN baseAmountMinor ELSE 0 END), 0) AS expenseMinor,
          COALESCE(SUM(CASE WHEN type = 'REFUND' THEN baseAmountMinor ELSE 0 END), 0) AS refundMinor
        FROM transactions
        WHERE deletedAt IS NULL AND occurredAt >= :startAt AND occurredAt < :endAt
        """,
    )
    fun observeAllBooksSummary(startAt: Long, endAt: Long): Flow<MonthlySummary>

    @Query(
        """
        SELECT COALESCE(SUM(a.openingBalanceMinor + COALESCE(postingTotals.amountMinor, 0)), 0)
        FROM accounts a
        LEFT JOIN (
          SELECT ledgerAccountId, SUM(amountMinor) AS amountMinor FROM postings WHERE deletedAt IS NULL GROUP BY ledgerAccountId
        ) postingTotals ON postingTotals.ledgerAccountId = a.id
        WHERE a.deletedAt IS NULL AND a.archived = 0
        """,
    )
    fun observeAllBooksNetAssets(): Flow<Long>

    @Query(
        """
        SELECT totals.categoryId AS categoryId, c.name AS categoryName, COALESCE(SUM(totals.amountMinor), 0) AS amountMinor
        FROM (
          SELECT t.categoryId AS categoryId,
                 CASE WHEN t.type = 'REFUND' THEN -t.baseAmountMinor ELSE t.baseAmountMinor END AS amountMinor
          FROM transactions t
          WHERE t.bookId = :bookId AND t.type IN ('EXPENSE', 'REFUND') AND t.deletedAt IS NULL
            AND t.occurredAt >= :startAt AND t.occurredAt < :endAt
            AND NOT EXISTS (SELECT 1 FROM transaction_splits s WHERE s.transactionId = t.id AND s.deletedAt IS NULL)
          UNION ALL
          SELECT s.categoryId AS categoryId,
                 CASE WHEN t.type = 'REFUND' THEN -s.baseAmountMinor ELSE s.baseAmountMinor END AS amountMinor
          FROM transaction_splits s
          JOIN transactions t ON t.id = s.transactionId
          WHERE t.bookId = :bookId AND t.type IN ('EXPENSE', 'REFUND') AND t.deletedAt IS NULL AND s.deletedAt IS NULL
            AND t.occurredAt >= :startAt AND t.occurredAt < :endAt
        ) totals
        LEFT JOIN categories c ON c.id = totals.categoryId
        GROUP BY totals.categoryId, c.name
        ORDER BY amountMinor DESC
        """,
    )
    fun observeCategoryTotals(bookId: String, startAt: Long, endAt: Long): Flow<List<CategoryTotal>>

    @Query(
        """
        SELECT strftime('%Y-%m', occurredAt / 1000, 'unixepoch', '+8 hours') AS period,
          COALESCE(SUM(CASE WHEN type = 'INCOME' THEN baseAmountMinor ELSE 0 END), 0) AS incomeMinor,
          COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN baseAmountMinor ELSE 0 END), 0) AS expenseMinor,
          COALESCE(SUM(CASE WHEN type = 'REFUND' THEN baseAmountMinor ELSE 0 END), 0) AS refundMinor
        FROM transactions
        WHERE bookId = :bookId AND deletedAt IS NULL AND occurredAt >= :startAt AND occurredAt < :endAt
        GROUP BY period ORDER BY period
        """,
    )
    fun observeMonthlyTrend(bookId: String, startAt: Long, endAt: Long): Flow<List<PeriodSummary>>

    @Query(
        """
        SELECT strftime('%Y-%m-%d', occurredAt / 1000, 'unixepoch', '+8 hours') AS day,
          COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN baseAmountMinor WHEN type = 'REFUND' THEN -baseAmountMinor ELSE 0 END), 0) AS amountMinor
        FROM transactions
        WHERE bookId = :bookId AND deletedAt IS NULL AND occurredAt >= :startAt AND occurredAt < :endAt
        GROUP BY day ORDER BY day
        """,
    )
    fun observeDailyExpenses(bookId: String, startAt: Long, endAt: Long): Flow<List<DailyExpense>>

    @Query(
        """
        SELECT t.memberId AS id, COALESCE(m.displayName, '未指定成员') AS name,
          COALESCE(SUM(CASE WHEN t.type = 'EXPENSE' THEN t.baseAmountMinor WHEN t.type = 'REFUND' THEN -t.baseAmountMinor ELSE 0 END), 0) AS amountMinor
        FROM transactions t LEFT JOIN memberships m ON m.bookId = t.bookId AND m.userId = t.memberId
        WHERE t.bookId = :bookId AND t.deletedAt IS NULL AND t.occurredAt >= :startAt AND t.occurredAt < :endAt
        GROUP BY t.memberId, m.displayName ORDER BY amountMinor DESC
        """,
    )
    fun observeMemberTotals(bookId: String, startAt: Long, endAt: Long): Flow<List<DimensionTotal>>

    @Query(
        """
        SELECT tag.id AS id, tag.name AS name,
          COALESCE(SUM(CASE WHEN t.type = 'EXPENSE' THEN t.baseAmountMinor WHEN t.type = 'REFUND' THEN -t.baseAmountMinor ELSE 0 END), 0) AS amountMinor
        FROM transaction_tags tt
        JOIN tags tag ON tag.id = tt.tagId AND tag.deletedAt IS NULL
        JOIN transactions t ON t.id = tt.transactionId AND t.deletedAt IS NULL
        WHERE tt.bookId = :bookId AND tt.deletedAt IS NULL AND t.occurredAt >= :startAt AND t.occurredAt < :endAt
        GROUP BY tag.id, tag.name ORDER BY amountMinor DESC
        """,
    )
    fun observeTagTotals(bookId: String, startAt: Long, endAt: Long): Flow<List<DimensionTotal>>

    @Query(
        """
        SELECT a.id AS accountId, a.openingBalanceMinor AS openingBalanceMinor,
          COALESCE(SUM(p.amountMinor), 0) AS postingBalanceMinor
        FROM accounts a
        LEFT JOIN postings p ON p.ledgerAccountId = a.id AND p.deletedAt IS NULL
        WHERE a.bookId = :bookId AND a.deletedAt IS NULL AND a.archived = 0
        GROUP BY a.id, a.openingBalanceMinor, a.sortOrder
        ORDER BY a.sortOrder
        """,
    )
    fun observeAccountBalances(bookId: String): Flow<List<AccountBalance>>

    @Query("SELECT * FROM budgets WHERE bookId = :bookId AND deletedAt IS NULL ORDER BY startAt DESC")
    fun observeBudgets(bookId: String): Flow<List<BudgetEntity>>

    @Query(
        """
        SELECT b.id AS budgetId,
          CASE WHEN b.categoryId IS NULL THEN
            COALESCE((
              SELECT SUM(CASE WHEN t.type = 'EXPENSE' THEN t.baseAmountMinor WHEN t.type = 'REFUND' THEN -t.baseAmountMinor ELSE 0 END)
              FROM transactions t
              WHERE t.bookId = b.bookId AND t.deletedAt IS NULL AND t.occurredAt >= b.startAt AND (b.endAt IS NULL OR t.occurredAt < b.endAt)
            ), 0)
          ELSE
            COALESCE((
              SELECT SUM(CASE WHEN t.type = 'EXPENSE' THEN t.baseAmountMinor WHEN t.type = 'REFUND' THEN -t.baseAmountMinor ELSE 0 END)
              FROM transactions t
              WHERE t.bookId = b.bookId AND t.deletedAt IS NULL AND t.occurredAt >= b.startAt AND (b.endAt IS NULL OR t.occurredAt < b.endAt)
                AND t.categoryId = b.categoryId
                AND NOT EXISTS (SELECT 1 FROM transaction_splits ignored WHERE ignored.transactionId = t.id AND ignored.deletedAt IS NULL)
            ), 0) +
            COALESCE((
              SELECT SUM(CASE WHEN t.type = 'EXPENSE' THEN s.baseAmountMinor WHEN t.type = 'REFUND' THEN -s.baseAmountMinor ELSE 0 END)
              FROM transaction_splits s JOIN transactions t ON t.id = s.transactionId
              WHERE t.bookId = b.bookId AND t.deletedAt IS NULL AND s.deletedAt IS NULL AND s.categoryId = b.categoryId
                AND t.occurredAt >= b.startAt AND (b.endAt IS NULL OR t.occurredAt < b.endAt)
            ), 0)
          END AS usedMinor
        FROM budgets b WHERE b.bookId = :bookId AND b.deletedAt IS NULL
        """,
    )
    fun observeBudgetUsage(bookId: String): Flow<List<BudgetUsage>>

    @Query("SELECT * FROM budgets WHERE bookId = :bookId AND deletedAt IS NULL ORDER BY startAt DESC")
    suspend fun getBudgets(bookId: String): List<BudgetEntity>

    @Query("SELECT * FROM saving_goals WHERE bookId = :bookId AND deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeSavingGoals(bookId: String): Flow<List<SavingGoalEntity>>

    @Query("SELECT * FROM recurring_rules WHERE bookId = :bookId AND deletedAt IS NULL ORDER BY nextRunAt")
    fun observeRecurringRules(bookId: String): Flow<List<RecurringRuleEntity>>

    @Query("SELECT * FROM recurring_rules WHERE enabled = 1 AND deletedAt IS NULL AND nextRunAt <= :now ORDER BY nextRunAt LIMIT 100")
    suspend fun dueRecurringRules(now: Long): List<RecurringRuleEntity>

    @Query("SELECT * FROM installment_plans WHERE bookId = :bookId AND deletedAt IS NULL ORDER BY firstDueAt")
    fun observeInstallmentPlans(bookId: String): Flow<List<InstallmentPlanEntity>>

    @Query("SELECT * FROM installment_plans WHERE deletedAt IS NULL AND completedCount < installmentCount AND firstDueAt <= :now ORDER BY firstDueAt LIMIT 100")
    suspend fun dueInstallmentPlans(now: Long): List<InstallmentPlanEntity>

    @Query("SELECT * FROM installment_plans WHERE id = :id AND deletedAt IS NULL")
    suspend fun getInstallmentPlan(id: String): InstallmentPlanEntity?

    @Query("SELECT * FROM saved_filters WHERE bookId = :bookId AND deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeSavedFilters(bookId: String): Flow<List<SavedFilterEntity>>

    @Query("SELECT * FROM tags WHERE bookId = :bookId AND deletedAt IS NULL ORDER BY name")
    fun observeTags(bookId: String): Flow<List<TagEntity>>

    @Query(
        """
        SELECT t.*,
               COALESCE(SUM(CASE WHEN tx.occurredAt >= :recentSince THEN 1 ELSE 0 END), 0) AS recentUsageCount,
               COUNT(tx.id) AS usageCount,
               COALESCE(MAX(tx.occurredAt), 0) AS lastUsedAt
        FROM tags t
        LEFT JOIN transaction_tags tt ON tt.tagId = t.id AND tt.deletedAt IS NULL
        LEFT JOIN transactions tx ON tx.id = tt.transactionId AND tx.deletedAt IS NULL
        WHERE t.bookId = :bookId AND t.deletedAt IS NULL
        GROUP BY t.id
        ORDER BY recentUsageCount DESC, lastUsedAt DESC, usageCount DESC, t.name COLLATE LOCALIZED
        """,
    )
    fun observeTagUsage(bookId: String, recentSince: Long): Flow<List<TagUsage>>

    @Query("SELECT * FROM tags WHERE bookId = :bookId AND deletedAt IS NULL ORDER BY name")
    suspend fun getTags(bookId: String): List<TagEntity>

    @Query("SELECT * FROM transaction_tags WHERE bookId = :bookId AND deletedAt IS NULL")
    suspend fun getTransactionTags(bookId: String): List<TransactionTagEntity>

    @Query("SELECT * FROM tags WHERE id = :id LIMIT 1")
    suspend fun getTag(id: String): TagEntity?

    @Query("SELECT * FROM transaction_tags WHERE tagId = :tagId AND deletedAt IS NULL")
    suspend fun getActiveTransactionTagsForTag(tagId: String): List<TransactionTagEntity>

    @Query("SELECT * FROM transaction_tags WHERE transactionId = :transactionId")
    suspend fun getTransactionTagsForTransaction(transactionId: String): List<TransactionTagEntity>

    @Query("SELECT * FROM attachments WHERE transactionId = :transactionId")
    suspend fun getAttachmentsForTransaction(transactionId: String): List<AttachmentEntity>

    @Query(
        """
        SELECT tt.transactionId AS transactionId,
               GROUP_CONCAT(t.name, '|') AS tagNames,
               GROUP_CONCAT(t.id, '|') AS tagIds
        FROM transaction_tags tt
        JOIN tags t ON t.id = tt.tagId AND t.deletedAt IS NULL
        WHERE tt.deletedAt IS NULL AND tt.transactionId IN (:transactionIds)
        GROUP BY tt.transactionId
        """,
    )
    suspend fun getTransactionTagSummaries(transactionIds: List<String>): List<TransactionTagSummary>

    @Query("SELECT * FROM memberships WHERE bookId = :bookId AND deletedAt IS NULL ORDER BY displayName")
    suspend fun getMemberships(bookId: String): List<MembershipEntity>

    @Query("SELECT * FROM memberships WHERE bookId = :bookId AND deletedAt IS NULL ORDER BY displayName")
    fun observeMemberships(bookId: String): Flow<List<MembershipEntity>>

    @Query("SELECT * FROM memberships WHERE bookId = :bookId AND userId = :userId LIMIT 1")
    suspend fun getMembership(bookId: String, userId: String): MembershipEntity?

    @Query("SELECT * FROM memberships WHERE userId = :userId")
    suspend fun getMembershipsForUser(userId: String): List<MembershipEntity>

    @Query("SELECT * FROM memberships WHERE bookId = :bookId AND userId = :userId LIMIT 1")
    fun observeMembership(bookId: String, userId: String): Flow<MembershipEntity?>

    @Query("SELECT * FROM merchants WHERE bookId = :bookId AND deletedAt IS NULL")
    suspend fun getMerchants(bookId: String): List<MerchantEntity>

    @Query("SELECT * FROM merchants WHERE bookId = :bookId AND deletedAt IS NULL ORDER BY name")
    fun observeMerchants(bookId: String): Flow<List<MerchantEntity>>

    @Query(
        """
        SELECT m.id AS id,
               m.name AS name,
               COALESCE(SUM(CASE WHEN tx.occurredAt >= :recentSince THEN 1 ELSE 0 END), 0) AS recentUsageCount,
               COUNT(tx.id) AS usageCount,
               COALESCE(MAX(tx.occurredAt), 0) AS lastUsedAt
        FROM merchants m
        LEFT JOIN transactions tx ON tx.merchantId = m.id AND tx.deletedAt IS NULL
        WHERE m.bookId = :bookId AND m.deletedAt IS NULL
        GROUP BY m.id
        ORDER BY recentUsageCount DESC, lastUsedAt DESC, usageCount DESC, m.name COLLATE LOCALIZED
        """,
    )
    fun observeMerchantUsage(bookId: String, recentSince: Long): Flow<List<DimensionUsage>>

    @Query("SELECT * FROM projects WHERE bookId = :bookId AND deletedAt IS NULL")
    suspend fun getProjects(bookId: String): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE bookId = :bookId AND deletedAt IS NULL ORDER BY name")
    fun observeProjects(bookId: String): Flow<List<ProjectEntity>>

    @Query(
        """
        SELECT p.id AS id,
               p.name AS name,
               COALESCE(SUM(CASE WHEN tx.occurredAt >= :recentSince THEN 1 ELSE 0 END), 0) AS recentUsageCount,
               COUNT(tx.id) AS usageCount,
               COALESCE(MAX(tx.occurredAt), 0) AS lastUsedAt
        FROM projects p
        LEFT JOIN transactions tx ON tx.projectId = p.id AND tx.deletedAt IS NULL
        WHERE p.bookId = :bookId AND p.deletedAt IS NULL
        GROUP BY p.id
        ORDER BY recentUsageCount DESC, lastUsedAt DESC, usageCount DESC, p.name COLLATE LOCALIZED
        """,
    )
    fun observeProjectUsage(bookId: String, recentSince: Long): Flow<List<DimensionUsage>>

    @Query("SELECT * FROM attachments WHERE remoteKey IS NULL AND deletedAt IS NULL AND localUri != '' ORDER BY updatedAt LIMIT :limit")
    suspend fun pendingAttachmentUploads(limit: Int = 10): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE remoteKey IS NOT NULL AND deletedAt IS NULL AND localUri = '' ORDER BY updatedAt LIMIT :limit")
    suspend fun missingAttachmentDownloads(limit: Int = 10): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun getAttachment(id: String): AttachmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertBook(value: BookEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertBooks(values: List<BookEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertMembership(value: MembershipEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertMemberships(values: List<MembershipEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAccount(value: AccountEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAccounts(values: List<AccountEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertCategory(value: CategoryEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertCategories(values: List<CategoryEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTransaction(value: TransactionEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTransactionSplits(values: List<TransactionSplitEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPostings(values: List<PostingEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTag(value: TagEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTransactionTags(values: List<TransactionTagEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAttachment(value: AttachmentEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAttachments(values: List<AttachmentEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertBudget(value: BudgetEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertRecurringRule(value: RecurringRuleEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertInstallmentPlan(value: InstallmentPlanEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSavingGoal(value: SavingGoalEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertMerchant(value: MerchantEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertProject(value: ProjectEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSavedFilter(value: SavedFilterEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAuditEvent(value: AuditEventEntity)

    @Query("SELECT * FROM audit_events WHERE bookId = :bookId ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun recentAuditEvents(bookId: String, limit: Int = 100): List<AuditEventEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun enqueue(value: OutboxOperationEntity)

    @Query("SELECT * FROM outbox_operations ORDER BY createdAt LIMIT :limit")
    suspend fun pendingOperations(limit: Int = 100): List<OutboxOperationEntity>

    @Query("DELETE FROM outbox_operations WHERE operationId IN (:operationIds)")
    suspend fun acknowledgeOperations(operationIds: List<String>)

    @Query("UPDATE outbox_operations SET retryCount = retryCount + 1, lastErrorCode = :errorCode WHERE operationId IN (:operationIds)")
    suspend fun markOperationsFailed(operationIds: List<String>, errorCode: String)

    @Query("DELETE FROM outbox_operations WHERE bookId = :bookId AND entityType = :entityType AND entityId = :entityId AND operation = 'DELETE'")
    suspend fun removePendingDelete(bookId: String, entityType: SyncEntityType, entityId: String): Int

    @Query("SELECT * FROM sync_cursors")
    suspend fun getSyncCursors(): List<SyncCursorEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSyncCursors(values: List<SyncCursorEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertConflicts(values: List<SyncConflictEntity>)

    @Query("SELECT * FROM sync_conflicts ORDER BY createdAt DESC")
    fun observeConflicts(): Flow<List<SyncConflictEntity>>

    @Query("DELETE FROM sync_conflicts WHERE operationId = :operationId")
    suspend fun removeConflict(operationId: String)

    @Transaction
    suspend fun insertTransactionBundle(
        transaction: TransactionEntity,
        postings: List<PostingEntity>,
        outbox: List<OutboxOperationEntity>,
        auditEvent: AuditEventEntity,
        splits: List<TransactionSplitEntity> = emptyList(),
        transactionTags: List<TransactionTagEntity> = emptyList(),
        attachments: List<AttachmentEntity> = emptyList(),
    ) {
        upsertTransaction(transaction)
        upsertTransactionSplits(splits)
        upsertTransactionTags(transactionTags)
        upsertAttachments(attachments)
        upsertPostings(postings)
        outbox.forEach { enqueue(it) }
        upsertAuditEvent(auditEvent)
    }

    @Transaction
    suspend fun updateTagBundle(
        tag: TagEntity,
        transactionTags: List<TransactionTagEntity>,
        outbox: List<OutboxOperationEntity>,
        auditEvent: AuditEventEntity,
    ) {
        upsertTag(tag)
        if (transactionTags.isNotEmpty()) upsertTransactionTags(transactionTags)
        outbox.forEach { enqueue(it) }
        upsertAuditEvent(auditEvent)
    }
}
