package com.example.onwin.data.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmFormatTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun pcm16Mono_isScaledBy32768() {
        val mono = toMono(PcmFormat(PcmEncoding.PCM_16BIT, 1), pcm16Bytes(0, 16_384, -32_768, 32_767))
        assertArrayEquals(floatArrayOf(0f, 0.5f, -1f, 32_767f / 32_768), mono, 0f)
    }

    @Test
    fun pcm16Stereo_isAveraged() {
        val mono = toMono(PcmFormat(PcmEncoding.PCM_16BIT, 2), pcm16Bytes(16_384, 0, -16_384, -16_384))
        assertArrayEquals(floatArrayOf(0.25f, -0.5f), mono, 0f)
    }

    @Test
    fun float6Channels_isAveraged() {
        val pcm = floatBytes(0.6f, 0.6f, 0.6f, 0f, 0f, 0f, -1f, -1f, -1f, -1f, -1f, -1f)
        val mono = toMono(PcmFormat(PcmEncoding.FLOAT, 6), pcm)
        assertArrayEquals(floatArrayOf(0.3f, -1f), mono, 1e-6f)
    }

    @Test
    fun float_isNotClippedBeforeFinalConversion() {
        // Если бы клиппинг был до усреднения, (1.5, -0.5) дало бы (1.0 - 0.5) / 2 = 0.25.
        val mono = toMono(PcmFormat(PcmEncoding.FLOAT, 2), floatBytes(1.5f, -0.5f, 3f, 3f))
        assertArrayEquals(floatArrayOf(0.5f, 3f), mono, 0f)

        val file = temp.newFile("clip.wav")
        WavWriter(file).use { it.writeFloats(mono) }
        assertArrayEquals(shortArrayOf(16_384, 32_767), wavSamplesOf(file))
    }

    @Test
    fun pcm8_isUnsigned() {
        val pcm = ByteBuffer.wrap(byteArrayOf(0, 128.toByte(), 255.toByte()))
        assertArrayEquals(floatArrayOf(-1f, 0f, 127f / 128), toMono(PcmFormat(PcmEncoding.PCM_8BIT, 1), pcm), 0f)
    }

    @Test
    fun pcm24_isSignExtended() {
        val pcm = ByteBuffer.wrap(
            byteArrayOf(
                0xFF.toByte(), 0xFF.toByte(), 0x7F,           // 8 388 607
                0x00, 0x00, 0x80.toByte(),                    // -8 388 608
                0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),  // -1
                0x00, 0x00, 0x40,                             // 4 194 304
            ),
        )
        val mono = toMono(PcmFormat(PcmEncoding.PCM_24BIT_PACKED, 1), pcm)
        assertArrayEquals(floatArrayOf(8_388_607f / 8_388_608, -1f, -1f / 8_388_608, 0.5f), mono, 0f)
    }

    @Test
    fun pcm32_isScaledBy2Pow31() {
        val pcm = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        pcm.putInt(Int.MIN_VALUE).putInt(1 shl 30).putInt(0)
        pcm.flip()
        assertArrayEquals(floatArrayOf(-1f, 0.5f, 0f), toMono(PcmFormat(PcmEncoding.PCM_32BIT, 1), pcm), 0f)
    }

    @Test
    fun partialFrame_isLeftInBuffer() {
        val format = PcmFormat(PcmEncoding.PCM_16BIT, 2)
        val pcm = pcm16Bytes(100, 200, 300)
        val dst = FloatArray(4)
        assertEquals(1, format.frameCount(pcm.remaining()))
        assertEquals(1, format.toMono(pcm, dst))
        assertEquals(4, pcm.position())
        assertEquals(2, pcm.remaining())
    }

    @Test
    fun androidEncodings_areMapped() {
        assertEquals(PcmEncoding.PCM_16BIT, PcmEncoding.fromAndroid(2))
        assertEquals(PcmEncoding.PCM_8BIT, PcmEncoding.fromAndroid(3))
        assertEquals(PcmEncoding.FLOAT, PcmEncoding.fromAndroid(4))
        assertEquals(PcmEncoding.PCM_24BIT_PACKED, PcmEncoding.fromAndroid(21))
        assertEquals(PcmEncoding.PCM_32BIT, PcmEncoding.fromAndroid(22))
        assertNull(PcmEncoding.fromAndroid(1))
        assertNull(PcmEncoding.fromAndroid(13))
    }

    private fun toMono(format: PcmFormat, pcm: ByteBuffer): FloatArray {
        val dst = FloatArray(format.frameCount(pcm.remaining()))
        assertEquals(dst.size, format.toMono(pcm, dst))
        return dst
    }
}
