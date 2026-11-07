package com.example.onwin.data.audio

import com.example.onwin.domain.model.ErrorCode
import com.example.onwin.domain.processing.MicrophoneRecorder
import com.example.onwin.domain.processing.RecordingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException

/** Nonblocking PCM16 input. All methods are called on the capture coroutine, including close. */
internal interface PcmCapture : Closeable {
    val sampleRate: Int
    val silenced: Boolean
    fun start()
    fun read(samples: ShortArray): Int
}

internal fun interface PcmCaptureFactory {
    fun create(sampleRate: Int): PcmCapture?
}

/** Bounded buffers; cancellation/Stop is observed at least every polling interval. */
internal class WavMicrophoneRecorder(
    private val factory: PcmCaptureFactory,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MicrophoneRecorder {
    override suspend fun record(
        partFile: File,
        stop: Deferred<Unit>,
        onStarted: () -> Unit,
        onDuration: (Long) -> Unit,
        live: Boolean,
    ): Long = withContext(dispatcher) {
        var completed = false
        try {
            val capture = sequenceOf(16_000, 48_000, 44_100).mapNotNull(factory::create).firstOrNull()
                ?: throw RecordingException(ErrorCode.MICROPHONE_UNAVAILABLE)
            val duration = capture.use { input ->
                val parent = checkNotNull(partFile.parentFile)
                if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create recordings directory")
                WavWriter(partFile).use { writer ->
                    val samples = ShortArray(4096)
                    val floats = FloatArray(samples.size)
                    val resampler = if (input.sampleRate == TARGET_SAMPLE_RATE) null else StreamingResampler(input.sampleRate)
                    val sink = FloatSink { data, offset, length -> writer.writeFloats(data, offset, length) }
                    input.start()
                    onStarted()
                    var lastReported = -1L
                    var flushedBytes = 0L
                    while (!stop.isCompleted) {
                        currentCoroutineContext().ensureActive()
                        if (input.silenced) throw RecordingException(ErrorCode.MICROPHONE_UNAVAILABLE)
                        val count = input.read(samples)
                        if (count < 0) throw RecordingException(ErrorCode.MICROPHONE_UNAVAILABLE)
                        if (count == 0) {
                            delay(10)
                            continue
                        }
                        if (resampler == null) writer.writeShorts(samples, length = count) else {
                            repeat(count) { floats[it] = samples[it] / 32768f }
                            resampler.process(floats, 0, count, sink)
                        }
                        if (live && writer.dataBytes - flushedBytes >= LIVE_FLUSH_BYTES) {
                            writer.flushBuffer()
                            flushedBytes = writer.dataBytes
                        }
                        if (writer.durationMs / 1000 != lastReported) {
                            lastReported = writer.durationMs / 1000
                            onDuration(writer.durationMs)
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (input.silenced) throw RecordingException(ErrorCode.MICROPHONE_UNAVAILABLE)
                    resampler?.flush(sink)
                    if (writer.frameCount == 0L) throw RecordingException(ErrorCode.EMPTY_RECORDING)
                    onDuration(writer.durationMs)
                    writer.durationMs
                }
            }
            completed = true
            duration
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            throw RecordingException(ErrorCode.MICROPHONE_PERMISSION_DENIED, e)
        } catch (e: IllegalStateException) {
            throw RecordingException(ErrorCode.MICROPHONE_UNAVAILABLE, e)
        } catch (e: WavSizeLimitException) {
            throw RecordingException(ErrorCode.TOO_LONG, e)
        } catch (e: IOException) {
            throw RecordingException(ErrorCode.STORAGE_FULL, e)
        } finally {
            // A successful result remains a .part until the controller commits it.
            if (!completed) partFile.delete()
        }
    }

    private companion object {
        const val LIVE_FLUSH_BYTES = 3_200 // 100 ms of mono PCM16 at 16 kHz.
    }
}

