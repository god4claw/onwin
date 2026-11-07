package com.example.onwin.data.audio

import com.example.onwin.domain.model.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class AudioConversionExceptionTest {

    @Test
    fun conversionErrors_mapToDomainCodes() {
        val cases = listOf(
            AudioConversionException.SourceUnavailable() to ErrorCode.SOURCE_UNAVAILABLE,
            AudioConversionException.NotSeekable() to ErrorCode.SOURCE_UNAVAILABLE,
            AudioConversionException.NoAudioTrack() to ErrorCode.NO_AUDIO_TRACK,
            AudioConversionException.UnsupportedFormat("audio/x-unknown") to ErrorCode.UNSUPPORTED_FORMAT,
            AudioConversionException.DecodeFailed(IllegalStateException("codec")) to ErrorCode.DECODE_FAILED,
            AudioConversionException.TooLong(WavSizeLimitException(10)) to ErrorCode.TOO_LONG,
            AudioConversionException.WriteFailed(IOException("ENOSPC")) to ErrorCode.STORAGE_FULL,
        )
        for ((error, code) in cases) assertEquals(code, error.toErrorCode())
    }

    @Test
    fun writingOutput_returnsBlockResult() {
        assertEquals(42, writingOutput { 42 })
    }

    @Test
    fun writingOutput_mapsIoErrorToWriteFailed() {
        val error = IOException("No space left on device")
        val thrown = assertThrows(AudioConversionException.WriteFailed::class.java) { writingOutput { throw error } }
        assertSame(error, thrown.cause)
    }

    @Test
    fun writingOutput_mapsSizeLimitToTooLong() {
        val error = WavSizeLimitException(10)
        val thrown = assertThrows(AudioConversionException.TooLong::class.java) { writingOutput { throw error } }
        assertSame(error, thrown.cause)
    }

    @Test
    fun writingOutput_passesOtherErrorsThrough() {
        val decodeFailed = AudioConversionException.DecodeFailed(IllegalStateException("codec"))
        assertSame(decodeFailed, assertThrows(AudioConversionException::class.java) { writingOutput { throw decodeFailed } })
        val bug = IllegalStateException("bug")
        assertSame(bug, assertThrows(IllegalStateException::class.java) { writingOutput { throw bug } })
    }
}
