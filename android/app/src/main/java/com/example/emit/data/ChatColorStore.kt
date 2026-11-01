package com.example.emit.data

import android.content.Context
import com.example.emit.ui.ChatPalette
import com.example.emit.ui.NeonColor

/** Per-thread color choice, persisted in SharedPreferences keyed by Telephony threadId. */
class ChatColorStore(context: Context) {
    private val prefs = context.getSharedPreferences("chat_colors", Context.MODE_PRIVATE)
    fun all(): Map<Long, String> = prefs.all.mapNotNull { (k, v) -> k.toLongOrNull()?.let { it to v.toString() } }.toMap()
    fun get(threadId: Long): NeonColor = ChatPalette.byId(prefs.getString(threadId.toString(), null))
    fun set(threadId: Long, colorId: String) = prefs.edit().putString(threadId.toString(), colorId).apply()
    fun reset(threadId: Long) = prefs.edit().remove(threadId.toString()).apply()
}
