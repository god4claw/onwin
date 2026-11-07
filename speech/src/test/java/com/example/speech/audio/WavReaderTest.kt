package com.example.speech.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `reads canonical pcm16 mono 16k`() {
        val file = writeWav(shortArrayOf(0, 16_384, -32_768, 32_767))

        val samples = WavReader.readMono16k(file)

        assertEquals(4, samples.size)
        assertEquals(0f, samples[0], 0f)
        assertEquals(0.5f, samples[1], 1e-4f)
        assertEquals(-1f, samples[2], 0f)
        assertEquals(32_767f / 32_768f, samples[3], 1e-6f)
    }

    @Test
    fun `zero data size means read to end of file`() {
        val file = writeWav(shortArrayOf(1_000, 2_000, 3_000), declaredDataSize = 0)

        val samples = WavReader.readMono16k(file)

        assertEquals(3, samples.size)
    }

    @Test
    fun `declared data size larger than file is clamped`() {
        val file = writeWav(shortArrayOf(1_000, 2_000), declaredDataSize = 10_000)

        val samples = WavReader.readMono16k(file)

        assertEquals(2, samples.size)
    }

    @Test
    fun `rejects audio longer than limit`() {
        val file = writeWav(ShortArray(16_000))

        val error = assertThrows(IllegalArgumentException::class.java) {
            WavReader.readMono16k(file, maxDurationMs = 500)
        }

        assertTrue(error.message!!.contains("лимит"))
    }

    @Test
    fun `rejects file shorter than header`() {
        val file = tmp.newFile()
        file.writeBytes(ByteArray(10))

        assertThrows(IllegalArgumentException::class.java) {
            WavReader.readMono16k(file)
        }
    }

    @Test
    fun `stream gives the same samples as a whole read, also across odd buffer borders`() {
        val values = ShortArray(5_001) { ((it * 7_919) % 65_536 - 32_768).toShort() }
        val file = writeWav(values)
        val whole = WavReader.readMono16k(file)

        for (bufferBytes in listOf(1, 3, 7, 4_096)) {
            WavReader.open(file, readBufferBytes = bufferBytes).use { stream ->
                assertEquals(values.size.toLong(), stream.totalSamples)
                val out = FloatArray(values.size)
                var filled = 0
                val chunk = FloatArray(333)
                while (true) {
                    val read = stream.read(chunk)
                    if (read == 0) break
                    chunk.copyInto(out, filled, 0, read)
                    filled += read
                }
                assertEquals("buffer $bufferBytes", values.size, filled)
                assertTrue("buffer $bufferBytes", out.contentEquals(whole))
            }
        }
    }

    @Test
    fun `stream ends at the real end of a truncated file`() {
        val file = writeWav(shortArrayOf(100, 200, 300), declaredDataSize = 1_000_000)

        WavReader.open(file).use { stream ->
            assertEquals(3L, stream.totalSamples)
            val out = FloatArray(10)
            assertEquals(3, stream.read(out))
            assertEquals(0, stream.read(out))
        }
    }

    @Test
    fun `open rejects audio longer than the limit before reading data`() {
        val file = writeWav(ShortArray(32_000))

        val error = assertThrows(IllegalArgumentException::class.java) {
            WavReader.open(file, maxDurationMs = 1_000)
        }
        assertTrue(error.message, "лимита" in error.message.orEmpty())
        WavReader.open(file, maxDurationMs = 2_000).use { assertEquals(2_000L, it.durationMs) }
    }

    private fun writeWav(
        values: ShortArray,
        declaredDataSize: Int = values.size * 2,
    ): File {
        val data = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (value in values) data.putShort(value)

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + declaredDataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(1) // mono
        header.putInt(16_000)
        header.putInt(32_000)
        header.putShort(2) // blockAlign
        header.putShort(16) // bitsPerSample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(declaredDataSize)

        val file = tmp.newFile()
        file.outputStream().use { out ->
            out.write(header.array())
            out.write(data.array())
        }
        return file
    }
}
