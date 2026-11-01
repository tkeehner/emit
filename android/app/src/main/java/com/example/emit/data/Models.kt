package com.example.emit.data

/** A Telephony thread. Identity is ALWAYS threadId; addresses come from canonical-addresses. */
data class Conversation(
    val threadId: Long,
    val addresses: List<String>,
    val names: List<String>,
    val snippet: String,
    val date: Long,
    val unread: Boolean,
    val messageCount: Int = 0,
    val label: String? = null,
) {
    val isGroup get() = addresses.size > 1
    val displayName get() = DisplayName.chat(label, names, addresses)
    /** Key for the per-contact glyph. */
    val glyphKey get() = addresses.sorted().joinToString(",").ifBlank { threadId.toString() }
    /** Single recipient this thread may send to, or null for groups/unknown. */
    val singleAddress get() = addresses.singleOrNull()
}

data class Message(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val outgoing: Boolean,
    val isMms: Boolean = false,
    val media: List<MediaRef> = emptyList(),
    val pending: Boolean = false,
    /** Carrier content-location of an incoming MMS that was never downloaded (tap to retry), else null. */
    val retryLocation: String? = null,
    /** Outgoing delivery state ([..]/[OK]/[DLV]/[ERR]); NONE for incoming. */
    val delivery: com.example.emit.ui.ChatLogic.Delivery = com.example.emit.ui.ChatLogic.Delivery.NONE,
) { val key get() = (if (isMms) "mms:" else "sms:") + id }

/** An image/video part stored in the MMS provider (content://mms/part/<id>). */
data class MediaRef(val partId: Long, val mime: String) {
    val uri get() = android.net.Uri.parse("content://mms/part/$partId")
    val isImage get() = mime.startsWith("image/")
}
