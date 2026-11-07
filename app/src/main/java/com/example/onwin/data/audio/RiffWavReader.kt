package com.example.onwin.data.audio

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Разбор WAV (RIFF) на случай, если на устройстве нет декодера `audio/raw`.
 *
 * Обходит чанки по порядку: неизвестные (`LIST`, `fact`, `bext`…) пропускаются с учётом
 * выравнивания на чётный байт, заголовок не считается 44-байтовым. Поддерживаются PCM
 * 8/16/24/32 бит и float 32 бит, в том числе в `WAVE_FORMAT_EXTENSIBLE`.
 * Конструктор читает заголовок до начала данных; дальше [read] отдаёт целые кадры.
 *
 * @param input поток с WAV; закрывается в [close].
 * @throws AudioConversionException.UnsupportedFormat если кодировка не поддерживается.
 * @throws IOException если это не WAV или заголовок повреждён.
 */
internal class RiffWavReader(private val input: InputStream) : Closeable {

    /** Частота дискретизации, Гц. */
    val sampleRate: Int

    /** Число каналов. */
    val channelCount: Int

    /** Кодировка сэмплов. */
    val encoding: PcmEncoding

    /** Размер данных из заголовка, байт; -1, если не указан (читается до конца потока). */
    val dataBytes: Long

    private val frameBytes: Int
    private var remaining: Long

    init {
        val riff = readExactly(12)
        if (riff.ascii(0) != "RIFF" || riff.ascii(8) != "WAVE") throw IOException("Not a RIFF/WAVE stream")
        var fmt: Fmt? = null
        var dataSize = 0L
        while (true) {
            val chunk = readExactly(8)
            val id = chunk.ascii(0)
            val size = chunk.getInt(4).toLong() and 0xFFFF_FFFFL
            if (id == "data") {
                dataSize = size
                break
            }
            if (id == "fmt ") fmt = parseFmt(size) else skipExactly(size)
            if (size % 2 == 1L) skipExactly(1)
        }
        val format = fmt ?: throw IOException("WAV data chunk before fmt chunk")
        sampleRate = format.sampleRate
        channelCount = format.channelCount
        encoding = format.encoding
        frameBytes = channelCount * encoding.bytesPerSample
        // 0xFFFFFFFF пишут потоковые записи, у которых размер неизвестен.
        dataBytes = if (dataSize == 0xFFFF_FFFFL) -1 else dataSize
        remaining = if (dataBytes < 0) Long.MAX_VALUE else dataBytes
    }

    /**
     * Читает в [dst] (от position до limit) только целые кадры.
     *
     * @return число прочитанных байт или -1, если данные закончились.
     */
    fun read(dst: ByteBuffer): Int {
        require(dst.hasArray()) { "Heap buffer required" }
        require(dst.remaining() >= frameBytes) { "dst must fit at least one frame" }
        if (remaining < frameBytes) return -1
        val capacity = min(dst.remaining().toLong(), remaining).toInt() / frameBytes * frameBytes
        val array = dst.array()
        val start = dst.arrayOffset() + dst.position()
        var total = 0
        while (total < capacity) {
            val count = input.read(array, start + total, capacity - total)
            if (count < 0) break
            total += count
        }
        // Обрезанный файл: неполный последний кадр отбрасывается.
        total = total / frameBytes * frameBytes
        if (total == 0) {
            remaining = 0
            return -1
        }
        remaining -= total
        dst.position(dst.position() + total)
        return total
    }

    /** Закрывает исходный поток. */
    override fun close() = input.close()

    private fun parseFmt(size: Long): Fmt {
        if (size < 16) throw IOException("fmt chunk is too short: $size")
        val bytes = readExactly(min(size, 40).toInt())
        skipExactly(size - bytes.limit())
        var tag = bytes.getShort(0).toInt() and 0xFFFF
        val channels = bytes.getShort(2).toInt() and 0xFFFF
        val rate = bytes.getInt(4)
        val blockAlign = bytes.getShort(12).toInt() and 0xFFFF
        val bits = bytes.getShort(14).toInt() and 0xFFFF
        // WAVE_FORMAT_EXTENSIBLE: настоящий формат — первые 2 байта GUID SubFormat.
        if (tag == FORMAT_EXTENSIBLE && bytes.limit() >= 26) tag = bytes.getShort(24).toInt() and 0xFFFF
        val encoding = when {
            tag == FORMAT_PCM && bits == 8 -> PcmEncoding.PCM_8BIT
            tag == FORMAT_PCM && bits == 16 -> PcmEncoding.PCM_16BIT
            tag == FORMAT_PCM && bits == 24 -> PcmEncoding.PCM_24BIT_PACKED
            tag == FORMAT_PCM && bits == 32 -> PcmEncoding.PCM_32BIT
            tag == FORMAT_FLOAT && bits == 32 -> PcmEncoding.FLOAT
            else -> throw AudioConversionException.UnsupportedFormat(MIME_WAV, "format tag $tag, $bits bits")
        }
        if (channels <= 0 || rate <= 0 || blockAlign != channels * encoding.bytesPerSample) {
            throw IOException("Invalid fmt chunk: channels=$channels rate=$rate blockAlign=$blockAlign")
        }
        return Fmt(rate, channels, encoding)
    }

    private fun readExactly(count: Int): ByteBuffer {
        val bytes = ByteArray(count)
        var total = 0
        while (total < count) {
            val read = input.read(bytes, total, count - total)
            if (read < 0) throw EOFException("Unexpected end of WAV header")
            total += read
        }
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun skipExactly(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
            } else {
                // skip() может вернуть 0, не дойдя до конца: проверяем чтением одного байта.
                if (input.read() < 0) throw EOFException("Unexpected end of WAV header")
                left--
            }
        }
    }

    private fun ByteBuffer.ascii(index: Int): String = String(array(), index, 4, Charsets.US_ASCII)

    private class Fmt(val sampleRate: Int, val channelCount: Int, val encoding: PcmEncoding)

    private companion object {
        const val FORMAT_PCM = 1
        const val FORMAT_FLOAT = 3
        const val FORMAT_EXTENSIBLE = 0xFFFE
        const val MIME_WAV = "audio/wav"
    }
}
