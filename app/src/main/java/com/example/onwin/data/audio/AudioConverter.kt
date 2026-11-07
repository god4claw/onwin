package com.example.onwin.data.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID

/**
 * Конвертирует аудио в WAV, который ожидает `Transcriber.transcribe`: 16 кГц, моно,
 * PCM16 little-endian, канонический 44-байтовый заголовок, сразу за ним данные.
 *
 * Любой вход, включая WAV, идёт одним путём: [MediaExtractor] → [MediaCodec] (для WAV —
 * декодер `audio/raw`) → [PcmPipeline]. Частота, каналы и кодировка берутся из выходного
 * формата декодера, а не из контейнера (у HE-AAC они различаются). Обработка потоковая,
 * память не зависит от длины файла. Если декодера `audio/raw` нет, WAV читается
 * [RiffWavReader] и попадает в тот же [PcmPipeline].
 *
 * Источник открывается через [android.content.ContentResolver]. Он должен поддерживать перемотку:
 * файл от FileProvider или MediaStore подходит, pipe от стримингового провайдера — нет
 * ([AudioConversionException.NotSeekable]).
 *
 * Пример:
 * ```
 * val result = converter.toWav(uri) { progress -> updateUi(progress) }
 * transcriber.transcribe(result.file).collect { event -> … }
 * ```
 *
 * @param context для доступа к [android.content.ContentResolver].
 * @param outputDir папка для результатов; создаётся при необходимости.
 * @param dispatcher где выполняется конвертация.
 */
class AudioConverter(
    private val context: Context,
    private val outputDir: File = File(context.filesDir, "recordings"),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    /**
     * Декодирует любой поддерживаемый платформой формат (mp3, m4a/AAC, ogg Opus/Vorbis, FLAC,
     * AMR, WAV, 3gp) в канонический WAV 16 кГц моно PCM16 в [outputDir].
     *
     * Запись идёт в `<uuid>.wav.part`, переименование в `<uuid>.wav` — только при успехе.
     * Отмена корутины быстро останавливает декодирование и удаляет частичный файл.
     *
     * @param source `content://`, `file://` или `android.resource://` URI с поддержкой перемотки.
     * @param onProgress 0f..1f; вызывается из фонового потока, не больше ~50 раз за конвертацию.
     *   Не вызывается, если длительность исходника неизвестна.
     * @throws AudioConversionException если файл не удалось сконвертировать: источник не открылся
     *   ([AudioConversionException.SourceUnavailable], [AudioConversionException.NotSeekable]),
     *   формат не подошёл ([AudioConversionException.NoAudioTrack],
     *   [AudioConversionException.UnsupportedFormat], [AudioConversionException.DecodeFailed])
     *   или не удалось записать результат ([AudioConversionException.TooLong],
     *   [AudioConversionException.WriteFailed]).
     */
    suspend fun toWav(
        source: Uri,
        onProgress: (Float) -> Unit = {}
    ): WavResult = withContext(dispatcher) {
        val id = UUID.randomUUID().toString()
        val partFile = File(outputDir, "$id.wav.part")
        val wavFile = File(outputDir, "$id.wav")
        val startNanos = System.nanoTime()
        try {
            val writer = writingOutput {
                if (!outputDir.isDirectory && !outputDir.mkdirs()) throw IOException("Cannot create $outputDir")
                WavWriter(partFile)
            }
            val pipeline = PcmPipeline(writer)
            val sourceInfo = try {
                convert(source, pipeline, onProgress)
            } catch (e: Throwable) {
                try {
                    writer.close()
                } catch (closeError: IOException) {
                    e.addSuppressed(closeError)
                }
                throw e
            }
            writingOutput {
                writer.close()
                if (!partFile.renameTo(wavFile)) throw IOException("Cannot rename $partFile to $wavFile")
            }
            logResult(wavFile, writer.durationMs, System.nanoTime() - startNanos, pipeline.resamplerNanos)
            WavResult(wavFile, writer.durationMs, sourceInfo)
        } catch (e: Throwable) {
            partFile.delete()
            throw e
        }
    }

    private suspend fun convert(
        source: Uri,
        pipeline: PcmPipeline,
        onProgress: (Float) -> Unit
    ): SourceInfo =
        try {
            decode(source, pipeline, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AudioConversionException) {
            throw e
        } catch (e: Exception) {
            throw AudioConversionException.DecodeFailed(e)
        }

    // Approach informed by ProtocolVoice AudioImporter, Apache-2.0.
    private suspend fun decode(
        source: Uri,
        pipeline: PcmPipeline,
        onProgress: (Float) -> Unit
    ): SourceInfo {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            // MediaExtractor держит свою копию дескриптора, наш можно закрыть сразу.
            openSource(source).use { extractor.setDataSource(it) }
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AudioConversionException.NoAudioTrack()
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = checkNotNull(inputFormat.getString(MediaFormat.KEY_MIME))
            val durationUs = inputFormat.longOrNull(MediaFormat.KEY_DURATION) ?: -1L
            Log.i(
                TAG,
                "Source: mime=$mime, container rate=${inputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE)}, " +
                    "channels=${inputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)}, durationUs=$durationUs"
            )

            codec = createDecoder(mime)
            if (codec == null) {
                if (mime != MediaFormat.MIMETYPE_AUDIO_RAW) throw AudioConversionException.UnsupportedFormat(mime)
                Log.w(TAG, "No audio/raw decoder, falling back to the RIFF parser")
                return decodeRiff(source, mime, pipeline, onProgress)
            }
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            val containerInfo = SourceInfo(
                mime = mime,
                sampleRate = inputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 0,
                channels = inputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 0,
                pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            )
            return runCodec(codec, extractor, containerInfo, pipeline, ProgressThrottle(durationUs, onProgress))
        } finally {
            try {
                codec?.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "MediaCodec.stop failed", e)
            }
            codec?.release()
            extractor.release()
        }
    }

    /**
     * Открывает [source] и проверяет, что по нему можно перематывать: MediaExtractor читает
     * файл вразнобой, а с pipe от стримингового провайдера он не работает.
     */
    private fun openSource(source: Uri): AssetFileDescriptor {
        val descriptor = openingSource { context.contentResolver.openAssetFileDescriptor(source, "r") }
        val seekable = try {
            Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_CUR)
            true
        } catch (_: ErrnoException) {
            false
        }
        if (!seekable) {
            descriptor.close()
            throw AudioConversionException.NotSeekable()
        }
        return descriptor
    }

    /**
     * Переводит ошибки открытия источника (нет файла, нет прав, null)
     * в [AudioConversionException.SourceUnavailable].
     */
    private inline fun <T : Any> openingSource(open: () -> T?): T = try {
        open()
    } catch (e: FileNotFoundException) {
        throw AudioConversionException.SourceUnavailable(e)
    } catch (e: SecurityException) {
        throw AudioConversionException.SourceUnavailable(e)
    } ?: throw AudioConversionException.SourceUnavailable()

    private fun createDecoder(mime: String): MediaCodec? = try {
        MediaCodec.createDecoderByType(mime)
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * Цикл декодирования. Вход подаётся без ожидания; блокирующее ожидание выхода (до
     * [TIMEOUT_US]) — только когда подать нечего, чтобы не простаивать.
     *
     * @param containerInfo формат по контейнеру; из него берётся mime для ошибок.
     */
    private suspend fun runCodec(
        codec: MediaCodec,
        extractor: MediaExtractor,
        containerInfo: SourceInfo,
        pipeline: PcmPipeline,
        progress: ProgressThrottle
    ): SourceInfo {
        val info = MediaCodec.BufferInfo()
        var sourceInfo: SourceInfo? = null
        var inputDone = false
        while (true) {
            currentCoroutineContext().ensureActive()
            var queued = false
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(0)
                if (inputIndex >= 0) {
                    val size = extractor.readSampleData(checkNotNull(codec.getInputBuffer(inputIndex)), 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                    queued = true
                }
            }
            val outputIndex = codec.dequeueOutputBuffer(info, if (queued) 0 else TIMEOUT_US)
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val format = applyFormat(codec.outputFormat, containerInfo.mime, pipeline)
                if (sourceInfo == null) sourceInfo = format
            } else if (outputIndex >= 0) {
                if (sourceInfo == null) {
                    // Формат не пришёл событием — берём его у самого буфера.
                    sourceInfo = applyFormat(codec.getOutputFormat(outputIndex), containerInfo.mime, pipeline)
                }
                if (info.size > 0) {
                    val buffer = checkNotNull(codec.getOutputBuffer(outputIndex))
                    buffer.limit(info.offset + info.size)
                    buffer.position(info.offset)
                    pipeline.write(buffer)
                }
                codec.releaseOutputBuffer(outputIndex, false)
                progress.update(info.presentationTimeUs)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        pipeline.finish()
        progress.complete()
        return sourceInfo
    }

    /** Перенастраивает [pipeline] на фактический выходной формат декодера. */
    private fun applyFormat(
        format: MediaFormat,
        mime: String,
        pipeline: PcmPipeline
    ): SourceInfo {
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val androidEncoding = format.intOrNull(MediaFormat.KEY_PCM_ENCODING) ?: AudioFormat.ENCODING_PCM_16BIT
        val encoding = PcmEncoding.fromAndroid(androidEncoding)
            ?: throw AudioConversionException.UnsupportedFormat(mime, "PCM encoding $androidEncoding")
        if (rate <= 0 || channels <= 0) {
            throw AudioConversionException.UnsupportedFormat(mime, "rate=$rate, channels=$channels")
        }
        pipeline.setFormat(rate, channels, encoding)
        Log.i(TAG, "Decoder output: rate=$rate, channels=$channels, encoding=$encoding; ${pipeline.resamplerInfo}")
        return SourceInfo(mime, rate, channels, androidEncoding)
    }

    /** Запасной путь для WAV без декодера `audio/raw`. */
    private suspend fun decodeRiff(
        source: Uri,
        mime: String,
        pipeline: PcmPipeline,
        onProgress: (Float) -> Unit
    ): SourceInfo {
        // Отдельное открытие: у копий дескриптора общая позиция, а экстрактор свою уже сдвинул.
        val stream = openingSource { context.contentResolver.openInputStream(source) }
        stream.buffered().use { input ->
            val reader = RiffWavReader(input)
            pipeline.setFormat(reader.sampleRate, reader.channelCount, reader.encoding)
            Log.i(
                TAG,
                "RIFF: rate=${reader.sampleRate}, channels=${reader.channelCount}, " +
                    "encoding=${reader.encoding}; ${pipeline.resamplerInfo}"
            )
            val progress = ProgressThrottle(reader.dataBytes, onProgress)
            val buffer = ByteBuffer.allocate(RIFF_BUFFER_BYTES)
            var consumed = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                buffer.clear()
                val count = reader.read(buffer)
                if (count < 0) break
                buffer.flip()
                pipeline.write(buffer)
                consumed += count
                progress.update(consumed)
            }
            pipeline.finish()
            progress.complete()
            return SourceInfo(mime, reader.sampleRate, reader.channelCount, reader.encoding.androidEncoding)
        }
    }

    private fun logResult(
        file: File,
        durationMs: Long,
        wallNanos: Long,
        resamplerNanos: Long
    ) {
        val audioSeconds = durationMs / 1000.0
        Log.i(
            TAG,
            String.format(
                Locale.ROOT,
                "Done: %s, duration=%d ms, wall=%d ms, speed=%.1fx, resampler only=%.1fx",
                file.name,
                durationMs,
                wallNanos / 1_000_000,
                audioSeconds / (wallNanos / 1e9),
                audioSeconds / (resamplerNanos / 1e9)
            )
        )
    }

    /** Прогресс position / total с шагом не меньше [STEP]; при неизвестном total молчит. */
    private class ProgressThrottle(
        private val total: Long,
        private val onProgress: (Float) -> Unit
    ) {
        private var reported = 0f

        fun update(position: Long) {
            if (total <= 0) return
            val value = (position.toDouble() / total).toFloat().coerceIn(0f, 1f)
            if (value - reported >= STEP) {
                reported = value
                onProgress(value)
            }
        }

        fun complete() {
            if (total > 0 && reported < 1f) {
                reported = 1f
                onProgress(1f)
            }
        }

        private companion object {
            const val STEP = 0.021f  // ≤ 47 промежуточных вызовов + финальный
        }
    }

    private companion object {
        const val TAG = "AudioConverter"
        const val TIMEOUT_US = 10_000L
        const val RIFF_BUFFER_BYTES = 64 * 1024

        fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null

        fun MediaFormat.longOrNull(key: String): Long? = if (containsKey(key)) getLong(key) else null
    }
}
