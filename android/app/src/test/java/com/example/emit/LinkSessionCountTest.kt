package com.example.emit

import com.example.emit.link.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LinkSessionCountTest {
    @Before fun reset() = LinkState.resetSessions()

    @Test fun pcStaysConnectedWhileAnotherSessionOfSameIdIsOpen() {
        LinkState.sessionOpened("pc1"); LinkState.sessionOpened("pc1")
        LinkState.sessionClosed("pc1")
        assertEquals(setOf("pc1"), LinkState.connected.value)
        LinkState.sessionClosed("pc1")
        assertTrue(LinkState.connected.value.isEmpty())
    }

    @Test fun extraCloseNeverGoesNegative() {
        LinkState.sessionClosed("pc1"); LinkState.sessionOpened("pc1")
        assertEquals(setOf("pc1"), LinkState.connected.value)
    }
}
