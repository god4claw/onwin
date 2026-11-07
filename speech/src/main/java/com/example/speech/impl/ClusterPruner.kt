package com.example.speech.impl

import kotlin.math.ceil

/**
 * Временной интервал, привязанный к кластеру диаризации.
 * Используется общей постобработкой batch и streaming.
 */
internal data class SpeakerSpan(
    val startMs: Long,
    val endMs: Long,
    val speaker: Int,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * Строит отображение «старый кластер → доминирующий кластер» для мелких кластеров.
 *
 * Кластер считается доминирующим, если он набрал хотя бы
 * `max(minSupportMs, minFraction * общая длительность речи)`. Остальные кластеры
 * считаются мусором от перекластеризации и приклеиваются целиком к тому
 * доминирующему кластеру, с которым у них больше временного контакта.
 *
 * Функция намеренно не зависит от `Segment`/`RawSegment`, чтобы одна и та же
 * логика работала и в batch, и в streaming.
 */
internal fun planClusterPruning(
    spans: List<SpeakerSpan>,
    minSupportMs: Long = DEFAULT_MIN_SUPPORT_MS,
    minFraction: Float = DEFAULT_MIN_FRACTION,
): Map<Int, Int> {
    require(minSupportMs >= 0L) { "minSupportMs должен быть >= 0, получено $minSupportMs" }
    require(minFraction in 0f..1f) {
        "minFraction должен быть в 0..1, получено $minFraction"
    }
    if (spans.isEmpty()) return emptyMap()

    val durationBySpeaker = HashMap<Int, Long>()
    for (span in spans) {
        if (span.durationMs <= 0L) continue
        durationBySpeaker[span.speaker] =
            (durationBySpeaker[span.speaker] ?: 0L) + span.durationMs
    }
    if (durationBySpeaker.isEmpty()) return spans.map { it.speaker to it.speaker }.toMap()

    val totalMs = durationBySpeaker.values.sum()
    val fractionSupportMs = ceil(totalMs.toDouble() * minFraction).toLong()
    val supportMs = maxOf(minSupportMs, fractionSupportMs)

    val dominant = durationBySpeaker
        .filterValues { it >= supportMs }
        .keys
        .ifEmpty {
            // На очень коротком аудио ни один кластер может не дотянуть до порога.
            // Тогда оставляем самый длинный, чтобы не плодить «спикеров» из шума.
            setOf(durationBySpeaker.maxByOrNull { it.value }!!.key)
        }

    if (dominant.size == 1 && dominant.size == durationBySpeaker.size) {
        val only = dominant.first()
        return mapOf(only to only)
    }

    val spansBySpeaker = spans.groupBy { it.speaker }
    val dominantSpans = spans.filter { it.speaker in dominant }
    val mapping = HashMap<Int, Int>(durationBySpeaker.size)

    for (speaker in durationBySpeaker.keys) {
        if (speaker in dominant) {
            mapping[speaker] = speaker
            continue
        }

        val tinySpans = spansBySpeaker[speaker].orEmpty()
        var bestSpeaker = dominant.min()
        var bestGap = Long.MAX_VALUE
        var bestOverlap = Long.MIN_VALUE

        // Сначала выбираем доминирующий кластер с максимальным перекрытием,
        // затем — с минимальным суммарным зазором до реплик мелкого кластера.
        for (candidate in dominant.sorted()) {
            val candidateSpans = dominantSpans.filter { it.speaker == candidate }
            var overlap = 0L
            var gap = 0L
            for (span in tinySpans) {
                var nearestGap = Long.MAX_VALUE
                for (other in candidateSpans) {
                    val currentOverlap = overlapMs(span, other)
                    if (currentOverlap > 0L) {
                        overlap += currentOverlap
                        nearestGap = 0L
                        break
                    }
                    nearestGap = minOf(nearestGap, gapMs(span, other))
                }
                if (nearestGap != Long.MAX_VALUE) gap += nearestGap
            }

            if (overlap > bestOverlap || (overlap == bestOverlap && gap < bestGap)) {
                bestSpeaker = candidate
                bestGap = gap
                bestOverlap = overlap
            }
        }

        mapping[speaker] = bestSpeaker
    }

    return mapping
}

/**
 * Применяет отображение pruning к сырым сегментам batch-диаризации.
 * Новые номера спикеров сжимаются в порядке первого появления.
 */
internal fun pruneTinyClusters(
    segments: List<RawSegment>,
    minSupportMs: Long = DEFAULT_MIN_SUPPORT_MS,
    minFraction: Float = DEFAULT_MIN_FRACTION,
): List<RawSegment> = pruneTinyClustersOf(
    segments = segments,
    startMs = { (it.startSec * 1000f).toLong() },
    endMs = { (it.endSec * 1000f).toLong() },
    speaker = { it.speaker },
    withSpeaker = { segment, speaker -> segment.copy(speaker = speaker) },
    minSupportMs = minSupportMs,
    minFraction = minFraction,
)

/**
 * Общая постобработка для любых сегментов с таймкодами и номером кластера.
 * Возвращает новый список, сжимая номера спикеров в порядке первого появления.
 */
internal fun <T> pruneTinyClustersOf(
    segments: List<T>,
    startMs: (T) -> Long,
    endMs: (T) -> Long,
    speaker: (T) -> Int,
    withSpeaker: (T, Int) -> T,
    minSupportMs: Long = DEFAULT_MIN_SUPPORT_MS,
    minFraction: Float = DEFAULT_MIN_FRACTION,
): List<T> {
    if (segments.isEmpty()) return segments
    val spans = segments.map {
        SpeakerSpan(
            startMs = startMs(it),
            endMs = endMs(it),
            speaker = speaker(it),
        )
    }
    val mapping = planClusterPruning(spans, minSupportMs, minFraction)
    val idMap = HashMap<Int, Int>()
    return segments.map { segment ->
        val prunedSpeaker = mapping[speaker(segment)] ?: speaker(segment)
        val compactSpeaker = idMap.getOrPut(prunedSpeaker) { idMap.size }
        withSpeaker(segment, compactSpeaker)
    }
}

private fun overlapMs(a: SpeakerSpan, b: SpeakerSpan): Long =
    (minOf(a.endMs, b.endMs) - maxOf(a.startMs, b.startMs)).coerceAtLeast(0L)

private fun gapMs(a: SpeakerSpan, b: SpeakerSpan): Long = when {
    a.endMs <= b.startMs -> b.startMs - a.endMs
    b.endMs <= a.startMs -> a.startMs - b.endMs
    else -> 0L
}

internal const val DEFAULT_MIN_SUPPORT_MS = 3_000L
internal const val DEFAULT_MIN_FRACTION = 0.05f
