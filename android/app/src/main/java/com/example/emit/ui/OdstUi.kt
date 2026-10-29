package com.example.emit.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** ODST rainy night skyline: cached buildings + lit windows, falling rain on top. At night it switches to VISR outlines. */
@Composable
fun OdstCity(modifier: Modifier, alpha: Float) {
    var tick by remember { mutableLongStateOf(0L) }
    val still = rememberReduceMotion()
    LaunchedEffect(still) { while (!still) { delay(70); tick++ } }
    val visr = remember { Comms.visrNight(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) }
    val streaks = remember { val r = java.util.Random(91); List(70) { floatArrayOf(r.nextFloat(), r.nextFloat(), 8f + r.nextFloat() * 16f, 0.5f + r.nextFloat() * 0.5f, 0.12f + r.nextFloat() * 0.22f) } }
    // The skyline reads as a backdrop, so it gets more opacity than glyph rain does at the same level.
    val a = (alpha * 2.6f).coerceIn(0f, 0.85f)
    Box(modifier.fillMaxSize().alpha(a).drawWithCache {
        val w = size.width.toInt().coerceAtLeast(1); val h = size.height.toInt().coerceAtLeast(1)
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        OdstArt.skyline(android.graphics.Canvas(bmp), w.toFloat(), h.toFloat(), density, visr)
        val img = bmp.asImageBitmap()
        onDrawBehind {
            drawImage(img)
            tick
            val rain = Color(0xFF9FB8D8)
            for (s in streaks) {
                val len = s[2] * density
                val speed = h * 0.09f * s[3]
                val y = ((s[1] * (h + 40)) + tick * speed) % (h + 40f) - 20f
                val x = ((s[0] * w * 1.3f) - (tick * speed * 0.18f)) % (w * 1.3f)
                drawLine(rain.copy(alpha = s[4]), Offset(x, y), Offset(x - len * 0.18f, y + len), strokeWidth = density)
            }
        }
    })
}

/** Pure drawing for the skyline (android.graphics so it can be cached into one bitmap). */
object OdstArt {
    const val AMBER = 0xFFFFB23F.toInt(); const val STEEL = 0xFF6F9CCC.toInt(); const val VISR = 0xFFD4FF3A.toInt()
    private class B(val x: Float, val w: Float, val h: Float, val seed: Float)
    private fun hash(a: Float, b: Float, c: Float): Float { val v = sin(a * 12.9898 + b * 78.233 + c * 37.719) * 43758.5453; return (v - kotlin.math.floor(v)).toFloat() }
    fun skyline(c: android.graphics.Canvas, w: Float, h: Float, d: Float, visr: Boolean) {
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val r = java.util.Random(77)
        val far = ArrayList<B>(); val near = ArrayList<B>()
        var x = -10f * d; while (x < w + 10 * d) { val bw = (14 + r.nextFloat() * 26) * d; far.add(B(x, bw, (90 + r.nextFloat() * 170) * d * (h / (600 * d)).coerceIn(0.8f, 1.6f), r.nextFloat() * 1000)); x += bw + (2 + r.nextFloat() * 4) * d }
        x = -6f * d; while (x < w + 10 * d) { val bw = (22 + r.nextFloat() * 34) * d; near.add(B(x, bw, (50 + r.nextFloat() * 120) * d * (h / (600 * d)).coerceIn(0.8f, 1.6f), r.nextFloat() * 1000)); x += bw + r.nextFloat() * 3 * d }
        val horizon = h * 0.73f
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h, intArrayOf(0xFF03060C.toInt(), 0xFF0A1422.toInt(), 0xFF1C1A1E.toInt(), 0xFF070809.toInt()), floatArrayOf(0f, 0.55f, 0.78f, 1f), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null
        if (visr) { p.color = 0xFF010301.toInt(); c.drawRect(0f, 0f, w, h, p) }
        p.shader = android.graphics.RadialGradient(w / 2, horizon, w * 0.95f, if (visr) 0x22D4FF3A else 0x2EFF963C, 0x00FF963C, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null
        fun layer(arr: List<B>, base: Float, col: Int, winA: Float, dk: Float, outline: Float) {
            for (b in arr) { val y = base - b.h
                if (visr) { p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = d; p.color = android.graphics.Color.argb((255 * outline).toInt(), 212, 255, 58); c.drawRect(b.x + d / 2, y, b.x + b.w - d / 2, h + 2, p); p.style = android.graphics.Paint.Style.FILL; continue }
                p.color = col; c.drawRect(b.x, y, b.x + b.w, h, p)
                val cols = (b.w / (5 * d)).toInt(); val rows = (b.h / (7 * d)).toInt()
                for (i in 0 until cols) for (j in 0 until rows) { val hv = hash(b.seed, i.toFloat(), j.toFloat()); if (hv > winA) continue
                    p.color = if (hv < winA * 0.25f) android.graphics.Color.argb((255 * 0.5f * dk).toInt(), 150, 190, 255) else android.graphics.Color.argb((255 * 0.65f * dk).toInt(), 255, (170 + hv * 60).toInt(), 90)
                    c.drawRect(b.x + (2 + i * 5) * d, y + (4 + j * 7) * d, b.x + (4 + i * 5) * d, y + (7 + j * 7) * d, p) } }
        }
        layer(far, horizon - 20 * d, 0xFF0B1220.toInt(), 0.33f, 0.55f, 0.35f)
        layer(near, horizon + 40 * d, 0xFF05080C.toInt(), 0.22f, 1f, 0.9f)
        p.shader = android.graphics.LinearGradient(0f, horizon, 0f, h, 0x1A788CAA, 0x80000000.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, horizon, w, h, p); p.shader = null
        if (visr) { p.color = 0x14D4FF3A; var y = 0f; while (y < h) { c.drawRect(0f, y, w, y + d, p); y += 4 * d } }
    }
}

/** Hexagonal callsign badge (two letters). [hot] = amber, else steel. */
@Composable
fun CallsignBadge(label: String, size: Dp, hot: Boolean = false) {
    val col = if (hot) Pal.Primary else Pal.Cyan
    val fill = Pal.S2c
    val text = remember(label) { Comms.callsign(label) }
    Box(Modifier.size(size).semantics { contentDescription = "contact avatar" }.drawWithCache {
        val r = size.toPx() / 2f * 0.94f; val cx = this.size.width / 2; val cy = this.size.height / 2
        val path = Path().apply { for (i in 0 until 6) { val an = i * PI / 3 + PI / 6; val px = cx + cos(an).toFloat() * r; val py = cy + sin(an).toFloat() * r; if (i == 0) moveTo(px, py) else lineTo(px, py) }; close() }
        onDrawBehind { drawPath(path, fill); drawPath(path, col, style = Stroke(1.3.dp.toPx())) }
    }, contentAlignment = Alignment.Center) {
        Text(text, color = col, fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.3f).sp, maxLines = 1)
    }
}

/** VISR corner brackets + a faint chamfered outline (ODST rows, headers, panels). No-op outside comms themes. */
@Composable
fun Modifier.visrBrackets(alpha: Float = 1f): Modifier {
    if (!LocalTheme.current.comms || alpha <= 0.01f) return this
    val col = Color(LocalTheme.current.palette.accent3)
    return this.drawWithContent {
        drawContent()
        val L = 7.dp.toPx(); val sw = 1.6.dp.toPx(); val w = size.width; val h = size.height; val k = 7.dp.toPx()
        val outline = Path().apply { moveTo(k, 0f); lineTo(w, 0f); lineTo(w, h - k); lineTo(w - k, h); lineTo(0f, h); lineTo(0f, k); close() }
        drawPath(outline, col.copy(alpha = 0.32f * alpha), style = Stroke(1.dp.toPx()))
        val c = col.copy(alpha = alpha); val st = Stroke(sw)
        drawPath(Path().apply { moveTo(0f, k + L); lineTo(0f, k); lineTo(k, 0f); lineTo(k + L * 0.6f, 0f) }, c, style = st)
        drawPath(Path().apply { moveTo(w - L, 0f); lineTo(w, 0f); lineTo(w, L) }, c, style = st)
        drawPath(Path().apply { moveTo(w, h - k - L); lineTo(w, h - k); lineTo(w - k, h); lineTo(w - k - L * 0.6f, h) }, c, style = st)
        drawPath(Path().apply { moveTo(L, h); lineTo(0f, h); lineTo(0f, h - L) }, c, style = st)
    }
}

/** Radio-comms side stripe: 3dp bar on the left (incoming) or right (outgoing). */
fun Modifier.commsStripe(color: Color, right: Boolean): Modifier = this.drawWithContent {
    drawContent(); val bw = 3.dp.toPx(); val inset = 4.dp.toPx()
    drawRect(color, Offset(if (right) size.width - bw else 0f, inset), androidx.compose.ui.geometry.Size(bw, (size.height - 2 * inset).coerceAtLeast(0f)))
}

/** One-shot VISR sweep for a screen opening: 0 -> 1 over ~1.6 s (instant with reduced motion). */
@Composable
fun rememberVisrSweep(key: Any?): Float {
    if (!LocalTheme.current.comms) return 1f
    val still = rememberReduceMotion()
    val a = remember(key) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(key) { if (!still) a.animateTo(1f, tween(1600)) }
    return a.value
}

/**
 * "TRANSMISSION SENT": a green-white flash, static lines and a chamfered banner, plus a short radio squelch.
 * [seq] increments per send; 0 = nothing yet. Comms themes only.
 */
@Composable
fun TransmissionOverlay(seq: Int) {
    if (!LocalTheme.current.comms || seq == 0) return
    val ctx = LocalContext.current
    val still = rememberReduceMotion()
    val s = remember(seq) { Animatable(0f) }
    LaunchedEffect(seq) { Squelch.play(ctx); s.snapTo(0f); s.animateTo(1.3f, tween(if (still) 900 else 1300, easing = androidx.compose.animation.core.LinearEasing)) }
    val t = s.value; if (t >= 1.3f) return
    val visr = Color(LocalTheme.current.palette.accent3)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (!still) Canvas(Modifier.fillMaxSize()) {
            drawRect(Color(0xFFE8FFE0).copy(alpha = (exp(-t * 5.0) * 0.45).toFloat()))
            val fr = (t * 30).toInt(); val la = exp(-t * 3.0).toFloat()
            for (i in 0 until 50) { val hx = OdstArtH.h(i, fr, 1); val hy = OdstArtH.h(i, fr, 2)
                drawRect((if (OdstArtH.h(i, fr, 0) > 0.5f) visr else Color.White).copy(alpha = la), Offset(hx * size.width, size.height / 2 - 60f + hy * 120f), androidx.compose.ui.geometry.Size(12f + OdstArtH.h(i, 3, 3) * 60f, 2.4f)) }
        }
        val ba = (minOf(1f, t / 0.08f) * (1f - ((t - 0.9f) / 0.4f).coerceIn(0f, 1f)))
        Column(Modifier.alpha(ba).background(Color(0xD9040802), CutCornerShape(8.dp, 8.dp)).border(1.5.dp, visr, CutCornerShape(8.dp, 8.dp))
            .padding(horizontal = 22.dp, vertical = 8.dp).semantics { contentDescription = "transmission sent" }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("TRANSMISSION SENT", color = visr, fontFamily = UiFont, fontWeight = FontWeight.Bold, fontSize = TypeScale.Label)
            Text("\u25C2\u25C2 SQUELCH \u25B8\u25B8", color = visr, fontFamily = UiFont, fontSize = TypeScale.Micro)
        }
    }
}
private object OdstArtH { fun h(i: Int, f: Int, k: Int): Float { val v = sin(i * 12.9898 + f * 78.233 + k * 37.719) * 43758.5453; return (v - kotlin.math.floor(v)).toFloat() } }

/** Short original radio squelch (band-passed noise burst, synthesized here). Silent unless the ringer is in normal mode. */
object Squelch {
    @Volatile private var pcm: ShortArray? = null
    fun samples(rate: Int = 22050): ShortArray {
        pcm?.let { return it }
        val n = (rate * 0.28).toInt(); val out = ShortArray(n); val rnd = java.util.Random(3)
        // 2-pole band-pass around 2.4 kHz (RBJ biquad), then a fast decay envelope.
        val f0 = 2400.0; val q = 0.8; val w0 = 2 * PI * f0 / rate; val al = sin(w0) / (2 * q)
        val b0 = al; val b2 = -al; val a0 = 1 + al; val a1 = -2 * cos(w0); val a2 = 1 - al
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in 0 until n) { val x = rnd.nextDouble() * 2 - 1
            val y = (b0 * x + b2 * x2 - a1 * y1 - a2 * y2) / a0; x2 = x1; x1 = x; y2 = y1; y1 = y
            val env = exp(-i.toDouble() / n * 4) * minOf(1.0, i / (rate * 0.004))
            out[i] = (y * env * 0.5 * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort() }
        pcm = out; return out
    }
    fun play(ctx: android.content.Context) {
        val am = ctx.getSystemService(android.media.AudioManager::class.java) ?: return
        if (am.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL) return
        Thread {
            runCatching {
                val data = samples(); val rate = 22050
                val tr = android.media.AudioTrack.Builder()
                    .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(data.size * 2).setTransferMode(android.media.AudioTrack.MODE_STATIC).build()
                tr.write(data, 0, data.size); tr.play(); Thread.sleep(400); tr.release()
            }
        }.start()
    }
}
