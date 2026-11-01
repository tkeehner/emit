package com.example.emit.ui

import com.example.emit.data.ChatPrefs
import com.example.emit.data.Conversation
import com.example.emit.sms.Scheduler
import org.junit.Assert.*
import org.junit.Test

class StageDTest {
    private fun c(id: Long, d: Long) = Conversation(id, listOf("+1$id"), listOf("n$id"), "", d, false)
    @Test fun arrangePinArchive() {
        val l = listOf(c(1, 10), c(2, 30), c(3, 20), c(4, 40))
        val f = ChatPrefs.Flags(pinned = setOf(3), archived = setOf(4))
        assertEquals(listOf(3L, 2L, 1L), ChatPrefs.arrange(l, f, false).map { it.threadId })
        assertEquals(listOf(4L), ChatPrefs.arrange(l, f, true).map { it.threadId })
        assertEquals("[^][m]", ChatPrefs.badges(3, f.copy(muted = setOf(3))))
    }
    @Test fun search() {
        assertEquals(listOf(0, 2), ThreadSearch.matches(listOf("Hello", "x", "say hello"), "HELLO"))
        assertEquals(listOf(0..1, 3..4), ThreadSearch.ranges("abxab", "AB"))
        assertEquals(0, ThreadSearch.step(-1, 3, true)); assertEquals(2, ThreadSearch.step(-1, 3, false))
        assertEquals(0, ThreadSearch.step(2, 3, true)); assertEquals(2, ThreadSearch.step(0, 3, false)); assertEquals(-1, ThreadSearch.step(0, 0, true))
    }
    @Test fun schedulingRules() {
        val now = 1_000_000_000L
        assertNull(Scheduler.validate("hi", "+1555", now + 3_600_000, now))
        assertNotNull(Scheduler.validate("", "+1555", now + 3_600_000, now))
        assertNotNull(Scheduler.validate("hi", "", now + 3_600_000, now))
        assertNotNull(Scheduler.validate("hi", "+1555", now + 1000, now))
    }
    @Test fun previews() {
        assertEquals("A & B", LinkPreview.parseTitle("<html><head><title>\n A &amp; B </title>"))
        assertEquals("OG", LinkPreview.parseTitle("<meta property=\"og:title\" content=\"OG\"><title>t</title>"))
        assertNull(LinkPreview.parseTitle("<p>none</p>"))
        assertEquals("https://x.com/a?id=1", LinkPreview.clean("https://x.com/a?utm_source=t&id=1&fbclid=z"))
        assertEquals("https://x.com/a", LinkPreview.clean("https://x.com/a?utm_medium=q"))
        assertEquals("example.com", LinkPreview.domain("https://www.example.com/p"))
    }
}
