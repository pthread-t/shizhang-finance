package com.billrecord.ledger.ai

import com.billrecord.shared.TransactionFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AiChartFactoryTest {
    @Test
    fun `time series uses line chart and creates stable drill filters`() {
        val plan = FinanceQueryPlan(bookId = "book", startAt = 1, endAt = 10, metric = FinanceMetric.EXPENSE, groupBy = FinanceGroupBy.TIME, granularity = TimeGranularity.MONTH)
        val rows = listOf(
            FinanceAggregateRow("2026-01", "2026-01", expenseMinor = 10_000, count = 2, filter = TransactionFilter(bookIds = setOf("book"), startEpochMillis = 1, endEpochMillis = 5)),
            FinanceAggregateRow("2026-02", "2026-02", expenseMinor = 20_000, refundMinor = 2_000, count = 3, filter = TransactionFilter(bookIds = setOf("book"), startEpochMillis = 5, endEpochMillis = 10)),
        )
        val chart = AiChartFactory.create(FinanceResult(plan, rows, 0, 30_000, 2_000, 5, "CNY", 10))

        assertEquals(AiChartKind.LINE, chart.kind)
        assertEquals(listOf(10_000L, 18_000L), chart.series.single().values)
        assertEquals(TimeGranularity.DAY, chart.points.first().nextGranularity)
        assertNotEquals(chart.points[0].pointId, chart.points[1].pointId)
    }

    @Test
    fun `small non-negative expense composition uses donut`() {
        val plan = FinanceQueryPlan(bookId = "book", startAt = 1, endAt = 10, metric = FinanceMetric.EXPENSE, groupBy = FinanceGroupBy.CATEGORY)
        val rows = listOf(
            FinanceAggregateRow("food", "餐饮", expenseMinor = 10_000, filter = TransactionFilter(categoryIds = setOf("food"))),
            FinanceAggregateRow("travel", "交通", expenseMinor = 5_000, filter = TransactionFilter(categoryIds = setOf("travel"))),
        )
        val chart = AiChartFactory.create(FinanceResult(plan, rows, 0, 15_000, 0, 2, "CNY", 10))
        assertEquals(AiChartKind.DONUT, chart.kind)
        assertEquals(setOf("food"), chart.points.first().filter.categoryIds)
    }

    @Test
    fun `two dimensions use heatmap and each cell keeps both filters`() {
        val plan = FinanceQueryPlan(
            bookId = "book",
            startAt = 1,
            endAt = 10,
            metric = FinanceMetric.EXPENSE,
            groupBy = FinanceGroupBy.TIME,
            secondaryGroupBy = FinanceGroupBy.ACCOUNT,
            granularity = TimeGranularity.MONTH,
        )
        val rows = listOf(
            FinanceAggregateRow(
                key = "2026-01",
                label = "2026-01",
                secondaryKey = "wechat",
                secondaryLabel = "微信",
                expenseMinor = 10_000,
                filter = TransactionFilter(bookIds = setOf("book"), startEpochMillis = 1, endEpochMillis = 5, accountIds = setOf("wechat")),
            ),
            FinanceAggregateRow(
                key = "2026-02",
                label = "2026-02",
                secondaryKey = "cash",
                secondaryLabel = "现金",
                expenseMinor = 8_000,
                filter = TransactionFilter(bookIds = setOf("book"), startEpochMillis = 5, endEpochMillis = 10, accountIds = setOf("cash")),
            ),
        )

        val chart = AiChartFactory.create(FinanceResult(plan, rows, 0, 18_000, 0, 2, "CNY", 10))

        assertEquals(AiChartKind.HEATMAP, chart.kind)
        assertEquals(2, chart.heatmapCells.size)
        val firstPoint = chart.points.first { it.pointId == chart.heatmapCells.first().pointId }
        assertEquals(setOf("wechat"), firstPoint.filter.accountIds)
        assertEquals(1L, firstPoint.filter.startEpochMillis)
    }

    @Test
    fun `base url validator rejects insecure and parameterized endpoints`() {
        assertEquals("https://api.example.com/v1", AiDataRepository.validateBaseUrl("https://api.example.com/v1/"))
        listOf("http://api.example.com", "https://api.example.com?a=1", "not a url").forEach { value ->
            runCatching { AiDataRepository.validateBaseUrl(value) }.onSuccess { error("Expected rejection for $value") }
        }
    }
}
