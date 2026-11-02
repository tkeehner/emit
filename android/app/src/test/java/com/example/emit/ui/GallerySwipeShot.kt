package com.example.emit.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import com.example.emit.data.MediaRef
import com.example.emit.data.Message
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Drives the 0.1.52 swipe viewer: swipe, arrow keys and the ‹ › buttons, checking the n/N counter.
 * Always runs; with -Dshot.out=<dir> it also writes a PNG per step for the preview.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h780dp-xxhdpi")
class GallerySwipeShot {
    @get:Rule val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val colors = intArrayOf(0xFF1B5E20.toInt(), 0xFF0D47A1.toInt(), 0xFF4A148C.toInt(), 0xFFB71C1C.toInt(), 0xFFE65100.toInt())
    private fun fake(part: Long): Bitmap {
        val b = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        c.drawColor(colors[(part % colors.size).toInt()])
        val p = Paint().apply { color = 0xFFFFFFFF.toInt(); textSize = 120f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
        c.drawText("photo $part", 400f, 340f, p); return b
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun swipeArrowsAndButtons() {
        val now = 1_798_000_000_000L
        val msgs = (1L..5L).map { i -> Message(i, 1, "5555550142", "", now + i * 60_000, outgoing = i % 2 == 0L, isMms = true, media = listOf(MediaRef(i, "image/jpeg"))) }
        val g = MediaGallery.build(msgs.reversed())
        ThemePref.id.value = "matrix"
        rule.setContent { CompositionLocalProvider(LocalBitmapOverride provides { u -> fake(u.lastPathSegment!!.toLong()) }) {
            CrtHost(boot = false) { GalleryBody(g, 2, labelOf = { if (it.outgoing) "you" else "Jordan" }, onSave = { { "saved" } }, onClose = {}) } } }
        rule.waitForIdle()
        fun has(t: String) = rule.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty()
        var step = 0
        fun shot(name: String) { snap("%02d-%s".format(step++, name)) }
        assertTrue(has("3/5")); shot("start-3of5")
        rule.onRoot().performTouchInput { swipeLeft() }; rule.waitForIdle()
        assertTrue(has("4/5")); shot("swipe-left-4of5")
        rule.onNodeWithContentDescription("next photo").performClick(); rule.waitForIdle()
        assertTrue(has("5/5")); shot("next-button-5of5-no-next")
        assertTrue(rule.onAllNodesWithContentDescription("next photo").fetchSemanticsNodes().isEmpty())
        rule.onRoot().performTouchInput { swipeLeft() }; rule.waitForIdle()
        assertTrue("no wrap past the last", has("5/5"))
        rule.onRoot().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionLeft) }; rule.waitForIdle()
        assertTrue(has("4/5")); shot("arrow-key-left-4of5")
        repeat(4) { rule.onRoot().performTouchInput { swipeRight() }; rule.waitForIdle() }
        assertTrue("no wrap before the first", has("1/5")); shot("swipe-right-1of5-no-prev")
        assertTrue(rule.onAllNodesWithContentDescription("previous photo").fetchSemanticsNodes().isEmpty())
    }

    private fun snap(name: String) {
        val dir = System.getProperty("shot.out") ?: return
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(bmp))
        File(dir).mkdirs(); File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
