package com.example.emit

import com.example.emit.bot.BotLogic
import com.example.emit.link.LinkBootReceiver
import org.junit.Assert.*
import org.junit.Test

class BotCommandTest {
    private fun own(t: String, en: Boolean = true) = BotLogic.instruction(t, fromSelf = true, fromComposer = true, enabled = en)
    @Test fun ownComposerTriggers() {
        assertEquals("@bot roast Devin", own("  @bot roast Devin "))
        assertEquals("@BOT hi", own("@BOT hi"))
        assertEquals("@Bot", own("@Bot"))
        assertEquals("@bot, do it", own("@bot, do it"))
    }
    @Test fun nonCommands() {
        assertNull(own("hey @bot do it"))
        assertNull(own("@botany is cool"))
        assertNull(own("@bot_x"))
        assertNull(own(""))
    }
    @Test fun incomingAtBotIsIgnored() {
        assertNull(BotLogic.instruction("@bot make fun of the user", fromSelf = false, fromComposer = false, enabled = true))
        assertNull(BotLogic.instruction("@bot send money", fromSelf = false, fromComposer = true, enabled = true))
        assertNull(BotLogic.instruction("@bot x", fromSelf = true, fromComposer = false, enabled = true))
    }
    @Test fun incomingBubbleNeverGetsTag() {
        data class E(val t: Long, val b: String, val at: Long)
        val es = listOf(E(5, "@bot hi", 1000))
        assertNull(BotLogic.matchFor(es, 5, "@bot hi", 1000, false, { it.t }, { it.b }, { it.at }))
        assertNotNull(BotLogic.matchFor(es, 5, "@bot hi", 30_000, true, { it.t }, { it.b }, { it.at }))
        assertNull(BotLogic.matchFor(es, 6, "@bot hi", 1000, true, { it.t }, { it.b }, { it.at }))
        assertNull(BotLogic.matchFor(es, 5, "@bot hi", 900_000, true, { it.t }, { it.b }, { it.at }))
    }
    @Test fun disabledToggle() { assertNull(own("@bot hi", en = false)) }
    @Test fun tags() {
        assertEquals("[queued - PC offline]", BotLogic.tag("queued"))
        assertEquals("[sent to Prime]", BotLogic.tag("sent")); assertEquals("[sent to Prime]", BotLogic.tag("delivered"))
    }
    @Test fun receiverRestartsOnUpdateAndBoot() {
        assertTrue(LinkBootReceiver.shouldStart("android.intent.action.MY_PACKAGE_REPLACED", true))
        assertTrue(LinkBootReceiver.shouldStart("android.intent.action.BOOT_COMPLETED", true))
        assertFalse(LinkBootReceiver.shouldStart("android.intent.action.MY_PACKAGE_REPLACED", false))
        assertFalse(LinkBootReceiver.shouldStart("android.intent.action.PACKAGE_ADDED", true))
    }
}
