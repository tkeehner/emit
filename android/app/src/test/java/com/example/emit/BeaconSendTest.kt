package com.example.emit

import com.example.emit.sms.BeaconSendReceiver.Companion.refusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BeaconSendTest {
    private fun r(n: String?, b: String? = "hi", def: Boolean = true, now: Long = 100_000, last: Long = 0) = refusal(n, b, def, now, last)
    @Test fun normal() { assertNull(r("+1 (555) 010-1234")); assertNull(r("5550101234")) }
    @Test fun notDefault() = assertEquals("Emit isn't the default SMS app", r("5550101234", def = false))
    @Test fun emergency() { assertEquals("won't text emergency numbers", r("911")); assertEquals("won't text emergency numbers", r("9-1-1")); assertEquals("won't text emergency numbers", r("988")) }
    @Test fun shortCodes() { assertEquals("won't text short codes", r("22000")); assertEquals("won't text short codes", r("611")) }
    @Test fun badNumber() { assertEquals("bad number", r("")); assertEquals("bad number", r(null)); assertEquals("bad number", r("555;rm")); assertEquals("bad number", r("gavin")) }
    @Test fun body() { assertEquals("empty message", r("5550101234", " ")); assertEquals("empty message", r("5550101234", null)); assertEquals("message too long", r("5550101234", "x".repeat(1001))) }
    @Test fun rateLimit() { assertEquals("too many sends in a row", r("5550101234", now = 10_000, last = 8_000)); assertNull(r("5550101234", now = 10_000, last = 6_000)) }
}
