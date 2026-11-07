package com.example.onwin.domain.model

/**
 * Сессия: одна запись и её обработка.
 *
 * @property id UUID.
 * @property title имя выбранного файла без расширения.
 * @property source откуда взялась запись.
 * @property originalName имя исходного файла от провайдера.
 * @property sourceUri URI исходника для повторной конвертации после перезапуска процесса.
 * @property createdAt время создания, мс с эпохи.
 * @property updatedAt время последнего изменения строки, мс с эпохи.
 * @property wavFileName имя WAV в `filesDir/recordings`; заполняется после конвертации.
 * @property durationMs длительность WAV; заполняется вместе с [wavFileName].
 * @property status текущий статус.
 * @property errorCode причина ошибки для [SessionStatus.FAILED].
 * @property errorMessage техническое описание ошибки для логов и отладки.
 */
data class Session(
    val id: String,
    val title: String,
    val source: SessionSource,
    val originalName: String,
    val sourceUri: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val wavFileName: String? = null,
    val durationMs: Long? = null,
    val status: SessionStatus,
    val errorCode: ErrorCode? = null,
    val errorMessage: String? = null,
) {
    /** Запись с микрофона нельзя повторить без сохранённого WAV. */
    val canRetry: Boolean
        get() = status.isRetryable && (source != SessionSource.MIC || wavFileName != null)
}
