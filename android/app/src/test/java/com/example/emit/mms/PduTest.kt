package com.example.emit.mms

import org.junit.Assert.*
import org.junit.Test

class PduTest {
    private fun u(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }

    @Test fun sendReqHeaderStart() {
        val b = Pdu.encodeSendReq(listOf("+15551234567"), listOf(PduPart("text/plain", "hi".toByteArray(), "t.txt")), "T1")
        assertEquals(0x8C, b[0].toInt() and 0xFF); assertEquals(0x80, b[1].toInt() and 0xFF)
        assertTrue(String(b, Charsets.ISO_8859_1).contains("+15551234567/TYPE=PLMN"))
    }

    @Test fun addressFormatting() {
        assertEquals("+15551234567/TYPE=PLMN", Pdu.mmsAddress("+1 (555) 123-4567"))
        assertEquals("a@b.com", Pdu.mmsAddress("a@b.com"))
    }

    /** A send-req is header-compatible with retrieve-conf parsing: round-trip parts through our parser. */
    @Test fun roundTripParts() {
        val img = ByteArray(5000) { (it % 251).toByte() }
        val enc = Pdu.encodeSendReq(listOf("5551234567"), listOf(PduPart("text/plain", "hello".toByteArray(), "text0.txt"),
            PduPart("image/jpeg", img, "image0.jpg")), "T2").also { it[1] = 0x84.toByte() } // as retrieve-conf
        val r = Pdu.parseRetrieveConf(enc)!!
        assertEquals(2, r.parts.size)
        assertEquals("text/plain", r.parts[0].contentType); assertEquals("hello", String(r.parts[0].data))
        assertEquals("image/jpeg", r.parts[1].contentType); assertEquals("image0.jpg", r.parts[1].name)
        assertArrayEquals(img, r.parts[1].data)
        assertEquals(listOf("5551234567"), r.to)
    }

    @Test fun parseNotificationInd() {
        val loc = "http://mms.vtext.com/servlets/mms?message-id=abc"
        val from = "+15550001111/TYPE=PLMN"
        val b = u(0x8C, 0x82, 0x98) + "TX9".toByteArray() + u(0, 0x8D, 0x92) +
            u(0x89, from.length + 2, 0x80) + from.toByteArray() + u(0) +
            u(0x8E, 2, 0x12, 0x34) + u(0x83) + loc.toByteArray() + u(0)
        val n = Pdu.parseNotification(b)!!
        assertEquals("TX9", n.transactionId); assertEquals(loc, n.contentLocation)
        assertEquals("+15550001111", n.from); assertEquals(0x1234L, n.size)
    }

    @Test fun notificationRejectsOtherTypes() = assertNull(Pdu.parseNotification(u(0x8C, 0x80, 0x98, 0x41, 0)))

    @Test fun perItemBudget() {
        assertEquals((MediaPrep.TOTAL_BUDGET - 100 - 2000) / 2, MediaPrep.perItemBudget(2, 100))
        assertEquals(0, MediaPrep.perItemBudget(0, 0))
    }

    @Test fun groupSendReqHasAllRecipients() {
        val enc = Pdu.encodeSendReq(listOf("5551110000", "5552220000", "+15553330000"),
            listOf(PduPart("text/plain", "yo".toByteArray(), "text0.txt")), "T3").also { it[1] = 0x84.toByte() }
        val r = Pdu.parseRetrieveConf(enc)!!
        assertEquals(listOf("5551110000", "5552220000", "+15553330000"), r.to)
        assertEquals("yo", String(r.parts.single().data))
    }

    /** Verizon-style group retrieve-conf: FROM + 2 TO, an app header, text/plain;charset part + jpeg part. */
    @Test fun groupRetrieveConfWithSenderTextAndImage() {
        val from = "+15550001111/TYPE=PLMN"
        val hdr = u(0x8C, 0x84, 0x98) + "TX1".toByteArray() + u(0, 0x8D, 0x92) +
            "X-Carrier".toByteArray() + u(0) + "vzw".toByteArray() + u(0) +
            u(0x89, from.length + 2, 0x80) + from.toByteArray() + u(0) +
            u(0x97) + "+15552223333/TYPE=PLMN".toByteArray() + u(0) +
            u(0x97) + "+15554445555/TYPE=PLMN".toByteArray() + u(0) +
            u(0x85, 4, 0x65, 0x00, 0x00, 0x01) +
            u(0x84, 0xB3)
        val text = "hey all".toByteArray(); val img = ByteArray(300) { it.toByte() }
        val th = u(3, 0x83, 0x81, 0xEA, 0x8E) + "text_0.txt".toByteArray() + u(0)
        val ih = u(0x9E, 0x8E) + "img.jpg".toByteArray() + u(0)
        val body = u(2) + u(th.size, text.size) + th + text + u(ih.size, 0x82, 0x2C) + ih + img
        val r = Pdu.parseRetrieveConf(hdr + body)!!
        assertEquals("+15550001111", r.from)
        assertEquals(listOf("+15552223333", "+15554445555"), r.to)
        assertEquals(2, r.parts.size)
        assertEquals("hey all", Pdu.bodyText(r.parts))
        assertTrue(r.parts[1].isImage); assertArrayEquals(img, r.parts[1].data)
    }

    @Test fun garbageIsNotARetrieveConf() {
        assertNull(Pdu.parseRetrieveConf("<html><body>error</body></html>".toByteArray()))
        assertNull(Pdu.parseRetrieveConf(ByteArray(0)))
    }

    @Test fun bodyTextIgnoresNonText() {
        assertNull(Pdu.bodyText(listOf(PduPart("application/smil", "<smil/>".toByteArray(), "s"))))
        assertEquals("a b", Pdu.bodyText(listOf(PduPart("Text/Plain; charset=utf-8", "a".toByteArray(), "1"), PduPart("text/plain", "b".toByteArray(), "2"))))
    }
}
