package com.example.emit.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Guards the three keyboard regressions against the 0.1.45 Matrix keyboard. */
class KeyboardRegressionTest {
    private val src = File("src/main/java/com/example/emit/ui/MatrixKeyboard.kt").readText()

    @Test fun matrixSuggestionFrameFillsItsThird() {
        val strip = src.substringAfter("fun SuggestionStrip(").substringBefore("fun SettingsRow(")
        assertTrue(
            "Matrix center border has to stay on the weighted slot",
            ".then(if (!neon && center && w != null) Modifier.border" in strip,
        )
        assertTrue("Neon may chip the word", "if (neon && center && w != null)" in strip)
        val matrixBranch = strip.substringAfter("} else {").substringBefore("if (i < 2)")
        assertFalse("Matrix text must not sit in a hugging padded box", "horizontal = 10.dp" in matrixBranch || ".padding(" in matrixBranch)
    }

    @Test fun matrixPopupDoesNotReadTheGlowToggle() {
        val face = src.substringAfter("fun RowScope.MatrixKeyFace(").substringBefore("fun RowScope.NeonKeyFace(")
        assertTrue("if (down && k.preview)" in face)
        assertFalse(face.contains("keyGlowEnabled"))
    }

    @Test fun neonKeyFaceDoesNotRecaseLetters() {
        val face = src.substringAfter("fun RowScope.NeonKeyFace(")
        assertTrue("val label = k.label" in face)
        assertFalse(face.contains(".uppercase()"))
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h420dp-xxhdpi")
class KeyboardPreviewTest {
    @get:Rule val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun neonLettersFollowShift() {
        show(NeonCityTheme, "hello")
        rule.onNodeWithText("q").assertIsDisplayed()
        rule.onNodeWithText("Q").assertDoesNotExist()
    }

    @Test fun neonCapitalizesAtASentenceStart() {
        show(NeonCityTheme, "")
        rule.onNodeWithText("Q").assertIsDisplayed()
        rule.onNodeWithText("q").assertDoesNotExist()
    }

    @Test fun popupStaysWhenKeyGlowIsOff() {
        EffectPrefs.keyGlow.value = false
        show(MatrixTheme, "hello")
        rule.onNodeWithText("q").performTouchInput { down(center) }
        rule.waitForIdle()
        rule.onNode(hasTestTag("key-preview") and hasAnyDescendant(hasText("q")), useUnmergedTree = true).assertExists()
    }

    @Test fun neonPreviewStaysAndKeepsCaseWhenGlowIsOff() {
        EffectPrefs.keyGlow.value = false
        show(NeonCityTheme, "hello")
        rule.onNodeWithText("q").performTouchInput { down(center) }
        rule.waitForIdle()
        rule.onNode(hasTestTag("key-preview") and hasAnyDescendant(hasText("q")), useUnmergedTree = true).assertExists()
        rule.onNode(hasTestTag("key-preview") and hasAnyDescendant(hasText("Q")), useUnmergedTree = true).assertDoesNotExist()
    }

    private fun show(theme: EmitTheme, text: String) {
        // Reduced motion stops the rain tick, so waitForIdle can return.
        android.provider.Settings.Global.putFloat(
            rule.activity.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            0f,
        )
        EffectPrefs.glitch.value = false
        val kb = KbController()
        kb.showSuggestions = false
        kb.target = KbTarget(
            id = "t", phone = false, singleLine = true,
            get = { TextFieldValue(text, TextRange(text.length)) },
            set = {},
        )
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides theme) { MatrixKeyboard(kb) }
        }
        rule.waitForIdle()
    }
}
