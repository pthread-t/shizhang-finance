package com.billrecord.ledger.data

import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionSortTest {
    @Test fun `amount sorting uses base currency magnitude and stable ties`() {
        val sql = transactionOrderBy(TransactionSort.AMOUNT_DESC)
        assertTrue(sql.startsWith("ABS(baseAmountMinor) DESC"))
        assertTrue(sql.endsWith("occurredAt DESC, id DESC"))
    }

    @Test fun `title sorting prefers note and uses localized collation`() {
        val sql = transactionOrderBy(TransactionSort.TITLE_ASC)
        assertTrue(sql.contains("TRIM(note)"))
        assertTrue(sql.contains("categories"))
        assertTrue(sql.contains("COLLATE LOCALIZED ASC"))
    }
}
