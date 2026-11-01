package com.example.emit.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ThreadForTest {
    @Test fun normalizesFormatting() {
        assertEquals("5555550123", ThreadFor.normalize(" (555) 555-0123 "))
        assertEquals("+15555550123", ThreadFor.normalize("+1 555.555.0123"))
    }
    @Test fun rejectsCodesAndJunk() {
        for (bad in listOf("*#06#", "*72", "12;3", "", "abc", "12", "1234567890123456")) assertThrows(RpcError::class.java) { ThreadFor.normalize(bad) }
    }
}
