package com.example.onwin.data.export

import com.example.onwin.domain.model.TranscriptSegment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal fun encodeTranscriptJson(segments: List<TranscriptSegment>): String =
    Json.encodeToString(
        JsonTranscript(segments.sortedBy { it.index }.map { segment ->
            JsonUtterance(
                speakerId = "speaker_${segment.speaker + 1}",
                startMs = segment.startMs,
                endMs = segment.endMs,
                text = segment.text,
            )
        }),
    )

@Serializable
private data class JsonTranscript(val transcript: List<JsonUtterance>)

@Serializable
private data class JsonUtterance(
    @SerialName("speaker_id") val speakerId: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    val text: String,
)
