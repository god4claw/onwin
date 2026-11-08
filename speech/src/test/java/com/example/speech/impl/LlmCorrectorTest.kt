package com.example.speech.impl

import com.example.speech.api.TextGenerator
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmCorrectorTest {

    private class FakeGenerator(
        private val response: String = "",
        private val delayMs: Long = 0,
        private val failure: Throwable? = null,
    ) : TextGenerator {
        var lastPrompt: String? = null
        var loaded = false

        override suspend fun load() {
            loaded = true
        }

        override fun release() = Unit

        override suspend fun generate(prompt: String, maxTokens: Int): String {
            lastPrompt = prompt
            if (delayMs > 0) delay(delayMs)
            failure?.let { throw it }
            return response
        }
    }

    @Test
    fun `small fix from the model is applied`() = runBlocking {
        val corrector = LlmCorrector(FakeGenerator("<fixed>привет как дела</fixed>"))

        assertEquals("привет как дела", corrector.correct("привет как дила"))
    }

    @Test
    fun `rewrite from the model is rejected`() = runBlocking {
        val corrector = LlmCorrector(
            FakeGenerator("<fixed>Здравствуйте, рад вас видеть, как ваши дела сегодня?</fixed>"),
        )

        assertEquals("привет как дила", corrector.correct("привет как дила"))
    }

    @Test
    fun `numbers survive the round trip`() = runBlocking {
        val generator = FakeGenerator("<fixed>в [[0]] году рост [[1]]</fixed>")
        val corrector = LlmCorrector(generator)

        assertEquals("в 2026 году рост 5 %", corrector.correct("в 2026 году рост 5 %"))
        assertTrue(generator.lastPrompt!!.contains("[[0]]"))
        assertFalse(generator.lastPrompt!!.contains("2026"))
    }

    @Test
    fun `lost placeholder rejects the answer`() = runBlocking {
        val corrector = LlmCorrector(FakeGenerator("<fixed>в [[0]] году рост</fixed>"))

        assertEquals("в 2026 году рост 5 %", corrector.correct("в 2026 году рост 5 %"))
    }

    @Test
    fun `model failure falls back to the original text`() = runBlocking {
        val corrector = LlmCorrector(FakeGenerator(failure = IllegalStateException("OOM")))

        assertEquals("привет как дила", corrector.correct("привет как дила"))
    }

    @Test
    fun `slow model falls back on the time budget`() = runBlocking {
        val corrector = LlmCorrector(FakeGenerator("<fixed>привет как дела</fixed>", delayMs = 500), budgetMs = 50)

        assertEquals("привет как дила", corrector.correct("привет как дила"))
    }

    @Test
    fun `answer without the tag is still parsed`() = runBlocking {
        val corrector = LlmCorrector(FakeGenerator("привет как дела"))

        assertEquals("привет как дела", corrector.correct("привет как дила"))
    }

    @Test
    fun `html line breaks from the model never reach the transcript`() = runBlocking {
        val source = "привет как дила"
        for (answer in listOf(
            "<fixed>привет как дела<br></fixed>",
            "<br>привет как дела</br>",
            "<br>\nпривет как дела",
            "<fixed>привет <br/> как дела</fixed>",
        )) {
            assertEquals(answer, "привет как дела", LlmCorrector(FakeGenerator(answer)).correct(source))
        }
    }

    @Test
    fun `long phrase with leftover tags is cleaned, not kept`() = runBlocking {
        // Раньше от <br></br> после нормализации оставалось «br br» — меньше 10 % длинной фразы,
        // и гард пропускал теги в расшифровку.
        val source = "мне было важно увидеть экран и оценить качество сборки перед покупкой телефона"
        val corrector = LlmCorrector(FakeGenerator("<fixed><br>$source</br></fixed>"))

        assertEquals(source, corrector.correct(source))
    }

    @Test
    fun `quoted answer and service prefix are stripped`() = runBlocking {
        val corrector = LlmCorrector(FakeGenerator("Исправленный текст: «привет как дела»"))

        assertEquals("привет как дела", corrector.correct("привет как дила"))
    }

    @Test
    fun `blank input is returned as is without calling the model`() = runBlocking {
        val generator = FakeGenerator("<fixed>что-то</fixed>")
        val corrector = LlmCorrector(generator)

        assertEquals("   ", corrector.correct("   "))
        assertNull(generator.lastPrompt)
    }

    @Test
    fun `load is delegated to the generator`() = runBlocking {
        val generator = FakeGenerator()
        LlmCorrector(generator).load()

        assertTrue(generator.loaded)
    }

    @Test
    fun `prompt asks for a fixed tag and forbids rewriting`() {
        val prompt = LlmCorrector.buildPrompt("привет как дила")

        assertTrue(prompt.contains("<fixed>"))
        assertTrue(prompt.contains("привет как дила"))
        assertTrue(prompt.contains("Не добавляй"))
    }
}
