package com.example.emit.mms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.FileProvider
import com.example.emit.data.SmsRepository
import com.example.emit.sms.IncomingBus
import com.example.emit.sms.Notifier
import java.io.File

/** MMS send/download through the platform MMS service. No automatic sending: send() is only reached from user taps. */
object MmsTransport {
    private const val TAG = "MatrixSMS"
    const val AUTHORITY = "com.example.emit.mms"
    private val MMS_PKGS = listOf("com.android.phone", "com.android.mms.service")

    private fun dir(context: Context) = File(context.cacheDir, "mms").apply { mkdirs() }

    private fun shareUri(context: Context, f: File, write: Boolean): Uri {
        val uri = FileProvider.getUriForFile(context, AUTHORITY, f)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        MMS_PKGS.forEach { runCatching { context.grantUriPermission(it, uri, flags) } }
        return uri
    }

    /** Called only via SmsSender.sendMmsToThread after all recipient checks pass. */
    fun send(context: Context, threadId: Long, recipients: List<String>, parts: List<PduPart>) {
        val pdu = Pdu.encodeSendReq(recipients, parts, "T" + System.currentTimeMillis().toString(16))
        val f = File(dir(context), "send-${System.nanoTime()}.pdu").apply { writeBytes(pdu) }
        val row = MmsStore.insert(context, Telephony.Mms.MESSAGE_BOX_OUTBOX, threadId, null, recipients, parts, read = true)
        val pi = PendingIntent.getBroadcast(context, (System.nanoTime() % Int.MAX_VALUE).toInt(),
            Intent(context, MmsResultReceiver::class.java).setAction(ACTION_SENT)
                .putExtra(EXTRA_FILE, f.absolutePath).putExtra(EXTRA_ROW, row?.toString()),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        context.getSystemService(SmsManager::class.java).sendMultimediaMessage(context, shareUri(context, f, false), null, null, pi)
    }

    /**
     * From WAP_PUSH_DELIVER (default app only) or a "tap to retry" placeholder. Downloads the message the carrier
     * announced. [placeholderRow] is the stored not-downloaded row to replace once the download succeeds.
     */
    fun download(context: Context, n: MmsNotification, subId: Int = -1, placeholderRow: String? = null) {
        val loc = n.contentLocation ?: return
        val f = File(dir(context), "dl-${System.nanoTime()}.pdu").apply { createNewFile() }
        val pi = PendingIntent.getBroadcast(context, (System.nanoTime() % Int.MAX_VALUE).toInt(),
            Intent(context, MmsResultReceiver::class.java).setAction(ACTION_DOWNLOADED)
                .putExtra(EXTRA_FILE, f.absolutePath).putExtra(EXTRA_FROM, n.from)
                .putExtra(EXTRA_LOC, loc).putExtra(EXTRA_TID, n.transactionId).putExtra(EXTRA_ROW, placeholderRow),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val sm = if (subId >= 0) runCatching { SmsManager.getSmsManagerForSubscriptionId(subId) }.getOrNull() else null
        Log.i(TAG, "mms download start from=${n.from} size=${n.size} sub=$subId retry=${placeholderRow != null} loc=$loc")
        (sm ?: context.getSystemService(SmsManager::class.java)).downloadMultimediaMessage(context, loc, shareUri(context, f, true), null, pi)
    }

    /** Keeps the last few raw PDUs that failed to parse or had no usable parts, for diagnosis (app-private). */
    private fun keepDebugPdu(context: Context, bytes: ByteArray, why: String) = runCatching {
        if (bytes.isEmpty()) return@runCatching
        val d = File(context.filesDir, "mms-debug").apply { mkdirs() }
        File(d, "${System.currentTimeMillis()}-$why.pdu").writeBytes(bytes)
        d.listFiles()?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
        Log.i(TAG, "mms raw pdu kept in files/mms-debug ($why, ${bytes.size} bytes)")
    }

    const val ACTION_SENT = "com.example.emit.MMS_SENT"
    const val ACTION_DOWNLOADED = "com.example.emit.MMS_DOWNLOADED"
    const val EXTRA_FILE = "file"; const val EXTRA_ROW = "row"; const val EXTRA_FROM = "from"
    const val EXTRA_LOC = "loc"; const val EXTRA_TID = "tid"

    internal fun onDownloaded(context: Context, file: File, fallbackFrom: String?, ok: Boolean,
                              loc: String? = null, tid: String? = null, placeholderRow: String? = null, resultCode: Int = 0) {
        val bytes = if (file.exists()) file.readBytes() else ByteArray(0)
        file.delete()
        val msg = if (ok && bytes.isNotEmpty()) Pdu.parseRetrieveConf(bytes) else null
        Log.i(TAG, "mms download result ok=$ok code=$resultCode bytes=${bytes.size} parsed=${msg != null} headers=${Pdu.lastHeaderCodes.joinToString(",") { Integer.toHexString(it) }}")
        if (msg == null || (msg.parts.isEmpty() && msg.from == null)) {
            if (ok) keepDebugPdu(context, bytes, if (msg == null) "unparsable" else "empty")
            Log.w(TAG, "mms download failed/unparsable from=$fallbackFrom; keeping retry placeholder")
            val repo = SmsRepository(context)
            if (placeholderRow == null && loc != null) {
                val from = fallbackFrom?.takeIf { it.isNotBlank() }
                val tidRow = if (from != null) repo.threadIdFor(from) else 0L
                if (tidRow > 0) MmsStore.insert(context, Telephony.Mms.MESSAGE_BOX_INBOX, tidRow, from, emptyList(), emptyList(),
                    read = false, contentLocation = loc, transactionId = tid, notificationOnly = true)
            }
            Notifier.showIncoming(context, fallbackFrom?.let { com.example.emit.data.DisplayName.person(repo.contactName(it), it) } ?: "MMS", "[MMS] download failed - open the chat and tap to retry", 0)
            IncomingBus.ping()
            return
        }
        Log.i(TAG, "mms parsed from=${msg.from} to=${msg.to.size} parts=${msg.parts.joinToString { "${it.contentType}:${it.data.size}" }}")
        if (msg.parts.isEmpty()) keepDebugPdu(context, bytes, "noparts")
        val from = msg.from?.takeIf { it.isNotBlank() } ?: fallbackFrom?.takeIf { it.isNotBlank() } ?: "unknown"
        val repo = SmsRepository(context)
        // Group MMS (multiple To besides us) lands in the group thread; we never send to groups.
        val own = com.example.emit.data.OwnNumbers.get(context)
        var others = com.example.emit.data.ThreadMapping.groupRecipients(from, msg.to, own)
        if (own.isEmpty() && msg.to.size > 1) {
            // Own number unknown: the user is one of the TOs. Prefer an existing thread whose set == all minus one TO.
            val convs = repo.conversations()
            msg.to.firstNotNullOfOrNull { me ->
                val cand = com.example.emit.data.ThreadMapping.groupRecipients(from, msg.to - me, setOf(me))
                convs.firstOrNull { com.example.emit.data.ThreadMapping.sameSet(it.addresses, cand) }?.let { cand }
            }?.let { others = it }
        }
        if (others.isEmpty()) others = listOf(from)
        val threadId = runCatching { Telephony.Threads.getOrCreateThreadId(context, others.toSet()) }.getOrElse { repo.threadIdFor(from) }
        MmsStore.insert(context, Telephony.Mms.MESSAGE_BOX_INBOX, threadId, from, msg.to, msg.parts,
            dateMs = (msg.date ?: (System.currentTimeMillis() / 1000)) * 1000, read = false, contentLocation = loc, transactionId = tid)
            .also { Log.i(TAG, "mms stored row=$it thread=$threadId from=$from group=${others.size > 1}") }
        placeholderRow?.let { MmsStore.delete(context, Uri.parse(it)); Log.i(TAG, "mms retry placeholder removed $it") }
        val text = Pdu.bodyText(msg.parts) ?: ""
        val media = msg.parts.count { it.isImage || it.isVideo }
        Notifier.showIncoming(context, repo.contactName(from), listOfNotNull(if (media > 0) "[MMS: $media media]" else null, text.ifBlank { null }).joinToString(" "), threadId)
        IncomingBus.ping()
    }
}

class MmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ok = resultCode == Activity.RESULT_OK
        val file = intent.getStringExtra(MmsTransport.EXTRA_FILE)?.let(::File)
        when (intent.action) {
            MmsTransport.ACTION_SENT -> {
                file?.delete()
                intent.getStringExtra(MmsTransport.EXTRA_ROW)?.let { row ->
                    // Success -> sent box. Failure stays in outbox (shown as not delivered).
                    if (ok) MmsStore.setBox(context, Uri.parse(row), Telephony.Mms.MESSAGE_BOX_SENT)
                }
                if (!ok) Log.w("MatrixSMS", "MMS send failed result=$resultCode")
                IncomingBus.ping()
            }
            MmsTransport.ACTION_DOWNLOADED -> if (file != null) {
                val pending = goAsync()
                Thread { try { MmsTransport.onDownloaded(context, file, intent.getStringExtra(MmsTransport.EXTRA_FROM), ok,
                    intent.getStringExtra(MmsTransport.EXTRA_LOC), intent.getStringExtra(MmsTransport.EXTRA_TID),
                    intent.getStringExtra(MmsTransport.EXTRA_ROW), resultCode) } finally { pending.finish() } }.start()
            }
        }
    }
}
