package com.example.emit.link

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.emit.MainActivity
import com.example.emit.data.SmsRepository
import com.example.emit.data.ThreadMapping
import com.example.emit.drafts.PrimeDraft
import com.example.emit.drafts.PrimeDraftStore
import com.example.emit.sms.IncomingBus
import com.example.emit.sms.SmsSender
import org.json.JSONArray
import org.json.JSONObject

/** In-process event bus to connected PCs. */
object LinkEvents {
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<(JSONObject) -> Unit>()
    fun add(l: (JSONObject) -> Unit) = listeners.add(l)
    fun remove(l: (JSONObject) -> Unit) = listeners.remove(l)
    fun listenerCount(): Int = listeners.size
    fun emit(ev: String, d: JSONObject) { val m = JSONObject().put("ev", ev).put("d", d); listeners.forEach { runCatching { it(m) } } }
    /** Like emit, but counts listeners whose send did not throw (used for bot_command delivery). */
    fun emitCounted(ev: String, d: JSONObject): Int { val m = JSONObject().put("ev", ev).put("d", d); return listeners.count { runCatching { it(m) }.isSuccess } }
}

fun PrimeDraft.toJson(): JSONObject = JSONObject().put("draft_id", id).put("thread_id", threadId)
    .put("to", JSONArray(address.split(',').filter { it.isNotBlank() })).put("body", body).put("created", created)
    .put("status", if (status == "approved") "sent" else status)

/**
 * The only path by which a PC-originated draft is sent. Called from: the notification APPROVE action (phone, requires
 * unlock), or approve_send from the PC UI when the user enabled "PC may approve". Re-checks the thread's recipients.
 */
object DraftApproval {
    private const val CHANNEL = "link_approvals"
    private const val TAG = "MatrixSMS"
    const val ACTION_APPROVE = "com.example.emit.link.APPROVE"
    const val ACTION_DENY = "com.example.emit.link.DENY"

    fun notifId(id: Long) = 900_000 + (id % 90_000).toInt()

    fun requestApproval(c: Context, d: PrimeDraft, who: String) {
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Send approvals (PC / Prime)", NotificationManager.IMPORTANCE_HIGH))
        fun pi(action: String) = PendingIntent.getBroadcast(c, (d.id * 2 + if (action == ACTION_APPROVE) 1 else 0).toInt(),
            Intent(c, ApprovalReceiver::class.java).setAction(action).putExtra("draft_id", d.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(c, notifId(d.id), Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_THREAD, d.threadId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("> approve send to $who?").setContentText(d.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(d.body)).setColor(0xFFFFB000.toInt())
            .setContentIntent(open).setAutoCancel(false).setOngoing(false).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .addAction(NotificationCompat.Action.Builder(0, "APPROVE + SEND", pi(ACTION_APPROVE)).setAuthenticationRequired(true).build())
            .addAction(NotificationCompat.Action.Builder(0, "DENY", pi(ACTION_DENY)).build())
            .build()
        runCatching { NotificationManagerCompat.from(c).notify(notifId(d.id), n) }
    }

    /** Returns the updated draft or throws with a reason. */
    fun approve(c: Context, id: Long, via: String): PrimeDraft {
        val store = PrimeDraftStore(c)
        val d = store.get(id) ?: throw IllegalArgumentException("draft $id not found")
        if (!store.transition(id, setOf("pending", "awaiting"), "sending")) throw IllegalStateException("draft is ${d.status}")
        val conv = SmsRepository(c).conversation(d.threadId)
        val r = when {
            conv == null -> SmsSender.Result.Refused("thread not found")
            conv.isGroup -> {
                val shown = d.address.split(',').filter { it.isNotBlank() }
                if (!ThreadMapping.sameSet(conv.addresses, shown)) SmsSender.Result.Refused("group members changed")
                else SmsSender.sendGroupToThread(c, d.threadId, shown, d.body, emptyList())
            }
            else -> SmsSender.sendToThread(c, d.threadId, d.address, d.body)
        }
        val ok = r == SmsSender.Result.Sent
        store.setStatus(id, if (ok) "approved" else "failed")
        Log.i(TAG, "link: draft $id approved via $via -> ${if (ok) "sent" else (r as SmsSender.Result.Refused).reason}")
        NotificationManagerCompat.from(c).cancel(notifId(id))
        val out = store.get(id)!!
        LinkEvents.emit("draft", out.toJson().apply { if (!ok) put("error", (r as SmsSender.Result.Refused).reason) })
        IncomingBus.ping()
        if (!ok) throw IllegalStateException("not sent: ${(r as SmsSender.Result.Refused).reason}")
        return out
    }

    fun deny(c: Context, id: Long) {
        val store = PrimeDraftStore(c)
        store.transition(id, setOf("pending", "awaiting"), "rejected")
        NotificationManagerCompat.from(c).cancel(notifId(id))
        store.get(id)?.let { LinkEvents.emit("draft", it.toJson()) }
        IncomingBus.ping()
    }
}

class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("draft_id", -1); if (id < 0) return
        val pr = goAsync()
        Thread {
            try {
                when (intent.action) {
                    DraftApproval.ACTION_APPROVE -> runCatching { DraftApproval.approve(context, id, "phone-notification") }
                        .onFailure { Log.w("MatrixSMS", "link: approve failed", it) }
                    DraftApproval.ACTION_DENY -> DraftApproval.deny(context, id)
                }
            } finally { pr.finish() }
        }.start()
    }
}
