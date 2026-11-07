package com.example.speech.impl

import com.example.speech.api.Segment
import com.example.speech.audio.OnlineSpeakerClusterer
import com.example.speech.audio.SpeakerChangeSplitter
import com.example.speech.audio.SpeechChunk
import com.example.speech.audio.VadSegmenter
import kotlinx.coroutines.CancellationException
import java.util.Locale

/**
 * Обработка одного VAD-чанка в псевдостриминге v2: нарезка по смене
 * говорящего, offline-ASR куска и назначение спикера.
 *
 * Вынесено из [SherpaStreamingTranscriber], чтобы эту логику можно было
 * проверить JVM-тестом без native-моделей: ASR и embedding подставляются
 * функциями, кластеризатор и splitter — обычные Kotlin-классы.
 *
 * [trace] получает по строке на каждое решение (разрез, реплика, спикер) —
 * только тайминги, длины и номера спикеров. Распознанный текст туда не
 * попадает: это офлайн-приложение, и текст разговора не должен утекать в logcat.
 */
internal class StreamingChunkProcessor(
    private val transcribe: (FloatArray) -> String,
    private val embeddingOf: (FloatArray) -> FloatArray?,
    private val clusterer: OnlineSpeakerClusterer,
    private val splitter: SpeakerChangeSplitter,
    private val minEmbeddingSamples: Int = MIN_EMBEDDING_SAMPLES,
    private val trace: ((String) -> Unit)? = null,
) {

    /**
     * Режет чанк в точках смены говорящего. Даже если резать нечего,
     * возвращается исходный чанк — вызывающий код не различает эти случаи.
     */
    fun split(chunk: SpeechChunk): List<SpeechChunk> {
        val startedAt = System.nanoTime()
        val pieces = splitter.split(chunk) { samples -> embeddingOf(samples) }
        trace?.let { log ->
            val cuts = if (pieces.size > 1) {
                " cuts=" + pieces.drop(1).joinToString(",") { "${msOf(it.startSample)}ms" }
            } else {
                ""
            }
            log(
                "split start=${msOf(chunk.startSample)}ms dur=${msOf(chunk.samples.size.toLong())}ms " +
                    "pieces=${pieces.size}$cuts took=${elapsedMs(startedAt)}ms",
            )
        }
        return pieces
    }

    /**
     * Распознаёт кусок и назначает говорящего. `null`, если модель не дала
     * текста: пустые реплики в `Utterance` не отдаются.
     */
    fun segment(piece: SpeechChunk): Segment? {
        val startMs = msOf(piece.startSample)
        val endMs = msOf(piece.startSample + piece.samples.size)

        val asrStartedAt = System.nanoTime()
        val text = transcribe(piece.samples)
        val asrMs = elapsedMs(asrStartedAt)
        if (text.isBlank()) {
            trace?.invoke("segment start=${startMs}ms dur=${endMs - startMs}ms asr=${asrMs}ms empty, dropped")
            return null
        }

        val decision = assignSpeaker(piece.samples)
        trace?.invoke(
            "segment start=${startMs}ms dur=${endMs - startMs}ms asr=${asrMs}ms " +
                "emb=${decision.embeddingMs}ms spk=${decision.speaker} " +
                "sim=${decision.similarity?.let { String.format(Locale.US, "%.3f", it) } ?: "-"} " +
                "new=${decision.isNew} via=${decision.via} chars=${text.length}",
        )
        return Segment(
            speaker = decision.speaker,
            startMs = startMs,
            endMs = endMs,
            text = text,
        )
    }

    private fun assignSpeaker(samples: FloatArray): SpeakerDecision {
        if (samples.size < minEmbeddingSamples) return fallback("short", embeddingMs = 0L)

        val embeddingStartedAt = System.nanoTime()
        val embedding = try {
            embeddingOf(samples)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val embeddingMs = elapsedMs(embeddingStartedAt)
        if (embedding == null) return fallback("no-embedding", embeddingMs)

        return try {
            val assignment = clusterer.assign(embedding, samples.size)
            SpeakerDecision(
                speaker = assignment.speaker,
                similarity = assignment.similarity,
                isNew = assignment.isNewSpeaker,
                embeddingMs = embeddingMs,
                via = "cluster",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fallback("cluster-error", embeddingMs)
        }
    }

    private fun fallback(via: String, embeddingMs: Long) = SpeakerDecision(
        speaker = clusterer.lastSpeaker.coerceAtLeast(0),
        similarity = null,
        isNew = false,
        embeddingMs = embeddingMs,
        via = via,
    )

    private class SpeakerDecision(
        val speaker: Int,
        val similarity: Float?,
        val isNew: Boolean,
        val embeddingMs: Long,
        /** Как выбран спикер: `cluster` или причина fallback-а. */
        val via: String,
    )

    private fun msOf(samples: Long): Long = samples * 1000L / VadSegmenter.SAMPLE_RATE

    private fun elapsedMs(startedAtNanos: Long): Long = (System.nanoTime() - startedAtNanos) / 1_000_000

    private companion object {
        const val MIN_EMBEDDING_MS = 500
        const val MIN_EMBEDDING_SAMPLES = VadSegmenter.SAMPLE_RATE * MIN_EMBEDDING_MS / 1000
    }
}
