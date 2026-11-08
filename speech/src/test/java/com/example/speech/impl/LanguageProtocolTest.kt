package com.example.speech.impl

import com.example.speech.api.ProtocolAction
import com.example.speech.api.TranscriptLanguage
import com.example.speech.api.detectTranscriptLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptLanguageTest {

    @Test
    fun detectsRussianEnglishAndMixed() {
        assertEquals(
            TranscriptLanguage.RU,
            detectTranscriptLanguage("Обсудили сроки проекта и решили выпустить релиз в пятницу."),
        )
        assertEquals(
            TranscriptLanguage.EN,
            detectTranscriptLanguage("We discussed the schedule and agreed on the release date."),
        )
        assertEquals(
            TranscriptLanguage.MIXED,
            detectTranscriptLanguage("Обсудили релиз, потом перешли на Kubernetes and Docker в проде."),
        )
        assertEquals(TranscriptLanguage.UNKNOWN, detectTranscriptLanguage("Да."))
    }
}

class ProtocolParserTest {

    private val russian = """
        КРАТКО:
        Обсудили релиз и перенесли его на 20 ноября.
        РЕШЕНИЯ:
        - Перенести релиз на 20 ноября
        - Обновить инструкцию
        ПОРУЧЕНИЯ:
        - Подготовить сборку | Игорь | 15 ноября
        - Закончить регресс | Марина | 18 ноября
    """.trimIndent()

    @Test
    fun parsesRussianSections() {
        val parsed = ProtocolParser.parse(russian, TranscriptLanguage.RU)

        assertEquals("Обсудили релиз и перенесли его на 20 ноября.", parsed?.summary)
        assertEquals(
            listOf("Перенести релиз на 20 ноября", "Обновить инструкцию"),
            parsed?.decisions,
        )
        assertEquals(
            listOf(
                ProtocolAction("Подготовить сборку", "Игорь", "15 ноября"),
                ProtocolAction("Закончить регресс", "Марина", "18 ноября"),
            ),
            parsed?.actions,
        )
    }

    @Test
    fun parsesEnglishSections() {
        val parsed = ProtocolParser.parse(
            """
            SUMMARY:
            The team moved the release to November 20.
            DECISIONS:
            - Move the release to November 20
            ACTIONS:
            - Prepare the build | Igor | November 15
            """.trimIndent(),
            TranscriptLanguage.EN,
        )

        assertEquals("The team moved the release to November 20.", parsed?.summary)
        assertEquals(listOf("Move the release to November 20"), parsed?.decisions)
        assertEquals(listOf(ProtocolAction("Prepare the build", "Igor", "November 15")), parsed?.actions)
    }

    @Test
    fun returnsNullForTextWithoutSections() {
        assertNull(ProtocolParser.parse("Просто пересказ разговора без заголовков.", TranscriptLanguage.RU))
    }

    @Test
    fun rejectsSpeakerEchoAndEmptySections() {
        val parsed = ProtocolParser.parse(
            """
            КРАТКО:
            Спикер 1: обсудили сроки и решили выпустить релиз в пятницу.
            РЕШЕНИЯ:
            ПОРУЧЕНИЯ:
            """.trimIndent(),
            TranscriptLanguage.RU,
        )
        assertNull(parsed)
    }

    @Test
    fun allowsMissingActionsAndDecisions() {
        val parsed = ProtocolParser.parse(
            """
            КРАТКО:
            Поговорили о погоде и планах на выходные, ничего не решили.
            """.trimIndent(),
            TranscriptLanguage.RU,
        )
        assertTrue(parsed != null)
        assertTrue(parsed!!.decisions.isEmpty())
        assertTrue(parsed.actions.isEmpty())
    }
}
