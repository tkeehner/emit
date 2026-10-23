package com.example.emit.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayNameTest {
    @Test fun keepsContactNameExactly() {
        assertEquals("Ryan's Mom", DisplayName.person("Ryan's Mom", "+15551234567"))
        assertEquals("Shawn Lenihan", DisplayName.person("  Shawn Lenihan ", "5551234567"))
        assertEquals("jenna Attinger", DisplayName.person("jenna Attinger", "5551234567"))
        assertEquals("S Walden", DisplayName.person("S Walden", "5551234567"))
    }
    @Test fun fallsBackToFormattedNumber() {
        assertEquals("(555) 123-4567", DisplayName.person(null, "5551234567"))
        assertEquals("+1 (555) 123-4567", DisplayName.person("", "+15551234567"))
        assertEquals("+1 (555) 123-4567", DisplayName.person("+15551234567", "+15551234567"))
        assertEquals("+44 20 7946 0958", DisplayName.phone("+44 20 7946 0958"))
        assertEquals("72975", DisplayName.phone("72975"))
        assertEquals("GOOGLE", DisplayName.person(null, "GOOGLE"))
        assertEquals("unknown", DisplayName.person(null, ""))
    }
    @Test fun groupTitles() {
        assertEquals("Mom, Devin, Jonathan", DisplayName.chat(null, listOf("Mom", "Devin", "Jonathan"), listOf("1", "2", "3")))
        assertEquals("Mom, (555) 123-4567", DisplayName.chat(null, listOf("Mom", "5551234567"), listOf("+1555000", "5551234567")))
        assertEquals("Tacos crew", DisplayName.chat(" Tacos crew ", listOf("Mom"), listOf("1")))
        assertEquals("Ryan's Mom", Conversation(1, listOf("+15551234567"), listOf("Ryan's Mom"), "", 0, false).displayName)
    }
    @Test fun senderLabelsUseRealName() =
        assertEquals("Ryan's Mom", ThreadMapping.senderName("+15551234567", listOf("+15551234567", "+15550000000"), listOf("Ryan's Mom", "Devin")))
    @Test fun quoteKeepsSpacedNames() =
        assertEquals("Ryan's Mom", MessageMeta.parseQuote(MessageMeta.quote("Ryan's Mom", "hi") + "ok")!!.who)
}
