package com.example.speech.audio

/**
 * Кольцевой буфер последних [capacity] сэмплов потока с абсолютной нумерацией.
 *
 * Нужен, чтобы VAD работал по выровненному АРУ сигналу, а ASR и embedding
 * получали исходный: VAD отдаёт чанк с абсолютным `startSample`, и тот же
 * отрезок берётся отсюда. Если отрезок уже вытеснен (сегмент длиннее буфера),
 * [slice] вернёт `null` — вызывающий код оставляет то, что дал VAD.
 */
internal class SampleHistory(private val capacity: Int) {
    init {
        require(capacity > 0) { "capacity должен быть > 0, получено $capacity" }
    }

    private val buffer = FloatArray(capacity)

    /** Сколько сэмплов принято с начала (или с последнего [reset]). */
    var totalSamples: Long = 0L
        private set

    fun append(samples: FloatArray) {
        var offset = 0
        // Длиннее буфера — нужен только хвост.
        if (samples.size > capacity) {
            offset = samples.size - capacity
            totalSamples += offset
        }
        while (offset < samples.size) {
            val position = (totalSamples % capacity).toInt()
            val count = minOf(capacity - position, samples.size - offset)
            samples.copyInto(buffer, position, offset, offset + count)
            offset += count
            totalSamples += count
        }
    }

    /** Отрезок `[start, start + length)` или `null`, если его нет целиком. */
    fun slice(start: Long, length: Int): FloatArray? {
        if (start < 0L || length < 0) return null
        val end = start + length
        if (end > totalSamples || start < totalSamples - capacity) return null

        val out = FloatArray(length)
        var written = 0
        var position = start
        while (written < length) {
            val index = (position % capacity).toInt()
            val count = minOf(capacity - index, length - written)
            buffer.copyInto(out, written, index, index + count)
            written += count
            position += count
        }
        return out
    }

    fun reset() {
        totalSamples = 0L
    }
}
