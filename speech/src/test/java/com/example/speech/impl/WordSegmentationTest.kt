package com.example.speech.impl

import com.example.speech.api.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordSegmentationTest {

    @Test
    fun `tokens with a leading space start words, punctuation sticks to the word`() {
        // Как на устройстве: «Сколько € я могу…» — пробел в токене = ▁ в словаре.
        val words = assembleWords(
            tokens = arrayOf(" С", "ко", "ль", "ко", " ", "€", " я", " мо", "г", "у", "?"),
            timestamps = floatArrayOf(0.00f, 0.60f, 0.72f, 0.84f, 0.96f, 1.04f, 1.48f, 1.60f, 1.68f, 1.72f, 5.20f),
            sampleRate = SR,
            limitSamples = 6 * SR,
        )

        assertEquals(listOf("Сколько", "€", "я", "могу?"), words.map { it.text })
        assertEquals(0, words[0].startSample)
    }

    @Test
    fun `word end comes from the last letter, not from a late punctuation token`() {
        val words = assembleWords(
            tokens = arrayOf(" дол", "ла", "ра", "?", " Бу", "дут"),
            timestamps = floatArrayOf(2.40f, 2.60f, 2.92f, 5.20f, 5.40f, 5.56f),
            sampleRate = SR,
            limitSamples = 6 * SR,
        )

        assertEquals("доллара?", words[0].text)
        // Последняя буква в 2,92 с + 0,25 с, а не 5,20 с от «?».
        assertEquals((3.17 * SR).toInt(), words[0].endSample)
        assertEquals((5.40 * SR).toInt(), words[1].startSample)
    }

    @Test
    fun `word end never runs into the next word or past the limit`() {
        val words = assembleWords(
            tokens = arrayOf(" да", " нет"),
            timestamps = floatArrayOf(0.00f, 0.10f),
            sampleRate = SR,
            limitSamples = (0.2 * SR).toInt(),
        )

        assertEquals((0.10 * SR).toInt(), words[0].endSample)
        assertEquals((0.20 * SR).toInt(), words[1].endSample)
    }

    @Test
    fun `a lone dash joins the previous word`() {
        val words = assembleWords(
            tokens = arrayOf(" да", " —", " нет"),
            timestamps = floatArrayOf(0.0f, 0.5f, 1.0f),
            sampleRate = SR,
            limitSamples = 2 * SR,
        )

        assertEquals(listOf("да —", "нет"), words.map { it.text })
    }

    @Test
    fun `mismatched or empty input gives no words`() {
        assertEquals(emptyList<AsrWord>(), assembleWords(arrayOf(" а"), floatArrayOf(), SR, SR))
        assertEquals(emptyList<AsrWord>(), assembleWords(emptyArray(), floatArrayOf(), SR, SR))
    }

    @Test
    fun `cut moves to the quietest frame near the boundary`() {
        val samples = FloatArray(s(3.0)) { 0.3f }.also { audio -> for (i in s(1.40) until s(1.45)) audio[i] = 0f }

        val cuts = chooseCuts(listOf(s(1.25)), samples, MIN_PIECE, ENERGY)

        assertTrue("cut at $cuts", cuts.single() in s(1.40)..s(1.45))
    }

    @Test
    fun `cut is dropped if a piece would be too short`() {
        assertEquals(emptyList<Int>(), chooseCuts(listOf(s(0.2)), FloatArray(s(2.5)), MIN_PIECE, ENERGY))
        // Окно поиска 2,1–2,5 с, тихий кадр — первый (2,1 с): справа остаётся 0,4 с без 5 мс.
        assertEquals(emptyList<Int>(), chooseCuts(listOf(s(2.4)), FloatArray(s(2.5)), MIN_PIECE, ENERGY))
    }

    @Test
    fun `two close boundaries keep only one cut`() {
        val cuts = chooseCuts(listOf(s(1.0), s(1.2)), FloatArray(s(3.0)), MIN_PIECE, ENERGY)

        assertEquals(1, cuts.size)
    }

    @Test
    fun `neighbors of one chunk with the same final speaker are merged`() {
        val segments = listOf(
            seg(0, 0, 1_000, "раз"), seg(0, 1_100, 2_000, "два"),
            seg(1, 2_100, 3_000, "три"),
            seg(1, 4_000, 5_000, "четыре"),
        )

        val merged = mergeSameSpeakerNeighbors(segments, chunkOf = listOf(0, 0, 0, 1))

        assertEquals(listOf("раз два", "три", "четыре"), merged.map { it.text })
        assertEquals(listOf(0L to 2_000L, 2_100L to 3_000L, 4_000L to 5_000L), merged.map { it.startMs to it.endMs })
    }

    private fun w(text: String, fromS: Double, toS: Double) = AsrWord(text, s(fromS), s(toS))

    private fun s(seconds: Double) = Math.round(seconds * SR).toInt()

    private fun seg(speaker: Int, from: Long, to: Long, text: String) = Segment(speaker, from, to, text)

    private companion object {
        const val SR = 16_000
        const val MIN_PIECE = SR * 400 / 1000
        const val ENERGY = SR * 300 / 1000
    }
}
