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
