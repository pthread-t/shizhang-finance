package com.billrecord.ledger.ui.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.billrecord.ledger.ui.theme.LedgerTheme

class AutomationDisclosureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LedgerTheme {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text("支付页面识别", style = MaterialTheme.typography.headlineMedium)
                    Text("开启后，拾账只读取微信和支付宝支付完成页中的金额与商家名称。识别内容在本机处理，不上传原始页面，不执行点击、滚动或付款操作。", Modifier.padding(vertical = 18.dp))
                    Button(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("打开系统无障碍设置") }
                }
            }
        }
    }
}

