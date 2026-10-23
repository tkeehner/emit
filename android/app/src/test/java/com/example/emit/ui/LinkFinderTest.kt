package com.example.emit.ui

import org.junit.Assert.*
import org.junit.Test

class LinkFinderTest {
    private fun t(s: String) = LinkFinder.find(s).map { it.target }
    @Test fun urls() {
        assertEquals(listOf("https://example.com/a?b=1"), t("see https://example.com/a?b=1."))
        assertEquals(listOf("https://www.google.com"), t("(www.google.com)"))
        assertEquals(listOf("https://youtube.com/watch"), t("youtube.com/watch, ok"))
        assertEquals(listOf("https://en.wikipedia.org/wiki/Foo_(bar)"), t("https://en.wikipedia.org/wiki/Foo_(bar)"))
    }
    @Test fun emailAndPhone() {
        assertEquals(listOf("mailto:bob@mail.com"), t("mail bob@mail.com"))
        assertEquals(listOf("tel:(555)1234567".filter { it.isDigit() }.let { "tel:$it" }), t("call (555) 123-4567"))
        assertEquals(listOf("tel:+15551234567"), t("+1 555-123-4567"))
        assertTrue(t("order 12345 at 3pm").isEmpty())
    }
}
