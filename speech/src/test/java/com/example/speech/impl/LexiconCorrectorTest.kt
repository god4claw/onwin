package com.example.speech.impl

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LexiconCorrectorTest {

    private val corrector = LexiconCorrector(
        mapOf(
            "иванофф" to "Иванов",
            "кубернетес" to "Kubernetes",
            "телеграмм" to "Telegram",
        ),
    )

    private fun fix(text: String): String = runBlocking { corrector.correct(text) }

    @Test
    fun `known misspelling is replaced`() {
        assertEquals("Kubernetes в проде", fix("кубернетес в проде"))
    }

    @Test
    fun `dictionary capitalization is kept for names`() {
        assertEquals("Иванов пришёл", fix("иванофф пришёл"))
    }

    @Test
    fun `capitalized source stays capitalized`() {
        assertEquals("Иванов пришёл", fix("Иванофф пришёл"))
    }

    @Test
    fun `all caps source stays all caps`() {
        assertEquals("ИВАНОВ", fix("ИВАНОФФ"))
    }

    @Test
    fun `word is matched whole, not inside another word`() {
        assertEquals("иваноффский стиль", fix("иваноффский стиль"))
    }

    @Test
    fun `words outside the lexicon are untouched`() {
        assertEquals("обычный текст без замен", fix("обычный текст без замен"))
    }

    @Test
    fun `single letter keys are ignored`() {
        val tiny = LexiconCorrector(mapOf("я" to "вы"))

        assertEquals("я пришёл", runBlocking { tiny.correct("я пришёл") })
    }

    @Test
    fun `hyphenated words are treated as one word`() {
        val hyphenated = LexiconCorrector(mapOf("интернет-магазин" to "веб-магазин"))

        assertEquals("веб-магазин открыт", runBlocking { hyphenated.correct("интернет-магазин открыт") })
    }
}
