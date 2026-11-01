package com.example.emit.bot

import android.content.Context
import com.example.emit.data.SmsRepository
import com.example.emit.link.LinkEvents
import com.example.emit.link.LinkState
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * @bot commands. The ONLY entry point is [BotCommands.onComposerSend], called from the thread composer's send action in
 * MainActivity (the user's own outgoing text). Incoming SMS/MMS receivers never call it, and [BotLogic.instruction] refuses
 * anything not from_self, so an incoming "@bot ..." from someone else can never become a command.
 * The message itself is still sent normally as SMS/MMS; the command is an extra bot_command event to the paired PC.
 */
object BotLogic {
    private val RX = Regex("^@bot(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)
    /** Returns the instruction text (the whole trimmed message) if this is a command, else null. */
    fun instruction(text: String, fromSelf: Boolean, fromComposer: Boolean, enabled: Boolean): String? {
        if (!fromSelf || !fromComposer || !enabled) return null
        val t = text.trim()
        return if (RX.containsMatchIn(t)) t else null
    }
    /** Find the queue entry that belongs to an outgoing bubble (same thread, same body, sent within 3 min). */
    fun <E> matchFor(entries: List<E>, threadId: Long, body: String, date: Long, outgoing: Boolean,
                     tid: (E) -> Long, b: (E) -> String, at: (E) -> Long): E? {
        if (!outgoing) return null
        return entries.filter { tid(it) == threadId && b(it) == body.trim() && kotlin.math.abs(at(it) - date) < 180_000 }
            .minByOrNull { kotlin.math.abs(at(it) - date) }
    }
    fun tag(status: String): String? = when (status) {
        "queued" -> "[queued - PC offline]"
        "sent", "delivered" -> "[sent to Prime]"
        "cancelled" -> "[@bot cancelled]"
        else -> null
    }
}

data class BotEntry(val id: String, val threadId: Long, val body: String, val created: Long, var status: String, val payload: JSONObject) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("thread_id", threadId).put("body", body).put("created", created).put("status", status).put("payload", payload)
    companion object { fun from(o: JSONObject) = BotEntry(o.getString("id"), o.getLong("thread_id"), o.getString("body"), o.getLong("created"), o.getString("status"), o.getJSONObject("payload")) }
}

object BotCommands {
    val entries = MutableStateFlow<List<BotEntry>>(emptyList())
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("bot_cmds", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, v: Boolean) = prefs(c).edit().putBoolean("enabled", v).apply()

    @Synchronized fun load(c: Context): List<BotEntry> {
        val a = JSONArray(prefs(c).getString("q", "[]"))
        return (0 until a.length()).map { BotEntry.from(a.getJSONObject(it)) }.also { entries.value = it }
    }
    @Synchronized private fun save(c: Context, l: List<BotEntry>) {
        val keep = l.sortedBy { it.created }.takeLast(100)
        prefs(c).edit().putString("q", JSONArray(keep.map { it.toJson() }).toString()).apply(); entries.value = keep
    }
    @Synchronized fun setStatus(c: Context, id: String, from: Set<String>, to: String): Boolean {
        val l = load(c); val e = l.firstOrNull { it.id == id && it.status in from } ?: return false
        e.status = to; save(c, l); return true
    }

    /** Called ONLY from the composer send path (the user typed it). Runs on IO, after the SMS/MMS send succeeded. */
    fun onComposerSend(c: Context, threadId: Long, text: String) {
        val instr = BotLogic.instruction(text, fromSelf = true, fromComposer = true, enabled = enabled(c)) ?: return
        val repo = SmsRepository(c)
        val conv = repo.conversation(threadId)
        val addrs = conv?.addresses.orEmpty()
        val names = repo.contactNames(addrs.toSet())
        val page = runCatching { repo.messagesPage(threadId, Long.MAX_VALUE, 10) }.getOrNull()
        val msgNames = page?.let { p -> repo.contactNames(p.messages.map { it.address }.filter { it.isNotBlank() }.toSet()) }.orEmpty()
        val now = System.currentTimeMillis()
        val payload = JSONObject().put("thread_id", threadId).put("from_self", true).put("instruction", instr).put("ts", now)
            .put("participants", JSONArray(addrs.map { JSONObject().put("number", it).put("name", names[it] ?: it) }))
            .put("context", JSONArray(page?.messages.orEmpty().sortedBy { it.date }.map { m ->
                JSONObject().put("sender", if (m.outgoing) "Me" else (msgNames[m.address] ?: m.address))
                    .put("text", m.body).put("time", m.date).put("outgoing", m.outgoing) }))
        val id = java.util.UUID.randomUUID().toString()
        payload.put("cmd_id", id)
        save(c, load(c) + BotEntry(id, threadId, text.trim(), now, "queued", payload))
        flush(c)
    }

    /** Push queued commands to every connected PC session. PC acks with the bot_ack RPC -> "delivered". */
    fun flush(c: Context) {
        if (LinkEvents.listenerCount() == 0) return   // gate on live sessions, not the per-PC id set
        for (e in load(c).filter { it.status == "queued" }) {
            if (LinkEvents.emitCounted("bot_command", e.payload) > 0) setStatus(c, e.id, setOf("queued"), "sent")
        }
    }
    fun cancel(c: Context, id: String) = setStatus(c, id, setOf("queued"), "cancelled")
    fun ack(c: Context, id: String) = setStatus(c, id, setOf("queued", "sent"), "delivered")
}
