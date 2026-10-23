package com.example.emit.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Keeps a field's TextFieldValue consistent (unit-tested). */
object FieldSync {
    fun clamp(v: TextFieldValue): TextFieldValue {
        val n = v.text.length
        val s = v.selection.start.coerceIn(0, n); val e = v.selection.end.coerceIn(0, n)
        return if (s == v.selection.start && e == v.selection.end) v else v.copy(selection = TextRange(s, e))
    }

    /** External text replaced ours: keep the cursor where it was if still valid, else put it at the end. */
    fun reconcile(current: TextFieldValue, external: String): TextFieldValue {
        val keep = external.startsWith(current.text.take(current.selection.start)) && current.selection.start <= external.length
        val pos = if (keep && external.length >= current.text.length) current.selection.start + (external.length - current.text.length)
                  else external.length
        return TextFieldValue(external, TextRange(pos.coerceIn(0, external.length)))
    }
}

/** Custom block-cursor geometry. */
object CursorMath {
    fun rect(l: TextLayoutResult, offset: Int): Rect = l.getCursorRect(offset.coerceIn(0, l.layoutInput.text.length))

    /**
     * Map a cursor position in text-layout coordinates into the visible field box. When the text is wider/taller than
     * the box the field scrolls to keep the cursor visible, so the cursor sits at the far edge; it never leaves the box.
     */
    fun fit(x: Float, y: Float, h: Float, cw: Float, boxW: Float, boxH: Float, textW: Float, textH: Float): Pair<Float, Float> {
        val maxX = (boxW - cw).coerceAtLeast(0f)
        val maxY = (boxH - h).coerceAtLeast(0f)
        val fx = if (textW > boxW && x > maxX) maxX else x.coerceIn(0f, maxX)
        val fy = if (textH > boxH && y > maxY) maxY else y.coerceIn(0f, maxY)
        return fx to fy
    }
}
