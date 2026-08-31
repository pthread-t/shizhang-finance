package com.billrecord.ledger.ai

import com.billrecord.shared.ReimbursementStatus
import com.billrecord.shared.TransactionFilter
import com.billrecord.shared.TransactionType
import kotlinx.serialization.Serializable

@Serializable
enum class AiProviderKind { DEEPSEEK, ZHIPU_GLM, OPENAI_COMPATIBLE }

@Serializable
enum class FinanceMetric { SUMMARY, INCOME, EXPENSE, NET, COUNT }

@Serializable
enum class FinanceGroupBy { NONE, TIME, CATEGORY, ACCOUNT, MEMBER, TAG, MERCHANT, PROJECT, TRANSACTION_TYPE, REIMBURSEMENT_STATUS }

@Serializable
enum class TimeGranularity { DAY, MONTH, YEAR }

@Serializable
data class FinanceQueryPlan(
    val bookId: String = "",
    val startAt: Long = 0,
    val endAt: Long = 0,
    val types: Set<TransactionType> = emptySet(),
    val accountIds: Set<String> = emptySet(),
    val categoryIds: Set<String> = emptySet(),
    val tagIds: Set<String> = emptySet(),
    val memberIds: Set<String> = emptySet(),
    val merchantIds: Set<String> = emptySet(),
    val projectIds: Set<String> = emptySet(),
    val minimumAmountMinor: Long? = null,
    val maximumAmountMinor: Long? = null,
    val reimbursementStatuses: Set<ReimbursementStatus> = emptySet(),
    val metric: FinanceMetric = FinanceMetric.SUMMARY,
    val groupBy: FinanceGroupBy = FinanceGroupBy.NONE,
    val secondaryGroupBy: FinanceGroupBy = FinanceGroupBy.NONE,
    val granularity: TimeGranularity = TimeGranularity.MONTH,
    val comparePrevious: Boolean = false,
) {
    fun transactionFilter() = TransactionFilter(
        bookIds = setOf(bookId),
        startEpochMillis = startAt,
        endEpochMillis = endAt,
        types = types,
        categoryIds = categoryIds,
        accountIds = accountIds,
        tagIds = tagIds,
        memberIds = memberIds,
        merchantIds = merchantIds,
        projectIds = projectIds,
        minimumAmountMinor = minimumAmountMinor,
        maximumAmountMinor = maximumAmountMinor,
        reimbursementStatuses = reimbursementStatuses,
    )
}

@Serializable
data class FinanceAggregateRow(
    val key: String,
    val label: String,
    val secondaryKey: String? = null,
    val secondaryLabel: String? = null,
    val incomeMinor: Long = 0,
    val expenseMinor: Long = 0,
    val refundMinor: Long = 0,
    val count: Int = 0,
    val filter: TransactionFilter,
) {
    val netExpenseMinor get() = expenseMinor - refundMinor
    val balanceMinor get() = incomeMinor - netExpenseMinor
}

@Serializable
data class FinanceResult(
    val plan: FinanceQueryPlan,
    val rows: List<FinanceAggregateRow>,
    val incomeMinor: Long,
    val expenseMinor: Long,
    val refundMinor: Long,
    val transactionCount: Int,
    val currency: String,
    val generatedAt: Long,
    val previous: FinancePeriodSummary? = null,
    val previousRows: List<FinanceAggregateRow> = emptyList(),
) {
    val netExpenseMinor get() = expenseMinor - refundMinor
    val balanceMinor get() = incomeMinor - netExpenseMinor
}

@Serializable
data class FinancePeriodSummary(
    val incomeMinor: Long,
    val expenseMinor: Long,
    val refundMinor: Long,
    val transactionCount: Int,
)

@Serializable
enum class AiChartKind { LINE, BAR, DONUT, HEATMAP, KPI }

@Serializable
data class ChartSeries(val name: String, val values: List<Long>)

@Serializable
data class ChartHeatmapCell(
    val xIndex: Int,
    val yIndex: Int,
    val value: Long,
    val pointId: String,
)

@Serializable
data class DrillPoint(
    val pointId: String,
    val label: String,
    val filter: TransactionFilter,
    val nextGranularity: TimeGranularity? = null,
)

@Serializable
data class ChartDescriptor(
    val kind: AiChartKind,
    val title: String,
    val labels: List<String>,
    val series: List<ChartSeries>,
    val points: List<DrillPoint>,
    val accessibilitySummary: String,
    val heatmapCells: List<ChartHeatmapCell> = emptyList(),
    val valueIsCount: Boolean = false,
    val currency: String = "CNY",
)

@Serializable
enum class DrillPresentation { CHART, TRANSACTIONS, DETAIL }

@Serializable
data class DrillLevel(
    val label: String,
    val filter: TransactionFilter,
    val presentation: DrillPresentation,
    val selectedPointId: String? = null,
    val transactionId: String? = null,
    val chartGroupBy: FinanceGroupBy? = null,
    val chartGranularity: TimeGranularity? = null,
)

data class FinanceCatalog(
    val bookId: String,
    val bookName: String,
    val currency: String,
    val timezone: String,
    val accounts: Map<String, String>,
    val categories: Map<String, String>,
    val categoryParents: Map<String, String?> = emptyMap(),
    val members: Map<String, String>,
    val tags: Map<String, String>,
    val merchants: Map<String, String>,
    val projects: Map<String, String>,
)
