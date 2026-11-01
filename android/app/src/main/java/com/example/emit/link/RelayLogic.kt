package com.example.emit.link

import java.security.MessageDigest

/** Pure logic for the AWS relay (unit-tested on the JVM). Wire format: see relay msms_relay.py. */
object RelayWire {
    const val MAGIC = "MSMS-RELAY/1"
    /** room id = hex(HMAC-SHA256(key = DH(s_pc, s_ph), "msms-room-v1")); identical to transport.room_id() on the PC. */
    fun roomId(staticShared: ByteArray): String = LinkCrypto.hmac(staticShared, "msms-room-v1".toByteArray()).joinToString("") { "%02x".format(it) }
    fun hello(token: String, room: String, role: String = "phone"): String {
        require(token.isNotBlank() && token.none { it.isWhitespace() }) { "bad token" }
        require(room.length == 64 && room.all { it in "0123456789abcdef" }) { "bad room" }
        return "$MAGIC $token $room $role\n"
    }
    enum class Reply { WAIT, PAIRED, PONG, ERROR }
    fun parse(line: String): Pair<Reply, String> = when (line) {
        "OK wait" -> Reply.WAIT to ""
        "OK paired" -> Reply.PAIRED to ""
        "PONG" -> Reply.PONG to ""
        else -> Reply.ERROR to line.removePrefix("ERR ").take(40)
    }
    fun normFingerprint(fp: String) = fp.replace(":", "").replace(" ", "").lowercase()
    fun validFingerprint(fp: String) = normFingerprint(fp).let { it.length == 64 && it.all { c -> c in "0123456789abcdef" } }
    fun certMatches(der: ByteArray, pinned: String): Boolean =
        MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }.toByteArray(),
            normFingerprint(pinned).toByteArray())
}

/**
 * When to hold a relay connection. No location permission: "home" = an unmetered Wi-Fi network whose fingerprint
 * (gateway + subnet + DNS, from LinkProperties) has seen a LAN PC session in the last [HOME_TTL_MS]. On home Wi-Fi the PC
 * reaches the LAN listener directly, so the relay stays off; anywhere else (cellular, other Wi-Fi) it connects.
 */
object RelayPolicy {
    const val HOME_TTL_MS = 14L * 24 * 3600 * 1000
    /** Keepalive while waiting in the room. Carrier NATs drop idle TCP mappings in ~30-120 s, so 4 min was far too slow:
     *  the phone sat on a dead socket showing "connected" while the relay had already dropped it. */
    const val PING_MS = 25 * 1000
    /** Dead when a PING has gone unanswered for a whole interval (i.e. ~25-50 s without PONG). */
    fun keepaliveDead(unanswered: Int) = unanswered >= 1
    data class Net(val connected: Boolean, val wifi: Boolean, val unmetered: Boolean, val fingerprint: String?)
    fun isHome(net: Net, homes: Map<String, Long>, now: Long): Boolean =
        net.wifi && net.unmetered && net.fingerprint != null && (homes[net.fingerprint]?.let { now - it < HOME_TTL_MS } ?: false)
    fun shouldConnect(relayEnabled: Boolean, configured: Boolean, pairedPcs: Int, net: Net, homes: Map<String, Long>, now: Long, lanSessions: Int): Boolean =
        relayEnabled && configured && pairedPcs > 0 && net.connected && lanSessions == 0 && !isHome(net, homes, now)
    /** Exponential backoff with jitter: 2s, 4s, ... capped at 5 min. */
    fun backoffMs(attempt: Int, jitter: Double = 0.5): Long {
        val base = (2_000L shl attempt.coerceIn(0, 8)).coerceAtMost(300_000L)
        return (base * (0.75 + 0.5 * jitter)).toLong()
    }
    fun networkFingerprint(gateway: String?, prefix: String?, dns: List<String>): String? {
        if (gateway == null && prefix == null) return null
        val s = "$gateway|$prefix|${dns.sorted().joinToString(",")}"
        return MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }
}
