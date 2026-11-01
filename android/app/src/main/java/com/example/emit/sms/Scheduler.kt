package com.example.emit.sms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/** One message the user scheduled himself. Sent once, only to [address] in [threadId], then removed. */
data class Scheduled(val id: Long, val threadId: Long, val address: String, val body: String, val at: Long) {
    fun json() = JSONObject().put("id", id).put("t", threadId).put("a", address).put("b", body).put("at", at)
    companion object { fun of(o: JSONObject) = Scheduled(o.getLong("id"), o.getLong("t"), o.getString("a"), o.getString("b"), o.getLong("at")) }
}

object Scheduler {
    val items = kotlinx.coroutines.flow.MutableStateFlow<List<Scheduled>>(emptyList())
    private fun p(c: Context) = c.getSharedPreferences("scheduled_send", 0)
    fun load(c: Context): List<Scheduled> {
        val a = runCatching { JSONArray(p(c).getString("list", "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).map { Scheduled.of(a.getJSONObject(it)) }.sortedBy { it.at }.also { items.value = it }
    }
    private fun save(c: Context, l: List<Scheduled>) { p(c).edit().putString("list", JSONArray(l.map { it.json() }).toString()).apply(); load(c) }

    /** Pure: validates a new schedule request. */
    fun validate(body: String, address: String, at: Long, now: Long): String? = when {
        body.isBlank() -> "empty message"
        address.isBlank() -> "scheduling is only for one-person chats"
        at <= now + 30_000 -> "pick a time at least a minute from now"
        at > now + 366L * 24 * 3600 * 1000 -> "pick a time within a year"
        else -> null
    }

    fun add(c: Context, threadId: Long, address: String, body: String, at: Long): String? {
        validate(body, address, at, System.currentTimeMillis())?.let { return it }
        val s = Scheduled(System.currentTimeMillis(), threadId, address, body, at)
        save(c, load(c) + s); arm(c, s); return null
    }
    fun cancel(c: Context, id: Long) { c.getSystemService(AlarmManager::class.java).cancel(pi(c, id)); save(c, load(c).filterNot { it.id == id }) }
    fun exactAllowed(c: Context) = c.getSystemService(AlarmManager::class.java).let { android.os.Build.VERSION.SDK_INT < 31 || it.canScheduleExactAlarms() }

    private fun pi(c: Context, id: Long) = PendingIntent.getBroadcast(c, (id % Int.MAX_VALUE).toInt(),
        Intent(c, ScheduledSendReceiver::class.java).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun arm(c: Context, s: Scheduled) {
        val am = c.getSystemService(AlarmManager::class.java)
        if (exactAllowed(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.at, pi(c, s.id))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.at, pi(c, s.id))
    }
    fun rearmAll(c: Context) = load(c).forEach { arm(c, it) }

    /** Fires for exactly one id: sends that message (same recipient check as the composer) and removes it. */
    fun fire(c: Context, id: Long) {
        val s = load(c).firstOrNull { it.id == id } ?: return
        save(c, load(c).filterNot { it.id == id })
        val res = SmsSender.sendToThread(c, s.threadId, s.address, s.body)
        if (res is SmsSender.Result.Refused) Notifier.showReplied(c, s.threadId, "scheduled message NOT sent: ${res.reason}")
        IncomingBus.ping()
    }
}

class ScheduledSendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1); if (id <= 0) return
        val pr = goAsync(); Thread { try { Scheduler.fire(context, id) } finally { pr.finish() } }.start()
    }
}

class SchedulerBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { Scheduler.rearmAll(context) }
}
