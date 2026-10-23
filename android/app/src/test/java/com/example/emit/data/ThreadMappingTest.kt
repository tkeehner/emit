package com.example.emit.data

import org.junit.Assert.*
import org.junit.Test

class ThreadMappingTest {
    private val canon = mapOf(1L to "+15551234567", 2L to "5559876543", 7L to "alice@example.com")

    @Test fun singleRecipient() = assertEquals(listOf("+15551234567"), ThreadMapping.recipients("1", canon))
    @Test fun groupRecipientsInOrder() = assertEquals(listOf("5559876543", "+15551234567"), ThreadMapping.recipients(" 2  1 ", canon))
    @Test fun unknownIdsDropped() = assertEquals(listOf("+15551234567"), ThreadMapping.recipients("1 99 x", canon))
    @Test fun emptyRecipients() = assertTrue(ThreadMapping.recipients(null, canon).isEmpty())
    @Test fun plusOneMatchesTenDigit() = assertTrue(ThreadMapping.sameAddress("+1 (555) 123-4567", "5551234567"))
    @Test fun differentNumbersDontMatch() = assertFalse(ThreadMapping.sameAddress("5551234567", "5551234568"))
    @Test fun blankNeverMatches() = assertFalse(ThreadMapping.sameAddress("", ""))
    @Test fun emailCaseInsensitive() = assertTrue(ThreadMapping.sameAddress("Alice@Example.com", "alice@example.com"))

    @Test fun mmsSenderUsesFromRowOnly() {
        org.junit.Assert.assertEquals("+15550001111", ThreadMapping.mmsSender(listOf("+15559998888" to 151, "+15550001111/TYPE=PLMN" to 137)))
        org.junit.Assert.assertEquals("", ThreadMapping.mmsSender(listOf("insert-address-token" to 137, "+1555" to 151)))
        org.junit.Assert.assertEquals("", ThreadMapping.mmsSender(listOf("unknown" to 137)))
    }
}
