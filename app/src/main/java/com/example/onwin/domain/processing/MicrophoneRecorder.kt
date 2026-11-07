package com.example.onwin.domain.processing

import com.example.onwin.domain.model.ErrorCode
import java.io.File
import kotlinx.coroutines.Deferred

/** Writes a closed canonical WAV to [partFile]. The controller commits or deletes it. */
fun interface MicrophoneRecorder {
    suspend fun record(partFile: File, stop: Deferred<Unit>, onStarted: () -> Unit, onDuration: (Long) -> Unit): Long
}

class RecordingException(val code: ErrorCode, cause: Throwable? = null) : Exception(code.name, cause)
