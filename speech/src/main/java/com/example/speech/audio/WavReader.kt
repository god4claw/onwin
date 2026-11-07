package com.example.speech.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object WavReader {

    private const val HEADER_SIZE = 44
    private const val SAMPLE_RATE = 16_000
    private const val MAX_DURATION_MS = 60L * 60L * 1000L
    private const val WAVE_FORMAT_PCM = 1

    /**
     * Читает канонический WAV 16 кГц / моно / PCM16.
     *
     * Аудио целиком живёт в памяти как `FloatArray`, поэтому длительность
     * ограничена: 60 минут — это ~230 МБ, что уже на грани OOM на бюджетном
     * устройстве. Лимит вынесен параметром, чтобы его можно было уменьшить
     * (или проверить в тесте) без правки кода.
     *
     * Если файл оказался короче объявленного `data`-чанка, возвращается
     * только реально прочитанное: нулевой «хвост» не должен уходить в ASR.
     */
    fun readMono16k(
        file: File,
        maxDurationMs: Long = MAX_DURATION_MS,
    ): FloatArray {
        require(file.isFile) { "WAV-файл не найден: ${file.absolutePath}" }
        require(maxDurationMs > 0L) { "maxDurationMs должен быть > 0, получено $maxDurationMs" }
        val maxSamples = maxDurationMs * SAMPLE_RATE / 1000L

        RandomAccessFile(file, "r").use { raf ->
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

            val totalSamples = (dataSize / 2).toInt()
            require(totalSamples > 0) { "В WAV нет аудиоданных" }
            require(totalSamples <= maxSamples) {
                "Аудио длиннее заданного лимита $maxDurationMs мс " +
                    "(в файле ${totalSamples * 1000L / SAMPLE_RATE} мс)"
            }

            raf.seek(HEADER_SIZE.toLong())
            val samples = FloatArray(totalSamples)
            val readBuffer = ByteArray(64 * 1024)
            var sampleIndex = 0
            var remaining = dataSize

            while (remaining > 0 && sampleIndex < totalSamples) {
                val toRead = minOf(remaining, readBuffer.size.toLong()).toInt()
                val read = raf.read(readBuffer, 0, toRead)
                if (read <= 0) break
                val byteBuffer = ByteBuffer.wrap(readBuffer, 0, read).order(ByteOrder.LITTLE_ENDIAN)
                val count = read / 2
                for (i in 0 until count) {
                    if (sampleIndex >= totalSamples) break
                    samples[sampleIndex++] = byteBuffer.short / 32768f
                }
                remaining -= read
            }

            return if (sampleIndex == totalSamples) samples else samples.copyOf(sampleIndex)
        }
    }

    private fun readFourCc(buffer: ByteBuffer): String {
        val bytes = ByteArray(4)
        buffer.get(bytes)
        return String(bytes, Charsets.US_ASCII)
    }
}
