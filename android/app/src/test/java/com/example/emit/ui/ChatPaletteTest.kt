package com.example.emit.ui

import org.junit.Assert.*
import org.junit.Test

class ChatPaletteTest {
    @Test fun sixColors() = assertEquals(6, ChatPalette.ALL.size)
    @Test fun unknownFallsBackToGreen() = assertEquals(0xFF00FF41, ChatPalette.byId("nope").argb)
    @Test fun nullIsDefault() = assertSame(ChatPalette.DEFAULT, ChatPalette.byId(null))
    @Test fun lookupCyan() = assertEquals(0xFF00E5FF, ChatPalette.byId("cyan").argb)
    @Test fun idsUnique() = assertEquals(ChatPalette.ALL.size, ChatPalette.ALL.map { it.id }.toSet().size)
    @Test fun dimKeepsAlphaAndScales() = assertEquals(0xFF00A52A, ChatPalette.dim(0xFF00FF41))
    @Test fun dimWhite() = assertEquals(0xFF7F7F7F, ChatPalette.dim(0xFFFFFFFF, 0.5f))
    @Test fun sentAlwaysOneColor() = assertEquals(Pal.BODY, ChatPalette.SENT_ARGB)
    @Test fun incomingDefaultIsDimmed() = assertEquals(ChatPalette.dim(0xFF00FF41), ChatPalette.incoming(ChatPalette.DEFAULT))
    @Test fun incomingChosenIsFull() = ChatPalette.ALL.filter { it.id != "green" }.forEach { assertEquals(it.argb, ChatPalette.incoming(it)) }
}
