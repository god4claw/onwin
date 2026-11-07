package com.example.speech.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OnlineSpeakerClustererTest {

    @Test
    fun similarEmbeddingsShareSpeaker() {
        val clusterer = OnlineSpeakerClusterer(threshold = 0.5f)

        val first = clusterer.assign(floatArrayOf(1f, 0f, 0f))
        val second = clusterer.assign(floatArrayOf(0.98f, 0.1f, 0f))

        assertEquals(0, first.speaker)
        assertTrue(first.isNewSpeaker)
        assertEquals(0, second.speaker)
        assertFalse(second.isNewSpeaker)
        assertEquals(1, clusterer.speakerCount)
    }

    @Test
    fun differentEmbeddingCreatesNewSpeaker() {
        val clusterer = OnlineSpeakerClusterer(threshold = 0.5f)

        assertEquals(0, clusterer.assign(floatArrayOf(1f, 0f, 0f)).speaker)
        val second = clusterer.assign(floatArrayOf(0f, 1f, 0f))

        assertEquals(1, second.speaker)
        assertTrue(second.isNewSpeaker)
        assertEquals(2, clusterer.speakerCount)
    }

    @Test
    fun speakerIdsFollowFirstAppearance() {
        val clusterer = OnlineSpeakerClusterer(threshold = 0.5f)

        val a1 = clusterer.assign(floatArrayOf(1f, 0f, 0f))
        val b1 = clusterer.assign(floatArrayOf(0f, 1f, 0f))
        val a2 = clusterer.assign(floatArrayOf(0.9f, 0.1f, 0f))

        assertEquals(0, a1.speaker)
        assertEquals(1, b1.speaker)
        assertEquals(0, a2.speaker)
        assertEquals(listOf(0, 1, 0), listOf(a1.speaker, b1.speaker, a2.speaker))
    }

    @Test
    fun centroidAdaptsToSeveralEmbeddings() {
        val clusterer = OnlineSpeakerClusterer(threshold = 0.55f)

        assertEquals(0, clusterer.assign(floatArrayOf(1f, 0f, 0f)).speaker)
        assertEquals(0, clusterer.assign(floatArrayOf(0.9f, 0.2f, 0f)).speaker)
        assertEquals(0, clusterer.assign(floatArrayOf(0.8f, 0.3f, 0f)).speaker)
        assertEquals(1, clusterer.assign(floatArrayOf(0f, 1f, 0f)).speaker)

        assertEquals(2, clusterer.speakerCount)
    }

    @Test
    fun maxSpeakersLimitsClusterCount() {
        val clusterer = OnlineSpeakerClusterer(threshold = 0.99f, maxSpeakers = 2)

        assertEquals(0, clusterer.assign(floatArrayOf(1f, 0f, 0f)).speaker)
        assertEquals(1, clusterer.assign(floatArrayOf(0f, 1f, 0f)).speaker)

        val third = clusterer.assign(floatArrayOf(0f, 0f, 1f))
        assertFalse(third.isNewSpeaker)
        assertTrue(third.speaker in 0..1)
        assertEquals(2, clusterer.speakerCount)
    }

    @Test
    fun resetStartsNewSession() {
        val clusterer = OnlineSpeakerClusterer(threshold = 0.5f)
        clusterer.assign(floatArrayOf(1f, 0f, 0f))
        clusterer.assign(floatArrayOf(0f, 1f, 0f))

        clusterer.reset()

        assertEquals(0, clusterer.speakerCount)
        assertEquals(-1, clusterer.lastSpeaker)
        val first = clusterer.assign(floatArrayOf(0f, 1f, 0f))
        assertEquals(0, first.speaker)
        assertTrue(first.isNewSpeaker)
    }

    @Test
    fun rejectsInconsistentEmbeddingSize() {
        val clusterer = OnlineSpeakerClusterer(threshold = 0.5f)
        clusterer.assign(floatArrayOf(1f, 0f, 0f))

        try {
            clusterer.assign(floatArrayOf(1f, 0f))
            fail("Ожидался IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun rejectsInvalidThreshold() {
        try {
            OnlineSpeakerClusterer(threshold = 1.5f)
            fail("Ожидался IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `new speaker is deferred until enough speech`() {
        val clusterer = OnlineSpeakerClusterer(
            threshold = 0.5f,
            minNewSpeakerSamples = 32_000,
        )

        val first = clusterer.assign(floatArrayOf(1f, 0f), durationSamples = 16_000)
        val pending = clusterer.assign(floatArrayOf(0f, 1f), durationSamples = 16_000)
        val promoted = clusterer.assign(floatArrayOf(0f, 1f), durationSamples = 16_000)

        assertEquals(0, first.speaker)
        assertEquals(0, pending.speaker)
        assertFalse(pending.isNewSpeaker)
        assertEquals(1, promoted.speaker)
        assertTrue(promoted.isNewSpeaker)
        assertEquals(2, clusterer.speakerCount)
    }

    @Test
    fun `pending new speaker is cleared when known speaker returns`() {
        val clusterer = OnlineSpeakerClusterer(
            threshold = 0.5f,
            minNewSpeakerSamples = 32_000,
        )

        clusterer.assign(floatArrayOf(1f, 0f), durationSamples = 16_000)
        clusterer.assign(floatArrayOf(0f, 1f), durationSamples = 16_000)
        val known = clusterer.assign(floatArrayOf(1f, 0f), durationSamples = 16_000)
        val pendingAgain = clusterer.assign(floatArrayOf(0f, 1f), durationSamples = 16_000)
        val promoted = clusterer.assign(floatArrayOf(0f, 1f), durationSamples = 16_000)

        assertEquals(0, known.speaker)
        assertEquals(0, pendingAgain.speaker)
        assertEquals(1, promoted.speaker)
        assertEquals(2, clusterer.speakerCount)
    }

    @Test
    fun `dissimilar pending candidates do not create a cluster together`() {
        val clusterer = OnlineSpeakerClusterer(
            threshold = 0.5f,
            minNewSpeakerSamples = 24_000,
        )

        clusterer.assign(floatArrayOf(1f, 0f, 0f), durationSamples = 16_000)
        clusterer.assign(floatArrayOf(0f, 1f, 0f), durationSamples = 8_000)
        clusterer.assign(floatArrayOf(0f, 0f, 1f), durationSamples = 8_000)

        assertEquals(1, clusterer.speakerCount)
    }

    @Test
    fun rejectsNegativePendingSupport() {
        try {
            OnlineSpeakerClusterer(threshold = 0.5f, minNewSpeakerSamples = -1)
            fail("Ожидался IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
