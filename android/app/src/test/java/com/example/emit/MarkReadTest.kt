package com.example.emit

import com.example.emit.data.Conversation
import org.junit.Assert.*
import org.junit.Test

class MarkReadTest {
    private fun c(id: Long, unread: Boolean) = Conversation(id, listOf("555$id"), listOf("n$id"), "", 0, unread)
    private val list = listOf(c(1, true), c(2, true), c(3, false))

    @Test fun clearsOnlyThatThread() {
        val r = markReadLocally(list, 2)
        assertEquals(listOf(true, false, false), r.map { it.unread })
    }
    @Test fun nullThreadNoChange() = assertSame(list, markReadLocally(list, null))
    @Test fun unknownThreadNoChange() = assertEquals(list, markReadLocally(list, 99))
}
