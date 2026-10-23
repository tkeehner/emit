package com.example.emit.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import com.example.emit.data.SmsRepository
import com.example.emit.data.ThreadMapping

/**
 * The ONLY place that sends SMS. Called exclusively from explicit user taps
 * (SEND button, or APPROVE on a Prime draft). Nothing calls this automatically.
 */
object SmsSender {
    sealed interface Result { data object Sent : Result; data class Refused(val reason: String) : Result }

    /** Reply in an existing thread. Re-reads the thread's recipients and refuses unless they match what the user saw. */
    fun sendToThread(context: Context, threadId: Long, shownAddress: String, body: String): Result {
        if (body.isBlank()) return Result.Refused("empty message")
        val repo = SmsRepository(context)
        val conv = repo.conversation(threadId) ?: return Result.Refused("thread not found")
        if (conv.isGroup) return Result.Refused("group threads can't be sent from Emit yet")
        val addr = conv.singleAddress ?: return Result.Refused("thread has no recipient")
        if (!ThreadMapping.sameAddress(addr, shownAddress)) return Result.Refused("recipient mismatch: shown $shownAddress, thread $addr")
        send(context, repo, threadId, addr, body)
        return Result.Sent
    }

    /** MMS reply with media in an existing single-recipient thread. Same recipient checks as SMS. */
    fun sendMmsToThread(context: Context, threadId: Long, shownAddress: String, body: String,
                        attachments: List<com.example.emit.mms.Attachment>): Result {
        if (attachments.isEmpty()) return sendToThread(context, threadId, shownAddress, body)
        val repo = SmsRepository(context)
        val conv = repo.conversation(threadId) ?: return Result.Refused("thread not found")
        if (conv.isGroup) return Result.Refused("group threads can't be sent from Emit yet")
        val addr = conv.singleAddress ?: return Result.Refused("thread has no recipient")
        if (!ThreadMapping.sameAddress(addr, shownAddress)) return Result.Refused("recipient mismatch: shown $shownAddress, thread $addr")
        val textBytes = body.toByteArray().size
        val media = com.example.emit.mms.MediaPrep.prepare(context, attachments, textBytes)
            .getOrElse { return Result.Refused(it.message ?: "couldn't prepare media") }
        val parts = (if (body.isNotBlank()) listOf(com.example.emit.mms.PduPart("text/plain", body.toByteArray(), "text0.txt")) else emptyList()) + media
        com.example.emit.mms.MmsTransport.send(context, threadId, listOf(addr), parts)
        return Result.Sent
    }

    /**
     * Group thread: ONE MMS with every participant in TO (never fanned out to individual SMS).
     * Re-reads the thread and refuses unless its recipient set exactly equals what the header showed.
     */
    fun sendGroupToThread(context: Context, threadId: Long, shown: List<String>, body: String,
                          attachments: List<com.example.emit.mms.Attachment>): Result {
        if (body.isBlank() && attachments.isEmpty()) return Result.Refused("empty message")
        val conv = SmsRepository(context).conversation(threadId) ?: return Result.Refused("thread not found")
        if (!conv.isGroup) return Result.Refused("not a group thread")
        if (!ThreadMapping.sameSet(conv.addresses, shown)) return Result.Refused("recipient set changed - reopen the chat")
        val parts = buildParts(context, body, attachments).getOrElse { return Result.Refused(it.message ?: "couldn't prepare media") }
        com.example.emit.mms.MmsTransport.send(context, threadId, conv.addresses, parts)
        return Result.Sent
    }

    /** New group message to explicitly entered recipients (one MMS). Returns thread id or null. */
    fun sendNewGroup(context: Context, recipients: List<String>, body: String): Long? {
        val r = recipients.map { it.trim() }.filter { it.isNotBlank() }.distinctBy(ThreadMapping::normalize)
        if (r.size < 2 || body.isBlank()) return null
        val tid = android.provider.Telephony.Threads.getOrCreateThreadId(context, r.toSet())
        val parts = buildParts(context, body, emptyList()).getOrNull() ?: return null
        com.example.emit.mms.MmsTransport.send(context, tid, r, parts)
        return tid
    }

    private fun buildParts(context: Context, body: String, attachments: List<com.example.emit.mms.Attachment>) = runCatching {
        val media = com.example.emit.mms.MediaPrep.prepare(context, attachments, body.toByteArray().size).getOrThrow()
        (if (body.isNotBlank()) listOf(com.example.emit.mms.PduPart("text/plain", body.toByteArray(), "text0.txt")) else emptyList()) + media
    }

    /** New message to a typed number; thread resolved via getOrCreateThreadId. Returns thread id or null. */
    fun sendNew(context: Context, address: String, body: String): Long? {
        if (address.isBlank() || body.isBlank()) return null
        val repo = SmsRepository(context)
        val tid = repo.threadIdFor(address)
        send(context, repo, tid, address, body)
        return tid
    }

    /**
     * Re-send ONE failed SMS row, only from an explicit tap on its red [ERR]. Re-reads the row and refuses unless it is
     * still FAILED; reuses the same row (same recipient, same body) - never sends anything else.
     */
    fun retryFailed(context: Context, smsId: Long): Result {
        val repo = SmsRepository(context)
        val (addr, body, type) = repo.smsRow(smsId) ?: return Result.Refused("message not found")
        if (type != android.provider.Telephony.Sms.MESSAGE_TYPE_FAILED) return Result.Refused("message is not failed")
        if (addr.isBlank() || body.isBlank()) return Result.Refused("nothing to resend")
        val uri = android.content.ContentUris.withAppendedId(android.provider.Telephony.Sms.CONTENT_URI, smsId)
        repo.setType(uri, android.provider.Telephony.Sms.MESSAGE_TYPE_OUTBOX); repo.setStatus(uri, android.provider.Telephony.Sms.STATUS_NONE)
        transmit(context, uri, addr, body)
        return Result.Sent
    }

    private fun send(context: Context, repo: SmsRepository, threadId: Long, address: String, body: String) {
        val uri = repo.insertOutgoing(threadId, address, body)
        transmit(context, uri, address, body)
    }

    private fun transmit(context: Context, uri: android.net.Uri?, address: String, body: String) {
        val sm = context.getSystemService(SmsManager::class.java)
        val parts = sm.divideMessage(body)
        val sentIntents = ArrayList<PendingIntent>()
        parts.forEachIndexed { i, _ ->
            val intent = Intent(context, SentStatusReceiver::class.java).setData(uri)
            sentIntents += PendingIntent.getBroadcast(context, (System.nanoTime() % Int.MAX_VALUE).toInt() + i, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val deliveryIntents = ArrayList<PendingIntent>()
        parts.forEachIndexed { i, _ ->
            val intent = Intent(context, DeliveryStatusReceiver::class.java).setData(uri)
            deliveryIntents += PendingIntent.getBroadcast(context, ((System.nanoTime() + 7919) % Int.MAX_VALUE).toInt() + i, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        if (parts.size > 1) sm.sendMultipartTextMessage(address, null, parts, sentIntents, deliveryIntents)
        else sm.sendTextMessage(address, null, body, sentIntents.first(), deliveryIntents.first())
    }
}
