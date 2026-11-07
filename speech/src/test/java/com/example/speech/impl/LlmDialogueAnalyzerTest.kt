package com.example.speech.impl

import com.example.speech.api.Segment
import com.example.speech.api.TextGenerator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmDialogueAnalyzerTest {

    /** Генератор, который отвечает по сценарию и запоминает промпты. */
    private class FakeGenerator(private val answer: suspend (String) -> String) : TextGenerator {
        val prompts = mutableListOf<String>()

        override suspend fun load() = Unit

        override suspend fun generate(prompt: String, maxTokens: Int): String {
            prompts += prompt
            return answer(prompt)
        }

        override fun release() = Unit
    }

    private fun dialogue(vararg texts: String): List<Segment> =
        texts.mapIndexed { index, text -> Segment(index % 2, index * 1_000L, index * 1_000L + 900, text) }

    @Test
    fun analyze_returnsNamesAndSummary() = runTest {
        val generator = FakeGenerator { prompt ->
            if (prompt.contains("Ты читаешь расшифровку")) "1: Анна\n2: Сергей" else "Обсудили сроки и договорились о встрече в пятницу."
        }
        val analyzer = LlmDialogueAnalyzer(generator)

        val notes = analyzer.analyze(dialogue("Меня зовут Анна.", "А я Сергей."))

        assertEquals(mapOf(0 to "Анна", 1 to "Сергей"), notes?.speakerNames)
        assertEquals("Обсудили сроки и договорились о встрече в пятницу.", notes?.summary)
        assertEquals(2, generator.prompts.size)
    }

    @Test
    fun analyze_collectsNamesFromAllChunks() = runTest {
        val generator = FakeGenerator { prompt ->
            when {
                prompt.contains("из отдела продаж") -> "1: Анна"
                prompt.contains("разработчик") -> "2: Сергей"
                else -> "Коротко поговорили о работе и всё согласовали."
            }
        }
        // Бюджет 40 символов — реплики разъезжаются по разным кускам.
        val analyzer = LlmDialogueAnalyzer(generator, maxCharsPerChunk = 40)

        val notes = analyzer.analyze(dialogue("Меня зовут Анна, я из отдела продаж.", "А я Сергей, разработчик.", "Договорились."))

        assertEquals(mapOf(0 to "Анна", 1 to "Сергей"), notes?.speakerNames)
    }

    @Test
    fun analyze_dropsConflictingNamesWithEqualVotes() = runTest {
        val generator = FakeGenerator { prompt ->
            when {
                prompt.contains("веду проект") -> "1: Анна"
                prompt.contains("давайте начнём") -> "1: Мария"
                prompt.contains("Ты читаешь расшифровку") -> ""
                else -> "Обсудили планы на неделю и распределили задачи по проекту."
            }
        }
        val analyzer = LlmDialogueAnalyzer(generator, maxCharsPerChunk = 40)

        val notes = analyzer.analyze(dialogue("Меня зовут Анна, я веду проект.", "Понятно, давайте начнём.", "Сегодня обсудим сроки."))

        assertTrue(notes == null || notes.speakerNames.isEmpty())
    }

    @Test
    fun analyze_keepsNameMentionedOnce() = runTest {
        val generator = FakeGenerator { prompt ->
            if (prompt.contains("Ты читаешь расшифровку")) "1: Анна" else "Обсудили сроки и договорились о встрече."
        }
        val analyzer = LlmDialogueAnalyzer(generator)

        val notes = analyzer.analyze(dialogue("Меня зовут Анна.", "Хорошо, до пятницы."))

        assertEquals(mapOf(0 to "Анна"), notes?.speakerNames)
    }

    @Test
    fun analyze_rejectsPlaceholdersAndInventedNumbers() = runTest {
        val generator = FakeGenerator { prompt ->
            if (prompt.contains("Ты читаешь расшифровку")) "1: Спикер 1\n2: неизвестно\n3: Иван2" else ""
        }
        val analyzer = LlmDialogueAnalyzer(generator)

        val notes = analyzer.analyze(dialogue("Привет.", "Привет."))

        assertNull(notes)
    }

    @Test
    fun analyze_rejectsEchoedTranscriptAsSummary() = runTest {
        val generator = FakeGenerator { prompt ->
            if (prompt.contains("Ты читаешь расшифровку")) "" else "Спикер 1: меня зовут Анна\nСпикер 2: очень приятно"
        }
        val analyzer = LlmDialogueAnalyzer(generator)

        assertNull(analyzer.analyze(dialogue("Меня зовут Анна.", "Очень приятно.")))
    }

    @Test
    fun analyze_survivesGeneratorFailureAndTimeout() = runTest {
        val failing = FakeGenerator { error("model crashed") }
        assertNull(LlmDialogueAnalyzer(failing).analyze(dialogue("Привет.")))

        val slow = FakeGenerator { kotlinx.coroutines.delay(10_000); "1: Анна" }
        assertNull(LlmDialogueAnalyzer(slow, budgetMs = 10).analyze(dialogue("Привет.")))
    }

    @Test
    fun analyze_returnsNullForBlankDialogue() = runTest {
        val generator = FakeGenerator { "1: Анна" }
        val analyzer = LlmDialogueAnalyzer(generator)

        assertNull(analyzer.analyze(emptyList()))
        assertNull(analyzer.analyze(dialogue("   ")))
        assertTrue(generator.prompts.isEmpty())
    }
}
