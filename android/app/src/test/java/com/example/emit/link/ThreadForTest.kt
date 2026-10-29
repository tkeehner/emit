package com.example.emit.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ThreadForTest {
    @Test fun normalizesFormatting() {
        assertEquals("9015551234", ThreadFor.normalize(" (901) 555-1234 "))
        assertEquals("+19015551234", ThreadFor.normalize("+1 901.555.1234"))
    }
    @Test fun rejectsCodesAndJunk() {
        for (bad in listOf("*#06#", "*72", "12;3", "", "abc", "12", "1234567890123456")) assertThrows(RpcError::class.java) { ThreadFor.normalize(bad) }
    }
}
