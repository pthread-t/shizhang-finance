package com.billrecord.ledger.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billrecord.ledger.data.local.PeriodSummary
import com.billrecord.ledger.ui.add.AdvancedInformationToggle
import com.billrecord.ledger.ui.components.BudgetBar
import com.billrecord.ledger.ui.components.CashFlowChart
import com.billrecord.ledger.ui.components.EmptyState
import com.billrecord.ledger.ui.reports.PeriodBar
import com.billrecord.ledger.ui.reports.ReportPeriod
import com.billrecord.ledger.ui.theme.LedgerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiPolishSmokeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun mainNavigation_exposesAllDestinationsAndGlobalAdd() {
        var added = false
        var destination: String? = null
        composeRule.setContent {
            LedgerTheme(darkTheme = false) {
                MainNavigationBar(
                    route = "home",
                    canEdit = true,
                    onNavigate = { destination = it },
                    onAdd = { added = true },
                )
            }
        }

        listOf("首页", "明细", "记账", "分析", "资产").forEach { composeRule.onNodeWithText(it).assertExists() }
        composeRule.onNodeWithContentDescription("全局快速记一笔").performClick()
        composeRule.runOnIdle { assertTrue(added) }
        composeRule.onNodeWithText("分析").performClick()
        composeRule.runOnIdle { assertEquals("reports", destination) }
    }

    @Test
    fun readOnlyNavigation_disablesGlobalAdd() {
        composeRule.setContent {
            LedgerTheme(darkTheme = false) {
                MainNavigationBar(route = "home", canEdit = false, onNavigate = {}, onAdd = {})
            }
        }

        composeRule.onNodeWithContentDescription("当前账本只读").assertIsNotEnabled()
    }

    @Test
    fun emptyState_keepsSinglePrimaryAction() {
        var clicked = false
        composeRule.setContent {
            LedgerTheme(darkTheme = false) {
                EmptyState("账本还是空的", "先记下今天的第一笔。", "开始记账", onAction = { clicked = true })
            }
        }

        composeRule.onNodeWithText("账本还是空的").assertExists()
        composeRule.onNodeWithText("开始记账").performClick()
        composeRule.runOnIdle { assertTrue(clicked) }
    }

    @Test
    fun addTransaction_expandsAdvancedInformation() {
        composeRule.setContent {
            LedgerTheme(darkTheme = false) {
                var expanded by remember { mutableStateOf(false) }
                Column {
                    AdvancedInformationToggle(expanded) { expanded = !expanded }
                    if (expanded) androidx.compose.material3.Text("标签 · 商家 · 项目 · 附件")
                }
            }
        }

        composeRule.onNodeWithText("更多信息").performClick()
        composeRule.onNodeWithText("收起更多信息").assertIsDisplayed()
        composeRule.onNodeWithText("标签 · 商家 · 项目 · 附件").assertIsDisplayed()
    }

    @Test
    fun reports_periodAndDimensionFilter_areInteractive() {
        var selected: ReportPeriod? = null
        var filterOpened = false
        composeRule.setContent {
            LedgerTheme(darkTheme = false) {
                PeriodBar(
                    selected = ReportPeriod.MONTH,
                    activeDimensions = 2,
                    onPeriod = { selected = it },
                    onFilters = { filterOpened = true },
                )
            }
        }

        composeRule.onNodeWithText("近三月").performClick()
        composeRule.onNodeWithText("2 项维度").performClick()
        composeRule.runOnIdle {
            assertEquals(ReportPeriod.THREE_MONTHS, selected)
            assertTrue(filterOpened)
        }
    }

    @Test
    fun cashFlowChart_exposesAccessibleDrillDown() {
        val points = listOf(
            PeriodSummary("2026-07", 120_000, 80_000, 0),
            PeriodSummary("2026-08", 150_000, 90_000, 10_000),
        )
        var selected: PeriodSummary? = null
        composeRule.setContent {
            LedgerTheme(darkTheme = false) {
                CashFlowChart(points, onSelect = { selected = it })
            }
        }

        composeRule.onNodeWithContentDescription("收支趋势", substring = true).performClick()
        composeRule.runOnIdle { assertEquals(points.last(), selected) }
    }

    @Test
    fun budgetProgress_announcesNormalWarningAndOverspendStates() {
        composeRule.setContent {
            LedgerTheme(darkTheme = false) {
                Column {
                    BudgetBar(500, 1_000)
                    BudgetBar(800, 1_000)
                    BudgetBar(1_200, 1_000)
                }
            }
        }

        composeRule.onNodeWithContentDescription("预算使用率 50%，状态正常").assertExists()
        composeRule.onNodeWithContentDescription("预算使用率 80%，状态预警").assertExists()
        composeRule.onNodeWithContentDescription("预算使用率 120%，状态超支").assertExists()
    }
}
