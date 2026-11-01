package com.example.emit.data

import org.junit.Assert.*
import org.junit.Test

class GroupMappingTest {
    @Test fun sameSetIgnoresOrderAndFormat() = assertTrue(ThreadMapping.sameSet(listOf("+15551110000", "5552220000"), listOf("(555) 222-0000", "5551110000")))
    @Test fun sameSetRejectsExtraMember() = assertFalse(ThreadMapping.sameSet(listOf("5551110000", "5552220000"), listOf("5551110000", "5552220000", "5553330000")))
    @Test fun sameSetRejectsMissingMember() = assertFalse(ThreadMapping.sameSet(listOf("5551110000", "5552220000"), listOf("5551110000")))
    @Test fun sameSetRejectsDuplicatesMasking() = assertFalse(ThreadMapping.sameSet(listOf("5551110000", "+15551110000"), listOf("5551110000")))
    @Test fun sameSetEmptyNeverMatches() = assertFalse(ThreadMapping.sameSet(emptyList(), emptyList()))

    @Test fun groupExcludesOwnNumber() = assertEquals(listOf("+15551110000", "5552220000"),
        ThreadMapping.groupRecipients("+15551110000", listOf("+15559990000", "5552220000"), setOf("5559990000")))
    @Test fun oneToOneUnknownOwnDropsSingleTo() = assertEquals(listOf("5551110000"),
        ThreadMapping.groupRecipients("5551110000", listOf("5559990000"), emptySet()))
    @Test fun dedupesSenderAlsoInTo() = assertEquals(listOf("5551110000", "5552220000"),
        ThreadMapping.groupRecipients("5551110000", listOf("+15551110000", "5552220000", "5559990000"), setOf("+1 555 999 0000")))

    @Test fun senderNameMatchesNormalized() = assertEquals("Jake", ThreadMapping.senderName("+15552220000", listOf("5551110000", "5552220000"), listOf("Mom", "Jake")))
    @Test fun senderNameFallsBackToNumber() = assertEquals("(555) 777-0000", ThreadMapping.senderName("5557770000", listOf("5551110000"), listOf("Mom")))
}
