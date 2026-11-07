package com.example.onwin.data.audio

import android.media.MediaFormat
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.UUID

/** Конвертация реальных файлов разных форматов на устройстве. */
@RunWith(Parameterized::class)
class AudioConverterTest(private val fixture: String) {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var outputDir: File
    private lateinit var source: Uri

    @Before
    fun setUp() {
        outputDir = File(context.cacheDir, "converter-test-${UUID.randomUUID()}")
        source = Uri.fromFile(Fixtures.file(fixture))
    }

    @After
    fun tearDown() {
        outputDir.deleteRecursively()
    }

    @Test
    fun producesCanonical16kMonoWav_withoutLeftovers() = runBlocking {
        val result = convert()
        val samples = readCanonical16kMonoWav(result.file)
        assertEquals(samples.size * 1000L / 16_000, result.durationMs)
        assertEquals(listOf(result.file.name), outputDir.list()!!.toList())
    }

    @Test
    fun keepsDurationAndPitch() = runBlocking {
        val result = convert()
        // Эталон — длительность исходника по контейнеру: в ней уже учтены priming/padding кодека
        // (у HE-AAC от MediaMuxer это +124 мс к номинальным 10 с).
        val container = containerAudioFormat(Fixtures.file(fixture))
        val sourceDurationMs = container.getLong(MediaFormat.KEY_DURATION) / 1000
        assertEquals("duration, ms", sourceDurationMs.toDouble(), result.durationMs.toDouble(), 50.0)

        val samples = readCanonical16kMonoWav(result.file)
        val (frequency, dominance) = spectralPeak(samples, 16_000, from = 4 * 16_000, to = 5 * 16_000)
        reportMetric(
            TAG,
            "$fixture: duration=${result.durationMs} ms (source $sourceDurationMs ms), " +
                "container rate=${container.getInteger(MediaFormat.KEY_SAMPLE_RATE)} Hz, " +
                "decoder rate=${result.source.sampleRate} Hz, peak=$frequency Hz, dominance=$dominance",
        )
        assertEquals("tone frequency, Hz", 1000.0, frequency, 5.0)
        assertTrue("tone dominance $dominance", dominance > 100)
    }

    @Test
    fun reportsThrottledMonotonicProgress() = runBlocking {
        val values = Collections.synchronizedList(mutableListOf<Float>())
        convert { values += it }
        assertTrue("calls: ${values.size}", values.size in 1..50)
        assertEquals(1f, values.last())
        values.zipWithNext().forEach { (previous, next) -> assertTrue("$previous -> $next", next >= previous) }
    }

    @Test
    fun cancellation_deletesPartialOutput() = runBlocking {
        val converter = AudioConverter(context, outputDir)
        var sawPartFile = false
        lateinit var job: Job
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            converter.toWav(source) { progress ->
                if (progress >= 0.2f && job.isActive) {
                    sawPartFile = outputDir.list()!!.any { it.endsWith(".wav.part") }
                    job.cancel()
                }
            }
        }
        job.start()
        job.join()
        assertTrue("job cancelled", job.isCancelled)
        assertTrue("partial file existed before cancel", sawPartFile)
        assertEquals(emptyList<String>(), outputDir.list()!!.toList())
    }

    /** Конвертирует [source] и записывает скорость (audio / wall) в метрики теста. */
    private suspend fun convert(onProgress: (Float) -> Unit = {}): WavResult {
        val start = System.nanoTime()
        val result = AudioConverter(context, outputDir).toWav(source, onProgress)
        val wallMs = (System.nanoTime() - start) / 1e6
        reportMetric(
            TAG,
            String.format(
                Locale.ROOT,
                "%s: speed=%.1fx, wall=%.0f ms, duration=%d ms, source=%s",
                fixture, result.durationMs / wallMs, wallMs, result.durationMs, result.source,
            ),
        )
        return result
    }

    companion object {
        private const val TAG = "AudioConverterTest"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<String> = Fixtures.names
    }
}
