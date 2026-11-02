package com.example.emit.ui

import com.example.emit.data.MediaRef
import com.example.emit.data.Message
import org.junit.Assert.*
import org.junit.Test

class MediaGalleryTest {
    private fun msg(id: Long, date: Long, out: Boolean, vararg media: MediaRef) =
        Message(id, 1, "5555550142", "", date, out, isMms = true, media = media.toList())
    private fun img(p: Long) = MediaRef(p, "image/jpeg")

    @Test fun mixesSentAndReceivedOldestFirst() {
        // Thread lists can come newest-first; the gallery must still read oldest -> newest.
        val g = MediaGallery.build(listOf(msg(3, 300, true, img(30)), msg(1, 100, false, img(10)), msg(2, 200, true, img(20))))
        assertEquals(listOf(10L, 20L, 30L), g.map { it.media.partId })
        assertEquals(listOf(false, true, true), g.map { it.outgoing })
    }

    @Test fun keepsPartOrderWithinOneMessageAndSkipsNonMedia() {
        val g = MediaGallery.build(listOf(msg(1, 100, false, img(2), MediaRef(9, "text/x-vcard"), MediaRef(3, "video/mp4"), img(1))))
        assertEquals(listOf(2L, 3L, 1L), g.map { it.media.partId })
    }

    @Test fun textOnlyMessagesAreIgnored() {
        assertTrue(MediaGallery.build(listOf(msg(1, 1, true), msg(2, 2, false))).isEmpty())
    }

    @Test fun noWrapAtEitherEnd() {
        assertEquals(0, MediaGallery.prev(0)); assertEquals(1, MediaGallery.prev(2))
        assertEquals(4, MediaGallery.next(4, 5)); assertEquals(3, MediaGallery.next(2, 5))
        assertFalse(MediaGallery.hasPrev(0)); assertFalse(MediaGallery.hasNext(4, 5))
        assertTrue(MediaGallery.hasPrev(1)); assertTrue(MediaGallery.hasNext(3, 5))
    }

    @Test fun positionIndicator() {
        assertEquals("3/12", MediaGallery.position(2, 12))
        assertEquals("", MediaGallery.position(0, 1))
    }

    @Test fun findsTappedPhoto() {
        val g = MediaGallery.build(listOf(msg(1, 100, false, img(10)), msg(2, 200, true, img(20))))
        assertEquals(1, MediaGallery.indexOf(g, img(20)))
        assertEquals(-1, MediaGallery.indexOf(g, img(99)))
    }
}
