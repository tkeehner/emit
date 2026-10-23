package com.example.emit.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class TintTest {
    @Test fun tintsKeepContrast() = ChatPalette.ALL.forEach {
        val a = it.argb.toLong() and 0xFFFFFFFFL
        val t = Pal.tint(a)
        assertTrue(it.id + " incoming", Pal.contrast(t.incoming, t.s1) >= 4.5)
        assertTrue(it.id + " name", Pal.contrast(t.name, t.bg) >= 4.5)
        assertTrue(it.id + " name on s1", Pal.contrast(t.name, t.s1) >= 4.5)
        assertTrue(it.id + " link", Pal.contrast(Pal.CYAN, t.s1) >= 4.5)
        assertTrue(it.id + " glyph", Pal.contrast(Pal.ensure(a, Pal.S2, 4.5), Pal.S2) >= 4.5)
    }
    @Test fun baseColorsReadable() {
        for (bg in listOf(Pal.BG, Pal.S1, Pal.S2)) {
            assertTrue(Pal.contrast(Pal.DIM, bg) >= 4.5); assertTrue(Pal.contrast(Pal.FAINT, bg) >= 4.5)
            assertTrue(Pal.contrast(Pal.BODY, bg) >= 4.5)
        }
        assertTrue(Pal.contrast(Pal.BODY, Pal.OUT_BG) >= 4.5)
        assertTrue(Pal.contrast(Pal.OUT_LABEL, Pal.BG) >= 4.5)
        assertTrue(Pal.contrast(0xFF001A08L, Pal.PRIMARY) >= 4.5)
    }
    @Test fun tintIsSlight() = ChatPalette.ALL.forEach {
        val t = Pal.tint(it.argb.toLong() and 0xFFFFFFFFL)
        assertTrue(it.id, Pal.contrast(t.bg, Pal.BG) < 1.3 && Pal.contrast(t.s1, Pal.S1) < 1.3)
    }
}
