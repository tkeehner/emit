package com.example.emit.mms

import java.io.ByteArrayOutputStream

/** One MMS body part. */
class PduPart(val contentType: String, val data: ByteArray, val name: String) {
    val isText get() = contentType.lowercase().startsWith("text/plain")
    val isImage get() = contentType.startsWith("image/")
    val isVideo get() = contentType.startsWith("video/")
}

/** Parsed M-Notification.ind (WAP push). */
data class MmsNotification(val transactionId: String?, val contentLocation: String?, val from: String?, val size: Long?)

/** Parsed M-Retrieve.conf. */
class MmsRetrieved(val from: String?, val to: List<String>, val date: Long?, val parts: List<PduPart>)

/** Minimal OMA MMS 1.2 / WSP encoder+decoder: enough for single-recipient send-req and retrieve-conf. Pure JVM (unit-tested). */
object Pdu {
    // Header field codes (already OR'd with 0x80)
    const val BCC = 0x81; const val CC = 0x82; const val CONTENT_LOCATION = 0x83; const val CONTENT_TYPE = 0x84
    const val DATE = 0x85; const val FROM = 0x89; const val MESSAGE_SIZE = 0x8E; const val MESSAGE_TYPE = 0x8C
    const val MMS_VERSION = 0x8D; const val TO = 0x97; const val TRANSACTION_ID = 0x98
    const val TYPE_SEND_REQ = 0x80; const val TYPE_NOTIFICATION_IND = 0x82; const val TYPE_RETRIEVE_CONF = 0x84

    private val WELL_KNOWN = mapOf(
        0x03 to "text/plain", 0x1D to "image/gif", 0x1E to "image/jpeg", 0x20 to "image/png", 0x21 to "image/vnd.wap.wbmp",
        0x23 to "application/vnd.wap.multipart.mixed", 0x33 to "application/vnd.wap.multipart.related", 0x0C to "text/html",
    )
    private val WELL_KNOWN_REV = WELL_KNOWN.entries.associate { it.value to it.key }

    // ---------- encoding ----------
    private fun ByteArrayOutputStream.uintvar(v: Long) {
        var n = v; val tmp = ArrayList<Int>()
        tmp += (n and 0x7F).toInt(); n = n shr 7
        while (n > 0) { tmp += ((n and 0x7F) or 0x80).toInt(); n = n shr 7 }
        tmp.reversed().forEach { write(it) }
    }
    private fun ByteArrayOutputStream.text(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        if (b.isNotEmpty() && (b[0].toInt() and 0xFF) >= 0x80) write(0x7F)
        write(b); write(0)
    }
    private fun ByteArrayOutputStream.valueLength(len: Int) { if (len < 31) write(len) else { write(31); uintvar(len.toLong()) } }

    /** Encodes a phone number as an MMS address. */
    fun mmsAddress(address: String): String {
        val a = address.trim()
        if (a.contains('@')) return a
        val digits = a.filter { it.isDigit() || it == '+' }
        return "$digits/TYPE=PLMN"
    }

    private fun encodeContentType(ct: String, out: ByteArrayOutputStream) {
        val known = WELL_KNOWN_REV[ct]
        if (ct == "text/plain") {
            // text/plain; charset=utf-8  -> value-length, media, charset(0x81) utf-8(0x6A|0x80)
            out.write(3); out.write(0x83); out.write(0x81); out.write(0xEA)
        } else if (known != null) out.write(known or 0x80) else out.text(ct)
    }

    fun encodeSendReq(to: List<String>, parts: List<PduPart>, transactionId: String): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(MESSAGE_TYPE); o.write(TYPE_SEND_REQ)
        o.write(TRANSACTION_ID); o.text(transactionId)
        o.write(MMS_VERSION); o.write(0x92) // 1.2
        o.write(FROM); o.write(1); o.write(0x81) // insert-address-token
        require(to.isNotEmpty())
        to.forEach { o.write(TO); o.text(mmsAddress(it)) }
        o.write(CONTENT_TYPE); o.write(0x23 or 0x80) // multipart/mixed (no SMIL needed)
        o.uintvar(parts.size.toLong())
        parts.forEach { p ->
            val h = ByteArrayOutputStream()
            encodeContentType(p.contentType, h)
            h.write(0x8E); h.text(p.name)              // Content-Location
            h.write(0xC0); h.text("<${p.name}>")        // Content-ID
            val hb = h.toByteArray()
            o.uintvar(hb.size.toLong()); o.uintvar(p.data.size.toLong()); o.write(hb); o.write(p.data)
        }
        return o.toByteArray()
    }

    // ---------- decoding ----------
    private class R(val b: ByteArray, var i: Int = 0) {
        fun has() = i < b.size
        fun peek() = b[i].toInt() and 0xFF
        fun u8() = b[i++].toInt() and 0xFF
        fun uintvar(): Long { var v = 0L; while (true) { val x = u8(); v = (v shl 7) or (x and 0x7F).toLong(); if (x and 0x80 == 0) return v } }
        fun text(): String { if (peek() == 0x7F) i++; val s = i; while (i < b.size && b[i].toInt() != 0) i++; val r = String(b, s, i - s, Charsets.UTF_8); if (i < b.size) i++; return r }
        /** Value-length if present (0..31), else -1 without consuming. */
        fun valueLen(): Int { val x = peek(); return when { x < 31 -> { i++; x }; x == 31 -> { i++; uintvar().toInt() }; else -> -1 } }
        fun skipValue() { val x = peek(); when { x >= 0x80 -> i++; x < 31 -> i += 1 + x; x == 31 -> { i++; val n = uintvar().toInt(); i += n }; else -> text() } }
        fun longInt(): Long { val n = u8(); var v = 0L; repeat(n) { v = (v shl 8) or u8().toLong() }; return v }
        /** Encoded-string-value: text, or value-length + charset + text. */
        fun encodedString(): String { val n = valueLen(); return if (n < 0) text() else { val end = i + n; skipValue(); val s = text(); i = end; s } }
    }

    private fun cleanAddr(a: String) = a.substringBefore("/TYPE=")

    private fun contentType(r: R): String {
        val x = r.peek()
        return when {
            x >= 0x80 -> WELL_KNOWN[r.u8() and 0x7F] ?: "application/octet-stream"
            x <= 31 -> { val n = r.valueLen(); val end = r.i + n
                val ct = if (r.peek() >= 0x80) WELL_KNOWN[r.u8() and 0x7F] ?: "application/octet-stream" else r.text()
                r.i = end; ct }
            else -> r.text()
        }
    }

    fun parseNotification(b: ByteArray): MmsNotification? = runCatching {
        val r = R(b); var tid: String? = null; var loc: String? = null; var from: String? = null; var size: Long? = null; var type = -1
        while (r.has()) {
            when (r.u8()) {
                MESSAGE_TYPE -> type = r.u8()
                TRANSACTION_ID -> tid = r.text()
                CONTENT_LOCATION -> loc = r.text()
                MESSAGE_SIZE -> size = r.longInt()
                FROM -> { val n = r.valueLen(); val end = r.i + n; from = if (r.u8() == 0x80) cleanAddr(r.encodedString()) else null; r.i = end }
                else -> r.skipValue()
            }
        }
        if (type != TYPE_NOTIFICATION_IND) null else MmsNotification(tid, loc, from, size)
    }.getOrNull()

    /** Header codes seen while parsing the last retrieve-conf (for logcat diagnostics). */
    @Volatile var lastHeaderCodes: List<Int> = emptyList(); private set

    /**
     * Parses M-Retrieve.conf. Returns null unless the PDU really is a retrieve-conf (message type 0x84), so an
     * HTML error page or empty body is treated as a failed download instead of an empty "[MMS]" message.
     */
    fun parseRetrieveConf(b: ByteArray): MmsRetrieved? = runCatching {
        val r = R(b); var from: String? = null; val to = ArrayList<String>(); var date: Long? = null; var ctype: String? = null
        var type = -1; val codes = ArrayList<Int>()
        while (r.has() && ctype == null) {
            val code = r.u8(); codes += code
            when (code) {
                MESSAGE_TYPE -> type = r.u8()
                FROM -> { val n = r.valueLen(); if (n < 0) { from = cleanAddr(r.encodedString()) } else { val end = r.i + n
                    from = if (n > 0 && r.u8() == 0x80) cleanAddr(r.encodedString()) else null; r.i = end } }
                TO, CC -> to += cleanAddr(r.encodedString())
                DATE -> date = r.longInt()
                CONTENT_TYPE -> ctype = contentType(r)
                else -> if (code < 0x80) { r.i--; r.text(); r.text() } // application header: token-text name + text value
                        else r.skipValue()
            }
        }
        lastHeaderCodes = codes
        if (type != TYPE_RETRIEVE_CONF) return@runCatching null
        val parts = ArrayList<PduPart>()
        if (ctype?.startsWith("application/vnd.wap.multipart") == true) {
            val n = r.uintvar().toInt()
            for (idx in 0 until n) {
                if (!r.has()) break
                val hl = r.uintvar().toInt(); val dl = r.uintvar().toInt()
                val hStart = r.i
                val pct = contentType(r)
                var name = "part$idx"
                while (r.i < hStart + hl) {
                    when (r.u8()) { 0x8E -> name = r.text(); 0xC0 -> r.text(); else -> { r.i--; if (r.peek() < 0x80) { r.text(); r.text() } else { r.i++; r.skipValue() } } }
                }
                r.i = hStart + hl
                val endData = minOf(r.i + dl, b.size)
                parts += PduPart(pct, b.copyOfRange(r.i, endData), name); r.i = endData
            }
        } else if (ctype != null && r.i < b.size) parts += PduPart(ctype, b.copyOfRange(r.i, b.size), "part0")
        MmsRetrieved(from, to, date, parts)
    }.getOrNull()

    /** Pure helper: text of an MMS from its text parts, or null. */
    fun bodyText(parts: List<PduPart>): String? =
        parts.filter { it.isText }.joinToString(" ") { String(it.data, Charsets.UTF_8).trim() }.trim().ifBlank { null }
}
