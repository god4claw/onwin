package com.example.speech.audio

/**
 * Режет слишком длинный сегмент VAD на куски не длиннее [maxSamples].
 *
 * Silero VAD в sherpa-onnx 1.13.0 в некоторых сценариях возвращает сегмент
 * длиннее `maxSpeechDuration` (на полном Pear — 172 с при лимите 25 с; в
 * диалогах, где паузы между репликами короче `minSilenceMs`, — до 105 с).
 * Этот защитный split не даёт одному utterance разрастись до минут.
 *
 * Разрез ставится в середину самого тихого окна [quietFrameSamples] среди
 * последних [searchSamples] перед лимитом, а не ровно по лимиту: слепой
 * разрез каждые 25 с попадал внутрь слова, и GigaAM портил слово с обеих
 * сторон. На 12 диалогах с живыми голосами это WER 4,96 → 4,05 %
 * (docs/bench-asr-edges.md). Если окно не помещается в зону поиска, режем
 * по лимиту, как раньше.
 */
internal fun splitLongVadSegment(
    startSample: Long,
    samples: FloatArray,
    maxSamples: Int,
    searchSamples: Int = maxSamples / 5 * 2,
    quietFrameSamples: Int = QUIET_FRAME_SAMPLES,
): List<VadEngine.RawSegment> {
    require(maxSamples >= 1) { "maxSamples должен быть >= 1, получено $maxSamples" }
    if (samples.size <= maxSamples) {
        return listOf(VadEngine.RawSegment(startSample, samples))
    }

    val result = ArrayList<VadEngine.RawSegment>(samples.size / maxSamples + 2)
    var offset = 0
    while (samples.size - offset > maxSamples) {
        val cut = quietestCut(samples, offset, offset + maxSamples, searchSamples, quietFrameSamples)
        result += VadEngine.RawSegment(startSample + offset, samples.copyOfRange(offset, cut))
        offset = cut
    }
    result += VadEngine.RawSegment(startSample + offset, samples.copyOfRange(offset, samples.size))
    return result
}

/** Середина самого тихого окна [frame] (шаг 10 мс) в зоне `(limit - search, limit]`; не влезает — сам [limit]. */
private fun quietestCut(samples: FloatArray, offset: Int, limit: Int, search: Int, frame: Int): Int {
    val from = maxOf(offset + 1, limit - search)
    if (frame <= 0 || limit - from < frame) return limit

    // Префиксные суммы квадратов: энергия любого окна за O(1).
    val prefix = DoubleArray(limit - from + 1)
    for (i in from until limit) prefix[i - from + 1] = prefix[i - from] + samples[i].toDouble() * samples[i]

    var best = limit
    var bestEnergy = Double.MAX_VALUE
    var position = from
    while (position + frame <= limit) {
        val energy = prefix[position + frame - from] - prefix[position - from]
        if (energy < bestEnergy) {
            bestEnergy = energy
            best = position + frame / 2
        }
        position += QUIET_HOP_SAMPLES
    }
    return best
}

/** 100 мс: короче паузы между фразами, но длиннее смычки взрывного согласного внутри слова. */
private const val QUIET_FRAME_SAMPLES = VadSegmenter.SAMPLE_RATE / 10
private const val QUIET_HOP_SAMPLES = VadSegmenter.SAMPLE_RATE / 100
