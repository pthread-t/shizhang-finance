package com.billrecord.ledger.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.billrecord.ledger.ui.components.EmptyState
import com.billrecord.ledger.ui.components.LedgerCard
import com.billrecord.ledger.ui.components.MiniTrend
import com.billrecord.ledger.ui.components.BudgetBar
import com.billrecord.ledger.ui.components.SectionTitle
import com.billrecord.ledger.ui.components.StatusPill
import com.billrecord.ledger.ui.components.MoneyText
import com.billrecord.ledger.ui.transactions.TransactionRow
import com.billrecord.ledger.ui.theme.ledgerColors
import com.billrecord.ledger.sync.SyncStatus
import com.billrecord.ledger.ui.components.SyncStatusIndicator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    padding: PaddingValues,
    canEdit: Boolean,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onOpenTransaction: (String) -> Unit,
    syncStatus: SyncStatus,
    onRetrySync: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var bookMenu by remember { mutableStateOf(false) }
    val selectedBook = state.books.firstOrNull { it.id == state.selectedBookId }

    Column(Modifier.fillMaxSize().padding(padding)) {
        TopAppBar(
            title = {
                Surface(onClick = { bookMenu = true }, color = MaterialTheme.colorScheme.background) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(selectedBook?.name ?: "拾账", style = MaterialTheme.typography.titleLarge)
                        Icon(Icons.Outlined.ExpandMore, contentDescription = "切换账本")
                    }
                }
                DropdownMenu(expanded = bookMenu, onDismissRequest = { bookMenu = false }) {
                    state.books.forEach { book ->
                        DropdownMenuItem(
                            text = { Text(book.name) },
                            onClick = { viewModel.selectBook(book.id); bookMenu = false },
                        )
                    }
                }
            },
            actions = {
                SyncStatusIndicator(syncStatus, onRetrySync)
                IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "设置") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 112.dp)) {
            item { MonthlyOverview(state) }
            item {
                SectionTitle(
                    title = "最近流水",
                    subtitle = if (state.recent.isEmpty()) "从第一笔开始，建立你的资金脉络" else "最近 ${state.recent.size} 笔记录",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
            if (state.recent.isEmpty()) {
                item {
                    if (canEdit) EmptyState("账本还是空的", "先记下今天的第一笔，报表会随之生成。", "记一笔", onAdd)
                    else EmptyState("账本还是空的", "当前成员角色为只读，不能新增账单。")
                }
            } else {
                state.recent.groupBy { dayLabel(it.occurredAt) }.forEach { (day, transactions) ->
                    item(key = "day-$day") {
                        Text(
                            day,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(transactions, key = { it.id }) { transaction ->
                        TransactionRow(
                            transaction = transaction,
                            categoryName = state.categories.firstOrNull { it.id == transaction.categoryId }?.name,
                            accountName = state.accounts.firstOrNull { it.id == transaction.accountId }?.name,
                            onClick = { onOpenTransaction(transaction.id) },
                        )
                        HorizontalDivider(Modifier.padding(start = 72.dp, end = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthlyOverview(state: HomeUiState) {
    val semanticColors = MaterialTheme.ledgerColors
    val expense = state.summary.expenseMinor - state.summary.refundMinor
    val budget = state.budgets.firstOrNull { it.categoryId == null }
    val balance = state.summary.incomeMinor - expense
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("本月净结余", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            StatusPill(if (balance >= 0) "收支健康" else "支出偏高", if (balance >= 0) semanticColors.income else semanticColors.expense)
        }
        MoneyText(
            balance,
            color = if (balance >= 0) MaterialTheme.colorScheme.onSurface else semanticColors.expense,
            prefixSign = true,
            style = MaterialTheme.typography.displaySmall,
        )
        MiniTrend(
            state.trend.map { it.incomeMinor - (it.expenseMinor - it.refundMinor) },
            color = if (balance >= 0) MaterialTheme.colorScheme.primary else semanticColors.expense,
        )
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OverviewMetric("收入", state.summary.incomeMinor, semanticColors.income, Modifier.weight(1f))
                OverviewMetric("支出", expense, semanticColors.expense, Modifier.weight(1f))
                OverviewMetric("储蓄率", if (state.summary.incomeMinor > 0) balance * 10_000 / state.summary.incomeMinor else null, semanticColors.info, Modifier.weight(1f), percent = true)
            }
        }
        budget?.let {
            LedgerCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("本月预算", style = MaterialTheme.typography.titleMedium)
                        Text("已用 ${com.billrecord.ledger.ui.components.formatMoney(expense)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "剩余 ${com.billrecord.ledger.ui.components.formatMoney(it.amountMinor - expense)}",
                        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                        fontFamily = FontFamily.SansSerif,
                        color = if (expense > it.amountMinor) semanticColors.expense else MaterialTheme.colorScheme.onSurface,
                    )
                }
                BudgetBar(expense, it.amountMinor, Modifier.padding(top = 14.dp))
            }
        }
    }
}

@Composable
private fun OverviewMetric(label: String, amount: Long?, color: androidx.compose.ui.graphics.Color, modifier: Modifier, percent: Boolean = false) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (amount == null) "—" else if (percent) "${amount / 100.0}%" else com.billrecord.ledger.ui.components.formatMoney(amount),
            modifier = Modifier.padding(top = 3.dp),
            color = color,
            fontFamily = FontFamily.SansSerif,
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            maxLines = 1,
        )
    }
}

private fun dayLabel(epochMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (day) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> day.format(DateTimeFormatter.ofPattern("M月d日 E"))
    }
}
