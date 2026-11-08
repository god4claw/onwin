package com.example.onwin.ui

import android.content.res.Resources
import androidx.annotation.StringRes
import com.example.onwin.R
import com.example.onwin.domain.model.ErrorCode
import com.example.onwin.domain.model.SessionStatus
import com.example.onwin.domain.processing.ProcessingStage
import com.example.onwin.domain.processing.RunningJob
import java.util.Locale
import kotlin.math.roundToInt

/** Сколько нумерованных шагов у обработки: конвертация, диаризация, распознавание. */
const val STEP_COUNT = 3

/**
 * Номер шага этапа (1..[STEP_COUNT]). `null` — этап вне нумерации: загрузка
 * моделей без прогресса и анализ диалога при наличии LLM.
 */
val ProcessingStage.step: Int?
    get() = when (this) {
        ProcessingStage.CONVERTING -> 1
        ProcessingStage.LOADING_MODELS -> null
        ProcessingStage.DIARIZATION -> 2
        ProcessingStage.RECOGNITION -> 3
        ProcessingStage.CORRECTING -> null
    }

/** Время `00:12` или `1:02:03`. */
fun formatClock(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0) / 1000
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

/** «Шаг 2 из 3 · Определение говорящих · 40%»; процент — только этого этапа. */
fun stageText(resources: Resources, job: RunningJob): String {
    val name = resources.getString(job.stage.nameRes)
    val step = job.stage.step ?: return name
    val fraction = job.fraction ?: return resources.getString(R.string.stage_step, step, STEP_COUNT, name)
    return resources.getString(R.string.stage_step_percent, step, STEP_COUNT, name, (fraction * 100).roundToInt())
}

/** Название этапа. */
@get:StringRes
val ProcessingStage.nameRes: Int
    get() = when (this) {
        ProcessingStage.CONVERTING -> R.string.stage_converting
        ProcessingStage.LOADING_MODELS -> R.string.stage_loading_models
        ProcessingStage.DIARIZATION -> R.string.stage_diarization
        ProcessingStage.RECOGNITION -> R.string.stage_recognition
        ProcessingStage.CORRECTING -> R.string.stage_correcting
    }

/** Подпись статуса. */
@get:StringRes
val SessionStatus.labelRes: Int
    get() = when (this) {
        SessionStatus.RECORDING -> R.string.status_recording
        SessionStatus.QUEUED -> R.string.status_queued
        SessionStatus.CONVERTING -> R.string.status_converting
        SessionStatus.TRANSCRIBING -> R.string.status_transcribing
        SessionStatus.CORRECTING -> R.string.status_correcting
        SessionStatus.DONE -> R.string.status_done
        SessionStatus.FAILED -> R.string.status_failed
        SessionStatus.CANCELLED -> R.string.status_cancelled
    }

/** Понятное пользователю описание ошибки. */
@get:StringRes
val ErrorCode.messageRes: Int
    get() = when (this) {
        ErrorCode.MICROPHONE_UNAVAILABLE -> R.string.error_microphone_unavailable
        ErrorCode.MICROPHONE_PERMISSION_DENIED -> R.string.error_microphone_permission
        ErrorCode.EMPTY_RECORDING -> R.string.error_empty_recording
        ErrorCode.INTERRUPTED -> R.string.error_interrupted
        ErrorCode.SOURCE_UNAVAILABLE -> R.string.error_source_unavailable
        ErrorCode.NO_AUDIO_TRACK -> R.string.error_no_audio_track
        ErrorCode.UNSUPPORTED_FORMAT -> R.string.error_unsupported_format
        ErrorCode.DECODE_FAILED -> R.string.error_decode_failed
        ErrorCode.TOO_LONG -> R.string.error_too_long
        ErrorCode.STORAGE_FULL -> R.string.error_storage_full
        ErrorCode.FOREGROUND_NOT_ALLOWED -> R.string.error_foreground_not_allowed
        ErrorCode.TRANSCRIPTION_FAILED -> R.string.error_transcription_failed
        ErrorCode.UNKNOWN -> R.string.error_unknown
    }
