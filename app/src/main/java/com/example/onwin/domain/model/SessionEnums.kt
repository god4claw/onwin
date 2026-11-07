package com.example.onwin.domain.model

/**
 * Статус обработки сессии. Хранится в БД по имени, поэтому константы можно переупорядочивать,
 * но не переименовывать.
 */
enum class SessionStatus {
    /** Запись с микрофона или её завершение. */
    RECORDING,

    /** Ждёт своей очереди. */
    QUEUED,

    /** Исходный файл конвертируется в WAV. */
    CONVERTING,

    /** Идёт загрузка моделей, диаризация или распознавание. */
    TRANSCRIBING,

    /** Готово, сегменты финальные. */
    DONE,

    /** Ошибка, причина — в [ErrorCode]. Частичные сегменты сохраняются. */
    FAILED,

    /** Отменено пользователем. */
    CANCELLED;

    /** Сессия ждёт обработки или обрабатывается. */
    val isActive: Boolean
        get() = this == RECORDING || this == QUEUED || this == CONVERTING || this == TRANSCRIBING

    /** Сессию можно запустить заново через `retry`. */
    val isRetryable: Boolean
        get() = this == FAILED || this == CANCELLED

    companion object {
        /** Статусы, в которых сессию оставляет оборванный процесс. */
        val UNFINISHED: List<SessionStatus> = entries.filter { it.isActive }
    }
}

/** Откуда взялась запись. */
enum class SessionSource {
    /** Файл, выбранный пользователем. */
    IMPORT,

    /** Запись с микрофона. */
    MIC
}

/** Причина ошибки сессии. Хранится в БД по имени; текст для пользователя подбирает UI. */
enum class ErrorCode {
    MICROPHONE_UNAVAILABLE,
    MICROPHONE_PERMISSION_DENIED,
    EMPTY_RECORDING,

    /** Процесс убит или истёк лимит foreground-сервиса; можно повторить. */
    INTERRUPTED,

    /** Источник не открылся или не поддерживает перемотку. */
    SOURCE_UNAVAILABLE,

    /** В файле нет аудиодорожки. */
    NO_AUDIO_TRACK,

    /** Нет декодера для формата. */
    UNSUPPORTED_FORMAT,

    /** Файл не удалось декодировать. */
    DECODE_FAILED,

    /** Запись не помещается в WAV. */
    TOO_LONG,

    /** Не удалось записать WAV (обычно нет места). */
    STORAGE_FULL,

    /** Система не разрешила запустить foreground-сервис. */
    FOREGROUND_NOT_ALLOWED,

    /** Ошибка распознавания в `:speech`. */
    TRANSCRIPTION_FAILED,

    /** Всё остальное. */
    UNKNOWN
}
