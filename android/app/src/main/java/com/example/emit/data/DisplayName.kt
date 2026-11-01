package com.example.emit.data

/** One source of truth for how people and chats are named on screen, in notifications and for a11y. */
object DisplayName {
    /** US numbers as "(555) 123-4567" / "+1 (555) 123-4567" style; anything else is left as typed. */
    fun phone(raw: String): String {
        val t = raw.trim(); if (t.isEmpty()) return "unknown"
        if (t.any { it.isLetter() } || t.contains('@')) return t   // email / alphanumeric short sender
        val d = t.filter { it.isDigit() }
        return when {
            d.length == 10 && !t.startsWith("+") -> "(${d.take(3)}) ${d.substring(3, 6)}-${d.substring(6)}"
            d.length == 11 && d[0] == '1' -> "+1 (${d.substring(1, 4)}) ${d.substring(4, 7)}-${d.substring(7)}"
            else -> t
        }
    }
    /** Contact name exactly as stored (spaces/case kept); falls back to the formatted number when there's no real name. */
    fun person(name: String?, address: String): String {
        val n = name?.trim().orEmpty()
        val isNumber = n.isNotEmpty() && n.none { it.isLetter() } && n.count { it.isDigit() } >= 3
        return if (n.isEmpty() || isNumber || n == address) phone(address.ifBlank { n }) else n
    }
    /** Group/1:1 chat title: custom label, else "Mom, Devin, Jonathan". */
    fun chat(label: String?, names: List<String>, addresses: List<String>): String {
        label?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (addresses.isEmpty()) return names.joinToString(", ").ifBlank { "unknown" }
        return addresses.mapIndexed { i, a -> person(names.getOrNull(i), a) }.joinToString(", ")
    }
}
