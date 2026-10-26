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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
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

/** Oxanium (SIL OFL), variable on the weight axis. Titles and keys only. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Oxanium = FontFamily(
    androidx.compose.ui.text.font.Font(com.example.emit.R.font.oxanium, androidx.compose.ui.text.font.FontWeight.Normal,
        variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(400))),
    androidx.compose.ui.text.font.Font(com.example.emit.R.font.oxanium, androidx.compose.ui.text.font.FontWeight.Medium,
        variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(500))),
    androidx.compose.ui.text.font.Font(com.example.emit.R.font.oxanium, androidx.compose.ui.text.font.FontWeight.SemiBold,
        variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(600))),
    androidx.compose.ui.text.font.Font(com.example.emit.R.font.oxanium, androidx.compose.ui.text.font.FontWeight.Bold,
        variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(700))),
)

/** True when the system animator duration scale is 0 (Remove animations / battery saver): effects go static. */
@Composable
fun rememberReduceMotion(): Boolean {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return remember { Motion.reduced(android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)) }
}
object Motion { fun reduced(animatorScale: Float) = animatorScale == 0f }

const val GLYPHS = "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ"

fun glyphFor(key: String): Char = GLYPHS[abs(key.hashCode()) % GLYPHS.length]

/** Stable per-contact tile: katakana for Matrix, a generated badge for glyph themes. */
@Composable
fun GlyphIcon(key: String, size: Int = 40, group: Boolean = false) {
    if (LocalTheme.current.avatar == AvatarStyle.GLYPH) {
        val addrs = if (group) key.split(',').filter { it.isNotEmpty() } else listOf(key)
        GlyphBadge(if (addrs.isEmpty()) listOf(key) else addrs, size.dp)
    } else KatakanaGlyph(key, size, group)
}

@Composable
private fun KatakanaGlyph(key: String, size: Int, group: Boolean) {
    val base = remember(key) { glyphFor(key) }
    var shown by remember(key) { mutableStateOf(base) }
    val still = rememberReduceMotion()
    val flicker = glitchEnabled() && !still
    LaunchedEffect(key, flicker) {
        if (!flicker) { shown = base; return@LaunchedEffect }
        while (true) {
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

/** Faint falling-code background, or the theme's drizzle. Idle when the level is off. */
@Composable
fun CodeRain(modifier: Modifier = Modifier, alpha: Float = 0.18f) {
    if (alpha <= 0f) return
    if (LocalTheme.current.rain == RainStyle.DRIZZLE) NeonDrizzle(modifier, alpha) else KatakanaRain(modifier, alpha)
}

@Composable
private fun KatakanaRain(modifier: Modifier, alpha: Float) {
    var tick by remember { mutableLongStateOf(0L) }
    val still = rememberReduceMotion()
    LaunchedEffect(still) { while (!still) { delay(70); tick++ } }
    val drops = remember { mutableStateListOf<Float>() }
    val paint = remember {
        Paint().apply { color = android.graphics.Color.rgb(0, 255, 65); typeface = Typeface.MONOSPACE; isAntiAlias = true }
    }
    val accent = LocalAccent.current.argb.toInt()
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
    val allow = glitchEnabled() && !rememberReduceMotion()
    val ambient = allow && LocalTheme.current.ambientGlitch
    var burst by remember { mutableStateOf(false) }
    LaunchedEffect(ambient) {
        if (!ambient) { burst = false; return@LaunchedEffect }
        while (true) {
            delay(Random.nextLong(12_000, 28_000))
            burst = true
            delay(180)
            burst = false
        }
    }
    var t by remember { mutableIntStateOf(0) }
    val show = allow && (active || burst)
    LaunchedEffect(show) { if (show) while (true) { delay(40); t++ } }
    val fx = LocalTheme.current.palette
    val splitA = Color(fx.glitchA); val splitB = Color(fx.glitchB); val scan = Color(fx.glitchScan)
    if (show) Canvas(Modifier.fillMaxSize()) {
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

/** Vertical neon streaks. Shares the rain's 70ms tick; nothing is scheduled when rain is off. */
@Composable
private fun NeonDrizzle(modifier: Modifier, alpha: Float) {
    var tick by remember { mutableLongStateOf(0L) }
    val still = rememberReduceMotion()
    LaunchedEffect(still) { while (!still) { delay(70); tick++ } }
    val seeds = remember { List(26) { Random.nextFloat() } }
    val palette = LocalTheme.current.palette
    val colors = listOf(Color(palette.accent2), Color(palette.accent3), Color(palette.primary), Color(palette.accent2))
    Canvas(modifier.fillMaxSize().alpha(alpha)) {
        tick
        val h = size.height
        seeds.forEachIndexed { i, seed ->
            val len = 36f + (i % 5) * 26f
            val x = ((seed * 97f + i * 41f) % size.width.coerceAtLeast(1f))
            val travel = if (still) seed * h else ((seed * h) + tick * (6f + i % 4)) % (h + len)
            val y = travel - len
            drawLine(colors[i % colors.size].copy(alpha = 0.45f), Offset(x, y), Offset(x, y + len), strokeWidth = 1.6f)
        }
        drawRect(Color(palette.accent3).copy(alpha = 0.05f), Offset(0f, h * 0.72f), androidx.compose.ui.geometry.Size(size.width, h * 0.28f))
    }
}

/** Cut-corner circuit badge. Cached per secret + number; the flicker is a rare one-frame nudge, not a loop. */
@Composable
fun GlyphBadge(addrs: List<String>, size: Dp) {
    val ctx = LocalContext.current
    val secret = remember { com.example.emit.data.BadgeSecret.bytes(ctx) }
    val spec = remember(addrs) { BadgeCache.get(secret, addrs) }
    var nudge by remember(spec.norm) { mutableStateOf(false) }
    val still = rememberReduceMotion()
    val flicker = glitchEnabled() && !still
    LaunchedEffect(spec.norm, flicker) {
        if (!flicker) { nudge = false; return@LaunchedEffect }
        while (true) {
            delay(Random.nextLong(4000, 10000))
            nudge = true
            delay(90)
            nudge = false
        }
    }
    Canvas(Modifier.size(size).semantics { contentDescription = if (spec.group) "group avatar" else "contact avatar" }.graphicsLayer {
        translationX = if (nudge) 1.5f else 0f
    }) { drawBadge(spec) }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBadge(spec: Badge.Spec) {
    val s = size.minDimension / 40f
    fun px(v: Float) = v * s
    fun cut(x: Float, y: Float, w: Float, h: Float, c: Float): Path = Path().apply {
        moveTo(px(x + c), px(y))
        lineTo(px(x + w), px(y))
        lineTo(px(x + w), px(y + h - c))
        lineTo(px(x + w - c), px(y + h))
        lineTo(px(x), px(y + h))
        lineTo(px(x), px(y + c))
        close()
    }
    val o = 7f
    val cell = 26f / 5f
    fun ctr(r: Int, c: Int) = Offset(px(o + c * cell + cell / 2f), px(o + r * cell + cell / 2f))
    val pri = Color(Badge.PALETTE[spec.pri])
    val sec = Color(Badge.PALETTE[spec.sec])
    val surface = Color(Badge.SURFACE)
    val frame = cut(0.5f, 0.5f, 39f, 39f, 8f)
    drawPath(frame, pri.copy(alpha = 0.28f))
    drawPath(frame, surface)
    drawPath(frame, pri.copy(alpha = 0xAA / 255f), style = Stroke(px(1f)))
    if (spec.group) drawPath(cut(3f, 3f, 34f, 34f, 6f), sec.copy(alpha = 0x88 / 255f), style = Stroke(px(0.8f)))
    val edge = when (spec.pinSide) {
        0 -> 0 to spec.pinPos
        1 -> spec.pinPos to 4
        2 -> 4 to spec.pinPos
        else -> spec.pinPos to 0
    }
    val from = ctr(edge.first, edge.second)
    val end = when (spec.pinSide) {
        0 -> Offset(from.x, px(2.5f))
        1 -> Offset(px(37.5f), from.y)
        2 -> Offset(from.x, px(37.5f))
        else -> Offset(px(2.5f), from.y)
    }
    drawLine(sec.copy(alpha = 0.8f), from, end, strokeWidth = px(1f))
    for (t in spec.traces) drawLine(pri.copy(alpha = 0.75f), ctr(t.a.r, t.a.c), ctr(t.b.r, t.b.c), strokeWidth = px(1.2f))
    val k = px(2.9f)
    for (r in 0 until 5) for (c in 0 until 5) {
        if (!spec.grid[r][c]) continue
        val p = ctr(r, c)
        if (Badge.Cell(r, c) in spec.vias) {
            drawCircle(surface, px(1.9f), p)
            drawCircle(sec, px(1.9f), p, style = Stroke(px(1.1f)))
        } else drawRect(pri, Offset(p.x - k / 2f, p.y - k / 2f), androidx.compose.ui.geometry.Size(k, k))
    }
}
