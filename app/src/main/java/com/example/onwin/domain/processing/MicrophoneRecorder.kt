package com.example.onwin.domain.processing

import com.example.onwin.domain.model.ErrorCode
import java.io.File
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow

/** Writes a closed canonical WAV to [partFile]. The controller commits or deletes it. */
fun interface MicrophoneRecorder {
    suspend fun record(
        partFile: File,
        stop: Deferred<Unit>,
        onStarted: () -> Unit,
        onDuration: (Long) -> Unit,
        live: Boolean,
    ): Long
}

/** Reads the audio as it is saved, including samples recorded before the speech worker was available. */
fun interface LivePcmSource {
    fun read(part: File, wav: File, committed: Deferred<Boolean>): Flow<FloatArray>
}

class RecordingException(val code: ErrorCode, cause: Throwable? = null) : Exception(code.name, cause)
