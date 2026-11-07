package com.example.speech.impl

/**
 * Сырой сегмент диаризации: секунды от начала файла и номер кластера.
 * Живёт рядом с [mergeShortFragments], чтобы логику склейки можно было тестировать без native-моделей.
 */
internal data class RawSegment(
    val startSec: Float,
    val endSec: Float,
    val speaker: Int,
)

/**
 * Приклеивает очень короткие обрывки диаризации к соседней длинной реплике.
 *
 * Склейка допускается только внутри одного кластера: иначе короткая реплика
 * второго собеседника («да», «ага», «понял») уедет первому и испортит DER.
 */
internal fun mergeShortFragments(segments: List<RawSegment>): List<RawSegment> {
    if (segments.size < 2) return segments
    val sorted = segments.sortedBy { it.startSec }
    val result = ArrayList<RawSegment>(sorted.size)
    var index = 0

    while (index < sorted.size) {
        val current = sorted[index]
        val currentDuration = current.endSec - current.startSec
        val previous = result.lastOrNull()
        val previousDuration = previous?.let { it.endSec - it.startSec } ?: 0f

        if (previous != null &&
            current.speaker == previous.speaker &&
            currentDuration < SHORT_FRAGMENT_SEC &&
            current.startSec - previous.endSec <= MAX_FRAGMENT_GAP_SEC &&
            previousDuration > currentDuration * 2f
        ) {
            result[result.lastIndex] = previous.copy(endSec = current.endSec)
            index += 1
            continue
        }

        val next = sorted.getOrNull(index + 1)
        if (next != null &&
            current.speaker == next.speaker &&
            currentDuration < SHORT_FRAGMENT_SEC &&
            next.startSec - current.endSec <= MAX_FRAGMENT_GAP_SEC &&
            (next.endSec - next.startSec) > currentDuration * 2f
        ) {
            result += next.copy(startSec = current.startSec)
            index += 2
            continue
        }

        result += current
        index += 1
    }

    return result
}

private const val SHORT_FRAGMENT_SEC = 0.7f
private const val MAX_FRAGMENT_GAP_SEC = 0.25f
