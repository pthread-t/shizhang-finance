package com.billrecord.server

import com.billrecord.shared.SyncOperationType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncConflictTest {
    @Test
    fun `different fields merge without conflict`() {
        val conflicts = detectConflictingFields(3, mapOf("note" to 3, "amountMinor" to 1), false, SyncOperationType.UPSERT, 1, setOf("amountMinor"))
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `same field modification conflicts`() {
        val conflicts = detectConflictingFields(3, mapOf("note" to 3), false, SyncOperationType.UPSERT, 1, setOf("note"))
        assertEquals(setOf("note"), conflicts)
    }

    @Test
    fun `editing a newer tombstone conflicts as whole entity`() {
        assertEquals(setOf("*"), detectConflictingFields(4, mapOf("*" to 4), true, SyncOperationType.UPSERT, 2, setOf("note")))
    }

    @Test
    fun `repeated delete does not create user conflict`() {
        assertTrue(detectConflictingFields(4, mapOf("*" to 4), true, SyncOperationType.DELETE, 2, emptySet()).isEmpty())
    }
}
