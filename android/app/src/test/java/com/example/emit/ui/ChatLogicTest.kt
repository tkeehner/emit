package com.example.emit.ui

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ChatLogicTest {
    private val z = ZoneId.of("America/Chicago")
    private fun t(d: Int, h: Int, m: Int, s: Int = 0) = ZonedDateTime.of(2026, 10, d, h, m, s, 0, z).toInstant().toEpochMilli()
    private val now = t(21, 21, 0)

    @Test fun dayLabels() {
        assertEquals("TODAY", ChatLogic.dayLabel(t(21, 0, 5), now, z))
        assertEquals("YESTERDAY", ChatLogic.dayLabel(t(20, 23, 59), now, z))
        assertEquals("SUN OCT 18", ChatLogic.dayLabel(t(18, 12, 0), now, z))
        assertEquals("WED OCT 22 2025", ChatLogic.dayLabel(ZonedDateTime.of(2025, 10, 22, 9, 0, 0, 0, z).toInstant().toEpochMilli(), now, z))
    }
    @Test fun grouping() {
        val e = listOf(ChatLogic.Entry("you", t(20, 10, 0)), ChatLogic.Entry("you", t(21, 9, 0)), ChatLogic.Entry("you", t(21, 9, 1, 30)),
            ChatLogic.Entry("you", t(21, 9, 3, 31)), ChatLogic.Entry("bob", t(21, 9, 4)), ChatLogic.Entry("bob", t(21, 9, 4, 10)))
        val r = ChatLogic.rows(e, now, z)
        assertEquals(listOf("YESTERDAY", "TODAY", null, null, null, null), r.map { it.divider })
        assertEquals(listOf(true, true, false, true, true, false), r.map { it.first })   // 9:03:31 is > 2 min after 9:01:30
        assertEquals(listOf(true, false, true, true, false, true), r.map { it.last })
    }
    @Test fun midnightBreaksGroup() {
        val r = ChatLogic.rows(listOf(ChatLogic.Entry("a", t(20, 23, 59, 30)), ChatLogic.Entry("a", t(21, 0, 0, 30))), now, z)
        assertTrue(r[1].first); assertEquals("TODAY", r[1].divider)
    }
    @Test fun delivery() {
        assertEquals(ChatLogic.Delivery.NONE, ChatLogic.smsDelivery(1, -1))
        assertEquals(ChatLogic.Delivery.PENDING, ChatLogic.smsDelivery(4, -1))
        assertEquals(ChatLogic.Delivery.SENT, ChatLogic.smsDelivery(2, -1))
        assertEquals(ChatLogic.Delivery.SENT, ChatLogic.smsDelivery(2, 32))
        assertEquals(ChatLogic.Delivery.DELIVERED, ChatLogic.smsDelivery(2, 0))
        assertEquals(ChatLogic.Delivery.FAILED, ChatLogic.smsDelivery(2, 64))
        assertEquals(ChatLogic.Delivery.FAILED, ChatLogic.smsDelivery(5, -1))
        assertTrue(ChatLogic.canRetry(ChatLogic.Delivery.FAILED, false))
        assertFalse(ChatLogic.canRetry(ChatLogic.Delivery.FAILED, true)); assertFalse(ChatLogic.canRetry(ChatLogic.Delivery.SENT, false))
    }
    @Test fun segments() {
        assertEquals("[0/160]", ChatLogic.segments("").label)
        assertEquals("[160/160]", ChatLogic.segments("a".repeat(160)).label)
        assertEquals("[161/306 2sms]", ChatLogic.segments("a".repeat(161)).label)
        assertEquals("[2/160]", ChatLogic.segments("€").label)
        assertEquals("[4/70]", ChatLogic.segments("hi😀").label)
        assertEquals("[71/134 2sms]", ChatLogic.segments("é".repeat(0) + "ж".repeat(71)).label)
    }
    @Test fun statusLineFixedWidth() {
        val all = listOf(ChatLogic.statusLine("PC connected via relay", 0), ChatLogic.statusLine("connected to relay", 0),
            ChatLogic.statusLine("off", 0), ChatLogic.statusLine("off", 1))
        assertEquals(1, all.map { it.length }.toSet().size)
    }
}
