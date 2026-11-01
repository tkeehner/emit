package com.example.emit.ui
import org.junit.Assert.assertEquals
import org.junit.Test
class VoiceTextTest {
    @Test fun emptyBaseCapitalizes() = assertEquals("Hello there", VoiceText.merge("", " hello there "))
    @Test fun appendsWithSpace() = assertEquals("hi how are you", VoiceText.merge("hi ", "how are you"))
    @Test fun capsAfterSentence() = assertEquals("Ok. See you", VoiceText.merge("Ok.", "see you"))
    @Test fun blankSpokenKeepsBase() = assertEquals("abc", VoiceText.merge("abc", "  "))
    @Test fun insertAtEndSameAsMerge() = assertEquals("hi how are you" to 14, VoiceText.insertAt("hi ", 99, "how are you"))
    @Test fun insertMiddle() = assertEquals("see you at noon today" to 15, VoiceText.insertAt("see you today", 8, "at noon"))
    @Test fun insertAfterSentence() = assertEquals("Ok. Fine then. Bye" to 14, VoiceText.insertAt("Ok. Bye", 4, "fine then."))
    @Test fun insertAtStart() = assertEquals("Hey there you" to 3, VoiceText.insertAt("there you", 0, "hey"))
}
