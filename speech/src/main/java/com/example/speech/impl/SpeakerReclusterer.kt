package com.example.speech.impl

import com.example.speech.api.Segment
import kotlin.math.max
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
 * 3. Спикер, на которого приходится меньше [minSpeakerShare] всей речи сессии,
 *    вливается в самый похожий кластер. ERes2Net на эмоциональной речи дробит
 *    одного человека на несколько кластеров; настоящий участник разговора
 *    почти всегда говорит заметную долю времени.
 * 4. Реплики без embedding (короче 0,5 с) получают метку ближайшей по времени.
 * 5. Номера — по первому появлению.
 *
 * Если число участников известно (`expectedSpeakers`), слияние идёт до
 * ровно стольких кластеров, без порога и без правила маленьких кластеров.
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
    private val minSpeakerShare: Float = 0f,
    private val maxItems: Int = 600,
) {
    init {
        require(mergeThreshold in 0f..1f) { "mergeThreshold должен быть в 0..1, получено $mergeThreshold" }
        require(maxItems >= 2) { "maxItems должен быть >= 2, получено $maxItems" }
        require(minSpeakerShare in 0f..0.5f) { "minSpeakerShare должен быть в 0..0,5, получено $minSpeakerShare" }
    }

    fun relabel(records: List<SegmentRecord>, expectedSpeakers: Int? = null): List<Segment> {
        require(expectedSpeakers == null || expectedSpeakers >= 1) {
            "expectedSpeakers должен быть null или >= 1, получено $expectedSpeakers"
        }
        if (records.isEmpty()) return emptyList()

        val withEmbedding = records.indices.filter { records[it].embedding?.isNotEmpty() == true }
        if (withEmbedding.isEmpty() || expectedSpeakers == 1) {
            return records.map { it.segment.copy(speaker = 0) }
        }

        val clusters = initialClusters(records, withEmbedding)
        if (expectedSpeakers != null) {
            mergeUntil(clusters, expectedSpeakers)
        } else {
            mergeWhileSimilar(clusters)
            absorbSmallClusters(clusters, records)
            absorbMinorSpeakers(clusters, records)
        }

        val labelOf = HashMap<Int, Int>(records.size)
        clusters.forEachIndexed { label, cluster -> cluster.members.forEach { labelOf[it] = label } }

        val timeIndex by lazy { TimeIndex(records, withEmbedding) }
        val raw = IntArray(records.size) { index ->
            labelOf[index] ?: labelOf.getValue(timeIndex.nearest(centerOf(records[index])))
        }
        val order = HashMap<Int, Int>()
        return records.mapIndexed { index, record ->
            record.segment.copy(speaker = order.getOrPut(raw[index]) { order.size })
        }
    }

    private class Cluster(val sum: FloatArray, val members: MutableList<Int>)

    /**
     * По одному кластеру на реплику. На длинной сессии (больше [maxItems]
     * реплик — это час-полтора речи) реплики сначала кластеризуются блоками
     * подряд идущих: внутри блока сливаются самые похожие, пока блок не сожмётся
     * до своей доли от [maxItems]. Глобальная кластеризация остаётся быстрой, а в
     * один кластер не попадают разные люди (раньше на длинной записи соседи
     * склеивались по онлайн-меткам, а при их чередовании — просто подряд).
     */
    private fun initialClusters(records: List<SegmentRecord>, indices: List<Int>): MutableList<Cluster> {
        var clusters = indices.map { index ->
            Cluster(weighted(records[index]), mutableListOf(index))
        }.toMutableList()
        while (clusters.size > maxItems) {
            val blocks = clusters.chunked(maxItems)
            val perBlock = max(1, maxItems / blocks.size)
            val next = ArrayList<Cluster>(maxItems)
            for (block in blocks) {
                val merged = block.toMutableList()
                mergeUntil(merged, perBlock)
                next += merged
            }
            clusters = next
        }
        return clusters
    }

    private fun mergeWhileSimilar(clusters: MutableList<Cluster>) = mergeUntil(clusters, 1, mergeThreshold)

    /**
     * Сливает самую похожую пару, пока кластеров больше [target] и сходство ≥ [minSimilarity].
     *
     * Пара — первая по порядку `(a < b)` с максимальным косинусом; `b` вливается в `a`.
     * Сходства пар кешируются, у каждой строки `a` хранится лучший партнёр `b > a`:
     * после слияния пересчитываются строка `a` и строки, чей партнёр был `a` или `b`.
     * Итог тот же, что у полного перебора пар на каждом шаге, но без O(n³·dim).
     */
    private fun mergeUntil(clusters: MutableList<Cluster>, target: Int, minSimilarity: Float = -2f) {
        val n = clusters.size
        if (n <= target) return
        val alive = BooleanArray(n) { true }
        val norm = DoubleArray(n) { normOf(clusters[it].sum) }
        val sim = Array(n) { FloatArray(n) }
        for (a in 0 until n) {
            for (b in a + 1 until n) sim[a][b] = cosine(clusters[a].sum, norm[a], clusters[b].sum, norm[b])
        }
        val bestSim = FloatArray(n)
        val bestOf = IntArray(n)
        fun recomputeRow(a: Int) {
            var best = -3f
            var partner = -1
            for (b in a + 1 until n) {
                if (alive[b] && sim[a][b] > best) {
                    best = sim[a][b]
                    partner = b
                }
            }
            bestSim[a] = best
            bestOf[a] = partner
        }
        for (a in 0 until n) recomputeRow(a)

        var count = n
        while (count > target) {
            var a = -1
            for (row in 0 until n) {
                if (alive[row] && bestOf[row] >= 0 && (a < 0 || bestSim[row] > bestSim[a])) a = row
            }
            if (a < 0 || bestSim[a] < minSimilarity) break
            val b = bestOf[a]
            addInto(clusters[a], clusters[b])
            alive[b] = false
            count--
            norm[a] = normOf(clusters[a].sum)
            for (k in 0 until n) {
                if (!alive[k] || k == a) continue
                val value = cosine(clusters[a].sum, norm[a], clusters[k].sum, norm[k])
                if (k < a) sim[k][a] = value else sim[a][k] = value
            }
            recomputeRow(a)
            for (k in 0 until a) {
                if (!alive[k]) continue
                if (bestOf[k] == a || bestOf[k] == b) {
                    recomputeRow(k)
                } else if (sim[k][a] > bestSim[k] || (sim[k][a] == bestSim[k] && a < bestOf[k])) {
                    bestSim[k] = sim[k][a]
                    bestOf[k] = a
                }
            }
            for (k in a + 1 until b) {
                if (alive[k] && bestOf[k] == b) recomputeRow(k)
            }
        }
        val survivors = clusters.filterIndexed { index, _ -> alive[index] }
        clusters.clear()
        clusters += survivors
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

    /** Вливает кластеры с долей речи меньше [minSpeakerShare] в самый похожий. */
    private fun absorbMinorSpeakers(clusters: MutableList<Cluster>, records: List<SegmentRecord>) {
        if (minSpeakerShare <= 0f) return
        fun durationMs(cluster: Cluster): Long = cluster.members.sumOf { records[it].segment.durationMs() }
        val totalMs = clusters.sumOf { durationMs(it) }.coerceAtLeast(1L)
        while (clusters.size > 1) {
            val smallest = clusters.minBy { durationMs(it) }
            if (durationMs(smallest) >= minSpeakerShare * totalMs) return
            val nearest = clusters.filter { it !== smallest }.maxBy { cosine(smallest.sum, it.sum) }
            addInto(nearest, smallest)
            clusters.remove(smallest)
        }
    }

    /**
     * Ближайшая по времени реплика с embedding: минимум |Δцентра|, при равенстве —
     * меньший индекс. Двоичный поиск, чтобы на длинной сессии не было O(n²).
     */
    private class TimeIndex(records: List<SegmentRecord>, candidates: List<Int>) {
        private val order = candidates.sortedWith(compareBy({ centerOf(records[it]) }, { it }))
        private val centers = LongArray(order.size) { centerOf(records[order[it]]) }

        fun nearest(center: Long): Int {
            val upper = lowerBound(center)
            var best = Long.MAX_VALUE
            if (upper < centers.size) best = centers[upper] - center
            if (upper > 0) best = min(best, center - centers[upper - 1])
            var result = Int.MAX_VALUE
            for (value in longArrayOf(center - best, center + best)) {
                var i = lowerBound(value)
                while (i < centers.size && centers[i] == value) {
                    result = min(result, order[i])
                    i++
                }
            }
            return result
        }

        private fun lowerBound(value: Long): Int {
            var low = 0
            var high = centers.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (centers[mid] < value) low = mid + 1 else high = mid
            }
            return low
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

    private fun cosine(a: FloatArray, b: FloatArray): Float = cosine(a, normOf(a), b, normOf(b))

    private fun cosine(a: FloatArray, normA: Double, b: FloatArray, normB: Double): Float {
        var dot = 0.0
        for (i in a.indices) dot += a[i].toDouble() * b[i]
        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator == 0.0) 0f else (dot / denominator).toFloat()
    }

    /** Сумма квадратов в double — как в прежнем косинусе, чтобы результат совпадал бит в бит. */
    private fun normOf(v: FloatArray): Double {
        var sum = 0.0
        for (value in v) sum += value.toDouble() * value
        return sum
    }

    private fun Segment.durationMs(): Long = (endMs - startMs).coerceAtLeast(0L)

    internal companion object {
        const val DEFAULT_MERGE_THRESHOLD = 0.65f
        private const val MAX_WEIGHT_SECONDS = 8.0

        private fun centerOf(record: SegmentRecord): Long = record.segment.startMs + record.segment.endMs
    }
}
