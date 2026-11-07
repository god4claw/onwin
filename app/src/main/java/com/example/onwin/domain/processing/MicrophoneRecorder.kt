package com.example.onwin.domain.processing

import com.example.onwin.domain.model.ErrorCode
import kotlinx.coroutines.Deferred
import java.io.File

/** Пишет готовый WAV в [partFile] (фиксирует или удаляет его контроллер); возвращает длительность, мс. */
fun interface MicrophoneRecorder {
    suspend fun record(
        partFile: File,
        stop: Deferred<Unit>,
        onStarted: () -> Unit,
        onDuration: (Long) -> Unit
    ): Long
}

/** Ошибка записи; [code] — причина. */
class RecordingException(
    val code: ErrorCode,
    cause: Throwable? = null
) : Exception(code.name, cause)
