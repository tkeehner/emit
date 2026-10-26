package com.example.emit.ui

import java.math.BigInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Neon City contact badge. The picture is fixed by HMAC-SHA256(deviceSecret, "emit-badge-v1|p|last10")
 * for a person, or the same with a group prefix and the sorted member numbers.
 * Same number in another format ("+1 (502) 555-0142" vs "5025550142") hashes the same.
 * The bit layout matches the approved glyph prototype: mirrored 5×5, traces, two vias, one pin.
 */
object Badge {
    val PALETTE = longArrayOf(0xFFF7E733, 0xFF3EE8F0, 0xFFFF3D81)
    const val SURFACE = 0xFF17131FL

    data class Cell(val r: Int, val c: Int)
    data class Trace(val a: Cell, val b: Cell)
    data class Spec(
        val group: Boolean,
        val grid: List<List<Boolean>>,
        val pri: Int,
        val sec: Int,
        val traces: List<Trace>,
        val vias: Set<Cell>,
        val pinSide: Int,
        val pinPos: Int,
        val norm: String,
        val hashHex: String,
    )

    fun normalize(addr: String): String {
        val digits = addr.filter { it.isDigit() }
        if (digits.length >= 10) return digits.takeLast(10)
        return addr.trim().lowercase()
    }

    /** Canonical message body, without the HMAC key. */
    fun message(addrs: List<String>): String {
        val group = addrs.size > 1
        val norm = if (group) addrs.map { normalize(it) }.sorted().joinToString(",") else normalize(addrs.firstOrNull().orEmpty())
        return "emit-badge-v1|${if (group) "g" else "p"}|$norm"
    }

    fun spec(secret: ByteArray, addrs: List<String>): Spec {
        val group = addrs.size > 1
        val norm = if (group) addrs.map { normalize(it) }.sorted().joinToString(",") else normalize(addrs.firstOrNull().orEmpty())
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val hash = mac.doFinal(message(addrs).toByteArray(Charsets.UTF_8))
        val bits = Bits(hash)
        val n = 5
        val grid = Array(n) { BooleanArray(n) }
        if (group) {
            val q = bits.take(9); val alt = bits.take(9)
            fun fill(value: Int) {
                for (r in 0 until 3) for (c in 0 until 3) {
                    val on = (value shr (r * 3 + c)) and 1 == 1
                    for (rr in intArrayOf(r, n - 1 - r)) for (cc in intArrayOf(c, n - 1 - c)) grid[rr][cc] = on
                }
            }
            fill(q)
            if (grid.onCount() !in 8..17) fill(q xor alt)
        } else {
            val q = bits.take(15); val alt = bits.take(15)
            fun fill(value: Int) {
                for (r in 0 until n) for (c in 0 until 3) {
                    val on = (value shr (r * 3 + c)) and 1 == 1
                    grid[r][c] = on
                    grid[r][n - 1 - c] = on
                }
            }
            fill(q)
            if (grid.onCount() !in 8..17) fill(q xor alt)
        }
        if (grid.onCount() < 8) for (r in 0 until n) grid[r][2] = true
        val pri = bits.take(8) % 3
        val sec = (pri + 1 + bits.take(1)) % 3
        val traces = ArrayList<Trace>()
        for (r in 0 until n) for (c in 0 until 3) {
            if (!grid[r][c]) continue
            for ((dr, dc) in arrayOf(0 to 1, 1 to 0)) {
                val rr = r + dr; val cc = c + dc
                if (rr < n && cc < n && grid[rr][cc] && bits.take(2) != 0) {
                    traces += Trace(Cell(r, c), Cell(rr, cc))
                    if (c < 2) traces += Trace(Cell(r, n - 1 - c), Cell(rr, n - 1 - cc))
                }
            }
        }
        val onCells = buildList { for (r in 0 until n) for (c in 0 until n) if (grid[r][c]) add(Cell(r, c)) }
        val vias = LinkedHashSet<Cell>()
        repeat(2) { vias += onCells[bits.take(6) % onCells.size] }
        val pinSide = bits.take(2)
        val pinPos = bits.take(3) % n
        return Spec(group, grid.map { it.toList() }, pri, sec, traces, vias, pinSide, pinPos, norm, hash.toHex())
    }

    private fun Array<BooleanArray>.onCount() = sumOf { row -> row.count { it } }

    private class Bits(bytes: ByteArray) {
        private val v = BigInteger(1, bytes)
        private val n = bytes.size * 8
        private var i = 0
        fun take(k: Int): Int {
            val shift = n - i - k
            val mask = BigInteger.ONE.shiftLeft(k).subtract(BigInteger.ONE)
            val out = v.shiftRight(shift).and(mask).toInt()
            i += k
            return out
        }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}

/** Small in-memory cache so a contact list does not rehash every frame. */
object BadgeCache {
    private val map = object : LinkedHashMap<String, Badge.Spec>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Badge.Spec>?) = size > 128
    }
    fun get(secret: ByteArray, addrs: List<String>): Badge.Spec {
        val key = secret.toHexKey() + "|" + Badge.message(addrs)
        synchronized(map) {
            map[key]?.let { return it }
            return Badge.spec(secret, addrs).also { map[key] = it }
        }
    }
    private fun ByteArray.toHexKey() = joinToString("") { "%02x".format(it) }
}
