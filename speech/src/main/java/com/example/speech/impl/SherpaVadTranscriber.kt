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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * Расшифровка файла тем же пайплайном, что и streaming: АРУ → Silero VAD →
 * GigaAM по репликам → CAM++ → перекластеризация спикеров по всей записи.
 * Файл подаётся целиком без пауз, наружу — обычный контракт [Transcriber].
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

        val samples = try {
            WavReader.readMono16k(wav).also {
                require(it.isNotEmpty()) { "В файле нет аудиоданных: ${wav.absolutePath}" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(TranscriptionEvent.Failed(e))
            return@channelFlow
        }

        val totalMs = (samples.size * 1000L / VadSegmenter.SAMPLE_RATE).coerceAtLeast(1L)
        send(TranscriptionEvent.Progress(Stage.RECOGNITION, 0f))

        val audio = flow {
            var offset = 0
            while (offset < samples.size) {
                val end = minOf(offset + CHUNK_SAMPLES, samples.size)
                emit(samples.copyOfRange(offset, end))
                offset = end
            }
        }

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
    }.flowOn(Dispatchers.IO)

    override fun release() = engine.release()

    private fun fraction(segment: Segment, totalMs: Long): Float =
        (segment.endMs.toFloat() / totalMs).coerceIn(0f, 1f)

    private companion object {
        const val MIN_SPEAKERS = 1
        const val MAX_SPEAKERS = 20

        /** 0,5 с — VAD и очередь ASR принимают любой размер, это лишь шаг подачи. */
        const val CHUNK_SAMPLES = VadSegmenter.SAMPLE_RATE / 2
    }
}
