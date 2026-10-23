package com.example.emit.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.example.emit.keyboard.Dict
import com.example.emit.keyboard.DictState
import com.example.emit.keyboard.Slots
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Drives the real KbController + WordEngine through Loading -> Ready, the path that broke on the phone. */
class KbControllerDictTest {
    private fun engine() = Dict.load({ File("src/main/assets/dict/words.bin").inputStream() }, { File("src/main/assets/dict/bigrams.bin").inputStream() })

    private fun controllerWithField(): Pair<KbController, () -> TextFieldValue> {
        val kb = KbController()
        var v = TextFieldValue("")
        kb.target = KbTarget("t", phone = false, singleLine = false, get = { v }, set = { v = it })
        return kb to { v }
    }

    @Test fun loadIsFast() {
        val t = System.nanoTime(); engine(); val ms = (System.nanoTime() - t) / 1e6
        assertTrue("load took ${ms}ms", ms < 1500)
    }

    @Test fun loadingThenReadyViaObservedFlow() = runBlocking {
        val (kb, field) = controllerWithField()
        val flow = MutableStateFlow<DictState>(DictState.Loading)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        kb.observe(flow, scope)
        assertNull(kb.engine)
        assertEquals(Slots(null, null, null), kb.computeSlots())
        // Typing while loading still works, no autocorrect.
        "teh".forEach { c -> kb.edit { it.insert(c.toString()) } }
        kb.separator(" ")
        assertEquals("teh ", field().text)

        val e = engine()
        flow.value = DictState.Ready(e, e.wordCount)   // emitted AFTER the controller started observing
        assertNotNull("controller never saw Ready", kb.engine)

        // Suggestions appear for a partial word.
        "tomo".forEach { c -> kb.edit { it.insert(c.toString()) } }
        val s = kb.computeSlots()
        assertTrue("no suggestions: $s", listOfNotNull(s.left, s.center, s.right).any { it.startsWith("tomorrow") })

        // 'teh ' autocorrects to 'the '.
        kb.target!!.set(TextFieldValue("", TextRange(0)))
        "teh".forEach { c -> kb.edit { it.insert(c.toString()) } }
        kb.separator(" ")
        assertEquals("the ", field().text)
        // and backspace reverts it
        kb.backspace()
        assertEquals("teh", field().text)
        scope.cancel()
    }

    @Test fun readyBeforeObserveIsPickedUp() {
        val (kb, _) = controllerWithField()
        val e = engine()
        kb.observe(MutableStateFlow<DictState>(DictState.Ready(e, e.wordCount)), CoroutineScope(Dispatchers.Unconfined))
        assertSame(e, kb.engine)
    }
}
