package com.example.emit.ui

import org.junit.Assert.*
import org.junit.Test

class HolidayThemeTest {
    @Test fun holidayIsRegisteredManualAndGenericallyNamed() {
        assertTrue(Themes.all.any { it.id == "holiday" }); assertNotEquals("holiday", Themes.all.first().id)
        assertEquals(AvatarStyle.HOLIDAY, HolidayTheme.avatar); assertEquals("Holiday", Themes.all.first { it.id == "holiday" }.name); assertEquals(RainStyle.HOLIDAY, HolidayTheme.rain)
    }
    @Test fun everyContactGetsAStableCharacterAndAllFiveAppear() {
        assertEquals(Holi.charFor("+1 (555) 555-0142"), Holi.charFor("5555550142"))
        assertEquals(Holi.charFor("5555550142"), Holi.charFor("5555550142"))
        val seen = (0 until 200).map { Holi.charFor("55555501%02d".format(it % 100) + it / 100) }.toSet()
        assertEquals(HoliChar.entries.toSet(), seen)
    }
    @Test fun sideIconsHugTheEdgesAndCoverEveryKind() {
        val s = Holi.spooks(14); assertTrue(s.all { it[0] < 0.14f || it[0] > 0.86f })
        assertEquals(Spook.entries.size, s.map { it[2].toInt() }.toSet().size)
    }
    @Test fun syncAppliesOnlyNewerKnownDifferentThemes() {
        assertTrue(ThemeSync.shouldApply("matrix", 10, "holiday", 20))
        assertFalse(ThemeSync.shouldApply("matrix", 30, "holiday", 20))
        assertFalse(ThemeSync.shouldApply("holiday", 10, "holiday", 20))
        assertFalse(ThemeSync.shouldApply("matrix", 10, "evil", 20))
        assertFalse(ThemeSync.shouldApply("matrix", 10, null, 20))
        for (id in listOf("matrix", "neon", "odst", "holiday")) assertTrue(id, ThemeSync.known(id))
        assertEquals("com.tyler.primelauncher.ThemeSyncReceiver", ThemeSync.PEER_RX)
    }
}
