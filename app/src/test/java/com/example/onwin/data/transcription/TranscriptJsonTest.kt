package com.example.onwin.data.transcription

import com.example.onwin.domain.model.TranscriptSegment
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptJsonTest {
    @Test
    fun encodesSegmentsInIndexOrderWithStableSpeakerIdsAndNumericTimestamps() {
        val segments = listOf(
            TranscriptSegment(2, 0, 6_000, 9_000, "До встречи!"),
            TranscriptSegment(0, 0, 1_250, 4_380, "Добрый день, \"Анна\"!\nКак дела?"),
            TranscriptSegment(1, 1, 4_380, 6_000, "Хорошо."),
        )
        val expected = """
            {"transcript":[
                {"speaker_id":"speaker_1","start_ms":1250,"end_ms":4380,"text":"Добрый день, \"Анна\"!\nКак дела?"},
                {"speaker_id":"speaker_2","start_ms":4380,"end_ms":6000,"text":"Хорошо."},
                {"speaker_id":"speaker_1","start_ms":6000,"end_ms":9000,"text":"До встречи!"}
            ]}
        """.trimIndent()

        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(encodeTranscriptJson(segments)))
    }

    @Test
    fun emptyTranscriptHasAnEmptyArray() {
        assertEquals("""{"transcript":[]}""", encodeTranscriptJson(emptyList()))
    }
}
