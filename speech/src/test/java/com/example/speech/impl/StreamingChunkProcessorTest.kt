package com.example.speech.impl

import com.example.speech.audio.OnlineSpeakerClusterer
import com.example.speech.audio.SpeakerChangeConfig
import com.example.speech.audio.SpeakerChangeSplitter
import com.example.speech.audio.SpeechChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingChunkProcessorTest {

    @Test
    fun `one voice gives one segment spanning the words`() {
        val processor = processor(words = { listOf(word("привет", 0.2, 0.6), word("мир", 0.7, 1.1)) }) { floatArrayOf(1f, 0f) }

        val segments = processor.process(SpeechChunk(16_000L, FloatArray(2 * SR)))

        assertEquals(1, segments.size)
        assertEquals("привет мир", segments[0].text)
        // Границы — по словам, а не по краям VAD-чанка (чанк начинается с 1 с);
        // начало на 0,1 с раньше метки первого токена.
        assertEquals(1_100L, segments[0].startMs)
        assertEquals(2_100L, segments[0].endMs)
        assertEquals(0, segments[0].speaker)
    }

    @Test
    fun `no words means no segments`() {
        val processor = processor(words = { emptyList() }) { floatArrayOf(1f, 0f) }

        assertTrue(processor.process(SpeechChunk(0L, FloatArray(SR))).isEmpty())
        assertTrue(processor.records.isEmpty())
    }

    @Test
    fun `voice change cuts in the pause and each piece is recognized on its own`() {
        // 0–2,3 с первый голос, тишина 2,3–2,5 с, дальше второй.
        val samples = FloatArray(48 * SR / 10) {
            when {
                it < 23 * SR / 10 -> 0.5f
                it < 25 * SR / 10 -> 0f
                else -> -0.5f
            }
        }
        val recognized = ArrayList<Int>()
        val processor = processor(
            words = { audio ->
                recognized += audio.size
                if (audio.average() > 0) {
                    listOf(word("добрый", 0.2, 0.8), word("день", 0.9, 1.5), word("коллеги", 1.6, 2.2))
                } else {
                    listOf(word("здравствуйте", 0.3, 1.0), word("рады", 1.1, 1.6), word("видеть", 1.7, 2.2))
                }
            },
            splitter = SpeakerChangeSplitter(SpeakerChangeConfig(windowMs = 800, hopMs = 400, minSegmentMs = 600, threshold = 0.5f)),
        ) { window -> if (window.average() > 0) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f) }

        val segments = processor.process(SpeechChunk(0L, samples))

        assertEquals(listOf("добрый день коллеги", "здравствуйте рады видеть"), segments.map { it.text })
        assertEquals(listOf(0, 1), segments.map { it.speaker })
        // Чанк не распознаётся целиком: только два куска, разрез — в тишине 2,3–2,5 с.
        assertEquals(2, recognized.size)
        assertTrue("cut at ${recognized[0]}", recognized[0] in (23 * SR / 10)..(25 * SR / 10))
        // Границы реплик — по словам куска (с упреждением 0,1 с на начало).
        assertEquals(100L, segments[0].startMs)
        assertEquals(2_200L, segments[0].endMs)
    }

    @Test
    fun `records keep embedding and chunk index for final re-clustering`() {
        val processor = processor(words = { listOf(word("да", 0.0, 0.8)) }) { floatArrayOf(1f, 0f) }

        processor.process(SpeechChunk(0L, FloatArray(SR)), chunkIndex = 7)

        assertEquals(7, processor.records.single().chunkIndex)
        assertTrue(processor.records.single().embedding != null)
    }

    @Test
    fun `short piece falls back to the last speaker without embedding`() {
        var embeddingCalls = 0
        val processor = processor(words = { listOf(word("да", 0.0, 0.3)) }) {
            embeddingCalls++
            floatArrayOf(1f, 0f)
        }

        val segment = processor.process(SpeechChunk(0L, FloatArray(SR / 2))).single()

        assertEquals(0, segment.speaker)
        assertEquals(0, embeddingCalls)
    }

    @Test
    fun `trace reports timings and speaker but never the recognized text`() {
        val lines = ArrayList<String>()
        val processor = processor(
            words = { listOf(word("секретная", 0.1, 0.6), word("фраза", 0.7, 0.95)) },
            trace = { lines += it },
        ) { floatArrayOf(1f, 0f) }

        processor.process(SpeechChunk(SR.toLong(), FloatArray(SR)))

        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].startsWith("chunk start=1000ms dur=1000ms boundaries=0 pieces=1"))
        assertTrue(lines[1], lines[1].startsWith("segment start=1000ms dur=950ms"))
        assertTrue(lines[1], "spk=0" in lines[1] && "new=true" in lines[1] && "chars=15" in lines[1])
        assertTrue(lines.none { "секретная" in it || "фраза" in it })
    }

    private fun word(text: String, fromS: Double, toS: Double) =
        AsrWord(text, (fromS * SR).toInt(), (toS * SR).toInt())

    private fun processor(
        words: (FloatArray) -> List<AsrWord>,
        splitter: SpeakerChangeSplitter = SpeakerChangeSplitter(SpeakerChangeConfig(minSegmentMs = 100_000)),
        trace: ((String) -> Unit)? = null,
        embedding: (FloatArray) -> FloatArray?,
    ): StreamingChunkProcessor = StreamingChunkProcessor(
        transcribeWords = words,
        embeddingOf = embedding,
        clusterer = OnlineSpeakerClusterer(threshold = 0.45f),
        splitter = splitter,
        trace = trace,
    )

    private companion object {
        const val SR = 16_000
    }
}
