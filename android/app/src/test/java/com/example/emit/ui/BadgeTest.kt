package com.example.emit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Badge picture is HMAC-SHA256(deviceSecret, "emit-badge-v1|p|last10") for a person,
 * and the group prefix plus the sorted member numbers for a group.
 * Vectors use secret = bytes 0..31 so a bit-order change fails loudly.
 */
class BadgeTest {
    private val secret = ByteArray(32) { it.toByte() }
    private val other = ByteArray(32) { 7 }

    @Test fun sameNumberDifferentFormatsShareABadge() {
        val a = Badge.spec(secret, listOf("+1 (555) 555-0142"))
        val b = Badge.spec(secret, listOf("555-555-0142"))
        val c = Badge.spec(secret, listOf("15555550142"))
        assertEquals("5555550142", a.norm)
        assertEquals(a.hashHex, b.hashHex)
        assertEquals(a.hashHex, c.hashHex)
        assertEquals(a.grid, b.grid)
        assertEquals(a.pri, c.pri)
        assertEquals(a.sec, c.sec)
        assertEquals(a.pinSide, b.pinSide)
        assertEquals(a.pinPos, b.pinPos)
        assertEquals(
            "65351d9fe5e16966f575cdba5ac8b8babe6f3be819ec056947997b8337a1ecc5",
            a.hashHex,
        )
        assertEquals(listOf(false, true, false, true, false), a.grid[0])
        assertEquals(listOf(true, true, false, true, true), a.grid[1])
        assertEquals(listOf(false, true, false, true, false), a.grid[2])
        assertEquals(0, a.pri)
        assertEquals(1, a.sec)
        assertEquals(setOf(Badge.Cell(4, 0), Badge.Cell(1, 0)), a.vias)
        assertEquals(3, a.pinSide)
        assertEquals(3, a.pinPos)
        assertTrue(a.traces.isNotEmpty())
    }

    @Test fun shortCodeKeepsTheRawAddress() {
        val s = Badge.spec(secret, listOf("72975"))
        assertEquals("72975", s.norm)
        assertEquals("ee4de6675fc9da2f221adcad383938ca08b325d67228d4a9747208b7d478c3a0", s.hashHex)
        assertEquals(2, s.pri)
    }

    @Test fun groupsDifferFromPeopleAndIgnoreOrder() {
        val person = Badge.spec(secret, listOf("5555550142"))
        val group = Badge.spec(secret, listOf("5555550142", "5555550117"))
        val flipped = Badge.spec(secret, listOf("(555) 555-0117", "+1 555 555 0142"))
        assertEquals(false, person.group)
        assertEquals(true, group.group)
        assertNotEquals(person.hashHex, group.hashHex)
        assertNotEquals(person.grid, group.grid)
        assertEquals(group.hashHex, flipped.hashHex)
        assertEquals(group.grid, flipped.grid)
        assertEquals("5555550117,5555550142", group.norm)
        assertTrue(group.hashHex.startsWith("45d08ca98e132d0d"))
        // 4-way symmetry: each corner matches the opposite corner.
        assertEquals(group.grid[0][0], group.grid[0][4])
        assertEquals(group.grid[0][0], group.grid[4][0])
        assertEquals(group.grid[0][1], group.grid[4][3])
    }

    @Test fun aDifferentSecretPaintsADifferentBadge() {
        val a = Badge.spec(secret, listOf("5555550142"))
        val b = Badge.spec(other, listOf("5555550142"))
        assertNotEquals(a.hashHex, b.hashHex)
        assertNotEquals(a.grid, b.grid)
    }

    @Test fun messageStaysOutOfTheKey() {
        assertEquals("emit-badge-v1|p|5555550142", Badge.message(listOf("+1 (555) 555-0142")))
        assertEquals("emit-badge-v1|g|5555550117,5555550142", Badge.message(listOf("5555550142", "5555550117")))
    }
}
