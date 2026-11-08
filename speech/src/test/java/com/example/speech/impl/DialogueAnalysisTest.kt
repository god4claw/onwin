package com.example.speech.impl

import com.example.speech.api.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerNameParserTest {

    @Test
    fun parsesNumberNameLines() {
        assertEquals(
            mapOf(0 to "Анна", 1 to "Сергей"),
            SpeakerNameParser.parse("1: Анна\n2: Сергей"),
        )
    }

    @Test
    fun toleratesDashesColonsAndQuotes() {
        assertEquals(
            mapOf(1 to "Иван Петров"),
            SpeakerNameParser.parse("- 2 — «Иван Петров»."),
        )
    }

    @Test
    fun rejectsPlaceholders() {
        assertFalse(SpeakerNameParser.isValidName("Спикер 1"))
        assertFalse(SpeakerNameParser.isValidName("неизвестно"))
        assertFalse(SpeakerNameParser.isValidName("говорящий"))
        assertFalse(SpeakerNameParser.isValidName("нет"))
    }

    @Test
    fun rejectsDigitsAndPunctuationInName() {
        assertFalse(SpeakerNameParser.isValidName("Иван2"))
        assertFalse(SpeakerNameParser.isValidName("Анна!"))
        assertFalse(SpeakerNameParser.isValidName("А"))
        assertTrue(SpeakerNameParser.isValidName("Анна-Мария"))
    }

    @Test
    fun ignoresLinesWithoutNumber() {
        assertTrue(SpeakerNameParser.parse("Меня зовут Анна\nСпикер 2").isEmpty())
    }
}

class SummarySanitizerTest {

    @Test
    fun keepsNormalSummary() {
        val text = "Обсудили сроки проекта. Решили выпустить релиз в пятницу. Осталось проверить документацию."
        assertEquals(text, SummarySanitizer.clean(text))
    }

    @Test
    fun stripsBulletsAndJoinsLines() {
        assertEquals(
            "Обсудили сроки. Решили выпустить релиз в пятницу.",
            SummarySanitizer.clean("- Обсудили сроки.\n* Решили выпустить релиз в пятницу."),
        )
    }

    @Test
    fun rejectsTooShortEchoAndNonCyrillic() {
        assertEquals("", SummarySanitizer.clean(""))
        assertEquals("", SummarySanitizer.clean("Коротко."))
        assertEquals("", SummarySanitizer.clean("Спикер 1: обсудили сроки проекта и решили выпустить релиз."))
        assertEquals("", SummarySanitizer.clean("We discussed the schedule and agreed on the release date."))
    }

    @Test
    fun trimsLongSummaryAtSentenceBoundary() {
        val sentence = "Обсудили очень важный вопрос и договорились о следующем шаге. "
        val long = sentence.repeat(30)
        val cleaned = SummarySanitizer.clean(long)

        assertTrue(cleaned.length <= 800)
        assertTrue(cleaned.endsWith("."))
    }
}

class DialogueChunkerTest {

    private fun segment(index: Int, text: String) = Segment(0, index * 1_000L, index * 1_000L + 900, text)

    @Test
    fun keepsShortDialogueInOneChunk() {
        val chunks = DialogueChunker.chunk(listOf(segment(0, "Раз."), segment(1, "Два.")), maxChars = 1_000)
        assertEquals(1, chunks.size)
    }

    @Test
    fun splitsByBudgetWithoutLosingSegments() {
        val segments = (1..10).map { segment(it, "Реплика номер $it, довольно длинная.") }
        val chunks = DialogueChunker.chunk(segments, maxChars = 120)

        assertTrue(chunks.size > 1)
        assertEquals(segments, chunks.flatten())
    }

    @Test
    fun keepsOversizedSegmentAlone() {
        val segments = listOf(segment(0, "Коротко."), segment(1, "я".repeat(500)), segment(2, "Ещё."))
        val chunks = DialogueChunker.chunk(segments, maxChars = 100)

        assertEquals(listOf(listOf(segments[0]), listOf(segments[1]), listOf(segments[2])), chunks)
    }
}
