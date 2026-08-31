package com.billrecord.ledger.ui.tags

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.billrecord.ledger.data.local.TagUsage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagManagementScreen(
    canEdit: Boolean,
    onClose: () -> Unit,
    viewModel: TagManagementViewModel = hiltViewModel(),
) {
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var search by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<TagUsage?>(null) }
    var deleting by remember { mutableStateOf<TagUsage?>(null) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("标签管理") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
                actions = { if (canEdit) IconButton(onClick = { creating = true }) { Icon(Icons.Outlined.Add, "新建标签") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it.take(40) },
                label = { Text("搜索标签") },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                singleLine = true,
            )
            LazyColumn(Modifier.fillMaxSize()) {
                val filtered = tags.filter { search.isBlank() || it.name.contains(search, ignoreCase = true) }
                items(filtered, key = { it.id }) { tag ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(tag.name, fontWeight = FontWeight.Medium)
                            Text("关联 ${tag.usageCount} 笔账单", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (canEdit) {
                            IconButton(onClick = { editing = tag }) { Icon(Icons.Outlined.Edit, "重命名 ${tag.name}") }
                            IconButton(onClick = { deleting = tag }) { Icon(Icons.Outlined.DeleteOutline, "删除 ${tag.name}") }
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 20.dp))
                }
            }
        }
    }
    if (creating || editing != null) {
        var name by remember(creating, editing?.id) { mutableStateOf(editing?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { creating = false; editing = null },
            title = { Text(if (creating) "新建标签" else "重命名标签") },
            text = { OutlinedTextField(name, { name = it.take(40) }, label = { Text("标签名称") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    editing?.let { viewModel.rename(it.id, name) } ?: viewModel.create(name)
                    creating = false; editing = null
                }, enabled = name.isNotBlank()) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { creating = false; editing = null }) { Text("取消") } },
        )
    }
    deleting?.let { tag ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除标签“${tag.name}”？") },
            text = {
                Text(if (tag.usageCount == 0L) "该标签尚未关联账单。删除后可在其他设备同步移除。" else "该标签关联了 ${tag.usageCount} 笔账单。删除会清除这些账单的标签关联，但不会删除账单。")
            },
            confirmButton = { TextButton(onClick = { viewModel.delete(tag.id); deleting = null }) { Text("确认删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}
