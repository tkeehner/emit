package com.example.emit.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import kotlin.math.*

/**
 * Holiday. Pumpkin orange + witch purple on a near-black candle-lit night; spooky side icons (bats, cobwebs,
 * candles, tombstones, skulls) drift down the screen edges, and every contact gets one of five shaded characters,
 * fixed per contact. Manual only, and it matches Beacon's holiday theme. Original art only.
 */
val HolidayTheme = EmitTheme(
    id = "holiday",
    name = "Holiday",
    blurb = "candle-lit night, drifting bats and cobwebs, a character for every contact",
    palette = ThemePalette(
        bg = 0xFF0A0608, s1 = 0xFF160D0A, s2 = 0xFF21140E, line = 0xFF3A2416, line2 = 0xFF553420,
        primary = 0xFFFF8C1E, body = 0xFFFFE2C8, dim = 0xFFC99A72, faint = 0xFFB0866A, onPrimary = 0xFF140A04,
        accent2 = 0xFFB57EF0, accent3 = 0xFFFFD35A,
        outBg = 0xFF2A1406, outLine = 0xFF7A3E12, outLabel = 0xFFFFB066,
        inBg = 0xFF1A1024, inLine = 0xFF4A3070, sent = 0xFFFFF0E0, incoming = 0xFFF0E6FF,
        danger = 0xFFFF6B6B, warnBg = 0xFF1E1006, warnLine = 0xFF5A3010,
        primeBg = 0xFF160D0A, unread = 0xFFFFE2C8, selBg = 0xFF2E1808, toggleOn = 0xFF2E1808, toggleOff = 0xFF21140E,
        rowLine = 0xFF26170F, scrim = 0xCC0A0608, scrimStrong = 0xDD0A0608, mediaScrim = 0xB30A0608,
        mark = 0x33FF8C1E, searchBg = 0xFFFFD35A, searchFg = 0xFF140A04,
        glitchA = 0x55FF8C1E, glitchB = 0x55B57EF0, glitchScan = 0x30000000, crtScan = 0x30000000, keyDown = 0xFF140A04,
    ),
    corners = CornerStyle.ROUNDED,
    rain = RainStyle.HOLIDAY,
    avatar = AvatarStyle.HOLIDAY,
    bodyFont = FontId.MONO,
    displayFont = FontId.MONO,
    keyFont = FontId.MONO,
    keyStyle = KeyStyle.NEON,
    fixedBubbles = true,
    titleGlow = true,
    scanlinesAlways = false,
    ambientGlitch = false,
    swatches = listOf(0xFF0A0608, 0xFF21140E, 0xFFFF8C1E, 0xFFB57EF0, 0xFFFFD35A),
    chrome = ThemeChrome(
        inbox = "INBOX", settings = "settings", about = "about", prime = "prime", link = "link",
        dayPrefix = "", uppercaseNames = false, sectionSlash = false, rainLabel = "drifting spooks", fab = "+ new chat",
    ),
)

enum class HoliChar { PUMPKIN, GHOST, CAT, HAT, SKULL }
enum class Spook { BAT, COBWEB, CANDLE, TOMBSTONE, SKULL }

/** Pure holiday helpers (JVM-testable). */
object Holi {
    /** Stable per contact: SHA-256 of the normalized address, mod 5. Same person, same character, every install. */
    fun charFor(addr: String): HoliChar {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(Badge.normalize(addr).toByteArray())
        val v = ((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF)
        return HoliChar.entries[v % HoliChar.entries.size]
    }
    /** Side-icon layout: deterministic, hugging the left and right edges (never under the text column). */
    fun spooks(n: Int, seed: Long = 31): List<FloatArray> { val r = java.util.Random(seed)
        return List(n) { i -> val left = i % 2 == 0; floatArrayOf(if (left) r.nextFloat() * 0.13f else 0.87f + r.nextFloat() * 0.13f,
            r.nextFloat(), (i % Spook.entries.size).toFloat(), 0.5f + r.nextFloat() * 0.5f, r.nextFloat() * 6.28f) } }
}

private val HO = Color(0xFFFF8C1E); private val HP = Color(0xFFB57EF0); private val HY = Color(0xFFFFD35A); private val HB = Color(0xFFFFE2C8)

/** Faint drifting side icons. Static frame when reduce-motion is on. */
@Composable
fun HolidaySpooks(modifier: Modifier, alpha: Float) {
    var tick by remember { mutableLongStateOf(0L) }
    val still = rememberReduceMotion()
    LaunchedEffect(still) { while (!still) { delay(80); tick++ } }
    val items = remember { Holi.spooks(14) }
    val a = (alpha * 2.2f).coerceIn(0f, 0.7f)
    Box(modifier.fillMaxSize().alpha(a).drawBehind {
        val w = size.width; val h = size.height; val t = tick * 0.08f
        for (s in items) {
            val k = Spook.entries[s[2].toInt()]; val sp = s[3]
            val y = ((s[1] * (h + 120)) + t * 9f * sp * density) % (h + 120f) - 60f
            val x = s[0] * w + sin(t * 0.6f + s[4]) * 6f * density
            spook(k, Offset(x, y), 11f * density * (0.8f + sp * 0.4f), t + s[4])
        }
    })
}

fun DrawScope.spook(k: Spook, c: Offset, s: Float, t: Float) {
    when (k) {
        Spook.BAT -> { val flap = sin(t * 3f) * 0.35f
            val p = Path().apply { moveTo(c.x, c.y); quadraticTo(c.x - s * 0.5f, c.y - s * (0.6f + flap), c.x - s * 1.2f, c.y - s * 0.2f)
                quadraticTo(c.x - s * 0.8f, c.y + s * 0.05f, c.x - s * 0.7f, c.y + s * 0.3f); quadraticTo(c.x - s * 0.35f, c.y + s * 0.05f, c.x, c.y + s * 0.3f)
                quadraticTo(c.x + s * 0.35f, c.y + s * 0.05f, c.x + s * 0.7f, c.y + s * 0.3f); quadraticTo(c.x + s * 0.8f, c.y + s * 0.05f, c.x + s * 1.2f, c.y - s * 0.2f)
                quadraticTo(c.x + s * 0.5f, c.y - s * (0.6f + flap), c.x, c.y); close() }
            drawPath(p, HP); drawCircle(HY, s * 0.06f, Offset(c.x - s * 0.1f, c.y + s * 0.08f)); drawCircle(HY, s * 0.06f, Offset(c.x + s * 0.1f, c.y + s * 0.08f)) }
        Spook.COBWEB -> { val st = Stroke(0.8f * density)
            for (i in 0 until 6) { val an = i * PI.toFloat() / 3f; drawLine(HB.copy(alpha = 0.7f), c, Offset(c.x + cos(an) * s, c.y + sin(an) * s), strokeWidth = 0.8f * density) }
            for (r in 1..3) { val rr = s * r / 3f; val p = Path(); for (i in 0..6) { val an = i * PI.toFloat() / 3f; val px = c.x + cos(an) * rr; val py = c.y + sin(an) * rr; if (i == 0) p.moveTo(px, py) else p.lineTo(px, py) }; drawPath(p, HB.copy(alpha = 0.6f), style = st) } }
        Spook.CANDLE -> { val fl = 1f + sin(t * 7f) * 0.15f
            drawRect(HB.copy(alpha = 0.85f), Offset(c.x - s * 0.22f, c.y - s * 0.2f), Size(s * 0.44f, s * 0.9f))
            drawCircle(HO.copy(alpha = 0.25f), s * 0.55f * fl, Offset(c.x, c.y - s * 0.45f))
            drawOval(HY, Offset(c.x - s * 0.1f, c.y - s * (0.2f + 0.45f * fl)), Size(s * 0.2f, s * 0.42f * fl)) }
        Spook.TOMBSTONE -> { val p = Path().apply { moveTo(c.x - s * 0.5f, c.y + s * 0.6f); lineTo(c.x - s * 0.5f, c.y - s * 0.1f)
                cubicTo(c.x - s * 0.5f, c.y - s * 0.75f, c.x + s * 0.5f, c.y - s * 0.75f, c.x + s * 0.5f, c.y - s * 0.1f); lineTo(c.x + s * 0.5f, c.y + s * 0.6f); close() }
            drawPath(p, Color(0xFF6E6478)); drawLine(Color(0xFF2A2230), Offset(c.x, c.y - s * 0.3f), Offset(c.x, c.y + s * 0.2f), strokeWidth = s * 0.1f)
            drawLine(Color(0xFF2A2230), Offset(c.x - s * 0.18f, c.y - s * 0.12f), Offset(c.x + s * 0.18f, c.y - s * 0.12f), strokeWidth = s * 0.1f) }
        Spook.SKULL -> { drawCircle(HB, s * 0.5f, Offset(c.x, c.y - s * 0.1f)); drawRect(HB, Offset(c.x - s * 0.28f, c.y + s * 0.1f), Size(s * 0.56f, s * 0.35f))
            drawCircle(Color(0xFF0A0608), s * 0.14f, Offset(c.x - s * 0.18f, c.y - s * 0.1f)); drawCircle(Color(0xFF0A0608), s * 0.14f, Offset(c.x + s * 0.18f, c.y - s * 0.1f)) }
    }
}

/** Shaded mini character for a contact. Battery saver drops the gradient shading, specular and rim (flat fill). */
@Composable
fun HolidayBadge(key: String, size: Dp, group: Boolean = false) {
    val addr = remember(key) { key.split(',').firstOrNull { it.isNotEmpty() } ?: key }
    val ch = remember(addr) { Holi.charFor(addr) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val shade = remember { !((ctx.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager)?.isPowerSaveMode ?: false) }
    Box(Modifier.size(size).semantics { contentDescription = if (group) "group avatar" else "contact avatar" }.drawWithCache {
        onDrawBehind { holidayChar(ch, shade); if (group) drawCircle(HP, this.size.minDimension * 0.12f, Offset(this.size.width * 0.86f, this.size.height * 0.16f)) }
    })
}

private fun body(ch: HoliChar) = when (ch) { HoliChar.PUMPKIN -> Color(0xFFFF8C1E); HoliChar.GHOST -> Color(0xFFF2ECFF); HoliChar.CAT -> Color(0xFF3A2E48)
    HoliChar.HAT -> Color(0xFF5B3B8A); HoliChar.SKULL -> Color(0xFFEFE6D4) }
private fun dark(c: Color) = Color(c.red * 0.45f, c.green * 0.45f, c.blue * 0.45f)
private fun lite(c: Color) = Color(c.red + (1 - c.red) * 0.5f, c.green + (1 - c.green) * 0.5f, c.blue + (1 - c.blue) * 0.5f)

fun DrawScope.holidayChar(ch: HoliChar, shade: Boolean) {
    val w = size.width; val R = w * 0.40f; val c = Offset(w / 2, size.height / 2 + w * 0.04f); val b = body(ch)
    val fill: Brush = if (shade) Brush.radialGradient(listOf(lite(b), b, dark(b)), Offset(c.x - R * 0.4f, c.y - R * 0.45f), R * 1.7f) else Brush.linearGradient(listOf(b, b))
    val eye = when (ch) { HoliChar.PUMPKIN -> HY; HoliChar.CAT -> Color(0xFFB6FF5A); HoliChar.HAT -> HY; else -> Color(0xFF1A1020) }
    when (ch) {
        HoliChar.PUMPKIN -> {
            drawRect(Color(0xFF4E7A2A), Offset(c.x - R * 0.08f, c.y - R * 1.15f), Size(R * 0.16f, R * 0.35f))
            drawOval(fill, Offset(c.x - R * 1.08f, c.y - R * 0.9f), Size(R * 2.16f, R * 1.85f))
            if (shade) for (k in listOf(-0.45f, 0f, 0.45f)) drawOval(dark(b).copy(alpha = 0.35f), Offset(c.x + R * k - R * 0.06f, c.y - R * 0.85f), Size(R * 0.12f, R * 1.7f))
            tri(eye, c.x - R * 0.38f, c.y - R * 0.18f, R * 0.22f); tri(eye, c.x + R * 0.38f, c.y - R * 0.18f, R * 0.22f)
            drawPath(Path().apply { moveTo(c.x - R * 0.55f, c.y + R * 0.25f); lineTo(c.x - R * 0.25f, c.y + R * 0.4f); lineTo(c.x, c.y + R * 0.28f); lineTo(c.x + R * 0.25f, c.y + R * 0.4f); lineTo(c.x + R * 0.55f, c.y + R * 0.25f); lineTo(c.x, c.y + R * 0.62f); close() }, eye)
        }
        HoliChar.GHOST -> {
            val p = Path().apply { moveTo(c.x - R * 0.85f, c.y + R * 0.9f); lineTo(c.x - R * 0.85f, c.y - R * 0.1f)
                cubicTo(c.x - R * 0.85f, c.y - R * 1.15f, c.x + R * 0.85f, c.y - R * 1.15f, c.x + R * 0.85f, c.y - R * 0.1f); lineTo(c.x + R * 0.85f, c.y + R * 0.9f)
                for (i in 0 until 4) { val x1 = c.x + R * 0.85f - (i + 0.5f) * R * 0.425f; val x2 = c.x + R * 0.85f - (i + 1) * R * 0.425f; quadraticTo(x1, c.y + (if (i % 2 == 0) R * 0.6f else R * 1.05f), x2, c.y + R * 0.9f) }
                close() }
            drawPath(p, fill)
            drawOval(eye, Offset(c.x - R * 0.42f, c.y - R * 0.3f), Size(R * 0.24f, R * 0.34f)); drawOval(eye, Offset(c.x + R * 0.18f, c.y - R * 0.3f), Size(R * 0.24f, R * 0.34f))
            drawOval(eye, Offset(c.x - R * 0.12f, c.y + R * 0.18f), Size(R * 0.24f, R * 0.28f))
        }
        HoliChar.CAT -> {
            tri(b, c.x - R * 0.55f, c.y - R * 0.85f, R * 0.4f, up = true); tri(b, c.x + R * 0.55f, c.y - R * 0.85f, R * 0.4f, up = true)
            drawCircle(fill, R * 0.92f, c)
            drawOval(eye, Offset(c.x - R * 0.55f, c.y - R * 0.3f), Size(R * 0.34f, R * 0.3f)); drawOval(eye, Offset(c.x + R * 0.21f, c.y - R * 0.3f), Size(R * 0.34f, R * 0.3f))
            drawLine(Color.Black, Offset(c.x - R * 0.38f, c.y - R * 0.28f), Offset(c.x - R * 0.38f, c.y - R * 0.02f), strokeWidth = R * 0.07f)
            drawLine(Color.Black, Offset(c.x + R * 0.38f, c.y - R * 0.28f), Offset(c.x + R * 0.38f, c.y - R * 0.02f), strokeWidth = R * 0.07f)
            tri(Color(0xFFFF8FB0), c.x, c.y + R * 0.15f, R * 0.1f)
            for (s in listOf(-1f, 1f)) for (k in listOf(-0.08f, 0.08f)) drawLine(HB.copy(alpha = 0.7f), Offset(c.x + s * R * 0.25f, c.y + R * 0.25f), Offset(c.x + s * R * 0.95f, c.y + R * (0.22f + k * 2)), strokeWidth = R * 0.04f)
        }
        HoliChar.HAT -> {
            drawOval(dark(b), Offset(c.x - R * 1.05f, c.y + R * 0.45f), Size(R * 2.1f, R * 0.45f))
            val cone = Path().apply { moveTo(c.x - R * 0.7f, c.y + R * 0.6f); quadraticTo(c.x - R * 0.2f, c.y - R * 0.6f, c.x + R * 0.15f, c.y - R * 1.05f)
                quadraticTo(c.x + R * 0.5f, c.y - R * 1.1f, c.x + R * 0.75f, c.y - R * 0.8f); quadraticTo(c.x + R * 0.35f, c.y - R * 0.7f, c.x + R * 0.3f, c.y - R * 0.5f); lineTo(c.x + R * 0.7f, c.y + R * 0.6f); close() }
            drawPath(cone, fill)
            drawRect(HO, Offset(c.x - R * 0.62f, c.y + R * 0.28f), Size(R * 1.3f, R * 0.18f)); drawRect(HY, Offset(c.x - R * 0.12f, c.y + R * 0.25f), Size(R * 0.24f, R * 0.24f), style = Stroke(R * 0.06f))
            drawOval(eye, Offset(c.x - R * 0.35f, c.y - R * 0.15f), Size(R * 0.22f, R * 0.26f)); drawOval(eye, Offset(c.x + R * 0.1f, c.y - R * 0.15f), Size(R * 0.22f, R * 0.26f))
        }
        HoliChar.SKULL -> {
            drawCircle(fill, R * 0.85f, Offset(c.x, c.y - R * 0.12f)); drawRoundRect(fill, Offset(c.x - R * 0.5f, c.y + R * 0.4f), Size(R * 1.0f, R * 0.5f), androidx.compose.ui.geometry.CornerRadius(R * 0.15f))
            drawOval(Color(0xFF1A1020), Offset(c.x - R * 0.58f, c.y - R * 0.35f), Size(R * 0.4f, R * 0.42f)); drawOval(Color(0xFF1A1020), Offset(c.x + R * 0.18f, c.y - R * 0.35f), Size(R * 0.4f, R * 0.42f))
            drawCircle(HO, R * 0.07f, Offset(c.x - R * 0.38f, c.y - R * 0.14f)); drawCircle(HO, R * 0.07f, Offset(c.x + R * 0.38f, c.y - R * 0.14f))
            tri(Color(0xFF1A1020), c.x, c.y + R * 0.22f, R * 0.1f, up = true)
            for (i in -2..2) drawLine(Color(0xFF8A7F70), Offset(c.x + i * R * 0.17f, c.y + R * 0.55f), Offset(c.x + i * R * 0.17f, c.y + R * 0.85f), strokeWidth = R * 0.04f)
        }
    }
    if (shade) { // specular + rim light
        drawCircle(Color.White.copy(alpha = 0.35f), R * 0.13f, Offset(c.x - R * 0.42f, c.y - R * 0.45f))
        drawArc(HP.copy(alpha = 0.55f), -20f, 80f, false, Offset(c.x - R * 0.9f, c.y - R * 0.9f), Size(R * 1.8f, R * 1.8f), style = Stroke(R * 0.07f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.tri(col: Color, x: Float, y: Float, s: Float, up: Boolean = true) {
    drawPath(Path().apply { if (up) { moveTo(x, y - s); lineTo(x + s, y + s * 0.7f); lineTo(x - s, y + s * 0.7f) } else { moveTo(x, y + s); lineTo(x + s, y - s * 0.7f); lineTo(x - s, y - s * 0.7f) }; close() }, col)
}

/**
 * Theme sync with Beacon (com.tyler.primelauncher). Both apps define a signature|knownSigner permission that only the
 * other app's signing cert can hold, and protect their exported receiver with it; broadcasts are explicit-component.
 * A timestamp guard (newer wins) plus "apply without re-sending" stops ping-pong.
 */
object ThemeSync {
    const val ACTION = "com.tyler.themesync.SET"
    const val PEER_PKG = "com.tyler.primelauncher"; const val PEER_RX = "com.tyler.primelauncher.ThemeSyncReceiver"
    /** receiverPermission = OUR permission: only Beacon (knownCerts) holds it, so nobody else can listen in. */
    const val PEER_PERM = "com.example.emit.permission.THEME_SYNC"
    /** Beacon theme ids map 1:1 (matrix, neon, odst, holiday). */
    fun known(id: String) = Themes.all.any { it.id == id }
    /** Pure decision: apply only a known, different, newer theme. */
    fun shouldApply(cur: String, curTs: Long, theme: String?, ts: Long) = theme != null && known(theme) && theme != cur && ts > curTs
    fun send(c: android.content.Context, theme: String, ts: Long) = runCatching {
        c.sendBroadcast(android.content.Intent(ACTION).setClassName(PEER_PKG, PEER_RX).putExtra("theme", theme).putExtra("ts", ts), PEER_PERM)
    }
}

class ThemeSyncReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(c: android.content.Context, i: android.content.Intent) {
        if (i.action != ThemeSync.ACTION) return
        val theme = i.getStringExtra("theme"); val ts = i.getLongExtra("ts", 0L)
        val sp = c.getSharedPreferences("ui_prefs", 0)
        if (ThemeSync.shouldApply(ThemePref.load(c), sp.getLong("theme_ts", 0L), theme, ts)) ThemePref.set(c, theme!!, sync = false, ts = ts)
    }
}
