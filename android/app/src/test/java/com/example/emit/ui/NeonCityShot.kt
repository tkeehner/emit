package com.example.emit.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.example.emit.data.Conversation
import com.example.emit.data.Message
import com.example.emit.keyboard.DictState
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Optional stills of Neon City. Skipped unless -Dshot.out names a directory
 * (the same switch ComposeBarShot uses for a single file).
 * Rain is forced off so the capture does not sit in the drizzle tick.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h780dp-xxhdpi")
class NeonCityShot {
    @get:Rule val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun inbox() = capture("neon-inbox") {
        val now = System.currentTimeMillis()
        val convos = listOf(
            Conversation(1, listOf("+1 (555) 555-0142"), listOf("Jordan"), "you still good for 7 tonight?", now, true),
            Conversation(2, listOf("5555550198"), listOf("Mom"), "Call me when you get a sec, love you", now - 40 * 60_000, false),
            Conversation(3, listOf("15555550117"), listOf("Sam"), "lol that's exactly what I said", now - 90 * 60_000, false),
            Conversation(4, listOf("(555) 555-0161"), listOf("Pizza Place"), "Your order #4182 is out for delivery", now - 3 * 3600_000, false),
            Conversation(5, listOf("5555550101", "5555550102", "5555550103", "5555550104"), listOf("Alex", "Sam", "Riley", "Jordan"), "Alex: who's bringing chips saturday", now - 5 * 3600_000, false, label = "Game Night"),
        )
        InboxScreen(convos, 1, emptyMap(), {}, { _, _ -> })
    }

    @Test fun thread() = capture("neon-thread") {
        val now = System.currentTimeMillis()
        val addr = "+1 (555) 555-0142"
        val msgs = listOf(
            Message(1, 1, addr, "hey are we still doing pizza + movie tonight?", now - 80 * 60_000, false),
            Message(2, 1, "me", "yeah for sure! what time works?", now - 70 * 60_000, true),
            Message(3, 1, addr, "7ish? Sam might come too", now - 50 * 60_000, false),
            Message(4, 1, "me", "nice, the more the merrier", now - 48 * 60_000, true),
            Message(5, 1, addr, "you still good for 7 tonight?", now - 5 * 60_000, false),
        )
        CompositionLocalProvider(LocalAccent provides ChatPalette.DEFAULT) {
            ThreadScreen(1, "Jordan", listOf(addr), true, null, messages = msgs, hasMore = false, loadingOlder = false,
                onNearTop = {}, drafts = emptyList(), onBack = {}, onSend = { _, _, done -> done() }, onApprove = {}, onReject = {})
        }
    }

    @Test fun keyboard() = capture("neon-keyboard") {
        val kb = KbController()
        kb.onDictState(DictState.Failed("offline"))
        kb.showSuggestions = true
        val value = TextFieldValue("yeah all good, see you at 7, I'll bring g", TextRange(48))
        kb.target = KbTarget(id = "shot", phone = false, singleLine = false, get = { value }, set = {})
        Column(Modifier.fillMaxSize()) {
            Header("JORDAN", onBack = {}, chips = false, leading = { GlyphBadge(listOf("+1 (555) 555-0142"), 28.dp) })
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            CompositionLocalProvider(LocalKb provides kb) { MatrixKeyboard(kb) }
        }
    }

    @Test fun picker() = capture("neon-picker") {
        CompositionLocalProvider(LocalKb provides KbController()) {
            SettingsScreen({}, {}, {})
        }
    }

    private fun capture(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        val dir = System.getProperty("shot.out")
        Assume.assumeTrue(dir != null)
        RainPref.level.value = 0
        ThemePref.id.value = "neon"
        EffectPrefs.glitch.value = false
        rule.mainClock.autoAdvance = false
        rule.setContent { CrtHost(boot = false, content) }
        repeat(12) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
        val v = rule.activity.window.decorView
        val bmp = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        val out = File(dir, "$name.png")
        out.parentFile?.mkdirs()
        java.io.FileOutputStream(out).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
