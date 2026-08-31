package com.billrecord.ledger.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.billrecord.ledger.data.AnalyticsFilter
import com.billrecord.ledger.data.LedgerRepository
import com.billrecord.ledger.data.local.AccountBalance
import com.billrecord.ledger.data.local.AccountEntity
import com.billrecord.ledger.data.local.BudgetEntity
import com.billrecord.ledger.data.local.CategoryEntity
import com.billrecord.ledger.data.local.CategoryTotal
import com.billrecord.ledger.data.local.DailyExpense
import com.billrecord.ledger.data.local.DimensionTotal
import com.billrecord.ledger.data.local.MembershipEntity
import com.billrecord.ledger.data.local.MonthlySummary
import com.billrecord.ledger.data.local.PeriodSummary
import com.billrecord.shared.TransactionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

enum class ReportPeriod(val label: String) {
    MONTH("本月"), THREE_MONTHS("近三月"), YEAR("今年"), CUSTOM("自定义")
}

data class ReportSelection(
    val period: ReportPeriod = ReportPeriod.MONTH,
    val customStartAt: Long? = null,
    val customEndAt: Long? = null,
    val accountIds: Set<String> = emptySet(),
    val categoryIds: Set<String> = emptySet(),
    val memberIds: Set<String> = emptySet(),
) {
    val activeDimensionCount get() = accountIds.size + categoryIds.size + memberIds.size
}

data class ReportsUiState(
    val bookId: String? = null,
    val periodLabel: String = "本月",
    val startAt: Long = 0,
    val endAt: Long = 0,
    val selection: ReportSelection = ReportSelection(),
    val summary: MonthlySummary = MonthlySummary(0, 0, 0),
    val previousSummary: MonthlySummary = MonthlySummary(0, 0, 0),
    val categories: List<CategoryTotal> = emptyList(),
    val budgets: List<BudgetEntity> = emptyList(),
    val budgetUsage: Map<String, Long> = emptyMap(),
    val trend: List<PeriodSummary> = emptyList(),
    val dailyExpenses: List<DailyExpense> = emptyList(),
    val memberTotals: List<DimensionTotal> = emptyList(),
    val tagTotals: List<DimensionTotal> = emptyList(),
    val balances: List<AccountBalance> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
    val budgetCategories: List<CategoryEntity> = emptyList(),
    val members: List<MembershipEntity> = emptyList(),
    val allBooksSummary: MonthlySummary = MonthlySummary(0, 0, 0),
    val allBooksNetAssets: Long = 0,
)

private data class AnalyticsCore(
    val summary: MonthlySummary,
    val previous: MonthlySummary,
    val categories: List<CategoryTotal>,
    val trend: List<PeriodSummary>,
    val daily: List<DailyExpense>,
)

private data class ReportContext(
    val accounts: List<AccountEntity>,
    val balances: List<AccountBalance>,
    val categories: List<CategoryEntity>,
    val members: List<MembershipEntity>,
    val budgets: List<BudgetEntity>,
    val budgetUsage: Map<String, Long>,
)

@HiltViewModel
class ReportsViewModel @Inject constructor(private val repository: LedgerRepository) : ViewModel() {
    private val selection = MutableStateFlow(ReportSelection())

    private val stateFlow = combine(repository.observeSelectedBookId().filterNotNull(), selection) { bookId, selected ->
        bookId to selected
    }.flatMapLatest { (bookId, selected) ->
        val (start, end) = selected.range()
        val filter = AnalyticsFilter(bookId, start, end, selected.accountIds, selected.categoryIds, selected.memberIds)
        val duration = end - start
        val previousFilter = filter.copy(startAt = start - duration, endAt = start)
        val core = combine(
            repository.observeAnalyticsSummary(filter),
            repository.observeAnalyticsSummary(previousFilter),
            repository.observeAnalyticsCategories(filter),
            repository.observeAnalyticsTrend(filter),
            repository.observeAnalyticsDaily(filter),
        ) { summary, previous, categories, trend, daily -> AnalyticsCore(summary, previous, categories, trend, daily) }
        val budgetData = combine(repository.observeBudgets(bookId), repository.observeBudgetUsage(bookId)) { budgets, usage ->
            budgets to usage.associate { it.budgetId to it.usedMinor }
        }
        val context = combine(
            repository.observeAccounts(bookId),
            repository.observeAccountBalances(bookId),
            repository.observeCategories(bookId, TransactionType.EXPENSE),
            repository.observeMemberships(bookId),
            budgetData,
        ) { accounts, balances, categories, members, budgets ->
            ReportContext(accounts, balances, categories, members, budgets.first, budgets.second)
        }
        val details = combine(
            core,
            context,
            repository.observeAnalyticsMembers(filter),
            repository.observeAnalyticsTags(filter),
        ) { values, metadata, memberTotals, tagTotals ->
            ReportsUiState(
                bookId = bookId,
                periodLabel = selected.periodLabel(start, end),
                startAt = start,
                endAt = end,
                selection = selected,
                summary = values.summary,
                previousSummary = values.previous,
                categories = values.categories,
                budgets = metadata.budgets,
                budgetUsage = metadata.budgetUsage,
                trend = values.trend,
                dailyExpenses = values.daily,
                memberTotals = memberTotals,
                tagTotals = tagTotals,
                balances = metadata.balances,
                accounts = metadata.accounts,
                budgetCategories = metadata.categories,
                members = metadata.members,
            )
        }
        combine(
            details,
            repository.observeAllBooksSummary(start, end),
            repository.observeAllBooksNetAssets(),
        ) { values, allSummary, allNetAssets -> values.copy(allBooksSummary = allSummary, allBooksNetAssets = allNetAssets) }
    }

    val state = stateFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportsUiState())

    fun setPeriod(value: ReportPeriod) {
        if (value != ReportPeriod.CUSTOM) selection.value = selection.value.copy(period = value, customStartAt = null, customEndAt = null)
    }

    fun setCustomRange(start: String, end: String): Boolean {
        val zone = ZoneId.of("Asia/Shanghai")
        val startAt = runCatching { LocalDate.parse(start).atStartOfDay(zone).toInstant().toEpochMilli() }.getOrNull() ?: return false
        val endAt = runCatching { LocalDate.parse(end).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() }.getOrNull() ?: return false
        if (startAt >= endAt) return false
        selection.value = selection.value.copy(period = ReportPeriod.CUSTOM, customStartAt = startAt, customEndAt = endAt)
        return true
    }

    fun toggleAccount(id: String) = updateSelection { copy(accountIds = accountIds.toggle(id)) }
    fun toggleCategory(id: String) = updateSelection { copy(categoryIds = categoryIds.toggle(id)) }
    fun toggleMember(id: String) = updateSelection { copy(memberIds = memberIds.toggle(id)) }
    fun clearDimensions() = updateSelection { copy(accountIds = emptySet(), categoryIds = emptySet(), memberIds = emptySet()) }

    fun addBudget(name: String, amountYuan: String, rollover: Boolean, period: String, categoryId: String?, customStart: String, customEnd: String) = viewModelScope.launch {
        val bookId = state.value.bookId ?: return@launch
        val amount = amountYuan.trim().toBigDecimalOrNull()?.movePointRight(2)?.longValueExact() ?: return@launch
        val zone = ZoneId.of("Asia/Shanghai")
        val start = customStart.takeIf(String::isNotBlank)?.let { runCatching { LocalDate.parse(it).atStartOfDay(zone).toInstant().toEpochMilli() }.getOrNull() }
        val end = customEnd.takeIf(String::isNotBlank)?.let { runCatching { LocalDate.parse(it).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() }.getOrNull() }
        repository.createBudget(bookId, name, amount, period, rollover, categoryId, start, end)
    }

    private inline fun updateSelection(block: ReportSelection.() -> ReportSelection) { selection.value = selection.value.block() }
    private fun Set<String>.toggle(value: String) = if (value in this) this - value else this + value
}

private fun ReportSelection.range(): Pair<Long, Long> {
    if (period == ReportPeriod.CUSTOM && customStartAt != null && customEndAt != null) return customStartAt to customEndAt
    val zone = ZoneId.of("Asia/Shanghai")
    val today = LocalDate.now(zone)
    return when (period) {
        ReportPeriod.THREE_MONTHS -> YearMonth.from(today).minusMonths(2).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() to
            YearMonth.from(today).plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        ReportPeriod.YEAR -> today.withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli() to
            today.withDayOfYear(1).plusYears(1).atStartOfDay(zone).toInstant().toEpochMilli()
        else -> YearMonth.from(today).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() to
            YearMonth.from(today).plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}

private fun ReportSelection.periodLabel(start: Long, end: Long): String {
    if (period != ReportPeriod.CUSTOM) return period.label
    val zone = ZoneId.of("Asia/Shanghai")
    val startText = java.time.Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
    val endText = java.time.Instant.ofEpochMilli(end).atZone(zone).toLocalDate().minusDays(1)
    return "$startText 至 $endText"
}
