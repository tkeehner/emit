package com.example.emit.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** Per-thread pin / mute / archive flags (on-device only; archive hides, never deletes). */
object ChatPrefs {
    data class Flags(val pinned: Set<Long> = emptySet(), val muted: Set<Long> = emptySet(), val archived: Set<Long> = emptySet())
    val flags = MutableStateFlow(Flags())
    private fun p(c: Context) = c.getSharedPreferences("chat_prefs", 0)
    private fun read(c: Context, k: String) = p(c).getStringSet(k, emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
    fun load(c: Context): Flags = Flags(read(c, "pinned"), read(c, "muted"), read(c, "archived")).also { flags.value = it }
    private fun write(c: Context, k: String, v: Set<Long>) { p(c).edit().putStringSet(k, v.map { it.toString() }.toSet()).apply(); load(c) }
    fun toggle(c: Context, which: String, id: Long) { val cur = read(c, which); write(c, which, if (id in cur) cur - id else cur + id) }
    /** Bulk: if every id is already set, clear them all; otherwise set them all. */
    fun toggleAll(c: Context, which: String, ids: Set<Long>) { val cur = read(c, which); write(c, which, if (ids.isNotEmpty() && cur.containsAll(ids)) cur - ids else cur + ids) }
    fun isMuted(c: Context, id: Long) = id in read(c, "muted")

    /** Pure: pinned first (newest first), then the rest; archived hidden unless [archivedView] (then only archived). */
    fun arrange(list: List<Conversation>, f: Flags, archivedView: Boolean): List<Conversation> {
        val base = list.filter { (it.threadId in f.archived) == archivedView }
        val (pin, rest) = base.partition { it.threadId in f.pinned }
        return pin.sortedByDescending { it.date } + rest.sortedByDescending { it.date }
    }
    /** Pure: "[^]" pinned, "[m]" muted prefix for the inbox row. */
    fun badges(id: Long, f: Flags) = (if (id in f.pinned) "[^]" else "") + (if (id in f.muted) "[m]" else "")
}
