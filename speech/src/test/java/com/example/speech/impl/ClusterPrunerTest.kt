package com.example.speech.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterPrunerTest {

    @Test
    fun `tiny cluster is merged into nearest dominant speaker`() {
        val pruned = pruneTinyClusters(
            listOf(
                RawSegment(0f, 40f, speaker = 0),
                RawSegment(40.5f, 60f, speaker = 1),
                RawSegment(60.2f, 61.2f, speaker = 2),
            ),
        )

        assertEquals(3, pruned.size)
        assertEquals(listOf(0, 1, 1), pruned.map { it.speaker })
    }

    @Test
    fun `speaker ids are compacted after pruning`() {
        val pruned = pruneTinyClusters(
            listOf(
                RawSegment(0f, 20f, speaker = 5),
                RawSegment(20f, 40f, speaker = 9),
                RawSegment(40.5f, 41.5f, speaker = 2),
            ),
        )

        assertEquals(listOf(0, 1, 1), pruned.map { it.speaker })
    }

    @Test
    fun `dominant clusters are left untouched`() {
        val pruned = pruneTinyClusters(
            listOf(
                RawSegment(0f, 20f, speaker = 0),
                RawSegment(20f, 40f, speaker = 1),
            ),
        )

        assertEquals(listOf(0, 1), pruned.map { it.speaker })
    }

    @Test
    fun `fraction support can keep a small but real cluster`() {
        val mapping = planClusterPruning(
            spans = listOf(
                SpeakerSpan(0, 100_000, speaker = 0),
                SpeakerSpan(100_000, 104_000, speaker = 1),
            ),
            minSupportMs = 3_000,
            minFraction = 0.03f,
        )

        assertEquals(mapOf(0 to 0, 1 to 1), mapping)
    }

    @Test
    fun `tiny segment overlapping dominant cluster follows overlap`() {
        val mapping = planClusterPruning(
            spans = listOf(
                SpeakerSpan(0, 40_000, speaker = 0),
                SpeakerSpan(40_000, 60_000, speaker = 1),
                SpeakerSpan(39_500, 40_500, speaker = 2),
            ),
            minSupportMs = 3_000,
            minFraction = 0.05f,
        )

        assertEquals(0, mapping[2])
    }

    @Test
    fun `all clusters below support collapse to the largest one`() {
        val mapping = planClusterPruning(
            spans = listOf(
                SpeakerSpan(0, 1_000, speaker = 0),
                SpeakerSpan(1_000, 2_000, speaker = 1),
                SpeakerSpan(2_000, 4_000, speaker = 2),
            ),
            minSupportMs = 3_000,
            minFraction = 0.05f,
        )

        assertEquals(2, mapping[0])
        assertEquals(2, mapping[1])
        assertEquals(2, mapping[2])
    }

    @Test
    fun `generic pruning works for arbitrary segment types`() {
        data class Item(val start: Long, val end: Long, val speaker: Int, val text: String)

        val result = pruneTinyClustersOf(
            segments = listOf(
                Item(0, 40_000, 3, "a"),
                Item(40_000, 60_000, 4, "b"),
                Item(60_100, 61_000, 7, "c"),
            ),
            startMs = { it.start },
            endMs = { it.end },
            speaker = { it.speaker },
            withSpeaker = { item, speaker -> item.copy(speaker = speaker) },
        )

        assertEquals(listOf(0, 1, 1), result.map { it.speaker })
        assertEquals(listOf("a", "b", "c"), result.map { it.text })
    }

    @Test
    fun `empty input stays empty`() {
        assertTrue(pruneTinyClusters(emptyList()).isEmpty())
    }
}
