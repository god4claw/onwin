package com.example.onwin.data.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class StreamingResamplerTest {

    private val sourceRates = intArrayOf(8_000, 11_025, 22_050, 44_100, 48_000)

    /** 44 056 Гц: 2000 фаз — режим с интерполяцией между строками таблицы. */
    private val oddRate = 44_056

    @Test
    fun sine1kHz_keepsFrequencyAndAmplitude() {
        for (rate in sourceRates + oddRate) {
            val output = resample(rate, sine(1000.0, rate, rate * 2))
            val from = output.size / 4
            val to = output.size * 3 / 4

            val frequency = zeroCrossingFrequency(output, TARGET_SAMPLE_RATE, from, to)
            assertEquals("frequency, $rate Hz", 1000.0, frequency, 1000.0 * 0.005)

            val amplitudeDb = 20 * log10(rms(output, from, to) * sqrt(2.0) / 0.5)
            assertTrue("amplitude $amplitudeDb dB, $rate Hz", abs(amplitudeDb) <= 1.0)
        }
    }

    @Test
    fun sine1kHz_isNotShiftedInTime() {
        // Нулевая задержка: выходной сэмпл n — это сигнал в момент n / 16000.
        for (rate in sourceRates + oddRate) {
            val output = resample(rate, sine(1000.0, rate, rate))
            val expected = sine(1000.0, TARGET_SAMPLE_RATE, output.size)
            for (n in output.size / 4 until output.size * 3 / 4) {
                assertEquals("sample $n, $rate Hz", expected[n], output[n], 1e-3f)
            }
        }
    }

    @Test
    fun sine10kHzAt48k_isAttenuatedBy40dB() {
        val input = sine(10_000.0, 48_000, 48_000)
        val output = resample(48_000, input)
        val attenuation = attenuationDb(input, output)
        println("48000 Hz, 10 kHz tone: attenuation %.1f dB".format(Locale.ROOT, attenuation))
        assertTrue("attenuation $attenuation dB", attenuation >= 40)
    }

    @Test
    fun downsampling_stopbandIsAttenuated() {
        for (rate in intArrayOf(22_050, 44_100, 48_000, oddRate)) {
            var worst = Double.MAX_VALUE
            var frequency = 8_000.0
            while (frequency < rate / 2.0) {
                val input = sine(frequency, rate, rate / 2)
                worst = minOf(worst, attenuationDb(input, resample(rate, input)))
                frequency += 250.0
            }
            println("$rate Hz: worst stopband attenuation 8 kHz..Nyquist %.1f dB".format(Locale.ROOT, worst))
            assertTrue("$rate Hz: $worst dB", worst >= 40)
        }
    }

    @Test
    fun upsampling_imagesAreAttenuated() {
        // При повышении частоты тон f даёт зеркальную копию на inputRate - f.
        for ((rate, tone) in listOf(8_000 to 3_000.0, 11_025 to 4_000.0)) {
            val output = resample(rate, sine(tone, rate, rate))
            val from = output.size / 4
            val to = output.size * 3 / 4
            val signal = goertzelAmplitude(output, TARGET_SAMPLE_RATE, tone, from, to)
            val image = goertzelAmplitude(output, TARGET_SAMPLE_RATE, rate - tone, from, to)
            val suppression = 20 * log10(signal / image)
            println("$rate Hz: image at ${rate - tone} Hz suppressed by %.1f dB".format(Locale.ROOT, suppression))
            assertTrue("$rate Hz: $suppression dB", suppression >= 40)
        }
    }

    @Test
    fun output_doesNotDependOnChunking() {
        val random = Random(42)
        for (rate in sourceRates + oddRate) {
            val input = FloatArray(rate * 3) { i ->
                (0.3 * sin(2 * PI * 440.0 * i / rate) + 0.2 * (random.nextFloat() - 0.5)).toFloat()
            }
            val whole = resample(rate, input)
            val chunks = IntArray(500) { random.nextInt(1, 4097) }
            val chunked = resample(rate, input, chunks)
            assertEquals("length, $rate Hz", whole.size, chunked.size)
            assertArrayEquals("$rate Hz", whole, chunked, 1f / 32_768)
        }
    }

    @Test
    fun outputLength_matchesRateRatio() {
        for (rate in sourceRates + oddRate + TARGET_SAMPLE_RATE) {
            for (length in intArrayOf(0, 1, 2, 3, 7, 100, 1_001, 44_101, rate)) {
                val output = resample(rate, FloatArray(length) { 0.1f })
                val expected = (length.toDouble() * TARGET_SAMPLE_RATE / rate).roundToLong()
                assertTrue(
                    "$rate Hz, $length samples: ${output.size} vs $expected",
                    abs(output.size - expected) <= 1,
                )
            }
        }
    }

    @Test
    fun sameRate_isPassedThroughUnchanged() {
        val random = Random(1)
        val input = FloatArray(10_000) { random.nextFloat() * 2 - 1 }
        assertArrayEquals(input, resample(TARGET_SAMPLE_RATE, input, intArrayOf(1, 333, 4096)), 0f)
    }

    @Test
    fun filterTable_matchesDesign() {
        val resampler441 = StreamingResampler(44_100)
        assertEquals(160, resampler441.tablePhases)
        assertTrue(!resampler441.interpolated)
        val resamplerOdd = StreamingResampler(oddRate)
        assertTrue(resamplerOdd.interpolated)
        println(listOf(8_000, 11_025, 22_050, 44_100, 48_000, oddRate).joinToString("\n") { StreamingResampler(it).toString() })
    }

    @Test(expected = IllegalStateException::class)
    fun process_afterFlush_throws() {
        val resampler = StreamingResampler(48_000)
        resampler.flush(CollectingSink())
        resampler.process(FloatArray(10), 0, 10, CollectingSink())
    }

    @Test
    fun speedFactor_isReported() {
        val rate = 48_000
        val seconds = 60
        val random = Random(7)
        val input = FloatArray(rate * seconds) { random.nextFloat() - 0.5f }
        resample(rate, input.copyOf(rate * 5)) // прогрев JIT
        val start = System.nanoTime()
        resample(rate, input, intArrayOf(4096))
        val speed = seconds / ((System.nanoTime() - start) / 1e9)
        println("JVM resampler 48000 -> 16000 Hz: %.0fx realtime".format(Locale.ROOT, speed))
        assertTrue("speed $speed", speed > 1)
    }

    /** Ослабление тона: RMS выхода к RMS входа в середине записи, дБ. */
    private fun attenuationDb(input: FloatArray, output: FloatArray): Double {
        val inputRms = rms(input, input.size / 4, input.size * 3 / 4)
        val outputRms = rms(output, output.size / 4, output.size * 3 / 4)
        return 20 * log10(inputRms / outputRms)
    }
}
