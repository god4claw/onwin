package com.example.speech.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerChangeDetectorTest {

    @Test
    fun `finds boundary between two speaker embeddings`() {
        val boundaries = findSpeakerChangeBoundaries(
            embeddings = listOf(
                floatArrayOf(1f, 0f, 0f),
                floatArrayOf(0.95f, 0.05f, 0f),
                floatArrayOf(0f, 1f, 0f),
                floatArrayOf(0.05f, 0.95f, 0f),
            ),
            windowStarts = listOf(0, 800, 1_600, 2_400),
            windowSamples = 1_600,
            audioSamples = 4_000,
            minSegmentSamples = 800,
            threshold = 0.5f,
        )

        assertEquals(listOf(2_000), boundaries)
    }

    @Test
    fun `keeps a single speaker without boundaries`() {
        val boundaries = findSpeakerChangeBoundaries(
            embeddings = listOf(
                floatArrayOf(1f, 0f, 0f),
                floatArrayOf(0.99f, 0.01f, 0f),
                floatArrayOf(0.97f, 0.03f, 0f),
            ),
            windowStarts = listOf(0, 800, 1_600),
            windowSamples = 1_600,
            audioSamples = 3_200,
            minSegmentSamples = 800,
            threshold = 0.45f,
        )

        assertTrue(boundaries.isEmpty())
    }

    @Test
    fun `does not cut too close to chunk edge`() {
        val boundaries = findSpeakerChangeBoundaries(
            embeddings = listOf(
                floatArrayOf(1f, 0f, 0f),
                floatArrayOf(0.95f, 0.05f, 0f),
                floatArrayOf(0f, 1f, 0f),
                floatArrayOf(0.05f, 0.95f, 0f),
            ),
            windowStarts = listOf(0, 800, 1_600, 2_400),
            windowSamples = 1_600,
            audioSamples = 3_000,
            minSegmentSamples = 1_200,
            threshold = 0.5f,
        )

        assertTrue(boundaries.isEmpty())
    }

    @Test
    fun `keeps only the deepest valley when two candidates are close`() {
        val boundaries = findSpeakerChangeBoundaries(
            embeddings = listOf(
                floatArrayOf(1f, 0f, 0f),
                floatArrayOf(0.95f, 0.05f, 0f),
                floatArrayOf(0.1f, 0.9f, 0f),
                floatArrayOf(0f, 1f, 0f),
                floatArrayOf(0.05f, 0.95f, 0f),
            ),
            windowStarts = listOf(0, 800, 1_600, 2_400, 3_200),
            windowSamples = 1_600,
            audioSamples = 5_000,
            minSegmentSamples = 1_000,
            threshold = 0.9f,
        )

        assertEquals(1, boundaries.size)
    }

    @Test
    fun `missing embeddings do not crash detection`() {
        val boundaries = findSpeakerChangeBoundaries(
            embeddings = listOf(
                floatArrayOf(1f, 0f),
                null,
                floatArrayOf(0f, 1f),
            ),
            windowStarts = listOf(0, 800, 1_600),
            windowSamples = 1_600,
            audioSamples = 3_200,
            minSegmentSamples = 500,
            threshold = 0.5f,
        )

        assertTrue(boundaries.isEmpty())
    }

    @Test
    fun `splitter cuts a chunk into contiguous pieces`() {
        val config = SpeakerChangeConfig(
            windowMs = 800,
            hopMs = 400,
            minSegmentMs = 600,
            threshold = 0.5f,
            energySearchMs = 0,
        )
        val splitter = SpeakerChangeSplitter(config)
        val chunk = SpeechChunk(
            startSample = 1_000L,
            samples = FloatArray(4_800 * 16) { index ->
                if (index < 2_400 * 16) 0.5f else -0.5f
            },
        )

        val pieces = splitter.split(chunk) { samples ->
            if (samples.first() > 0f) {
                floatArrayOf(1f, 0f)
            } else {
                floatArrayOf(0f, 1f)
            }
        }

        assertEquals(2, pieces.size)
        assertEquals(1_000L, pieces[0].startSample)
        assertEquals(1_000L + 41_600L, pieces[1].startSample)
        assertEquals(41_600, pieces[0].samples.size)
        assertEquals(4_800 * 16 - 41_600, pieces[1].samples.size)

        val joined = FloatArray(chunk.samples.size)
        var offset = 0
        for (piece in pieces) {
            piece.samples.copyInto(joined, offset)
            offset += piece.samples.size
        }
        assertTrue(chunk.samples.contentEquals(joined))
    }

    @Test
    fun `short chunk is not split`() {
        val config = SpeakerChangeConfig(
            windowMs = 1_600,
            hopMs = 800,
            minSegmentMs = 1_500,
            threshold = 0.45f,
            energySearchMs = 0,
        )
        val splitter = SpeakerChangeSplitter(config)
        val chunk = SpeechChunk(startSample = 0L, samples = FloatArray(1_000))

        val pieces = splitter.split(chunk) { floatArrayOf(1f, 0f) }

        assertEquals(1, pieces.size)
        assertEquals(1_000, pieces[0].samples.size)
    }
}
