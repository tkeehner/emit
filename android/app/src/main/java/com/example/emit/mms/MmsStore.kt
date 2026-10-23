package com.example.emit.mms

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log

/** Writes MMS rows into the Telephony provider (default SMS app only). */
object MmsStore {
    private const val TAG = "MatrixSMS"
    private const val ADDR_FROM = 137; private const val ADDR_TO = 151

    fun insert(
        context: Context, box: Int, threadId: Long, from: String?, to: List<String>,
        parts: List<PduPart>, dateMs: Long = System.currentTimeMillis(), read: Boolean,
        contentLocation: String? = null, transactionId: String? = null, notificationOnly: Boolean = false,
    ): Uri? = runCatching {
        val cr = context.contentResolver
        val v = ContentValues().apply {
            put(Telephony.Mms.THREAD_ID, threadId)
            put(Telephony.Mms.DATE, dateMs / 1000); put(Telephony.Mms.DATE_SENT, dateMs / 1000)
            put(Telephony.Mms.MESSAGE_BOX, box)
            put(Telephony.Mms.READ, if (read) 1 else 0); put(Telephony.Mms.SEEN, if (read) 1 else 0)
            put(Telephony.Mms.MESSAGE_TYPE, if (notificationOnly) 130 else if (box == Telephony.Mms.MESSAGE_BOX_INBOX) 132 else 128)
            contentLocation?.let { put(Telephony.Mms.CONTENT_LOCATION, it) }
            transactionId?.let { put(Telephony.Mms.TRANSACTION_ID, it) }
            put(Telephony.Mms.MMS_VERSION, 18)
            put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
            put(Telephony.Mms.TEXT_ONLY, if (parts.all { it.isText }) 1 else 0)
        }
        val uri = cr.insert(Telephony.Mms.CONTENT_URI, v) ?: return null
        val id = uri.lastPathSegment ?: return null
        fun addr(a: String, type: Int) = cr.insert(Uri.parse("content://mms/$id/addr"), ContentValues().apply {
            put("address", a); put("type", type); put("charset", 106)
        })
        addr(from?.takeIf { it.isNotBlank() } ?: "insert-address-token", ADDR_FROM)
        to.forEach { addr(it, ADDR_TO) }
        parts.forEach { p -> runCatching {
            val pv = ContentValues().apply {
                put("mid", id); put("ct", p.contentType); put("cl", p.name); put("cid", "<${p.name}>"); put("name", p.name)
                if (p.isText) { put("chset", 106); put("text", String(p.data, Charsets.UTF_8)) }
            }
            val partUri = cr.insert(Uri.parse("content://mms/$id/part"), pv)
            if (partUri != null && !p.isText) cr.openOutputStream(partUri)?.use { it.write(p.data) }
            Log.i(TAG, "mms store part mid=$id ct=${p.contentType} bytes=${p.data.size} ok=${partUri != null}")
        }.onFailure { Log.w(TAG, "mms store part failed mid=$id ct=${p.contentType}", it) } }
        uri
    }.onFailure { Log.w(TAG, "mms insert failed", it) }.getOrNull()

    fun delete(context: Context, uri: Uri) = runCatching { context.contentResolver.delete(uri, null, null) }

    fun setBox(context: Context, uri: Uri, box: Int) = runCatching {
        context.contentResolver.update(uri, ContentValues().apply { put(Telephony.Mms.MESSAGE_BOX, box) }, null, null)
    }
}
