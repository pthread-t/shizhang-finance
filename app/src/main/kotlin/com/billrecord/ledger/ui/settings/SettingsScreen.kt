package com.billrecord.ledger.ui.settings

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.billrecord.ledger.data.export.ExportFormat
import com.billrecord.ledger.BuildConfig
import com.billrecord.ledger.data.local.SyncConflictEntity
import com.billrecord.ledger.sync.SyncStatus
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private enum class PasswordAction { BACKUP, PREVIEW_MERGE, PREVIEW_REPLACE, APPLY_MERGE, APPLY_REPLACE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    onAiSettings: () -> Unit,
    onAccountSecurity: () -> Unit,
    onManageTags: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val automation by viewModel.automationEnabled.collectAsStateWithLifecycle()
    val secureScreen by viewModel.secureScreen.collectAsStateWithLifecycle()
    val appLock by viewModel.appLock.collectAsStateWithLifecycle()
    val dailyReminder by viewModel.dailyReminder.collectAsStateWithLifecycle()
    val themeAccent by viewModel.themeAccent.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val conflicts by viewModel.conflicts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var urlInput by remember(serverUrl) { mutableStateOf(serverUrl) }
    var showLogin by remember { mutableStateOf(false) }
    var showInvite by remember { mutableStateOf(false) }
    var passwordAction by remember { mutableStateOf<PasswordAction?>(null) }
    var pendingRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var requestedReplaceRestore by remember { mutableStateOf(false) }
    var conflictToResolve by remember { mutableStateOf<SyncConflictEntity?>(null) }

    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let { viewModel.export(it, ExportFormat.CSV) } }
    val xlsxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { it?.let { viewModel.export(it, ExportFormat.XLSX) } }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(viewModel::previewImport) }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) { pendingRestoreUri = uri; passwordAction = PasswordAction.BACKUP }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { pendingRestoreUri = uri; passwordAction = if (requestedReplaceRestore) PasswordAction.PREVIEW_REPLACE else PasswordAction.PREVIEW_MERGE }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setDailyReminder(granted)
        if (!granted) Toast.makeText(context, "未开启通知权限；每日提醒保持关闭", Toast.LENGTH_LONG).show()
    }

    LaunchedEffect(state.message, state.error) {
        (state.message ?: state.error)?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }
    LaunchedEffect(state.signedIn) { if (state.signedIn) viewModel.loadCloudManagement() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("设置") }, navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Outlined.ArrowBack, contentDescription = "返回") } }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SettingsSection("云同步", Icons.Outlined.CloudSync) {
                if (BuildConfig.ALLOW_SERVER_URL_EDIT) {
                    OutlinedTextField(value = urlInput, onValueChange = { urlInput = it }, label = { Text("服务器 HTTPS 地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                } else {
                    Text("预发布云端：$serverUrl", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (BuildConfig.ALLOW_SERVER_URL_EDIT) {
                        OutlinedButton(onClick = { viewModel.saveServerUrl(urlInput) }) { Text("保存地址") }
                    }
                    if (state.signedIn) Button(onClick = viewModel::syncNow) { Text(if (syncStatus == SyncStatus.SYNCING) "同步中" else "立即同步") }
                    else Button(onClick = { showLogin = true }) { Text("登录") }
                }
                Text(syncLabel(syncStatus), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (conflicts.isNotEmpty()) {
                    Button(onClick = { conflictToResolve = conflicts.first() }) { Text("处理同步冲突（${conflicts.size}）") }
                    Text("冲突不会静默覆盖；可以逐字段选择本机或服务器版本。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            SettingsSection("家庭账本", Icons.Outlined.Group) {
                if (state.signedIn) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.currentRole == com.billrecord.shared.BookRole.OWNER) {
                            OutlinedButton(onClick = viewModel::createInvite) { Text("生成编辑者邀请码") }
                        }
                        OutlinedButton(onClick = { showInvite = true }) { Text("加入账本") }
                    }
                    state.inviteCode?.let { Text("邀请码  $it", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                    state.members.forEach { member ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(member.username, fontWeight = FontWeight.Medium); Text(member.role.roleLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            if (state.currentRole == com.billrecord.shared.BookRole.OWNER && member.role != com.billrecord.shared.BookRole.OWNER) {
                                TextButton(onClick = { viewModel.toggleMemberRole(member) }) { Text("切换角色") }
                                TextButton(onClick = { viewModel.removeMember(member) }) { Text("移除") }
                            }
                        }
                    }
                } else Text("登录云同步后可邀请家人共同记账。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SettingsSection("账户与账本", Icons.Outlined.ManageAccounts) {
                if (state.signedIn) OutlinedButton(onClick = onAccountSecurity, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.ManageAccounts, contentDescription = null)
                    Text("账户与安全", Modifier.padding(start = 8.dp))
                }
                OutlinedButton(onClick = onManageTags, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Label, contentDescription = null)
                    Text("标签管理", Modifier.padding(start = 8.dp))
                }
            }
            SettingsSection("数据迁移", Icons.Outlined.Download) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { csvLauncher.launch("拾账-账单.csv") }) { Text("导出 CSV") }
                    OutlinedButton(onClick = { xlsxLauncher.launch("拾账-账单.xlsx") }) { Text("导出 XLSX") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("text/*", "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) }) { Text("导入账单") }
                    OutlinedButton(onClick = { backupLauncher.launch("拾账-${System.currentTimeMillis()}.billbackup") }) { Text("完整备份") }
                    OutlinedButton(onClick = { requestedReplaceRestore = false; restoreLauncher.launch(arrayOf("application/octet-stream", "*/*")) }) { Text("合并恢复") }
                }
                OutlinedButton(onClick = { requestedReplaceRestore = true; restoreLauncher.launch(arrayOf("application/octet-stream", "*/*")) }) { Text("完整替换恢复") }
                Text("完整替换会先校验备份，再替换本机逻辑数据；附件随备份恢复。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SettingsSection("隐私与自动记账", Icons.Outlined.Lock) {
                OutlinedButton(onClick = onAiSettings, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.SmartToy, contentDescription = null)
                    Text("AI 模型与本机会话", Modifier.padding(start = 8.dp))
                }
                OutlinedButton(onClick = viewModel::cycleThemeAccent) { Text("主题色：${themeAccent.accentLabel()}") }
                SettingToggle("每日记账提醒", "每天 20:00 由本地任务提醒", dailyReminder) { enabled ->
                    if (enabled && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else viewModel.setDailyReminder(enabled)
                }
                SettingToggle("应用锁", "进入应用时使用生物识别或设备数字密码", appLock) { enabled ->
                    val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    if (!enabled || BiometricManager.from(context).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS) {
                        viewModel.setAppLock(enabled)
                    } else Toast.makeText(context, "请先在系统中设置屏幕锁或生物识别", Toast.LENGTH_LONG).show()
                }
                SettingToggle("防止系统截屏", "重启应用后生效", secureScreen, viewModel::setSecureScreen)
                SettingToggle("支付页面识别", "默认关闭；仅监听微信和支付宝，数据留在本机", automation) {
                    viewModel.setAutomationEnabled(it)
                    if (it) context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            Spacer(Modifier.height(28.dp))
        }
        if (state.busy) androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }

    if (showLogin) LoginDialog(onDismiss = { showLogin = false }) { username, password, bootstrap, inviteCode, recoveryCode ->
        viewModel.login(username, password, bootstrap, inviteCode, recoveryCode); showLogin = false
    }
    if (showInvite) TextInputDialog("加入家庭账本", "邀请码", onDismiss = { showInvite = false }) { viewModel.acceptInvite(it); showInvite = false }
    passwordAction?.let { action ->
        TextInputDialog("备份密码", "至少 8 位", password = true, onDismiss = { passwordAction = null; pendingRestoreUri = null }) { password ->
            pendingRestoreUri?.let { uri -> when (action) {
                PasswordAction.BACKUP -> viewModel.createBackup(uri, password)
                PasswordAction.PREVIEW_MERGE -> viewModel.previewBackup(uri, password, false)
                PasswordAction.PREVIEW_REPLACE -> viewModel.previewBackup(uri, password, true)
                PasswordAction.APPLY_MERGE -> viewModel.restoreBackup(uri, password, false)
                PasswordAction.APPLY_REPLACE -> viewModel.restoreBackup(uri, password, true)
            } }
            passwordAction = null; pendingRestoreUri = null
        }
    }
    state.importPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = viewModel::dismissImport,
            title = { Text("导入预览") },
            text = { Text("来源：${preview.source}\n可导入 ${preview.validCount} 笔，重复 ${preview.duplicateCount} 笔，错误 ${preview.errorCount} 笔。重复和错误行不会写入。") },
            confirmButton = { TextButton(onClick = viewModel::confirmImport, enabled = preview.validCount > 0) { Text("导入") } },
            dismissButton = { TextButton(onClick = viewModel::dismissImport) { Text("取消") } },
        )
    }
    state.recoveryCode?.let { code ->
        AlertDialog(
            onDismissRequest = {}, title = { Text("保存恢复码") },
            text = { Text("这是唯一一次显示恢复码。请离线保存：\n\n$code", fontFamily = FontFamily.Monospace) },
            confirmButton = { TextButton(onClick = viewModel::acknowledgeRecoveryCode) { Text("我已保存") } },
        )
    }
    state.restorePreview?.let { pending ->
        val manifest = pending.preview.manifest
        AlertDialog(
            onDismissRequest = viewModel::dismissRestorePreview,
            title = { Text(if (pending.replace) "确认完整替换" else "确认合并恢复") },
            text = { Text("备份时间：${manifest.createdAt}\n账单及配置表：${manifest.tableCounts.size} 张\n附件：${manifest.attachmentCount} 个\n与本机计数不同：${pending.preview.changedTables} 张表\n\n${if (pending.replace) "完整替换会在单一数据库事务中替换逻辑数据。" else "同主键记录会更新，本机其他记录保留。"}") },
            confirmButton = { TextButton(onClick = {
                pendingRestoreUri = pending.uri
                passwordAction = if (pending.replace) PasswordAction.APPLY_REPLACE else PasswordAction.APPLY_MERGE
                viewModel.dismissRestorePreview()
            }) { Text("继续并再次输入密码") } },
            dismissButton = { TextButton(onClick = viewModel::dismissRestorePreview) { Text("取消") } },
        )
    }
    conflictToResolve?.let { conflict ->
        ConflictDialog(
            conflict = conflict,
            onDismiss = { conflictToResolve = null },
            onResolve = { localFields -> viewModel.resolveConflict(conflict, localFields); conflictToResolve = null },
        )
    }
}

@Composable
private fun SettingsSection(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary); Text(title, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium) }
        content()
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun SettingToggle(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium); Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LoginDialog(onDismiss: () -> Unit, onSubmit: (String, String, Boolean, String?, String?) -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var bootstrap by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var recover by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("连接私有云") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(username, { username = it }, label = { Text("用户名") }, singleLine = true)
            OutlinedTextField(password, { password = it }, label = { Text(if (recover) "新密码" else "密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            if (recover) {
                OutlinedTextField(code, { code = it }, label = { Text("一次性恢复码") }, singleLine = true)
                Text("恢复会撤销该账号全部旧会话，并显示一个新的恢复码。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                SettingToggle("初始化服务器", "仅第一次创建首个账号时开启", bootstrap) { bootstrap = it; if (it) code = "" }
                if (!bootstrap) OutlinedTextField(code, { code = it }, label = { Text("邀请码（新成员注册时填写）") }, singleLine = true)
            }
            TextButton(onClick = { recover = !recover; bootstrap = false; code = "" }) { Text(if (recover) "返回登录" else "使用恢复码重设密码") }
        } },
        confirmButton = { TextButton(
            onClick = { onSubmit(username, password, bootstrap, code.takeIf { !recover && it.isNotBlank() }, code.takeIf { recover && it.isNotBlank() }) },
            enabled = username.length >= 3 && password.length >= 10 && (!recover || code.isNotBlank()),
        ) { Text(if (recover) "重设并登录" else if (bootstrap) "创建并登录" else if (code.isNotBlank()) "注册并加入" else "登录") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun TextInputDialog(title: String, label: String, password: Boolean = false, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, label = { Text(label) }, visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSubmit(value) }, enabled = value.isNotBlank() && (!password || value.length >= 8)) { Text("确认") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun syncLabel(status: SyncStatus) = when (status) {
    SyncStatus.LOCAL_ONLY -> "当前仅保存在本机"
    SyncStatus.IDLE -> "本机数据与服务器同步"
    SyncStatus.SYNCING -> "正在同步增量修改"
    SyncStatus.ERROR -> "上次同步失败，将在网络可用时重试"
}

private fun com.billrecord.shared.BookRole.roleLabel() = when (this) {
    com.billrecord.shared.BookRole.OWNER -> "所有者"
    com.billrecord.shared.BookRole.EDITOR -> "编辑者"
    com.billrecord.shared.BookRole.VIEWER -> "查看者"
}

@Composable
private fun ConflictDialog(conflict: SyncConflictEntity, onDismiss: () -> Unit, onResolve: (Set<String>) -> Unit) {
    val fields = remember(conflict.operationId) {
        runCatching { Json.decodeFromString<Set<String>>(conflict.conflictingFieldsJson) }.getOrDefault(setOf("*"))
    }
    val local = remember(conflict.operationId) { runCatching { Json.parseToJsonElement(conflict.localPayloadJson).jsonObject }.getOrNull() }
    val server = remember(conflict.operationId) { runCatching { Json.parseToJsonElement(conflict.serverPayloadJson).jsonObject }.getOrNull() }
    var localFields by remember(conflict.operationId) { mutableStateOf(emptySet<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择冲突版本") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${conflict.entityType.name} · ${conflict.entityId.take(8)}", style = MaterialTheme.typography.labelLarge)
                fields.forEach { field ->
                    val label = if (field == "*") "整条记录（删除与编辑冲突）" else field
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(field in localFields, onCheckedChange = { checked -> localFields = if (checked) localFields + field else localFields - field })
                        Column(Modifier.weight(1f)) {
                            Text(label, fontWeight = FontWeight.Medium)
                            if (field != "*") {
                                Text("本机 ${local?.get(field).shortValue()}", style = MaterialTheme.typography.bodySmall)
                                Text("云端 ${server?.get(field).shortValue()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text("勾选保留本机；不勾选采用云端", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onResolve(localFields) }) { Text("应用选择") } },
        dismissButton = { Row { TextButton(onClick = { onResolve(emptySet()) }) { Text("全部用云端") }; TextButton(onClick = onDismiss) { Text("稍后") } } },
    )
}

private fun kotlinx.serialization.json.JsonElement?.shortValue(): String = this?.toString()?.take(64) ?: "（空）"

private fun String.accentLabel() = when (this) { "RUST" -> "珊瑚红"; "BRASS" -> "暖杏"; "SLATE" -> "晴蓝"; else -> "湖水青" }
