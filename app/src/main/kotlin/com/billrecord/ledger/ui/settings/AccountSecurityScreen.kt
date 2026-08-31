package com.billrecord.ledger.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSecurityScreen(
    onClose: () -> Unit,
    viewModel: AccountSecurityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var currentPassword by remember { mutableStateOf("") }
    var newUsername by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    LaunchedEffect(state.message, state.error) {
        state.message?.let {
            currentPassword = ""
            newUsername = ""
            newPassword = ""
            confirmation = ""
        }
        (state.message ?: state.error)?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }
    LaunchedEffect(state.signedOut) { if (state.signedOut) onClose() }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("账户与安全") }, navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } }) },
    ) { padding ->
        if (state.loading) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { CircularProgressIndicator() }
        } else {
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("当前用户名", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.profile?.username ?: "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                OutlinedTextField(newUsername, { newUsername = it.take(32) }, label = { Text("新用户名（不修改可留空）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(currentPassword, { currentPassword = it }, label = { Text("当前密码") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), singleLine = true)
                OutlinedTextField(newPassword, { newPassword = it }, label = { Text("新密码（至少 10 位，可留空）") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), singleLine = true)
                OutlinedTextField(confirmation, { confirmation = it }, label = { Text("再次输入新密码") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), singleLine = true)
                Button(
                    onClick = { viewModel.updateCredentials(currentPassword, newUsername, newPassword, confirmation) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存账户信息") }
                Text("保存后当前手机会自动续签，其他设备需要重新登录；恢复码不会改变。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("已登录设备", style = MaterialTheme.typography.titleMedium)
                state.devices.forEach { device ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name, fontWeight = FontWeight.Medium)
                            Text(if (device.current) "当前设备" else "最近在线 ${java.text.DateFormat.getDateTimeInstance().format(device.lastSeenAtEpochMillis)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!device.current) TextButton(onClick = { viewModel.revokeDevice(device.id) }) { Text("撤销") }
                    }
                }
                OutlinedButton(onClick = viewModel::requestSignOut, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("退出当前账号", color = MaterialTheme.colorScheme.error) }
            }
        }
        if (state.busy) Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { CircularProgressIndicator() }
    }
    if (state.confirmUnsafeLogout) {
        AlertDialog(
            onDismissRequest = viewModel::cancelUnsafeLogout,
            title = { Text("尚未完成同步") },
            text = { Text("网络异常导致退出前同步失败。未同步操作仍保存在本机，使用同一账号重新登录后会继续同步。仍要退出吗？") },
            confirmButton = { TextButton(onClick = viewModel::forceSignOut) { Text("仍然退出", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = viewModel::cancelUnsafeLogout) { Text("取消") } },
        )
    }
}
