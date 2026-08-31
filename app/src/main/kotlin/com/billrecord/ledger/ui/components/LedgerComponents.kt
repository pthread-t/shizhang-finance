package com.billrecord.ledger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

enum class LedgerSurfaceStyle { Plain, Tonal, Raised }

private val CategoryVisualPalette = listOf(
    Color(0xFF2F6FED), Color(0xFF0B8F84), Color(0xFFF06C67), Color(0xFFE49A2F),
    Color(0xFF8068D8), Color(0xFFD76596), Color(0xFF239DBB), Color(0xFF4F9854),
)

fun categoryVisualColor(key: String): Color = CategoryVisualPalette[(key.hashCode() and Int.MAX_VALUE) % CategoryVisualPalette.size]

@Composable
fun MoneyText(
    amountMinor: Long,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    prefixSign: Boolean = false,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val formatted = formatMoney(amountMinor, prefixSign)
    Text(
        text = formatted,
        modifier = modifier.semantics { contentDescription = "金额 $formatted 元" },
        color = color,
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        style = style.copy(fontFeatureSettings = "tnum"),
    )
}

fun formatMoney(amountMinor: Long, prefixSign: Boolean = false): String {
    val formatter = NumberFormat.getCurrencyInstance(Locale.SIMPLIFIED_CHINESE).apply {
        currency = Currency.getInstance("CNY")
        maximumFractionDigits = 2
        minimumFractionDigits = 2
    }
    val value = amountMinor / 100.0
    return if (prefixSign && amountMinor > 0) "+${formatter.format(value)}" else formatter.format(value)
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 40.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.extraLarge) {
                Icon(
                    Icons.Outlined.AddCircleOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(16.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(20.dp))
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
fun LedgerCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    style: LedgerSurfaceStyle = LedgerSurfaceStyle.Tonal,
    content: @Composable ColumnScope.() -> Unit,
) {
    val color = when (style) {
        LedgerSurfaceStyle.Plain -> Color.Transparent
        LedgerSurfaceStyle.Tonal, LedgerSurfaceStyle.Raised -> MaterialTheme.colorScheme.surface
    }
    Surface(
        modifier = modifier.then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)),
        shape = MaterialTheme.shapes.large,
        color = color,
        shadowElevation = if (style == LedgerSurfaceStyle.Raised) 2.dp else 0.dp,
    ) {
        Column(Modifier.padding(if (style == LedgerSurfaceStyle.Plain) 0.dp else 18.dp), content = content)
    }
}

@Composable
fun SectionTitle(
    title: String,
    subtitle: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (action != null && onAction != null) {
            androidx.compose.material3.TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, color = color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.extraLarge) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            color = color,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
