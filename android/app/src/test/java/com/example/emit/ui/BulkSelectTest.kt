package com.example.emit.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BulkSelectTest {
    @Test fun toggleAddsAndRemoves() {
        assertEquals(setOf(1L), BulkSelect.toggle(emptySet(), 1))
        assertEquals(emptySet<Long>(), BulkSelect.toggle(setOf(1L), 1))
    }
    @Test fun selectAllThenClear() {
        val all = listOf(1L, 2L, 3L)
        assertEquals(all.toSet(), BulkSelect.selectAll(setOf(2L), all))
        assertEquals(emptySet<Long>(), BulkSelect.selectAll(all.toSet(), all))
    }
    @Test fun pruneDropsMissing() = assertEquals(setOf(2L), BulkSelect.prune(setOf(2L, 9L), listOf(1L, 2L)))
    @Test fun texts() {
        assertEquals("3 selected", BulkSelect.title(3))
        assertEquals("Delete 3 conversations? This can't be undone.", BulkSelect.confirmPrompt(3))
        assertEquals("Delete 1 conversation? This can't be undone.", BulkSelect.confirmPrompt(1))
        assertEquals("> deleted 2 conversations", BulkSelect.result(2, 0))
        assertEquals("> deleted 2, [X] 1 failed", BulkSelect.result(2, 1))
    }
    @Test fun failureDoesNotStopOthers() = runBlocking {
        val tried = mutableListOf<Long>(); val prog = mutableListOf<Pair<Int, Int>>()
        val (ok, bad) = BulkSelect.runAll(listOf(1L, 2L, 3L, 4L), { d, t -> prog += d to t }) { id ->
            tried += id
            when (id) { 2L -> error("boom"); 3L -> false; else -> true }
        }
        assertEquals(listOf(1L, 2L, 3L, 4L), tried)
        assertEquals(listOf(1L, 4L), ok); assertEquals(listOf(2L, 3L), bad)
        assertEquals(4 to 4, prog.last())
    }
}
