package com.example.emit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ODST theme tokens and its pure helpers. Placeholder callsigns only. */
class OdstThemeTest {
    @Test fun odstIsRegisteredButNotTheDefault() {
        assertEquals("odst", Themes.byId("odst").id)
        assertEquals("matrix", Themes.byId(null).id)
        assertEquals(MatrixTheme, Themes.all.first())
    }

    @Test fun odstUsesItsOwnLook() {
        val t = OdstTheme
        assertTrue(t.comms && t.fixedBubbles)
        assertEquals(RainStyle.CITY, t.rain); assertEquals(AvatarStyle.CALLSIGN, t.avatar); assertEquals(KeyStyle.ARMOR, t.keyStyle)
        assertEquals(CornerStyle.CUT, t.corners)
        assertEquals("COMMS", t.chrome.inbox)
        assertFalse(MatrixTheme.comms); assertFalse(NeonCityTheme.comms)
    }

    @Test fun callsignsAreTwoCharacters() {
        assertEquals("A1", Comms.callsign("Alpha 1"))
        assertEquals("HB", Comms.callsign("home base"))
        assertEquals("DI", Comms.callsign("Dispatch"))
        assertEquals("X-", Comms.callsign("x"))
        assertEquals("??", Comms.callsign("  "))
        assertEquals("50", Comms.callsign("(555) 010-0100")) // digits-only numbers: first digit of the first two groups
    }

    @Test fun commsHeaders() {
        assertEquals("ALPHA-1 \u25B8 21:03", Comms.header("Alpha-1", "21:03", false))
        assertEquals("YOU \u25B8 21:04", Comms.header("you", "21:04", true))
    }

    @Test fun visrComesOnAfterSunset() {
        assertTrue(Comms.visrNight(19)); assertTrue(Comms.visrNight(23)); assertTrue(Comms.visrNight(0)); assertTrue(Comms.visrNight(5))
        assertFalse(Comms.visrNight(6)); assertFalse(Comms.visrNight(12)); assertFalse(Comms.visrNight(18))
    }

    @Test fun squelchIsShortAndBounded() {
        val s = Squelch.samples()
        assertTrue(s.size in 5000..7000)
        assertTrue(s.any { it.toInt() != 0 })
        assertTrue(kotlin.math.abs(s.last().toInt()) < 2000) // decays to near silence
    }
}
