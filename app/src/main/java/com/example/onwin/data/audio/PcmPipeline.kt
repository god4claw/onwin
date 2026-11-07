package com.example.onwin.data.audio

import java.nio.ByteBuffer

/** Частота дискретизации, которую ожидает `Transcriber.transcribe`. */
internal const val TARGET_SAMPLE_RATE = 16_000

/**
 * Чистая (без Android) часть конвертации: PCM декодера → моно float → 16 кГц → [WavWriter].
 *
 * Формат задаётся по фактическому выходу декодера ([setFormat]) и может смениться посреди
 * потока: при смене частоты старый ресемплер дописывает свой хвост, и создаётся новый;
 * при смене каналов или кодировки ресемплер сохраняет состояние.
 * Ошибки записи выходят как [AudioConversionException.TooLong] и
 * [AudioConversionException.WriteFailed].
 *
 * @param writer куда пишется результат; закрывает его вызывающий.
 */
internal class PcmPipeline(
    private val writer: WavWriter
) {
    private var format: PcmFormat? = null
    private var resampler: StreamingResampler? = null
    private var mono = FloatArray(0)
    private var sinkNanos = 0L
    private val sink =
        FloatSink { samples, offset, length ->
            val start = System.nanoTime()
            writingOutput { writer.writeFloats(samples, offset, length) }
            sinkNanos += System.nanoTime() - start
        }

    /** Время, проведённое в ресемплере (без записи в файл), нс. */
    var resamplerNanos = 0L
        private set

    /** Описание текущего ресемплера для логов. */
    val resamplerInfo: String? get() = resampler?.toString()

    /** Задаёт формат следующих буферов [write]. */
    fun setFormat(
        sampleRate: Int,
        channelCount: Int,
        encoding: PcmEncoding
    ) {
        format = PcmFormat(encoding, channelCount)
        val current = resampler
        if (current?.inputRate != sampleRate) {
            current?.let(::flush)
            resampler = StreamingResampler(sampleRate, TARGET_SAMPLE_RATE)
        }
    }

    /** Обрабатывает все целые кадры между position и limit буфера [pcm]. */
    fun write(pcm: ByteBuffer) {
        val format = checkNotNull(format) { "Output format is not set" }
        val frames = format.frameCount(pcm.remaining())
        if (frames == 0) return
        if (mono.size < frames) mono = FloatArray(frames)
        format.toMono(pcm, mono)
        val resampler = checkNotNull(resampler)
        timed { resampler.process(mono, 0, frames, sink) }
    }

    /** Дописывает хвост ресемплера. Вызывается один раз, после последнего [write]. */
    fun finish() {
        resampler?.let(::flush)
    }

    private fun flush(resampler: StreamingResampler) = timed { resampler.flush(sink) }

    private inline fun timed(block: () -> Unit) {
        val sinkBefore = sinkNanos
        val start = System.nanoTime()
        block()
        resamplerNanos += System.nanoTime() - start - (sinkNanos - sinkBefore)
    }
}
