package com.example.emit

import com.example.emit.link.AppLock
import org.junit.Assert.*
import org.junit.Test

class StageETest {
    @Test fun pinRulesAndHash() {
        assertTrue(AppLock.validPin("1234")); assertFalse(AppLock.validPin("123")); assertFalse(AppLock.validPin("12a4")); assertFalse(AppLock.validPin("1".repeat(13)))
        val salt = ByteArray(16) { it.toByte() }
        assertArrayEquals(AppLock.hash("1234", salt), AppLock.hash("1234", salt))
        assertFalse(AppLock.hash("1234", salt).contentEquals(AppLock.hash("1235", salt)))
    }
    @Test fun relock() {
        assertFalse(AppLock.shouldLock(false, 0, 0, 100))                 // off = never locks
        assertTrue(AppLock.shouldLock(true, 0, 0, 100))                   // cold start
        assertFalse(AppLock.shouldLock(true, 50, 0, 100))                 // unlocked, in foreground
        assertFalse(AppLock.shouldLock(true, 50, 1000, 1000 + 59_000))    // brief background
        assertTrue(AppLock.shouldLock(true, 50, 1000, 1000 + 61_000))
    }
    @Test fun crashLogTrim() {
        val e = CrashLog.entry("t", "0.1.31", 34, "main", "java.lang.X\n at a")
        assertTrue(e.startsWith("=== t  v0.1.31  sdk34  thread=main\njava.lang.X"))
        val big = (1..100).joinToString("") { CrashLog.entry("t$it", "v", 1, "m", "x".repeat(50)) }
        val t = CrashLog.trim(big, 500)
        assertTrue(t.length <= 500); assertTrue(t.startsWith("=== ")); assertTrue(t.contains("t100"))
    }
    @Test fun changelogNewestFirst() = assertEquals(BuildConfig.VERSION_NAME, com.example.emit.ui.Changelog.entries.first().first)
}
