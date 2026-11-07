package com.example.onwin.data.transcription

import com.example.speech.api.Segment
import com.example.speech.api.SpeechConfig
import com.example.speech.api.Stage
import com.example.speech.api.Transcriber
import com.example.speech.api.TranscriptionEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Демо-реализация [Transcriber] без моделей: приложению и тестам нужны правдоподобные сегменты,
 * а настоящая расшифровка живёт в модуле `:speech` (`SherpaVadTranscriber`).
 *
 * Порядок событий — как в контракте: загрузка с задержкой (повторная с тем же конфигом — сразу),
 * сначала прогресс диаризации, потом сегменты по порядку времени вперемешку с прогрессом
 * распознавания, в конце `Done` с тем же списком. Длительность сегментов считается по настоящей
 * длительности WAV из заголовка; спикеры 0/1/2 идут по кругу, а `expectedSpeakers` не учитывается.
 *
 * Отмена — отменой коллектора. Две расшифровки одновременно и расшифровка без [load] — ошибка
 * использования: [IllegalStateException] приходит при сборе потока. `release()` только забывает
 * конфиг: идущая расшифровка доходит до конца, а следующая потребует [load].
 *
 * @param loadDelayMs сколько длится [load].
 * @param diarizationMs сколько длится этап диаризации.
 * @param realtimeFactor время распознавания сегмента относительно его длительности.
 * @param failAfterSegments если задано — после стольких сегментов приходит `Failed` (для тестов).
 */
class FakeTranscriber(
    private val loadDelayMs: Long = 1_000,
    private val diarizationMs: Long = 2_000,
    private val realtimeFactor: Double = 0.1,
    private val failAfterSegments: Int? = null,
) : Transcriber {

    @Volatile
    private var loadedConfig: SpeechConfig? = null
    private val busy = AtomicBoolean(false)

    override suspend fun load(config: SpeechConfig) {
        if (loadedConfig == config) return
        delay(loadDelayMs)
        loadedConfig = config
    }

    override fun transcribe(
        wav: File,
        @Suppress("UNUSED_PARAMETER") expectedSpeakers: Int?,
    ): Flow<TranscriptionEvent> = flow {
        check(loadedConfig != null) { "load() must be called before transcribe()" }
        check(busy.compareAndSet(false, true)) { "Another transcription is running" }
        try {
            val durationMs = try {
                readDurationMs(wav)
            } catch (e: IOException) {
                emit(TranscriptionEvent.Failed(e))
                return@flow
            }
            for (step in 1..DIARIZATION_STEPS) {
                delay(diarizationMs / DIARIZATION_STEPS)
                emit(TranscriptionEvent.Progress(Stage.DIARIZATION, step.toFloat() / DIARIZATION_STEPS))
            }
            val segments = planSegments(durationMs)
            emit(TranscriptionEvent.Progress(Stage.RECOGNITION, 0f))
            segments.forEachIndexed { index, segment ->
                if (index == failAfterSegments) {
                    emit(TranscriptionEvent.Failed(IllegalStateException("Fake failure after $index segments")))
                    return@flow
                }
                delay(((segment.endMs - segment.startMs) * realtimeFactor).toLong())
                emit(TranscriptionEvent.SegmentReady(segment))
                emit(TranscriptionEvent.Progress(Stage.RECOGNITION, (index + 1f) / segments.size))
            }
            emit(TranscriptionEvent.Done(segments))
        } finally {
            busy.set(false)
        }
    }

    override fun release() {
        loadedConfig = null
    }

    private companion object {
        const val DIARIZATION_STEPS = 20
        const val BYTES_PER_MS = 32  // 16 000 Гц × 2 байта
        const val GAP_MS = 400L
        val LENGTHS_MS = longArrayOf(5_200, 7_400, 3_800, 6_100)
        val PHRASES = listOf(
            "Добрый день, давайте начнём встречу.",
            "Да, я подготовил отчёт за прошлую неделю.",
            "Хорошо, расскажите коротко, что получилось.",
            "Мы закончили импорт и сейчас проверяем распознавание.",
            "Есть вопросы по срокам?",
            "Думаю, к пятнице успеем всё доделать.",
        )

        /** Длительность канонического WAV 16 кГц моно PCM16 по размеру чанка `data`. */
        fun readDurationMs(wav: File): Long {
            val header = ByteArray(44)
            DataInputStream(wav.inputStream()).use { it.readFully(header) }
            val dataBytes = (header[40].toLong() and 0xFF) or
                ((header[41].toLong() and 0xFF) shl 8) or
                ((header[42].toLong() and 0xFF) shl 16) or
                ((header[43].toLong() and 0xFF) shl 24)
            return dataBytes / BYTES_PER_MS
        }

        /** Реплики подряд с паузами, последняя обрезается по концу записи. */
        fun planSegments(durationMs: Long): List<Segment> {
            val segments = mutableListOf<Segment>()
            var start = 0L
            while (start < durationMs) {
                val index = segments.size
                val end = minOf(start + LENGTHS_MS[index % LENGTHS_MS.size], durationMs)
                segments += Segment(index % 3, start, end, PHRASES[index % PHRASES.size])
                start = end + GAP_MS
            }
            return segments
        }
    }
}
