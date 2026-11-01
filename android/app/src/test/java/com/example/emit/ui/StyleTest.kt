package com.example.emit.ui

import org.junit.Assert.*
import org.junit.Test

class StyleTest {
    @Test fun faintAndDimMeetContrastForEveryPalette() = ChatPalette.ALL.forEach {
        assertTrue(it.id, ChatPalette.contrastOnBlack(ChatPalette.dimForContrast(it.argb, 0.40f, 4.5)) >= 4.5)
        assertTrue(it.id, ChatPalette.contrastOnBlack(ChatPalette.dimForContrast(it.argb, 0.65f, 6.0)) >= 4.5)
    }
    @Test fun typeScaleSnaps() {
        assertEquals(listOf(10, 11, 12, 12, 14, 14, 18, 18, 26), listOf(10, 11, 12, 13, 14, 15, 16, 18, 26).map { TypeScale.forSp(it) })
    }
    @Test fun reducedMotion() { assertTrue(Motion.reduced(0f)); assertFalse(Motion.reduced(1f)) }
    @Test fun gridIsMultipleOf4() = listOf(Ui.ScreenPad, Ui.CardPad, Ui.Gap, Ui.DialogPad, Ui.BubbleGap, Ui.BubbleGapBetween, Ui.OutgoingIndent, Ui.Touch)
        .forEach { assertEquals(0f, it.value % 4f, 0f) }
}
