package com.example.speech.audio

import kotlin.math.sqrt

internal class OnlineSpeakerClusterer(
    private val threshold: Float,
    private val maxSpeakers: Int = DEFAULT_MAX_SPEAKERS,
) {
    init {
        require(threshold in 0f..1f) {
            "onlineClusterThreshold должен быть в 0..1, получено $threshold"
        }
        require(maxSpeakers >= 1) {
            "maxOnlineSpeakers должен быть >= 1, получено $maxSpeakers"
        }
    }

    private val embeddingSums = ArrayList<FloatArray>()
    private var dimension = -1

    var lastSpeaker: Int = -1
        private set

    val speakerCount: Int get() = embeddingSums.size

    fun assign(embedding: FloatArray): Assignment {
        require(embedding.isNotEmpty()) { "Speaker embedding пустой" }
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
            return Assignment(
                speaker = bestIndex,
                similarity = bestSimilarity,
                isNewSpeaker = false,
            )
        }

        embeddingSums += normalized.copyOf()
        lastSpeaker = embeddingSums.lastIndex
        return Assignment(
            speaker = lastSpeaker,
            similarity = bestSimilarity.coerceAtLeast(0f),
            isNewSpeaker = true,
        )
    }

    fun reset() {
        embeddingSums.clear()
        dimension = -1
        lastSpeaker = -1
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
    }
}
