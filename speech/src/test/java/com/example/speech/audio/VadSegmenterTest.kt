package com.example.speech.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VadSegmenterTest {

    @Test
    fun `accept buffers arbitrary chunks into vad windows`() {
        val engine = FakeVadEngine()
        val segmenter = VadSegmenter(engine)

        val first = segmenter.accept(FloatArray(1024) { it.toFloat() })

        assertEquals(2, engine.accepted.size)
        assertEquals(512, engine.accepted[0].size)
        assertEquals(512, engine.accepted[1].size)
        assertEquals(1024L, segmenter.processedSamples)
        assertTrue(first.chunks.isEmpty())
    }

    @Test
    fun `drain returns chunks and speech started only once`() {
        val engine = FakeVadEngine()
        engine.detected = true
        engine.pending += VadEngine.RawSegment(
            startSample = 512L,
            samples = floatArrayOf(0.1f, 0.2f),
        )
        val segmenter = VadSegmenter(engine)

        val first = segmenter.accept(FloatArray(512))
        val second = segmenter.accept(FloatArray(512))

        assertNotNull(first.speechStartedAtSample)
        assertEquals(0L, first.speechStartedAtSample)
        assertEquals(1, first.chunks.size)
        assertEquals(512L, first.chunks[0].startSample)
        assertEquals(2, first.chunks[0].samples.size)

        assertNull(second.speechStartedAtSample)
    }

    @Test
    fun `flush pads tail and flushes engine`() {
        val engine = FakeVadEngine()
        val segmenter = VadSegmenter(engine)

        segmenter.accept(FloatArray(100) { 0.5f })
        val chunks = segmenter.flush()

        assertTrue(chunks.isEmpty())
        assertEquals(1, engine.accepted.size)
        assertEquals(512, engine.accepted[0].size)
        assertEquals(0.5f, engine.accepted[0][0])
        assertEquals(0f, engine.accepted[0][100])
        assertTrue(engine.flushed)
        assertEquals(512L, segmenter.processedSamples)
        assertEquals(100L, segmenter.receivedSamples)
    }

    @Test
    fun `speech chunk trims flush padding`() {
        val chunk = SpeechChunk(startSample = 1_000L, samples = FloatArray(500) { 1f })

        assertEquals(500, chunk.trimmedTo(2_000L)!!.samples.size)

        val trimmed = chunk.trimmedTo(1_200L)!!
        assertEquals(1_000L, trimmed.startSample)
        assertEquals(200, trimmed.samples.size)

        assertNull(chunk.trimmedTo(1_000L))
    }

    @Test
    fun `reset clears pending state`() {
        val engine = FakeVadEngine()
        val segmenter = VadSegmenter(engine)

        segmenter.accept(FloatArray(100))
        segmenter.reset()

        assertEquals(0L, segmenter.processedSamples)
        assertEquals(0L, segmenter.receivedSamples)
        assertFalse(engine.flushed)

        segmenter.accept(FloatArray(512))
        assertEquals(1, engine.accepted.size)
        assertEquals(512L, segmenter.receivedSamples)
    }

    private class FakeVadEngine : VadEngine {
        val accepted = ArrayList<FloatArray>()
        val pending = ArrayList<VadEngine.RawSegment>()
        var detected = false
        var flushed = false

        override fun accept(samples: FloatArray) {
            accepted += samples.copyOf()
        }

        override fun drain(): List<VadEngine.RawSegment> {
            val result = pending.toList()
            pending.clear()
            return result
        }

        override fun flush() {
            flushed = true
        }

        override fun isSpeechDetected(): Boolean = detected

        override fun reset() {
            flushed = false
        }

        override fun release() = Unit
    }
}
