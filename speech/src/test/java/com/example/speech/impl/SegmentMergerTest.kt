package com.example.speech.impl

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentMergerTest {

    @Test
    fun `short fragment of same speaker merges into previous long turn`() {
        val merged = mergeShortFragments(
            listOf(
                RawSegment(0f, 5f, speaker = 0),
                RawSegment(5.1f, 5.4f, speaker = 0),
            ),
        )

        assertEquals(1, merged.size)
        assertEquals(0f, merged[0].startSec, 0.001f)
        assertEquals(5.4f, merged[0].endSec, 0.001f)
    }

    @Test
    fun `short fragment of another speaker is not merged into previous`() {
        val merged = mergeShortFragments(
            listOf(
                RawSegment(0f, 5f, speaker = 0),
                RawSegment(5.1f, 5.4f, speaker = 1),
            ),
        )

        assertEquals(2, merged.size)
        assertEquals(1, merged[1].speaker)
    }

    @Test
    fun `short fragment of same speaker merges into following long turn`() {
        val merged = mergeShortFragments(
            listOf(
                RawSegment(0f, 0.3f, speaker = 1),
                RawSegment(0.4f, 6f, speaker = 1),
            ),
        )

        assertEquals(1, merged.size)
        assertEquals(0f, merged[0].startSec, 0.001f)
        assertEquals(6f, merged[0].endSec, 0.001f)
    }

    @Test
    fun `short fragment before another speaker is kept separate`() {
        val merged = mergeShortFragments(
            listOf(
                RawSegment(0f, 0.3f, speaker = 1),
                RawSegment(0.4f, 6f, speaker = 0),
            ),
        )

        assertEquals(2, merged.size)
        assertEquals(1, merged[0].speaker)
        assertEquals(0, merged[1].speaker)
    }

    @Test
    fun `long fragment is not merged`() {
        val merged = mergeShortFragments(
            listOf(
                RawSegment(0f, 5f, speaker = 0),
                RawSegment(5.1f, 6.0f, speaker = 0),
            ),
        )

        assertEquals(2, merged.size)
    }
}
