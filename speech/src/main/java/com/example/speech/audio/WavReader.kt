package com.example.speech.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object WavReader {

    private const val HEADER_SIZE = 44
    private const val SAMPLE_RATE = 16_000
    private const val MAX_DURATION_MS = 60L * 60L * 1000L
    private const val WAVE_FORMAT_PCM = 1
    private const val READ_BUFFER_BYTES = 64 * 1024

    /**
     * Открытый канонический WAV 16 кГц / моно / PCM16: заголовок проверен,
     * данные читаются кусками через [read]. Память не зависит от длины файла.
     *
     * Если файл оказался короче объявленного `data`-чанка, [read] просто
     * раньше вернёт 0: нулевой «хвост» не должен уходить в ASR.
     */
    class Stream internal constructor(
        private val raf: RandomAccessFile,
        /** Сэмплов по заголовку (не больше, чем реально есть в файле). */
        val totalSamples: Long,
        readBufferBytes: Int,
    ) : Closeable {
        private val bytes = ByteArray(readBufferBytes)
        private var remainingBytes = totalSamples * 2
        /** Младший байт сэмпла, если прошлое чтение закончилось на нечётном байте. */
        private var carry = -1

        val durationMs: Long get() = totalSamples * 1000L / SAMPLE_RATE

        /** Заполняет `dst[offset until offset + length]`; возвращает число сэмплов, 0 — данные кончились. */
        fun read(dst: FloatArray, offset: Int = 0, length: Int = dst.size - offset): Int {
            require(offset >= 0 && length >= 0 && offset + length <= dst.size) {
                "Некорректный диапазон offset=$offset length=$length для массива ${dst.size}"
            }
            var written = 0
            while (written < length && remainingBytes > 0) {
                val wanted = minOf(
                    (length - written).toLong() * 2 - (if (carry >= 0) 1 else 0),
                    remainingBytes,
                    bytes.size.toLong(),
                ).toInt()
                val read = raf.read(bytes, 0, wanted)
                if (read <= 0) {
                    remainingBytes = 0
                    break
                }
                remainingBytes -= read
                var i = 0
                if (carry >= 0) {
                    dst[offset + written++] = sample(carry, bytes[0].toInt())
                    carry = -1
                    i = 1
                }
                while (i + 1 < read) {
                    dst[offset + written++] = sample(bytes[i].toInt() and 0xFF, bytes[i + 1].toInt())
                    i += 2
                }
                if (i < read) carry = bytes[i].toInt() and 0xFF
            }
            return written
        }

        override fun close() = raf.close()

        private fun sample(low: Int, high: Int): Float = ((high shl 8) or low).toShort() / 32768f
    }

    /**
     * Проверяет заголовок и открывает данные для чтения кусками.
     *
     * @param maxDurationMs файл длиннее отклоняется сразу, до чтения данных.
     */
    fun open(
        file: File,
        maxDurationMs: Long = MAX_DURATION_MS,
        readBufferBytes: Int = READ_BUFFER_BYTES,
    ): Stream {
        require(file.isFile) { "WAV-файл не найден: ${file.absolutePath}" }
        require(maxDurationMs > 0L) { "maxDurationMs должен быть > 0, получено $maxDurationMs" }
        require(readBufferBytes >= 1) { "readBufferBytes должен быть >= 1, получено $readBufferBytes" }
        val maxSamples = maxDurationMs * SAMPLE_RATE / 1000L

        val raf = RandomAccessFile(file, "r")
        try {
            val fileLength = raf.length()
            require(fileLength >= HEADER_SIZE) {
                "WAV короче $HEADER_SIZE байт: ${file.absolutePath}"
            }

            val header = ByteArray(HEADER_SIZE)
            raf.readFully(header)
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            require(readFourCc(buffer) == "RIFF") {
                "Это не WAV (ожидался RIFF): ${file.absolutePath}"
            }
            buffer.position(8)
            require(readFourCc(buffer) == "WAVE") {
                "Это не WAV (ожидался WAVE): ${file.absolutePath}"
            }

            buffer.position(12)
            require(readFourCc(buffer) == "fmt ") {
                "WAV должен начинаться с fmt-чанка: ${file.absolutePath}"
            }
            require(buffer.int == 16) {
                "Ожидался fmt-чанк размером 16 байт: ${file.absolutePath}"
            }

            val audioFormat = buffer.short.toInt()
            val channels = buffer.short.toInt()
            val sampleRate = buffer.int
            buffer.int
            val blockAlign = buffer.short.toInt()
            val bitsPerSample = buffer.short.toInt()

            buffer.position(36)
            require(readFourCc(buffer) == "data") {
                "WAV должен содержать data-чанк сразу после fmt: ${file.absolutePath}"
            }
            val declaredDataSize = buffer.int.toLong() and 0xFFFFFFFFL
            val available = fileLength - HEADER_SIZE
            val dataSize = when {
                declaredDataSize == 0L -> available
                declaredDataSize > available -> available
                else -> declaredDataSize
            }

            require(audioFormat == WAVE_FORMAT_PCM) {
                "WAV должен быть PCM, получен format=$audioFormat: ${file.absolutePath}"
            }
            require(channels == 1) {
                "WAV должен быть моно, получено каналов: $channels"
            }
            require(sampleRate == SAMPLE_RATE) {
                "WAV должен быть 16 кГц, получено: $sampleRate Гц"
            }
            require(bitsPerSample == 16) {
                "WAV должен быть PCM16, получено бит на сэмпл: $bitsPerSample"
            }
            require(blockAlign == channels * bitsPerSample / 8) {
                "Некорректный blockAlign=$blockAlign для $channels каналов PCM16"
            }

            val totalSamples = dataSize / 2
            require(totalSamples > 0) { "В WAV нет аудиоданных" }
            require(totalSamples <= maxSamples) {
                "Аудио длиннее заданного лимита $maxDurationMs мс " +
                    "(в файле ${totalSamples * 1000L / SAMPLE_RATE} мс)"
            }
            return Stream(raf, totalSamples, readBufferBytes)
        } catch (t: Throwable) {
            raf.close()
            throw t
        }
    }

    /**
     * Читает канонический WAV 16 кГц / моно / PCM16 целиком.
     *
     * Аудио целиком живёт в памяти как `FloatArray`, поэтому длительность
     * ограничена: 60 минут — это ~230 МБ. Длинные файлы читаются кусками
     * через [open] (так работает `SherpaVadTranscriber`).
     */
    fun readMono16k(
        file: File,
        maxDurationMs: Long = MAX_DURATION_MS,
    ): FloatArray = open(file, maxDurationMs).use { stream ->
        require(stream.totalSamples <= Int.MAX_VALUE) {
            "Аудио слишком длинное для чтения целиком: ${stream.durationMs} мс"
        }
        val samples = FloatArray(stream.totalSamples.toInt())
        var filled = 0
        while (filled < samples.size) {
            val read = stream.read(samples, filled, samples.size - filled)
            if (read <= 0) break
            filled += read
        }
        if (filled == samples.size) samples else samples.copyOf(filled)
    }

    private fun readFourCc(buffer: ByteBuffer): String {
        val bytes = ByteArray(4)
        buffer.get(bytes)
        return String(bytes, Charsets.US_ASCII)
    }
}
