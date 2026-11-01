package com.example.emit.ui

import com.example.emit.data.MediaRef
import com.example.emit.data.Message
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class MessageActionsTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun msg(id: Long, body: String, date: Long, out: Boolean = false, media: List<MediaRef> = emptyList()) =
        Message(id, 1, if (out) "" else "+15551234567", body, date, out, media = media)

    @Test fun singleCopiesBodyOrMarker() {
        assertEquals("hello there", MessageText.single(msg(1, "hello there", 0)))
        assertEquals("[media]", MessageText.single(msg(2, "", 0, media = listOf(MediaRef(5, "image/jpeg")))))
    }

    @Test fun multiIsChronologicalWithSenderAndTime() {
        val a = msg(1, "first", 1_700_000_000_000)
        val b = msg(2, "second", 1_700_000_060_000, out = true)
        val txt = MessageText.multi(listOf(b, a), { if (it.outgoing) "you" else "sam" }, utc)
        assertEquals("[Nov 14 22:13] sam: first\n[Nov 14 22:14] you: second", txt)
    }

    @Test fun saveTargets() {
        assertEquals("Pictures/MatrixSMS" to "jpg", MessageText.saveTarget("image/jpeg"))
        assertEquals("Pictures/MatrixSMS" to "png", MessageText.saveTarget("image/png"))
        assertEquals("Pictures/MatrixSMS" to "gif", MessageText.saveTarget("IMAGE/GIF"))
        assertEquals("Movies/MatrixSMS" to "3gp", MessageText.saveTarget("video/3gpp"))
        assertEquals("Movies/MatrixSMS" to "mp4", MessageText.saveTarget("video/mp4"))
        assertNull(MessageText.saveTarget("text/plain"))
        assertNull(MessageText.saveTarget("application/smil"))
    }

    @Test fun classifyClipboard() {
        assertEquals(MessageText.Paste.Text("hi"), MessageText.classify("hi", null, null))
        assertSame(MessageText.Paste.Nothing, MessageText.classify("", null, null))
        assertSame(MessageText.Paste.Nothing, MessageText.classify(null, null, "image/png"))
    }

    @Test fun pasteInsertsAtCursorReplacingSelection() {
        assertEquals(EditState("see you tomorrow!", 16), EditState("see you !", 8).insert("tomorrow"))
        assertEquals(EditState("a XY d", 4), EditState("a bc d", 2, 4).insert("XY"))
    }
}
