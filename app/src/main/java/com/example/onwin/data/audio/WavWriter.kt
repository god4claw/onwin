package com.example.onwin.data.audio

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.min

/**
 * Потоковая запись WAV: PCM 16 бит little-endian, канонический 44-байтовый заголовок,
 * сразу за ним данные `data`.
 *
 * Сначала пишется заголовок-заглушка, сэмплы буферизуются, настоящий заголовок
 * с размерами записывается в [close]. Память не зависит от длины записи.
 * Подходит и для импорта ([writeFloats]), и для записи с микрофона ([writeShorts]).
 *
 * Размеры в заголовке ограничены знаковым 32-битным числом (многие читатели WAV, включая
 * sherpa-onnx, читают их как int32): не больше [MAX_DATA_BYTES] байт данных, ~18,6 ч
 * для 16 кГц моно. При превышении бросается [WavSizeLimitException].
 *
 * Approach informed by ProtocolVoice AudioImporter, Apache-2.0 (заглушка заголовка + патч в конце).
 *
 * @property sampleRate частота дискретизации, Гц.
 * @property channelCount число каналов; сэмплы каналов передаются чередующимися.
 */
class WavWriter internal constructor(
    file: File,
    val sampleRate: Int,
    val channelCount: Int,
    private val maxDataBytes: Long,
) : Closeable {

    /**
     * Создаёт [file] (перезаписывая существующий) и пишет в него заголовок-заглушку.
     *
     * @param file файл для записи.
     * @param sampleRate частота дискретизации, Гц.
     * @param channelCount число каналов.
     */
    constructor(
        file: File,
        sampleRate: Int = TARGET_SAMPLE_RATE,
        channelCount: Int = 1,
    ) : this(file, sampleRate, channelCount, MAX_DATA_BYTES)

    private val channel: FileChannel
    private val buffer = ByteBuffer.allocate(BUFFER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private val blockAlign = channelCount * BYTES_PER_SAMPLE
    private var closed = false

    /** Сколько байт PCM-данных записано (без заголовка). */
    var dataBytes: Long = 0
        private set

    /** Сколько кадров (сэмплов на канал) записано. */
    val frameCount: Long get() = dataBytes / blockAlign

    /** Длительность записанного, мс. */
    val durationMs: Long get() = frameCount * 1000 / sampleRate

    init {
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        require(channelCount > 0) { "channelCount must be positive: $channelCount" }
        channel = FileOutputStream(file).channel
        try {
            // Относительная запись: позиция канала встаёт сразу за заглушкой.
            buffer.put(ByteArray(HEADER_BYTES))
            flushBuffer()
        } catch (e: IOException) {
            channel.close()
            throw e
        }
    }

    /**
     * Дописывает сэмплы PCM16 из [samples] (каналы чередуются).
     *
     * @throws WavSizeLimitException если данные не поместятся в WAV.
     */
    fun writeShorts(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset) {
        reserve(samples.size, offset, length)
        var index = offset
        val end = offset + length
        while (index < end) {
            if (!buffer.hasRemaining()) flushBuffer()
            val count = min(end - index, buffer.remaining() / BYTES_PER_SAMPLE)
            repeat(count) { buffer.putShort(samples[index++]) }
        }
    }

    /**
     * Дописывает float-сэмплы из [samples] (каналы чередуются), переводя их в 16 бит.
     * Значения за пределами -1..1 ограничиваются; это единственное место клиппинга в конвейере.
     *
     * @throws WavSizeLimitException если данные не поместятся в WAV.
     */
    fun writeFloats(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset) {
        reserve(samples.size, offset, length)
        var index = offset
        val end = offset + length
        while (index < end) {
            if (!buffer.hasRemaining()) flushBuffer()
            val count = min(end - index, buffer.remaining() / BYTES_PER_SAMPLE)
            repeat(count) { buffer.putShort(floatToPcm16(samples[index++])) }
        }
    }

    /**
     * Сбрасывает буфер, записывает итоговый заголовок и закрывает файл.
     * Повторный вызов ничего не делает.
     */
    override fun close() {
        if (closed) return
        closed = true
        channel.use {
            flushBuffer()
            writeFully(wavHeader(dataBytes, sampleRate, channelCount), position = 0)
        }
    }

    private fun reserve(size: Int, offset: Int, length: Int) {
        check(!closed) { "WavWriter is closed" }
        require(offset >= 0 && length >= 0 && offset + length <= size) { "Bad range: $offset+$length of $size" }
        val bytes = length.toLong() * BYTES_PER_SAMPLE
        if (dataBytes + bytes > maxDataBytes) throw WavSizeLimitException(maxDataBytes)
        dataBytes += bytes
    }

    internal fun flushBuffer() {
        buffer.flip()
        while (buffer.hasRemaining()) channel.write(buffer)
        buffer.clear()
    }

    /** Позиционная запись: позиция канала не меняется. */
    private fun writeFully(src: ByteBuffer, position: Long) {
        var offset = position
        while (src.hasRemaining()) offset += channel.write(src, offset)
    }

    /** Константы формата. */
    companion object {
        /** Размер канонического заголовка WAV, байт. */
        const val HEADER_BYTES = 44

        /** Максимум данных, при котором размер RIFF (данные + 36) помещается в int32. */
        const val MAX_DATA_BYTES: Long = Int.MAX_VALUE.toLong() - (HEADER_BYTES - 8)

        private const val BYTES_PER_SAMPLE = 2
        private const val BUFFER_BYTES = 64 * 1024

        /** Float -1..1 → PCM16 с округлением и ограничением; NaN → 0. */
        internal fun floatToPcm16(value: Float): Short =
            Math.round(value * 32_768f).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

        /** Канонический 44-байтовый заголовок PCM16 WAV. */
        internal fun wavHeader(dataBytes: Long, sampleRate: Int, channelCount: Int): ByteBuffer {
            val blockAlign = channelCount * BYTES_PER_SAMPLE
            return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt((dataBytes + HEADER_BYTES - 8).toInt())
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)                          // размер fmt-чанка
                putShort(1)                         // PCM
                putShort(channelCount.toShort())
                putInt(sampleRate)
                putInt(sampleRate * blockAlign)     // byte rate
                putShort(blockAlign.toShort())
                putShort(16)                        // бит на сэмпл
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataBytes.toInt())
                flip()
            }
        }
    }
}

/**
 * Данные не помещаются в WAV: размер RIFF превысил бы знаковое 32-битное число.
 *
 * @property maxDataBytes допустимый максимум данных, байт.
 */
class WavSizeLimitException(val maxDataBytes: Long) :
    IOException("WAV data would exceed $maxDataBytes bytes (signed 32-bit RIFF size limit)")
