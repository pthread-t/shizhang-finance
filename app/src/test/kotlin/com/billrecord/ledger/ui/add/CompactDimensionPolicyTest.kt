package com.billrecord.ledger.ui.add

import org.junit.Assert.assertEquals
import org.junit.Test

class CompactDimensionPolicyTest {
    @Test
    fun selectedItemsStayVisibleBeforeCommonItems() {
        val ordered = (1..12).map(Int::toString)

        assertEquals(
            listOf("10", "1", "2", "3", "4", "5", "6", "7"),
            compactDimensionIds(ordered, setOf("10")),
        )
    }

    @Test
    fun allSelectedItemsRemainVisibleEvenAboveDefaultLimit() {
        val ordered = (1..12).map(Int::toString)
        val selected = (3..11).map(Int::toString).toSet()

        assertEquals((3..11).map(Int::toString), compactDimensionIds(ordered, selected))
    }

    @Test
    fun unknownSelectionsAreIgnored() {
        assertEquals(listOf("1", "2", "3"), compactDimensionIds(listOf("1", "2", "3"), setOf("missing")))
    }
}
