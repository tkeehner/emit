package com.example.emit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.1.47: contact names are highlighted in their chat's color, like Prime's name. */
class NameColorTest {
    @Test fun defaultChatsUseThePrimaryPrimeAlreadyUses() {
        for (t in Themes.all) {
            val p = t.palette
            assertEquals(t.id, p.primary, NameColor.argb(ChatPalette.DEFAULT, p.primary, p.s1))
            assertEquals(t.id, p.primary, NameColor.argb(ChatPalette.byId(null), p.primary, p.bg))
        }
    }

    @Test fun chosenColorsShowAtFullStrengthAndClearAaEverywhere() {
        for (t in Themes.all) {
            val p = t.palette
            for (n in ChatPalette.ALL) {
                val tint = Pal.tint(n.argb, p.bg, p.s1, p.line, p.body, p.dim)
                val rowSurface = if (t.fixedBubbles && NameColor.isDefault(n)) p.s1 else tint.s1
                val headerSurface = if (t.fixedBubbles && NameColor.isDefault(n)) p.bg else tint.bg
                val raw = if (NameColor.isDefault(n)) p.primary else n.argb
                // Every palette color is bright enough that no lightening is needed: the hue is shown as chosen.
                assertEquals("${t.id} ${n.id} row", raw, NameColor.argb(n, p.primary, rowSurface))
                assertEquals("${t.id} ${n.id} header", raw, NameColor.argb(n, p.primary, headerSurface))
                assertTrue("${t.id} ${n.id} selected", Pal.contrast(NameColor.argb(n, p.primary, p.selBg), p.selBg) >= 4.5)
                for (lvl in 0..2) {
                    val rain = Pal.mix(p.bg, n.argb, Pal.RAIN[lvl])
                    val row = Pal.over(rowSurface, rain, Pal.ROW_A)
                    assertTrue("${t.id} ${n.id} $lvl row over rain", Pal.contrast(raw, row) >= 4.5)
                    val scrim = Pal.over(p.bg, Pal.over(n.argb, headerSurface, Pal.RAIN[lvl]), Pal.SURFACE_A)
                    assertTrue("${t.id} ${n.id} $lvl sender over rain", Pal.contrast(raw, scrim) >= 4.5)
                }
            }
        }
    }

    @Test fun darkChosenColorKeepsHueButIsLiftedToAa() {
        val dark = NeonColor("x", "x", 0xFF400010)
        val c = NameColor.argb(dark, Pal.PRIMARY, Pal.BG)
        assertTrue(Pal.contrast(c, Pal.BG) >= 4.5)
        assertTrue("still reddish", ((c shr 16) and 0xFF) > (c and 0xFF))
    }

    @Test fun inboxNamesAreNoLongerPlainBody() {
        val src = File("src/main/java/com/example/emit/ui/Screens.kt").readText()
        assertFalse(src.contains("c.displayName, color = if (c.unread) Green else Pal.Body"))
        assertTrue(src.contains("c.displayName, color = nameColor"))
        val prime = File("src/main/java/com/example/emit/ui/PrimeScreen.kt").readText()
        assertTrue("Prime's name stays Green", prime.contains("Text(\"Prime\", color = Green"))
    }
}
