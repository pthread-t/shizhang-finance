package com.billrecord.ledger.ui.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.billrecord.ledger.data.local.BudgetEntity
import com.billrecord.ledger.data.local.DailyExpense
import com.billrecord.ledger.data.local.DimensionTotal
import com.billrecord.ledger.data.local.PeriodSummary
import com.billrecord.ledger.ui.components.BudgetBar
import com.billrecord.ledger.ui.components.CashFlowChart
import com.billrecord.ledger.ui.components.CategoryDonutChart
import com.billrecord.ledger.ui.components.LedgerCard
import com.billrecord.ledger.ui.components.LedgerSurfaceStyle
import com.billrecord.ledger.ui.components.MoneyText
import com.billrecord.ledger.ui.components.SectionTitle
import com.billrecord.ledger.ui.components.StatusPill
import com.billrecord.ledger.ui.components.formatMoney
import com.billrecord.ledger.ui.theme.ledgerColors
import com.billrecord.ledger.ui.assistant.AiAssistantScreen
import com.billrecord.shared.TransactionFilter
import com.billrecord.shared.TransactionType
import com.billrecord.ledger.sync.SyncStatus
import com.billrecord.ledger.ui.components.SyncStatusIndicator
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max

private enum class AnalysisTab(val label: String) { OVERVIEW("概览"), SPENDING("支出"), BUDGET("预算"), ASSISTANT("AI 助手") }

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    padding: PaddingValues,
    canEdit: Boolean,
    onOpenTransactions: (TransactionFilter) -> Unit,
    onOpenAiDrill: (String, String) -> Unit,
    onOpenAiSettings: () -> Unit,
    syncStatus: SyncStatus,
    onRetrySync: () -> Unit,
    viewModel: ReportsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(AnalysisTab.OVERVIEW) }
    var showBudgetDialog by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var showCustomRange by remember { mutableStateOf(false) }

    fun drill(filter: TransactionFilter = TransactionFilter()) {
        onOpenTransactions(
            filter.copy(
                bookIds = singleton(state.bookId),
                startEpochMillis = filter.startEpochMillis ?: state.startAt,
                endEpochMillis = filter.endEpochMillis ?: state.endAt,
            ),
        )
    }

    Column(Modifier.fillMaxSize().padding(padding)) {
        TopAppBar(
            title = {
                Column {
                    Text(if (tab == AnalysisTab.ASSISTANT) "AI 财务助手" else "分析")
                    Text(if (tab == AnalysisTab.ASSISTANT) "仅发送聚合数据" else state.periodLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            actions = {
                SyncStatusIndicator(syncStatus, onRetrySync)
                if (tab != AnalysisTab.ASSISTANT) {
                    IconButton(onClick = { showFilters = true }) {
                        Icon(Icons.Outlined.FilterList, contentDescription = "分析筛选")
                    }
                    if (canEdit) IconButton(onClick = { showBudgetDialog = true }) { Icon(Icons.Outlined.Add, "新建预算") }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        if (tab != AnalysisTab.ASSISTANT) {
            PeriodBar(
                selected = state.selection.period,
                activeDimensions = state.selection.activeDimensionCount,
                onPeriod = { if (it == ReportPeriod.CUSTOM) showCustomRange = true else viewModel.setPeriod(it) },
                onFilters = { showFilters = true },
            )
        }
        AnalysisTabBar(tab, onSelect = { tab = it })
        when (tab) {
            AnalysisTab.OVERVIEW -> OverviewTab(state, ::drill)
            AnalysisTab.SPENDING -> SpendingTab(state, ::drill)
            AnalysisTab.BUDGET -> BudgetTab(state, canEdit, { showBudgetDialog = true }, ::drill)
            AnalysisTab.ASSISTANT -> AiAssistantScreen(onOpenSettings = onOpenAiSettings, onOpenDrill = onOpenAiDrill)
        }
    }

    if (showFilters) AnalysisFilterSheet(
        state = state,
        onAccount = viewModel::toggleAccount,
        onCategory = viewModel::toggleCategory,
        onMember = viewModel::toggleMember,
        onClear = viewModel::clearDimensions,
        onDismiss = { showFilters = false },
    )
    if (showCustomRange) CustomRangeDialog(
        onDismiss = { showCustomRange = false },
        onApply = { start, end -> viewModel.setCustomRange(start, end).also { if (it) showCustomRange = false } },
    )
    if (canEdit && showBudgetDialog) BudgetDialog(
        categories = state.budgetCategories,
        onDismiss = { showBudgetDialog = false },
        onSave = { name, amount, rollover, period, categoryId, start, end ->
            viewModel.addBudget(name, amount, rollover, period, categoryId, start, end)
            showBudgetDialog = false
        },
    )
}

@Composable
internal fun PeriodBar(
    selected: ReportPeriod,
    activeDimensions: Int,
    onPeriod: (ReportPeriod) -> Unit,
    onFilters: () -> Unit,
) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ReportPeriod.entries) { period ->
            Surface(
                onClick = { onPeriod(period) },
                shape = MaterialTheme.shapes.small,
                color = if (selected == period) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                contentColor = if (selected == period) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(period.label, Modifier.padding(horizontal = 14.dp, vertical = 9.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
        if (activeDimensions > 0) {
            item { AssistChip(onClick = onFilters, label = { Text("$activeDimensions 项维度") }) }
        }
    }
}

@Composable
private fun AnalysisTabBar(selected: AnalysisTab, onSelect: (AnalysisTab) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AnalysisTab.entries.forEach { tab ->
                Surface(
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                    color = if (selected == tab) MaterialTheme.colorScheme.surface else Color.Transparent,
                    contentColor = if (selected == tab) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    shadowElevation = if (selected == tab) 1.dp else 0.dp,
                ) {
                    Text(tab.label, Modifier.padding(vertical = 9.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun OverviewTab(state: ReportsUiState, drill: (TransactionFilter) -> Unit) {
    val semanticColors = MaterialTheme.ledgerColors
    val expense = state.summary.expenseMinor - state.summary.refundMinor
    val previousExpense = state.previousSummary.expenseMinor - state.previousSummary.refundMinor
    val balance = state.summary.incomeMinor - expense
    val savingsRate = if (state.summary.incomeMinor > 0) balance * 100.0 / state.summary.incomeMinor else null
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            LedgerCard(Modifier.fillMaxWidth(), onClick = { drill(TransactionFilter()) }, style = LedgerSurfaceStyle.Plain) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("本期净结余", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StatusPill(savingsRate?.let { "储蓄率 %.1f%%".format(it) } ?: "暂无收入基线", if (balance >= 0) semanticColors.income else semanticColors.expense)
                }
                MoneyText(balance, Modifier.padding(top = 8.dp), color = if (balance >= 0) MaterialTheme.colorScheme.onSurface else semanticColors.expense, prefixSign = true, style = MaterialTheme.typography.displaySmall)
                Text(if (balance >= 0) "本期保持盈余" else "本期支出超过收入", Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KpiCard("收入", state.summary.incomeMinor, changeText(state.summary.incomeMinor, state.previousSummary.incomeMinor), semanticColors.income, Modifier.weight(1f)) {
                    drill(TransactionFilter(types = setOf(TransactionType.INCOME)))
                }
                KpiCard("净支出", expense, changeText(expense, previousExpense), semanticColors.expense, Modifier.weight(1f)) {
                    drill(TransactionFilter(types = setOf(TransactionType.EXPENSE, TransactionType.REFUND)))
                }
            }
        }
        item {
            LedgerCard(Modifier.fillMaxWidth()) {
                SectionTitle("收支走势", "点按数据点查看当期流水")
                CashFlowChart(state.trend, Modifier.fillMaxWidth()) { point ->
                    val (start, end) = point.periodRange()
                    drill(TransactionFilter(startEpochMillis = start, endEpochMillis = end))
                }
            }
        }
        item {
            LedgerCard(Modifier.fillMaxWidth()) {
                SectionTitle("资金去向", "按净支出统计，退款已冲减")
                CategoryDonutChart(state.categories, Modifier.fillMaxWidth()) { category ->
                    drill(TransactionFilter(types = setOf(TransactionType.EXPENSE, TransactionType.REFUND), categoryIds = singleton(category.categoryId)))
                }
            }
        }
        item { InsightCard(state) }
    }
}

@Composable
private fun SpendingTab(state: ReportsUiState, drill: (TransactionFilter) -> Unit) {
    val categoryMaximum = state.categories.maxOfOrNull { it.amountMinor } ?: 1L
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            LedgerCard(Modifier.fillMaxWidth()) {
                SectionTitle("分类构成", "点按分类查看对应账单")
                CategoryDonutChart(state.categories, Modifier.fillMaxWidth()) { category ->
                    drill(TransactionFilter(types = setOf(TransactionType.EXPENSE, TransactionType.REFUND), categoryIds = singleton(category.categoryId)))
                }
            }
        }
        if (state.categories.isNotEmpty()) {
            item {
                LedgerCard(Modifier.fillMaxWidth()) {
                    SectionTitle("分类排行", "净支出从高到低")
                    state.categories.take(8).forEachIndexed { index, item ->
                        RankingRow(index + 1, item.categoryName ?: "未分类", item.amountMinor, categoryMaximum) {
                            drill(TransactionFilter(types = setOf(TransactionType.EXPENSE, TransactionType.REFUND), categoryIds = singleton(item.categoryId)))
                        }
                    }
                }
            }
        }
        item {
            LedgerCard(Modifier.fillMaxWidth()) {
                SectionTitle("每日支出热力图", "颜色越深，净支出越高")
                ExpenseHeatmap(state.dailyExpenses)
            }
        }
        if (state.memberTotals.isNotEmpty()) {
            item { DimensionRanking("成员支出", state.memberTotals) { id -> drill(TransactionFilter(memberIds = singleton(id))) } }
        }
        if (state.tagTotals.isNotEmpty()) {
            item { DimensionRanking("标签排行", state.tagTotals) { id -> drill(TransactionFilter(tagIds = singleton(id))) } }
        }
    }
}

@Composable
private fun BudgetTab(
    state: ReportsUiState,
    canEdit: Boolean,
    onAdd: () -> Unit,
    drill: (TransactionFilter) -> Unit,
) {
    val semanticColors = MaterialTheme.ledgerColors
    val totalBudget = state.budgets.sumOf { it.amountMinor }
    val totalUsed = state.budgets.sumOf { state.budgetUsage[it.id] ?: 0L }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            LedgerCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("预算执行", style = MaterialTheme.typography.titleMedium)
                        MoneyText(totalUsed, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.headlineMedium)
                    }
                    StatusPill(
                        if (totalBudget == 0L) "未设置" else "${totalUsed * 100 / totalBudget.coerceAtLeast(1L)}%",
                        if (totalBudget > 0 && totalUsed >= totalBudget) semanticColors.expense else if (totalBudget > 0 && totalUsed * 100 >= totalBudget * 80) semanticColors.warning else semanticColors.income,
                    )
                }
                Text("总预算 ${formatMoney(totalBudget)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
                BudgetBar(totalUsed, totalBudget, Modifier.padding(top = 14.dp))
            }
        }
        if (state.budgets.isEmpty()) {
            item {
                LedgerCard(Modifier.fillMaxWidth()) {
                    Text("还没有预算", style = MaterialTheme.typography.titleMedium)
                    Text("设置总预算或分类预算后，这里会显示执行进度和风险状态。", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 7.dp))
                    if (canEdit) Button(onClick = onAdd, modifier = Modifier.padding(top = 16.dp)) { Text("添加预算") }
                }
            }
        } else {
            items(state.budgets.sortedByDescending { (state.budgetUsage[it.id] ?: 0L).toDouble() / it.amountMinor.coerceAtLeast(1L) }, key = { it.id }) { budget ->
                BudgetCard(budget, state.budgetUsage[budget.id] ?: 0L) {
                    drill(
                        TransactionFilter(
                            startEpochMillis = budget.startAt,
                            endEpochMillis = budget.endAt,
                            categoryIds = singleton(budget.categoryId),
                            types = setOf(TransactionType.EXPENSE, TransactionType.REFUND),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun KpiCard(label: String, amount: Long, comparison: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    LedgerCard(modifier, onClick) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MoneyText(amount, Modifier.padding(top = 6.dp), color = color, style = MaterialTheme.typography.titleLarge)
        Text(comparison, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
private fun InsightCard(state: ReportsUiState) {
    val expense = state.summary.expenseMinor - state.summary.refundMinor
    val top = state.categories.firstOrNull()
    LedgerCard(Modifier.fillMaxWidth()) {
        SectionTitle("本期洞察", "基于本地账本实时计算")
        if (expense <= 0) {
            Text("有支出记录后，这里会总结主要变化。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                "${top?.categoryName ?: "未分类"}是最大支出项，占本期净支出的 ${top?.amountMinor?.times(100)?.div(expense.coerceAtLeast(1L)) ?: 0}%。",
                style = MaterialTheme.typography.bodyLarge,
            )
            val previous = state.previousSummary.expenseMinor - state.previousSummary.refundMinor
            Text(
                "整体支出较上一等长周期${if (expense >= previous) "增加" else "减少"}${changeText(expense, previous).removePrefix("+").removePrefix("-")}。",
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DimensionRanking(title: String, values: List<DimensionTotal>, onClick: (String?) -> Unit) {
    val maximum = values.maxOfOrNull { it.amountMinor } ?: 1L
    LedgerCard(Modifier.fillMaxWidth()) {
        SectionTitle(title, "前 5 项")
        values.take(5).forEachIndexed { index, value ->
            RankingRow(index + 1, value.name ?: "未指定", value.amountMinor, maximum) { onClick(value.id) }
        }
    }
}

@Composable
private fun RankingRow(index: Int, name: String, amount: Long, maximum: Long, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(index.toString().padStart(2, '0'), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Row(Modifier.fillMaxWidth()) {
                Text(name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                Text(formatMoney(amount), fontFamily = FontFamily.SansSerif, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
            }
            Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.extraLarge)) {
                Box(Modifier.fillMaxWidth((amount.toFloat() / maximum.coerceAtLeast(1L)).coerceIn(0f, 1f)).height(4.dp).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraLarge))
            }
        }
    }
}

@Composable
private fun BudgetCard(budget: BudgetEntity, used: Long, onClick: () -> Unit) {
    val semanticColors = MaterialTheme.ledgerColors
    val ratio = used.toFloat() / budget.amountMinor.coerceAtLeast(1L)
    LedgerCard(Modifier.fillMaxWidth(), onClick) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(budget.name, style = MaterialTheme.typography.titleMedium)
                Text(budget.period.budgetPeriodLabel(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusPill(if (ratio >= 1f) "已超支" else if (ratio >= .8f) "接近上限" else "正常", if (ratio >= 1f) semanticColors.expense else if (ratio >= .8f) semanticColors.warning else semanticColors.income)
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("已用 ${formatMoney(used)}", style = MaterialTheme.typography.bodyMedium)
            Text("预算 ${formatMoney(budget.amountMinor)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BudgetBar(used, budget.amountMinor, Modifier.padding(top = 10.dp))
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AnalysisFilterSheet(
    state: ReportsUiState,
    onAccount: (String) -> Unit,
    onCategory: (String) -> Unit,
    onMember: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
            SectionTitle("分析筛选", "所有图表使用相同筛选口径", if (state.selection.activeDimensionCount > 0) "清除" else null, onClear)
            Text("账户", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.accounts, key = { it.id }) { item ->
                    FilterChip(selected = item.id in state.selection.accountIds, onClick = { onAccount(item.id) }, label = { Text(item.name) })
                }
            }
            Text("分类", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.budgetCategories, key = { it.id }) { item ->
                    FilterChip(selected = item.id in state.selection.categoryIds, onClick = { onCategory(item.id) }, label = { Text(item.name) })
                }
            }
            if (state.members.isNotEmpty()) {
                Text("成员", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.members, key = { it.userId }) { item ->
                        FilterChip(selected = item.userId in state.selection.memberIds, onClick = { onMember(item.userId) }, label = { Text(item.displayName) })
                    }
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)) { Text("查看分析") }
        }
    }
}

@Composable
private fun CustomRangeDialog(onDismiss: () -> Unit, onApply: (String, String) -> Boolean) {
    var start by remember { mutableStateOf(LocalDate.now().withDayOfMonth(1).toString()) }
    var end by remember { mutableStateOf(LocalDate.now().toString()) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义分析周期") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(start, { start = it.take(10); error = false }, label = { Text("开始日期") }, placeholder = { Text("YYYY-MM-DD") }, singleLine = true)
                OutlinedTextField(end, { end = it.take(10); error = false }, label = { Text("结束日期") }, placeholder = { Text("YYYY-MM-DD") }, singleLine = true)
                if (error) Text("请输入有效日期，且结束日期不得早于开始日期。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { if (!onApply(start, end)) error = true }) { Text("应用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ExpenseHeatmap(values: List<DailyExpense>) {
    val expenseColor = MaterialTheme.ledgerColors.expense
    if (values.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
            Text("当前周期暂无每日支出", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val dates = values.map { LocalDate.parse(it.day) }
    val first = dates.minOrNull() ?: return
    val last = dates.maxOrNull() ?: first
    val alignedFirst = first.minusDays((first.dayOfWeek.value - 1).toLong())
    val weekCount = (((last.toEpochDay() - alignedFirst.toEpochDay()) / 7) + 1).toInt()
    val byDay = values.associate { LocalDate.parse(it.day) to it.amountMinor.coerceAtLeast(0) }
    val maximum = max(1L, byDay.values.maxOrNull() ?: 1L)
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(7) { weekday ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(weekCount) { week ->
                    val date = alignedFirst.plusDays((week * 7 + weekday).toLong())
                    val amount = byDay[date] ?: 0L
                    val intensity = if (amount == 0L) 0f else (0.18f + 0.82f * amount.toFloat() / maximum).coerceIn(.18f, 1f)
                    Box(Modifier.size(9.dp).background(if (amount == 0L) MaterialTheme.colorScheme.surfaceVariant else expenseColor.copy(alpha = intensity), MaterialTheme.shapes.extraSmall))
                }
            }
        }
    }
}

private fun changeText(current: Long, previous: Long): String {
    if (previous == 0L) return if (current == 0L) "与上期持平" else "上期无基线"
    val percent = (current - previous) * 100.0 / abs(previous.toDouble())
    return "较上期 %+.1f%%".format(percent)
}

private fun PeriodSummary.periodRange(): Pair<Long, Long> {
    val zone = ZoneId.of("Asia/Shanghai")
    return if (period.length == 10) {
        val date = LocalDate.parse(period)
        date.atStartOfDay(zone).toInstant().toEpochMilli() to date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    } else {
        val month = YearMonth.parse(period)
        month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() to month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}

@Composable
private fun BudgetDialog(
    categories: List<com.billrecord.ledger.data.local.CategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (String, String, Boolean, String, String?, String, String) -> Unit,
) {
    var name by remember { mutableStateOf("家庭月预算") }
    var amount by remember { mutableStateOf("") }
    var rollover by remember { mutableStateOf(false) }
    var period by remember { mutableStateOf("MONTH") }
    var categoryIndex by remember { mutableStateOf(-1) }
    var customStart by remember { mutableStateOf("") }
    var customEnd by remember { mutableStateOf("") }
    val selectedCategory = categories.getOrNull(categoryIndex)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建预算") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("预算名称") }, singleLine = true)
                OutlinedTextField(amount, { amount = it }, label = { Text("金额（元）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                OutlinedButton(onClick = { period = when (period) { "WEEK" -> "MONTH"; "MONTH" -> "YEAR"; "YEAR" -> "CUSTOM"; else -> "WEEK" } }, Modifier.fillMaxWidth()) { Text("周期：${period.budgetPeriodLabel()}") }
                OutlinedButton(onClick = { categoryIndex = if (categoryIndex + 1 >= categories.size) -1 else categoryIndex + 1 }, Modifier.fillMaxWidth()) { Text("范围：${selectedCategory?.name ?: "全部支出"}") }
                if (period == "CUSTOM") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(customStart, { customStart = it.take(10) }, Modifier.weight(1f), label = { Text("开始日期") }, placeholder = { Text("YYYY-MM-DD") }, singleLine = true)
                        OutlinedTextField(customEnd, { customEnd = it.take(10) }, Modifier.weight(1f), label = { Text("结束日期") }, placeholder = { Text("YYYY-MM-DD") }, singleLine = true)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("未用金额滚存"); Switch(rollover, { rollover = it }) }
            }
        },
        confirmButton = {
            val customValid = period != "CUSTOM" || runCatching { LocalDate.parse(customStart) < LocalDate.parse(customEnd) }.getOrDefault(false)
            TextButton(enabled = name.isNotBlank() && amount.toBigDecimalOrNull()?.signum() == 1 && customValid, onClick = { onSave(name, amount, rollover, period, selectedCategory?.id, customStart, customEnd) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun String.budgetPeriodLabel() = when (this) { "WEEK" -> "每周"; "YEAR" -> "每年"; "CUSTOM" -> "自定义"; else -> "每月" }

private fun <T : Any> singleton(value: T?): Set<T> = value?.let(::setOf) ?: emptySet()
