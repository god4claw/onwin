package com.example.onwin.data.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Тестовый сигнал: тон 1 кГц (0.3) + шум с огибающей 4 Гц, похожей на слоги речи. */
internal class TestSignal(private val sampleRate: Int, seed: Int = 1) {
    private val random = Random(seed)
    private var index = 0L

    fun next(): Float {
        val time = index++.toDouble() / sampleRate
        val tone = 0.3 * sin(2 * PI * 1000 * time)
        val envelope = 0.5 + 0.5 * sin(2 * PI * 4 * time)
        return (tone + 0.1 * envelope * (random.nextDouble() * 2 - 1)).toFloat()
    }
}

/** Тестовые аудиофайлы: из assets/audio тестового APK или сгенерированные на устройстве. */
internal object Fixtures {
    /** HE-AAC кодируется на устройстве: локальный ffmpeg без libfdk_aac его не умеет. */
    const val HE_AAC = "he_aac_44k_stereo.m4a"

    /** Номинальная длительность сигнала во всех фикстурах, мс. */
    const val DURATION_MS = 10_000L

    val names = listOf(
        "mp3_44k_stereo.mp3",
        "aac_lc_48k_stereo.m4a",
        HE_AAC,
        "opus_48k_mono.ogg",
        "flac_22k_24bit_stereo.flac",
        "wav_44k_stereo_list.wav",
        "wav_16k_mono.wav",
        "wav_48k_24bit_mono.wav",
        "amr_nb_8k_mono.amr",
        "amr_nb_8k_mono.3gp",
        "amr_wb_16k_mono.awb",
        "vorbis_44k_stereo.ogg",
    )

    @Synchronized
    fun file(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = File(instrumentation.targetContext.cacheDir, "audio-fixtures").apply { mkdirs() }
        val file = File(dir, name)
        if (file.exists()) return file
        val temp = File(dir, "$name.tmp")
        if (name == HE_AAC) {
            encodeHeAac(temp, sampleRate = 44_100, channels = 2)
        } else {
            instrumentation.context.assets.open("audio/$name").use { input ->
                temp.outputStream().use { input.copyTo(it) }
            }
        }
        check(temp.renameTo(file)) { "Cannot rename $temp" }
        return file
    }

    /** Кодирует [DURATION_MS] тестового сигнала в HE-AAC (AAC+SBR) .m4a кодером платформы. */
    private fun encodeHeAac(target: File, sampleRate: Int, channels: Int) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectHE)
            setInteger(MediaFormat.KEY_BIT_RATE, 48_000)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val signal = TestSignal(sampleRate)
            val totalFrames = sampleRate * DURATION_MS / 1000
            var queuedFrames = 0L
            var inputDone = false
            var track = -1
            val info = MediaCodec.BufferInfo()
            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buffer.clear()
                        val frames = min(buffer.remaining() / (2L * channels), totalFrames - queuedFrames).toInt()
                        repeat(frames) {
                            val sample = WavWriter.floatToPcm16(signal.next())
                            repeat(channels) { buffer.putShort(sample) }
                        }
                        val timeUs = queuedFrames * 1_000_000 / sampleRate
                        queuedFrames += frames
                        inputDone = queuedFrames >= totalFrames
                        val flags = if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        codec.queueInputBuffer(index, 0, frames * 2 * channels, timeUs, flags)
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (index >= 0) {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0) muxer.writeSampleData(track, codec.getOutputBuffer(index)!!, info)
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            codec.release()
            if (muxerStarted) muxer.stop()
            muxer.release()
        }
    }
}

/** Имя файла с метриками тестов в `files/` приложения. */
internal const val METRICS_FILE = "audio-test-metrics.txt"

/**
 * Пишет измерение в logcat и дописывает его в `files/[METRICS_FILE]` приложения.
 * Некоторые прошивки (Huawei EMUI) не пускают Log.i приложений в logcat; файл тогда читается так:
 * `adb shell run-as com.example.onwin cat files/audio-test-metrics.txt`.
 */
internal fun reportMetric(tag: String, message: String) {
    Log.i(tag, message)
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    synchronized(Fixtures) { File(context.filesDir, METRICS_FILE).appendText("$tag: $message\n") }
}

/**
 * Проверяет, что [block] падает с исключением [T], и возвращает его.
 * Любое другое исключение пробрасывается, успех — ошибка теста.
 */
internal inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw e
    }
    throw AssertionError("Expected ${T::class.java.simpleName}, but the call succeeded")
}

/** Читает [count] сэмплов канонического WAV 16 кГц моно, начиная с сэмпла [from], не загружая файл целиком. */
internal fun readWavWindow(file: File, from: Long, count: Int): ShortArray {
    val bytes = ByteArray(count * 2)
    RandomAccessFile(file, "r").use { raf ->
        raf.seek(44 + from * 2)
        raf.readFully(bytes)
    }
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return ShortArray(count) { buffer.getShort() }
}

/** Формат аудиодорожки [file] по контейнеру. */
internal fun containerAudioFormat(file: File): MediaFormat {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.path)
        return (0 until extractor.trackCount).map(extractor::getTrackFormat)
            .first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
    } finally {
        extractor.release()
    }
}

/** Проверяет канонический заголовок WAV 16 кГц моно PCM16 и возвращает сэмплы. */
internal fun readCanonical16kMonoWav(file: File): ShortArray {
    val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    val dataBytes = file.length().toInt() - 44
    assertEquals("RIFF", bytes.ascii(0))
    assertEquals(36 + dataBytes, bytes.getInt(4))
    assertEquals("WAVE", bytes.ascii(8))
    assertEquals("fmt ", bytes.ascii(12))
    assertEquals(16, bytes.getInt(16))
    assertEquals(1, bytes.getShort(20).toInt())        // PCM
    assertEquals(1, bytes.getShort(22).toInt())        // моно
    assertEquals(16_000, bytes.getInt(24))
    assertEquals(32_000, bytes.getInt(28))
    assertEquals(2, bytes.getShort(32).toInt())
    assertEquals(16, bytes.getShort(34).toInt())
    assertEquals("data", bytes.ascii(36))
    assertEquals(dataBytes, bytes.getInt(40))
    bytes.position(44)
    return ShortArray(dataBytes / 2) { bytes.getShort() }
}

/**
 * Пик спектра в диапазоне [minHz]..[maxHz] с шагом 0.5 Гц (алгоритм Гёрцеля по [from, to)).
 *
 * @return частота пика и отношение его мощности к медиане по диапазону.
 */
internal fun spectralPeak(
    samples: ShortArray,
    rate: Int,
    from: Int,
    to: Int,
    minHz: Double = 900.0,
    maxHz: Double = 1100.0,
): Pair<Double, Double> {
    val powers = generateSequence(minHz) { it + 0.5 }.takeWhile { it <= maxHz }.map { frequency ->
        val coefficient = 2 * cos(2 * PI * frequency / rate)
        var s1 = 0.0
        var s2 = 0.0
        for (i in from until to) {
            val s0 = samples[i] + coefficient * s1 - s2
            s2 = s1
            s1 = s0
        }
        frequency to (s1 * s1 + s2 * s2 - coefficient * s1 * s2)
    }.toList()
    val peak = powers.maxBy { it.second }
    val median = powers.map { it.second }.sorted()[powers.size / 2]
    return peak.first to peak.second / median
}

private fun ByteBuffer.ascii(index: Int): String = String(ByteArray(4) { get(index + it) }, Charsets.US_ASCII)
