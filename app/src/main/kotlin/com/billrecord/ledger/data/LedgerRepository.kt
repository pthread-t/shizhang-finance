package com.billrecord.ledger.data

import androidx.sqlite.db.SimpleSQLiteQuery
import com.billrecord.ledger.data.local.AccountBalance
import com.billrecord.ledger.data.local.AccountEntity
import com.billrecord.ledger.data.local.AuditEventEntity
import com.billrecord.ledger.data.local.BookEntity
import com.billrecord.ledger.data.local.BudgetEntity
import com.billrecord.ledger.data.local.CategoryEntity
import com.billrecord.ledger.data.local.CategoryTotal
import com.billrecord.ledger.data.local.LedgerDao
import com.billrecord.ledger.data.local.InstallmentPlanEntity
import com.billrecord.ledger.data.local.MembershipEntity
import com.billrecord.ledger.data.local.MonthlySummary
import com.billrecord.ledger.data.local.TagEntity
import com.billrecord.ledger.data.local.MerchantEntity
import com.billrecord.ledger.data.local.ProjectEntity
import com.billrecord.ledger.data.local.TransactionTagEntity
import com.billrecord.ledger.data.local.AttachmentEntity
import com.billrecord.ledger.data.local.OutboxOperationEntity
import com.billrecord.ledger.data.local.PostingEntity
import com.billrecord.ledger.data.local.SavingGoalEntity
import com.billrecord.ledger.data.local.SavedFilterEntity
import com.billrecord.ledger.data.local.TransactionEntity
import com.billrecord.ledger.data.local.TransactionSplitEntity
import com.billrecord.shared.AccountType
import com.billrecord.shared.BookRole
import com.billrecord.shared.LedgerDraft
import com.billrecord.shared.LedgerEngine
import com.billrecord.shared.LedgerSplitDraft
import com.billrecord.shared.ReimbursementStatus
import com.billrecord.shared.SyncEntityType
import com.billrecord.shared.SyncOperationType
import com.billrecord.shared.TransactionFilter
import com.billrecord.shared.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

data class RecordInput(
    val bookId: String,
    val type: TransactionType,
    val amountMinor: Long,
    val accountId: String,
    val categoryId: String? = null,
    val destinationAccountId: String? = null,
    val destinationAmountMinor: Long? = null,
    val currency: String = "CNY",
    val destinationCurrency: String? = null,
    val exchangeRate: String = "1",
    val baseAmountMinor: Long = amountMinor,
    val note: String = "",
    val occurredAt: Long = System.currentTimeMillis(),
    val reimbursementStatus: ReimbursementStatus = ReimbursementStatus.NONE,
    val refundOfTransactionId: String? = null,
    val splits: List<RecordSplitInput> = emptyList(),
    val tagIds: Set<String> = emptySet(),
    val merchantId: String? = null,
    val projectId: String? = null,
)

data class RecordSplitInput(
    val categoryId: String?,
    val amountMinor: Long,
    val baseAmountMinor: Long = amountMinor,
    val note: String = "",
)

data class EditTransactionInput(
    val transactionId: String,
    val amountMinor: Long,
    val accountId: String,
    val categoryId: String?,
    val note: String,
    val occurredAt: Long,
)

data class AnalyticsFilter(
    val bookId: String,
    val startAt: Long,
    val endAt: Long,
    val accountIds: Set<String> = emptySet(),
    val categoryIds: Set<String> = emptySet(),
    val memberIds: Set<String> = emptySet(),
)

@Serializable
data class RecurringTemplate(
    val type: TransactionType,
    val amountMinor: Long,
    val accountId: String,
    val categoryId: String? = null,
    val note: String = "",
)

@Singleton
class LedgerRepository @Inject constructor(
    private val dao: LedgerDao,
    private val preferences: AppPreferences,
    private val json: Json,
) {
    fun observeBooks(): Flow<List<BookEntity>> = dao.observeBooks()
    fun observeSelectedBookId(): Flow<String?> = preferences.selectedBookId
    fun observeAccounts(bookId: String): Flow<List<AccountEntity>> = dao.observeAccounts(bookId)
    fun observeCategories(bookId: String, type: TransactionType): Flow<List<CategoryEntity>> =
        dao.observeCategories(bookId, type.name)
    fun observeRecentTransactions(bookId: String): Flow<List<TransactionEntity>> = dao.observeRecentTransactions(bookId)
    fun observeDeletedTransactions(bookId: String): Flow<List<TransactionEntity>> = dao.observeDeletedTransactions(bookId)
    fun observeSummary(bookId: String, startAt: Long, endAt: Long): Flow<MonthlySummary> =
        dao.observeSummary(bookId, startAt, endAt)
    fun observeAllBooksSummary(startAt: Long, endAt: Long) = dao.observeAllBooksSummary(startAt, endAt)
    fun observeAllBooksNetAssets() = dao.observeAllBooksNetAssets()
    fun observeCategoryTotals(bookId: String, startAt: Long, endAt: Long): Flow<List<CategoryTotal>> =
        dao.observeCategoryTotals(bookId, startAt, endAt)
    fun observeMonthlyTrend(bookId: String, startAt: Long, endAt: Long) = dao.observeMonthlyTrend(bookId, startAt, endAt)
    fun observeDailyExpenses(bookId: String, startAt: Long, endAt: Long) = dao.observeDailyExpenses(bookId, startAt, endAt)
    fun observeMemberTotals(bookId: String, startAt: Long, endAt: Long) = dao.observeMemberTotals(bookId, startAt, endAt)
    fun observeTagTotals(bookId: String, startAt: Long, endAt: Long) = dao.observeTagTotals(bookId, startAt, endAt)
    fun observeAnalyticsSummary(filter: AnalyticsFilter) = dao.observeAnalyticsSummary(analyticsQuery(filter, AnalyticsMetric.SUMMARY))
    fun observeAnalyticsCategories(filter: AnalyticsFilter) = dao.observeAnalyticsCategories(analyticsQuery(filter, AnalyticsMetric.CATEGORIES))
    fun observeAnalyticsTrend(filter: AnalyticsFilter) = dao.observeAnalyticsTrend(analyticsQuery(filter, AnalyticsMetric.TREND))
    fun observeAnalyticsDaily(filter: AnalyticsFilter) = dao.observeAnalyticsDaily(analyticsQuery(filter, AnalyticsMetric.DAILY))
    fun observeAnalyticsMembers(filter: AnalyticsFilter) = dao.observeAnalyticsMembers(analyticsQuery(filter, AnalyticsMetric.MEMBERS))
    fun observeAnalyticsTags(filter: AnalyticsFilter) = dao.observeAnalyticsTags(analyticsQuery(filter, AnalyticsMetric.TAGS))
    fun observeAccountBalances(bookId: String): Flow<List<AccountBalance>> = dao.observeAccountBalances(bookId)
    fun observeBudgets(bookId: String): Flow<List<BudgetEntity>> = dao.observeBudgets(bookId)
    fun observeBudgetUsage(bookId: String) = dao.observeBudgetUsage(bookId)
    fun observeSavingGoals(bookId: String): Flow<List<SavingGoalEntity>> = dao.observeSavingGoals(bookId)
    fun observeRecurringRules(bookId: String): Flow<List<com.billrecord.ledger.data.local.RecurringRuleEntity>> = dao.observeRecurringRules(bookId)
    fun observeInstallmentPlans(bookId: String): Flow<List<com.billrecord.ledger.data.local.InstallmentPlanEntity>> = dao.observeInstallmentPlans(bookId)
    fun observeSavedFilters(bookId: String): Flow<List<SavedFilterEntity>> = dao.observeSavedFilters(bookId)
    fun observeTags(bookId: String) = dao.observeTags(bookId)
    fun observeTagUsage(bookId: String) = dao.observeTagUsage(bookId, System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000)
    fun observeMemberships(bookId: String) = dao.observeMemberships(bookId)
    fun observeMerchants(bookId: String) = dao.observeMerchants(bookId)
    fun observeProjects(bookId: String) = dao.observeProjects(bookId)
    fun observeMerchantUsage(bookId: String) = dao.observeMerchantUsage(bookId, System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000)
    fun observeProjectUsage(bookId: String) = dao.observeProjectUsage(bookId, System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000)
    fun observeSplitTransactionIds(bookId: String) = dao.observeSplitTransactionIds(bookId)
    fun observeCanEdit(): Flow<Boolean> = combine(preferences.selectedBookId, preferences.sessionUserId) { bookId, userId -> bookId to userId }
        .flatMapLatest { (bookId, userId) ->
            if (bookId == null || userId == null) flowOf(true)
            else dao.observeMembership(bookId, userId).map { membership ->
                membership == null || membership.deletedAt == null && membership.role != BookRole.VIEWER
            }
        }

    suspend fun initialize(): String {
        dao.getFirstBook()?.let { existing ->
            if (preferences.selectedBookId.first() == null) preferences.selectBook(existing.id)
            return existing.id
        }

        val now = System.currentTimeMillis()
        val bookId = id()
        val book = BookEntity(
            id = bookId,
            name = "日常账本",
            colorArgb = 0xFF2C6E63,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsertBook(book)
        dao.upsertMembership(MembershipEntity(bookId, "local", "我", BookRole.OWNER, now))

        val accounts = listOf(
            AccountEntity(id(), bookId, "现金", AccountType.CASH, "CNY", icon = "payments", colorArgb = 0xFFB4553E, sortOrder = 0, updatedAt = now),
            AccountEntity(id(), bookId, "微信", AccountType.E_WALLET, "CNY", icon = "chat", colorArgb = 0xFF2C6E63, sortOrder = 1, updatedAt = now),
            AccountEntity(id(), bookId, "支付宝", AccountType.E_WALLET, "CNY", icon = "wallet", colorArgb = 0xFF3278B8, sortOrder = 2, updatedAt = now),
            AccountEntity(id(), bookId, "银行卡", AccountType.DEBIT_CARD, "CNY", icon = "card", colorArgb = 0xFFC49A4A, sortOrder = 3, updatedAt = now),
            AccountEntity(id(), bookId, "信用卡", AccountType.CREDIT_CARD, "CNY", icon = "credit", colorArgb = 0xFF73575C, sortOrder = 4, updatedAt = now),
        )
        dao.upsertAccounts(accounts)

        val expenseNames = listOf("餐饮", "交通", "购物", "居住", "医疗", "娱乐", "人情", "其他")
        val incomeNames = listOf("工资", "奖金", "投资", "兼职", "其他收入")
        val categories = expenseNames.mapIndexed { index, name ->
            CategoryEntity(id(), bookId, name = name, type = TransactionType.EXPENSE, icon = expenseIcon(name), colorArgb = categoryColor(index), sortOrder = index, updatedAt = now)
        } + incomeNames.mapIndexed { index, name ->
            CategoryEntity(id(), bookId, name = name, type = TransactionType.INCOME, icon = "income", colorArgb = categoryColor(index + 3), sortOrder = index, updatedAt = now)
        }
        dao.upsertCategories(categories)

        enqueueSeed(book, accounts, categories, now)
        preferences.selectBook(bookId)
        return bookId
    }

    suspend fun selectBook(bookId: String) = preferences.selectBook(bookId)

    suspend fun getBook(bookId: String) = dao.getBook(bookId)
    suspend fun getBooks() = dao.getAllBooksIncludingDeleted().filter { it.deletedAt == null }
    suspend fun getTransaction(id: String) = dao.getTransaction(id)?.takeIf { it.deletedAt == null }
    suspend fun getAccounts(bookId: String) = dao.getAccounts(bookId)
    suspend fun getCategories(bookId: String) = dao.getCategories(bookId)
    suspend fun getTags(bookId: String) = dao.getTags(bookId)
    suspend fun getTransactionTags(bookId: String) = dao.getTransactionTags(bookId)
    suspend fun getTransactionTagSummaries(transactionIds: List<String>) =
        if (transactionIds.isEmpty()) emptyList() else dao.getTransactionTagSummaries(transactionIds)
    suspend fun getTransactionSplits(transactionIds: List<String>) =
        if (transactionIds.isEmpty()) emptyList() else dao.getTransactionSplits(transactionIds)
    suspend fun getTransactionSplits(transactionId: String) = dao.getTransactionSplits(transactionId).filter { it.deletedAt == null }
    suspend fun getTransactionTagsForTransaction(transactionId: String) = dao.getTransactionTagsForTransaction(transactionId).filter { it.deletedAt == null }
    suspend fun getAttachmentsForTransaction(transactionId: String) = dao.getAttachmentsForTransaction(transactionId).filter { it.deletedAt == null }
    suspend fun getMemberships(bookId: String) = dao.getMemberships(bookId)
    suspend fun getBudgets(bookId: String) = dao.getBudgets(bookId)
    suspend fun getMerchants(bookId: String) = dao.getMerchants(bookId)
    suspend fun getProjects(bookId: String) = dao.getProjects(bookId)

    suspend fun createTransaction(input: RecordInput): String {
        requireCanEdit(input.bookId)
        require(input.amountMinor > 0) { "金额必须大于 0" }
        val now = System.currentTimeMillis()
        val transactionId = id()
        val transaction = TransactionEntity(
            id = transactionId,
            bookId = input.bookId,
            type = input.type,
            amountMinor = input.amountMinor,
            currency = input.currency,
            baseAmountMinor = input.baseAmountMinor,
            exchangeRate = input.exchangeRate,
            categoryId = if (input.splits.isEmpty()) input.categoryId else null,
            accountId = input.accountId,
            destinationAccountId = input.destinationAccountId,
            destinationAmountMinor = input.destinationAmountMinor,
            destinationCurrency = input.destinationCurrency,
            memberId = preferences.userId() ?: "local",
            merchantId = input.merchantId,
            projectId = input.projectId,
            reimbursementStatus = input.reimbursementStatus,
            refundOfTransactionId = input.refundOfTransactionId,
            note = input.note.trim(),
            occurredAt = input.occurredAt,
            createdAt = now,
            updatedAt = now,
        )
        val postings = LedgerEngine.createPostings(
            LedgerDraft(
                transactionId = transactionId,
                type = input.type,
                accountId = input.accountId,
                destinationAccountId = input.destinationAccountId,
                categoryId = input.categoryId,
                amountMinor = input.amountMinor,
                currency = input.currency,
                baseAmountMinor = input.baseAmountMinor,
                destinationAmountMinor = input.destinationAmountMinor,
                destinationCurrency = input.destinationCurrency,
                splits = input.splits.map { LedgerSplitDraft(it.categoryId, it.amountMinor, it.baseAmountMinor) },
            ),
        ).map { posting ->
            PostingEntity(
                id = id(),
                transactionId = transactionId,
                bookId = input.bookId,
                ledgerAccountId = posting.ledgerAccountId,
                amountMinor = posting.amountMinor,
                currency = posting.currency,
                baseAmountMinor = posting.baseAmountMinor,
                createdAt = now,
            )
        }
        val splits = input.splits.map { split ->
            TransactionSplitEntity(
                id = id(),
                transactionId = transactionId,
                bookId = input.bookId,
                categoryId = split.categoryId,
                amountMinor = split.amountMinor,
                baseAmountMinor = split.baseAmountMinor,
                note = split.note.trim(),
                updatedAt = now,
            )
        }
        val transactionTags = input.tagIds.map { tagId -> TransactionTagEntity(transactionId, tagId, input.bookId, now) }
        val outbox = mutableListOf(toOutbox(transaction, now))
        outbox += postings.map { toOutbox(it, now) }
        outbox += splits.map { toOutbox(it, now) }
        outbox += transactionTags.map { toOutbox(it, now) }
        dao.insertTransactionBundle(
            transaction,
            postings,
            outbox,
            AuditEventEntity(id(), input.bookId, preferences.userId() ?: "local", SyncEntityType.TRANSACTION, transactionId, "CREATE", "[]", now),
            splits,
            transactionTags,
        )
        return transactionId
    }

    suspend fun updateTransaction(input: EditTransactionInput) {
        val existing = requireNotNull(dao.getTransaction(input.transactionId)) { "账单不存在" }
        requireCanEdit(existing.bookId)
        require(existing.deletedAt == null) { "回收站中的账单需要先恢复" }
        require(input.amountMinor > 0) { "金额必须大于 0" }
        val splits = dao.getTransactionSplits(existing.id).filter { it.deletedAt == null }
        if (splits.isNotEmpty()) require(input.amountMinor == existing.amountMinor) { "拆分账单请保持总金额不变" }
        if (existing.type == TransactionType.TRANSFER) {
            require(input.amountMinor == existing.amountMinor && input.accountId == existing.accountId) { "转账的金额与账户请删除后重新创建" }
        }
        val effectiveCategory = if (splits.isEmpty() && existing.type !in setOf(TransactionType.TRANSFER, TransactionType.ADJUSTMENT)) input.categoryId else existing.categoryId
        val rate = existing.exchangeRate.toBigDecimalOrNull() ?: java.math.BigDecimal.ONE
        val baseAmountMinor = java.math.BigDecimal(input.amountMinor).multiply(rate).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
        val now = System.currentTimeMillis()
        val updated = existing.copy(
            amountMinor = input.amountMinor,
            baseAmountMinor = if (splits.isEmpty()) baseAmountMinor else existing.baseAmountMinor,
            accountId = input.accountId,
            categoryId = effectiveCategory,
            note = input.note.trim().take(200),
            occurredAt = input.occurredAt,
            updatedAt = now,
        )
        val payload = buildJsonObject {
            if (updated.amountMinor != existing.amountMinor) { put("amountMinor", updated.amountMinor); put("baseAmountMinor", updated.baseAmountMinor) }
            if (updated.accountId != existing.accountId) put("accountId", updated.accountId)
            if (updated.categoryId != existing.categoryId) put("categoryId", updated.categoryId)
            if (updated.note != existing.note) put("note", updated.note)
            if (updated.occurredAt != existing.occurredAt) put("occurredAt", updated.occurredAt)
        }
        if (payload.isEmpty()) return
        val financialChanged = updated.amountMinor != existing.amountMinor || updated.accountId != existing.accountId || updated.categoryId != existing.categoryId
        val postingChanges = if (!financialChanged) emptyList() else {
            val old = dao.getPostings(existing.id).filter { it.deletedAt == null }
            val deleted = old.map { it.copy(deletedAt = now) }
            val replacements = LedgerEngine.createPostings(
                LedgerDraft(
                    transactionId = updated.id, type = updated.type, accountId = updated.accountId, destinationAccountId = updated.destinationAccountId,
                    categoryId = updated.categoryId, amountMinor = updated.amountMinor, currency = updated.currency, baseAmountMinor = updated.baseAmountMinor,
                    destinationAmountMinor = updated.destinationAmountMinor, destinationCurrency = updated.destinationCurrency,
                    splits = splits.map { LedgerSplitDraft(it.categoryId, it.amountMinor, it.baseAmountMinor) },
                ),
            ).map { posting -> PostingEntity(id(), updated.id, updated.bookId, posting.ledgerAccountId, posting.amountMinor, posting.currency, posting.baseAmountMinor, now) }
            deleted + replacements
        }
        val operations = mutableListOf(
            outbox(updated.bookId, SyncEntityType.TRANSACTION, updated.id, payload, now, updated.version),
        )
        postingChanges.filter { it.deletedAt != null }.forEach { operations += toDeleteOutbox(it.bookId, SyncEntityType.POSTING, it.id, it.version, now) }
        postingChanges.filter { it.deletedAt == null }.forEach { operations += toOutbox(it, now) }
        dao.insertTransactionBundle(
            updated, postingChanges, operations,
            AuditEventEntity(id(), updated.bookId, preferences.userId() ?: "local", SyncEntityType.TRANSACTION, updated.id, "UPDATE", json.encodeToString(payload.keys), now),
        )
    }

    suspend fun createAccount(
        bookId: String,
        name: String,
        type: AccountType,
        openingBalanceMinor: Long = 0,
        creditLimitMinor: Long? = null,
        statementDay: Int? = null,
        repaymentDay: Int? = null,
    ): String {
        requireCanEdit(bookId)
        if (type == AccountType.CREDIT_CARD) {
            require(statementDay in 1..31 && repaymentDay in 1..31) { "信用卡账单日和还款日需在 1–31 日之间" }
        }
        val now = System.currentTimeMillis()
        val account = AccountEntity(
            id = id(), bookId = bookId, name = name.trim(), type = type, currency = "CNY", openingBalanceMinor = openingBalanceMinor,
            creditLimitMinor = creditLimitMinor, statementDay = statementDay, repaymentDay = repaymentDay,
            icon = "wallet", colorArgb = 0xFF52616B, sortOrder = dao.getAccounts(bookId).size, updatedAt = now,
        )
        dao.upsertAccount(account)
        dao.enqueue(toOutbox(account, now))
        return account.id
    }

    suspend fun createBudget(
        bookId: String,
        name: String,
        amountMinor: Long,
        period: String = "MONTH",
        rollover: Boolean = false,
        categoryId: String? = null,
        customStartAt: Long? = null,
        customEndAt: Long? = null,
    ): String {
        requireCanEdit(bookId)
        require(amountMinor > 0)
        val now = System.currentTimeMillis()
        val book = dao.getBook(bookId)
        val zone = ZoneId.of(book?.timezone ?: "Asia/Shanghai")
        val today = java.time.LocalDate.now(zone)
        val (start, end) = when (period) {
            "WEEK" -> {
                val date = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                date.atStartOfDay(zone).toInstant().toEpochMilli() to date.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
            }
            "YEAR" -> {
                val date = today.withDayOfYear(1)
                date.atStartOfDay(zone).toInstant().toEpochMilli() to date.plusYears(1).atStartOfDay(zone).toInstant().toEpochMilli()
            }
            "CUSTOM" -> {
                require(customStartAt != null && customEndAt != null && customStartAt < customEndAt) { "自定义预算需要有效的起止日期" }
                customStartAt to customEndAt
            }
            else -> currentMonthRange(book?.timezone ?: "Asia/Shanghai", book?.monthStartDay ?: 1)
        }
        val budget = BudgetEntity(id(), bookId, name.trim(), categoryId, period, start, end, amountMinor, "CNY", rollover, updatedAt = now)
        dao.upsertBudget(budget)
        dao.enqueue(toOutbox(budget, now))
        return budget.id
    }

    suspend fun createSavingGoal(bookId: String, name: String, targetAmountMinor: Long, currentAmountMinor: Long = 0, isWish: Boolean = false): String {
        requireCanEdit(bookId)
        require(targetAmountMinor > 0)
        val now = System.currentTimeMillis()
        val goal = SavingGoalEntity(id(), bookId, name.trim(), targetAmountMinor, currentAmountMinor, "CNY", isWish = isWish, updatedAt = now)
        dao.upsertSavingGoal(goal)
        dao.enqueue(toOutbox(goal, now))
        return goal.id
    }

    suspend fun createCategory(bookId: String, name: String, type: TransactionType, parentId: String? = null): String {
        requireCanEdit(bookId)
        val now = System.currentTimeMillis()
        val category = CategoryEntity(id(), bookId, parentId, name.trim().take(40), type, "category", categoryColor(dao.getCategories(bookId).size), dao.getCategories(bookId).size, updatedAt = now)
        dao.upsertCategory(category); dao.enqueue(toOutbox(category, now)); return category.id
    }

    suspend fun createTag(bookId: String, name: String): String {
        requireCanEdit(bookId)
        val normalized = name.trim().take(40)
        require(normalized.isNotBlank()) { "标签名称不能为空" }
        require(dao.getTags(bookId).none { it.name.equals(normalized, ignoreCase = true) }) { "同名标签已存在" }
        val now = System.currentTimeMillis(); val value = TagEntity(id(), bookId, normalized, 0xFF2C6E63, now)
        dao.upsertTag(value); dao.enqueue(outbox(bookId, SyncEntityType.TAG, value.id, buildJsonObject { put("id", value.id); put("name", value.name); put("colorArgb", value.colorArgb) }, now)); return value.id
    }

    suspend fun renameTag(tagId: String, name: String) {
        val existing = requireNotNull(dao.getTag(tagId)) { "标签不存在" }
        requireCanEdit(existing.bookId)
        val normalized = name.trim().take(40)
        require(normalized.isNotBlank()) { "标签名称不能为空" }
        require(dao.getTags(existing.bookId).none { it.id != tagId && it.name.equals(normalized, ignoreCase = true) }) { "同名标签已存在" }
        val now = System.currentTimeMillis()
        val updated = existing.copy(name = normalized, updatedAt = now)
        val payload = buildJsonObject { put("id", updated.id); put("name", updated.name); put("colorArgb", updated.colorArgb) }
        dao.updateTagBundle(
            updated,
            emptyList(),
            listOf(outbox(updated.bookId, SyncEntityType.TAG, updated.id, payload, now, updated.version)),
            AuditEventEntity(id(), updated.bookId, preferences.userId() ?: "local", SyncEntityType.TAG, updated.id, "UPDATE", "[\"name\"]", now),
        )
    }

    suspend fun deleteTag(tagId: String): Int {
        val existing = requireNotNull(dao.getTag(tagId)) { "标签不存在" }
        requireCanEdit(existing.bookId)
        val links = dao.getActiveTransactionTagsForTag(tagId)
        val now = System.currentTimeMillis()
        val deletedLinks = links.map { it.copy(updatedAt = now, deletedAt = now) }
        val deletedTag = existing.copy(updatedAt = now, deletedAt = now)
        val operations = links.map {
            toDeleteOutbox(existing.bookId, SyncEntityType.TRANSACTION_TAG, "${it.transactionId}:${it.tagId}".toDeterministicUuid(), it.version, now)
        } + toDeleteOutbox(existing.bookId, SyncEntityType.TAG, existing.id, existing.version, now)
        dao.updateTagBundle(
            deletedTag,
            deletedLinks,
            operations,
            AuditEventEntity(id(), existing.bookId, preferences.userId() ?: "local", SyncEntityType.TAG, existing.id, "DELETE", "[\"transactionTags\"]", now),
        )
        return links.size
    }

    suspend fun createMerchant(bookId: String, name: String): String {
        requireCanEdit(bookId)
        val now = System.currentTimeMillis(); val value = MerchantEntity(id(), bookId, name.trim().take(80), now)
        dao.upsertMerchant(value); dao.enqueue(outbox(bookId, SyncEntityType.MERCHANT, value.id, buildJsonObject { put("id", value.id); put("name", value.name) }, now)); return value.id
    }

    suspend fun createProject(bookId: String, name: String): String {
        requireCanEdit(bookId)
        val now = System.currentTimeMillis(); val value = ProjectEntity(id(), bookId, name.trim().take(80), now)
        dao.upsertProject(value); dao.enqueue(outbox(bookId, SyncEntityType.PROJECT, value.id, buildJsonObject { put("id", value.id); put("name", value.name) }, now)); return value.id
    }

    suspend fun createInstallmentPlan(bookId: String, accountId: String, name: String, totalAmountMinor: Long, installmentCount: Int, firstDueAt: Long): String {
        requireCanEdit(bookId)
        require(totalAmountMinor > 0) { "分期总额必须大于 0" }
        require(installmentCount in 2..120) { "期数需在 2 到 120 之间" }
        val now = System.currentTimeMillis()
        val plan = InstallmentPlanEntity(id(), bookId, accountId, name.trim().take(80), totalAmountMinor, installmentCount, 0, firstDueAt, "MONTHLY", now)
        dao.upsertInstallmentPlan(plan)
        dao.enqueue(toOutbox(plan, now))
        return plan.id
    }

    suspend fun advanceInstallment(planId: String) {
        val plan = dao.getInstallmentPlan(planId) ?: return
        requireCanEdit(plan.bookId)
        if (plan.completedCount >= plan.installmentCount) return
        val now = System.currentTimeMillis()
        val updated = plan.copy(
            completedCount = plan.completedCount + 1,
            firstDueAt = nextOccurrence(plan.firstDueAt, plan.recurrenceRule),
            updatedAt = now,
        )
        dao.upsertInstallmentPlan(updated)
        dao.enqueue(toOutbox(updated, now))
    }

    suspend fun dueInstallmentPlans(now: Long = System.currentTimeMillis()) = dao.dueInstallmentPlans(now)

    suspend fun createSavedFilter(bookId: String, name: String, filter: TransactionFilter): String {
        requireCanEdit(bookId)
        val now = System.currentTimeMillis()
        val value = SavedFilterEntity(id(), bookId, name.trim().take(80), json.encodeToString(filter), now)
        dao.upsertSavedFilter(value)
        dao.enqueue(outbox(bookId, SyncEntityType.SAVED_FILTER, value.id, buildJsonObject {
            put("id", value.id); put("name", value.name); put("filterJson", value.filterJson)
        }, now))
        return value.id
    }

    fun decodeSavedFilter(value: SavedFilterEntity): TransactionFilter = json.decodeFromString(value.filterJson)

    suspend fun createRecurringRule(bookId: String, name: String, template: RecurringTemplate, frequency: String, firstRunAt: Long): String {
        requireCanEdit(bookId)
        val now = System.currentTimeMillis()
        val rule = com.billrecord.ledger.data.local.RecurringRuleEntity(id(), bookId, name.trim(), json.encodeToString(template), frequency, firstRunAt, true, now)
        dao.upsertRecurringRule(rule)
        dao.enqueue(outbox(bookId, SyncEntityType.RECURRING_RULE, rule.id, buildJsonObject {
            put("id", rule.id); put("name", rule.name); put("transactionTemplateJson", rule.transactionTemplateJson); put("recurrenceRule", rule.recurrenceRule); put("nextRunAt", rule.nextRunAt); put("enabled", rule.enabled)
        }, now))
        return rule.id
    }

    suspend fun processDueRecurringRules(now: Long = System.currentTimeMillis()): Int {
        var created = 0
        dao.dueRecurringRules(now).forEach { rule ->
            val template = json.decodeFromString<RecurringTemplate>(rule.transactionTemplateJson)
            createTransaction(RecordInput(rule.bookId, template.type, template.amountMinor, template.accountId, template.categoryId, note = template.note, occurredAt = rule.nextRunAt))
            val next = nextOccurrence(rule.nextRunAt, rule.recurrenceRule)
            dao.upsertRecurringRule(rule.copy(nextRunAt = next, updatedAt = now))
            created++
        }
        return created
    }

    suspend fun deleteTransaction(transactionId: String) {
        val existing = requireNotNull(dao.getTransaction(transactionId))
        requireCanEdit(existing.bookId)
        if (existing.deletedAt != null) return
        val now = System.currentTimeMillis()
        val transaction = existing.copy(updatedAt = now, deletedAt = now)
        val postings = dao.getPostings(transactionId).map { it.copy(deletedAt = now) }
        val splits = dao.getTransactionSplits(transactionId).map { it.copy(deletedAt = now, updatedAt = now) }
        val transactionTags = dao.getTransactionTagsForTransaction(transactionId).map { it.copy(deletedAt = now, updatedAt = now) }
        val attachments = dao.getAttachmentsForTransaction(transactionId).map { it.copy(deletedAt = now, updatedAt = now) }
        val operations = listOf(toDeleteOutbox(transaction.bookId, SyncEntityType.TRANSACTION, transaction.id, transaction.version, now)) +
            postings.map { toDeleteOutbox(transaction.bookId, SyncEntityType.POSTING, it.id, it.version, now) } +
            splits.map { toDeleteOutbox(transaction.bookId, SyncEntityType.TRANSACTION_SPLIT, it.id, it.version, now) } +
            transactionTags.map { toDeleteOutbox(transaction.bookId, SyncEntityType.TRANSACTION_TAG, "${it.transactionId}:${it.tagId}".toDeterministicUuid(), it.version, now) } +
            attachments.map { toDeleteOutbox(transaction.bookId, SyncEntityType.ATTACHMENT, it.id, it.version, now) }
        dao.insertTransactionBundle(
            transaction,
            postings,
            operations,
            AuditEventEntity(id(), transaction.bookId, preferences.userId() ?: "local", SyncEntityType.TRANSACTION, transaction.id, "DELETE", "[]", now),
            splits,
            transactionTags,
            attachments,
        )
    }

    suspend fun restoreTransaction(transactionId: String) {
        val existing = requireNotNull(dao.getTransaction(transactionId))
        requireCanEdit(existing.bookId)
        if (existing.deletedAt == null) return
        val now = System.currentTimeMillis()
        val transaction = existing.copy(updatedAt = now, deletedAt = null)
        val postings = dao.getPostings(transactionId).map { it.copy(deletedAt = null) }
        val splits = dao.getTransactionSplits(transactionId).map { it.copy(deletedAt = null, updatedAt = now) }
        val transactionTags = dao.getTransactionTagsForTransaction(transactionId).map { it.copy(deletedAt = null, updatedAt = now) }
        val attachments = dao.getAttachmentsForTransaction(transactionId).map { it.copy(deletedAt = null, updatedAt = now) }
        val operations = mutableListOf<OutboxOperationEntity>()
        if (dao.removePendingDelete(transaction.bookId, SyncEntityType.TRANSACTION, transaction.id) == 0) {
            operations += toOutbox(transaction, now)
        }
        postings.forEach { posting ->
            if (dao.removePendingDelete(posting.bookId, SyncEntityType.POSTING, posting.id) == 0) {
                operations += toOutbox(posting, now)
            }
        }
        splits.forEach { split ->
            if (dao.removePendingDelete(split.bookId, SyncEntityType.TRANSACTION_SPLIT, split.id) == 0) {
                operations += toOutbox(split, now)
            }
        }
        transactionTags.forEach { link ->
            val entityId = "${link.transactionId}:${link.tagId}".toDeterministicUuid()
            if (dao.removePendingDelete(link.bookId, SyncEntityType.TRANSACTION_TAG, entityId) == 0) operations += toOutbox(link, now)
        }
        attachments.forEach { attachment ->
            if (dao.removePendingDelete(attachment.bookId, SyncEntityType.ATTACHMENT, attachment.id) == 0) operations += toOutbox(attachment, now)
        }
        dao.insertTransactionBundle(
            transaction,
            postings,
            operations,
            AuditEventEntity(id(), transaction.bookId, preferences.userId() ?: "local", SyncEntityType.TRANSACTION, transaction.id, "RESTORE", "[]", now),
            splits,
            transactionTags,
            attachments,
        )
    }

    suspend fun updateReimbursementStatus(transactionIds: Collection<String>, status: ReimbursementStatus) {
        transactionIds.distinct().forEach { transactionId ->
            val existing = dao.getTransaction(transactionId) ?: return@forEach
            requireCanEdit(existing.bookId)
            if (existing.deletedAt != null || existing.reimbursementStatus == status) return@forEach
            val now = System.currentTimeMillis()
            val updated = existing.copy(reimbursementStatus = status, updatedAt = now)
            val payload = buildJsonObject { put("reimbursementStatus", status.name) }
            dao.insertTransactionBundle(
                updated,
                emptyList(),
                listOf(outbox(updated.bookId, SyncEntityType.TRANSACTION, updated.id, payload, now, updated.version)),
                AuditEventEntity(id(), updated.bookId, preferences.userId() ?: "local", SyncEntityType.TRANSACTION, updated.id, "STATUS_${status.name}", "[\"reimbursementStatus\"]", now),
            )
        }
    }

    fun pagedQuery(filter: TransactionFilter, sort: TransactionSort = TransactionSort.DATE_DESC) = dao.pageTransactions(buildQuery(filter, sort))
    suspend fun queryForExportPage(filter: TransactionFilter, limit: Int, offset: Int) = queryForExportPage(filter, TransactionSort.DATE_DESC, limit, offset)
    suspend fun queryForExportPage(filter: TransactionFilter, sort: TransactionSort, limit: Int, offset: Int) = dao.queryTransactions(buildQuery(filter, sort, limit, offset))

    fun currentMonthRange(timezone: String = "Asia/Shanghai", monthStartDay: Int = 1): Pair<Long, Long> {
        val zone = ZoneId.of(timezone)
        val now = ZonedDateTime.now(zone)
        val baseMonth = if (now.dayOfMonth < monthStartDay) now.minusMonths(1) else now
        val start = baseMonth.withDayOfMonth(monthStartDay.coerceAtMost(baseMonth.toLocalDate().lengthOfMonth()))
            .toLocalDate().atStartOfDay(zone)
        return start.toInstant().toEpochMilli() to start.plusMonths(1).toInstant().toEpochMilli()
    }

    private enum class AnalyticsMetric { SUMMARY, CATEGORIES, TREND, DAILY, MEMBERS, TAGS }

    private fun analyticsQuery(filter: AnalyticsFilter, metric: AnalyticsMetric): SimpleSQLiteQuery {
        fun placeholders(size: Int) = List(size) { "?" }.joinToString(",")
        val factWhere = mutableListOf(
            "t.bookId = ?",
            "t.deletedAt IS NULL",
            "t.occurredAt >= ?",
            "t.occurredAt < ?",
        )
        val factArgs = mutableListOf<Any>(filter.bookId, filter.startAt, filter.endAt)
        if (filter.accountIds.isNotEmpty()) {
            factWhere += "(t.accountId IN (${placeholders(filter.accountIds.size)}) OR t.destinationAccountId IN (${placeholders(filter.accountIds.size)}))"
            factArgs.addAll(filter.accountIds)
            factArgs.addAll(filter.accountIds)
        }
        if (filter.memberIds.isNotEmpty()) {
            factWhere += "t.memberId IN (${placeholders(filter.memberIds.size)})"
            factArgs.addAll(filter.memberIds)
        }
        val facts = """
            WITH facts AS (
              SELECT t.id AS transactionId, t.bookId, t.type, t.occurredAt, t.memberId, t.categoryId,
                     t.baseAmountMinor AS amountMinor
              FROM transactions t
              WHERE ${factWhere.joinToString(" AND ")}
                AND NOT EXISTS (SELECT 1 FROM transaction_splits s WHERE s.transactionId = t.id AND s.deletedAt IS NULL)
              UNION ALL
              SELECT t.id AS transactionId, t.bookId, t.type, t.occurredAt, t.memberId, s.categoryId,
                     s.baseAmountMinor AS amountMinor
              FROM transactions t JOIN transaction_splits s ON s.transactionId = t.id AND s.deletedAt IS NULL
              WHERE ${factWhere.joinToString(" AND ")}
            )
        """.trimIndent()
        val args = mutableListOf<Any>().apply {
            addAll(factArgs)
            addAll(factArgs)
        }
        val categoryWhere = if (filter.categoryIds.isEmpty()) "" else {
            args.addAll(filter.categoryIds)
            " WHERE facts.categoryId IN (${placeholders(filter.categoryIds.size)})"
        }
        val spendingWhere = if (categoryWhere.isBlank()) {
            "WHERE facts.type IN ('EXPENSE', 'REFUND')"
        } else {
            "$categoryWhere AND facts.type IN ('EXPENSE', 'REFUND')"
        }
        val trendPeriod = if (filter.endAt - filter.startAt <= 45L * 24 * 60 * 60 * 1000) "%Y-%m-%d" else "%Y-%m"
        val body = when (metric) {
            AnalyticsMetric.SUMMARY -> """
                SELECT COALESCE(SUM(CASE WHEN type = 'INCOME' THEN amountMinor ELSE 0 END), 0) AS incomeMinor,
                       COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN amountMinor ELSE 0 END), 0) AS expenseMinor,
                       COALESCE(SUM(CASE WHEN type = 'REFUND' THEN amountMinor ELSE 0 END), 0) AS refundMinor
                FROM facts$categoryWhere
            """
            AnalyticsMetric.CATEGORIES -> """
                SELECT facts.categoryId AS categoryId, c.name AS categoryName,
                       COALESCE(SUM(CASE WHEN facts.type = 'REFUND' THEN -facts.amountMinor ELSE facts.amountMinor END), 0) AS amountMinor
                FROM facts LEFT JOIN categories c ON c.id = facts.categoryId
                $spendingWhere
                GROUP BY facts.categoryId, c.name ORDER BY amountMinor DESC
            """
            AnalyticsMetric.TREND -> """
                SELECT strftime('$trendPeriod', occurredAt / 1000, 'unixepoch', '+8 hours') AS period,
                       COALESCE(SUM(CASE WHEN type = 'INCOME' THEN amountMinor ELSE 0 END), 0) AS incomeMinor,
                       COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN amountMinor ELSE 0 END), 0) AS expenseMinor,
                       COALESCE(SUM(CASE WHEN type = 'REFUND' THEN amountMinor ELSE 0 END), 0) AS refundMinor
                FROM facts$categoryWhere GROUP BY period ORDER BY period
            """
            AnalyticsMetric.DAILY -> """
                SELECT strftime('%Y-%m-%d', occurredAt / 1000, 'unixepoch', '+8 hours') AS day,
                       COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN amountMinor WHEN type = 'REFUND' THEN -amountMinor ELSE 0 END), 0) AS amountMinor
                FROM facts$categoryWhere GROUP BY day ORDER BY day
            """
            AnalyticsMetric.MEMBERS -> """
                SELECT facts.memberId AS id, COALESCE(m.displayName, '未指定成员') AS name,
                       COALESCE(SUM(CASE WHEN facts.type = 'EXPENSE' THEN facts.amountMinor WHEN facts.type = 'REFUND' THEN -facts.amountMinor ELSE 0 END), 0) AS amountMinor
                FROM facts LEFT JOIN memberships m ON m.bookId = facts.bookId AND m.userId = facts.memberId
                $categoryWhere GROUP BY facts.memberId, m.displayName ORDER BY amountMinor DESC
            """
            AnalyticsMetric.TAGS -> """
                SELECT tag.id AS id, tag.name AS name,
                       COALESCE(SUM(CASE WHEN facts.type = 'EXPENSE' THEN facts.amountMinor WHEN facts.type = 'REFUND' THEN -facts.amountMinor ELSE 0 END), 0) AS amountMinor
                FROM facts JOIN transaction_tags tt ON tt.transactionId = facts.transactionId AND tt.deletedAt IS NULL
                JOIN tags tag ON tag.id = tt.tagId AND tag.deletedAt IS NULL
                $categoryWhere GROUP BY tag.id, tag.name ORDER BY amountMinor DESC
            """
        }
        return SimpleSQLiteQuery("$facts\n$body", args.toTypedArray())
    }

    private fun buildQuery(filter: TransactionFilter, sort: TransactionSort, limit: Int? = null, offset: Int = 0): SimpleSQLiteQuery {
        val where = mutableListOf("deletedAt IS NULL")
        val args = mutableListOf<Any>()
        fun placeholders(size: Int) = List(size) { "?" }.joinToString(",")
        if (filter.bookIds.isNotEmpty()) { where += "bookId IN (${placeholders(filter.bookIds.size)})"; args.addAll(filter.bookIds) }
        filter.startEpochMillis?.let { where += "occurredAt >= ?"; args += it }
        filter.endEpochMillis?.let { where += "occurredAt < ?"; args += it }
        if (filter.types.isNotEmpty()) { where += "type IN (${placeholders(filter.types.size)})"; args.addAll(filter.types.map { it.name }) }
        if (filter.categoryIds.isNotEmpty()) {
            val realIds = filter.categoryIds - "__NONE__"
            val clauses = mutableListOf<String>()
            if (realIds.isNotEmpty()) {
                val categoryPlaceholders = placeholders(realIds.size)
                clauses += "categoryId IN ($categoryPlaceholders)"
                args.addAll(realIds)
                clauses += "EXISTS (SELECT 1 FROM transaction_splits ts WHERE ts.transactionId = transactions.id AND ts.deletedAt IS NULL AND ts.categoryId IN ($categoryPlaceholders))"
                args.addAll(realIds)
            }
            if ("__NONE__" in filter.categoryIds) {
                clauses += "(categoryId IS NULL AND NOT EXISTS (SELECT 1 FROM transaction_splits ts WHERE ts.transactionId = transactions.id AND ts.deletedAt IS NULL))"
                clauses += "EXISTS (SELECT 1 FROM transaction_splits ts WHERE ts.transactionId = transactions.id AND ts.deletedAt IS NULL AND ts.categoryId IS NULL)"
            }
            where += "(${clauses.joinToString(" OR ")})"
        }
        if (filter.accountIds.isNotEmpty()) {
            where += "(accountId IN (${placeholders(filter.accountIds.size)}) OR destinationAccountId IN (${placeholders(filter.accountIds.size)}))"
            args.addAll(filter.accountIds)
            args.addAll(filter.accountIds)
        }
        fun nullableDimension(column: String, values: Set<String>) {
            if (values.isEmpty()) return
            val realIds = values - "__NONE__"
            val clauses = mutableListOf<String>()
            if (realIds.isNotEmpty()) { clauses += "$column IN (${placeholders(realIds.size)})"; args.addAll(realIds) }
            if ("__NONE__" in values) clauses += "$column IS NULL"
            where += "(${clauses.joinToString(" OR ")})"
        }
        nullableDimension("memberId", filter.memberIds)
        nullableDimension("merchantId", filter.merchantIds)
        nullableDimension("projectId", filter.projectIds)
        filter.minimumAmountMinor?.let { where += "baseAmountMinor >= ?"; args += it }
        filter.maximumAmountMinor?.let { where += "baseAmountMinor <= ?"; args += it }
        if (filter.reimbursementStatuses.isNotEmpty()) {
            where += "reimbursementStatus IN (${placeholders(filter.reimbursementStatuses.size)})"
            args.addAll(filter.reimbursementStatuses.map { it.name })
        }
        if (filter.tagIds.isNotEmpty()) {
            val realIds = filter.tagIds - "__NONE__"
            val clauses = mutableListOf<String>()
            if (realIds.isNotEmpty()) {
                clauses += "EXISTS (SELECT 1 FROM transaction_tags tt WHERE tt.transactionId = transactions.id AND tt.deletedAt IS NULL AND tt.tagId IN (${placeholders(realIds.size)}))"
                args.addAll(realIds)
            }
            if ("__NONE__" in filter.tagIds) clauses += "NOT EXISTS (SELECT 1 FROM transaction_tags tt WHERE tt.transactionId = transactions.id AND tt.deletedAt IS NULL)"
            where += "(${clauses.joinToString(" OR ")})"
        }
        if (filter.query.isNotBlank()) {
            val query = filter.query.trim()
            val escapedQuery = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            val amountMinor = runCatching { query.toBigDecimalOrNull()?.movePointRight(2)?.setScale(0, java.math.RoundingMode.HALF_UP)?.longValueExact() }.getOrNull()
            where += if (amountMinor == null) "note LIKE ? ESCAPE '\\'" else "(note LIKE ? ESCAPE '\\' OR amountMinor = ?)"
            args += "%$escapedQuery%"
            amountMinor?.let(args::add)
        }
        val pagination = if (limit == null) "" else { args.add(limit); args.add(offset); " LIMIT ? OFFSET ?" }
        return SimpleSQLiteQuery("SELECT * FROM transactions WHERE ${where.joinToString(" AND ")} ORDER BY ${transactionOrderBy(sort)}$pagination", args.toTypedArray())
    }

    private suspend fun enqueueSeed(book: BookEntity, accounts: List<AccountEntity>, categories: List<CategoryEntity>, now: Long) {
        dao.enqueue(
            outbox(book.id, SyncEntityType.BOOK, book.id, buildJsonObject {
                put("id", book.id); put("name", book.name); put("baseCurrency", book.baseCurrency); put("timezone", book.timezone); put("monthStartDay", book.monthStartDay)
            }, now),
        )
        accounts.forEach { dao.enqueue(toOutbox(it, now)) }
        categories.forEach { dao.enqueue(toOutbox(it, now)) }
    }

    private fun toOutbox(value: TransactionEntity, now: Long) = outbox(
        value.bookId, SyncEntityType.TRANSACTION, value.id,
        buildJsonObject {
            put("id", value.id); put("type", value.type.name); put("amountMinor", value.amountMinor); put("currency", value.currency)
            put("baseAmountMinor", value.baseAmountMinor); put("exchangeRate", value.exchangeRate); value.categoryId?.let { put("categoryId", it) }
            put("accountId", value.accountId); value.destinationAccountId?.let { put("destinationAccountId", it) }
            value.destinationAmountMinor?.let { put("destinationAmountMinor", it) }; value.destinationCurrency?.let { put("destinationCurrency", it) }
            value.memberId?.let { put("memberId", it) }; value.merchantId?.let { put("merchantId", it) }; value.projectId?.let { put("projectId", it) }
            value.refundOfTransactionId?.let { put("refundOfTransactionId", it) }; put("note", value.note)
            put("occurredAt", value.occurredAt); put("createdAt", value.createdAt); put("reimbursementStatus", value.reimbursementStatus.name)
        }, now, value.version,
    )

    private fun toOutbox(value: PostingEntity, now: Long) = outbox(
        value.bookId, SyncEntityType.POSTING, value.id,
        buildJsonObject {
            put("id", value.id); put("transactionId", value.transactionId); put("ledgerAccountId", value.ledgerAccountId)
            put("amountMinor", value.amountMinor); put("currency", value.currency); put("baseAmountMinor", value.baseAmountMinor)
        }, now, value.version,
    )

    private fun toOutbox(value: TransactionSplitEntity, now: Long) = outbox(
        value.bookId, SyncEntityType.TRANSACTION_SPLIT, value.id,
        buildJsonObject {
            put("id", value.id); put("transactionId", value.transactionId); value.categoryId?.let { put("categoryId", it) }
            put("amountMinor", value.amountMinor); put("baseAmountMinor", value.baseAmountMinor); put("note", value.note)
        }, now, value.version,
    )

    private fun toOutbox(value: TransactionTagEntity, now: Long) = outbox(
        value.bookId, SyncEntityType.TRANSACTION_TAG, "${value.transactionId}:${value.tagId}".toDeterministicUuid(),
        buildJsonObject { put("transactionId", value.transactionId); put("tagId", value.tagId) }, now, value.version,
    )

    private fun toOutbox(value: AttachmentEntity, now: Long) = outbox(
        value.bookId, SyncEntityType.ATTACHMENT, value.id,
        buildJsonObject {
            put("id", value.id); put("transactionId", value.transactionId); put("displayName", value.displayName); put("mimeType", value.mimeType)
            value.remoteKey?.let { put("remoteKey", it) }; put("sha256", value.sha256); put("sizeBytes", value.sizeBytes)
        }, now, value.version,
    )

    private fun toOutbox(value: AccountEntity, now: Long) = outbox(
        value.bookId, SyncEntityType.ACCOUNT, value.id,
        buildJsonObject {
            put("id", value.id); put("name", value.name); put("type", value.type.name); put("currency", value.currency); put("openingBalanceMinor", value.openingBalanceMinor)
            value.creditLimitMinor?.let { put("creditLimitMinor", it) }; value.statementDay?.let { put("statementDay", it) }; value.repaymentDay?.let { put("repaymentDay", it) }
        },
        now,
    )

    private fun toOutbox(value: CategoryEntity, now: Long) = outbox(
        value.bookId, SyncEntityType.CATEGORY, value.id,
        buildJsonObject { put("id", value.id); put("name", value.name); put("type", value.type.name); value.parentId?.let { put("parentId", it) }; put("icon", value.icon) },
        now,
    )

    private fun toOutbox(value: BudgetEntity, now: Long) = outbox(
        value.bookId, SyncEntityType.BUDGET, value.id,
        buildJsonObject { put("id", value.id); put("name", value.name); value.categoryId?.let { put("categoryId", it) }; put("period", value.period); put("startAt", value.startAt); value.endAt?.let { put("endAt", it) }; put("amountMinor", value.amountMinor); put("currency", value.currency); put("rollover", value.rollover); put("alertThresholdPercent", value.alertThresholdPercent) },
        now,
    )

    private fun toOutbox(value: SavingGoalEntity, now: Long) = outbox(
        value.bookId, SyncEntityType.SAVING_GOAL, value.id,
        buildJsonObject { put("id", value.id); put("name", value.name); put("targetAmountMinor", value.targetAmountMinor); put("currentAmountMinor", value.currentAmountMinor); put("currency", value.currency); value.targetAt?.let { put("targetAt", it) }; put("isWish", value.isWish) },
        now,
    )

    private fun toOutbox(value: InstallmentPlanEntity, now: Long) = outbox(
        value.bookId, SyncEntityType.INSTALLMENT_PLAN, value.id,
        buildJsonObject {
            put("id", value.id); put("accountId", value.accountId); put("name", value.name); put("totalAmountMinor", value.totalAmountMinor)
            put("installmentCount", value.installmentCount); put("completedCount", value.completedCount); put("firstDueAt", value.firstDueAt); put("recurrenceRule", value.recurrenceRule)
        }, now, value.version,
    )

    private fun outbox(
        bookId: String,
        entityType: SyncEntityType,
        entityId: String,
        payload: kotlinx.serialization.json.JsonObject,
        now: Long,
        baseVersion: Long = 0,
    ) = OutboxOperationEntity(
        operationId = id(), bookId = bookId, entityType = entityType, entityId = entityId,
        operation = SyncOperationType.UPSERT, baseVersion = baseVersion,
        changedFieldsJson = json.encodeToString(payload.keys), payloadJson = payload.toString(),
        clientModifiedAt = now, createdAt = now,
    )

    private fun toDeleteOutbox(bookId: String, entityType: SyncEntityType, entityId: String, baseVersion: Long, now: Long) =
        OutboxOperationEntity(id(), bookId, entityType, entityId, SyncOperationType.DELETE, baseVersion, "[]", "{}", now, now)

    private fun id() = UUID.randomUUID().toString()
    private fun String.toDeterministicUuid(): String = UUID.nameUUIDFromBytes(toByteArray(Charsets.UTF_8)).toString()
    private fun expenseIcon(name: String) = when (name) {
        "餐饮" -> "restaurant"; "交通" -> "directions_bus"; "购物" -> "shopping_bag"; "居住" -> "home"
        "医疗" -> "medical"; "娱乐" -> "movie"; "人情" -> "redeem"; else -> "category"
    }
    private fun categoryColor(index: Int) = listOf(0xFFB4553E, 0xFF2C6E63, 0xFFC49A4A, 0xFF52616B, 0xFF73575C)[index % 5]
    private fun nextOccurrence(epochMillis: Long, frequency: String): Long {
        val value = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.of("Asia/Shanghai"))
        return when (frequency) {
            "DAILY" -> value.plusDays(1)
            "WEEKLY" -> value.plusWeeks(1)
            "YEARLY" -> value.plusYears(1)
            else -> value.plusMonths(1)
        }.toInstant().toEpochMilli()
    }

    private suspend fun requireCanEdit(bookId: String) {
        val userId = preferences.userId() ?: return
        val membership = dao.getMembership(bookId, userId) ?: return
        require(membership.deletedAt == null) { "您已不再是该账本成员" }
        require(membership.role != BookRole.VIEWER) { "当前成员角色为只读，不能修改账本" }
    }
}
