package com.billrecord.ledger.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.billrecord.ledger.data.local.CategoryTotal
import com.billrecord.ledger.data.local.PeriodSummary
import com.billrecord.ledger.ui.theme.ledgerColors
import kotlin.math.atan2
import kotlin.math.max

private val ChartPalette = listOf(
    Color(0xFF2F6FED), Color(0xFF0B8F84), Color(0xFFF06C67), Color(0xFFE49A2F),
    Color(0xFF8068D8), Color(0xFFD76596), Color(0xFF239DBB), Color(0xFF4F9854),
)

@Composable
fun CashFlowChart(
    values: List<PeriodSummary>,
    modifier: Modifier = Modifier,
    onSelect: ((PeriodSummary) -> Unit)? = null,
) {
    if (values.isEmpty()) {
        ChartEmpty("累积几个月数据后，这里会显示趋势。", modifier)
        return
    }
    val grid = MaterialTheme.colorScheme.outlineVariant
    val expenseColor = MaterialTheme.ledgerColors.expense
    val incomeColor = MaterialTheme.ledgerColors.income
    val maximum = max(1L, values.maxOf { max(it.incomeMinor, it.expenseMinor - it.refundMinor) })
    val semantics = values.joinToString("，") {
        "${it.period}收入${formatMoney(it.incomeMinor)}，支出${formatMoney(it.expenseMinor - it.refundMinor)}"
    }
    Column(
        modifier.semantics {
            contentDescription = "收支趋势。$semantics"
            if (onSelect != null) {
                onClick("查看最近一期流水") {
                    onSelect(values.last())
                    true
                }
            }
        },
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(176.dp)
                .then(
                    if (onSelect == null) Modifier else Modifier.pointerInput(values) {
                        detectTapGestures { offset ->
                            val index = ((offset.x / size.width) * values.size).toInt().coerceIn(values.indices)
                            onSelect(values[index])
                        }
                    },
                ),
        ) {
            val left = 8.dp.toPx()
            val right = size.width - 8.dp.toPx()
            val top = 10.dp.toPx()
            val bottom = size.height - 22.dp.toPx()
            repeat(4) { index ->
                val y = top + (bottom - top) * index / 3f
                drawLine(grid, Offset(left, y), Offset(right, y), 1.dp.toPx())
            }
            val step = if (values.size == 1) 0f else (right - left) / (values.size - 1)
            fun y(value: Long) = bottom - (bottom - top) * value.coerceAtLeast(0).toFloat() / maximum
            fun path(selector: (PeriodSummary) -> Long): Path = Path().apply {
                values.forEachIndexed { index, point ->
                    val px = if (values.size == 1) (left + right) / 2 else left + step * index
                    val py = y(selector(point))
                    if (index == 0) moveTo(px, py) else lineTo(px, py)
                }
            }
            drawPath(path { it.incomeMinor }, incomeColor, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            drawPath(path { it.expenseMinor - it.refundMinor }, expenseColor, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            values.forEachIndexed { index, point ->
                val px = if (values.size == 1) (left + right) / 2 else left + step * index
                drawCircle(incomeColor, 3.dp.toPx(), Offset(px, y(point.incomeMinor)))
                drawCircle(expenseColor, 3.dp.toPx(), Offset(px, y(point.expenseMinor - point.refundMinor)))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(values.first().period.takeLast(5), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChartLegendDot("收入", incomeColor)
                ChartLegendDot("支出", expenseColor)
            }
            Text(values.last().period.takeLast(5), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun CategoryDonutChart(
    values: List<CategoryTotal>,
    modifier: Modifier = Modifier,
    onSelect: ((CategoryTotal) -> Unit)? = null,
) {
    val positive = values.filter { it.amountMinor > 0 }
    if (positive.isEmpty()) {
        ChartEmpty("记下支出后，这里会显示资金去向。", modifier)
        return
    }
    val total = positive.sumOf { it.amountMinor }.coerceAtLeast(1L)
    val semantics = positive.take(6).joinToString("，") {
        "${it.categoryName ?: "未分类"}${it.amountMinor * 100 / total}%"
    }
    Row(
        modifier.fillMaxWidth().semantics {
            contentDescription = "支出分类占比。$semantics"
            if (onSelect != null) {
                onClick("查看第一项分类流水") {
                    onSelect(positive.first())
                    true
                }
            }
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(Modifier.size(142.dp), contentAlignment = Alignment.Center) {
            Canvas(
                Modifier
                    .size(142.dp)
                    .then(
                        if (onSelect == null) Modifier else Modifier.pointerInput(positive) {
                            detectTapGestures { offset ->
                                val center = Offset(size.width / 2f, size.height / 2f)
                                var angle = Math.toDegrees(atan2((offset.y - center.y).toDouble(), (offset.x - center.x).toDouble())).toFloat() + 90f
                                if (angle < 0) angle += 360f
                                var cursor = 0f
                                positive.forEach { value ->
                                    val sweep = value.amountMinor * 360f / total
                                    if (angle in cursor..(cursor + sweep)) {
                                        onSelect(value)
                                        return@detectTapGestures
                                    }
                                    cursor += sweep
                                }
                            }
                        },
                    ),
            ) {
                var start = -90f
                val stroke = Stroke(width = 20.dp.toPx(), cap = StrokeCap.Butt)
                positive.forEachIndexed { index, value ->
                    val sweep = value.amountMinor * 360f / total
                    drawArc(ChartPalette[index % ChartPalette.size], start, sweep, false, style = stroke)
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("支出", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatMoney(total), style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), fontFamily = FontFamily.SansSerif)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            positive.take(5).forEachIndexed { index, value ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(ChartPalette[index % ChartPalette.size], MaterialTheme.shapes.extraSmall))
                    Text(value.categoryName ?: "未分类", Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("${value.amountMinor * 100 / total}%", style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), fontFamily = FontFamily.SansSerif)
                }
            }
        }
    }
}

@Composable
fun MiniTrend(
    values: List<Long>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val maximum = max(1L, values.maxOrNull() ?: 1L)
    Canvas(modifier.height(42.dp).fillMaxWidth()) {
        if (values.isEmpty()) return@Canvas
        val path = Path()
        val step = if (values.size == 1) 0f else size.width / (values.size - 1)
        values.forEachIndexed { index, value ->
            val x = if (values.size == 1) size.width / 2 else step * index
            val y = size.height - size.height * value.coerceAtLeast(0).toFloat() / maximum
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun BudgetBar(
    usedMinor: Long,
    targetMinor: Long,
    modifier: Modifier = Modifier,
) {
    val ratio = usedMinor.toFloat() / targetMinor.coerceAtLeast(1L)
    val percent = if (targetMinor <= 0L) 0L else usedMinor * 100 / targetMinor
    val status = when {
        ratio >= 1f -> "超支"
        ratio >= .8f -> "预警"
        else -> "正常"
    }
    val color = when {
        ratio >= 1f -> MaterialTheme.colorScheme.error
        ratio >= .8f -> MaterialTheme.ledgerColors.warning
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .semantics { contentDescription = "预算使用率 $percent%，状态$status" }
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.extraLarge),
    ) {
        Box(
            Modifier
                .fillMaxWidth(ratio.coerceIn(0f, 1f))
                .height(8.dp)
                .background(color, MaterialTheme.shapes.extraLarge),
        )
    }
}

@Composable
private fun ChartLegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, MaterialTheme.shapes.extraSmall))
        Text(label, Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChartEmpty(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(132.dp), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}
