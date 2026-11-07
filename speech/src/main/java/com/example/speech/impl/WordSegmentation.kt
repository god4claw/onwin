package com.example.speech.impl

import com.example.speech.api.Segment

/**
 * Слово распознавания с границами в сэмплах относительно начала распознанного
 * отрезка. Текст — как его выдала модель, вместе с прилипшей пунктуацией.
 */
internal data class AsrWord(
    val text: String,
    val startSample: Int,
    val endSample: Int,
)

/**
 * Собирает слова из токенов GigaAM (SentencePiece) и их меток времени.
 *
 * - Токен с ведущим пробелом (`▁` в словаре; sherpa-onnx отдаёт его пробелом)
 *   начинает новое слово, остальные дописываются к текущему.
 * - Метки времени — моменты появления токенов (шаг модели 40 мс); длительностей
 *   CTC не даёт. Начало слова — метка первого токена; конец — метка последнего
 *   **буквенного** токена плюс [endPadSamples], но не дальше начала следующего
 *   слова и не дальше [limitSamples]. Знаки препинания CTC выдаёт с запозданием
 *   (на замере «?» пришёл через 2 с после слова), поэтому конец по ним не считается.
 */
internal fun assembleWords(
    tokens: Array<String>,
    timestamps: FloatArray,
    sampleRate: Int,
    limitSamples: Int,
    endPadSamples: Int = sampleRate / 4,
): List<AsrWord> {
    if (tokens.isEmpty() || timestamps.size != tokens.size) return emptyList()

    class Draft(val text: StringBuilder, val startSample: Int, var lastContentSample: Int)

    val drafts = ArrayList<Draft>()
    for (i in tokens.indices) {
        val token = tokens[i]
        val at = (timestamps[i] * sampleRate).toInt().coerceIn(0, limitSamples)
        val startsWord = token.startsWith(" ") || token.startsWith("▁")
        val body = token.trimStart(' ', '▁')
        if (startsWord || drafts.isEmpty()) {
            drafts += Draft(StringBuilder(body), at, if (isContent(body)) at else -1)
        } else {
            val current = drafts.last()
            current.text.append(body)
            if (isContent(body)) current.lastContentSample = at
        }
    }

    val words = ArrayList<AsrWord>(drafts.size)
    for ((index, draft) in drafts.withIndex()) {
        val text = draft.text.toString().trim()
        if (text.isEmpty()) continue
        val lastContent = if (draft.lastContentSample >= 0) draft.lastContentSample else draft.startSample
        val nextStart = drafts.getOrNull(index + 1)?.startSample ?: limitSamples
        val end = minOf(lastContent + endPadSamples, nextStart, limitSamples).coerceAtLeast(draft.startSample + 1)
        if (words.isNotEmpty() && text.all(::isPunctuation)) {
            // Слово из одной пунктуации («—», «%») приклеиваем к предыдущему;
            // символы вроде «€» остаются словами.
            val previous = words.removeAt(words.lastIndex)
            words += previous.copy(text = previous.text + " " + text)
            continue
        }
        words += AsrWord(text, draft.startSample, end)
    }
    return words
}

private fun isContent(body: String): Boolean = body.any { it.isLetterOrDigit() }

private fun isPunctuation(char: Char): Boolean = when (Character.getType(char).toByte()) {
    Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
    Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
    Character.CONNECTOR_PUNCTUATION, Character.OTHER_PUNCTUATION,
    -> true
    else -> false
}

/**
 * Точки разреза VAD-чанка по смене говорящего, в сэмплах относительно чанка.
 *
 * Каждая точка [boundaries] сдвигается к самому тихому кадру в окне
 * ±[energySearchSamples] — граница реплик обычно приходится на паузу.
 * Точка отбрасывается, если кусок до или после неё короче [minPieceSamples].
 *
 * Проверяли и разрез в паузах между словами по меткам времени ASR — точка
 * смены по embedding-ам известна лишь до ±0,8 с, и разрез уходил в паузу
 * внутри фразы, отрезая её конец (WER 15,9 → 16,4–16,6 %). По энергии — нет.
 */
internal fun chooseCuts(
    boundaries: List<Int>,
    samples: FloatArray,
    minPieceSamples: Int,
    energySearchSamples: Int,
): List<Int> {
    val cuts = ArrayList<Int>()
    for (boundary in boundaries.sorted()) {
        val cut = lowestEnergyPoint(samples, boundary, energySearchSamples)
        val previous = cuts.lastOrNull() ?: 0
        if (cut - previous < minPieceSamples || samples.size - cut < minPieceSamples) continue
        cuts += cut
    }
    return cuts
}

/** Середина самого тихого кадра (10 мс) в окне ±[searchSamples] вокруг [around]. */
internal fun lowestEnergyPoint(samples: FloatArray, around: Int, searchSamples: Int, frame: Int = 160): Int {
    val from = (around - searchSamples).coerceAtLeast(0)
    val to = (around + searchSamples).coerceAtMost(samples.size)
    if (to - from < frame) return around.coerceIn(0, samples.size)
    var best = around
    var bestEnergy = Double.MAX_VALUE
    var position = from
    while (position + frame <= to) {
        var energy = 0.0
        for (i in position until position + frame) energy += samples[i].toDouble() * samples[i]
        if (energy < bestEnergy) {
            bestEnergy = energy
            best = position + frame / 2
        }
        position += frame
    }
    return best
}

/**
 * Склеивает соседние реплики одного VAD-чанка, если после финальной
 * перекластеризации у них один спикер: ложный разрез по смене говорящего
 * не должен дробить фразу. [chunkOf] — номер VAD-чанка для каждой реплики.
 */
internal fun mergeSameSpeakerNeighbors(segments: List<Segment>, chunkOf: List<Int>): List<Segment> {
    require(segments.size == chunkOf.size) { "segments=${segments.size}, chunkOf=${chunkOf.size}" }
    val merged = ArrayList<Segment>(segments.size)
    var lastChunk = Int.MIN_VALUE
    for ((index, segment) in segments.withIndex()) {
        val previous = merged.lastOrNull()
        if (previous != null && chunkOf[index] == lastChunk && previous.speaker == segment.speaker) {
            merged[merged.lastIndex] = previous.copy(
                endMs = maxOf(previous.endMs, segment.endMs),
                text = previous.text + " " + segment.text,
            )
        } else {
            merged += segment
        }
        lastChunk = chunkOf[index]
    }
    return merged
}
