package com.example.emit.data

import org.junit.Assert.*
import org.junit.Test

class QuoteParseTest {
    @Test fun parsesReplyWithNewline() {
        val q = MessageMeta.parseQuote(">> devin: you still down?\nyes see you at 7")!!
        assertEquals("devin", q.who); assertEquals("you still down?", q.quote); assertEquals("yes see you at 7", q.body)
    }
    @Test fun roundTripsWhatWeSend() {
        val sent = MessageMeta.withQuote(MessageMeta.quote("devin", "tacos at 7?"), "on my way")
        assertEquals(">> devin: tacos at 7?\non my way", sent)  // SMS body format unchanged
        assertEquals("on my way", MessageMeta.parseQuote(sent)!!.body)
    }
    @Test fun multilineBodyKept() = assertEquals("a\nb", MessageMeta.parseQuote(">> you: x\na\nb")!!.body)
    @Test fun noNewlineIsNotAReply() = assertNull(MessageMeta.parseQuote(">> devin: just text"))
    @Test fun ellipsizedQuote() {
        val q = MessageMeta.quote("devin", "x".repeat(100))
        assertTrue(q.contains("…")); assertEquals(60, MessageMeta.parseQuote(q + "ok")!!.quote.length)
    }
    @Test fun noFalsePositiveMidMessage() {
        assertNull(MessageMeta.parseQuote("look at this >> devin: hi\nok"))
        assertNull(MessageMeta.parseQuote("hey\n>> devin: hi\nok"))
        assertNull(MessageMeta.parseQuote(">>no space: hi\nok"))
        assertEquals("plain text", MessageMeta.messagePart("plain text"))
    }
    @Test fun replyToReplyQuotesMessagePart() =
        assertEquals(">> sam: second\n", MessageMeta.quote("sam", ">> devin: first\nsecond"))
}
