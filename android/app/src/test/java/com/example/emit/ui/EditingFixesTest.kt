package com.example.emit.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.example.emit.keyboard.AutoText
import com.example.emit.keyboard.Dict
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EditingFixesTest {
    /** Simulates the field: TextFieldValue is the single source of truth, parent always gets the new text. */
    private class Field {
        var tfv = TextFieldValue("")
        var parent = ""
        fun set(v: TextFieldValue) { tfv = FieldSync.clamp(v); parent = tfv.text }
        fun recompose() { if (tfv.text != parent) tfv = FieldSync.reconcile(tfv, parent) }
        fun type(s: String) { val e = EditState(tfv.text, tfv.selection.start, tfv.selection.end).insert(s); set(TextFieldValue(e.text, TextRange(e.start))); recompose() }
        fun back() { val (e, _) = AutoText.backspace(EditState(tfv.text, tfv.selection.start, tfv.selection.end), null); set(TextFieldValue(e.text, TextRange(e.start))); recompose() }
    }

    @Test fun typeAThenBackspaceIsEmpty() { val f = Field(); f.type("a"); f.back(); assertEquals("", f.tfv.text); assertEquals("", f.parent); assertEquals(0, f.tfv.selection.start) }
    @Test fun typeHiBackspaceTwiceIsEmpty() { val f = Field(); f.type("H"); f.type("i"); f.back(); f.back(); assertEquals("", f.tfv.text); assertEquals("", f.parent) }
    @Test fun backspaceAtZeroIsNoop() { val f = Field(); f.back(); assertEquals("", f.tfv.text) }
    @Test fun staleCorrectionDoesNotBlockBackspace() {
        val c = com.example.emit.keyboard.Correction("teh", "the", " ", 99)
        assertEquals("", AutoText.backspace(EditState("a", 1), c).first.text)
    }
    @Test fun clampFixesOutOfRangeSelection() = assertEquals(TextRange(2), FieldSync.clamp(TextFieldValue("hi", TextRange(9))).selection)
    @Test fun reconcileClearedAfterSend() = assertEquals(TextFieldValue("", TextRange(0)), FieldSync.reconcile(TextFieldValue("hello", TextRange(5)), ""))

    @Test fun cursorNeverLeavesBox() {
        // text wider than box, cursor at end -> pinned to right edge
        assertEquals(91f to 0f, CursorMath.fit(400f, 0f, 20f, 9f, 100f, 20f, 400f, 20f))
        // short text -> real position
        assertEquals(30f to 0f, CursorMath.fit(30f, 0f, 20f, 9f, 100f, 20f, 30f, 20f))
        // multi-line beyond visible height -> bottom line
        assertEquals(10f to 80f, CursorMath.fit(10f, 200f, 20f, 9f, 100f, 100f, 100f, 220f))
        // stale x past width without overflow -> clamped
        assertEquals(91f to 0f, CursorMath.fit(150f, 0f, 20f, 9f, 100f, 20f, 50f, 20f))
    }

    /** The packaged dictionary assets parse. */
    @Test fun dictAssetsParse() {
        val w = File("src/main/assets/dict/words.bin"); val b = File("src/main/assets/dict/bigrams.bin")
        val e = Dict.load({ w.inputStream() }, { b.inputStream() })
        assertEquals(39998, e.wordCount); assertTrue(e.bigramKeys > 1000)
    }
}
