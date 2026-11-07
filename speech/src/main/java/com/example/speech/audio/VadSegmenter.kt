package com.example.speech.audio

internal data class SpeechChunk(
    val startSample: Long,
    val samples: FloatArray,
) {
    fun trimmedTo(availableSamples: Long): SpeechChunk? {
        val available = availableSamples - startSample
        if (available <= 0L) return null
        if (samples.size <= available) return this
        return copy(samples = samples.copyOf(available.toInt()))
    }
}

internal data class VadUpdate(
    val speechStartedAtSample: Long?,
    val chunks: List<SpeechChunk>,
)

internal interface VadEngine {
    fun accept(samples: FloatArray)
    fun drain(): List<RawSegment>
    fun flush()
    fun isSpeechDetected(): Boolean
    fun reset()
    fun release()

    data class RawSegment(
        val startSample: Long,
        val samples: FloatArray,
    )
}

internal class VadSegmenter(
    private val engine: VadEngine,
    private val windowSize: Int = WINDOW_SIZE,
) {
    private val window = FloatArray(windowSize)
    private var windowFill = 0
    private var speechActive = false

    var processedSamples: Long = 0L
        private set

    var receivedSamples: Long = 0L
        private set

    fun accept(samples: FloatArray): VadUpdate {
        if (samples.isEmpty()) return VadUpdate(null, emptyList())

        receivedSamples += samples.size
        val chunks = ArrayList<SpeechChunk>()
        var speechStartedAtSample: Long? = null
        var offset = 0

        while (offset < samples.size) {
            val copy = minOf(windowSize - windowFill, samples.size - offset)
            samples.copyInto(window, windowFill, offset, offset + copy)
            windowFill += copy
            offset += copy

            if (windowFill == windowSize) {
                val started = feedWindow()
                if (speechStartedAtSample == null) speechStartedAtSample = started
                drainInto(chunks)
            }
        }

        return VadUpdate(speechStartedAtSample, chunks)
    }

    fun flush(): List<SpeechChunk> {
        val chunks = ArrayList<SpeechChunk>()

        if (windowFill > 0) {
            window.fill(0f, windowFill, windowSize)
            feedWindow()
            drainInto(chunks)
        }

        engine.flush()
        drainInto(chunks)
        return chunks
    }

    fun reset() {
        windowFill = 0
        processedSamples = 0L
        receivedSamples = 0L
        speechActive = false
        engine.reset()
    }

    fun release() = engine.release()

    private fun feedWindow(): Long? {
        engine.accept(window)
        windowFill = 0
        processedSamples += windowSize

        val detected = engine.isSpeechDetected()
        val started = if (detected && !speechActive) processedSamples - windowSize else null
        speechActive = detected
        return started
    }

    private fun drainInto(out: MutableList<SpeechChunk>) {
        for (segment in engine.drain()) {
            out += SpeechChunk(
                startSample = segment.startSample.coerceAtLeast(0L),
                samples = segment.samples,
            )
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val WINDOW_SIZE = 512
    }
}
