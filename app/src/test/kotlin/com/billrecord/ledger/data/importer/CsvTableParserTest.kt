package com.billrecord.ledger.data.importer

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTableParserTest {
    @Test fun supportsQuotedCommasAndNewlines() {
        val rows = CsvTableParser.parse("时间,备注,金额\r\n2026-01-01,\"午饭,两人\",32.5\r\n2026-01-02,\"跨\n行\",10")
        assertEquals(3, rows.size)
        assertEquals("午饭,两人", rows[1][1])
        assertEquals("跨\n行", rows[2][1])
    }
}

