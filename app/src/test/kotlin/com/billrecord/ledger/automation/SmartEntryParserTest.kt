package com.billrecord.ledger.automation

import com.billrecord.shared.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartEntryParserTest {
    @Test fun parsesChineseQuickEntry() {
        val result = SmartEntryParser.parse("微信 午饭 32.50")
        assertEquals(3_250L, result.amountMinor)
        assertEquals("餐饮", result.categoryHint)
        assertEquals("微信", result.accountHint)
        assertEquals(TransactionType.EXPENSE, result.type)
    }

    @Test fun parsesIncome() {
        val result = SmartEntryParser.parse("工资收入 12000 银行卡")
        assertEquals(1_200_000L, result.amountMinor)
        assertEquals(TransactionType.INCOME, result.type)
    }
}

