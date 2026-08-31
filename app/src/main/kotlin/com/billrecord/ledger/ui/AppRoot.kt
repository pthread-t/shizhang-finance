package com.billrecord.ledger.ui

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.billrecord.ledger.ui.add.AddTransactionScreen
import com.billrecord.ledger.ui.assets.AssetsScreen
import com.billrecord.ledger.ui.home.HomeScreen
import com.billrecord.ledger.ui.reports.ReportsScreen
import com.billrecord.ledger.ui.settings.SettingsScreen
import com.billrecord.ledger.ui.transactions.TransactionListScreen
import com.billrecord.ledger.ui.transactions.TransactionDetailScreen
import com.billrecord.ledger.ui.assistant.AiDrillScreen
import com.billrecord.ledger.ui.assistant.AiSettingsScreen
import com.billrecord.ledger.ui.tags.TagManagementScreen
import com.billrecord.ledger.ui.settings.AccountSecurityScreen
import com.billrecord.ledger.sync.SyncStatus
import com.billrecord.shared.TransactionFilter

private data class Destination(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val destinations = listOf(
    Destination("home", "首页", Icons.Outlined.Home),
    Destination("transactions", "明细", Icons.AutoMirrored.Outlined.ReceiptLong),
    Destination("reports", "分析", Icons.Outlined.BarChart),
    Destination("assets", "资产", Icons.Outlined.AccountBalanceWallet),
)

@Composable
fun AppRoot(
    ready: Boolean,
    canEdit: Boolean,
    sharedIntent: Intent?,
    syncStatus: SyncStatus,
    onRetrySync: () -> Unit,
) {
    if (!ready) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.semantics { contentDescription = "正在准备本地账本" })
        }
        return
    }

    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val mainDestination = destinations.any { it.route == route }
    var drilldownFilter by remember { mutableStateOf<TransactionFilter?>(null) }

    LaunchedEffect(sharedIntent) {
        if (canEdit && sharedIntent?.action == Intent.ACTION_SEND) navController.navigate("add")
    }

    Scaffold(
        bottomBar = {
            if (mainDestination) {
                MainNavigationBar(
                    route = route,
                    canEdit = canEdit,
                    onAdd = { navController.navigate("add") },
                    onNavigate = { destination ->
                        if (destination == "transactions") drilldownFilter = null
                        navController.navigate(destination) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        NavHost(navController, startDestination = "home", modifier = Modifier.fillMaxSize()) {
            composable("home") {
                HomeScreen(
                    padding,
                    canEdit = canEdit,
                    onAdd = { navController.navigate("add") },
                    onSettings = { navController.navigate("settings") },
                    onOpenTransaction = { navController.navigate("transaction/$it") },
                    syncStatus = syncStatus,
                    onRetrySync = onRetrySync,
                )
            }
            composable("transactions") {
                TransactionListScreen(
                    padding,
                    canEdit = canEdit,
                    onOpenTransaction = { navController.navigate("transaction/$it") },
                    initialFilter = drilldownFilter,
                    onInitialFilterConsumed = { drilldownFilter = null },
                    syncStatus = syncStatus,
                    onRetrySync = onRetrySync,
                )
            }
            composable("reports") {
                ReportsScreen(
                    padding,
                    canEdit = canEdit,
                    onOpenTransactions = { filter ->
                        drilldownFilter = filter
                        navController.navigate("transactions") { launchSingleTop = true }
                    },
                    onOpenAiDrill = { messageId, pointId -> navController.navigate("ai-drill/$messageId/$pointId") },
                    onOpenAiSettings = { navController.navigate("ai-settings") },
                    syncStatus = syncStatus,
                    onRetrySync = onRetrySync,
                )
            }
            composable("assets") { AssetsScreen(padding, canEdit = canEdit, syncStatus = syncStatus, onRetrySync = onRetrySync) }
            composable("add") {
                AddTransactionScreen(
                    sharedIntent = sharedIntent,
                    onClose = { navController.popBackStack() },
                    onManageTags = { navController.navigate("tags") },
                )
            }
            composable("settings") {
                SettingsScreen(
                    onClose = { navController.popBackStack() },
                    onAiSettings = { navController.navigate("ai-settings") },
                    onAccountSecurity = { navController.navigate("account-security") },
                    onManageTags = { navController.navigate("tags") },
                )
            }
            composable("tags") { TagManagementScreen(canEdit = canEdit, onClose = { navController.popBackStack() }) }
            composable("account-security") { AccountSecurityScreen(onClose = { navController.popBackStack() }) }
            composable("transaction/{transactionId}") { TransactionDetailScreen(canEdit = canEdit, onClose = { navController.popBackStack() }) }
            composable("ai-settings") { AiSettingsScreen(onClose = { navController.popBackStack() }) }
            composable("ai-drill/{messageId}/{pointId}") { AiDrillScreen(onClose = { navController.popBackStack() }) }
        }
    }
}

@Composable
internal fun MainNavigationBar(
    route: String?,
    canEdit: Boolean,
    onNavigate: (String) -> Unit,
    onAdd: () -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        destinations.forEachIndexed { index, destination ->
            if (index == 2) {
                Column(Modifier.weight(1f).height(80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        onClick = onAdd,
                        modifier = Modifier
                            .padding(top = 5.dp)
                            .size(48.dp)
                            .clip(MaterialTheme.shapes.extraLarge)
                            .semantics { contentDescription = if (canEdit) "全局快速记一笔" else "当前账本只读" },
                        enabled = canEdit,
                        shape = MaterialTheme.shapes.extraLarge,
                        color = if (canEdit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (canEdit) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        shadowElevation = if (canEdit) 2.dp else 0.dp,
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Default.Add, contentDescription = null) }
                    }
                    Text("记账", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            NavigationBarItem(
                selected = route == destination.route,
                onClick = { onNavigate(destination.route) },
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                label = { Text(destination.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    }
}
