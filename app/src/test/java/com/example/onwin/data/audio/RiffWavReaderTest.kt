package com.example.onwin.data.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RiffWavReaderTest {

    @Test
    fun canonicalPcm16_isRead() {
        val data = pcmData(6)
        val reader = RiffWavReader(wav(fmtChunk(1, 1, 16_000, 16), chunk("data", data)))
        assertEquals(16_000, reader.sampleRate)
        assertEquals(1, reader.channelCount)
        assertEquals(PcmEncoding.PCM_16BIT, reader.encoding)
        assertEquals(6L, reader.dataBytes)
        assertArrayEquals(data, readAll(reader, 1024))
    }

    @Test
    fun unknownChunks_areSkippedWithPadding() {
        val data = pcmData(8)
        val reader = RiffWavReader(
            wav(
                chunk("fact", ByteArray(4)),
                fmtChunk(1, 2, 44_100, 16),
                chunk("LIST", "INFOx".toByteArray()), // нечётный размер + байт выравнивания
                chunk("data", data),
            ),
        )
        assertEquals(44_100, reader.sampleRate)
        assertEquals(2, reader.channelCount)
        assertArrayEquals(data, readAll(reader, 1024))
    }

    @Test
    fun extensibleFloat_isRecognized() {
        val reader = RiffWavReader(wav(extensibleFmtChunk(subFormat = 3, channels = 2, bits = 32), chunk("data", pcmData(16))))
        assertEquals(PcmEncoding.FLOAT, reader.encoding)
        assertEquals(2, reader.channelCount)
    }

    @Test
    fun extensiblePcm24_isRecognized() {
        val reader = RiffWavReader(wav(extensibleFmtChunk(subFormat = 1, channels = 1, bits = 24), chunk("data", pcmData(6))))
        assertEquals(PcmEncoding.PCM_24BIT_PACKED, reader.encoding)
    }

    @Test
    fun adpcm_isUnsupported() {
        assertThrows(AudioConversionException.UnsupportedFormat::class.java) {
            RiffWavReader(wav(fmtChunk(2, 1, 8_000, 4, blockAlign = 256), chunk("data", pcmData(4))))
        }
    }

    @Test
    fun notWav_fails() {
        assertThrows(IOException::class.java) { RiffWavReader(ByteArrayInputStream("ID3 not a wave file".toByteArray())) }
    }

    @Test
    fun missingDataChunk_fails() {
        assertThrows(IOException::class.java) { RiffWavReader(wav(fmtChunk(1, 1, 16_000, 16))) }
    }

    @Test
    fun truncatedData_stopsAtLastWholeFrame() {
        // Заявлено 100 байт, в файле 7: один целый стерео-кадр (4 байта) и обрывок.
        val bytes = wavBytes(fmtChunk(1, 2, 16_000, 16), chunkHeader("data", 100) + pcmData(7))
        val reader = RiffWavReader(ByteArrayInputStream(bytes))
        assertEquals(4, readAll(reader, 1024).size)
    }

    @Test
    fun unknownDataSize_readsUntilEnd() {
        val bytes = wavBytes(fmtChunk(1, 1, 16_000, 16), chunkHeader("data", -1) + pcmData(10))
        val reader = RiffWavReader(ByteArrayInputStream(bytes))
        assertEquals(-1L, reader.dataBytes)
        assertEquals(10, readAll(reader, 1024).size)
    }

    @Test
    fun read_returnsWholeFramesOnly() {
        // 24 бит стерео: кадр 6 байт, в буфер на 64 байта помещается 10 кадров.
        val data = pcmData(6 * 25)
        val reader = RiffWavReader(wav(fmtChunk(1, 2, 48_000, 24), chunk("data", data)))
        val buffer = ByteBuffer.allocate(64)
        assertEquals(60, reader.read(buffer))
        assertArrayEquals(data, readAll(reader, 64, prefix = buffer.array().copyOf(60)))
    }

    private fun readAll(reader: RiffWavReader, bufferSize: Int, prefix: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArrayOutputStream().apply { write(prefix) }
        val buffer = ByteBuffer.allocate(bufferSize)
        while (true) {
            buffer.clear()
            val count = reader.read(buffer)
            if (count < 0) break
            assertEquals(0, count % (reader.channelCount * reader.encoding.bytesPerSample))
            out.write(buffer.array(), 0, count)
        }
        return out.toByteArray()
    }

    private fun pcmData(size: Int) = ByteArray(size) { (it * 7 + 1).toByte() }

    private fun fmtChunk(tag: Int, channels: Int, rate: Int, bits: Int, blockAlign: Int = channels * bits / 8) =
        chunk("fmt ", le(16) { putShort(tag.toShort()); putShort(channels.toShort()); putFmtTail(rate, blockAlign, bits) })

    private fun extensibleFmtChunk(subFormat: Int, channels: Int, bits: Int): ByteArray {
        val blockAlign = channels * bits / 8
        return chunk(
            "fmt ",
            le(40) {
                putShort(0xFFFE.toShort())
                putShort(channels.toShort())
                putFmtTail(48_000, blockAlign, bits)
                putShort(22)                    // cbSize
                putShort(bits.toShort())        // valid bits
                putInt(0)                       // channel mask
                putShort(subFormat.toShort())   // начало GUID SubFormat
                put(ByteArray(14))
            },
        )
    }

    private fun ByteBuffer.putFmtTail(rate: Int, blockAlign: Int, bits: Int) {
        putInt(rate)
        putInt(rate * blockAlign)
        putShort(blockAlign.toShort())
        putShort(bits.toShort())
    }

    private fun le(size: Int, block: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(block).array()

    private fun chunkHeader(id: String, size: Int) = id.toByteArray(Charsets.US_ASCII) + le(4) { putInt(size) }

    private fun chunk(id: String, body: ByteArray): ByteArray =
        chunkHeader(id, body.size) + body + if (body.size % 2 == 1) byteArrayOf(0) else byteArrayOf()

    private fun wavBytes(vararg chunks: ByteArray): ByteArray {
        val body = chunks.fold("WAVE".toByteArray(Charsets.US_ASCII)) { acc, c -> acc + c }
        return chunkHeader("RIFF", body.size) + body
    }

    private fun wav(vararg chunks: ByteArray) = ByteArrayInputStream(wavBytes(*chunks))
}
