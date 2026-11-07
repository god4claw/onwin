package com.example.onwin.data.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class WavWriterTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun header_isCanonical16kMono() {
        val file = temp.newFile("mono.wav")
        WavWriter(file).use { it.writeShorts(ShortArray(1_000) { i -> i.toShort() }) }
        assertHeader(file, dataBytes = 2_000, sampleRate = 16_000, channels = 1)
        assertEquals(44L + 2_000, file.length())
    }

    @Test
    fun header_hasStereoBlockAlignAndByteRate() {
        val file = temp.newFile("stereo.wav")
        WavWriter(file, sampleRate = 44_100, channelCount = 2).use { it.writeShorts(ShortArray(10)) }
        assertHeader(file, dataBytes = 20, sampleRate = 44_100, channels = 2)
    }

    @Test
    fun emptyFile_hasZeroDataSize() {
        val file = temp.newFile("empty.wav")
        WavWriter(file).close()
        assertHeader(file, dataBytes = 0, sampleRate = 16_000, channels = 1)
        assertEquals(44L, file.length())
    }

    @Test
    fun shorts_roundTrip() {
        val random = Random(3)
        // Больше внутреннего буфера (64 КиБ), куски разной длины.
        val samples = ShortArray(100_003) { random.nextInt(-32_768, 32_768).toShort() }
        val file = temp.newFile("roundtrip.wav")
        WavWriter(file).use { writer ->
            writer.writeShorts(samples, 0, 1)
            writer.writeShorts(samples, 1, 50_000)
            writer.writeShorts(samples, 50_001, samples.size - 50_001)
            assertEquals(samples.size.toLong(), writer.frameCount)
            assertEquals(samples.size * 1000L / 16_000, writer.durationMs)
        }
        assertArrayEquals(samples, wavSamplesOf(file))
    }

    @Test
    fun floats_areRoundedAndClippedOnce() {
        val file = temp.newFile("floats.wav")
        WavWriter(file).use {
            it.writeFloats(floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f, 1.5f, -1.5f, Float.NaN, 1f / 65_536))
        }
        assertArrayEquals(
            shortArrayOf(0, 16_384, -16_384, 32_767, -32_768, 32_767, -32_768, 0, 1),
            wavSamplesOf(file),
        )
    }

    @Test
    fun sizeLimit_failsCleanly() {
        val file = temp.newFile("limit.wav")
        val writer = WavWriter(file, 16_000, 1, maxDataBytes = 10)
        writer.writeShorts(ShortArray(5))
        assertThrows(WavSizeLimitException::class.java) { writer.writeFloats(FloatArray(1)) }
        writer.close()
        assertHeader(file, dataBytes = 10, sampleRate = 16_000, channels = 1)
    }

    @Test
    fun maxDataBytes_keepsRiffSizeWithinSignedInt() {
        assertEquals(Int.MAX_VALUE.toLong(), WavWriter.MAX_DATA_BYTES + 36)
    }

    @Test
    fun close_isIdempotent_andWritesAfterCloseFail() {
        val file = temp.newFile("closed.wav")
        val writer = WavWriter(file)
        writer.close()
        writer.close()
        assertThrows(IllegalStateException::class.java) { writer.writeShorts(ShortArray(1)) }
    }

    private fun assertHeader(file: File, dataBytes: Int, sampleRate: Int, channels: Int) {
        val header = wavHeaderOf(file)
        assertEquals("RIFF", header.ascii(0))
        assertEquals(36 + dataBytes, header.getInt(4))
        assertEquals("WAVE", header.ascii(8))
        assertEquals("fmt ", header.ascii(12))
        assertEquals(16, header.getInt(16))
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(channels, header.getShort(22).toInt())
        assertEquals(sampleRate, header.getInt(24))
        assertEquals(sampleRate * channels * 2, header.getInt(28))
        assertEquals(channels * 2, header.getShort(32).toInt())
        assertEquals(16, header.getShort(34).toInt())
        assertEquals("data", header.ascii(36))
        assertEquals(dataBytes, header.getInt(40))
    }
}
