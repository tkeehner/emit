package com.example.emit.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log

/**
 * Reads/writes the system Telephony provider. Threads are identified ONLY by Telephony thread _id;
 * addresses come from recipient_ids -> canonical-addresses. Write access requires default SMS role.
 */
class SmsRepository(private val context: Context) {
    private val cr get() = context.contentResolver

    private fun canonicalAddresses(): Map<Long, String> {
        val map = HashMap<Long, String>()
        runCatching {
            cr.query(Uri.parse("content://mms-sms/canonical-addresses"), arrayOf("_id", "address"), null, null, null)?.use { c ->
                while (c.moveToNext()) map[c.getLong(0)] = c.getString(1) ?: ""
            }
        }.onFailure { Log.w(TAG, "canonical-addresses failed", it) }
        return map
    }

    fun conversations(): List<Conversation> {
        val canon = canonicalAddresses()
        val out = ArrayList<Conversation>()
        runCatching {
            cr.query(Uri.parse("content://mms-sms/conversations?simple=true"),
                arrayOf("_id", "date", "snippet", "read", "recipient_ids", "message_count"), null, null, "date DESC")?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(5) == 0) continue
                    val tid = c.getLong(0)
                    val addrs = ThreadMapping.recipients(c.getString(4), canon)
                    out += Conversation(tid, addrs, emptyList(), c.getString(2) ?: "", c.getLong(1), c.getInt(3) == 0, c.getInt(5))
                }
            }
        }.onFailure { Log.w(TAG, "threads query failed", it) }
        val names = contactNames(out.flatMap { it.addresses }.toSet())
        return out.map { c -> c.copy(names = c.addresses.map { names[it] ?: it }) }
    }

    /** Fresh read of one thread (used by the send safety check). Falls back to a full scan if selection is unsupported. */
    fun conversation(threadId: Long): Conversation? {
        val canon = canonicalAddresses()
        runCatching {
            cr.query(Uri.parse("content://mms-sms/conversations?simple=true"), arrayOf("_id", "recipient_ids"),
                "_id=?", arrayOf(threadId.toString()), null)?.use { c ->
                while (c.moveToNext()) if (c.getLong(0) == threadId) {
                    val addrs = ThreadMapping.recipients(c.getString(1), canon)
                    return Conversation(threadId, addrs, addrs.map(::contactName), "", 0, false)
                }
            }
        }
        return conversations().firstOrNull { it.threadId == threadId }
    }

    data class Page(val messages: List<Message>, val hasMore: Boolean, val oldestDate: Long)

    /**
     * Newest [limit] messages of exactly one thread_id older than [beforeMs] (Long.MAX_VALUE = newest page).
     * SMS and MMS are each limited, merged, and trimmed so pages never leave gaps. MMS text parts are
     * fetched in one batched IN(...) query. Each source has its own error handling.
     */
    fun messagesPage(threadId: Long, beforeMs: Long = Long.MAX_VALUE, limit: Int = PAGE): Page {
        val all = ArrayList<Message>()
        var smsFull = false; var mmsFull = false
        runCatching {
            cr.query(Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.THREAD_ID, Telephony.Sms.STATUS),
                "${Telephony.Sms.THREAD_ID}=? AND ${Telephony.Sms.DATE}<?", arrayOf(threadId.toString(), beforeMs.toString()),
                "${Telephony.Sms.DATE} DESC LIMIT $limit")?.use { c ->
                var n = 0
                while (c.moveToNext()) {
                    n++
                    if (c.getLong(5) != threadId) continue // defensive
                    all += Message(c.getLong(0), threadId, c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3),
                        c.getInt(4) != Telephony.Sms.MESSAGE_TYPE_INBOX,
                        pending = c.getInt(4) == Telephony.Sms.MESSAGE_TYPE_OUTBOX || c.getInt(4) == Telephony.Sms.MESSAGE_TYPE_QUEUED,
                        delivery = com.example.emit.ui.ChatLogic.smsDelivery(c.getInt(4), if (c.isNull(6)) -1 else c.getInt(6)))
                }
                smsFull = n >= limit
            }
        }.onFailure { Log.w(TAG, "sms query failed", it) }
        val mms = ArrayList<Triple<Long, Long, Int>>()
        val mmsLoc = HashMap<Long, String>()
        runCatching {
            val beforeSec = if (beforeMs == Long.MAX_VALUE) Long.MAX_VALUE else beforeMs / 1000 + 1
            cr.query(Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.THREAD_ID,
                    Telephony.Mms.MESSAGE_TYPE, Telephony.Mms.CONTENT_LOCATION),
                "${Telephony.Mms.THREAD_ID}=? AND ${Telephony.Mms.DATE}<?", arrayOf(threadId.toString(), beforeSec.toString()),
                "${Telephony.Mms.DATE} DESC LIMIT $limit")?.use { c ->
                var n = 0
                while (c.moveToNext()) {
                    n++
                    if (c.getLong(3) != threadId) continue
                    val ms = c.getLong(1) * 1000
                    if (ms < beforeMs) {
                        mms += Triple(c.getLong(0), ms, c.getInt(2))
                        // m_type 130 = notification only (never downloaded): keep its location for tap-to-retry.
                        if (c.getInt(4) == 130 || c.getInt(4) == 132) c.getString(5)?.takeIf { it.isNotBlank() }?.let { mmsLoc[c.getLong(0)] = it }
                    }
                }
                mmsFull = n >= limit
            }
        }.onFailure { Log.w(TAG, "mms query failed", it) }
        val (texts, media) = mmsParts(mms.map { it.first })
        mms.forEach { (id, ms, box) ->
            val m = media[id].orEmpty()
            val sender = if (box == Telephony.Mms.MESSAGE_BOX_INBOX) mmsFrom(id) else ""
            val empty = texts[id] == null && m.isEmpty()
            val retry = if (empty && box == Telephony.Mms.MESSAGE_BOX_INBOX) mmsLoc[id] else null
            val body = texts[id] ?: when { !empty -> ""; retry != null -> "[MMS not downloaded - tap to retry]"; else -> "[MMS - content missing]" }
            if (empty && box == Telephony.Mms.MESSAGE_BOX_INBOX) Log.i(TAG, "mms parse row=$id empty sender=${sender.ifBlank { "?" }} retry=${retry != null}")
            all += Message(id, threadId, sender, body, ms,
                box != Telephony.Mms.MESSAGE_BOX_INBOX, isMms = true, media = m, pending = box == Telephony.Mms.MESSAGE_BOX_OUTBOX,
                retryLocation = retry, delivery = when (box) {
                    Telephony.Mms.MESSAGE_BOX_INBOX -> com.example.emit.ui.ChatLogic.Delivery.NONE
                    Telephony.Mms.MESSAGE_BOX_OUTBOX -> com.example.emit.ui.ChatLogic.Delivery.PENDING
                    Telephony.Mms.MESSAGE_BOX_FAILED -> com.example.emit.ui.ChatLogic.Delivery.FAILED
                    else -> com.example.emit.ui.ChatLogic.Delivery.SENT })
        }
        val newestFirst = all.sortedByDescending { it.date }.take(limit)
        val hasMore = smsFull || mmsFull || all.size > limit
        return Page(newestFirst.reversed(), hasMore, newestFirst.lastOrNull()?.date ?: 0L)
    }

    /**
     * Deletes every SMS+MMS in exactly this thread_id. Only works for the default SMS app (otherwise the
     * provider silently deletes nothing). Returns true only if the thread is verifiably gone afterwards.
     */
    fun deleteThread(threadId: Long): Boolean {
        require(threadId > 0)
        val n = runCatching {
            cr.delete(Uri.parse("content://mms-sms/conversations/$threadId"), null, null)
        }.onFailure { Log.w(TAG, "thread delete failed", it) }.getOrDefault(0)
        Log.i(TAG, "deleted $n rows from thread $threadId")
        val remaining = runCatching {
            cr.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID), "${Telephony.Sms.THREAD_ID}=?", arrayOf(threadId.toString()), null)
                ?.use { it.count } ?: 0
        }.getOrDefault(0) + runCatching {
            cr.query(Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID), "${Telephony.Mms.THREAD_ID}=?", arrayOf(threadId.toString()), null)
                ?.use { it.count } ?: 0
        }.getOrDefault(0)
        return remaining == 0
    }

    /** Deletes ONE message row (after the user confirmed). Returns true only if the row is verifiably gone. */
    fun deleteMessage(m: Message): Boolean {
        val uri = android.content.ContentUris.withAppendedId(if (m.isMms) Telephony.Mms.CONTENT_URI else Telephony.Sms.CONTENT_URI, m.id)
        runCatching { cr.delete(uri, null, null) }.onFailure { Log.w(TAG, "message delete failed", it) }
        return runCatching { cr.query(uri, arrayOf("_id"), null, null, null)?.use { it.count } ?: 0 }.getOrDefault(0) == 0
    }

    /** date_sent (ms) for one message, if the provider has it. */
    fun dateSent(m: Message): Long? = runCatching {
        val uri = android.content.ContentUris.withAppendedId(if (m.isMms) Telephony.Mms.CONTENT_URI else Telephony.Sms.CONTENT_URI, m.id)
        cr.query(uri, arrayOf(if (m.isMms) Telephony.Mms.DATE_SENT else Telephony.Sms.DATE_SENT), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).let { if (m.isMms) it * 1000 else it }.takeIf { it > 0 } else null }
    }.getOrNull()

    /** Marks every SMS and MMS in exactly this thread_id read+seen. Returns rows changed (default SMS app only). */
    fun markRead(threadId: Long): Int {
        val v = ContentValues().apply { put("read", 1); put("seen", 1) }
        val args = arrayOf(threadId.toString())
        val sms = runCatching { cr.update(Telephony.Sms.CONTENT_URI, v, "thread_id=? AND (read=0 OR seen=0)", args) }
            .onFailure { Log.w(TAG, "sms markRead failed", it) }.getOrDefault(0)
        val mms = runCatching { cr.update(Telephony.Mms.CONTENT_URI, v, "thread_id=? AND (read=0 OR seen=0)", args) }
            .onFailure { Log.w(TAG, "mms markRead failed", it) }.getOrDefault(0)
        return sms + mms
    }

    fun insertInbox(address: String, body: String, date: Long, subId: Int): Uri? = runCatching {
        cr.insert(Telephony.Sms.Inbox.CONTENT_URI, ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address); put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.THREAD_ID, threadIdFor(address))
            put(Telephony.Sms.DATE, System.currentTimeMillis()); put(Telephony.Sms.DATE_SENT, date)
            put(Telephony.Sms.READ, 0); put(Telephony.Sms.SEEN, 0); put(Telephony.Sms.SUBSCRIPTION_ID, subId)
        })
    }.getOrNull()

    /** Records an outgoing message in the given thread's outbox; SentStatusReceiver moves it to sent/failed. */
    fun insertOutgoing(threadId: Long, address: String, body: String): Uri? = runCatching {
        cr.insert(Telephony.Sms.Outbox.CONTENT_URI, ContentValues().apply {
            put(Telephony.Sms.THREAD_ID, threadId)
            put(Telephony.Sms.ADDRESS, address); put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis()); put(Telephony.Sms.READ, 1)
        })
    }.getOrNull()

    fun setStatus(uri: Uri, status: Int) = runCatching {
        cr.update(uri, ContentValues().apply { put(Telephony.Sms.STATUS, status) }, null, null)
    }

    /** (address, body, type) of one SMS row, for tap-to-retry. */
    fun smsRow(id: Long): Triple<String, String, Int>? = runCatching {
        cr.query(android.content.ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.TYPE),
            null, null, null)?.use { c -> if (c.moveToFirst()) Triple(c.getString(0) ?: "", c.getString(1) ?: "", c.getInt(2)) else null }
    }.getOrNull()

    fun setType(uri: Uri, type: Int) = runCatching {
        cr.update(uri, ContentValues().apply { put(Telephony.Sms.TYPE, type) }, null, null)
    }

    fun threadIdFor(address: String): Long = Telephony.Threads.getOrCreateThreadId(context, address)

    /** Contact search for the New Message screen: name or number substring, max 8. */
    fun searchContacts(q: String): List<Pair<String, String>> {
        if (q.isBlank()) return emptyList()
        return runCatching {
            cr.query(Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(q)),
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
                val out = ArrayList<Pair<String, String>>()
                while (c.moveToNext() && out.size < 8) { val n = c.getString(1) ?: continue; out += (c.getString(0) ?: n) to n }
                out.distinctBy { ThreadMapping.normalize(it.second) }
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** All contact display names (for keyboard: never autocorrect names). */
    fun allContactNames(): List<String> = runCatching {
        cr.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) c.getString(0)?.let { add(it) } }
        } ?: emptyList()
    }.getOrDefault(emptyList())

    fun contactName(address: String): String = nameCache[address] ?: (lookupOne(address).also { nameCache[address] = it })

    private fun lookupOne(address: String): String = run {
        runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
            cr.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: address
    }

    /** One query for all parts of the given MMS ids: text bodies + image/video refs. */
    private fun mmsParts(ids: List<Long>): Pair<Map<Long, String>, Map<Long, List<MediaRef>>> {
        if (ids.isEmpty()) return emptyMap<Long, String>() to emptyMap()
        val texts = HashMap<Long, StringBuilder>(); val media = HashMap<Long, MutableList<MediaRef>>()
        runCatching {
            cr.query(Uri.parse("content://mms/part"), arrayOf("_id", "mid", "ct", "text", "_data"),
                "mid IN (${ids.joinToString(",")})", null, "_id ASC")?.use { c ->
                while (c.moveToNext()) {
                    val ct = (c.getString(2) ?: continue).lowercase()
                    when {
                        ct.startsWith("text/plain") -> {
                            // Text is normally in the text column; some writers leave it in the part file (_data) instead.
                            val t = c.getString(3) ?: if (c.getString(4) != null) readPartText(c.getLong(0)) else null
                            val sb = texts.getOrPut(c.getLong(1)) { StringBuilder() }
                            if (!t.isNullOrBlank()) { if (sb.isNotEmpty()) sb.append(' '); sb.append(t) }
                        }
                        ct.startsWith("image/") || ct.startsWith("video/") -> media.getOrPut(c.getLong(1)) { ArrayList() } += MediaRef(c.getLong(0), ct)
                    }
                }
            }
        }.onFailure { Log.w(TAG, "mms parts failed", it) }
        return texts.mapValues { it.value.toString() }.filterValues { it.isNotBlank() } to media
    }

    private fun readPartText(partId: Long): String? = runCatching {
        cr.openInputStream(Uri.parse("content://mms/part/$partId"))?.use { String(it.readBytes(), Charsets.UTF_8) }
    }.getOrNull()

    /** FROM address (type 137) of one MMS; ignores placeholder tokens. */
    private fun mmsFrom(id: Long): String = runCatching {
        cr.query(Uri.parse("content://mms/$id/addr"), arrayOf("address", "type"), null, null, null)?.use { c ->
            val rows = ArrayList<Pair<String, Int>>()
            while (c.moveToNext()) rows += (c.getString(0) ?: "") to c.getInt(1)
            ThreadMapping.mmsSender(rows)
        }
    }.getOrNull() ?: ""

    /** Batched contact lookup: one query over all phone numbers, matched by normalized number; cached process-wide. */
    fun contactNames(addresses: Set<String>): Map<String, String> {
        val missing = addresses.filter { !nameCache.containsKey(it) }
        if (missing.isNotEmpty()) {
            val byNorm = HashMap<String, String>()
            runCatching {
                cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                    null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val num = c.getString(0) ?: continue
                        byNorm.putIfAbsent(ThreadMapping.normalize(num), c.getString(1) ?: num)
                    }
                }
            }.onFailure { Log.w(TAG, "contacts batch failed", it) }
            missing.forEach { a -> nameCache[a] = byNorm[ThreadMapping.normalize(a)] ?: a }
        }
        return addresses.associateWith { nameCache[it] ?: it }
    }

    companion object {
        const val TAG = "MatrixSMS"
        const val PAGE = 50
        private val nameCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    }
}
