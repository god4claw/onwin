package com.example.speech.impl

import com.example.speech.audio.OnlineSpeakerClusterer
import com.example.speech.audio.SpeakerChangeConfig
import com.example.speech.audio.SpeakerChangeSplitter
import com.example.speech.audio.SpeechChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingChunkProcessorTest {

    @Test
    fun `segment returns recognized text with speaker`() {
        val processor = processor(transcribe = { "привет" }) { floatArrayOf(1f, 0f) }

        val segment = processor.segment(SpeechChunk(0L, FloatArray(SAMPLES_PER_SECOND)))!!

        assertEquals("привет", segment.text)
        assertEquals(0, segment.speaker)
        assertEquals(0L, segment.startMs)
        assertEquals(1_000L, segment.endMs)
    }

    @Test
    fun `blank text is skipped`() {
        val processor = processor(transcribe = { "  " }) { floatArrayOf(1f, 0f) }

        assertNull(processor.segment(SpeechChunk(0L, FloatArray(SAMPLES_PER_SECOND))))
    }

    @Test
    fun `short piece does not ask for embedding`() {
        var embeddingCalls = 0
        val processor = processor(transcribe = { "да" }) {
            embeddingCalls++
            floatArrayOf(1f, 0f)
        }

        val segment = processor.segment(SpeechChunk(0L, FloatArray(SAMPLES_PER_SECOND / 4)))!!

        assertEquals(0, segment.speaker)
        assertEquals(0, embeddingCalls)
    }

    @Test
    fun `different voices get different speakers`() {
        val processor = processor(
            transcribe = { samples -> if (samples[0] > 0f) "раз" else "два" },
        ) { samples -> if (samples[0] > 0f) floatArrayOf(1f, 0f) else floatArrayOf(-1f, 0f) }

        val first = processor.segment(SpeechChunk(0L, FloatArray(SAMPLES_PER_SECOND) { 1f }))!!
        val second = processor.segment(
            SpeechChunk(SAMPLES_PER_SECOND.toLong(), FloatArray(SAMPLES_PER_SECOND) { -1f }),
        )!!

        assertEquals(0, first.speaker)
        assertEquals(1, second.speaker)
        assertEquals("раз", first.text)
        assertEquals("два", second.text)
    }

    @Test
    fun `failed embedding falls back to last speaker`() {
        val processor = processor(transcribe = { "текст" }) { null }

        val segment = processor.segment(SpeechChunk(0L, FloatArray(SAMPLES_PER_SECOND)))!!

        assertEquals(0, segment.speaker)
    }

    @Test
    fun `split returns a single piece for a short chunk`() {
        val processor = processor(transcribe = { "" }) { floatArrayOf(1f, 0f) }
        val chunk = SpeechChunk(0L, FloatArray(SAMPLES_PER_SECOND))

        val pieces = processor.split(chunk)

        assertEquals(1, pieces.size)
        assertTrue(pieces[0].samples.contentEquals(chunk.samples))
    }

    @Test
    fun `trace reports timings and speaker but never the recognized text`() {
        val lines = ArrayList<String>()
        val processor = processor(
            transcribe = { "секретная фраза" },
            trace = { lines += it },
        ) { floatArrayOf(1f, 0f) }

        processor.split(SpeechChunk(0L, FloatArray(SAMPLES_PER_SECOND)))
        processor.segment(SpeechChunk(SAMPLES_PER_SECOND.toLong(), FloatArray(SAMPLES_PER_SECOND)))

        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].startsWith("split start=0ms dur=1000ms pieces=1"))
        assertTrue(lines[1], lines[1].startsWith("segment start=1000ms dur=1000ms"))
        assertTrue(lines[1], "spk=0" in lines[1] && "new=true" in lines[1] && "chars=15" in lines[1])
        assertTrue(lines.none { "секретная" in it || "фраза" in it })
    }

    @Test
    fun `trace explains fallback speaker for a short piece`() {
        val lines = ArrayList<String>()
        val processor = processor(transcribe = { "да" }, trace = { lines += it }) { floatArrayOf(1f, 0f) }

        processor.segment(SpeechChunk(0L, FloatArray(SAMPLES_PER_SECOND / 4)))

        assertTrue(lines.single(), "via=short" in lines.single() && "sim=-" in lines.single())
    }

    private fun processor(
        transcribe: (FloatArray) -> String,
        trace: ((String) -> Unit)? = null,
        embedding: (FloatArray) -> FloatArray?,
    ): StreamingChunkProcessor = StreamingChunkProcessor(
        transcribe = transcribe,
        embeddingOf = embedding,
        clusterer = OnlineSpeakerClusterer(threshold = 0.45f),
        splitter = SpeakerChangeSplitter(SpeakerChangeConfig(minSegmentMs = 100_000)),
        trace = trace,
    )

    private companion object {
        const val SAMPLES_PER_SECOND = 16_000
    }
}
