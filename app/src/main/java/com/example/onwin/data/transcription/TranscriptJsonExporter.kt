package com.example.onwin.data.transcription

import android.content.Context
import android.net.Uri
import com.example.onwin.domain.model.TranscriptSegment
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class TranscriptJsonExporter @Inject constructor(@ApplicationContext context: Context) {
    private val resolver = context.contentResolver

    internal suspend fun write(destination: Uri, segments: List<TranscriptSegment>) =
        withContext(Dispatchers.IO) {
            val json = encodeTranscriptJson(segments)
            val output = resolver.openOutputStream(destination)
                ?: throw IOException("Could not open export destination")
            output.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
        }
}

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
