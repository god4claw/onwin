package com.example.onwin.data.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.random.Random

class PcmPipelineTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun pcm16At16kMono_passesThroughBitExact() {
        val random = Random(5)
        val samples = ShortArray(40_001) { random.nextInt(-32_768, 32_768).toShort() }
        val file = convert { pipeline ->
            pipeline.setFormat(16_000, 1, PcmEncoding.PCM_16BIT)
            var offset = 0
            while (offset < samples.size) {
                val count = minOf(random.nextInt(1, 5_000), samples.size - offset)
                pipeline.write(pcm16(samples.copyOfRange(offset, offset + count)))
                offset += count
            }
        }
        assertArrayEquals(samples, wavSamplesOf(file))
    }

    @Test
    fun sampleRateChange_midStream_keepsDurationAndPitch() {
        // 1 с 44.1 кГц стерео PCM16, затем 1 с 48 кГц моно float — как при смене формата декодером.
        val first = sine(1000.0, 44_100, 44_100)
        val second = sine(1000.0, 48_000, 48_000)
        val file = convert { pipeline ->
            pipeline.setFormat(44_100, 2, PcmEncoding.PCM_16BIT)
            pipeline.write(pcm16(ShortArray(first.size * 2) { i -> WavWriter.floatToPcm16(first[i / 2]) }))
            pipeline.setFormat(48_000, 1, PcmEncoding.FLOAT)
            pipeline.write(floatBytes(*second))
        }
        val output = wavSamplesOf(file).let { s -> FloatArray(s.size) { s[it] / 32_768f } }
        assertEquals(32_000, output.size)
        assertEquals(1000.0, zeroCrossingFrequency(output, 16_000, 2_000, 14_000), 5.0)
        assertEquals(1000.0, zeroCrossingFrequency(output, 16_000, 18_000, 30_000), 5.0)
    }

    @Test
    fun channelChange_withSameRate_keepsResamplerState() {
        // Сигнал непрерывен через смену стерео → моно: если бы ресемплер пересоздался,
        // на стыке был бы провал из-за нулевого дополнения.
        val rate = 44_100
        val signal = sine(1000.0, rate, rate)
        val half = rate / 2
        val file = convert { pipeline ->
            pipeline.setFormat(rate, 2, PcmEncoding.FLOAT)
            pipeline.write(floatBytes(*FloatArray(half * 2) { signal[it / 2] }))
            pipeline.setFormat(rate, 1, PcmEncoding.FLOAT)
            pipeline.write(floatBytes(*signal.copyOfRange(half, rate)))
        }
        val output = wavSamplesOf(file)
        val expected = sine(1000.0, 16_000, output.size)
        for (n in 7_500 until 8_500) {
            assertTrue("sample $n", abs(output[n] / 32_768f - expected[n]) < 2e-3f)
        }
    }

    @Test
    fun sizeLimit_duringConversion_isTooLong() {
        val file = temp.newFile()
        WavWriter(file, 16_000, 1, maxDataBytes = 100).use { writer ->
            val pipeline = PcmPipeline(writer)
            pipeline.setFormat(16_000, 1, PcmEncoding.PCM_16BIT)
            assertThrows(AudioConversionException.TooLong::class.java) { pipeline.write(pcm16(ShortArray(51))) }
        }
    }

    private fun convert(block: (PcmPipeline) -> Unit): File {
        val file = temp.newFile()
        WavWriter(file).use { writer ->
            val pipeline = PcmPipeline(writer)
            block(pipeline)
            pipeline.finish()
        }
        return file
    }

    private fun pcm16(samples: ShortArray): ByteBuffer =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            samples.forEach { putShort(it) }
            flip()
        }
}
