package com.example.speech.impl

import com.example.speech.api.Segment
import com.example.speech.audio.OnlineSpeakerClusterer
import com.example.speech.audio.SpeakerChangeSplitter
import com.example.speech.audio.SpeechChunk
import com.example.speech.audio.VadSegmenter
import kotlinx.coroutines.CancellationException

/**
 * Обработка одного VAD-чанка в псевдостриминге v2: нарезка по смене
 * говорящего, offline-ASR куска и назначение спикера.
 *
 * Вынесено из [SherpaStreamingTranscriber], чтобы эту логику можно было
 * проверить JVM-тестом без native-моделей: ASR и embedding подставляются
 * функциями, кластеризатор и splitter — обычные Kotlin-классы.
 */
internal class StreamingChunkProcessor(
    private val transcribe: (FloatArray) -> String,
    private val embeddingOf: (FloatArray) -> FloatArray?,
    private val clusterer: OnlineSpeakerClusterer,
    private val splitter: SpeakerChangeSplitter,
    private val minEmbeddingSamples: Int = MIN_EMBEDDING_SAMPLES,
) {

    /**
     * Режет чанк в точках смены говорящего. Даже если резать нечего,
     * возвращается исходный чанк — вызывающий код не различает эти случаи.
     */
    fun split(chunk: SpeechChunk): List<SpeechChunk> =
        splitter.split(chunk) { samples -> embeddingOf(samples) }

    /**
     * Распознаёт кусок и назначает говорящего. `null`, если модель не дала
     * текста: пустые реплики в `Utterance` не отдаются.
     */
    fun segment(piece: SpeechChunk): Segment? {
        val text = transcribe(piece.samples)
        if (text.isBlank()) return null

        val startMs = piece.startSample * 1000L / VadSegmenter.SAMPLE_RATE
        val endMs = (piece.startSample + piece.samples.size) * 1000L / VadSegmenter.SAMPLE_RATE
        return Segment(
            speaker = assignSpeaker(piece.samples),
            startMs = startMs,
            endMs = endMs,
            text = text,
        )
    }

    private fun assignSpeaker(samples: FloatArray): Int {
        if (samples.size < minEmbeddingSamples) return fallbackSpeaker()

        val embedding = try {
            embeddingOf(samples)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return fallbackSpeaker()

        return try {
            clusterer.assign(embedding, samples.size).speaker
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fallbackSpeaker()
        }
    }

    private fun fallbackSpeaker(): Int = clusterer.lastSpeaker.coerceAtLeast(0)

    private companion object {
        const val MIN_EMBEDDING_MS = 500
        const val MIN_EMBEDDING_SAMPLES = VadSegmenter.SAMPLE_RATE * MIN_EMBEDDING_MS / 1000
    }
}
