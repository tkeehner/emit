package com.example.emit.prime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.emit.MainActivity
import com.example.emit.link.LinkEvents
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The pinned "Prime" chat: the user <-> his assistant over the PC link (LAN or relay). Never SMS, never contacts,
 * never the carrier. Stored only in app-private prefs.
 *  - outgoing: queued -> sent (pushed as a `prime_chat` event) -> delivered (PC acked with prime_ack after the webhook took it)
 *  - incoming: arrives via the prime_reply RPC (deduped by reply_id)
 */
data class PrimeMsg(val id: String, val text: String, val ts: Long, val outgoing: Boolean, var status: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("text", text).put("ts", ts).put("out", outgoing).put("st", status)
    companion object { fun from(o: JSONObject) = PrimeMsg(o.getString("id"), o.getString("text"), o.getLong("ts"), o.getBoolean("out"), o.optString("st")) }
}

object PrimeChat {
    const val NOTIF_ID = 777_001
    private const val CHANNEL = "prime_chat"
    val messages = MutableStateFlow<List<PrimeMsg>>(emptyList())
    val unread = MutableStateFlow(false)
    @Volatile var foreground = false
    @Volatile var screenOpen = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("prime_chat", Context.MODE_PRIVATE)

    @Synchronized fun load(c: Context): List<PrimeMsg> {
        val p = prefs(c); val a = JSONArray(p.getString("msgs", "[]"))
        unread.value = p.getBoolean("unread", false)
        return (0 until a.length()).map { PrimeMsg.from(a.getJSONObject(it)) }.also { messages.value = it }
    }
    @Synchronized private fun save(c: Context, l: List<PrimeMsg>) {
        val keep = l.sortedBy { it.ts }.takeLast(500)
        prefs(c).edit().putString("msgs", JSONArray(keep.map { it.toJson() }).toString()).apply(); messages.value = keep
    }
    fun markRead(c: Context) { prefs(c).edit().putBoolean("unread", false).apply(); unread.value = false
        runCatching { NotificationManagerCompat.from(c).cancel(NOTIF_ID) } }

    /** True while the newest message is the user's and Prime hasn't answered yet (and it isn't stuck/failed for long). */
    fun thinking(l: List<PrimeMsg>, now: Long = System.currentTimeMillis()): Boolean {
        val last = l.lastOrNull() ?: return false
        return last.outgoing && now - last.ts < 30 * 60_000L
    }

    fun send(c: Context, text: String) {
        val t = text.trim().take(4000); if (t.isEmpty()) return
        val m = PrimeMsg(java.util.UUID.randomUUID().toString(), t, System.currentTimeMillis(), true, "queued")
        save(c, load(c) + m); flush(c)
    }

    private fun payload(m: PrimeMsg) = JSONObject().put("kind", "prime_chat").put("msg_id", m.id).put("text", m.text).put("ts", m.ts).put("from_self", true)

    /** Push queued messages to every connected PC session (same gate as @bot). */
    fun flush(c: Context) {
        if (LinkEvents.listenerCount() == 0) return
        val l = load(c); var changed = false
        for (m in l.filter { it.outgoing && it.status == "queued" }) {
            if (LinkEvents.emitCounted("prime_chat", payload(m)) > 0) { m.status = "sent"; changed = true }
        }
        if (changed) save(c, l)
    }

    /** Sent but never acked (e.g. the session dropped): re-queue so the next flush retries; PC dedupes by msg_id. */
    fun requeueUnacked(c: Context, olderThanMs: Long = 60_000) {
        val now = System.currentTimeMillis(); val l = load(c); var changed = false
        l.filter { it.outgoing && it.status == "sent" && now - it.ts > olderThanMs }.forEach { it.status = "queued"; changed = true }
        if (changed) save(c, l)
    }

    @Synchronized fun ack(c: Context, id: String): Boolean {
        val l = load(c); val m = l.firstOrNull { it.id == id && it.outgoing } ?: return false
        if (m.status != "delivered") { m.status = "delivered"; save(c, l) }
        return true
    }

    /** From the PC (prime_reply RPC). Returns false if this reply_id was already stored. */
    @Synchronized fun receive(c: Context, replyId: String, text: String): Boolean {
        val t = text.trim().take(8000)
        if (replyId.isBlank() || t.isEmpty()) throw IllegalArgumentException("reply_id and text required")
        val l = load(c); if (l.any { it.id == replyId }) return false
        save(c, l + PrimeMsg(replyId, t, System.currentTimeMillis(), false, "received"))
        val visible = foreground && screenOpen
        if (!visible) { prefs(c).edit().putBoolean("unread", true).apply(); unread.value = true; notify(c, t) }
        com.example.emit.sms.IncomingBus.ping()
        return true
    }

    private fun notify(c: Context, text: String) {
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Prime chat", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(c, NOTIF_ID, Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_PRIME, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle("> Prime").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(0xFF00FF41.toInt()).setAutoCancel(true).setContentIntent(open).setCategory(NotificationCompat.CATEGORY_MESSAGE).build()
        runCatching { NotificationManagerCompat.from(c).notify(NOTIF_ID, n) }
    }
}
