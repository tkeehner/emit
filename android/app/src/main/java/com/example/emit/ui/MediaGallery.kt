package com.example.emit.ui

import com.example.emit.data.MediaRef
import com.example.emit.data.Message

/**
 * 0.1.52: the photo/video strip the fullscreen viewer swipes through.
 * Every image and video in the loaded conversation, sent and received, oldest first
 * (ties keep the order the parts appear in the message). No wrap-around at either end.
 */
object MediaGallery {
    data class Item(val media: MediaRef, val date: Long, val outgoing: Boolean, val messageKey: String)

    fun build(messages: List<Message>): List<Item> =
        messages.withIndex()
            .flatMap { (mi, m) -> m.media.withIndex().filter { viewable(it.value) }.map { (pi, md) -> Triple(mi, pi, Item(md, m.date, m.outgoing, m.key)) } }
            .sortedWith(compareBy<Triple<Int, Int, Item>>({ it.third.date }, { it.first }, { it.second }))
            .map { it.third }

    fun viewable(md: MediaRef) = md.isImage || md.mime.startsWith("video/")

    /** Index of [md] in [items], or -1. Part ids are unique per MMS part. */
    fun indexOf(items: List<Item>, md: MediaRef) = items.indexOfFirst { it.media.partId == md.partId }

    fun prev(i: Int) = if (i > 0) i - 1 else i
    fun next(i: Int, size: Int) = if (i < size - 1) i + 1 else i
    fun hasPrev(i: Int) = i > 0
    fun hasNext(i: Int, size: Int) = i < size - 1

    /** "3/12"; empty for a single item so lone photos look exactly like before. */
    fun position(i: Int, size: Int) = if (size <= 1) "" else "${i + 1}/$size"
}
