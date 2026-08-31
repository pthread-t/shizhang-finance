package com.billrecord.ledger.ui.transactions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Checklist
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.billrecord.ledger.ui.components.EmptyState
import com.billrecord.shared.TransactionType
import com.billrecord.shared.ReimbursementStatus
import com.billrecord.shared.TransactionFilter
import com.billrecord.ledger.data.local.SavedFilterEntity
import com.billrecord.ledger.data.export.ExportFormat
import com.billrecord.ledger.ui.components.StatusPill
import com.billrecord.ledger.ui.theme.ledgerColors
import com.billrecord.ledger.sync.SyncStatus
import com.billrecord.ledger.ui.components.SyncStatusIndicator
import com.billrecord.ledger.data.TransactionSort

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionListScreen(
    padding: PaddingValues,
    canEdit: Boolean,
    onOpenTransaction: (String) -> Unit,
    initialFilter: TransactionFilter? = null,
    onInitialFilterConsumed: () -> Unit = {},
    syncStatus: SyncStatus,
    onRetrySync: () -> Unit,
    viewModel: TransactionListViewModel = hiltViewModel(),
) {
    LaunchedEffect(initialFilter) {
        initialFilter?.let {
            viewModel.applyInitialFilter(it)
            onInitialFilterConsumed()
        }
    }
    val pagingItems = viewModel.transactions.collectAsLazyPagingItems()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selectedTypes by viewModel.selectedTypes.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val advanced by viewModel.advancedFilter.collectAsStateWithLifecycle()
    val savedFilters by viewModel.savedFilters.collectAsStateWithLifecycle()
    val recycleBin by viewModel.recycleBin.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val merchants by viewModel.merchants.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val splitTransactionIds by viewModel.splitTransactionIds.collectAsStateWithLifecycle()
    val exportMessage by viewModel.exportMessage.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    var showFilters by remember { mutableStateOf(false) }
    var showRecycleBin by remember { mutableStateOf(false) }
    var batchMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var showExportMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    val csvExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { viewModel.exportCurrent(it, ExportFormat.CSV) } }
    val xlsxExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri -> uri?.let { viewModel.exportCurrent(it, ExportFormat.XLSX) } }
    val typeOptions = listOf(null to "全部", TransactionType.EXPENSE to "支出", TransactionType.INCOME to "收入", TransactionType.TRANSFER to "转账", TransactionType.REFUND to "退款")
    val advancedCount = listOf(
        advanced.accountIds.size,
        advanced.categoryIds.size,
        advanced.tagIds.size,
        advanced.memberIds.size,
        advanced.merchantIds.size,
        advanced.projectIds.size,
        advanced.reimbursementStatuses.size,
        if (advanced.startEpochMillis != null || advanced.endEpochMillis != null) 1 else 0,
        if (advanced.minimumAmountMinor != null || advanced.maximumAmountMinor != null) 1 else 0,
    ).sum()

    Column(Modifier.fillMaxSize().padding(padding)) {
        TopAppBar(
            title = {
                Column {
                    Text("账单明细")
                    Text(if (pagingItems.itemCount == 0) "搜索、筛选和管理流水" else "当前已加载 ${pagingItems.itemCount} 笔", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            actions = {
                SyncStatusIndicator(syncStatus, onRetrySync)
                androidx.compose.foundation.layout.Box {
                    IconButton(onClick = { showSortMenu = true }) { Icon(Icons.Outlined.Sort, contentDescription = "排序：${sort.label}") }
                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        TransactionSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(if (option == sort) "✓ ${option.label}" else option.label) },
                                onClick = { viewModel.selectSort(option); showSortMenu = false },
                            )
                        }
                    }
                }
                if (canEdit) {
                    IconButton(onClick = { batchMode = !batchMode; if (!batchMode) selectedIds = emptySet() }) { Icon(Icons.Outlined.Checklist, contentDescription = "批量编辑") }
                    IconButton(onClick = { showRecycleBin = true }) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "回收站") }
                }
                androidx.compose.foundation.layout.Box {
                    IconButton(onClick = { showExportMenu = true }) { Icon(Icons.Outlined.Download, contentDescription = "导出当前筛选") }
                    DropdownMenu(expanded = showExportMenu, onDismissRequest = { showExportMenu = false }) {
                        DropdownMenuItem(text = { Text("导出 CSV") }, onClick = { showExportMenu = false; csvExportLauncher.launch("拾账-当前筛选.csv") })
                        DropdownMenuItem(text = { Text("导出 XLSX") }, onClick = { showExportMenu = false; xlsxExportLauncher.launch("拾账-当前筛选.xlsx") })
                    }
                }
                IconButton(onClick = { showFilters = true }) { Icon(Icons.Outlined.Tune, contentDescription = "组合筛选") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.query.value = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            label = { Text("搜索备注或精确金额") },
            singleLine = true,
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
            items(typeOptions) { (type, label) ->
                val selected = if (type == null) selectedTypes.isEmpty() else type in selectedTypes
                Surface(
                    onClick = { viewModel.selectType(type) },
                    modifier = Modifier.padding(end = 8.dp),
                    shape = MaterialTheme.shapes.small,
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                ) { Text(label, Modifier.padding(horizontal = 14.dp, vertical = 9.dp), style = MaterialTheme.typography.labelLarge) }
            }
        }
        if (advancedCount > 0) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                StatusPill("已启用 $advancedCount 项筛选", MaterialTheme.ledgerColors.info)
                TextButton(onClick = viewModel::resetAdvanced, modifier = Modifier.padding(start = 6.dp)) { Text("清除") }
            }
        }
        if (batchMode) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("已选 ${selectedIds.size}", Modifier.weight(1f).padding(vertical = 10.dp))
                TextButton(enabled = selectedIds.isNotEmpty(), onClick = { viewModel.batchSetReimbursement(selectedIds, ReimbursementStatus.PENDING); selectedIds = emptySet() }) { Text("待报销") }
                TextButton(enabled = selectedIds.isNotEmpty(), onClick = { viewModel.batchSetReimbursement(selectedIds, ReimbursementStatus.REIMBURSED); selectedIds = emptySet() }) { Text("已报销") }
                TextButton(enabled = selectedIds.isNotEmpty(), onClick = { viewModel.batchDelete(selectedIds); selectedIds = emptySet() }) { Text("删除") }
            }
        }

        if (pagingItems.itemCount == 0 && pagingItems.loadState.refresh is LoadState.NotLoading) {
            EmptyState("没有符合条件的账单", if (canEdit) "请调整搜索或筛选条件。" else "当前成员角色为只读；可调整筛选条件查看已有账单。")
        } else {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 104.dp)) {
                items(pagingItems.itemCount) { index ->
                    pagingItems[index]?.let { transaction ->
                        TransactionRow(
                            transaction,
                            categories.firstOrNull { it.id == transaction.categoryId }?.name,
                            accounts.firstOrNull { it.id == transaction.accountId }?.name,
                            selected = if (batchMode) transaction.id in selectedIds else null,
                            onSelectedChange = if (batchMode) { selected -> selectedIds = if (selected) selectedIds + transaction.id else selectedIds - transaction.id } else null,
                            onClick = if (batchMode) ({ selectedIds = if (transaction.id in selectedIds) selectedIds - transaction.id else selectedIds + transaction.id }) else ({ onOpenTransaction(transaction.id) }),
                        )
                        HorizontalDivider(Modifier.padding(start = 68.dp))
                    }
                }
            }
        }
    }

    if (showFilters) AdvancedFilterSheet(
        filter = advanced,
        accounts = accounts,
        categories = categories,
        tags = tags,
        members = members,
        merchants = merchants,
        projects = projects,
        savedFilters = savedFilters,
        onToggleAccount = viewModel::toggleAccount,
        onToggleCategory = viewModel::toggleCategory,
        onToggleTag = viewModel::toggleTag,
        onToggleMember = viewModel::toggleMember,
        onToggleMerchant = viewModel::toggleMerchant,
        onToggleProject = viewModel::toggleProject,
        onReimbursement = viewModel::setReimbursement,
        onSetRange = viewModel::setRange,
        onSetAmounts = viewModel::setAmountRange,
        onReset = viewModel::resetAdvanced,
        onSave = viewModel::saveCurrentFilter,
        onApplySaved = viewModel::applySavedFilter,
        onDismiss = { showFilters = false },
    )
    exportMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearExportMessage,
            title = { Text("导出结果") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::clearExportMessage) { Text("知道了") } },
        )
    }
    if (showRecycleBin) ModalBottomSheet(onDismissRequest = { showRecycleBin = false }) {
        Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            Text("回收站", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            Text("恢复会保留原始账单 ID；已经同步的删除会作为新版本恢复。", modifier = Modifier.padding(horizontal = 20.dp), color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            if (recycleBin.isEmpty()) {
                Text("回收站为空", modifier = Modifier.padding(20.dp), color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth()) {
                    items(recycleBin, key = { "deleted-${it.id}" }) { transaction ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(categories.firstOrNull { it.id == transaction.categoryId }?.name ?: transaction.type.name)
                                Text(transaction.note.ifBlank { transaction.id.take(8) }, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            TextButton(onClick = { viewModel.restore(transaction.id) }) {
                                Icon(Icons.Outlined.Restore, contentDescription = null)
                                Text("恢复")
                            }
                        }
                        HorizontalDivider(Modifier.padding(start = 20.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun EditTransactionDialog(
    transaction: com.billrecord.ledger.data.local.TransactionEntity,
    accounts: List<com.billrecord.ledger.data.local.AccountEntity>,
    categories: List<com.billrecord.ledger.data.local.CategoryEntity>,
    hasSplits: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String?, String, String) -> Unit,
) {
    var amount by remember(transaction.id) { mutableStateOf(java.math.BigDecimal(transaction.amountMinor).movePointLeft(2).stripTrailingZeros().toPlainString()) }
    var accountId by remember(transaction.id) { mutableStateOf(transaction.accountId) }
    var categoryId by remember(transaction.id) { mutableStateOf(transaction.categoryId) }
    var note by remember(transaction.id) { mutableStateOf(transaction.note) }
    var date by remember(transaction.id) { mutableStateOf(transaction.occurredAt.toDateText()) }
    val financialLocked = transaction.type == TransactionType.TRANSFER
    val account = accounts.firstOrNull { it.id == accountId }
    val category = categories.firstOrNull { it.id == categoryId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑账单") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it.filter { value -> value.isDigit() || value == '.' }.take(12) },
                label = { Text(if (hasSplits) "总金额（拆分账单不可改）" else "金额") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                enabled = !financialLocked && !hasSplits,
                singleLine = true,
            )
            OutlinedButton(
                enabled = !financialLocked && accounts.isNotEmpty(),
                onClick = {
                    val current = accounts.indexOfFirst { it.id == accountId }
                    accountId = accounts[if (current < 0) 0 else (current + 1) % accounts.size].id
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("账户：${account?.name ?: "未选择"}") }
            if (transaction.type !in setOf(TransactionType.TRANSFER, TransactionType.ADJUSTMENT)) {
                OutlinedButton(
                    enabled = !hasSplits && categories.isNotEmpty(),
                    onClick = {
                        val current = categories.indexOfFirst { it.id == categoryId }
                        categoryId = categories[(current + 1).coerceAtLeast(0) % categories.size].id
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (hasSplits) "分类：多个拆分项" else "分类：${category?.name ?: "未分类"}") }
            }
            OutlinedTextField(date, { date = it.take(10) }, label = { Text("日期 YYYY-MM-DD") }, singleLine = true)
            OutlinedTextField(note, { note = it.take(200) }, label = { Text("备注") }, minLines = 2)
            if (financialLocked) Text("转账的账户和金额为保证双边金额一致不可直接修改；日期与备注仍可编辑。", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        confirmButton = {
            val valid = amount.toBigDecimalOrNull()?.signum() == 1 && accountId.isNotBlank() && runCatching { java.time.LocalDate.parse(date) }.isSuccess
            TextButton(enabled = valid, onClick = { onSave(amount, accountId, categoryId, note, date) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedFilterSheet(
    filter: TransactionFilter,
    accounts: List<com.billrecord.ledger.data.local.AccountEntity>,
    categories: List<com.billrecord.ledger.data.local.CategoryEntity>,
    tags: List<com.billrecord.ledger.data.local.TagEntity>,
    members: List<com.billrecord.ledger.data.local.MembershipEntity>,
    merchants: List<com.billrecord.ledger.data.local.MerchantEntity>,
    projects: List<com.billrecord.ledger.data.local.ProjectEntity>,
    savedFilters: List<SavedFilterEntity>,
    onToggleAccount: (String) -> Unit,
    onToggleCategory: (String) -> Unit,
    onToggleTag: (String) -> Unit,
    onToggleMember: (String) -> Unit,
    onToggleMerchant: (String) -> Unit,
    onToggleProject: (String) -> Unit,
    onReimbursement: (ReimbursementStatus?) -> Unit,
    onSetRange: (String, String) -> Boolean,
    onSetAmounts: (String, String) -> Boolean,
    onReset: () -> Unit,
    onSave: (String) -> Unit,
    onApplySaved: (SavedFilterEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var start by remember(filter.startEpochMillis) { mutableStateOf(filter.startEpochMillis.toDateText()) }
    var end by remember(filter.endEpochMillis) { mutableStateOf(filter.endEpochMillis?.minus(1).toDateText()) }
    var minimum by remember(filter.minimumAmountMinor) { mutableStateOf(filter.minimumAmountMinor.toYuanText()) }
    var maximum by remember(filter.maximumAmountMinor) { mutableStateOf(filter.maximumAmountMinor.toYuanText()) }
    var saveName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        androidx.compose.foundation.lazy.LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Text("组合筛选", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall) }
            if (savedFilters.isNotEmpty()) {
                item {
                    Text("已保存", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    LazyRow { items(savedFilters, key = { it.id }) { saved -> AssistChip(onClick = { onApplySaved(saved); onDismiss() }, label = { Text(saved.name) }, modifier = Modifier.padding(end = 8.dp)) } }
                }
            }
            item {
                Text("时间范围", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(start, { start = it }, Modifier.weight(1f), label = { Text("开始 YYYY-MM-DD") }, singleLine = true)
                    OutlinedTextField(end, { end = it }, Modifier.weight(1f), label = { Text("结束 YYYY-MM-DD") }, singleLine = true)
                }
            }
            item {
                Text("金额范围", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(minimum, { minimum = it }, Modifier.weight(1f), label = { Text("最低元") }, singleLine = true)
                    OutlinedTextField(maximum, { maximum = it }, Modifier.weight(1f), label = { Text("最高元") }, singleLine = true)
                }
            }
            item {
                Text("账户", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                LazyRow { items(accounts, key = { it.id }) { account -> AssistChip(onClick = { onToggleAccount(account.id) }, label = { Text(if (account.id in filter.accountIds) "✓ ${account.name}" else account.name) }, modifier = Modifier.padding(end = 8.dp)) } }
            }
            item {
                Text("分类", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                LazyRow { items(categories, key = { it.id }) { category -> AssistChip(onClick = { onToggleCategory(category.id) }, label = { Text(if (category.id in filter.categoryIds) "✓ ${category.name}" else category.name) }, modifier = Modifier.padding(end = 8.dp)) } }
            }
            item {
                Text("报销状态", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                LazyRow {
                    item { AssistChip(onClick = { onReimbursement(null) }, label = { Text("全部") }, modifier = Modifier.padding(end = 8.dp)) }
                    items(listOf(ReimbursementStatus.PENDING to "待报销", ReimbursementStatus.REIMBURSED to "已报销")) { (status, label) ->
                        AssistChip(onClick = { onReimbursement(status) }, label = { Text(if (status in filter.reimbursementStatuses) "✓ $label" else label) }, modifier = Modifier.padding(end = 8.dp))
                    }
                }
            }
            if (tags.isNotEmpty()) item {
                Text("标签", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                LazyRow { items(tags, key = { it.id }) { tag -> AssistChip(onClick = { onToggleTag(tag.id) }, label = { Text(if (tag.id in filter.tagIds) "✓ ${tag.name}" else tag.name) }, modifier = Modifier.padding(end = 8.dp)) } }
            }
            if (members.isNotEmpty()) item {
                Text("成员", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                LazyRow { items(members, key = { it.userId }) { member -> AssistChip(onClick = { onToggleMember(member.userId) }, label = { Text(if (member.userId in filter.memberIds) "✓ ${member.displayName}" else member.displayName) }, modifier = Modifier.padding(end = 8.dp)) } }
            }
            if (merchants.isNotEmpty()) item {
                Text("商家 / 对象", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                LazyRow { items(merchants, key = { it.id }) { merchant -> AssistChip(onClick = { onToggleMerchant(merchant.id) }, label = { Text(if (merchant.id in filter.merchantIds) "✓ ${merchant.name}" else merchant.name) }, modifier = Modifier.padding(end = 8.dp)) } }
            }
            if (projects.isNotEmpty()) item {
                Text("项目", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                LazyRow { items(projects, key = { it.id }) { project -> AssistChip(onClick = { onToggleProject(project.id) }, label = { Text(if (project.id in filter.projectIds) "✓ ${project.name}" else project.name) }, modifier = Modifier.padding(end = 8.dp)) } }
            }
            item {
                OutlinedTextField(saveName, { saveName = it }, Modifier.fillMaxWidth(), label = { Text("保存当前筛选为…") }, singleLine = true)
                TextButton(enabled = saveName.isNotBlank(), onClick = { onSave(saveName); saveName = "" }) { Text("保存常用筛选") }
            }
            error?.let { message -> item { Text(message, color = androidx.compose.material3.MaterialTheme.colorScheme.error) } }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { onReset(); start = ""; end = ""; minimum = ""; maximum = "" }, Modifier.weight(1f)) { Text("重置") }
                    Button(onClick = {
                        val rangeOk = onSetRange(start, end)
                        val amountOk = onSetAmounts(minimum, maximum)
                        if (rangeOk && amountOk) onDismiss() else error = "请检查日期格式和金额范围"
                    }, Modifier.weight(1f)) { Text("应用") }
                }
            }
        }
    }
}

private fun Long?.toDateText(): String = this?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate().toString() } ?: ""
private fun Long?.toYuanText(): String = this?.let { java.math.BigDecimal(it).movePointLeft(2).stripTrailingZeros().toPlainString() } ?: ""
