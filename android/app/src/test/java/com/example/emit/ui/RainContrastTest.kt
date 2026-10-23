package com.example.emit.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Worst case: brightest rain glyph (chat accent, full head alpha) behind every text surface. */
class RainContrastTest {
    private val fgs = listOf(Pal.FAINT, Pal.DIM, Pal.BODY, Pal.PRIMARY, Pal.CYAN)
    @Test fun inboxRowsReadableOverRain() = ChatPalette.ALL.forEach { n -> for (lvl in 0..2) {
        val t = Pal.tint(n.argb); val rain = Pal.rainPeak(n.argb, lvl)
        val row = Pal.over(t.s1, rain, Pal.ROW_A)
        for (fg in fgs) assertTrue("${n.id} $lvl row", Pal.contrast(fg, row) >= 4.5)
        assertTrue("${n.id} $lvl label", Pal.contrast(Pal.FAINT, Pal.over(Pal.BG, rain, Pal.SURFACE_A)) >= 4.5)
    } }
    @Test fun chatReadableOverRain() = ChatPalette.ALL.forEach { n -> for (lvl in 0..2) {
        val t = Pal.tint(n.argb)
        val bg = Pal.over(n.argb, t.bg, Pal.RAIN[lvl])           // rain drawn on top of the opaque tint
        val scrim = Pal.over(Pal.BG, bg, Pal.SURFACE_A)           // names, dividers, status under the scrim
        for (fg in listOf(t.name, Pal.FAINT, Pal.DIM, Pal.OUT_LABEL)) assertTrue("${n.id} $lvl scrim", Pal.contrast(fg, scrim) >= 4.5)
        val inB = Pal.over(t.s1, bg, Pal.BUBBLE_A); val outB = Pal.over(Pal.OUT_BG, bg, Pal.BUBBLE_A)
        assertTrue("${n.id} $lvl incoming", Pal.contrast(t.incoming, inB) >= 4.5)
        assertTrue("${n.id} $lvl outgoing", Pal.contrast(ChatPalette.SENT_ARGB, outB) >= 4.5)
        assertTrue("${n.id} $lvl link", Pal.contrast(Pal.CYAN, inB) >= 4.5)
        assertTrue("${n.id} $lvl bar", Pal.contrast(Pal.BODY, Pal.over(Pal.S2, bg, Pal.BUBBLE_A)) >= 4.5)
    } }
    @Test fun levels() { assertTrue(Pal.RAIN[0] == 0f && Pal.RAIN[1] in 0.1f..0.2f && Pal.RAIN[2] > Pal.RAIN[1]) }
    @Test fun rainVisibleThroughSurfaces() {
        // visible share of the "more" rain must be meaningful between and behind content
        assertTrue(Pal.RAIN[2] * (1 - Pal.ROW_A) >= 0.08f)
        assertTrue(Pal.RAIN[2] * (1 - Pal.BUBBLE_A) >= 0.02f)
    }

    /** Lint-like guard against the 0.1.36 bug: nothing opaque/tinted may sit between the rain and the thread content. */
    @Test fun threadContainerHasNoBackgroundOverRain() {
        val src = File("src/main/java/com/example/emit/ui/Screens.kt").readText()
        val body = src.substringAfter("fun ThreadScreen(").substringBefore("\nfun ComposeBar(")
        val root = body.lines().first { "testTag(\"thread-root\")" in it }
        assertTrue("root must draw the opaque tint", "background(Color(ChatTint.bg))" in root && "copy(alpha" !in root)
        assertTrue("CodeRain must be composed right inside the thread root", root.substringAfter("thread-root").contains("CodeRain("))
        val content = body.lines().first { "testTag(\"thread-content\")" in it }
        assertFalse("thread content must not have a background", ".background(" in content)
        for (bad in listOf("Scaffold(", "Surface(", "containerColor")) assertFalse(bad, bad in body)
        assertFalse("LazyColumn must not paint a background", Regex("LazyColumn\\([^\\n]*background").containsMatchIn(body))
    }
}
