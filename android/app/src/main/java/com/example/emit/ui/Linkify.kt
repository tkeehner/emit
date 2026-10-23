package com.example.emit.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration

/** Finds URLs, emails and phone numbers in message text (pure logic, unit-tested). */
object LinkFinder {
    data class Hit(val start: Int, val end: Int, val target: String)
    private val URL = Regex("""(?i)\b((?:https?://|www\.)[^\s<>"]+|[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|net|org|io|gov|edu|co|us|app|dev|me|ly|gl|be)(?:/[^\s<>"]*)?)""")
    private val EMAIL = Regex("""(?i)\b[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}\b""")
    private val PHONE = Regex("""(?<![\w/])(?:\+?1[\s.-]?)?(?:\(\d{3}\)|\d{3})[\s.-]?\d{3}[\s.-]?\d{4}(?![\w/])""")
    private const val TRAIL = ".,;:!?)]}'\""

    fun find(text: String): List<Hit> {
        val hits = mutableListOf<Hit>()
        fun overlaps(s: Int, e: Int) = hits.any { s < it.end && e > it.start }
        EMAIL.findAll(text).forEach { hits += Hit(it.range.first, it.range.last + 1, "mailto:" + it.value) }
        URL.findAll(text).forEach { m ->
            var e = m.range.last + 1
            while (e > m.range.first && text[e - 1] in TRAIL) {
                if (text[e - 1] == ')' && text.substring(m.range.first, e).count { it == '(' } >= text.substring(m.range.first, e).count { it == ')' }) break
                e--
            }
            val s = m.range.first
            if (e > s && !overlaps(s, e)) {
                val v = text.substring(s, e)
                hits += Hit(s, e, if (v.startsWith("http", true)) v else "https://$v")
            }
        }
        PHONE.findAll(text).forEach { m ->
            val s = m.range.first; val e = m.range.last + 1
            if (!overlaps(s, e)) hits += Hit(s, e, "tel:" + m.value.filter { it.isDigit() || it == '+' })
        }
        return hits.sortedBy { it.start }
    }

    fun annotate(prefix: String, body: String, linkColor: Color, highlight: String = "", current: Boolean = false): AnnotatedString = buildAnnotatedString {
        append(prefix)
        val style = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
        var i = 0
        for (h in find(body)) {
            append(body.substring(i, h.start))
            pushLink(LinkAnnotation.Url(h.target, style)); append(body.substring(h.start, h.end)); pop()
            i = h.end
        }
        append(body.substring(i))
        ThreadSearch.ranges(body, highlight).forEach { r ->
            addStyle(SpanStyle(background = if (current) Color(0xFFFFB000) else linkColor.copy(alpha = 0.35f), color = if (current) Color.Black else Color.Unspecified),
                prefix.length + r.first, prefix.length + r.last + 1)
        }
    }
}
