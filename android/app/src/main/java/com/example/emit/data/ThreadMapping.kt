package com.example.emit.data

/** Pure helpers (unit-tested) for mapping Telephony thread rows to addresses. */
object ThreadMapping {
    /** recipient_ids is a space-separated list of canonical-address ids, e.g. "12 40". */
    fun recipients(recipientIds: String?, canonical: Map<Long, String>): List<String> =
        recipientIds.orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .mapNotNull { it.toLongOrNull()?.let(canonical::get) }
            .filter { it.isNotBlank() }

    /** Digits-only comparison key; US numbers compared on last 10 digits (+1 vs 10-digit). */
    fun normalize(address: String): String {
        val trimmed = address.trim()
        if (trimmed.contains('@') || trimmed.any { it.isLetter() }) return trimmed.lowercase()
        val digits = trimmed.filter { it.isDigit() }
        return if (digits.length == 11 && digits.startsWith("1")) digits.substring(1) else digits
    }

    fun sameAddress(a: String, b: String): Boolean = a.isNotBlank() && normalize(a) == normalize(b)

    /** Exact recipient-set equality on normalized numbers (order/format independent). Empty never matches. */
    fun sameSet(a: Collection<String>, b: Collection<String>): Boolean {
        val x = a.map(::normalize).filter { it.isNotEmpty() }.toSet(); val y = b.map(::normalize).filter { it.isNotEmpty() }.toSet()
        return x.isNotEmpty() && x == y && a.size == x.size && b.size == y.size
    }

    /**
     * Participants of an incoming MMS: FROM + TO/CC, minus the user's own number(s), deduped by normalized number.
     * If own number is unknown and there is exactly one TO, that TO is the user (1:1 MMS).
     */
    fun groupRecipients(from: String?, to: List<String>, own: Set<String>): List<String> {
        val ownN = own.map(::normalize).filter { it.isNotEmpty() }.toSet()
        val tos = if (ownN.isEmpty() && to.size == 1) emptyList() else to
        val seen = HashSet<String>(); val out = ArrayList<String>()
        (listOfNotNull(from) + tos).forEach { a ->
            val n = normalize(a)
            if (a.isBlank() || n in ownN || !seen.add(n)) return@forEach
            out += a
        }
        return out
    }

    /** Display name for a message sender, matched against the thread participants by normalized number. */
    fun senderName(address: String, participants: List<String>, names: List<String>): String {
        val i = participants.indexOfFirst { sameAddress(it, address) }
        return if (i >= 0) DisplayName.person(names.getOrNull(i), participants[i]) else DisplayName.phone(address)
    }

    /** Sender of an incoming MMS from its addr rows (address, type). Only type 137 (FROM) counts; tokens/"unknown" are blank. */
    fun mmsSender(rows: List<Pair<String, Int>>): String =
        rows.firstOrNull { it.second == 137 }?.first?.substringBefore("/TYPE=")?.trim()
            ?.takeIf { it.isNotBlank() && it != "insert-address-token" && !it.equals("unknown", true) } ?: ""
}
