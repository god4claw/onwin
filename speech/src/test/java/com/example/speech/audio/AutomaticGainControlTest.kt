package com.example.speech.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class AutomaticGainControlTest {

    @Test
    fun `quiet speech is raised to the target level`() {
        val agc = AutomaticGainControl(targetDbfs = -20f, maxGainDb = 24f)
        // 1 с фона −62 dBFS, затем 3 с «речи» −40 dBFS.
        val input = noise(seconds = 1.0, dbfs = -62f) + tone(seconds = 3.0, dbfs = -40f)

        val output = agc.process(input)

        // Оценка уровня речи тянется к громким слогам (быстро вверх, медленно
        // вниз), поэтому средний RMS сигнала с огибающей чуть ниже цели.
        val settled = rmsDb(output.copyOfRange(output.size - SAMPLE_RATE, output.size))
        assertTrue("settled at $settled dBFS", settled in -23.0..-19.0)
    }

    @Test
    fun `gain never exceeds the ceiling`() {
        val agc = AutomaticGainControl(targetDbfs = -20f, maxGainDb = 12f)
        // Фон выше −70 dBFS: тише — это «цифровая тишина», и она в оценку фона не входит.
        val input = noise(seconds = 1.0, dbfs = -65f) + tone(seconds = 3.0, dbfs = -50f)

        val output = agc.process(input)

        // Потолок достигается, но не превышается. Средний уровень чуть ниже −50 + 12:
        // в провалах огибающей (ниже порога «речь») усиление ненадолго отпускается.
        assertTrue("max gain ${agc.maxAppliedGainDb}", agc.maxAppliedGainDb in 11.5f..12.001f)
        val settled = rmsDb(output.copyOfRange(output.size - SAMPLE_RATE, output.size))
        assertTrue("settled at $settled dBFS", settled in -40.5..-37.5)
    }

    @Test
    fun `loud speech is not attenuated`() {
        val agc = AutomaticGainControl(targetDbfs = -20f)
        val input = noise(seconds = 0.5, dbfs = -60f) + tone(seconds = 2.0, dbfs = -12f)

        val output = agc.process(input)

        val tail = 16_000
        assertEquals(
            rmsDb(input.copyOfRange(input.size - tail, input.size)),
            rmsDb(output.copyOfRange(output.size - tail, output.size)),
            0.2,
        )
    }

    @Test
    fun `output never leaves -1 to 1 even on a sudden loud onset`() {
        val agc = AutomaticGainControl(targetDbfs = -20f, maxGainDb = 24f)
        // Длинная тихая речь разгоняет усиление, затем внезапный громкий звук.
        val input = noise(seconds = 0.5, dbfs = -70f) + tone(seconds = 2.0, dbfs = -45f) +
            tone(seconds = 0.5, dbfs = -3f)

        val output = agc.process(input)

        assertTrue(output.all { abs(it) <= 1f })
    }

    @Test
    fun `stationary noise is not amplified`() {
        val agc = AutomaticGainControl(targetDbfs = -20f, maxGainDb = 24f)
        val input = noise(seconds = 5.0, dbfs = -45f)

        val output = agc.process(input)

        assertEquals(rmsDb(input), rmsDb(output), 1.0)
    }

    @Test
    fun `leading digital silence does not make every pause look like speech`() {
        val agc = AutomaticGainControl(targetDbfs = -20f, maxGainDb = 24f)
        // Нули в начале (так бывает и у микрофона, и у голосовых), затем фон −45.
        val input = FloatArray(SAMPLE_RATE / 2) + noise(seconds = 6.0, dbfs = -45f)

        val output = agc.process(input)

        val tail = 3 * SAMPLE_RATE
        assertEquals(
            rmsDb(input.copyOfRange(input.size - tail, input.size)),
            rmsDb(output.copyOfRange(output.size - tail, output.size)),
            1.0,
        )
    }

    @Test
    fun `gain returns to unity in a pause after quiet speech`() {
        val agc = AutomaticGainControl(targetDbfs = -20f, maxGainDb = 24f)
        agc.process(noise(seconds = 1.0, dbfs = -60f) + tone(seconds = 2.0, dbfs = -40f))
        assertTrue("boosted during speech: ${agc.currentGainDb}", agc.currentGainDb > 10f)

        agc.process(noise(seconds = 0.5, dbfs = -60f))

        assertTrue("back to unity in the pause: ${agc.currentGainDb}", agc.currentGainDb < 1f)
    }

    @Test
    fun `chunking does not change the result`() {
        val input = noise(seconds = 0.5, dbfs = -60f) + tone(seconds = 2.0, dbfs = -38f) +
            noise(seconds = 0.5, dbfs = -60f) + tone(seconds = 1.0, dbfs = -15f)
        val whole = AutomaticGainControl().process(input)

        val agc = AutomaticGainControl()
        val random = Random(7)
        val pieces = ArrayList<FloatArray>()
        var offset = 0
        while (offset < input.size) {
            val end = minOf(offset + 1 + random.nextInt(900), input.size)
            pieces += agc.process(input.copyOfRange(offset, end))
            offset = end
        }
        val chunked = pieces.reduce { acc, piece -> acc + piece }

        assertArrayEquals(whole, chunked, 0f)
    }

    @Test
    fun `reset forgets the previous session`() {
        val input = noise(seconds = 0.5, dbfs = -60f) + tone(seconds = 2.0, dbfs = -40f)
        val fresh = AutomaticGainControl().process(input)

        val agc = AutomaticGainControl()
        agc.process(tone(seconds = 2.0, dbfs = -45f))
        agc.reset()

        assertArrayEquals(fresh, agc.process(input), 0f)
        assertEquals(0f, AutomaticGainControl().currentGainDb, 0f)
    }

    private fun tone(seconds: Double, dbfs: Float): FloatArray {
        // Синус с амплитудной модуляцией 4 Гц — грубая «речь» с огибающей слогов.
        val n = (seconds * SAMPLE_RATE).toInt()
        val raw = FloatArray(n) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            (sin(2 * PI * 220 * t) * (0.6 + 0.4 * sin(2 * PI * 4 * t))).toFloat()
        }
        return scaleTo(raw, dbfs)
    }

    private fun noise(seconds: Double, dbfs: Float): FloatArray {
        val random = Random(seconds.hashCode() xor dbfs.hashCode())
        val raw = FloatArray((seconds * SAMPLE_RATE).toInt()) { random.nextFloat() * 2f - 1f }
        return scaleTo(raw, dbfs)
    }

    private fun scaleTo(samples: FloatArray, dbfs: Float): FloatArray {
        val scale = (10.0.pow(dbfs / 20.0) / rms(samples)).toFloat()
        return FloatArray(samples.size) { samples[it] * scale }
    }

    private fun rms(samples: FloatArray): Double =
        sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    private fun rmsDb(samples: FloatArray): Double = 20 * log10(rms(samples))

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
