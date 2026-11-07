package com.example.onwin.data.audio

import java.io.IOException

/**
 * Ошибка конвертации аудио в WAV ([AudioConverter.toWav]).
 * Отмена корутины этим исключением не оборачивается.
 */
sealed class AudioConversionException(message: String, cause: Throwable? = null) : IOException(message, cause) {

    /** Источник не открылся: файла нет, нет прав на URI или провайдер ничего не вернул. */
    class SourceUnavailable(cause: Throwable? = null) :
        AudioConversionException("Cannot open audio source" + cause?.let { ": $it" }.orEmpty(), cause)

    /**
     * Источник не поддерживает перемотку (pipe или сокет от стримингового `content://` провайдера),
     * а [android.media.MediaExtractor] читает файл вразнобой.
     */
    class NotSeekable : AudioConversionException("Audio source is not seekable (streamed content:// provider)")

    /** В файле нет аудиодорожки. */
    class NoAudioTrack : AudioConversionException("No audio track found")

    /**
     * Нет декодера для формата или декодер отдаёт неподдерживаемую кодировку PCM.
     *
     * @property mime MIME-тип дорожки.
     */
    class UnsupportedFormat(val mime: String, detail: String? = null) :
        AudioConversionException("Unsupported audio format: $mime" + detail?.let { " ($it)" }.orEmpty())

    /** Файл не удалось прочитать или декодировать. */
    class DecodeFailed(cause: Throwable) : AudioConversionException("Audio decoding failed: $cause", cause)

    /** Результат не помещается в WAV (~18,6 ч для 16 кГц моно, см. [WavWriter.MAX_DATA_BYTES]). */
    class TooLong(cause: WavSizeLimitException) :
        AudioConversionException("Audio is too long for a WAV file", cause)

    /** Не удалось записать результат: нет места, нет прав на папку и т. п. */
    class WriteFailed(cause: IOException) : AudioConversionException("Cannot write converted audio: $cause", cause)
}

/**
 * Выполняет запись результата и переводит ошибки вывода в [AudioConversionException.TooLong]
 * и [AudioConversionException.WriteFailed], чтобы они не выглядели как ошибки декодирования.
 */
internal inline fun <T> writingOutput(block: () -> T): T = try {
    block()
} catch (e: AudioConversionException) {
    throw e
} catch (e: WavSizeLimitException) {
    throw AudioConversionException.TooLong(e)
} catch (e: IOException) {
    throw AudioConversionException.WriteFailed(e)
}
