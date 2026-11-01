package com.example.emit.ui

import org.junit.Assert.*
import org.junit.Test

class LayoutTest {
    private val upd = listOf("[ install ]", "[ decline ]")
    @Test fun sideBySideOnGalaxyWidthNormalFont() = assertFalse(Ui.shouldStack(320f, upd, 15f, 1.0f))
    @Test fun stacksAtLargeFontScale() = assertTrue(Ui.shouldStack(320f, upd, 15f, 2.0f))
    @Test fun stacksOnNarrowDialog() = assertTrue(Ui.shouldStack(200f, upd, 15f, 1.0f))
    @Test fun singleNeverStacks() = assertFalse(Ui.shouldStack(10f, listOf("[ ok ]"), 15f, 2f))
}
