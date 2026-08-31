package com.billrecord.ledger.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.billrecord.ledger.sync.SyncStatus

@Composable
fun SyncStatusIndicator(status: SyncStatus, onRetry: () -> Unit) {
    when (status) {
        SyncStatus.SYNCING -> {
            val transition = rememberInfiniteTransition(label = "cloud-sync")
            val rotation by transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
                label = "cloud-sync-rotation",
            )
            Icon(
                Icons.Outlined.Sync,
                contentDescription = "正在后台同步",
                modifier = Modifier.graphicsLayer { rotationZ = rotation },
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        SyncStatus.ERROR -> IconButton(onClick = onRetry) {
            Icon(
                Icons.Outlined.SyncProblem,
                contentDescription = "同步失败，点击重试",
                tint = MaterialTheme.colorScheme.error,
            )
        }
        SyncStatus.IDLE, SyncStatus.LOCAL_ONLY -> Unit
    }
}
