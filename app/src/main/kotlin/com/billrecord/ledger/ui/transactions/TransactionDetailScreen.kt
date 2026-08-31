package com.billrecord.ledger.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.billrecord.ledger.ui.components.LedgerCard
import com.billrecord.ledger.ui.components.LedgerSurfaceStyle
import com.billrecord.ledger.ui.components.MoneyText
import com.billrecord.ledger.ui.theme.ledgerColors
import com.billrecord.ledger.ui.components.formatMoney
import com.billrecord.shared.TransactionType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(
    canEdit: Boolean,
    onClose: () -> Unit,
    viewModel: TransactionDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state.deleted) { if (state.deleted) onClose() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账单详情") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
                actions = {
                    if (canEdit && state.transaction != null) {
                        IconButton(onClick = { editing = true }) { Icon(Icons.Outlined.Edit, "编辑账单") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, "删除账单") }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            state.transaction == null -> Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(state.message ?: "账单不存在") }
            else -> {
                val tx = requireNotNull(state.transaction)
                val category = state.categories.firstOrNull { it.id == tx.categoryId }
                val account = state.accounts.firstOrNull { it.id == tx.accountId }
                val destination = state.accounts.firstOrNull { it.id == tx.destinationAccountId }
                val positive = tx.type == TransactionType.INCOME || tx.type == TransactionType.REFUND
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        LedgerCard(Modifier.fillMaxWidth(), style = LedgerSurfaceStyle.Plain) {
                            Text(typeLabel(tx.type), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MoneyText(
                                tx.baseAmountMinor,
                                Modifier.padding(vertical = 8.dp),
                                color = if (positive) MaterialTheme.ledgerColors.income else MaterialTheme.ledgerColors.expense,
                                prefixSign = positive,
                                style = MaterialTheme.typography.displaySmall,
                            )
                        }
                    }
                    item {
                        LedgerCard(Modifier.fillMaxWidth()) {
                            Text("账单信息", fontWeight = FontWeight.Medium)
                            DetailLine("时间", formatTime(tx.occurredAt))
                            DetailLine("账户", account?.name ?: tx.accountId)
                            destination?.let { DetailLine("转入账户", it.name) }
                            DetailLine("分类", category?.name ?: "未分类")
                            DetailLine("原币金额", "${formatMoney(tx.amountMinor)} ${tx.currency}")
                            DetailLine("汇率", "1 ${tx.currency} = ${tx.exchangeRate} 基础币种")
                            state.memberName?.let { DetailLine("成员", it) }
                            state.merchantName?.let { DetailLine("商家 / 对象", it) }
                            state.projectName?.let { DetailLine("项目", it) }
                            if (state.tagNames.isNotEmpty()) DetailLine("标签", state.tagNames.joinToString("、"))
                            DetailLine("报销状态", reimbursementLabel(tx.reimbursementStatus.name))
                            tx.refundOfTransactionId?.let { DetailLine("退款关联", it) }
                        }
                    }
                    if (state.splits.isNotEmpty()) item {
                        LedgerCard(Modifier.fillMaxWidth()) {
                            Text("拆分明细", fontWeight = FontWeight.Medium)
                            state.splits.forEach { split ->
                                DetailLine(state.categories.firstOrNull { it.id == split.categoryId }?.name ?: "未分类", formatMoney(split.baseAmountMinor))
                            }
                        }
                    }
                    if (tx.note.isNotBlank()) item {
                        LedgerCard(Modifier.fillMaxWidth()) {
                            Text("备注", fontWeight = FontWeight.Medium)
                            Text(tx.note, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (state.attachments.isNotEmpty()) item {
                        LedgerCard(Modifier.fillMaxWidth()) {
                            Text("附件", fontWeight = FontWeight.Medium)
                            state.attachments.forEach { attachment -> DetailLine(attachment.displayName, "${attachment.sizeBytes / 1024} KB") }
                        }
                    }
                    state.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                }
            }
        }
    }
    val tx = state.transaction
    if (editing && tx != null) {
        EditTransactionDialog(
            transaction = tx,
            accounts = state.accounts,
            categories = state.categories.filter { it.type == if (tx.type == TransactionType.INCOME) TransactionType.INCOME else TransactionType.EXPENSE },
            hasSplits = state.splits.isNotEmpty(),
            onDismiss = { editing = false },
            onSave = { amount, accountId, categoryId, note, date ->
                viewModel.update(amount, accountId, categoryId, note, date)
                editing = false
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("移到回收站？") },
            text = { Text("账单可在明细页的回收站恢复。") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.padding(start = 16.dp), fontWeight = FontWeight.Medium)
    }
}

private fun typeLabel(type: TransactionType) = when (type) {
    TransactionType.EXPENSE -> "支出"
    TransactionType.INCOME -> "收入"
    TransactionType.TRANSFER -> "转账"
    TransactionType.REFUND -> "退款"
    TransactionType.ADJUSTMENT -> "余额调整"
}

private fun reimbursementLabel(value: String) = when (value) {
    "PENDING" -> "待报销"
    "REIMBURSED" -> "已报销"
    else -> "无"
}

private fun formatTime(epoch: Long) = Instant.ofEpochMilli(epoch).atZone(ZoneId.of("Asia/Shanghai"))
    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
