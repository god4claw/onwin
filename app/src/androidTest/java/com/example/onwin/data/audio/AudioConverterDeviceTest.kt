package com.example.onwin.data.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import kotlin.random.Random

/** Проверки платформы, памяти и скорости на устройстве. */
@RunWith(AndroidJUnit4::class)
class AudioConverterDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun rawDecoder_isAvailable() {
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_RAW)
        reportMetric(TAG, "audio/raw decoder: ${codec.name}")
        codec.release()
    }

    @Test
    fun oneHourInput_keepsMemoryFlat() = runBlocking {
        val dir = File(context.cacheDir, "one-hour-test").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            // Час 8 кГц моно: идёт через декодер audio/raw и повышающий ресемплер.
            val source = File(dir, "one_hour_8k_mono.wav")
            WavWriter(source, sampleRate = 8_000).use { writer ->
                val signal = TestSignal(8_000)
                val second = ShortArray(8_000)
                repeat(3_600) {
                    for (i in second.indices) second[i] = WavWriter.floatToPcm16(signal.next())
                    writer.writeShorts(second)
                }
            }

            val runtime = Runtime.getRuntime()
            System.gc()
            val javaBaseline = runtime.totalMemory() - runtime.freeMemory()
            val nativeBaseline = Debug.getNativeHeapAllocatedSize()
            var javaPeak = javaBaseline
            var nativePeak = nativeBaseline
            val start = System.nanoTime()
            val result = AudioConverter(context, File(dir, "out")).toWav(Uri.fromFile(source)) {
                javaPeak = maxOf(javaPeak, runtime.totalMemory() - runtime.freeMemory())
                nativePeak = maxOf(nativePeak, Debug.getNativeHeapAllocatedSize())
            }
            val wallSeconds = (System.nanoTime() - start) / 1e9
            val javaGrowthMb = (javaPeak - javaBaseline) / MB
            val nativeGrowthMb = (nativePeak - nativeBaseline) / MB
            reportMetric(
                TAG,
                String.format(
                    Locale.ROOT,
                    "1 h input: %.1f s wall, speed %.0fx, java heap +%.1f MB, native heap +%.1f MB",
                    wallSeconds, 3_600 / wallSeconds, javaGrowthMb, nativeGrowthMb,
                ),
            )

            assertEquals(3_600_000.0, result.durationMs.toDouble(), 50.0)
            // Накопление всего сигнала заняло бы ≥ 115 МБ (16 кГц PCM16) — на порядок больше порога.
            assertTrue("java heap +$javaGrowthMb MB", javaGrowthMb < 32)
            assertTrue("native heap +$nativeGrowthMb MB", nativeGrowthMb < 32)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun resamplerSpeed_onDevice() {
        val random = Random(7)
        for (rate in intArrayOf(44_100, 48_000)) {
            val seconds = 60
            val input = FloatArray(rate * seconds) { random.nextFloat() - 0.5f }
            val sink = FloatSink { _, _, _ -> }
            StreamingResampler(rate).apply { process(input, 0, rate * 5, sink) } // прогрев JIT
            val resampler = StreamingResampler(rate)
            val start = System.nanoTime()
            var offset = 0
            while (offset < input.size) {
                val count = minOf(4_096, input.size - offset)
                resampler.process(input, offset, count, sink)
                offset += count
            }
            resampler.flush(sink)
            val speed = seconds / ((System.nanoTime() - start) / 1e9)
            reportMetric(TAG, String.format(Locale.ROOT, "Resampler %d -> 16000 Hz: %.0fx realtime", rate, speed))
            assertTrue("speed $speed", speed > 1)
        }
    }

    private companion object {
        const val TAG = "AudioConverterDeviceTest"
        const val MB = 1024.0 * 1024.0
    }
}
