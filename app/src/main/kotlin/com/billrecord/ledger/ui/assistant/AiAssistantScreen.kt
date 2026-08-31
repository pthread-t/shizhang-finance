package com.billrecord.ledger.ui.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.billrecord.ledger.ai.ChartDescriptor
import com.billrecord.ledger.ai.FinanceResult
import com.billrecord.ledger.data.local.AiMessageEntity
import com.billrecord.ledger.ui.components.LedgerCard
import kotlinx.serialization.json.Json

@Composable
fun AiAssistantScreen(
    onOpenSettings: () -> Unit,
    onOpenDrill: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AiAssistantViewModel = hiltViewModel(),
) {
    val profiles by viewModel.readyProfiles.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedConversationId.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }

    LaunchedEffect(conversations, selectedId) {
        if (selectedId == null && conversations.isNotEmpty()) viewModel.selectConversation(conversations.first().id)
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                OutlinedButton(onClick = { showHistory = true }) {
                    Icon(Icons.Outlined.History, null)
                    Text("历史 ${conversations.size}", Modifier.padding(start = 6.dp))
                }
                DropdownMenu(expanded = showHistory, onDismissRequest = { showHistory = false }) {
                    conversations.forEach { conversation ->
                        DropdownMenuItem(
                            text = { Text(conversation.title, maxLines = 1) },
                            onClick = { viewModel.selectConversation(conversation.id); showHistory = false },
                            trailingIcon = { IconButton(onClick = { viewModel.deleteConversation(conversation.id) }) { Icon(Icons.Outlined.DeleteOutline, "删除会话") } },
                        )
                    }
                }
            }
            IconButton(onClick = viewModel::newConversation) { Icon(Icons.Outlined.AddComment, "新建会话") }
            Box(Modifier.weight(1f))
            IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "AI 模型设置") }
        }

        if (profiles.none { it.enabled && it.isDefault }) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("尚未配置 AI 模型", style = MaterialTheme.typography.titleMedium)
                    Text("添加 DeepSeek、GLM 或 OpenAI 兼容接口后即可提问。", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    Button(onClick = onOpenSettings, modifier = Modifier.padding(top = 16.dp)) { Text("配置模型") }
                }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (messages.isEmpty()) item {
                    LedgerCard(Modifier.fillMaxWidth()) {
                        Text("可以这样问", style = MaterialTheme.typography.titleMedium)
                        Text("“今年每月餐饮支出趋势”\n“本月比上月多花在哪里”\n“近三个月支出最多的分类”", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                items(messages, key = { it.id }) { message ->
                    MessageCard(message, onOpenDrill = { pointId -> onOpenDrill(message.id, pointId) }, onRetry = { viewModel.retry(message) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    input,
                    { input = it },
                    Modifier.weight(1f),
                    label = { Text("询问当前账本") },
                    maxLines = 4,
                    enabled = !busy,
                )
                IconButton(
                    onClick = { val value = input; input = ""; viewModel.send(value) },
                    enabled = input.isNotBlank() && !busy,
                    modifier = Modifier.semantics { contentDescription = "发送问题" },
                ) { if (busy) CircularProgressIndicator() else Icon(Icons.AutoMirrored.Outlined.Send, null) }
            }
        }
    }
}

@Composable
private fun MessageCard(message: AiMessageEntity, onOpenDrill: (String) -> Unit, onRetry: () -> Unit) {
    val user = message.role == "USER"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(if (user) .84f else .96f),
        ) {
            Column(Modifier.padding(14.dp)) {
                when (message.status) {
                    "LOADING" -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(); Text("正在查询本机账本并生成分析…", Modifier.padding(start = 10.dp)) }
                    "ERROR" -> {
                        Text(message.errorMessage ?: "生成失败", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetry) { Text("重试") }
                    }
                    else -> Text(message.text)
                }
                if (!user && message.status == "COMPLETE") {
                    message.chartJson?.let { value ->
                        runCatching { Json.decodeFromString<ChartDescriptor>(value) }.getOrNull()?.let { chart ->
                            AiChart(chart, Modifier.fillMaxWidth().padding(top = 12.dp), onPoint = onOpenDrill)
                        }
                    }
                    val result = message.aggregateJson?.let { value -> runCatching { Json.decodeFromString<FinanceResult>(value) }.getOrNull() }
                    val scope = result?.let {
                        val zone = java.time.ZoneId.of("Asia/Shanghai")
                        val start = java.time.Instant.ofEpochMilli(it.plan.startAt).atZone(zone).toLocalDate()
                        val end = java.time.Instant.ofEpochMilli(it.plan.endAt).atZone(zone).toLocalDate().minusDays(1)
                        "$start 至 $end · ${it.currency} · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(it.generatedAt)}"
                    }
                    Text(
                        listOfNotNull(message.model ?: "本机", scope, "仅含聚合数据", "建议不构成专业投资意见").joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
}
