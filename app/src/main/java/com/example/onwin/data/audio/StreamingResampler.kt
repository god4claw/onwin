package com.example.onwin.data.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Получатель потока моно-сэмплов float.
 */
internal fun interface FloatSink {
    /**
     * Принимает [length] сэмплов из [samples], начиная с [offset].
     * Массив переиспользуется отправителем: данные нужно скопировать, если их надо хранить.
     */
    fun write(samples: FloatArray, offset: Int, length: Int)
}

/**
 * Потоковый ресемплер моно-сигнала: точный рациональный полифазный FIR с окном Кайзера.
 *
 * Время выходного сэмпла n во входных сэмплах — `t = n * step / phaseCount`, где
 * `step / phaseCount = inputRate / outputRate` после сокращения на НОД. Позиция хранится
 * целыми числами, поэтому длительность не «уплывает» даже на многочасовых записях.
 * Для каждой из `phaseCount` дробных позиций заранее посчитана своя строка коэффициентов,
 * так что на выходной сэмпл приходится одно скалярное произведение длиной [taps].
 * Если фаз больше [MAX_EXACT_PHASES] (нестандартные частоты вроде 44 056 Гц), таблица
 * строится на [INTERPOLATED_PHASES] фаз, а между соседними строками — линейная интерполяция.
 *
 * Фильтр: полоса пропускания до 0.875 Найквиста меньшей из частот, подавление ~80 дБ
 * от её Найквиста (для 16 кГц: пропускание до 7 кГц, подавление от 8 кГц).
 *
 * Ядро центрировано, начало дополнено нулями, [flush] дописывает нули в конец — задержки
 * нет, таймстемпы не сдвигаются. Выход: ровно `ceil(входных * outputRate / inputRate)` сэмплов.
 * Результат побитово одинаков при любой нарезке входа на куски. Память постоянна:
 * буферы выделяются один раз, в горячем цикле аллокаций нет.
 *
 * Если частоты совпадают, сигнал передаётся как есть.
 *
 * @property inputRate частота входа, Гц.
 * @property outputRate частота выхода, Гц.
 */
internal class StreamingResampler(
    val inputRate: Int,
    val outputRate: Int = TARGET_SAMPLE_RATE,
) {
    private val bypass = inputRate == outputRate

    // t = readIndex + phase / phaseCount (readIndex — в координатах buffer).
    private val phaseCount: Int
    private val step: Int

    /** Длина фильтра во входных сэмплах (0 в режиме bypass). */
    val taps: Int

    /** Число строк таблицы коэффициентов (без дополнительной строки для интерполяции). */
    val tablePhases: Int

    /** true, если между строками таблицы используется линейная интерполяция. */
    val interpolated: Boolean

    private val table: FloatArray
    private val buffer: FloatArray
    private val output = FloatArray(if (bypass) 0 else OUTPUT_BLOCK)

    private var bufferLength: Int
    private var readIndex = 0
    private var phase = 0
    private var outputLength = 0
    private var inputCount = 0L
    private var outputCount = 0L
    private var flushed = false

    init {
        require(inputRate > 0 && outputRate > 0) { "Invalid rates: $inputRate -> $outputRate" }
        val gcd = gcd(inputRate, outputRate)
        phaseCount = outputRate / gcd
        step = inputRate / gcd
        if (bypass) {
            taps = 0
            tablePhases = 0
            interpolated = false
            table = FloatArray(0)
        } else {
            // Частоты нормированы на входную: циклы на входной сэмпл.
            val lowRate = min(inputRate, outputRate).toDouble()
            val cutoff = (PASSBAND + STOPBAND) / 2 * lowRate / inputRate
            val transition = (STOPBAND - PASSBAND) * lowRate / inputRate
            // Оценка порядка фильтра Кайзера.
            val order = ceil((ATTENUATION_DB - 7.95) / (2.285 * 2 * PI * transition)).toInt()
            taps = (order + 3) / 4 * 4  // кратно 4 — под развёрнутый цикл в dot()
            interpolated = phaseCount > MAX_EXACT_PHASES
            tablePhases = if (interpolated) INTERPOLATED_PHASES else phaseCount
            table = buildTable(cutoff)
        }
        check(bypass || step < phaseCount.toLong() * taps) { "Ratio too large: $inputRate -> $outputRate" }
        buffer = if (bypass) FloatArray(0) else FloatArray(taps + INPUT_BLOCK)
        // Нули перед первым сэмплом: левая половина ядра для t = 0.
        bufferLength = if (bypass) 0 else taps / 2 - 1
    }

    /**
     * Ресемплирует [length] сэмплов из [input], начиная с [offset]; результат уходит в [sink]
     * (возможно, частями и с задержкой до следующего вызова или [flush]).
     */
    fun process(input: FloatArray, offset: Int, length: Int, sink: FloatSink) {
        check(!flushed) { "Resampler is already flushed" }
        require(offset >= 0 && length >= 0 && offset + length <= input.size) { "Bad range: $offset+$length" }
        inputCount += length
        if (bypass) {
            if (length > 0) sink.write(input, offset, length)
            outputCount += length
            return
        }
        var consumed = 0
        while (consumed < length) {
            val count = min(length - consumed, buffer.size - bufferLength)
            System.arraycopy(input, offset + consumed, buffer, bufferLength, count)
            bufferLength += count
            consumed += count
            produce(sink, Long.MAX_VALUE)
            compact()
        }
        drainOutput(sink)
    }

    /**
     * Завершает поток: дописывает нули и выдаёт оставшийся хвост в [sink].
     * После вызова [process] больше не принимается.
     */
    fun flush(sink: FloatSink) {
        if (flushed) return
        flushed = true
        if (bypass) return
        // Выходы n с n * step < inputCount * phaseCount, т. е. t < inputCount.
        val limit = ceilDiv(inputCount * phaseCount, step.toLong())
        while (outputCount < limit) {
            buffer.fill(0f, bufferLength, buffer.size)
            bufferLength = buffer.size
            produce(sink, limit)
            compact()
        }
        drainOutput(sink)
    }

    override fun toString(): String = if (bypass) {
        "StreamingResampler($inputRate -> $outputRate Hz, bypass)"
    } else {
        "StreamingResampler($inputRate -> $outputRate Hz, taps=$taps, phases=$tablePhases, " +
            "interpolated=$interpolated)"
    }

    private fun produce(sink: FloatSink, limit: Long) {
        while (outputCount < limit && readIndex + taps <= bufferLength) {
            output[outputLength++] = computeSample()
            outputCount++
            phase += step
            readIndex += phase / phaseCount
            phase %= phaseCount
            if (outputLength == output.size) drainOutput(sink)
        }
    }

    private fun computeSample(): Float {
        if (!interpolated) return dot(phase * taps, readIndex)
        val scaled = phase.toLong() * tablePhases
        val row = (scaled / phaseCount).toInt()
        val weight = (scaled % phaseCount).toFloat() / phaseCount
        val a = dot(row * taps, readIndex)
        val b = dot((row + 1) * taps, readIndex)
        return a + (b - a) * weight
    }

    private fun dot(rowOffset: Int, bufferOffset: Int): Float {
        val table = table
        val buffer = buffer
        var s0 = 0f
        var s1 = 0f
        var s2 = 0f
        var s3 = 0f
        var k = 0
        while (k < taps) {
            s0 += table[rowOffset + k] * buffer[bufferOffset + k]
            s1 += table[rowOffset + k + 1] * buffer[bufferOffset + k + 1]
            s2 += table[rowOffset + k + 2] * buffer[bufferOffset + k + 2]
            s3 += table[rowOffset + k + 3] * buffer[bufferOffset + k + 3]
            k += 4
        }
        return (s0 + s1) + (s2 + s3)
    }

    /** Сдвигает непрочитанный хвост в начало буфера. */
    private fun compact() {
        val keep = bufferLength - readIndex
        System.arraycopy(buffer, readIndex, buffer, 0, keep)
        bufferLength = keep
        readIndex = 0
    }

    private fun drainOutput(sink: FloatSink) {
        if (outputLength > 0) sink.write(output, 0, outputLength)
        outputLength = 0
    }

    /**
     * Строка p содержит коэффициенты для дробной позиции p / tablePhases: элемент j
     * умножается на входной сэмпл floor(t) - taps/2 + 1 + j. Каждая строка нормирована
     * на единичное усиление по постоянному току.
     */
    private fun buildTable(cutoff: Double): FloatArray {
        val rows = if (interpolated) tablePhases + 1 else tablePhases
        val half = taps / 2
        val windowNorm = besselI0(KAISER_BETA)
        val result = FloatArray(rows * taps)
        val row = DoubleArray(taps)
        for (p in 0 until rows) {
            val fraction = p.toDouble() / tablePhases
            var sum = 0.0
            for (j in 0 until taps) {
                // Расстояние от t до входного сэмпла j, во входных сэмплах.
                val x = fraction + half - 1 - j
                row[j] = windowedSinc(x, cutoff, half, windowNorm)
                sum += row[j]
            }
            for (j in 0 until taps) result[p * taps + j] = (row[j] / sum).toFloat()
        }
        return result
    }

    private companion object {
        const val PASSBAND = 0.4375       // доля меньшей частоты: 7 кГц для 16 кГц
        const val STOPBAND = 0.5          // её Найквист: 8 кГц для 16 кГц
        const val ATTENUATION_DB = 80.0
        const val KAISER_BETA = 0.1102 * (ATTENUATION_DB - 8.7)
        const val MAX_EXACT_PHASES = 1024
        const val INTERPOLATED_PHASES = 512
        const val INPUT_BLOCK = 4096
        const val OUTPUT_BLOCK = 4096

        fun windowedSinc(x: Double, cutoff: Double, half: Int, windowNorm: Double): Double {
            val r = x / half
            if (abs(r) > 1.0) return 0.0
            val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
            return sinc * besselI0(KAISER_BETA * sqrt(1 - r * r)) / windowNorm
        }

        /** Модифицированная функция Бесселя первого рода нулевого порядка (ряд Тейлора). */
        fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val quarterSquare = x * x / 4
            var k = 1
            while (term > 1e-12 * sum) {
                term *= quarterSquare / (k.toDouble() * k)
                sum += term
                k++
            }
            return sum
        }

        tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b
    }
}
