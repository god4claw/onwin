package com.example.speech.impl

import android.content.Context
import com.example.speech.api.Segment
import com.example.speech.api.SpeechConfig
import com.example.speech.api.Stage
import com.example.speech.api.StreamingEvent
import com.example.speech.api.StreamingTranscriber
import com.example.speech.api.Transcriber
import com.example.speech.api.TranscriptionEvent
import com.example.speech.audio.VadSegmenter
import com.example.speech.audio.WavReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * Расшифровка файла тем же пайплайном, что и streaming: АРУ → Silero VAD →
 * GigaAM по репликам → speaker embedding (ERes2Net) → перекластеризация спикеров по всей записи.
 * Файл подаётся без пауз, наружу — обычный контракт [Transcriber].
 *
 * Файл читается кусками по 0,5 с прямо в пайплайн (очередь ASR даёт обратное
 * давление), поэтому память не зависит от длины записи: лимит — 12 часов
 * ([MAX_FILE_DURATION_MS]); за сессию копятся только реплики и их embedding-и.
 *
 * Чем лучше [SherpaTranscriber] (pyannote + FastClustering) на наших замерах
 * (golden set + отложенная выборка, синтетика; docs/bench-vad-transcriber.md):
 * WER 18,0/19,7 → 16,6/16,2 %, вдвое быстрее, и не падает: batch-диаризация
 * sherpa-onnx роняет процесс SIGSEGV на монологе при пороге по умолчанию.
 *
 * События:
 * - `Progress(RECOGNITION, доля)` — по концу последней распознанной реплики;
 * - `SegmentReady` — реплика с предварительным номером спикера;
 * - `Done` — все сегменты с финальными номерами (перекластеризация по всей
 *   записи); приложение заменяет ими предварительные.
 *
 * `expectedSpeakers` 1..20 сводит реплики ровно к стольким спикерам; `null` —
 * автоопределение.
 */
class SherpaVadTranscriber(context: Context) : Transcriber, StreamingTranscriber {

    private val engine = SherpaStreamingTranscriber(context)

    override suspend fun load(config: SpeechConfig) = engine.load(config)

    override fun transcribeStream(audio: Flow<FloatArray>): Flow<StreamingEvent> =
        engine.transcribeStream(audio)

    override fun transcribe(wav: File, expectedSpeakers: Int?): Flow<TranscriptionEvent> = channelFlow {
        if (expectedSpeakers != null && expectedSpeakers !in MIN_SPEAKERS..MAX_SPEAKERS) {
            send(
                TranscriptionEvent.Failed(
                    IllegalArgumentException(
                        "expectedSpeakers должен быть null (авто) или " +
                            "$MIN_SPEAKERS..$MAX_SPEAKERS, получено $expectedSpeakers",
                    ),
                ),
            )
            return@channelFlow
        }

        val wavStream = try {
            WavReader.open(wav, MAX_FILE_DURATION_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(TranscriptionEvent.Failed(e))
            return@channelFlow
        }

        val totalMs = wavStream.durationMs.coerceAtLeast(1L)
        send(TranscriptionEvent.Progress(Stage.RECOGNITION, 0f))

        // Ошибка чтения посреди файла дойдёт до engine и вернётся как Failed.
        val audio = flow {
            while (true) {
                val chunk = FloatArray(CHUNK_SAMPLES)
                val read = wavStream.read(chunk)
                if (read <= 0) break
                emit(if (read == chunk.size) chunk else chunk.copyOf(read))
            }
        }

        try {
            collectEvents(audio, expectedSpeakers, totalMs)
        } finally {
            wavStream.close()
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun ProducerScope<TranscriptionEvent>.collectEvents(
        audio: Flow<FloatArray>,
        expectedSpeakers: Int?,
        totalMs: Long,
    ) {
        engine.transcribeStream(audio, expectedSpeakers).collect { event ->
            when (event) {
                is StreamingEvent.SpeechStarted -> Unit
                is StreamingEvent.Utterance -> {
                    send(TranscriptionEvent.SegmentReady(event.segment))
                    send(TranscriptionEvent.Progress(Stage.RECOGNITION, fraction(event.segment, totalMs)))
                }
                is StreamingEvent.Done -> {
                    send(TranscriptionEvent.Progress(Stage.RECOGNITION, 1f))
                    send(TranscriptionEvent.Done(event.segments))
                }
                is StreamingEvent.Failed -> send(TranscriptionEvent.Failed(event.cause))
            }
        }
    }

    override fun release() = engine.release()

    private fun fraction(segment: Segment, totalMs: Long): Float =
        (segment.endMs.toFloat() / totalMs).coerceIn(0f, 1f)

    private companion object {
        const val MIN_SPEAKERS = 1
        const val MAX_SPEAKERS = 20

        /** 0,5 с — VAD и очередь ASR принимают любой размер, это лишь шаг подачи. */
        const val CHUNK_SAMPLES = VadSegmenter.SAMPLE_RATE / 2

        /**
         * 12 часов: с запасом над худшим случаем (8 ч). Память от длины не зависит;
         * позиции VAD в sherpa-onnx — int32, это ~37 ч при 16 кГц.
         */
        const val MAX_FILE_DURATION_MS = 12L * 60L * 60L * 1000L
    }
}
