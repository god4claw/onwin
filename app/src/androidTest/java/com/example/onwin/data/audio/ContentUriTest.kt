package com.example.onwin.data.audio

import android.content.ContentValues
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Источники `content://`: провайдеры других процессов, MediaStore и ошибки открытия. */
@RunWith(AndroidJUnit4::class)
class ContentUriTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var outputDir: File

    @Before
    fun setUp() {
        outputDir = File(context.cacheDir, "content-uri-test-${UUID.randomUUID()}")
    }

    @After
    fun tearDown() {
        outputDir.deleteRecursively()
    }

    @Test
    fun fileProviderUris_areConverted() = runBlocking {
        for (name in listOf("aac_lc_48k_stereo.m4a", "opus_48k_mono.ogg", "wav_44k_stereo_list.wav")) {
            assertConverted(TestAudioProvider.uri("file", name), name)
        }
    }

    @Test
    fun mediaStoreUri_isConverted() = runBlocking {
        // Настоящий системный провайдер в другом процессе, как файл, выбранный из «Загрузок».
        val name = "mp3_44k_stereo.mp3"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "audio-converter-test-${UUID.randomUUID()}.mp3")
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { output ->
                Fixtures.file(name).inputStream().use { it.copyTo(output) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            assertConverted(uri, name)
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    @Test
    fun pipeUri_failsAsNotSeekable() = runBlocking {
        expectFailure<AudioConversionException.NotSeekable> {
            AudioConverter(context, outputDir).toWav(TestAudioProvider.uri("pipe", "opus_48k_mono.ogg"))
        }
        assertNoLeftovers()
    }

    @Test
    fun missingProviderFile_isSourceUnavailable() = runBlocking {
        expectFailure<AudioConversionException.SourceUnavailable> {
            AudioConverter(context, outputDir).toWav(TestAudioProvider.uri("file", "missing.mp3"))
        }
        assertNoLeftovers()
    }

    @Test
    fun unknownProvider_isSourceUnavailable() = runBlocking {
        expectFailure<AudioConversionException.SourceUnavailable> {
            AudioConverter(context, outputDir).toWav(Uri.parse("content://com.example.onwin.missing/a.mp3"))
        }
        assertNoLeftovers()
    }

    @Test
    fun missingLocalFile_isSourceUnavailable() = runBlocking {
        expectFailure<AudioConversionException.SourceUnavailable> {
            AudioConverter(context, outputDir).toWav(Uri.fromFile(File(context.cacheDir, "missing.mp3")))
        }
        assertNoLeftovers()
    }

    private suspend fun assertConverted(uri: Uri, name: String) {
        val result = AudioConverter(context, outputDir).toWav(uri)
        val sourceDurationMs = containerAudioFormat(Fixtures.file(name)).getLong(MediaFormat.KEY_DURATION) / 1000
        val samples = readCanonical16kMonoWav(result.file)
        val (frequency, _) = spectralPeak(samples, 16_000, from = 4 * 16_000, to = 5 * 16_000)
        reportMetric(TAG, "$uri: duration=${result.durationMs} ms (source $sourceDurationMs ms), peak=$frequency Hz")
        assertEquals("duration of $uri, ms", sourceDurationMs.toDouble(), result.durationMs.toDouble(), 50.0)
        assertEquals("tone of $uri, Hz", 1000.0, frequency, 5.0)
    }

    private fun assertNoLeftovers() = assertEquals(emptyList<String>(), outputDir.list().orEmpty().toList())

    private companion object {
        const val TAG = "ContentUriTest"
    }
}
