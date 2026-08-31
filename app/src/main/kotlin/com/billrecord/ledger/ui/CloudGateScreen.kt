package com.billrecord.ledger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun CloudGateScreen(
    state: AppLaunchState,
    onLogin: (String, String) -> Unit,
    onRetry: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("拾账预发布体验", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("数据将从配置的私有云安全同步，不会在安装包内生成样例账本。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        if (state.loginRequired) {
            OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("体验账号") }, singleLine = true, enabled = !state.busy)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("密码") }, singleLine = true, enabled = !state.busy, visualTransformation = PasswordVisualTransformation())
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onLogin(username, password) },
                enabled = !state.busy && username.length >= 3 && password.length >= 10,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("登录并同步云端账本") }
        } else {
            Button(onClick = onRetry, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("重试云端同步") }
        }
        if (state.busy) {
            Spacer(Modifier.height(18.dp))
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text("正在验证账号并下载体验数据…")
        }
        state.error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
