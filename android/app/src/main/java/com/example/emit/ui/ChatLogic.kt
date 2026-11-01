package com.example.emit.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure chat-layout logic (unit-tested): date dividers, sender grouping, delivery status, SMS segments, status line. */
object ChatLogic {
    const val GROUP_WINDOW_MS = 2 * 60 * 1000L

    /** "TODAY", "YESTERDAY", else "TUE OCT 21" (plus year when not this year). */
    fun dayLabel(ms: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate(); val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return when (d) {
            today -> "TODAY"
            today.minusDays(1) -> "YESTERDAY"
            else -> d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) "EEE MMM d" else "EEE MMM d yyyy", Locale.US)).uppercase(Locale.US)
        }
    }

    data class Entry(val sender: String, val date: Long)
    /** divider = day label to draw above this row (null = none); first/last of a same-sender run within GROUP_WINDOW_MS. */
    data class Row(val divider: String?, val first: Boolean, val last: Boolean)

    fun rows(entries: List<Entry>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<Row> {
        fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        fun joins(a: Entry, b: Entry) = a.sender == b.sender && b.date - a.date in 0..GROUP_WINDOW_MS && day(a.date) == day(b.date)
        return entries.indices.map { i ->
            val e = entries[i]; val prev = entries.getOrNull(i - 1); val next = entries.getOrNull(i + 1)
            val divider = if (prev == null || day(prev.date) != day(e.date)) dayLabel(e.date, nowMs, zone) else null
            Row(divider, prev == null || !joins(prev, e), next == null || !joins(e, next))
        }
    }

    enum class Delivery(val label: String, val spoken: String) {
        NONE("", ""), PENDING("[..]", "sending"), SENT("[OK]", "sent"), DELIVERED("[DLV]", "delivered"), FAILED("[ERR]", "failed, tap to retry")
    }
    // android.provider.Telephony constants (copied so this stays JVM-testable)
    private const val TYPE_INBOX = 1; private const val TYPE_SENT = 2; private const val TYPE_OUTBOX = 4; private const val TYPE_FAILED = 5; private const val TYPE_QUEUED = 6
    private const val STATUS_COMPLETE = 0; private const val STATUS_PENDING = 32; private const val STATUS_FAILED = 64

    /** SMS row TYPE + STATUS -> what to show. */
    fun smsDelivery(type: Int, status: Int): Delivery = when (type) {
        TYPE_INBOX -> Delivery.NONE
        TYPE_OUTBOX, TYPE_QUEUED -> Delivery.PENDING
        TYPE_FAILED -> Delivery.FAILED
        TYPE_SENT -> when { status == STATUS_FAILED -> Delivery.FAILED; status == STATUS_COMPLETE -> Delivery.DELIVERED; else -> Delivery.SENT }
        else -> Delivery.SENT
    }

    /** Only a FAILED outgoing SMS may be retried, and only by an explicit tap. */
    fun canRetry(d: Delivery, isMms: Boolean) = d == Delivery.FAILED && !isMms

    private const val GSM = "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
    private const val GSM_EXT = "^{}\\[~]|€\u000C"
    data class Segments(val units: Int, val perSegment: Int, val segments: Int) {
        /** "[42/160]" for one segment, "[200/306 2sms]" for several. */
        val label get() = if (segments <= 1) "[$units/$perSegment]" else "[$units/${perSegment * segments} ${segments}sms]"
    }
    fun segments(text: String): Segments {
        val gsm = text.all { it in GSM || it in GSM_EXT }
        val units = if (gsm) text.sumOf { if (it in GSM_EXT) 2L else 1L }.toInt() else text.length  // UTF-16 code units (emoji = 2)
        val single = if (gsm) 160 else 70; val multi = if (gsm) 153 else 67
        return if (units <= single) Segments(units, single, if (units == 0) 0 else 1)
        else Segments(units, multi, (units + multi - 1) / multi)
    }

    /** Fixed-width header status line (same length for every state so the header never shifts). */
    fun statusLine(relay: String, lanSessions: Int): String {
        val link = when { lanSessions > 0 -> "LAN ●"; relay.startsWith("PC connected") -> "AWS ●"; relay.startsWith("connected") -> "AWS ◐"; else -> "OFF ○" }
        return "[E2E ✓] [SYNC: $link]"
    }
}
