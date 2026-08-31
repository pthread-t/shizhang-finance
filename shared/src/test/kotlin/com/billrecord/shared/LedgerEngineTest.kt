package com.billrecord.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LedgerEngineTest {
    @Test
    fun `expense creates balanced postings`() {
        val postings = LedgerEngine.createPostings(
            LedgerDraft(
                transactionId = "tx-1",
                type = TransactionType.EXPENSE,
                accountId = "cash",
                categoryId = "food",
                amountMinor = 3_200,
            ),
        )

        assertEquals(0, postings.sumOf { it.baseAmountMinor })
        assertEquals(-3_200, postings.first().amountMinor)
        assertEquals("system:expense:food", postings.last().ledgerAccountId)
    }

    @Test
    fun `cross currency transfer balances in base currency`() {
        val postings = LedgerEngine.createPostings(
            LedgerDraft(
                transactionId = "tx-2",
                type = TransactionType.TRANSFER,
                accountId = "cny-card",
                destinationAccountId = "usd-cash",
                amountMinor = 720,
                baseAmountMinor = 720,
                destinationAmountMinor = 100,
                destinationCurrency = "USD",
            ),
        )

        assertEquals(0, postings.sumOf { it.baseAmountMinor })
        assertEquals(100, postings.last().amountMinor)
        assertEquals("USD", postings.last().currency)
    }

    @Test
    fun `transfer rejects same account`() {
        assertFailsWith<IllegalArgumentException> {
            LedgerEngine.createPostings(
                LedgerDraft(
                    transactionId = "tx-3",
                    type = TransactionType.TRANSFER,
                    accountId = "cash",
                    destinationAccountId = "cash",
                    amountMinor = 100,
                ),
            )
        }
    }

    @Test
    fun `split expense creates one account posting and balanced category postings`() {
        val postings = LedgerEngine.createPostings(
            LedgerDraft(
                transactionId = "tx-split",
                type = TransactionType.EXPENSE,
                accountId = "card",
                amountMinor = 10_000,
                splits = listOf(
                    LedgerSplitDraft("food", 6_400),
                    LedgerSplitDraft("transport", 3_600),
                ),
            ),
        )

        assertEquals(3, postings.size)
        assertEquals(-10_000, postings.first().amountMinor)
        assertEquals(listOf("system:expense:food", "system:expense:transport"), postings.drop(1).map { it.ledgerAccountId })
        assertEquals(0, postings.sumOf { it.baseAmountMinor })
    }

    @Test
    fun `split totals must exactly equal transaction total`() {
        assertFailsWith<IllegalArgumentException> {
            LedgerEngine.createPostings(
                LedgerDraft(
                    transactionId = "tx-invalid-split",
                    type = TransactionType.EXPENSE,
                    accountId = "cash",
                    amountMinor = 1_000,
                    splits = listOf(LedgerSplitDraft("food", 999), LedgerSplitDraft("other", 1)),
                    baseAmountMinor = 999,
                ),
            )
        }
    }
}
