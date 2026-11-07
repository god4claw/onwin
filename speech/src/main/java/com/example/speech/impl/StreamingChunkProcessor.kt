package com.example.speech.impl

import com.example.speech.api.Segment
import com.example.speech.audio.OnlineSpeakerClusterer
import com.example.speech.audio.SpeakerChangeSplitter
import com.example.speech.audio.SpeechChunk
import com.example.speech.audio.VadSegmenter
import kotlinx.coroutines.CancellationException
import java.util.Locale

/**
 * Реплика сессии вместе с её embedding (`null`, если реплика короче минимума
 * или embedding не посчитался) и номером VAD-чанка. `segment.speaker` —
 * онлайн-метка на момент выдачи; финальные метки считаются в конце сессии.
 */
internal class SegmentRecord(
    val segment: Segment,
    val embedding: FloatArray?,
    val chunkIndex: Int = 0,
)

/**
 * Обработка одного VAD-чанка: точки смены говорящего → разрез по тихим
 * кадрам → ASR каждого куска со словами → спикер каждой реплики.
 *
 * - Границы реплики берутся по словам (метки времени токенов GigaAM), а не по
 *   краям VAD-чанка или куска.
 * - Embedding для спикера считается по участку со словами, без пауз по краям.
 * - Соседние куски чанка с одним финальным спикером в конце сессии склеиваются
 *   ([mergeSameSpeakerNeighbors]) — ложный разрез не дробит фразу.
 *
 * На 26 синтетических разговорах это снизило ошибку атрибуции спикеров
 * 9,1 → 6,5 % при том же WER (см. docs/bench-word-timestamps.md).
 *
 * Вынесено из [SherpaStreamingTranscriber], чтобы логику можно было проверить
 * JVM-тестом без native-моделей: ASR и embedding подставляются функциями.
 *
 * [trace] получает по строке на каждое решение — только тайминги, длины и
 * номера спикеров. Распознанный текст туда не попадает: это офлайн-приложение,
 * и текст разговора не должен утекать в logcat.
 */
internal class StreamingChunkProcessor(
    private val transcribeWords: (FloatArray) -> List<AsrWord>,
    private val embeddingOf: (FloatArray) -> FloatArray?,
    private val clusterer: OnlineSpeakerClusterer,
    private val splitter: SpeakerChangeSplitter,
    private val minEmbeddingSamples: Int = MIN_EMBEDDING_SAMPLES,
    private val minPieceSamples: Int = MIN_PIECE_SAMPLES,
    private val energySearchSamples: Int = ENERGY_SEARCH_SAMPLES,
    private val trace: ((String) -> Unit)? = null,
) {
    private val sessionRecords = ArrayList<SegmentRecord>()

    /** Все выданные реплики сессии с embedding — для перекластеризации в конце. */
    val records: List<SegmentRecord> get() = sessionRecords

    /** Реплики чанка по порядку; пусто, если модель не дала текста. */
    fun process(chunk: SpeechChunk, chunkIndex: Int = 0): List<Segment> {
        val chunkStartMs = msOf(chunk.startSample)
        val chunkMs = msOf(chunk.samples.size.toLong())

        val splitStartedAt = System.nanoTime()
        val boundaries = splitter.findBoundaries(chunk.samples) { samples -> embeddingOf(samples) }
        val cuts = chooseCuts(boundaries, chunk.samples, minPieceSamples, energySearchSamples)
        val splitMs = elapsedMs(splitStartedAt)

        val asrStartedAt = System.nanoTime()
        val edges = listOf(0) + cuts + chunk.samples.size
        val pieces = edges.zipWithNext().map { (from, to) ->
            from to transcribeWords(chunk.samples.copyOfRange(from, to))
        }
        val words = pieces.sumOf { it.second.size }
        trace?.invoke(
            "chunk start=${chunkStartMs}ms dur=${chunkMs}ms boundaries=${boundaries.size} " +
                "pieces=${pieces.size} split=${splitMs}ms asr=${elapsedMs(asrStartedAt)}ms words=$words" +
                if (cuts.isNotEmpty()) " cuts=" + cuts.joinToString(",") { "${msOf(chunk.startSample + it)}ms" } else "",
        )

        return pieces.mapNotNull { (offset, pieceWords) ->
            if (pieceWords.isEmpty()) null else segmentOf(chunk, offset, pieceWords, chunkIndex)
        }
    }

    /** Реплика из слов [words], отсчитанных от [offset] внутри чанка. */
    private fun segmentOf(chunk: SpeechChunk, offset: Int, words: List<AsrWord>, chunkIndex: Int): Segment {
        // Метка первого токена позже начала звука — реплика начинается чуть раньше.
        val from = (offset + words.first().startSample - PRE_ROLL_SAMPLES).coerceIn(offset, chunk.samples.size)
        val to = (offset + words.last().endSample).coerceIn(from, chunk.samples.size)
        val text = words.joinToString(" ") { it.text }
        val startMs = msOf(chunk.startSample + from)
        val endMs = msOf(chunk.startSample + to)

        val decision = assignSpeaker(chunk.samples.copyOfRange(from, to))
        trace?.invoke(
            "segment start=${startMs}ms dur=${endMs - startMs}ms " +
                "emb=${decision.embeddingMs}ms spk=${decision.speaker} " +
                "sim=${decision.similarity?.let { String.format(Locale.US, "%.3f", it) } ?: "-"} " +
                "new=${decision.isNew} via=${decision.via} words=${words.size} chars=${text.length}",
        )
        val segment = Segment(
            speaker = decision.speaker,
            startMs = startMs,
            endMs = endMs,
            text = text,
        )
        sessionRecords += SegmentRecord(segment, decision.embedding, chunkIndex)
        return segment
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
                embedding = embedding,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fallback("cluster-error", embeddingMs, embedding)
        }
    }

    private fun fallback(via: String, embeddingMs: Long, embedding: FloatArray? = null) = SpeakerDecision(
        speaker = clusterer.lastSpeaker.coerceAtLeast(0),
        similarity = null,
        isNew = false,
        embeddingMs = embeddingMs,
        via = via,
        embedding = embedding,
    )

    private class SpeakerDecision(
        val speaker: Int,
        val similarity: Float?,
        val isNew: Boolean,
        val embeddingMs: Long,
        /** Как выбран спикер: `cluster` или причина fallback-а. */
        val via: String,
        val embedding: FloatArray?,
    )

    private fun msOf(samples: Long): Long = samples * 1000L / VadSegmenter.SAMPLE_RATE

    private fun msOf(samples: Int): Long = msOf(samples.toLong())

    private fun elapsedMs(startedAtNanos: Long): Long = (System.nanoTime() - startedAtNanos) / 1_000_000

    private companion object {
        const val MIN_EMBEDDING_MS = 500
        const val MIN_EMBEDDING_SAMPLES = VadSegmenter.SAMPLE_RATE * MIN_EMBEDDING_MS / 1000

        /** Кусок между разрезами не короче 0,4 с. */
        const val MIN_PIECE_SAMPLES = VadSegmenter.SAMPLE_RATE * 400 / 1000

        /** На столько начало звука опережает метку первого токена. */
        const val PRE_ROLL_SAMPLES = VadSegmenter.SAMPLE_RATE / 10

        /** Разрез сдвигается к самому тихому кадру в окне ±0,3 с. */
        const val ENERGY_SEARCH_SAMPLES = VadSegmenter.SAMPLE_RATE * 300 / 1000
    }
}
