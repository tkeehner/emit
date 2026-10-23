package com.example.emit.ui
import org.junit.Assert.assertEquals
import org.junit.Test
class VoiceTextTest {
    @Test fun emptyBaseCapitalizes() = assertEquals("Hello there", VoiceText.merge("", " hello there "))
    @Test fun appendsWithSpace() = assertEquals("hi how are you", VoiceText.merge("hi ", "how are you"))
    @Test fun capsAfterSentence() = assertEquals("Ok. See you", VoiceText.merge("Ok.", "see you"))
    @Test fun blankSpokenKeepsBase() = assertEquals("abc", VoiceText.merge("abc", "  "))
}
