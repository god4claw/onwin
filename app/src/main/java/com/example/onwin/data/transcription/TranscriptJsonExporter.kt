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

/**
 * Экспортер транскриптов сессий в формат JSON.
 *
 * Предоставляет возможность записи сегментов расшифровки [TranscriptSegment]
 * по указанному URI назначения (например, в файл через [android.content.ContentResolver]).
 *
 * @param context Контекст приложения, используемый для доступа к [android.content.ContentResolver].
 */
class TranscriptJsonExporter @Inject constructor(@ApplicationContext context: Context) {
    private val resolver = context.contentResolver

    /**
     * Записывает список сегментов транскрипции [segments] в файл по URI [destination] в формате JSON.
     *
     * @param destination URI назначения для экспорта данных.
     * @param segments Список сегментов расшифровки для записи.
     * @throws IOException Если не удалось открыть поток записи по указанному URI.
     */
    internal suspend fun write(destination: Uri, segments: List<TranscriptSegment>) =
        withContext(Dispatchers.IO) {
            val json = encodeTranscriptJson(segments)
            val output = resolver.openOutputStream(destination)
                ?: throw IOException("Could not open export destination")
            output.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
        }
}

/**
 * Преобразует список сегментов транскрипции [TranscriptSegment] в JSON-строку.
 *
 * Сегменты предварительно сортируются по их индексу [TranscriptSegment.index],
 * а идентификаторы спикеров форматируются как `speaker_N` (где N = speaker + 1).
 *
 * @param segments Список сегментов расшифровки.
 * @return Сформированная JSON-строка.
 */
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

/**
 * Модель верхнего уровня для сериализации транскрипта в JSON.
 *
 * @property transcript Список реплик (высказываний).
 */
@Serializable
private data class JsonTranscript(val transcript: List<JsonUtterance>)

/**
 * Модель отдельной реплики (высказывания) для сериализации в JSON.
 *
 * @property speakerId Идентификатор спикера в формате `speaker_N`.
 * @property startMs Время начала реплики в миллисекундах.
 * @property endMs Время окончания реплики в миллисекундах.
 * @property text Текст реплики.
 */
@Serializable
private data class JsonUtterance(
    @SerialName("speaker_id") val speakerId: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    val text: String,
)
