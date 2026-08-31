package com.billrecord.ledger

import android.graphics.Bitmap
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Live acceptance test. It deliberately relies on a model profile configured by the tester and
 * never embeds or prints an API key. Run this class explicitly; it performs real provider calls.
 */
@RunWith(AndroidJUnit4::class)
@Ignore("Requires a tester-configured live AI provider and must be run explicitly")
class AiAssistantLiveAcceptanceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mockLedgerQuestionsProduceCompleteAccessibleAnswers() {
        composeRule.waitUntil(30_000) { nodesWithText("首页") > 0 }
        composeRule.onAllNodes(hasText("分析")).onFirst().performClick()
        composeRule.waitUntil(10_000) { nodesWithText("AI 助手") > 0 }
        composeRule.onAllNodes(hasText("AI 助手")).onFirst().performClick()
        composeRule.waitUntil(20_000) { nodesWithText("AI 财务助手") > 0 && editableNodes() > 0 }

        val questions = listOf(
            "今年每月餐饮支出趋势",
            "近三个月支出最多的分类",
            "本月比上月多花在哪里",
            "本月微信账户 100 元以上的支出",
        )
        val expectedAggregates = listOf("¥900.00", "¥2,080.50", null, "¥1,040.00")
        questions.forEachIndexed { index, question ->
            composeRule.onAllNodes(hasContentDescription("新建会话")).onFirst().performClick()
            composeRule.waitUntil(10_000) { editableNodes() > 0 }
            val completedBefore = completedAnswerNodes()
            composeRule.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement(question)
            composeRule.onAllNodes(hasContentDescription("发送问题")).onFirst().performClick()
            composeRule.waitUntil(20_000) { loadingNodes() > 0 }
            composeRule.waitUntil(180_000) { loadingNodes() == 0 }

            assertEquals("第 ${index + 1} 个问题不应出现重试状态", 0, nodesWithText("重试"))
            assertEquals("第 ${index + 1} 个问题不应把有数据的条件判为空", 0, nodesWithText("当前条件下没有账单记录"))
            composeRule.waitUntil(20_000) { completedAnswerNodes() > completedBefore }
            expectedAggregates[index]?.let { expected ->
                assertTrue("第 ${index + 1} 个问题应包含已核对的聚合金额 $expected", nodesWithText(expected) > 0)
            }
            if (index == 1 || index == 3) assertEquals("未要求对比的问题不得声称较上期变化", 0, nodesWithText("较上期"))
            assertTrue("回答应提供可点击的本机下钻入口", clickableNodes() >= 4)
            saveScreenshot("question_${index + 1}")
        }
    }

    private fun nodesWithText(value: String): Int = composeRule
        .onAllNodes(hasText(value, substring = true), useUnmergedTree = true)
        .fetchSemanticsNodes(atLeastOneRootRequired = false)
        .size

    private fun editableNodes(): Int = composeRule
        .onAllNodes(hasSetTextAction(), useUnmergedTree = true)
        .fetchSemanticsNodes(atLeastOneRootRequired = false)
        .size

    private fun loadingNodes(): Int = nodesWithText("正在查询本机账本并生成分析")

    private fun completedAnswerNodes(): Int = nodesWithText("仅含聚合数据")

    private fun clickableNodes(): Int = composeRule
        .onAllNodes(hasClickAction(), useUnmergedTree = true)
        .fetchSemanticsNodes(atLeastOneRootRequired = false)
        .size

    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir("ai-e2e"))
        val output = File(directory, "$name.png")
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        output.outputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
        bitmap.recycle()
        println("AI_E2E_SCREENSHOT=${output.absolutePath}")
    }
}
