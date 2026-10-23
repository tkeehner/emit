package com.example.emit.ui

import java.net.HttpURLConnection
import java.net.URL

/** Optional link previews (OFF by default). Fetches only when enabled: no cookies, no referrer, generic UA, 64 KB cap. */
object LinkPreview {
    data class Preview(val title: String, val domain: String)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Preview?>()

    fun domain(url: String): String = runCatching { URL(url).host.removePrefix("www.") }.getOrDefault("")

    /** Pure: og:title, else <title>; entity-decoded, whitespace-collapsed, max 90 chars. */
    fun parseTitle(html: String): String? {
        val og = Regex("""<meta[^>]+property\s*=\s*["']og:title["'][^>]*content\s*=\s*["']([^"']+)""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: Regex("""<meta[^>]+content\s*=\s*["']([^"']+)["'][^>]*property\s*=\s*["']og:title""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
        val t = og ?: Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)
        val clean = t?.replace("&amp;", "&")?.replace("&quot;", "\"")?.replace("&#39;", "'")?.replace("&lt;", "<")?.replace("&gt;", ">")
            ?.replace(Regex("\\s+"), " ")?.trim()
        return clean?.takeIf { it.isNotBlank() }?.let { if (it.length > 90) it.take(89) + "…" else it }
    }

    /** Pure: strip common tracking params before fetching. */
    fun clean(url: String): String {
        val q = url.substringAfter('?', ""); if (q.isEmpty()) return url
        val keep = q.split('&').filterNot { p -> p.substringBefore('=').lowercase().let { it.startsWith("utm_") || it in setOf("fbclid", "gclid", "si", "igshid", "mc_eid") } }
        return url.substringBefore('?') + if (keep.isEmpty()) "" else "?" + keep.joinToString("&")
    }

    fun fetch(url: String): Preview? = cache.getOrPut(url) {
        runCatching {
            val u = URL(clean(url)); if (u.protocol != "https") return@runCatching null
            val c = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000; readTimeout = 5000; instanceFollowRedirects = true; useCaches = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) EmitSMS-preview"); setRequestProperty("Accept", "text/html")
            }
            try {
                if (c.responseCode !in 200..299 || c.contentType?.contains("html", true) != true) return@runCatching null
                val bytes = c.inputStream.use { it.readNBytesCompat(65536) }
                parseTitle(String(bytes, Charsets.UTF_8))?.let { Preview(it, domain(c.url.toString())) }
            } finally { c.disconnect() }
        }.getOrNull()
    }
    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var left = n
        while (left > 0) { val r = read(buf, 0, minOf(buf.size, left)); if (r < 0) break; out.write(buf, 0, r); left -= r }
        return out.toByteArray()
    }
}

/** In-thread search (pure). */
object ThreadSearch {
    fun matches(bodies: List<String>, q: String): List<Int> = if (q.isBlank()) emptyList() else bodies.indices.filter { bodies[it].contains(q, ignoreCase = true) }
    fun ranges(body: String, q: String): List<IntRange> {
        if (q.isBlank()) return emptyList()
        val out = mutableListOf<IntRange>(); var i = body.indexOf(q, 0, true)
        while (i >= 0) { out += i until i + q.length; i = body.indexOf(q, i + q.length, true) }
        return out
    }
    fun step(cur: Int, size: Int, forward: Boolean) = if (size == 0) -1 else ((if (cur < 0) (if (forward) 0 else size - 1) else cur + if (forward) 1 else -1) + size) % size
}
