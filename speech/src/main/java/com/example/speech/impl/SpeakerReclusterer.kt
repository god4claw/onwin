package com.example.speech.impl

import com.example.speech.api.Segment
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Финальные метки спикеров для streaming-сессии: перекластеризация всех
 * embedding-ов сессии разом, когда стрим закончился.
 *
 * Зачем: онлайн-кластеризатор решает по одной реплике и только по прошлому.
 * Чтобы не плодить «спикеров» из шума, он ждёт 2,5 с речи нового голоса и до
 * этого отдаёт реплики ближайшему кластеру, а финальный pruning выкидывал
 * кластеры короче 3 с. На коротких разговорах это сливало собеседника,
 * который отвечает коротко («да», «понятно»), с основным. Перекластеризация
 * видит все реплики сразу:
 *
 * 1. Агломеративная кластеризация по центроидам: кластер — взвешенная сумма
 *    нормированных embedding-ов (вес — √длительности, не больше √8 с), сходство —
 *    косинус сумм; сливаем самую похожую пару, пока сходство ≥ [mergeThreshold].
 * 2. Маленький кластер (< [minClusterMs] речи) остаётся отдельным спикером,
 *    только если в нём ≥ [minClusterSegments] реплик и он не похож ни на кого
 *    (сходство < [keepSimilarity]); иначе вливается в самый похожий.
 * 3. Реплики без embedding (короче 0,5 с) получают метку ближайшей по времени.
 * 4. Номера — по первому появлению.
 *
 * Параметры подобраны на золотом наборе и проверены на отложенной выборке,
 * которую подбор не видел: ошибка атрибуции 25,1 → 12,7 %, верное число
 * спикеров 9 → 13 из 18 (см. docs/bench-recluster.md).
 */
internal class SpeakerReclusterer(
    private val mergeThreshold: Float = DEFAULT_MERGE_THRESHOLD,
    private val minClusterMs: Long = 2_500L,
    private val keepSimilarity: Float = 0.40f,
    private val minClusterSegments: Int = 2,
    private val maxItems: Int = 600,
) {
    init {
        require(mergeThreshold in 0f..1f) { "mergeThreshold должен быть в 0..1, получено $mergeThreshold" }
        require(maxItems >= 2) { "maxItems должен быть >= 2, получено $maxItems" }
    }

    fun relabel(records: List<SegmentRecord>): List<Segment> {
        if (records.isEmpty()) return emptyList()

        val withEmbedding = records.indices.filter { records[it].embedding?.isNotEmpty() == true }
        if (withEmbedding.isEmpty()) return records.map { it.segment.copy(speaker = 0) }

        val clusters = initialClusters(records, withEmbedding)
        mergeWhileSimilar(clusters)
        absorbSmallClusters(clusters, records)

        val labelOf = HashMap<Int, Int>(records.size)
        clusters.forEachIndexed { label, cluster -> cluster.members.forEach { labelOf[it] = label } }

        val raw = IntArray(records.size) { index ->
            labelOf[index] ?: labelOf.getValue(nearestInTime(records, index, withEmbedding))
        }
        val order = HashMap<Int, Int>()
        return records.mapIndexed { index, record ->
            record.segment.copy(speaker = order.getOrPut(raw[index]) { order.size })
        }
    }

    private class Cluster(val sum: FloatArray, val members: MutableList<Int>)

    /**
     * По одному кластеру на реплику. На очень длинной сессии (больше
     * [maxItems] реплик) соседние реплики с одной онлайн-меткой сначала
     * склеиваются, чтобы кластеризация оставалась быстрой (O(n²) на слияние).
     */
    private fun initialClusters(records: List<SegmentRecord>, indices: List<Int>): MutableList<Cluster> {
        val clusters = indices.map { index ->
            Cluster(weighted(records[index]), mutableListOf(index))
        }.toMutableList()
        while (clusters.size > maxItems) {
            var merged = false
            var i = 0
            while (i < clusters.size - 1 && clusters.size > maxItems) {
                val left = clusters[i]
                val right = clusters[i + 1]
                if (records[left.members.last()].segment.speaker == records[right.members.first()].segment.speaker) {
                    addInto(left, right)
                    clusters.removeAt(i + 1)
                    merged = true
                } else {
                    i++
                }
            }
            if (!merged) {
                // Онлайн-метки чередуются каждую реплику: склеиваем просто соседей.
                var j = 0
                while (j < clusters.size - 1 && clusters.size > maxItems) {
                    addInto(clusters[j], clusters[j + 1])
                    clusters.removeAt(j + 1)
                    j++
                }
            }
        }
        return clusters
    }

    private fun mergeWhileSimilar(clusters: MutableList<Cluster>) {
        while (clusters.size > 1) {
            var bestA = -1
            var bestB = -1
            var best = -2f
            for (a in 0 until clusters.size - 1) {
                for (b in a + 1 until clusters.size) {
                    val similarity = cosine(clusters[a].sum, clusters[b].sum)
                    if (similarity > best) {
                        best = similarity
                        bestA = a
                        bestB = b
                    }
                }
            }
            if (best < mergeThreshold) return
            addInto(clusters[bestA], clusters[bestB])
            clusters.removeAt(bestB)
        }
    }

    private fun absorbSmallClusters(clusters: MutableList<Cluster>, records: List<SegmentRecord>) {
        fun durationMs(cluster: Cluster): Long =
            cluster.members.sumOf { records[it].segment.durationMs() }

        while (clusters.size > 1) {
            val absorbed = clusters
                .sortedBy { durationMs(it) }
                .firstOrNull { small ->
                    if (durationMs(small) >= minClusterMs) return@firstOrNull false
                    val nearest = clusters.filter { it !== small }.maxBy { cosine(small.sum, it.sum) }
                    val similarity = cosine(small.sum, nearest.sum)
                    val distinctSpeaker = similarity < keepSimilarity && small.members.size >= minClusterSegments
                    if (distinctSpeaker) return@firstOrNull false
                    addInto(nearest, small)
                    true
                } ?: return
            clusters.remove(absorbed)
        }
    }

    private fun nearestInTime(records: List<SegmentRecord>, index: Int, candidates: List<Int>): Int {
        val center = records[index].segment.let { it.startMs + it.endMs }
        return candidates.minBy { other ->
            abs(records[other].segment.let { it.startMs + it.endMs } - center)
        }
    }

    private fun weighted(record: SegmentRecord): FloatArray {
        val embedding = checkNotNull(record.embedding)
        var norm = 0.0
        for (value in embedding) norm += value.toDouble() * value
        val seconds = min(record.segment.durationMs() / 1000.0, MAX_WEIGHT_SECONDS)
        val scale = if (norm == 0.0) 0.0 else sqrt(seconds.coerceAtLeast(0.0)) / sqrt(norm)
        return FloatArray(embedding.size) { (embedding[it] * scale).toFloat() }
    }

    private fun addInto(target: Cluster, source: Cluster) {
        for (i in target.sum.indices) target.sum[i] += source.sum[i]
        target.members += source.members
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            normA += a[i].toDouble() * a[i]
            normB += b[i].toDouble() * b[i]
        }
        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator == 0.0) 0f else (dot / denominator).toFloat()
    }

    private fun Segment.durationMs(): Long = (endMs - startMs).coerceAtLeast(0L)

    internal companion object {
        const val DEFAULT_MERGE_THRESHOLD = 0.60f
        private const val MAX_WEIGHT_SECONDS = 8.0
    }
}
