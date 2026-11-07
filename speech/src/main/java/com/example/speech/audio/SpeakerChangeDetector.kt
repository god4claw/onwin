package com.example.speech.audio

import kotlin.math.sqrt

/**
 * Настройки поиска смены говорящего внутри одного VAD-чанка.
 *
 * Окно 1.6 с даёт CAM++ достаточно контекста для устойчивого embedding,
 * шаг 0.8 с — точность границы порядка половины шага. Минимальное расстояние
 * между точками 1.5 с не даёт дробить речь на слоги. Точку потом сдвигают к
 * тихому кадру ([com.example.speech.impl.chooseCuts]).
 */
internal data class SpeakerChangeConfig(
    val windowMs: Int = 1_600,
    val hopMs: Int = 800,
    val minSegmentMs: Int = 1_500,
    val threshold: Float = 0.65f,
) {
    init {
        require(windowMs > 0) { "windowMs должен быть > 0, получено $windowMs" }
        require(hopMs > 0) { "hopMs должен быть > 0, получено $hopMs" }
        require(minSegmentMs >= 0) {
            "minSegmentMs должен быть >= 0, получено $minSegmentMs"
        }
        require(threshold in 0f..1f) {
            "speakerChangeThreshold должен быть в 0..1, получено $threshold"
        }
    }
}

/**
 * Ищет точки смены говорящего в VAD-чанке.
 *
 * Сравниваются embedding-и соседних окон: резкое падение косинуса ниже
 * [SpeakerChangeConfig.threshold] считается сменой. Чтобы не ловить один
 * плавный переход несколько раз, берутся только локальные минимумы, а затем
 * работает non-maximum suppression с минимальным расстоянием между точками.
 */
internal class SpeakerChangeSplitter(
    private val config: SpeakerChangeConfig = SpeakerChangeConfig(),
) {
    private val windowSamples = config.windowMs * VadSegmenter.SAMPLE_RATE / 1000
    private val hopSamples = config.hopMs * VadSegmenter.SAMPLE_RATE / 1000
    private val minSegmentSamples = config.minSegmentMs * VadSegmenter.SAMPLE_RATE / 1000

    /** Точки смены в сэмплах относительно начала [samples]; пусто — один говорящий. */
    fun findBoundaries(
        samples: FloatArray,
        embed: (FloatArray) -> FloatArray?,
    ): List<Int> {
        if (samples.size < windowSamples + 2 * hopSamples) return emptyList()

        val starts = ArrayList<Int>()
        var start = 0
        while (start + windowSamples <= samples.size) {
            starts += start
            start += hopSamples
        }
        if (starts.size < 3) return emptyList()

        val embeddings = starts.map { windowStart ->
            runCatching {
                embed(samples.copyOfRange(windowStart, windowStart + windowSamples))
            }.getOrNull()
        }
        return findSpeakerChangeBoundaries(
            embeddings = embeddings,
            windowStarts = starts,
            windowSamples = windowSamples,
            audioSamples = samples.size,
            minSegmentSamples = minSegmentSamples,
            threshold = config.threshold,
        )
    }
}

/**
 * Чистая часть детектора без native-моделей: по готовым embedding-ам окон
 * возвращает границы в сэмплах относительно начала чанка.
 */
internal fun findSpeakerChangeBoundaries(
    embeddings: List<FloatArray?>,
    windowStarts: List<Int>,
    windowSamples: Int,
    audioSamples: Int,
    minSegmentSamples: Int,
    threshold: Float,
): List<Int> {
    require(embeddings.size == windowStarts.size) {
        "embeddings=${embeddings.size}, windowStarts=${windowStarts.size}"
    }
    require(windowSamples > 0) { "windowSamples должен быть > 0" }
    require(threshold in 0f..1f) { "threshold должен быть в 0..1" }
    if (embeddings.size < 2) return emptyList()

    val similarities = ArrayList<Float?>(embeddings.size - 1)
    for (index in 0 until embeddings.size - 1) {
        val left = embeddings[index]
        val right = embeddings[index + 1]
        similarities += if (left == null || right == null) {
            null
        } else {
            cosine(left, right)
        }
    }

    val candidates = ArrayList<BoundaryCandidate>()
    for (index in similarities.indices) {
        val similarity = similarities[index] ?: continue
        if (similarity >= threshold) continue

        val previous = previousSimilarity(similarities, index)
        val next = nextSimilarity(similarities, index)
        val localMinimum = (previous == null || similarity <= previous) &&
            (next == null || similarity <= next)
        if (!localMinimum) continue

        val boundary = (windowStarts[index] + windowStarts[index + 1] + windowSamples) / 2
        if (boundary < minSegmentSamples || audioSamples - boundary < minSegmentSamples) {
            continue
        }
        candidates += BoundaryCandidate(boundary, similarity)
    }
    if (candidates.isEmpty()) return emptyList()

    // Сначала берём самые глубокие минимумы, затем отбрасываем слишком близкие.
    candidates.sortWith(compareBy<BoundaryCandidate> { it.similarity }.thenBy { it.boundary })
    val accepted = ArrayList<Int>()
    for (candidate in candidates) {
        if (accepted.any { kotlin.math.abs(it - candidate.boundary) < minSegmentSamples }) {
            continue
        }
        accepted += candidate.boundary
    }
    return accepted.sorted()
}

private data class BoundaryCandidate(
    val boundary: Int,
    val similarity: Float,
)

private fun previousSimilarity(values: List<Float?>, index: Int): Float? {
    for (i in index - 1 downTo 0) {
        values[i]?.let { return it }
    }
    return null
}

private fun nextSimilarity(values: List<Float?>, index: Int): Float? {
    for (i in index + 1 until values.size) {
        values[i]?.let { return it }
    }
    return null
}

private fun cosine(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size) { "Размеры embedding не совпадают: ${a.size} и ${b.size}" }
    var dot = 0.0
    var normA = 0.0
    var normB = 0.0
    for (index in a.indices) {
        dot += a[index].toDouble() * b[index]
        normA += a[index].toDouble() * a[index]
        normB += b[index].toDouble() * b[index]
    }
    val denominator = sqrt(normA) * sqrt(normB)
    return if (denominator == 0.0) 0f else (dot / denominator).toFloat()
}
