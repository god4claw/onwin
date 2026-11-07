package com.example.onwin.data.audio

import android.media.MediaFormat
import android.net.Uri
import android.os.Debug
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.util.Locale

/**
 * Часовые сжатые файлы: скорость, память, длительность и отсутствие дрейфа к концу записи.
 *
 * Файлы не входят в репозиторий: `bash scripts/push_long_audio.sh` генерирует их и кладёт
 * на устройство в [DEVICE_DIR]. Если файла там нет, тест пропускается.
 *
 * По умолчанию тест исключён из прогона (см. app/build.gradle.kts), включается свойством:
 * `./gradlew :app:connectedDebugAndroidTest -PlongAudioTests=true`.
 */
@RunWith(Parameterized::class)
class LongInputTest(private val name: String) {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "long-input-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun oneHourFile_convertsWithFlatMemoryAndNoDrift() = runBlocking {
        val source = File(dir, name)
        copyFromDevice("$DEVICE_DIR/$name", source)
        assumeTrue("$name is not in $DEVICE_DIR, run scripts/push_long_audio.sh", source.length() > 0)

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
        val sourceDurationMs = containerAudioFormat(source).getLong(MediaFormat.KEY_DURATION) / 1000

        // Тон в предпоследние секунды: если бы время «уплыло», пик сместился бы.
        val endFrom = result.durationMs * 16 - 6 * 16_000
        val (endFrequency, _) = spectralPeak(readWavWindow(result.file, endFrom, 16_000), 16_000, 0, 16_000)

        reportMetric(
            TAG,
            String.format(
                Locale.ROOT,
                "%s: %.1f s wall, speed %.0fx, duration %d ms (source %d ms), tone at end %.1f Hz, " +
                    "java heap +%.1f MB, native heap +%.1f MB, source=%s",
                name, wallSeconds, result.durationMs / 1000.0 / wallSeconds, result.durationMs, sourceDurationMs,
                endFrequency, javaGrowthMb, nativeGrowthMb, result.source,
            ),
        )
        assertEquals("duration, ms", sourceDurationMs.toDouble(), result.durationMs.toDouble(), 50.0)
        assertEquals("tone at the end, Hz", 1000.0, endFrequency, 5.0)
        assertTrue("java heap +$javaGrowthMb MB", javaGrowthMb < 32)
        assertTrue("native heap +$nativeGrowthMb MB", nativeGrowthMb < 32)
    }

    /** Копирует файл из области shell (`/data/local/tmp`) в [target]; если файла нет, [target] будет пустым. */
    private fun copyFromDevice(path: String, target: File) {
        val output = instrumentation.uiAutomation.executeShellCommand("cat $path")
        ParcelFileDescriptor.AutoCloseInputStream(output).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
    }

    companion object {
        /** Куда `scripts/push_long_audio.sh` кладёт часовые файлы. */
        const val DEVICE_DIR = "/data/local/tmp/long-audio"

        private const val TAG = "LongInputTest"
        private const val MB = 1024.0 * 1024.0

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun names(): List<String> = listOf("opus_1h_mono.ogg", "aac_lc_1h_stereo.m4a", "mp3_1h_stereo.mp3")
    }
}
