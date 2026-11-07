package com.example.onwin.data.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Кодировка PCM-сэмплов на выходе декодера.
 *
 * @property bytesPerSample размер одного сэмпла одного канала в байтах.
 * @property androidEncoding соответствующая константа `android.media.AudioFormat.ENCODING_*`.
 *   Значения продублированы числами, чтобы этот файл оставался чистым Kotlin и тестировался на JVM.
 */
internal enum class PcmEncoding(val bytesPerSample: Int, val androidEncoding: Int) {
    /** Беззнаковые 8 бит. */
    PCM_8BIT(1, 3),

    /** Знаковые 16 бит. */
    PCM_16BIT(2, 2),

    /** Знаковые 24 бита, упакованные в 3 байта. */
    PCM_24BIT_PACKED(3, 21),

    /** Знаковые 32 бита. */
    PCM_32BIT(4, 22),

    /** Float 32 бита, номинальный диапазон -1..1. */
    FLOAT(4, 4);

    companion object {
        /** Кодировка по константе `AudioFormat.ENCODING_*` или null, если она не поддерживается. */
        fun fromAndroid(encoding: Int): PcmEncoding? = entries.firstOrNull { it.androidEncoding == encoding }
    }
}

/**
 * Перевод буфера декодера (чередующиеся каналы, little-endian) в моно-сэмплы float.
 *
 * Каналы сводятся усреднением во float, без ограничения диапазона: клиппинг делается
 * один раз, при финальном переводе в 16 бит ([WavWriter.writeFloats]).
 *
 * @property encoding кодировка сэмплов.
 * @property channelCount число чередующихся каналов.
 */
internal class PcmFormat(val encoding: PcmEncoding, val channelCount: Int) {

    init {
        require(channelCount > 0) { "channelCount must be positive: $channelCount" }
    }

    /** Размер одного кадра (сэмплы всех каналов) в байтах. */
    val bytesPerFrame: Int = encoding.bytesPerSample * channelCount

    private val channelScale = 1f / channelCount

    /** Сколько целых кадров помещается в [byteCount] байтах. */
    fun frameCount(byteCount: Int): Int = byteCount / bytesPerFrame

    /**
     * Читает все целые кадры между position и limit буфера [src], пишет моно-сэмплы в [dst]
     * и сдвигает position на прочитанные байты. Неполный кадр в конце остаётся непрочитанным.
     *
     * Порядок байт [src] переключается на little-endian (так отдают PCM MediaCodec и WAV).
     *
     * @return число записанных в [dst] сэмплов.
     */
    fun toMono(src: ByteBuffer, dst: FloatArray): Int {
        src.order(ByteOrder.LITTLE_ENDIAN)
        val frames = frameCount(src.remaining())
        require(dst.size >= frames) { "dst is too small: ${dst.size} < $frames" }
        val bytesPerSample = encoding.bytesPerSample
        var position = src.position()
        for (frame in 0 until frames) {
            var sum = 0f
            for (channel in 0 until channelCount) {
                sum += readSample(src, position)
                position += bytesPerSample
            }
            dst[frame] = sum * channelScale
        }
        src.position(position)
        return frames
    }

    private fun readSample(src: ByteBuffer, index: Int): Float = when (encoding) {
        PcmEncoding.PCM_8BIT -> ((src.get(index).toInt() and 0xFF) - 128) / 128f
        PcmEncoding.PCM_16BIT -> src.getShort(index) / 32_768f
        PcmEncoding.PCM_24BIT_PACKED -> {
            // Старший байт знаковый: сдвиг влево на 16 сохраняет знак.
            val value = (src.get(index + 2).toInt() shl 16) or
                ((src.get(index + 1).toInt() and 0xFF) shl 8) or
                (src.get(index).toInt() and 0xFF)
            value / 8_388_608f
        }
        PcmEncoding.PCM_32BIT -> src.getInt(index) / 2_147_483_648f
        PcmEncoding.FLOAT -> src.getFloat(index)
    }
}
