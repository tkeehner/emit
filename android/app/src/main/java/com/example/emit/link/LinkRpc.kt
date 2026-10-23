package com.example.emit.link

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import com.example.emit.BuildConfig
import com.example.emit.data.SmsRepository
import com.example.emit.drafts.PrimeDraftStore
import org.json.JSONArray
import org.json.JSONObject

class RpcError(val code: String, msg: String) : Exception(msg)

/** Methods a paired PC may call. There is NO direct send: request_send only asks the user; approve_send is PC-UI-only and gated by a phone toggle. */
class LinkRpc(private val c: Context, private val store: LinkStore) {
    private val repo = SmsRepository(c)
    private val drafts = PrimeDraftStore(c)

    fun call(m: String, p: JSONObject, viaRelay: Boolean = false): Any = when (m) {
        "relay_config" -> {
            // Only over the home LAN link, so a session that already depends on the relay can't re-point it.
            if (viaRelay) throw RpcError("forbidden", "relay settings can only be changed over home Wi-Fi")
            val host = p.optString("host").trim(); val fp = RelayWire.normFingerprint(p.optString("fingerprint")); val tok = p.optString("token").trim()
            val port = p.optInt("port", 443)
            if (host.isEmpty() || host.length > 253 || !RelayWire.validFingerprint(fp) || tok.length !in 16..200 || tok.any { it.isWhitespace() } || port !in 1..65535)
                throw RpcError("bad_request", "bad relay config")
            store.relayHost = host; store.relayPort = port; store.relayFingerprint = fp; store.relayToken = tok
            store.relayEnabled = p.optBoolean("enabled", true)
            RelayConnector.kick()
            JSONObject().put("ok", true).put("enabled", store.relayEnabled).put("host", host)
        }
        "update_begin" -> JSONObject().put("offset", AppUpdater.receiver(c).begin(p.getString("sha256"), p.getLong("size")))
        "update_chunk" -> JSONObject().put("offset", AppUpdater.receiver(c).chunk(p.getString("sha256"), p.getLong("offset"), unb64(p.getString("data_b64"))))
        "update_finish" -> {
            val r = AppUpdater.receiver(c).finish(p.getString("sha256")); AppUpdater.onReady(c, r)
            JSONObject().put("ready", true).put("version_name", r.info.versionName).put("version_code", r.info.versionCode)
                .put("note", "waiting for the [ install ] tap on the phone")
        }
        "status" -> JSONObject().put("phone_id", store.phoneId).put("name", android.os.Build.MODEL).put("app_version", BuildConfig.VERSION_NAME)
            .put("approvals_from_pc", store.allowPcApproval).put("drafts_awaiting", drafts.pending().count { it.status == "awaiting" })
        "list_threads" -> {
            val all = repo.conversations(); val off = p.optInt("offset", 0).coerceAtLeast(0); val lim = p.optInt("limit", 30).coerceIn(1, 200)
            JSONObject().put("total", all.size).put("threads", JSONArray(all.drop(off).take(lim).map { t ->
                JSONObject().put("thread_id", t.threadId).put("name", t.displayName).put("addresses", JSONArray(t.addresses))
                    .put("names", JSONArray(t.names)).put("snippet", t.snippet).put("date", t.date).put("unread", t.unread)
                    .put("count", t.messageCount).put("group", t.isGroup) }))
        }
        "read_thread" -> {
            val tid = p.getLong("thread_id")
            val conv = repo.conversation(tid) ?: throw RpcError("not_found", "thread $tid")
            val before = if (p.has("before_ms")) p.getLong("before_ms") else Long.MAX_VALUE
            val page = repo.messagesPage(tid, before, p.optInt("limit", 30).coerceIn(1, 200))
            val names = repo.contactNames(page.messages.map { it.address }.filter { it.isNotBlank() }.toSet())
            JSONObject().put("thread_id", tid).put("name", conv.displayName).put("has_more", page.hasMore).put("oldest_date", page.oldestDate)
                .put("messages", JSONArray(page.messages.map { msg ->
                    JSONObject().put("key", msg.key).put("id", msg.id).put("mms", msg.isMms).put("from", if (msg.outgoing) "" else msg.address)
                        .put("from_name", if (msg.outgoing) "me" else (names[msg.address] ?: msg.address)).put("body", msg.body)
                        .put("date", msg.date).put("outgoing", msg.outgoing).put("pending", msg.pending)
                        .put("media", JSONArray(msg.media.map { JSONObject().put("part_id", it.partId).put("mime", it.mime) })) }))
        }
        "search" -> search(p.optString("query").trim().ifEmpty { throw RpcError("bad_request", "query required") }, p.optInt("limit", 30).coerceIn(1, 200))
        "contacts" -> JSONObject().put("contacts", JSONArray(repo.searchContacts(p.optString("query")).map { JSONObject().put("name", it.first).put("number", it.second) }))
        "get_media" -> {
            val id = p.getLong("part_id")
            val cr = c.contentResolver; val uri = Uri.parse("content://mms/part/$id")
            val mime = cr.query(uri, arrayOf("ct"), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: throw RpcError("not_found", "part $id")
            val bytes = cr.openInputStream(uri)?.use { s -> s.readBytes().also { if (it.size > 20_000_000) throw RpcError("too_large", "media > 20 MB") } }
                ?: throw RpcError("not_found", "part $id has no data")
            JSONObject().put("part_id", id).put("mime", mime).put("size", bytes.size).put("data_b64", b64(bytes))
        }
        "create_draft" -> {
            val tid = p.getLong("thread_id"); val body = p.optString("body").trim().take(2000)
            if (body.isEmpty()) throw RpcError("bad_request", "body required")
            val conv = repo.conversation(tid) ?: throw RpcError("not_found", "thread $tid")
            if (conv.addresses.isEmpty()) throw RpcError("bad_request", "thread has no recipients")
            val d = drafts.create(tid, conv.addresses.joinToString(","), body)
            com.example.emit.sms.IncomingBus.ping(); LinkEvents.emit("draft", d.toJson())
            JSONObject().put("draft", d.toJson())
        }
        "list_drafts" -> {
            val st = p.optString("status", "all"); val tid = if (p.has("thread_id")) p.getLong("thread_id") else null
            JSONObject().put("drafts", JSONArray(drafts.recent(200).filter { (tid == null || it.threadId == tid) &&
                (st == "all" || st.isEmpty() || it.toJson().getString("status") == st) }.map { it.toJson() }))
        }
        "request_send" -> {
            val id = p.getLong("draft_id")
            if (!drafts.transition(id, setOf("pending"), "awaiting")) throw RpcError("bad_request", "draft is ${drafts.get(id)?.status ?: "missing"}")
            val d = drafts.get(id)!!
            val who = repo.conversation(d.threadId)?.let { conv -> repo.contactNames(conv.addresses.toSet()).values.joinToString(", ") } ?: d.address
            DraftApproval.requestApproval(c, d, who)
            com.example.emit.sms.IncomingBus.ping(); LinkEvents.emit("draft", d.toJson())
            JSONObject().put("draft", d.toJson())
        }
        "cancel_draft" -> {
            val id = p.getLong("draft_id")
            drafts.transition(id, setOf("pending", "awaiting"), "cancelled")
            androidx.core.app.NotificationManagerCompat.from(c).cancel(DraftApproval.notifId(id))
            com.example.emit.sms.IncomingBus.ping()
            JSONObject().put("draft", (drafts.get(id) ?: throw RpcError("not_found", "draft $id")).toJson())
        }
        "approve_send" -> {
            if (!store.allowPcApproval) throw RpcError("forbidden", "PC approvals are turned off on the phone")
            val id = p.getLong("draft_id")
            if (drafts.get(id)?.status != "awaiting") throw RpcError("bad_request", "request_send first")
            try { JSONObject().put("draft", DraftApproval.approve(c, id, "pc-ui").toJson()) }
            catch (e: IllegalStateException) { throw RpcError("send_failed", e.message ?: "failed") }
        }
        "prime_reply" -> {
            val ok = runCatching { com.example.emit.prime.PrimeChat.receive(c, p.optString("reply_id"), p.optString("text")) }
                .getOrElse { throw RpcError("bad_request", it.message ?: "bad reply") }
            JSONObject().put("ok", true).put("new", ok)
        }
        "prime_ack" -> JSONObject().put("ok", com.example.emit.prime.PrimeChat.ack(c, p.getString("msg_id")))
        "bot_ack" -> JSONObject().put("ok", com.example.emit.bot.BotCommands.ack(c, p.getString("cmd_id")))
        "bot_pending" -> { com.example.emit.bot.BotCommands.flush(c)
            runCatching { com.example.emit.prime.PrimeChat.requeueUnacked(c); com.example.emit.prime.PrimeChat.flush(c) }
            JSONObject().put("queued", com.example.emit.bot.BotCommands.load(c).count { it.status == "queued" }) }
        else -> throw RpcError("bad_request", "unknown method $m")
    }

    private fun search(q: String, limit: Int): JSONObject {
        val out = JSONArray(); val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        c.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id", "thread_id", "address", "body", "date", "type"),
            "body LIKE ? ESCAPE '\\'", arrayOf(like), "date DESC LIMIT $limit")?.use { cur ->
            while (cur.moveToNext()) out.put(JSONObject().put("key", "sms:" + cur.getLong(0)).put("thread_id", cur.getLong(1))
                .put("from", if (cur.getInt(5) == Telephony.Sms.MESSAGE_TYPE_INBOX) cur.getString(2) ?: "" else "")
                .put("body", cur.getString(3) ?: "").put("date", cur.getLong(4)).put("outgoing", cur.getInt(5) != Telephony.Sms.MESSAGE_TYPE_INBOX))
        }
        runCatching {
            c.contentResolver.query(Uri.parse("content://mms/part"), arrayOf("mid", "text"), "ct='text/plain' AND text LIKE ? ESCAPE '\\'", arrayOf(like), "_id DESC")?.use { cur ->
                var n = 0
                while (cur.moveToNext() && n++ < limit) {
                    val mid = cur.getLong(0)
                    c.contentResolver.query(Telephony.Mms.CONTENT_URI, arrayOf("thread_id", "date", "msg_box"), "_id=?", arrayOf(mid.toString()), null)?.use { m ->
                        if (m.moveToFirst()) out.put(JSONObject().put("key", "mms:$mid").put("thread_id", m.getLong(0)).put("from", "")
                            .put("body", cur.getString(1) ?: "").put("date", m.getLong(1) * 1000).put("outgoing", m.getInt(2) != Telephony.Mms.MESSAGE_BOX_INBOX))
                    }
                }
            }
        }
        val sorted = (0 until out.length()).map { out.getJSONObject(it) }.sortedByDescending { it.getLong("date") }.take(limit)
        return JSONObject().put("results", JSONArray(sorted))
    }
}
