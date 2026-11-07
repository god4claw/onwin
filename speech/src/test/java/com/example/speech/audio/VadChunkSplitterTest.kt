package com.example.speech.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class VadChunkSplitterTest {

    @Test
    fun `short segment is returned unchanged`() {
        val samples = floatArrayOf(1f, 2f, 3f)
        val chunks = splitLongVadSegment(startSample = 100L, samples = samples, maxSamples = 10)

        assertEquals(1, chunks.size)
        assertEquals(100L, chunks[0].startSample)
        assertEquals(3, chunks[0].samples.size)
    }

    @Test
    fun `long segment is split with correct offsets`() {
        val samples = FloatArray(25) { it.toFloat() }
        val chunks = splitLongVadSegment(startSample = 1_000L, samples = samples, maxSamples = 10)

        assertEquals(3, chunks.size)
        assertEquals(listOf(1_000L, 1_010L, 1_020L), chunks.map { it.startSample })
        assertEquals(listOf(10, 10, 5), chunks.map { it.samples.size })
        assertEquals(0f, chunks[0].samples.first(), 0f)
        assertEquals(24f, chunks[2].samples.last(), 0f)
    }

    @Test
    fun `exact multiple does not produce empty chunk`() {
        val chunks = splitLongVadSegment(startSample = 0L, samples = FloatArray(20), maxSamples = 10)

        assertEquals(2, chunks.size)
        assertEquals(listOf(10, 10), chunks.map { it.samples.size })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero max samples is rejected`() {
        splitLongVadSegment(startSample = 0L, samples = FloatArray(1), maxSamples = 0)
    }
}
