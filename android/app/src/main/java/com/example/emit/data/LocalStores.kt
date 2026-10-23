package com.example.emit.data

import android.content.Context

/** Unsent composer text per thread (on-device SharedPreferences only). */
class DraftMemory(c: Context) {
    private val p = c.getSharedPreferences("draft_memory", 0)
    fun get(threadId: Long): String = p.getString(threadId.toString(), "") ?: ""
    fun set(threadId: Long, text: String) { p.edit().apply { if (text.isBlank()) remove(threadId.toString()) else putString(threadId.toString(), text.take(5000)) }.apply() }
}

/** When the carrier delivery report arrived, per SMS row (the provider has no column for it). */
class DeliveryTimes(c: Context) {
    private val p = c.getSharedPreferences("delivery_times", 0)
    fun put(smsId: Long, at: Long) = p.edit().putLong(smsId.toString(), at).apply()
    fun get(smsId: Long): Long? = p.getLong(smsId.toString(), 0L).takeIf { it > 0 }
}

/** Pure helpers (unit-tested). */
object MessageMeta {
    /** Composer line for swipe/menu reply: ">> who: first line (max 60 chars)". */
    fun quote(who: String, body: String): String {
        val first = messagePart(body).lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.removePrefix(">> ").orEmpty().ifBlank { "[media]" }
        return ">> $who: " + (if (first.length > 60) first.take(59) + "…" else first) + "\n"
    }
    data class Quoted(val who: String, val quote: String, val body: String)
    private val QUOTE = Regex("^>> ([^:\\n]{1,40}): ([^\\n]*)\\n")
    /** Splits a reply body (">> who: quoted\n message") into its parts; null when it isn't a reply. Only matches at the very start and needs the newline separator. */
    fun parseQuote(body: String): Quoted? {
        val m = QUOTE.find(body) ?: return null
        return Quoted(m.groupValues[1].trim(), m.groupValues[2].trim(), body.substring(m.range.last + 1))
    }
    fun messagePart(body: String) = parseQuote(body)?.body ?: body
    fun withQuote(quote: String, text: String) = if (text.startsWith(">> ")) quote + text.substringAfter('\n', "") else quote + text

    /** Info sheet rows: label to value. */
    fun info(m: Message, sentAt: Long?, deliveredAt: Long?, fmt: (Long) -> String): List<Pair<String, String>> = buildList {
        add("type" to if (m.isMms) "MMS" + (if (m.media.isNotEmpty()) " (${m.media.size} attachment${if (m.media.size > 1) "s" else ""})" else "") else "SMS")
        add("direction" to if (m.outgoing) "outgoing" else "incoming from ${m.address.ifBlank { "?" }}")
        if (m.outgoing) {
            add("sent" to fmt(sentAt ?: m.date))
            add("status" to m.delivery.name.lowercase())
            add("delivered" to (deliveredAt?.let(fmt) ?: if (m.delivery == com.example.emit.ui.ChatLogic.Delivery.DELIVERED) "yes (time unknown)" else "-"))
        } else {
            sentAt?.let { add("sent" to fmt(it)) }
            add("received" to fmt(m.date))
        }
        add("chars" to "${m.body.length}" + if (!m.isMms) " · " + com.example.emit.ui.ChatLogic.segments(m.body).let { "${it.segments} segment(s)" } else "")
    }
}
