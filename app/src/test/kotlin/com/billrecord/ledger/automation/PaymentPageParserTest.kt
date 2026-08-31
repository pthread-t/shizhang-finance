package com.billrecord.ledger.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentPageParserTest {
    @Test fun parsesPaymentCompletion() {
        val result = PaymentPageParser.parse("微信", listOf("支付成功", "拾光咖啡", "¥32.00"))
        assertEquals("32.00", result?.amount)
        assertEquals("微信", result?.source)
    }

    @Test fun ignoresNonCompletionScreens() {
        assertNull(PaymentPageParser.parse("支付宝", listOf("确认付款", "¥32.00")))
    }
}

