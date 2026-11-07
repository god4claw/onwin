package com.example.onwin.data.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Синус [frequency] Гц длиной [count] сэмплов на частоте [rate]. */
internal fun sine(frequency: Double, rate: Int, count: Int, amplitude: Double = 0.5, startIndex: Int = 0) =
    FloatArray(count) { i -> (amplitude * sin(2 * PI * frequency * (startIndex + i) / rate)).toFloat() }

/** Собирает всё, что пришло в sink, в один массив. */
internal class CollectingSink : FloatSink {
    private var data = FloatArray(1024)

    /** Сколько сэмплов собрано. */
    var size = 0
        private set

    override fun write(samples: FloatArray, offset: Int, length: Int) {
        if (size + length > data.size) data = data.copyOf(maxOf(data.size * 2, size + length))
        System.arraycopy(samples, offset, data, size, length)
        size += length
    }

    fun toArray(): FloatArray = data.copyOf(size)
}

/** Ресемплирует [input] целиком или кусками из [chunkSizes] (циклически) и возвращает весь выход. */
internal fun resample(inputRate: Int, input: FloatArray, chunkSizes: IntArray? = null): FloatArray {
    val resampler = StreamingResampler(inputRate)
    val sink = CollectingSink()
    if (chunkSizes == null) {
        resampler.process(input, 0, input.size, sink)
    } else {
        var offset = 0
        var chunk = 0
        while (offset < input.size) {
            val length = minOf(chunkSizes[chunk++ % chunkSizes.size], input.size - offset)
            resampler.process(input, offset, length, sink)
            offset += length
        }
    }
    resampler.flush(sink)
    return sink.toArray()
}

/** Частота по интерполированным восходящим переходам через ноль на отрезке [from, to). */
internal fun zeroCrossingFrequency(samples: FloatArray, rate: Int, from: Int, to: Int): Double {
    var first = -1.0
    var last = -1.0
    var count = 0
    for (i in from + 1 until to) {
        val previous = samples[i - 1]
        val current = samples[i]
        if (previous < 0 && current >= 0) {
            val time = i - 1 + previous / (previous - current).toDouble()
            if (first < 0) first = time
            last = time
            count++
        }
    }
    return (count - 1) / (last - first) * rate
}

/** Среднеквадратичное значение на отрезке [from, to). */
internal fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Double {
    var sum = 0.0
    for (i in from until to) sum += samples[i].toDouble() * samples[i]
    return sqrt(sum / (to - from))
}

/** Амплитуда составляющей [frequency] Гц на отрезке [from, to) (алгоритм Гёрцеля). */
internal fun goertzelAmplitude(samples: FloatArray, rate: Int, frequency: Double, from: Int, to: Int): Double {
    val coefficient = 2 * cos(2 * PI * frequency / rate)
    var s1 = 0.0
    var s2 = 0.0
    for (i in from until to) {
        val s0 = samples[i] + coefficient * s1 - s2
        s2 = s1
        s1 = s0
    }
    val power = s1 * s1 + s2 * s2 - coefficient * s1 * s2
    return 2 * sqrt(power) / (to - from)
}

/** PCM16 little-endian. */
internal fun pcm16Bytes(vararg samples: Int): ByteBuffer =
    ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        samples.forEach { putShort(it.toShort()) }
        flip()
    }

/** Float32 little-endian. */
internal fun floatBytes(vararg samples: Float): ByteBuffer =
    ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
        samples.forEach { putFloat(it) }
        flip()
    }

/** Заголовок (первые 44 байта) WAV-файла. */
internal fun wavHeaderOf(file: File): ByteBuffer =
    ByteBuffer.wrap(file.readBytes(), 0, WavWriter.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)

/** PCM16-сэмплы канонического WAV (всё после 44 байт). */
internal fun wavSamplesOf(file: File): ShortArray {
    val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    bytes.position(WavWriter.HEADER_BYTES)
    return ShortArray(bytes.remaining() / 2) { bytes.getShort() }
}

/** Четыре ASCII-символа заголовка начиная с [index]. */
internal fun ByteBuffer.ascii(index: Int): String = String(ByteArray(4) { get(index + it) }, Charsets.US_ASCII)
