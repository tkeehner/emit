package com.example.emit.data

import com.example.emit.ui.ChatLogic
import org.junit.Assert.*
import org.junit.Test

class MessageMetaTest {
    @Test fun quoteLine() {
        assertEquals(">> bob: hey there\n", MessageMeta.quote("bob", "\nhey there\nsecond"))
        assertEquals(">> bob: [media]\n", MessageMeta.quote("bob", ""))
        assertEquals(60 + ">> b: ".length + 1, MessageMeta.quote("b", "x".repeat(200)).length)
    }
    @Test fun replacingQuoteKeepsTypedText() {
        val t = MessageMeta.withQuote(">> a: one\n", "typed")
        assertEquals(">> a: one\ntyped", t)
        assertEquals(">> b: two\ntyped", MessageMeta.withQuote(">> b: two\n", t))
    }
    @Test fun infoRows() {
        val m = Message(1, 2, "+1555", "hi", 1000, outgoing = true, delivery = ChatLogic.Delivery.DELIVERED)
        val r = MessageMeta.info(m, 900, 1500) { "t$it" }.toMap()
        assertEquals("SMS", r["type"]); assertEquals("t900", r["sent"]); assertEquals("t1500", r["delivered"]); assertEquals("delivered", r["status"])
        val inc = MessageMeta.info(Message(1, 2, "+1555", "", 1000, outgoing = false, isMms = true), null, null) { "t$it" }.toMap()
        assertEquals("MMS", inc["type"]); assertEquals("t1000", inc["received"]); assertNull(inc["delivered"])
    }
}
