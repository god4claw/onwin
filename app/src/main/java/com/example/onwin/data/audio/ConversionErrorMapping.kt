package com.example.onwin.data.audio

import com.example.onwin.domain.model.ErrorCode

/**
 * Код ошибки для исключения этапа конвертации. `when` по sealed-классу без `else`:
 * новый подкласс [AudioConversionException] не скомпилируется, пока ему не назначат код.
 */
internal fun AudioConversionException.toErrorCode(): ErrorCode = when (this) {
    is AudioConversionException.SourceUnavailable -> ErrorCode.SOURCE_UNAVAILABLE
    is AudioConversionException.NotSeekable -> ErrorCode.SOURCE_UNAVAILABLE
    is AudioConversionException.NoAudioTrack -> ErrorCode.NO_AUDIO_TRACK
    is AudioConversionException.UnsupportedFormat -> ErrorCode.UNSUPPORTED_FORMAT
    is AudioConversionException.DecodeFailed -> ErrorCode.DECODE_FAILED
    is AudioConversionException.TooLong -> ErrorCode.TOO_LONG
    is AudioConversionException.WriteFailed -> ErrorCode.STORAGE_FULL
}
