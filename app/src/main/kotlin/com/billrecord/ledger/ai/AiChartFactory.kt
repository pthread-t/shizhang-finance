package com.billrecord.ledger.ai

import com.billrecord.ledger.ui.components.formatMoney
import java.util.UUID

object AiChartFactory {
    fun create(result: FinanceResult): ChartDescriptor {
        val rows = result.rows
        val kind = when {
            result.plan.groupBy == FinanceGroupBy.NONE || rows.size <= 1 -> AiChartKind.KPI
            result.plan.secondaryGroupBy != FinanceGroupBy.NONE -> AiChartKind.HEATMAP
            result.plan.groupBy == FinanceGroupBy.TIME -> AiChartKind.LINE
            result.plan.metric == FinanceMetric.EXPENSE && rows.size <= 6 && rows.all { it.netExpenseMinor >= 0 } -> AiChartKind.DONUT
            else -> AiChartKind.BAR
        }
        val ordinarySeries = when (result.plan.metric) {
            FinanceMetric.INCOME -> listOf(ChartSeries("收入", rows.map { it.incomeMinor }))
            FinanceMetric.EXPENSE -> listOf(ChartSeries("净支出", rows.map { it.netExpenseMinor }))
            FinanceMetric.NET -> listOf(ChartSeries("结余", rows.map { it.balanceMinor }))
            FinanceMetric.COUNT -> listOf(ChartSeries("笔数", rows.map { it.count.toLong() }))
            FinanceMetric.SUMMARY -> listOf(
                ChartSeries("收入", rows.map { it.incomeMinor }),
                ChartSeries("净支出", rows.map { it.netExpenseMinor }),
            )
        }
        val pointsByRow = rows.associateWith { row ->
            DrillPoint(
                pointId = UUID.randomUUID().toString(),
                label = listOfNotNull(row.label, row.secondaryLabel).joinToString(" · "),
                filter = row.filter,
                nextGranularity = when {
                    result.plan.groupBy != FinanceGroupBy.TIME && result.plan.secondaryGroupBy != FinanceGroupBy.TIME -> null
                    result.plan.granularity == TimeGranularity.YEAR -> TimeGranularity.MONTH
                    result.plan.granularity == TimeGranularity.MONTH -> TimeGranularity.DAY
                    else -> null
                },
            )
        }
        val points = rows.mapNotNull(pointsByRow::get)
        val labels: List<String>
        val series: List<ChartSeries>
        val heatmapCells: List<ChartHeatmapCell>
        if (kind == AiChartKind.HEATMAP) {
            labels = rows.map { it.label }.distinct()
            val secondaryLabels = rows.mapNotNull { it.secondaryLabel }.distinct()
            series = secondaryLabels.map { secondaryLabel ->
                ChartSeries(
                    secondaryLabel,
                    labels.map { label -> rows.firstOrNull { it.label == label && it.secondaryLabel == secondaryLabel }?.let { metricValue(it, result.plan.metric) } ?: 0L },
                )
            }
            heatmapCells = rows.mapNotNull { row ->
                val x = labels.indexOf(row.label)
                val y = secondaryLabels.indexOf(row.secondaryLabel)
                val point = pointsByRow[row]
                if (x < 0 || y < 0 || point == null) null else ChartHeatmapCell(x, y, metricValue(row, result.plan.metric), point.pointId)
            }
        } else {
            labels = rows.map { it.label }
            series = ordinarySeries
            heatmapCells = emptyList()
        }
        val summary = if (rows.isEmpty()) "当前条件没有账单" else rows.take(6).joinToString("，") { row ->
            "${listOfNotNull(row.label, row.secondaryLabel).joinToString("与")}${when (result.plan.metric) { FinanceMetric.COUNT -> "${row.count}笔"; else -> formatMoney(metricValue(row, result.plan.metric)) }}"
        }
        return ChartDescriptor(
            kind = kind,
            title = chartTitle(result.plan),
            labels = labels,
            series = series,
            points = points,
            accessibilitySummary = summary,
            heatmapCells = heatmapCells,
            valueIsCount = result.plan.metric == FinanceMetric.COUNT,
            currency = result.currency,
        )
    }

    private fun chartTitle(plan: FinanceQueryPlan) = when (plan.metric) {
        FinanceMetric.INCOME -> "收入分析"
        FinanceMetric.EXPENSE -> "支出分析"
        FinanceMetric.NET -> "收支结余"
        FinanceMetric.COUNT -> "账单笔数"
        FinanceMetric.SUMMARY -> "收支概览"
    }

    private fun metricValue(row: FinanceAggregateRow, metric: FinanceMetric): Long = when (metric) {
        FinanceMetric.INCOME -> row.incomeMinor
        FinanceMetric.EXPENSE -> row.netExpenseMinor
        FinanceMetric.NET -> row.balanceMinor
        FinanceMetric.COUNT -> row.count.toLong()
        FinanceMetric.SUMMARY -> row.netExpenseMinor
    }
}
