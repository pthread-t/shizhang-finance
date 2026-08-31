package com.billrecord.ledger.ui.assistant

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.billrecord.ledger.ai.DrillPresentation
import com.billrecord.ledger.ui.components.LedgerCard
import com.billrecord.ledger.ui.components.MoneyText
import com.billrecord.ledger.ui.components.formatMoney
import com.billrecord.ledger.ui.transactions.TransactionRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AiDrillScreen(onClose: () -> Unit, viewModel: AiDrillViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val paging = viewModel.transactions.collectAsLazyPagingItems()
    fun back() { if (!viewModel.popLevel()) onClose() }
    BackHandler(onBack = ::back)
    Scaffold(topBar = { TopAppBar(title = { Text(state.levels.lastOrNull()?.label ?: "下钻分析") }, navigationIcon = { IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回上一级") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp)) {
                state.levels.forEachIndexed { index, level ->
                    TextButton(onClick = { viewModel.popTo(index) }, enabled = index < state.levels.lastIndex) { Text(level.label) }
                    if (index < state.levels.lastIndex) Text("›", Modifier.padding(top = 12.dp))
                }
            }
            when {
                state.loading -> androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.error != null -> LedgerCard(Modifier.fillMaxWidth().padding(20.dp)) { Text(state.error ?: "加载失败", color = MaterialTheme.colorScheme.error); Text("可返回上一级继续查看。", Modifier.padding(top = 8.dp)) }
                state.levels.lastOrNull()?.presentation == DrillPresentation.CHART && state.chart != null -> {
                    AiChart(requireNotNull(state.chart), Modifier.fillMaxWidth().padding(20.dp), viewModel::selectChartPoint)
                }
                state.levels.lastOrNull()?.presentation == DrillPresentation.DETAIL && state.detail != null -> TransactionDetail(requireNotNull(state.detail))
                else -> {
                    if (paging.itemCount == 0 && paging.loadState.refresh is LoadState.NotLoading) {
                        LedgerCard(Modifier.fillMaxWidth().padding(20.dp)) { Text("当前下钻条件没有原子账单") }
                    } else LazyColumn(Modifier.fillMaxSize()) {
                        items(paging.itemCount) { index ->
                            paging[index]?.let { tx ->
                                TransactionRow(tx, state.categories[tx.categoryId], state.accounts[tx.accountId], onClick = { viewModel.openTransaction(tx.id) })
                                HorizontalDivider(Modifier.padding(start = 68.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionDetail(detail: AiTransactionDetail) {
    val tx = detail.transaction
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            LedgerCard(Modifier.fillMaxWidth()) {
                Text(tx.type.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MoneyText(tx.baseAmountMinor, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.headlineMedium)
                DetailLine("原币金额", "${formatMoney(tx.amountMinor)} ${tx.currency}")
                DetailLine("汇率", tx.exchangeRate)
                DetailLine("时间", Instant.ofEpochMilli(tx.occurredAt).atZone(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                DetailLine("账户", detail.accountName ?: tx.accountId)
                detail.destinationAccountName?.let { DetailLine("目标账户", it) }
                DetailLine("分类", detail.categoryName ?: "未分类")
                detail.memberName?.let { DetailLine("成员", it) }
                detail.merchantName?.let { DetailLine("商家", it) }
                detail.projectName?.let { DetailLine("项目", it) }
                if (detail.tagNames.isNotEmpty()) DetailLine("标签", detail.tagNames.joinToString("、"))
                DetailLine("报销状态", tx.reimbursementStatus.name)
                tx.refundOfTransactionId?.let { DetailLine("退款关联", it) }
                DetailLine("备注", tx.note.ifBlank { "无" })
            }
        }
        if (detail.splits.isNotEmpty()) item {
            LedgerCard(Modifier.fillMaxWidth()) {
                Text("拆分明细", style = MaterialTheme.typography.titleMedium)
                detail.splits.forEach { (split, category) -> DetailLine(category ?: "未分类", formatMoney(split.baseAmountMinor)) }
            }
        }
        if (detail.attachments.isNotEmpty()) item {
            LedgerCard(Modifier.fillMaxWidth()) {
                Text("附件", style = MaterialTheme.typography.titleMedium)
                detail.attachments.forEach { Text("${it.displayName} · ${it.mimeType}", Modifier.padding(top = 6.dp)) }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 9.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(2f), fontFamily = FontFamily.Monospace)
    }
}
