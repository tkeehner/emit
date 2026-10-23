package com.example.emit.ui
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Tapping inside existing text must move the (custom-keyboard) cursor there, and typing inserts at it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h600dp-xxhdpi")
class CursorTapTest {
    @get:Rule val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val long = "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen"

    private fun setup(): KbController {
        val kb = KbController()
        rule.setContent {
            CompositionLocalProvider(LocalKb provides kb) {
                var t by remember { mutableStateOf(long) }
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    TerminalField(t, { t = it }, "> ", "msg", modifier = Modifier.fillMaxWidth().testTag("f"))
                }
            }
        }
        return kb
    }

    @Test fun tapMovesCursorAndTypingInsertsThere() {
        val kb = setup()
        val node = rule.onNodeWithTag("f")
        node.performTouchInput { click(Offset(width * 0.3f, height * 0.25f)) }
        rule.waitForIdle()
        node.performTouchInput { click(Offset(width * 0.3f, height * 0.25f)) }
        rule.waitForIdle()
        val sel = kb.target!!.get().selection.start
        assertTrue("cursor should move near tap, was $sel of ${long.length}", sel in 1 until long.length - 10)
        val s = kb.state()!!
        kb.apply(s.insert("X"))
        rule.waitForIdle()
        val v = kb.target!!.get()
        assertTrue(v.text, v.text[sel] == 'X' && v.selection.start == sel + 1)
    }

    @Test fun longScrolledFieldTapTop() {
        val kb = KbController()
        val txt = (1..40).joinToString(" ") { "word$it" }
        rule.setContent {
            CompositionLocalProvider(LocalKb provides kb) {
                var t by remember { mutableStateOf(txt) }
                ComposeBar(t, { t = it }) {}
            }
        }
        val node = rule.onNodeWithTag("compose_field")
        node.performTouchInput { click(Offset(width * 0.5f, 30f)) }
        rule.waitForIdle()
        node.performTouchInput { click(Offset(width * 0.5f, 30f)) }
        rule.waitForIdle()
        val sel = kb.target!!.get().selection.start
        assertTrue("tap in a scrolled 5-line box lands mid-text, was $sel of ${txt.length}", sel in 1 until txt.length - 40)
        kb.apply(kb.state()!!.insert("X")); rule.waitForIdle()
        assertTrue(kb.target!!.get().text[sel] == 'X')
    }

    @Test fun scrollMath() {
        org.junit.Assert.assertEquals(40, CursorMath.scrollToShow(100, 200, 40f, 60f))   // above -> scroll up
        org.junit.Assert.assertEquals(160, CursorMath.scrollToShow(0, 200, 330f, 360f))  // below -> scroll down
        org.junit.Assert.assertEquals(50, CursorMath.scrollToShow(50, 200, 100f, 120f))  // visible -> unchanged
    }

    @Test fun firstTapAlsoPlacesCursor() {
        val kb = setup()
        rule.onNodeWithTag("f").performTouchInput { click(Offset(width * 0.3f, height * 0.25f)) }
        rule.waitForIdle()
        val sel = kb.target!!.get().selection.start
        assertTrue("first tap cursor $sel", sel in 1 until long.length - 10)
    }
}
