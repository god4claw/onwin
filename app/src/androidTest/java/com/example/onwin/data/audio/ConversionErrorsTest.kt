package com.example.onwin.data.audio

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.random.Random

/** Повреждённые и неподходящие входы, ошибки записи: понятное исключение, без падений и остатков файлов. */
@RunWith(AndroidJUnit4::class)
class ConversionErrorsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var workDir: File
    private lateinit var outputDir: File

    @Before
    fun setUp() {
        workDir = File(context.cacheDir, "errors-test-${UUID.randomUUID()}").apply { mkdirs() }
        outputDir = File(workDir, "out")
    }

    @After
    fun tearDown() {
        outputDir.setWritable(true, false)
        workDir.deleteRecursively()
    }

    @Test
    fun emptyFile_isDecodeFailed() = runBlocking {
        expectFailure<AudioConversionException.DecodeFailed> { convert(input("empty.mp3", ByteArray(0))) }
        assertNoLeftovers()
    }

    @Test
    fun textFile_isDecodeFailed() = runBlocking {
        val text = "Это не аудио, а текст.\n".repeat(1_000).toByteArray()
        expectFailure<AudioConversionException.DecodeFailed> { convert(input("notes.mp3", text)) }
        assertNoLeftovers()
    }

    @Test
    fun randomBytes_failWithConversionError() = runBlocking {
        val error = expectFailure<AudioConversionException> { convert(input("random.m4a", Random(1).nextBytes(64 * 1024))) }
        reportMetric(TAG, "random bytes: ${error::class.java.simpleName}")
        assertNoLeftovers()
    }

    @Test
    fun videoOnlyMp4_isNoAudioTrack() = runBlocking {
        expectFailure<AudioConversionException.NoAudioTrack> { convert(Fixtures.file("video_only.mp4")) }
        assertNoLeftovers()
    }

    @Test
    fun truncatedMp4_withoutMoov_isNoAudioTrackOrDecodeFailed() = runBlocking {
        // ffmpeg пишет индекс (moov) в конец файла: без второй половины файл не открывается.
        // Как именно, зависит от MediaExtractor: одни версии не открывают файл, другие (эмулятор API 37)
        // открывают его без дорожек.
        val error = expectFailure<AudioConversionException> { convert(firstHalfOf("aac_lc_48k_stereo.m4a")) }
        assertTrue(
            "$error",
            error is AudioConversionException.NoAudioTrack || error is AudioConversionException.DecodeFailed,
        )
        assertNoLeftovers()
    }

    @Test
    fun truncatedMp3_convertsAvailablePart() = runBlocking {
        val result = convert(firstHalfOf("mp3_44k_stereo.mp3"))
        reportMetric(TAG, "truncated mp3: duration=${result.durationMs} ms")
        assertEquals(5_000.0, result.durationMs.toDouble(), 100.0)
    }

    @Test
    fun truncatedWav_convertsAvailablePart() = runBlocking {
        val result = convert(firstHalfOf("wav_44k_stereo_list.wav"))
        reportMetric(TAG, "truncated wav: duration=${result.durationMs} ms")
        assertEquals(5_000.0, result.durationMs.toDouble(), 50.0)
    }

    @Test
    fun corruptedMiddle_convertsOrFailsCleanly() = runBlocking {
        // Мусор посреди файла: декодер может пережить его или упасть; в обоих случаях — без падения
        // процесса и без остатков. Исход записывается в метрики.
        for (name in listOf("mp3_44k_stereo.mp3", "aac_lc_48k_stereo.m4a", "opus_48k_mono.ogg", "flac_22k_24bit_stereo.flac")) {
            val bytes = Fixtures.file(name).readBytes()
            Random(2).nextBytes(bytes, bytes.size / 2, bytes.size / 2 + 4_096)
            val outcome = try {
                val result = convert(input("corrupted_$name", bytes))
                assertEquals(listOf(result.file.name), outputDir.list()!!.toList())
                result.file.delete()
                "converted, duration=${result.durationMs} ms"
            } catch (e: AudioConversionException) {
                assertNoLeftovers()
                e::class.java.simpleName
            }
            reportMetric(TAG, "corrupted middle of $name: $outcome")
        }
    }

    @Test
    fun outputDirIsAFile_isWriteFailed() = runBlocking {
        outputDir.writeText("not a directory")
        expectFailure<AudioConversionException.WriteFailed> { convert(Fixtures.file("wav_16k_mono.wav")) }
        assertEquals("not a directory", outputDir.readText())
    }

    @Test
    fun readOnlyOutputDir_isWriteFailed() = runBlocking {
        outputDir.mkdirs()
        assumeTrue("cannot make $outputDir read-only", outputDir.setWritable(false, false))
        expectFailure<AudioConversionException.WriteFailed> { convert(Fixtures.file("wav_16k_mono.wav")) }
        assertNoLeftovers()
    }

    private suspend fun convert(file: File): WavResult = AudioConverter(context, outputDir).toWav(Uri.fromFile(file))

    private fun input(name: String, bytes: ByteArray): File = File(workDir, name).apply { writeBytes(bytes) }

    private fun firstHalfOf(name: String): File {
        val bytes = Fixtures.file(name).readBytes()
        return input("half_$name", bytes.copyOf(bytes.size / 2))
    }

    private fun assertNoLeftovers() = assertEquals(emptyList<String>(), outputDir.list().orEmpty().toList())

    private companion object {
        const val TAG = "ConversionErrorsTest"
    }
}
