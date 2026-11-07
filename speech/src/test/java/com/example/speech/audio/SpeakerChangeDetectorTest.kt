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
    fun `splitter finds the boundary between two voices without cutting audio`() {
        val config = SpeakerChangeConfig(windowMs = 800, hopMs = 400, minSegmentMs = 600, threshold = 0.5f)
        val splitter = SpeakerChangeSplitter(config)
        // 2,4 с первого голоса, затем 2,4 с второго.
        val samples = FloatArray(4_800 * 16) { index -> if (index < 2_400 * 16) 0.5f else -0.5f }

        val boundaries = splitter.findBoundaries(samples) { window ->
            if (window.first() > 0f) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f)
        }

        assertEquals(1, boundaries.size)
        // Окно 0,8 с, шаг 0,4 с: точка между окнами, накрывающими смену, ~2,4 с ± шаг.
        assertTrue("boundary at ${boundaries[0] / 16} ms", boundaries[0] in (2_000 * 16)..(2_800 * 16))
    }

    @Test
    fun `short chunk has no boundaries`() {
        val splitter = SpeakerChangeSplitter(SpeakerChangeConfig())

        assertTrue(splitter.findBoundaries(FloatArray(1_000)) { floatArrayOf(1f, 0f) }.isEmpty())
    }
}
