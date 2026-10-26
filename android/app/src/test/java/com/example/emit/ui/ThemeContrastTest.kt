package com.example.emit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every theme's text colors have to clear WCAG AA. A new theme is covered as soon as it joins [Themes.all]. */
class ThemeContrastTest {
    @Test fun everyThemeTextClearsAa() {
        assertTrue(Themes.all.isNotEmpty())
        for (theme in Themes.all) {
            for ((name, fg, bg) in theme.palette.textPairs()) {
                val ratio = Pal.contrast(fg, bg)
                assertTrue("${theme.id} $name is ${"%.2f".format(ratio)}", ratio >= 4.5)
            }
        }
    }

    @Test fun matrixTokensMatchTheHistoricalPalette() {
        val p = MatrixTheme.palette
        assertTrue(p.bg == Pal.BG && p.s1 == Pal.S1 && p.s2 == Pal.S2)
        assertTrue(p.primary == Pal.PRIMARY && p.body == Pal.BODY && p.dim == Pal.DIM && p.faint == Pal.FAINT)
        assertTrue(p.outBg == Pal.OUT_BG && p.outLine == Pal.OUT_LINE && p.outLabel == Pal.OUT_LABEL && p.accent2 == Pal.CYAN)
        assertTrue(p.sent == ChatPalette.SENT_ARGB)
        assertTrue(MatrixTheme.corners == CornerStyle.ROUNDED && MatrixTheme.avatar == AvatarStyle.KATAKANA)
        assertTrue(MatrixTheme.chrome.inbox == "~\$ inbox" && MatrixTheme.chrome.dayPrefix == "")
        assertTrue(MatrixTheme.chrome.fab == "[+ NEW]")
    }

    @Test fun themeIdsResolveWithoutARestart() {
        assertEquals("matrix", Themes.byId(null).id)
        assertEquals("matrix", Themes.byId("nope").id)
        assertEquals("neon", Themes.byId("neon").id)
        assertEquals(listOf("matrix", "neon"), Themes.all.map { it.id })
        assertTrue(NeonCityTheme.fixedBubbles && NeonCityTheme.scanlinesAlways && NeonCityTheme.ambientGlitch)
        assertTrue(NeonCityTheme.avatar == AvatarStyle.GLYPH && NeonCityTheme.corners == CornerStyle.CUT)
        assertTrue(NeonCityTheme.chrome.fab == "+ NEW" && NeonCityTheme.chrome.uppercaseNames)
    }
}
