package com.example.speech.impl

import com.example.speech.api.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SpeakerReclustererTest {

    private val random = Random(42)

    @Test
    fun `a speaker who only answers briefly gets their own label`() {
        // Как на живой записи: один говорит длинно, второй отвечает «да», «понятно» по ~1 с.
        // Онлайн-кластеризатор такие ответы отдавал первому спикеру.
        val records = listOf(
            record(0, 5_000, A), record(5_200, 6_200, B, online = 0),
            record(6_500, 11_000, A), record(11_200, 12_100, B, online = 0),
            record(12_400, 18_000, A), record(18_200, 19_200, B, online = 0),
            record(19_500, 24_000, A), record(24_300, 25_300, B, online = 0),
        )

        val labels = SpeakerReclusterer().relabel(records).map { it.speaker }

        assertEquals(listOf(0, 1, 0, 1, 0, 1, 0, 1), labels)
    }

    @Test
    fun `a single short burst does not become a speaker`() {
        val records = listOf(
            record(0, 6_000, A), record(6_200, 7_000, C),
            record(7_200, 13_000, A), record(13_200, 19_000, A),
        )

        val labels = SpeakerReclusterer().relabel(records).map { it.speaker }

        assertEquals(listOf(0, 0, 0, 0), labels)
    }

    @Test
    fun `one speaker with noisy embeddings stays one speaker`() {
        val records = (0 until 12).map { i -> record(i * 3_000L, i * 3_000L + 2_500, A, noise = 0.35f) }

        val labels = SpeakerReclusterer().relabel(records).map { it.speaker }.toSet()

        assertEquals(setOf(0), labels)
    }

    @Test
    fun `segment without embedding takes the label of the nearest one in time`() {
        val records = listOf(
            record(0, 5_000, A), record(5_100, 9_000, B), record(9_100, 9_400, null),
            record(9_500, 14_000, B), record(20_000, 25_000, A),
        )

        val labels = SpeakerReclusterer().relabel(records).map { it.speaker }

        assertEquals(listOf(0, 1, 1, 1, 0), labels)
    }

    @Test
    fun `labels follow first appearance and keep segment data`() {
        val records = listOf(record(0, 4_000, B), record(4_200, 9_000, A), record(9_200, 13_000, B))

        val result = SpeakerReclusterer().relabel(records)

        assertEquals(listOf(0, 1, 0), result.map { it.speaker })
        assertEquals(records.map { it.segment.copy(speaker = 0) }, result.map { it.copy(speaker = 0) })
    }

    @Test
    fun `empty and embedding-less sessions`() {
        assertEquals(emptyList<Segment>(), SpeakerReclusterer().relabel(emptyList()))

        val noEmbeddings = listOf(record(0, 300, null, online = 2), record(400, 700, null, online = 1))
        assertEquals(listOf(0, 0), SpeakerReclusterer().relabel(noEmbeddings).map { it.speaker })
    }

    @Test
    fun `expected speaker count forces exactly that many clusters`() {
        // Три голоса, но пользователь сказал «двое»: самые похожие сливаются.
        val records = listOf(
            record(0, 4_000, A), record(4_200, 8_000, B), record(8_200, 12_000, C),
            record(12_200, 16_000, A), record(16_200, 20_000, B),
        )

        val two = SpeakerReclusterer().relabel(records, expectedSpeakers = 2).map { it.speaker }
        val one = SpeakerReclusterer().relabel(records, expectedSpeakers = 1).map { it.speaker }
        val auto = SpeakerReclusterer().relabel(records).map { it.speaker }

        assertEquals(2, two.toSet().size)
        assertEquals(setOf(0), one.toSet())
        assertEquals(3, auto.toSet().size)
    }

    @Test
    fun `expected speakers keeps a short distinct speaker that auto mode would absorb`() {
        val records = listOf(
            record(0, 6_000, A), record(6_200, 7_000, C),
            record(7_200, 13_000, A), record(13_200, 19_000, A),
        )

        val labels = SpeakerReclusterer().relabel(records, expectedSpeakers = 2).map { it.speaker }

        assertEquals(listOf(0, 1, 0, 0), labels)
    }

    @Test
    fun `a speaker with a tiny share of the talk is merged into the most similar one`() {
        // A и B — настоящие собеседники; C — «спикер» из эмоциональных реплик A:
        // 3 с из 41 с речи (~7 %), длиннее 2,5 с — старое правило его не трогает.
        val nearA = floatArrayOf(0.5f, 0.1f, 0f, 0f, 0.05f, 0f, 0.8f, 0.2f)
        val records = listOf(
            record(0, 10_000, A), record(10_200, 20_000, B), record(20_200, 21_700, nearA, noise = 0f),
            record(21_900, 31_000, A), record(31_200, 40_000, B), record(40_200, 41_700, nearA, noise = 0f),
        )

        val plain = SpeakerReclusterer().relabel(records).map { it.speaker }
        val shared = SpeakerReclusterer(minSpeakerShare = 0.08f).relabel(records).map { it.speaker }

        assertEquals(3, plain.toSet().size)
        assertEquals(listOf(0, 1, 0, 0, 1, 0), shared)
    }

    @Test
    fun `minimum share keeps a real short-answer speaker above the share`() {
        val records = listOf(
            record(0, 5_000, A), record(5_200, 6_200, B, online = 0),
            record(6_500, 11_000, A), record(11_200, 12_100, B, online = 0),
            record(12_400, 18_000, A), record(18_200, 19_200, B, online = 0),
            record(19_500, 24_000, A), record(24_300, 25_300, B, online = 0),
        )

        // B — ~16 % речи: выше порога 12 %, остаётся отдельным спикером.
        val labels = SpeakerReclusterer(minSpeakerShare = 0.12f).relabel(records).map { it.speaker }

        assertEquals(listOf(0, 1, 0, 1, 0, 1, 0, 1), labels)
    }

    @Test
    fun `merge threshold controls how eagerly clusters merge`() {
        val records = (0 until 8).map { i -> record(i * 3_000L, i * 3_000L + 2_500, A, noise = 0.35f) }

        val strict = SpeakerReclusterer(mergeThreshold = 0.99f).relabel(records).map { it.speaker }.toSet()

        assertTrue("strict threshold should split noisy speaker: $strict", strict.size > 1)
    }

    @Test
    fun `cached merging gives exactly the labels of the full pair search`() {
        // Сессии до maxItems реплик: новая реализация обязана совпасть с прежней один в один.
        val rnd = Random(7)
        repeat(30) { seed ->
            val speakers = 1 + rnd.nextInt(5)
            val voices = List(speakers) { FloatArray(16) { rnd.nextFloat() - 0.5f } }
            val noise = 0.05f + rnd.nextFloat() * 0.6f
            var t = 0L
            var voice = 0
            val records = List(20 + rnd.nextInt(280)) {
                if (rnd.nextFloat() < 0.4f) voice = rnd.nextInt(speakers)
                val duration = 300L + rnd.nextInt(6_000)
                val base = voices[voice]
                val embedding = if (rnd.nextFloat() < 0.1f) null else FloatArray(base.size) { base[it] + (rnd.nextFloat() - 0.5f) * 2 * noise }
                SegmentRecord(
                    segment = Segment(speaker = rnd.nextInt(3), startMs = t, endMs = t + duration, text = "s$seed-$it"),
                    embedding = embedding,
                ).also { t += duration + rnd.nextInt(800) }
            }
            val configs = listOf(
                Triple(0.65f, 0f, null), Triple(0.5f, 0f, null), Triple(0.65f, 0.15f, null),
                Triple(0.65f, 0.15f, 2), Triple(0.65f, 0f, 3),
            )
            for ((threshold, share, expected) in configs) {
                val reference = ReferenceReclusterer(mergeThreshold = threshold, minSpeakerShare = share).relabel(records, expected)
                val actual = SpeakerReclusterer(mergeThreshold = threshold, minSpeakerShare = share).relabel(records, expected)
                assertEquals("seed $seed threshold $threshold share $share expected $expected", reference, actual)
            }
        }
    }

    @Test
    fun `twenty thousand segments with wrong online labels keep speakers apart`() {
        // 20 000 реплик трёх человек (много часов речи), онлайн-метки чередуются наугад.
        // Прежняя склейка соседей по меткам сливала бы разных людей.
        val rnd = Random(11)
        val voices = listOf(A, B, C)
        var voice = 0
        val truth = IntArray(20_000)
        val records = List(truth.size) { i ->
            if (rnd.nextFloat() < 0.35f) voice = rnd.nextInt(3)
            truth[i] = voice
            record(i * 1_440L, i * 1_440L + 1_200, voices[voice], online = i % 2, noise = 0.25f)
        }

        val startedAt = System.nanoTime()
        val labels = SpeakerReclusterer().relabel(records).map { it.speaker }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(3, labels.toSet().size)
        val mapping = labels.indices.groupBy { labels[it] }.mapValues { (_, idx) ->
            idx.groupingBy { truth[it] }.eachCount().maxBy { it.value }.key
        }
        val correct = labels.indices.count { mapping.getValue(labels[it]) == truth[it] }
        assertTrue("accuracy $correct / ${labels.size}", correct >= labels.size * 0.99)
        assertTrue("took $elapsedMs ms", elapsedMs < 15_000)
    }

    @Test
    fun `a long session stays fast and correct`() {
        // ~1,5 ч диалога: 2000 реплик. Больше maxItems — сначала склейка соседей.
        val records = (0 until 2_000).map { i ->
            val voice = if ((i / 3) % 2 == 0) A else B
            record(i * 2_500L, i * 2_500L + 2_000, voice, online = if (voice === A) 0 else 1)
        }

        val startedAt = System.nanoTime()
        val labels = SpeakerReclusterer().relabel(records).map { it.speaker }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(setOf(0, 1), labels.toSet())
        assertTrue("took $elapsedMs ms", elapsedMs < 3_000)
    }

    private fun record(
        startMs: Long,
        endMs: Long,
        voice: FloatArray?,
        online: Int = 0,
        noise: Float = 0.1f,
    ) = SegmentRecord(
        segment = Segment(speaker = online, startMs = startMs, endMs = endMs, text = "t$startMs"),
        embedding = voice?.let { base -> FloatArray(base.size) { base[it] + (random.nextFloat() - 0.5f) * 2 * noise } },
    )

    private companion object {
        // Три «голоса» в 8-мерном пространстве: попарный косинус ~0.
        val A = floatArrayOf(1f, 0.2f, 0f, 0f, 0.1f, 0f, 0f, 0f)
        val B = floatArrayOf(0f, 0f, 1f, 0.2f, 0f, 0.1f, 0f, 0f)
        val C = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1f, 0.3f)
    }
}
