package com.example.speech.audio

/**
 * Режет слишком длинный сегмент VAD на куски не длиннее [maxSamples].
 *
 * Silero VAD в sherpa-onnx 1.13.0 в некоторых сценариях возвращает сегмент
 * длиннее `maxSpeechDuration` (на полном Pear — 172 с при лимите 25 с).
 * Этот защитный split не даёт одному utterance разрастись до минут.
 */
internal fun splitLongVadSegment(
    startSample: Long,
    samples: FloatArray,
    maxSamples: Int,
): List<VadEngine.RawSegment> {
    require(maxSamples >= 1) { "maxSamples должен быть >= 1, получено $maxSamples" }
    if (samples.size <= maxSamples) {
        return listOf(VadEngine.RawSegment(startSample, samples))
    }

    val result = ArrayList<VadEngine.RawSegment>((samples.size + maxSamples - 1) / maxSamples)
    var offset = 0
    while (offset < samples.size) {
        val end = minOf(offset + maxSamples, samples.size)
        result += VadEngine.RawSegment(
            startSample = startSample + offset,
            samples = samples.copyOfRange(offset, end),
        )
        offset = end
    }
    return result
}
