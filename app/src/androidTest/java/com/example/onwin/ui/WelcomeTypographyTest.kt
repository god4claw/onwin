package com.example.onwin.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.onwin.R
import com.example.onwin.ui.screens.welcome.WelcomeScreen
import com.example.onwin.ui.screens.welcome.WelcomeState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WelcomeTypographyTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun welcomeKeepsPlayfairHeadingsAndUsesGolosForCopy() {
        compose.setContent { WelcomeScreen(state = WelcomeState.Ready, onStart = {}) }

        compose.onNodeWithText("Расшифруй\nсвоё аудио").assertFont(R.font.playfair_display)
        compose.onNodeWithText("Лучшая расшифровка\nБез интернета").assertFont(R.font.playfair_display)
        compose.onNodeWithText("Получите качественный текст из аудио по спикерам и таймкодам")
            .assertFont(R.font.golos_text)
        compose.onNodeWithText("Начать").assertFont(R.font.golos_text)
    }
}

internal fun SemanticsNodeInteraction.assertFont(fontResId: Int) {
    val layouts = mutableListOf<TextLayoutResult>()
    performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    assertEquals(FontFamily(Font(fontResId)), layouts.single().layoutInput.style.fontFamily)
}
