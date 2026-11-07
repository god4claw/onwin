package com.example.onwin.data.export

import com.example.onwin.domain.model.Session
import com.example.onwin.domain.model.SessionDetail
import com.example.onwin.domain.model.SessionSource
import com.example.onwin.domain.model.SessionStatus
import com.example.onwin.domain.model.SpeakerName
import com.example.onwin.domain.model.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptTextTest {
    private val detail = SessionDetail(
        session = Session(
            id = "transcript",
            title = "План #1",
            source = SessionSource.IMPORT,
            originalName = "meeting.wav",
            sourceUri = null,
            createdAt = 1,
            updatedAt = 1,
            status = SessionStatus.DONE,
        ),
        segments = listOf(
            TranscriptSegment(1, 1, 3_600_000, 3_601_010, "До встречи"),
            TranscriptSegment(0, 0, 1_250, 4_380, "Привет *всем*\n    # заметка"),
        ),
        speakerNames = listOf(SpeakerName(0, "Анна [ведущая]")),
    )

    @Test
    fun plainTextIncludesOrderedUtterancesWithNamesAndTimestamps() {
        val expected = """
            План #1

            [00:00:01.250 – 00:00:04.380] Анна [ведущая]
            Привет *всем*
                # заметка

            [01:00:00.000 – 01:00:01.010] Спикер 2
            До встречи
        """.trimIndent() + "\n"

        assertEquals(expected, detail.toExportTranscript { "Спикер ${it + 1}" }.toPlainText())
    }

    @Test
    fun markdownEscapesUserContentAndKeepsUtterancesInOrder() {
        val expected = """
            # План \#1

            **\[00\:00\:01\.250 – 00\:00\:04\.380\] Анна \[ведущая\]**

            Привет \*всем\*
            &#32;&#32;&#32;&#32;\# заметка

            **\[01\:00\:00\.000 – 01\:00\:01\.010\] Спикер 2**

            До встречи
        """.trimIndent() + "\n"

        assertEquals(expected, detail.toExportTranscript { "Спикер ${it + 1}" }.toMarkdown())
    }
}