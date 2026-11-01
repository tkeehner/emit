package com.example.emit.data

import android.content.Context

/** Local, per-threadId group names (never sent anywhere). */
class GroupNameStore(context: Context) {
    private val prefs = context.getSharedPreferences("group_names", Context.MODE_PRIVATE)
    fun all(): Map<Long, String> = prefs.all.mapNotNull { (k, v) -> k.toLongOrNull()?.let { it to v.toString() } }.toMap()
    fun set(threadId: Long, name: String?) {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) prefs.edit().remove(threadId.toString()).apply() else prefs.edit().putString(threadId.toString(), n.take(40)).apply()
    }
}
