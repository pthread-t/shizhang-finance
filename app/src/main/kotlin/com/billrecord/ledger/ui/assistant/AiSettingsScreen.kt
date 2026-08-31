package com.billrecord.ledger.ui.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.billrecord.ledger.ai.AiProviderKind
import com.billrecord.ledger.data.local.AiProviderProfileEntity
import com.billrecord.ledger.ui.components.LedgerCard
import com.billrecord.ledger.ui.components.SectionTitle

private data class ProviderDraft(
    val id: String? = null,
    val name: String,
    val kind: AiProviderKind,
    val baseUrl: String,
    val model: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(onClose: () -> Unit, viewModel: AiSettingsViewModel = hiltViewModel()) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var draft by remember { mutableStateOf<ProviderDraft?>(null) }
    var deleteTarget by remember { mutableStateOf<AiProviderProfileEntity?>(null) }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() } }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("AI 模型") }, navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } }) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LedgerCard(Modifier.fillMaxWidth()) {
                SectionTitle("数据边界", "启用前请确认")
                Text("问题、维度名称和本机计算的聚合结果会直接发送给所选模型供应商。原子账单、备注、附件和交易 ID 不会发送。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionTitle("模型配置", "API Key 仅保存在本机加密存储")
            profiles.forEach { profile ->
                LedgerCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(profile.displayName, style = MaterialTheme.typography.titleMedium)
                            Text("${profile.model} · ${profile.providerKind}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                if (!viewModel.hasApiKey(profile.id)) "需要重新填写 API Key" else if (profile.isDefault) "默认模型" else "可用模型",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (viewModel.hasApiKey(profile.id)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                        }
                        IconButton(onClick = { deleteTarget = profile }) { Icon(Icons.Outlined.DeleteOutline, "删除 ${profile.displayName}") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                        OutlinedButton(onClick = { draft = ProviderDraft(profile.id, profile.displayName, AiProviderKind.valueOf(profile.providerKind), profile.baseUrl, profile.model) }) { Text("编辑") }
                        OutlinedButton(onClick = { viewModel.test(profile) }) { Text("测试") }
                        if (!profile.isDefault) Button(onClick = { viewModel.makeDefault(profile.id) }) { Text("设为默认") }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { draft = ProviderDraft(name = "DeepSeek", kind = AiProviderKind.DEEPSEEK, baseUrl = "https://api.deepseek.com", model = "deepseek-v4-pro") }) { Text("添加 DeepSeek") }
                OutlinedButton(onClick = { draft = ProviderDraft(name = "智谱 GLM", kind = AiProviderKind.ZHIPU_GLM, baseUrl = "https://open.bigmodel.cn/api/paas/v4", model = "glm-5.3") }) { Text("添加 GLM") }
            }
            OutlinedButton(onClick = { draft = ProviderDraft(name = "兼容模型", kind = AiProviderKind.OPENAI_COMPATIBLE, baseUrl = "https://", model = "") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.SmartToy, null)
                Text("添加 OpenAI 兼容接口", Modifier.padding(start = 8.dp))
            }
            TextButton(onClick = viewModel::clearHistory, modifier = Modifier.fillMaxWidth()) { Text("清空本机 AI 会话") }
            if (busy) CircularProgressIndicator()
        }
    }
    draft?.let { value -> ProviderDialog(value, { draft = null }) { name, kind, url, model, key -> viewModel.save(value.id, name, kind, url, model, key); draft = null } }
    deleteTarget?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除模型配置？") },
            text = { Text("将同时擦除 ${profile.displayName} 的本机 API Key，不影响历史回答。") },
            confirmButton = { TextButton(onClick = { viewModel.delete(profile.id); deleteTarget = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ProviderDialog(draft: ProviderDraft, onDismiss: () -> Unit, onSave: (String, AiProviderKind, String, String, String) -> Unit) {
    var name by remember(draft.id) { mutableStateOf(draft.name) }
    var kind by remember(draft.id) { mutableStateOf(draft.kind) }
    var url by remember(draft.id) { mutableStateOf(draft.baseUrl) }
    var model by remember(draft.id) { mutableStateOf(draft.model) }
    var key by remember(draft.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.id == null) "添加模型" else "编辑模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("配置名称") }, singleLine = true)
                OutlinedButton(onClick = { kind = AiProviderKind.entries[(kind.ordinal + 1) % AiProviderKind.entries.size] }, modifier = Modifier.fillMaxWidth()) { Text("类型：${kind.name}") }
                OutlinedTextField(url, { url = it }, label = { Text("Base URL（HTTPS）") }, singleLine = true)
                OutlinedTextField(model, { model = it }, label = { Text("模型名") }, singleLine = true)
                OutlinedTextField(key, { key = it }, label = { Text(if (draft.id == null) "API Key" else "新 API Key（留空则不变）") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && url.startsWith("https://") && model.isNotBlank() && (draft.id != null || key.isNotBlank()), onClick = { onSave(name, kind, url, model, key) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
