package com.example.emit.ui

/** Pure, unit-tested helpers for multi-select delete on the chat list. */
object BulkSelect {
    fun toggle(sel: Set<Long>, id: Long): Set<Long> = if (id in sel) sel - id else sel + id
    /** select all; if everything is already selected, clear instead. */
    fun selectAll(sel: Set<Long>, all: List<Long>): Set<Long> = if (all.isNotEmpty() && sel.containsAll(all)) emptySet() else all.toSet()
    /** Drop ids that no longer exist (e.g. deleted / refreshed list). */
    fun prune(sel: Set<Long>, all: List<Long>): Set<Long> = sel intersect all.toSet()
    fun title(n: Int) = "$n selected"
    fun confirmPrompt(n: Int) = "Delete $n conversation${if (n == 1) "" else "s"}? This can't be undone."
    fun progress(done: Int, total: Int) = "> deleting $done/$total..."
    fun result(ok: Int, failed: Int): String = when {
        failed == 0 -> "> deleted $ok conversation${if (ok == 1) "" else "s"}"
        ok == 0 -> "[X] delete failed for $failed conversation${if (failed == 1) "" else "s"}"
        else -> "> deleted $ok, [X] $failed failed"
    }

    /** Runs [delete] for each id; an exception or false on one never stops the rest. Returns (ok ids, failed ids). */
    suspend fun runAll(ids: List<Long>, onProgress: suspend (Int, Int) -> Unit = { _, _ -> },
                       delete: suspend (Long) -> Boolean): Pair<List<Long>, List<Long>> {
        val ok = mutableListOf<Long>(); val bad = mutableListOf<Long>()
        ids.forEachIndexed { i, id ->
            onProgress(i, ids.size)
            if (runCatching { delete(id) }.getOrDefault(false)) ok += id else bad += id
        }
        onProgress(ids.size, ids.size)
        return ok to bad
    }
}
