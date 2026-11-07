package com.example.speech.audio

import kotlin.math.sqrt

internal class OnlineSpeakerClusterer(
    private val threshold: Float,
    private val maxSpeakers: Int = DEFAULT_MAX_SPEAKERS,
    /**
     * Сколько сэмплов новой речи нужно накопить, прежде чем создавать кластер.
     * Это отсекает «спикеров» из одного короткого выкрика. 0 — создавать сразу
     * (поведение по умолчанию для unit-тестов и обратной совместимости).
     */
    private val minNewSpeakerSamples: Int = 0,
) {
    init {
        require(threshold in 0f..1f) {
            "onlineClusterThreshold должен быть в 0..1, получено $threshold"
        }
        require(maxSpeakers >= 1) {
            "maxOnlineSpeakers должен быть >= 1, получено $maxSpeakers"
        }
        require(minNewSpeakerSamples >= 0) {
            "minNewSpeakerSamples должен быть >= 0, получено $minNewSpeakerSamples"
        }
    }

    private val embeddingSums = ArrayList<FloatArray>()
    private var dimension = -1

    private var pendingSum: FloatArray? = null
    private var pendingSamples = 0

    var lastSpeaker: Int = -1
        private set

    val speakerCount: Int get() = embeddingSums.size

    fun assign(embedding: FloatArray, durationSamples: Int = 0): Assignment {
        require(embedding.isNotEmpty()) { "Speaker embedding пустой" }
        require(durationSamples >= 0) {
            "durationSamples должен быть >= 0, получено $durationSamples"
        }
        if (dimension == -1) {
            dimension = embedding.size
        } else {
            require(embedding.size == dimension) {
                "Размер embedding изменился: ожидался $dimension, получен ${embedding.size}"
            }
        }

        val normalized = normalize(embedding)

        var bestIndex = -1
        var bestSimilarity = -1f
        for (index in embeddingSums.indices) {
            val similarity = cosine(embeddingSums[index], normalized)
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity
                bestIndex = index
            }
        }

        val useExisting = bestIndex >= 0 &&
            (bestSimilarity >= threshold || embeddingSums.size >= maxSpeakers)
        if (useExisting) {
            val sum = embeddingSums[bestIndex]
            for (i in sum.indices) sum[i] += normalized[i]
            lastSpeaker = bestIndex
            clearPending()
            return Assignment(
                speaker = bestIndex,
                similarity = bestSimilarity,
                isNewSpeaker = false,
            )
        }

        if (embeddingSums.isEmpty() || minNewSpeakerSamples <= 0) {
            return createSpeaker(normalized, bestSimilarity)
        }

        val pending = pendingSum
        if (pending == null) {
            pendingSum = normalized.copyOf()
            pendingSamples = durationSamples
        } else if (cosine(pending, normalized) >= PENDING_COHESION) {
            for (i in pending.indices) pending[i] += normalized[i]
            pendingSamples += durationSamples
        } else {
            pendingSum = normalized.copyOf()
            pendingSamples = durationSamples
        }

        if (pendingSamples >= minNewSpeakerSamples) {
            val sum = checkNotNull(pendingSum)
            clearPending()
            return createSpeaker(sum, bestSimilarity)
        }

        // Пока новый кластер не подтверждён, относим реплику к ближайшему
        // существующему. Это лучше, чем показывать пользователю нового
        // «спикера» после одного короткого фрагмента.
        val provisional = bestIndex.takeIf { it >= 0 }
            ?: lastSpeaker.takeIf { it >= 0 }
            ?: 0
        lastSpeaker = provisional
        return Assignment(
            speaker = provisional,
            similarity = bestSimilarity.coerceAtLeast(0f),
            isNewSpeaker = false,
        )
    }

    fun reset() {
        embeddingSums.clear()
        dimension = -1
        lastSpeaker = -1
        clearPending()
    }

    private fun createSpeaker(normalized: FloatArray, similarity: Float): Assignment {
        embeddingSums += normalized.copyOf()
        lastSpeaker = embeddingSums.lastIndex
        clearPending()
        return Assignment(
            speaker = lastSpeaker,
            similarity = similarity.coerceAtLeast(0f),
            isNewSpeaker = true,
        )
    }

    private fun clearPending() {
        pendingSum = null
        pendingSamples = 0
    }

    private fun cosine(sum: FloatArray, normalized: FloatArray): Float {
        var dot = 0f
        var squaredNorm = 0f
        for (i in sum.indices) {
            dot += sum[i] * normalized[i]
            squaredNorm += sum[i] * sum[i]
        }
        if (squaredNorm == 0f) return 0f
        return dot / sqrt(squaredNorm)
    }

    private fun normalize(vector: FloatArray): FloatArray {
        var squaredNorm = 0.0
        for (value in vector) squaredNorm += value.toDouble() * value
        if (squaredNorm == 0.0) return vector.copyOf()

        val inverseNorm = (1.0 / sqrt(squaredNorm)).toFloat()
        return FloatArray(vector.size) { vector[it] * inverseNorm }
    }

    data class Assignment(
        val speaker: Int,
        val similarity: Float,
        val isNewSpeaker: Boolean,
    )

    companion object {
        const val DEFAULT_MAX_SPEAKERS = 20

        /**
         * Минимальное сходство между накопленными «кандидатами» в новый кластер.
         * Ниже этого порога pending сбрасывается: это шум, а не один человек.
         */
        private const val PENDING_COHESION = 0.35f
    }
}
