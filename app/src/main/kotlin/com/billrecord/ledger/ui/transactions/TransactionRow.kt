package com.billrecord.ledger.ui.transactions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.billrecord.ledger.data.local.TransactionEntity
import com.billrecord.ledger.ui.components.MoneyText
import com.billrecord.ledger.ui.components.categoryVisualColor
import com.billrecord.ledger.ui.theme.ledgerColors
import com.billrecord.shared.TransactionType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TransactionRow(
    transaction: TransactionEntity,
    categoryName: String?,
    accountName: String?,
    selected: Boolean? = null,
    onSelectedChange: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val semanticColors = MaterialTheme.ledgerColors
    val categoryColor = categoryVisualColor(categoryName ?: typeName(transaction.type))
    val positive = transaction.type in setOf(TransactionType.INCOME, TransactionType.REFUND)
    Row(
        Modifier.fillMaxWidth().then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null && onSelectedChange != null) {
            Checkbox(checked = selected, onCheckedChange = onSelectedChange)
        }
        Surface(
            modifier = Modifier.size(40.dp),
            color = categoryColor.copy(alpha = 0.14f),
            shape = MaterialTheme.shapes.small,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text((categoryName ?: typeName(transaction.type)).take(1), fontWeight = FontWeight.Bold, color = categoryColor)
            }
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(categoryName ?: typeName(transaction.type), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                listOfNotNull(accountName, formatTime(transaction.occurredAt), transaction.note.takeIf(String::isNotBlank)).joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
        }
        MoneyText(
            amountMinor = if (positive) transaction.baseAmountMinor else -transaction.baseAmountMinor,
            color = if (positive) semanticColors.income else semanticColors.expense,
            prefixSign = positive,
        )
    }
}

private fun typeName(type: TransactionType) = when (type) {
    TransactionType.EXPENSE -> "支出"; TransactionType.INCOME -> "收入"; TransactionType.TRANSFER -> "转账"
    TransactionType.REFUND -> "退款"; TransactionType.ADJUSTMENT -> "余额调整"
}

private fun formatTime(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
