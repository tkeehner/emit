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
        val a = Badge.spec(secret, listOf("+1 (502) 555-0142"))
        val b = Badge.spec(secret, listOf("502-555-0142"))
        val c = Badge.spec(secret, listOf("15025550142"))
        assertEquals("5025550142", a.norm)
        assertEquals(a.hashHex, b.hashHex)
        assertEquals(a.hashHex, c.hashHex)
        assertEquals(a.grid, b.grid)
        assertEquals(a.pri, c.pri)
        assertEquals(a.sec, c.sec)
        assertEquals(a.pinSide, b.pinSide)
        assertEquals(a.pinPos, b.pinPos)
        assertEquals(
            "fddc94a01c3d06355de5de4f976403522c5ecf13143355dbfe1304effe8ed710",
            a.hashHex,
        )
        assertEquals(listOf(false, true, true, true, false), a.grid[0])
        assertEquals(listOf(false, false, false, false, false), a.grid[1])
        assertEquals(listOf(true, true, true, true, true), a.grid[2])
        assertEquals(1, a.pri)
        assertEquals(2, a.sec)
        assertEquals(setOf(Badge.Cell(4, 2), Badge.Cell(0, 2)), a.vias)
        assertEquals(2, a.pinSide)
        assertEquals(2, a.pinPos)
        assertTrue(a.traces.isNotEmpty())
    }

    @Test fun shortCodeKeepsTheRawAddress() {
        val s = Badge.spec(secret, listOf("72975"))
        assertEquals("72975", s.norm)
        assertEquals("ee4de6675fc9da2f221adcad383938ca08b325d67228d4a9747208b7d478c3a0", s.hashHex)
        assertEquals(2, s.pri)
    }

    @Test fun groupsDifferFromPeopleAndIgnoreOrder() {
        val person = Badge.spec(secret, listOf("5025550142"))
        val group = Badge.spec(secret, listOf("5025550142", "5025550117"))
        val flipped = Badge.spec(secret, listOf("(502) 555-0117", "+1 502 555 0142"))
        assertEquals(false, person.group)
        assertEquals(true, group.group)
        assertNotEquals(person.hashHex, group.hashHex)
        assertNotEquals(person.grid, group.grid)
        assertEquals(group.hashHex, flipped.hashHex)
        assertEquals(group.grid, flipped.grid)
        assertEquals("5025550117,5025550142", group.norm)
        assertTrue(group.hashHex.startsWith("4300b6172905ff78"))
        // 4-way symmetry: each corner matches the opposite corner.
        assertEquals(group.grid[0][0], group.grid[0][4])
        assertEquals(group.grid[0][0], group.grid[4][0])
        assertEquals(group.grid[0][1], group.grid[4][3])
    }

    @Test fun aDifferentSecretPaintsADifferentBadge() {
        val a = Badge.spec(secret, listOf("5025550142"))
        val b = Badge.spec(other, listOf("5025550142"))
        assertNotEquals(a.hashHex, b.hashHex)
        assertNotEquals(a.grid, b.grid)
    }

    @Test fun messageStaysOutOfTheKey() {
        assertEquals("emit-badge-v1|p|5025550142", Badge.message(listOf("+1 (502) 555-0142")))
        assertEquals("emit-badge-v1|g|5025550117,5025550142", Badge.message(listOf("5025550142", "5025550117")))
    }
}
