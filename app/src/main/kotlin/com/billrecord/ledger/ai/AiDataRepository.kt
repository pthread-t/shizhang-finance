package com.billrecord.ledger.ai

import com.billrecord.ledger.data.AppPreferences
import com.billrecord.ledger.data.LedgerRepository
import com.billrecord.ledger.data.local.AiConversationEntity
import com.billrecord.ledger.data.local.AiMessageEntity
import com.billrecord.ledger.data.local.AiProviderProfileEntity
import com.billrecord.ledger.data.local.LedgerDao
import com.billrecord.ledger.data.local.TransactionEntity
import com.billrecord.shared.TransactionFilter
import com.billrecord.shared.TransactionType
import kotlinx.coroutines.flow.Flow
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiDataRepository @Inject constructor(
    private val dao: LedgerDao,
    private val ledgerRepository: LedgerRepository,
    private val preferences: AppPreferences,
) {
    fun observeProfiles() = dao.observeAiProviderProfiles()
    fun observeConversations(bookId: String) = dao.observeAiConversations(bookId)
    fun observeMessages(conversationId: String) = dao.observeAiMessages(conversationId)
    suspend fun profile(id: String) = dao.getAiProviderProfile(id)
    suspend fun defaultProfile() = dao.getDefaultAiProviderProfile()
    suspend fun message(id: String) = dao.getAiMessage(id)
    fun apiKey(profileId: String) = preferences.aiApiKey(profileId)

    suspend fun saveProfile(
        id: String? = null,
        displayName: String,
        kind: AiProviderKind,
        baseUrl: String,
        model: String,
        apiKey: String?,
        enabled: Boolean = true,
        makeDefault: Boolean = true,
    ): String {
        val normalized = validateBaseUrl(baseUrl)
        require(displayName.isNotBlank()) { "请输入配置名称" }
        require(model.isNotBlank()) { "请输入模型名称" }
        val now = System.currentTimeMillis()
        val profileId = id ?: UUID.randomUUID().toString()
        val existing = id?.let { dao.getAiProviderProfile(it) }
        dao.upsertAiProviderProfile(
            AiProviderProfileEntity(
                id = profileId,
                displayName = displayName.trim(),
                providerKind = kind.name,
                baseUrl = normalized,
                model = model.trim(),
                enabled = enabled,
                isDefault = makeDefault,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
        if (!apiKey.isNullOrBlank()) preferences.saveAiApiKey(profileId, apiKey)
        if (makeDefault) dao.setDefaultAiProviderProfile(profileId, now)
        return profileId
    }

    suspend fun setDefaultProfile(id: String) = dao.setDefaultAiProviderProfile(id, System.currentTimeMillis())

    suspend fun deleteProfile(id: String) {
        dao.deleteAiProviderProfile(id)
        preferences.deleteAiApiKey(id)
    }

    suspend fun newConversation(bookId: String, title: String): String {
        val now = System.currentTimeMillis()
        return UUID.randomUUID().toString().also {
            dao.upsertAiConversation(AiConversationEntity(it, bookId, title.take(40).ifBlank { "新对话" }, now, now))
        }
    }

    suspend fun saveMessage(value: AiMessageEntity) {
        dao.upsertAiMessage(value)
        dao.getAiConversation(value.conversationId)?.let { dao.upsertAiConversation(it.copy(updatedAt = System.currentTimeMillis())) }
    }

    suspend fun deleteConversation(id: String) = dao.deleteAiConversation(id)
    suspend fun clearHistory() = dao.clearAiHistory()

    suspend fun catalog(bookId: String): FinanceCatalog {
        val book = requireNotNull(dao.getBook(bookId)) { "账本不存在" }
        val categories = dao.getCategories(bookId)
        return FinanceCatalog(
            bookId = book.id,
            bookName = book.name,
            currency = book.baseCurrency,
            timezone = book.timezone,
            accounts = dao.getAccounts(bookId).associate { it.id to it.name },
            categories = categories.associate { it.id to it.name },
            categoryParents = categories.associate { it.id to it.parentId },
            members = dao.getMemberships(bookId).associate { it.userId to it.displayName },
            tags = dao.getTags(bookId).associate { it.id to it.name },
            merchants = dao.getMerchants(bookId).associate { it.id to it.name },
            projects = dao.getProjects(bookId).associate { it.id to it.name },
        )
    }

    fun validatePlan(raw: FinanceQueryPlan, catalog: FinanceCatalog): FinanceQueryPlan {
        val zone = ZoneId.of(catalog.timezone)
        val today = java.time.LocalDate.now(zone)
        val defaultStart = today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val defaultEnd = today.withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val plan = raw.copy(
            bookId = catalog.bookId,
            startAt = raw.startAt.takeIf { it > 0 } ?: defaultStart,
            endAt = raw.endAt.takeIf { it > 0 } ?: defaultEnd,
        )
        require(plan.startAt < plan.endAt) { "查询开始时间必须早于结束时间" }
        require(plan.accountIds.all(catalog.accounts::containsKey)) { "查询包含未知账户" }
        require(plan.categoryIds.all(catalog.categories::containsKey)) { "查询包含未知分类" }
        require(plan.memberIds.all(catalog.members::containsKey)) { "查询包含未知成员" }
        require(plan.tagIds.all(catalog.tags::containsKey)) { "查询包含未知标签" }
        require(plan.merchantIds.all(catalog.merchants::containsKey)) { "查询包含未知商家" }
        require(plan.projectIds.all(catalog.projects::containsKey)) { "查询包含未知项目" }
        require(plan.minimumAmountMinor == null || plan.maximumAmountMinor == null || plan.minimumAmountMinor <= plan.maximumAmountMinor) { "金额区间无效" }
        val expandedPlan = plan.copy(categoryIds = expandCategoryIds(plan.categoryIds, catalog.categoryParents))
        val normalizedDimensions = when {
            expandedPlan.groupBy == FinanceGroupBy.NONE && expandedPlan.secondaryGroupBy != FinanceGroupBy.NONE ->
                expandedPlan.copy(groupBy = expandedPlan.secondaryGroupBy, secondaryGroupBy = FinanceGroupBy.NONE)
            expandedPlan.secondaryGroupBy == expandedPlan.groupBy -> expandedPlan.copy(secondaryGroupBy = FinanceGroupBy.NONE)
            else -> expandedPlan
        }
        return when (normalizedDimensions.metric) {
            FinanceMetric.EXPENSE -> normalizedDimensions.copy(types = setOf(TransactionType.EXPENSE, TransactionType.REFUND))
            FinanceMetric.INCOME -> normalizedDimensions.copy(types = setOf(TransactionType.INCOME))
            else -> normalizedDimensions
        }
    }

    suspend fun execute(plan: FinanceQueryPlan, catalog: FinanceCatalog): FinanceResult {
        val filter = plan.transactionFilter()
        val transactions = mutableListOf<TransactionEntity>()
        var offset = 0
        while (true) {
            val page = ledgerRepository.queryForExportPage(filter, 1_000, offset)
            transactions += page
            if (page.size < 1_000) break
            offset += page.size
        }
        val splits = transactions.map { it.id }.chunked(400).flatMap { ids ->
            if (ids.isEmpty()) emptyList() else dao.getTransactionSplits(ids)
        }.groupBy { it.transactionId }
        val transactionTags = dao.getTransactionTags(plan.bookId).groupBy { it.transactionId }
        val rows = linkedMapOf<String, MutableAggregate>()
        val zone = ZoneId.of(catalog.timezone)
        var totalIncome = 0L
        var totalExpense = 0L
        var totalRefund = 0L
        val matchedTransactionIds = mutableSetOf<String>()

        transactions.forEach { transaction ->
            val transactionSplits = splits[transaction.id].orEmpty()
            val facts = if (transactionSplits.isNotEmpty()) {
                transactionSplits.filter { categoryMatches(plan.categoryIds, it.categoryId) }.map { Fact(transaction, it.categoryId, it.baseAmountMinor) }
            } else {
                listOf(Fact(transaction, transaction.categoryId, transaction.baseAmountMinor)).filter { categoryMatches(plan.categoryIds, it.categoryId) }
            }
            if (facts.isEmpty()) return@forEach
            val matchedAmount = facts.sumOf { it.amountMinor }
            matchedTransactionIds += transaction.id
            when (transaction.type) {
                TransactionType.INCOME -> totalIncome += matchedAmount
                TransactionType.EXPENSE -> totalExpense += matchedAmount
                TransactionType.REFUND -> totalRefund += matchedAmount
                else -> Unit
            }
            facts.forEach { fact ->
                val primary = dimensionValues(plan.groupBy, plan, fact, transactionTags[transaction.id].orEmpty().map { it.tagId }, catalog, zone)
                val secondary = if (plan.secondaryGroupBy == FinanceGroupBy.NONE) {
                    listOf(DimensionValue("", ""))
                } else {
                    dimensionValues(plan.secondaryGroupBy, plan, fact, transactionTags[transaction.id].orEmpty().map { it.tagId }, catalog, zone)
                }
                primary.forEach { first -> secondary.forEach { second ->
                    val aggregateKey = if (plan.secondaryGroupBy == FinanceGroupBy.NONE) first.key else "${first.key}\u001F${second.key}"
                    val aggregate = rows.getOrPut(aggregateKey) {
                        MutableAggregate(
                            key = first.key,
                            label = first.label,
                            secondaryKey = second.key.takeIf { plan.secondaryGroupBy != FinanceGroupBy.NONE },
                            secondaryLabel = second.label.takeIf { plan.secondaryGroupBy != FinanceGroupBy.NONE },
                        )
                    }
                    aggregate.add(transaction, fact.amountMinor)
                } }
            }
        }
        val baseFilter = plan.transactionFilter()
        val resultRows = rows.values.map { aggregate ->
            FinanceAggregateRow(
                key = aggregate.key,
                label = aggregate.label,
                secondaryKey = aggregate.secondaryKey,
                secondaryLabel = aggregate.secondaryLabel,
                incomeMinor = aggregate.income,
                expenseMinor = aggregate.expense,
                refundMinor = aggregate.refund,
                count = aggregate.transactionIds.size,
                filter = filterForDimension(baseFilter, plan.groupBy, aggregate.key, plan.granularity, catalog.timezone).let { primaryFilter ->
                    aggregate.secondaryKey?.let { secondaryKey ->
                        filterForDimension(primaryFilter, plan.secondaryGroupBy, secondaryKey, plan.granularity, catalog.timezone)
                    } ?: primaryFilter
                },
            )
        }.let { unsorted ->
            when {
                plan.groupBy == FinanceGroupBy.TIME -> unsorted.sortedWith(compareBy<FinanceAggregateRow> { it.key }.thenBy { it.secondaryKey })
                plan.secondaryGroupBy == FinanceGroupBy.TIME -> unsorted.sortedWith(compareBy<FinanceAggregateRow> { it.secondaryKey }.thenByDescending { rowValue(it, plan.metric) })
                else -> unsorted.sortedByDescending { rowValue(it, plan.metric) }
            }
        }
        val current = FinanceResult(
            plan = plan,
            rows = resultRows,
            incomeMinor = totalIncome,
            expenseMinor = totalExpense,
            refundMinor = totalRefund,
            transactionCount = matchedTransactionIds.size,
            currency = catalog.currency,
            generatedAt = System.currentTimeMillis(),
        )
        if (!plan.comparePrevious) return current
        val duration = plan.endAt - plan.startAt
        val previousResult = execute(plan.copy(startAt = plan.startAt - duration, endAt = plan.startAt, comparePrevious = false), catalog)
        return current.copy(
            previous = FinancePeriodSummary(previousResult.incomeMinor, previousResult.expenseMinor, previousResult.refundMinor, previousResult.transactionCount),
            previousRows = previousResult.rows,
        )
    }

    private fun dimensionValues(
        groupBy: FinanceGroupBy,
        plan: FinanceQueryPlan,
        fact: Fact,
        tagIds: List<String>,
        catalog: FinanceCatalog,
        zone: ZoneId,
    ): List<DimensionValue> = when (groupBy) {
        FinanceGroupBy.TIME -> {
            val date = Instant.ofEpochMilli(fact.transaction.occurredAt).atZone(zone)
            val value = when (plan.granularity) {
                TimeGranularity.DAY -> date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                TimeGranularity.MONTH -> date.format(DateTimeFormatter.ofPattern("yyyy-MM"))
                TimeGranularity.YEAR -> date.year.toString()
            }
            listOf(DimensionValue(value, value))
        }
        FinanceGroupBy.CATEGORY -> listOf(DimensionValue(fact.categoryId ?: "none", fact.categoryId?.let(catalog.categories::get) ?: "未分类"))
        FinanceGroupBy.ACCOUNT -> listOf(DimensionValue(fact.transaction.accountId, catalog.accounts[fact.transaction.accountId] ?: "未知账户"))
        FinanceGroupBy.MEMBER -> listOf(DimensionValue(fact.transaction.memberId ?: "none", fact.transaction.memberId?.let(catalog.members::get) ?: "未指定成员"))
        FinanceGroupBy.TAG -> tagIds.distinct().filter { plan.tagIds.isEmpty() || it in plan.tagIds }.map { DimensionValue(it, catalog.tags[it] ?: "未知标签") }.ifEmpty { listOf(DimensionValue("none", "未指定标签")) }
        FinanceGroupBy.MERCHANT -> listOf(DimensionValue(fact.transaction.merchantId ?: "none", fact.transaction.merchantId?.let(catalog.merchants::get) ?: "未指定商家"))
        FinanceGroupBy.PROJECT -> listOf(DimensionValue(fact.transaction.projectId ?: "none", fact.transaction.projectId?.let(catalog.projects::get) ?: "未指定项目"))
        FinanceGroupBy.TRANSACTION_TYPE -> listOf(DimensionValue(fact.transaction.type.name, fact.transaction.type.name))
        FinanceGroupBy.REIMBURSEMENT_STATUS -> listOf(DimensionValue(fact.transaction.reimbursementStatus.name, fact.transaction.reimbursementStatus.name))
        FinanceGroupBy.NONE -> listOf(DimensionValue("total", "合计"))
    }

    private fun filterForDimension(
        base: TransactionFilter,
        groupBy: FinanceGroupBy,
        key: String,
        granularity: TimeGranularity,
        timezone: String,
    ): TransactionFilter = when (groupBy) {
        FinanceGroupBy.TIME -> timeFilter(base, key, granularity, timezone)
        FinanceGroupBy.CATEGORY -> base.copy(categoryIds = setOf(key.takeUnless { it == "none" } ?: "__NONE__"))
        FinanceGroupBy.ACCOUNT -> base.copy(accountIds = setOf(key))
        FinanceGroupBy.MEMBER -> base.copy(memberIds = setOf(key.takeUnless { it == "none" } ?: "__NONE__"))
        FinanceGroupBy.TAG -> base.copy(tagIds = setOf(key.takeUnless { it == "none" } ?: "__NONE__"))
        FinanceGroupBy.MERCHANT -> base.copy(merchantIds = setOf(key.takeUnless { it == "none" } ?: "__NONE__"))
        FinanceGroupBy.PROJECT -> base.copy(projectIds = setOf(key.takeUnless { it == "none" } ?: "__NONE__"))
        FinanceGroupBy.TRANSACTION_TYPE -> base.copy(types = setOf(TransactionType.valueOf(key)))
        FinanceGroupBy.REIMBURSEMENT_STATUS -> base.copy(reimbursementStatuses = setOf(com.billrecord.shared.ReimbursementStatus.valueOf(key)))
        else -> base
    }

    private fun timeFilter(base: TransactionFilter, key: String, granularity: TimeGranularity, timezone: String): TransactionFilter {
        val zone = ZoneId.of(timezone)
        val start = when (granularity) {
            TimeGranularity.DAY -> java.time.LocalDate.parse(key).atStartOfDay(zone)
            TimeGranularity.MONTH -> java.time.YearMonth.parse(key).atDay(1).atStartOfDay(zone)
            TimeGranularity.YEAR -> java.time.LocalDate.of(key.toInt(), 1, 1).atStartOfDay(zone)
        }
        val end = when (granularity) {
            TimeGranularity.DAY -> start.plusDays(1)
            TimeGranularity.MONTH -> start.plusMonths(1)
            TimeGranularity.YEAR -> start.plusYears(1)
        }
        return base.copy(startEpochMillis = start.toInstant().toEpochMilli(), endEpochMillis = end.toInstant().toEpochMilli())
    }

    private fun categoryMatches(categoryIds: Set<String>, categoryId: String?): Boolean =
        categoryIds.isEmpty() || categoryId in categoryIds || (categoryId == null && "__NONE__" in categoryIds)

    private fun expandCategoryIds(categoryIds: Set<String>, parents: Map<String, String?>): Set<String> {
        if (categoryIds.isEmpty()) return emptySet()
        val expanded = categoryIds.toMutableSet()
        var changed: Boolean
        do {
            changed = false
            parents.forEach { (id, parentId) ->
                if (parentId in expanded && expanded.add(id)) changed = true
            }
        } while (changed)
        return expanded
    }

    companion object {
        fun validateBaseUrl(value: String): String {
            val normalized = value.trim().trimEnd('/')
            val uri = runCatching { URI(normalized) }.getOrNull() ?: error("Base URL 无效")
            require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()) { "Base URL 必须是 HTTPS 地址" }
            require(uri.query == null && uri.fragment == null) { "Base URL 不能包含查询参数或片段" }
            return normalized
        }
    }
}

private data class Fact(val transaction: TransactionEntity, val categoryId: String?, val amountMinor: Long)
private data class DimensionValue(val key: String, val label: String)

private class MutableAggregate(
    val key: String,
    val label: String,
    val secondaryKey: String?,
    val secondaryLabel: String?,
) {
    var income = 0L
    var expense = 0L
    var refund = 0L
    val transactionIds = mutableSetOf<String>()
    fun add(transaction: TransactionEntity, amount: Long) {
        when (transaction.type) {
            TransactionType.INCOME -> income += amount
            TransactionType.EXPENSE -> expense += amount
            TransactionType.REFUND -> refund += amount
            else -> Unit
        }
        transactionIds += transaction.id
    }
}

private fun rowValue(row: FinanceAggregateRow, metric: FinanceMetric): Long = when (metric) {
    FinanceMetric.INCOME -> row.incomeMinor
    FinanceMetric.EXPENSE -> row.netExpenseMinor
    FinanceMetric.NET -> row.balanceMinor
    FinanceMetric.COUNT -> row.count.toLong()
    FinanceMetric.SUMMARY -> maxOf(row.incomeMinor, row.netExpenseMinor)
}
