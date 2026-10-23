package com.example.emit.ui
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the compose bar to a PNG when -Dshot.out=... is given; otherwise skipped. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h400dp-xxhdpi")
class ComposeBarShot {
    @get:Rule val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Test fun shot() {
        val out = System.getProperty("shot.out"); Assume.assumeTrue(out != null)
        rule.setContent {
            Column(Modifier.fillMaxWidth().background(Color.Black).padding(vertical = 8.dp)) {
                ComposeBar("I'm hopefully going to get the new text box working today so it reads well", {}, onAttach = {}, onSchedule = {}) {}
                ComposeBar("", {}, onAttach = {}, onSchedule = {}) {}
            }
        }
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val bmp = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        java.io.FileOutputStream(out!!).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
