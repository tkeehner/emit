package com.example.emit.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlin.math.abs
import kotlin.random.Random

val Black: Color @Composable get() = Pal.Bg
val Amber: Color @Composable get() = Color(LocalTheme.current.palette.accent3)
/** JetBrains Mono NL (no ligatures; SIL OFL 1.1, see assets/licenses). Missing glyphs (katakana) fall back to the system font. */
val Mono = FontFamily(
    androidx.compose.ui.text.font.Font(com.example.emit.R.font.jbmono_regular, androidx.compose.ui.text.font.FontWeight.Normal),
    androidx.compose.ui.text.font.Font(com.example.emit.R.font.jbmono_medium, androidx.compose.ui.text.font.FontWeight.Medium),
    androidx.compose.ui.text.font.Font(com.example.emit.R.font.jbmono_bold, androidx.compose.ui.text.font.FontWeight.Bold))

/** True when the system animator duration scale is 0 (Remove animations / battery saver): effects go static. */
@Composable
fun rememberReduceMotion(): Boolean {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return remember { Motion.reduced(android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)) }
}
object Motion { fun reduced(animatorScale: Float) = animatorScale == 0f }

const val GLYPHS = "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ"

fun glyphFor(key: String): Char = GLYPHS[abs(key.hashCode()) % GLYPHS.length]

/** Stable per-contact katakana glyph that occasionally flickers through random glyphs. */
@Composable
fun GlyphIcon(key: String, size: Int = 40, group: Boolean = false) {
    val base = remember(key) { glyphFor(key) }
    var shown by remember(key) { mutableStateOf(base) }
    val still = rememberReduceMotion()
    LaunchedEffect(key, still) {
        while (!still) {
            delay(Random.nextLong(3000, 9000))
            repeat(6) { shown = GLYPHS.random(); delay(60) }
            shown = base
        }
    }
    val glyphC = Color(Pal.ensure(LocalAccent.current.argb.let { if (it == ChatPalette.DEFAULT.argb) LocalTheme.current.palette.primary else it }, LocalTheme.current.palette.s2, 4.5))
    val tile = LocalTheme.current.shape((size / 4).dp)
    Box(Modifier.size(size.dp).background(Pal.S2c, tile)
        .border(1.dp, Color(Pal.tint(LocalAccent.current.argb, LocalTheme.current.palette.bg, LocalTheme.current.palette.s1, LocalTheme.current.palette.line, LocalTheme.current.palette.body, LocalTheme.current.palette.dim).line), tile), contentAlignment = Alignment.Center) {
        if (group) Text(shown.toString() + glyphFor(key.reversed() + "#"), color = glyphC, fontFamily = UiFont, fontSize = (size / 3).sp, modifier = Modifier.semantics { contentDescription = "group avatar" })
        else Text(shown.toString(), color = glyphC, fontFamily = UiFont, fontSize = (size / 2).sp, modifier = Modifier.semantics { contentDescription = "contact avatar" })
    }
}

/** Faint falling-code background. */
@Composable
fun CodeRain(modifier: Modifier = Modifier, alpha: Float = 0.18f) {
    var tick by remember { mutableLongStateOf(0L) }
    val still = rememberReduceMotion()
    LaunchedEffect(still) { while (!still) { delay(70); tick++ } }
    val drops = remember { mutableStateListOf<Float>() }
    val paint = remember {
        Paint().apply { color = android.graphics.Color.rgb(0, 255, 65); typeface = Typeface.MONOSPACE; isAntiAlias = true }
    }
    val accent = LocalAccent.current.argb.toInt()
    if (alpha <= 0f) return
    Canvas(modifier.fillMaxSize().alpha(alpha)) {
        paint.color = accent
        val cell = 14.dp.toPx(); paint.textSize = cell
        val cols = (size.width / cell).toInt() + 1
        while (drops.size < cols) drops.add(Random.nextFloat() * (size.height / cell + 12f) - (if (still) 0f else 6f))
        tick // read to trigger redraw
        for (i in 0 until cols) {
            val y = drops[i]
            for (k in 0 until 12) {
                val row = y - k
                if (row < 0) continue
                paint.alpha = (255 * (1f - k / 12f)).toInt()
                drawContext.canvas.nativeCanvas.drawText(GLYPHS.random().toString(), i * cell, row * cell, paint)
            }
            drops[i] = if (y * cell > size.height + 12 * cell && Random.nextFloat() > 0.95f) 0f else y + 0.5f
        }
    }
}

/** Blinking block cursor. */
@Composable
fun BlinkCursor() {
    var on by remember { mutableStateOf(true) }
    val still = rememberReduceMotion()
    LaunchedEffect(still) { while (!still) { delay(530); on = !on } }
    Text("█", color = if (on) Green else Color.Transparent, fontFamily = UiFont)
}

/** Glitch overlay: jitter, red/cyan split, scanlines. Drive with `active` for ~350ms on navigation. */
@Composable
fun GlitchOverlay(active: Boolean) {
    if (!active || rememberReduceMotion()) return
    var t by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(40); t++ } }
    val fx = LocalTheme.current.palette
    val splitA = Color(fx.glitchA); val splitB = Color(fx.glitchB); val scan = Color(fx.glitchScan)
    Canvas(Modifier.fillMaxSize()) {
        t
        val bands = 6
        repeat(bands) {
            val y = Random.nextFloat() * size.height; val h = Random.nextFloat() * 30f + 4f
            val dx = (Random.nextFloat() - 0.5f) * 40f
            drawRect(splitA, Offset(dx, y), androidx.compose.ui.geometry.Size(size.width, h))
            drawRect(splitB, Offset(-dx, y + h / 2), androidx.compose.ui.geometry.Size(size.width, h))
        }
        var y = 0f
        while (y < size.height) { drawRect(scan, Offset(0f, y), androidx.compose.ui.geometry.Size(size.width, 2f)); y += 4f }
    }
}
