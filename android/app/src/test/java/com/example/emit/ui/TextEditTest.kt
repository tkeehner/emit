package com.example.emit.ui

import org.junit.Assert.*
import org.junit.Test

class TextEditTest {
    @Test fun insertAtCursor() = assertEquals(EditState("heXllo", 3), EditState("hello", 2).insert("X"))
    @Test fun insertReplacesSelection() = assertEquals(EditState("hXo", 2), EditState("hello", 1, 4).insert("X"))
    @Test fun insertAtEnd() = assertEquals(EditState("hi!", 3), EditState("hi", 2).insert("!"))
    @Test fun backspaceMiddle() = assertEquals(EditState("hllo", 1), EditState("hello", 2).backspace())
    @Test fun backspaceAtStartNoop() = assertEquals(EditState("hello", 0), EditState("hello", 0).backspace())
    @Test fun backspaceSelection() = assertEquals(EditState("ho", 1), EditState("hello", 1, 4).backspace())
    @Test fun backspaceEmoji() = assertEquals(EditState("a", 1), EditState("a\uD83D\uDE00", 3).backspace())
    @Test fun outOfRangeCursorClamped() = assertEquals(EditState("hiX", 3), EditState("hi", 99).insert("X"))
    @Test fun shiftTapOnce() = assertEquals(ShiftMode.ONCE, nextShift(ShiftMode.OFF, 1000, 0))
    @Test fun shiftDoubleTapLocks() = assertEquals(ShiftMode.LOCK, nextShift(ShiftMode.ONCE, 1200, 1000))
    @Test fun shiftSlowSecondTapOff() = assertEquals(ShiftMode.OFF, nextShift(ShiftMode.ONCE, 2000, 1000))
    @Test fun lockTapOff() = assertEquals(ShiftMode.OFF, nextShift(ShiftMode.LOCK, 5000, 4900))
    @Test fun onceDropsAfterChar() = assertEquals(ShiftMode.OFF, afterChar(ShiftMode.ONCE))
    @Test fun lockStaysAfterChar() = assertEquals(ShiftMode.LOCK, afterChar(ShiftMode.LOCK))
}
