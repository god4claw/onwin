package com.example.onwin.domain.processing

import com.example.onwin.domain.model.ErrorCode
import kotlinx.coroutines.flow.StateFlow

/**
 * Единственный владелец записи и обработки: микрофон → WAV → расшифровка или импорт → конвертация → расшифровка.
 * Одно задание за раз, ожидающие задания — FIFO.
 * Живёт в масштабе приложения и переживает пересоздание Activity.
 *
 * Статусы и сегменты пишутся в БД, живой прогресс — только в [state].
 */
interface ProcessingController {
    /** Начинает запись из действия пользователя; `null`, если контроллер занят. */
    fun startRecording(): String?

    /** Завершает захват и запускает расшифровку; устаревшие и повторные команды игнорируются. */
    fun stopRecording(sessionId: String)

    /** Текущее задание, запись и очередь. */
    val state: StateFlow<ProcessingState>

    /**
     * Создаёт сессию для [source] и ставит её в очередь; foreground-сервис запускается тут же, синхронно.
     * Вызывать только из действия пользователя, пока приложение на экране: из фона Android 12+ запуск запрещает.
     * Если система всё же отказала, сессия станет `FAILED` с [ErrorCode.FOREGROUND_NOT_ALLOWED].
     *
     * @return id новой сессии.
     */
    fun enqueueImport(source: String): String

    /** Запускает импорт из видимого UI; id новой сессии или `null`, если занято или не удалось. */
    suspend fun importIfIdle(source: String): String?

    /** В очереди — убирает из очереди и ставит `CANCELLED`; в работе — отменяет задание. */
    fun cancel(sessionId: String)

    /**
     * Повторяет `FAILED`/`CANCELLED` сессию (MIC — только с сохранённым WAV): сегменты стираются,
     * готовый WAV используется без новой конвертации. Вызывать только из действия пользователя.
     */
    fun retry(sessionId: String)

    /** Удаляет сессию, её сегменты и WAV. Обрабатываемую сейчас сессию не трогает. */
    fun delete(sessionId: String)

    /**
     * Обрывает текущее задание и всю очередь: все становятся `FAILED` с [errorCode].
     * Для случаев, когда foreground больше не удержать (лимит Android 15, отказ системы).
     */
    fun failAll(errorCode: ErrorCode)
}

/**
 * Состояние обработки.
 *
 * @property running текущее задание, `null` — ничего не обрабатывается.
 * @property queuedCount сколько заданий ждёт после текущего.
 */
data class ProcessingState(
    val running: RunningJob? = null,
    val queuedCount: Int = 0,
    val recording: RecordingState? = null,
    val busy: Boolean = false
)

/**
 * Задание в работе.
 *
 * @property sessionId сессия.
 * @property title название сессии (для уведомления).
 * @property stage текущий этап.
 * @property fraction прогресс этапа 0..1; `null` — неизвестен (загрузка моделей, источник без длительности).
 */
data class RunningJob(
    val sessionId: String,
    val title: String,
    val stage: ProcessingStage,
    val fraction: Float?
)

/** Этап обработки для UI. */
enum class ProcessingStage {
    /** Конвертация в WAV. */
    CONVERTING,

    /** Загрузка моделей `:speech`, прогресса нет. */
    LOADING_MODELS,

    /** Определение говорящих. */
    DIARIZATION,

    /** Распознавание речи. */
    RECOGNITION
}

/** Живые данные записи не пишутся в Room на каждом тике. */
data class RecordingState(
    val sessionId: String,
    val title: String,
    val phase: RecordingPhase = RecordingPhase.STARTING,
    val elapsedMs: Long = 0
)
enum class RecordingPhase { STARTING, RECORDING, STOPPING }
