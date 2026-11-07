package com.example.speech.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishRouterTest {

    private val calls = ArrayList<Int>()

    private fun router(english: (FloatArray) -> List<AsrWord>) = EnglishRouter(
        englishWords = { samples -> calls += samples.size; english(samples) },
        sampleRate = SR,
    )

    @Test
    fun `an english chunk goes to the english model as a whole`() {
        // Как GigaAM пишет английскую речь: латиница «на слух».
        val giga = words("Ask" to 0.1, "now" to 0.4, "wey" to 0.6, "your" to 0.8, "cuntry" to 1.0, "canduffery" to 1.5)
        val samples = FloatArray(2 * SR)

        val routed = router { listOf(w("Ask", 0.1, 0.3), w("not", 0.4, 0.6), w("what", 0.6, 0.8)) }.route(samples, giga)

        assertEquals(listOf("Ask", "not", "what"), routed.map { it.text })
        assertEquals(listOf(samples.size), calls)
    }

    @Test
    fun `an english phrase next to a russian answer is replaced by its own span`() {
        // Английская фраза одного человека и русский ответ другого в одном куске.
        val giga = words(
            "Norries" to 0.2, "Mr." to 0.7, "Kulters" to 1.0, "manner" to 1.5, "Les" to 2.0, "Interesting" to 2.3,
            "Маша," to 4.0, "ты" to 4.4, "не" to 4.6, "поверишь," to 4.8, "что" to 5.4, "сегодня" to 5.6, "было" to 6.1,
        )
        val samples = FloatArray(7 * SR)

        val routed = router { slice ->
            // Английская модель видит только участок: метки — от его начала.
            listOf(w("Nor", 0.0, 0.3), w("is", 0.4, 0.5), w("Mr.", 0.6, 0.9), w("Quilter's", 1.0, 1.5), w("manner", 1.6, 2.0))
                .filter { it.endSample <= slice.size }
        }.route(samples, giga)

        assertEquals(
            listOf("Nor", "is", "Mr.", "Quilter's", "manner", "Маша,", "ты", "не", "поверишь,", "что", "сегодня", "было"),
            routed.map { it.text },
        )
        assertEquals(1, calls.size)
        // Участок начинается не раньше 0 и не заходит на первое русское слово.
        assertTrue("span ${calls.single()}", calls.single() <= (4.0 * SR).toInt())
        assertEquals((0.0 * SR).toInt(), routed.first().startSample)
        assertTrue(routed.take(5).all { it.endSample <= (4.0 * SR).toInt() })
        assertEquals((4.0 * SR).toInt(), routed[5].startSample)
    }

    @Test
    fun `brands inside a russian phrase stay with gigaam`() {
        val giga = words(
            "Цену" to 0.0, "акций" to 0.4, "Exxon" to 0.9, "Mobil" to 1.3, "и" to 1.7, "iPhone" to 1.9,
            "или" to 2.4, "Android" to 2.7, "сравни," to 3.2, "пожалуйста." to 3.7,
        )

        val routed = router { error("английская модель не должна вызываться") }.route(FloatArray(5 * SR), giga)

        assertEquals(giga, routed)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `empty english output keeps gigaam words`() {
        val giga = words("Hello" to 0.1, "my" to 0.5, "dear" to 0.7, "friend" to 1.0, "how" to 1.4, "are" to 1.6, "you" to 1.8)

        val routed = router { emptyList() }.route(FloatArray(3 * SR), giga)

        assertEquals(giga, routed)
    }

    private fun words(vararg items: Pair<String, Double>): List<AsrWord> =
        items.mapIndexed { i, (text, start) ->
            val end = if (i + 1 < items.size) items[i + 1].second else start + 0.4
            w(text, start, end)
        }

    private fun w(text: String, fromS: Double, toS: Double) = AsrWord(text, (fromS * SR).toInt(), (toS * SR).toInt())

    private companion object {
        const val SR = 16_000
    }
}
