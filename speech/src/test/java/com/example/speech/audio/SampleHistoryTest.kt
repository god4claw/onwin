package com.example.speech.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SampleHistoryTest {

    @Test
    fun `slice returns the exact original samples across the ring boundary`() {
        val history = SampleHistory(capacity = 10)
        history.append(ramp(0, 7))
        history.append(ramp(7, 6)) // 13 сэмплов, буфер на 10 — уже провернулся

        assertArrayEquals(ramp(5, 6), history.slice(5, 6), 0f)
        assertArrayEquals(ramp(3, 10), history.slice(3, 10), 0f)
        assertEquals(13L, history.totalSamples)
    }

    @Test
    fun `evicted or future ranges are null`() {
        val history = SampleHistory(capacity = 10)
        history.append(ramp(0, 13))

        assertNull(history.slice(2, 3)) // начало вытеснено
        assertNull(history.slice(10, 5)) // конец ещё не пришёл
        assertNull(history.slice(-1, 2))
    }

    @Test
    fun `append longer than capacity keeps the tail`() {
        val history = SampleHistory(capacity = 4)
        history.append(ramp(0, 9))

        assertEquals(9L, history.totalSamples)
        assertArrayEquals(ramp(5, 4), history.slice(5, 4), 0f)
        assertNull(history.slice(4, 1))
    }

    @Test
    fun `reset starts numbering from zero`() {
        val history = SampleHistory(capacity = 8)
        history.append(ramp(100, 8))
        history.reset()
        history.append(ramp(0, 3))

        assertArrayEquals(ramp(0, 3), history.slice(0, 3), 0f)
        assertNull(history.slice(0, 4))
    }

    private fun ramp(from: Int, count: Int) = FloatArray(count) { (from + it).toFloat() }
}
