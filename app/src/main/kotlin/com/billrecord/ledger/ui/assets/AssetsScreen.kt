package com.billrecord.ledger.ui.assets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.billrecord.ledger.ui.components.MoneyText
import com.billrecord.ledger.ui.components.LedgerCard
import com.billrecord.ledger.ui.components.BudgetBar
import com.billrecord.ledger.ui.components.StatusPill
import com.billrecord.ledger.ui.theme.ledgerColors
import com.billrecord.ledger.sync.SyncStatus
import com.billrecord.ledger.ui.components.SyncStatusIndicator
import com.billrecord.shared.AccountType

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AssetsScreen(
    padding: PaddingValues,
    canEdit: Boolean,
    syncStatus: SyncStatus,
    onRetrySync: () -> Unit,
    viewModel: AssetsViewModel = hiltViewModel(),
) {
    val semanticColors = MaterialTheme.ledgerColors
    val state by viewModel.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<AssetDialog?>(null) }
    val netAssets = state.accounts.sumOf { account ->
        val balance = state.balances[account.id]
        account.openingBalanceMinor + (balance?.postingBalanceMinor ?: 0)
    }
    val assetTotal = state.accounts.sumOf { account ->
        val value = account.openingBalanceMinor + (state.balances[account.id]?.postingBalanceMinor ?: 0)
        if (account.type in setOf(AccountType.LOAN, AccountType.LIABILITY, AccountType.CREDIT_CARD)) 0 else value.coerceAtLeast(0)
    }
    val liabilityTotal = state.accounts.sumOf { account ->
        val value = account.openingBalanceMinor + (state.balances[account.id]?.postingBalanceMinor ?: 0)
        if (account.type in setOf(AccountType.LOAN, AccountType.LIABILITY, AccountType.CREDIT_CARD)) kotlin.math.abs(value) else 0
    }
    Column(Modifier.fillMaxSize().padding(padding)) {
        TopAppBar(
            title = { Text("资产") },
            actions = {
                SyncStatusIndicator(syncStatus, onRetrySync)
                if (canEdit) IconButton(onClick = { dialog = AssetDialog.ACCOUNT }) { Icon(Icons.Outlined.Add, "新建账户") }
            },
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 104.dp)) {
            item {
                LedgerCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column {
                            Text("净资产", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                            MoneyText(netAssets, Modifier.padding(top = 6.dp), color = if (netAssets >= 0) MaterialTheme.colorScheme.onSurface else semanticColors.expense, style = MaterialTheme.typography.headlineMedium)
                        }
                        StatusPill(if (netAssets >= 0) "资产为正" else "负债偏高", if (netAssets >= 0) semanticColors.income else semanticColors.expense)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        AssetMetric("总资产", assetTotal, semanticColors.income, Modifier.weight(1f))
                        AssetMetric("总负债", liabilityTotal, semanticColors.expense, Modifier.weight(1f))
                    }
                }
            }
            item {
                SectionHeader("账户", if (canEdit) "新建" else null, onClick = { dialog = AssetDialog.ACCOUNT })
            }
            items(state.accounts, key = { it.id }) { account ->
                val total = account.openingBalanceMinor + (state.balances[account.id]?.postingBalanceMinor ?: 0)
                LedgerCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column { Text(account.name, fontWeight = FontWeight.Medium); Text(account.type.label(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        MoneyText(total, color = if (total < 0) semanticColors.expense else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            item { SectionHeader("存钱目标与愿望", if (canEdit) "添加" else null, onClick = { dialog = AssetDialog.GOAL }) }
            if (state.goals.isEmpty()) {
                item { Text("为旅行、应急金或心愿设一个看得见的进度。", Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(state.goals, key = { it.id }) { goal ->
                    LedgerCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(goal.name, fontWeight = FontWeight.Medium)
                            Text("${goal.currentAmountMinor / 100} / ${goal.targetAmountMinor / 100}", style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
                        }
                        BudgetBar(goal.currentAmountMinor, goal.targetAmountMinor, Modifier.padding(top = 10.dp))
                    }
                }
            }
            item { SectionHeader("周期账单", if (canEdit) "添加" else null, onClick = { dialog = AssetDialog.RECURRING }) }
            if (state.recurringRules.isEmpty()) {
                item { Text("房租、会员和固定缴费会按计划在本地生成。", Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(state.recurringRules, key = { it.id }) { rule ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text(rule.name, fontWeight = FontWeight.Medium); Text(rule.recurrenceRule.label(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(if (rule.enabled) "已启用" else "已停用", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item { SectionHeader("分期计划", if (canEdit) "添加" else null, onClick = { dialog = AssetDialog.INSTALLMENT }) }
            if (state.installmentPlans.isEmpty()) {
                item { Text("记录信用卡或大额消费分期，在到期日收到本地提醒。", Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(state.installmentPlans, key = { "installment-${it.id}" }) { plan ->
                    LedgerCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(plan.name, fontWeight = FontWeight.Medium)
                                Text("${plan.completedCount}/${plan.installmentCount} 期 · 下期 ${plan.firstDueAt.toDateText()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (canEdit && plan.completedCount < plan.installmentCount) TextButton(onClick = { viewModel.markInstallmentPaid(plan.id) }) { Text("记为已还") }
                        }
                        BudgetBar(plan.completedCount.toLong(), plan.installmentCount.toLong(), Modifier.padding(top = 10.dp))
                    }
                }
            }
        }
    }

    when (dialog) {
        AssetDialog.ACCOUNT -> AccountDialog(onDismiss = { dialog = null }) { name, type, opening, limit, statementDay, repaymentDay ->
            viewModel.addAccount(name, type, opening, limit, statementDay, repaymentDay); dialog = null
        }
        AssetDialog.GOAL -> GoalDialog(onDismiss = { dialog = null }) { name, target, wish ->
            viewModel.addGoal(name, target, wish); dialog = null
        }
        AssetDialog.RECURRING -> RecurringDialog(state.accounts.isNotEmpty(), onDismiss = { dialog = null }) { name, amount, frequency ->
            state.accounts.firstOrNull()?.let { viewModel.addRecurring(name, amount, it.id, frequency) }
            dialog = null
        }
        AssetDialog.INSTALLMENT -> InstallmentDialog(state.accounts, onDismiss = { dialog = null }) { name, total, count, accountId, dueDate ->
            viewModel.addInstallment(name, total, count, accountId, dueDate)
            dialog = null
        }
        null -> Unit
    }
}

@Composable
private fun AssetMetric(label: String, amount: Long, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(com.billrecord.ledger.ui.components.formatMoney(amount), style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), fontFamily = FontFamily.SansSerif, color = color, modifier = Modifier.padding(top = 3.dp))
    }
}

private enum class AssetDialog { ACCOUNT, GOAL, RECURRING, INSTALLMENT }

@Composable
private fun SectionHeader(title: String, action: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 18.dp, end = 12.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (action != null) TextButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun AccountDialog(onDismiss: () -> Unit, onSave: (String, AccountType, String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf("") }
    var creditLimit by remember { mutableStateOf("") }
    var statementDay by remember { mutableStateOf("") }
    var repaymentDay by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(AccountType.CASH) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建账户") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("账户名称") }, singleLine = true)
            OutlinedTextField(opening, { opening = it }, label = { Text("期初余额（元）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            OutlinedButton(onClick = { type = AccountType.entries[(type.ordinal + 1) % AccountType.entries.size] }, Modifier.fillMaxWidth()) { Text("类型：${type.label()}") }
            if (type == AccountType.CREDIT_CARD) {
                OutlinedTextField(creditLimit, { creditLimit = it }, label = { Text("信用额度（元，可选）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(statementDay, { statementDay = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label = { Text("账单日") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                    OutlinedTextField(repaymentDay, { repaymentDay = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label = { Text("还款日") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                }
            }
            Text("点按类型按钮可循环选择；信用卡可配置账期和本地还款提醒。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        confirmButton = {
            val cardDaysValid = type != AccountType.CREDIT_CARD || statementDay.toIntOrNull() in 1..31 && repaymentDay.toIntOrNull() in 1..31
            TextButton(enabled = name.isNotBlank() && cardDaysValid, onClick = { onSave(name, type, opening, creditLimit, statementDay, repaymentDay) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun GoalDialog(onDismiss: () -> Unit, onSave: (String, String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var wish by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加目标") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("目标名称") }, singleLine = true)
            OutlinedTextField(target, { target = it }, label = { Text("目标金额（元）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("加入愿望清单"); Checkbox(wish, { wish = it }) }
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank() && target.toBigDecimalOrNull()?.signum() == 1, onClick = { onSave(name, target, wish) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RecurringDialog(hasAccount: Boolean, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var frequency by remember { mutableStateOf("MONTHLY") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加周期账单") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!hasAccount) Text("请先创建一个账户。", color = MaterialTheme.colorScheme.error)
            OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
            OutlinedTextField(amount, { amount = it }, label = { Text("每期金额（元）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            OutlinedButton(onClick = { frequency = if (frequency == "MONTHLY") "WEEKLY" else if (frequency == "WEEKLY") "YEARLY" else "MONTHLY" }, Modifier.fillMaxWidth()) { Text("频率：${frequency.label()}") }
            Text("首次保存后立即生成一笔，后续由本地任务自动执行。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        confirmButton = { TextButton(enabled = hasAccount && name.isNotBlank() && amount.toBigDecimalOrNull()?.signum() == 1, onClick = { onSave(name, amount, frequency) }) { Text("启用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun InstallmentDialog(accounts: List<com.billrecord.ledger.data.local.AccountEntity>, onDismiss: () -> Unit, onSave: (String, String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var total by remember { mutableStateOf("") }
    var count by remember { mutableStateOf("12") }
    var accountIndex by remember { mutableStateOf(0) }
    var dueDate by remember { mutableStateOf(java.time.LocalDate.now().plusMonths(1).toString()) }
    val account = accounts.getOrNull(accountIndex)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加分期计划") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (accounts.isEmpty()) Text("请先创建一个账户。", color = MaterialTheme.colorScheme.error)
            OutlinedTextField(name, { name = it }, label = { Text("计划名称") }, singleLine = true)
            OutlinedTextField(total, { total = it }, label = { Text("分期总额（元）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            OutlinedTextField(count, { count = it.filter(Char::isDigit).take(3) }, label = { Text("总期数（2–120）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            OutlinedTextField(dueDate, { dueDate = it.take(10) }, label = { Text("首期日期 YYYY-MM-DD") }, singleLine = true)
            OutlinedButton(onClick = { if (accounts.isNotEmpty()) accountIndex = (accountIndex + 1) % accounts.size }, Modifier.fillMaxWidth()) { Text("扣款账户：${account?.name ?: "未选择"}") }
            Text("每次点按“记为已还”后推进到下个月；提醒完全在本机生成。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        confirmButton = {
            val validCount = count.toIntOrNull() in 2..120
            val validDate = runCatching { java.time.LocalDate.parse(dueDate) }.isSuccess
            TextButton(enabled = account != null && name.isNotBlank() && total.toBigDecimalOrNull()?.signum() == 1 && validCount && validDate, onClick = { onSave(name, total, count, requireNotNull(account).id, dueDate) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun AccountType.label() = when (this) {
    AccountType.CASH -> "现金"; AccountType.DEBIT_CARD -> "银行卡"; AccountType.CREDIT_CARD -> "信用卡"
    AccountType.E_WALLET -> "电子钱包"; AccountType.SAVINGS -> "储蓄"; AccountType.STORED_VALUE -> "储值卡"
    AccountType.INVESTMENT -> "投资"; AccountType.LOAN -> "借款"; AccountType.LIABILITY -> "负债"
}

private fun String.label() = when (this) { "WEEKLY" -> "每周"; "YEARLY" -> "每年"; else -> "每月" }
private fun Long.toDateText() = java.time.Instant.ofEpochMilli(this).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate().toString()
