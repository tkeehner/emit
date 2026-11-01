package com.example.emit.link

import org.junit.Assert.*
import org.junit.Test

class RelayLogicTest {
    private val now = 10_000_000_000L
    private val home = RelayPolicy.Net(true, true, true, "abc")
    private val cell = RelayPolicy.Net(true, false, false, "zzz")

    @Test fun helloAndParse() {
        val room = "ab".repeat(32)
        assertEquals("MSMS-RELAY/1 tok0123456789abcdef $room phone\n", RelayWire.hello("tok0123456789abcdef", room))
        assertThrows(IllegalArgumentException::class.java) { RelayWire.hello("bad token", room) }
        assertThrows(IllegalArgumentException::class.java) { RelayWire.hello("t".repeat(20), "xyz") }
        assertEquals(RelayWire.Reply.PAIRED, RelayWire.parse("OK paired").first)
        assertEquals(RelayWire.Reply.ERROR to "auth", RelayWire.parse("ERR auth"))
    }

    @Test fun fingerprint() {
        assertTrue(RelayWire.validFingerprint("AB:".repeat(31) + "AB"))
        assertFalse(RelayWire.validFingerprint("abc"))
        val der = byteArrayOf(1, 2, 3)
        val fp = java.security.MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it) }
        assertTrue(RelayWire.certMatches(der, fp)); assertFalse(RelayWire.certMatches(byteArrayOf(1, 2, 4), fp))
    }

    @Test fun policy() {
        val homes = mapOf("abc" to now - 1000)
        assertFalse(RelayPolicy.shouldConnect(true, true, 1, home, homes, now, 0))      // home Wi-Fi: LAN only
        assertTrue(RelayPolicy.shouldConnect(true, true, 1, cell, homes, now, 0))       // cellular
        assertTrue(RelayPolicy.shouldConnect(true, true, 1, home.copy(fingerprint = "cafe"), homes, now, 0))  // other Wi-Fi
        assertTrue(RelayPolicy.shouldConnect(true, true, 1, home, mapOf("abc" to now - RelayPolicy.HOME_TTL_MS - 1), now, 0)) // stale home
        assertTrue(RelayPolicy.shouldConnect(true, true, 1, home.copy(unmetered = false), homes, now, 0))   // metered hotspot
        assertFalse(RelayPolicy.shouldConnect(true, true, 1, cell, homes, now, 1))      // PC already on a LAN session
        assertFalse(RelayPolicy.shouldConnect(false, true, 1, cell, homes, now, 0))
        assertFalse(RelayPolicy.shouldConnect(true, false, 1, cell, homes, now, 0))
        assertFalse(RelayPolicy.shouldConnect(true, true, 0, cell, homes, now, 0))
        assertFalse(RelayPolicy.shouldConnect(true, true, 1, cell.copy(connected = false), homes, now, 0))
    }

    @Test fun backoff() {
        assertEquals(2000L, RelayPolicy.backoffMs(0)); assertEquals(4000L, RelayPolicy.backoffMs(1))
        assertEquals(300_000L, RelayPolicy.backoffMs(20)); assertTrue(RelayPolicy.backoffMs(3, 0.0) < RelayPolicy.backoffMs(3, 1.0))
    }

    @Test fun netFingerprintStable() {
        val a = RelayPolicy.networkFingerprint("192.168.1.1", "192.168.0/22", listOf("8.8.8.8", "1.1.1.1"))
        assertEquals(a, RelayPolicy.networkFingerprint("192.168.1.1", "192.168.0/22", listOf("1.1.1.1", "8.8.8.8")))
        assertNotEquals(a, RelayPolicy.networkFingerprint("10.0.0.1", "10.0.0/24", listOf()))
        assertNull(RelayPolicy.networkFingerprint(null, null, listOf()))
    }

    @Test fun keepalive() {
        assertTrue(RelayPolicy.PING_MS <= 30_000)   // must beat carrier NAT idle timeouts
        assertFalse(RelayPolicy.keepaliveDead(0)); assertTrue(RelayPolicy.keepaliveDead(1))
        assertEquals(1500L, RelayPolicy.backoffMs(0, 0.0)); assertEquals(225_000L, RelayPolicy.backoffMs(20, 0.0))
    }
}
