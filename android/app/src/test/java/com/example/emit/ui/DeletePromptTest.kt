package com.example.emit.ui

import org.junit.Assert.*
import org.junit.Test

class DeletePromptTest {
    @Test fun plural() = assertTrue(deletePrompt("Mom", 42).let { it.startsWith("> rm -rf thread // Mom?") && it.contains("all 42 messages on this phone") })
    @Test fun singular() = assertTrue(deletePrompt("Jake", 1).contains("all 1 message on this phone"))
}
