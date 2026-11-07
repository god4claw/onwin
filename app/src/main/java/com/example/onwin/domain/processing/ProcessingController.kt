package com.example.onwin.domain.processing

import com.example.onwin.domain.model.ErrorCode
import kotlinx.coroutines.flow.StateFlow

/**
 * Единственный владелец записи и обработки: микрофон → WAV → расшифровка или импорт → конвертация → расшифровка.
 * Один захват микрофона и один обработчик FIFO. Захват резервирует место при старте.
 * Живёт в масштабе приложения и переживает пересоздание Activity.
 *
 * Статусы и сегменты пишутся в БД, живой прогресс — только в [state].
 */
interface ProcessingController {

    /** Starts from a visible user action; null when capture is already active or unavailable. */
    fun startRecording(): String?

    /** Finalize capture and transcribe; stale/repeated commands are ignored. */
    fun stopRecording(sessionId: String)

    /** Current processing, capture, and queued work. */
    val state: StateFlow<ProcessingState>

    /**
     * Создаёт сессию для [source] и ставит её в очередь. Foreground-сервис запускается тут же, синхронно.
     * Вызывать только из действия пользователя, пока приложение на экране: из фона Android 12+
     * запуск сервиса запрещает. Если система всё же отказала, сессия станет `FAILED` с
     * [ErrorCode.FOREGROUND_NOT_ALLOWED].
     *
     * @return id новой сессии.
     */
    fun enqueueImport(source: String): String

    /** Ставит импорт в очередь из видимого UI. Возвращает результат после создания сессии. */
    suspend fun importAudio(source: String): ImportSubmission?

    /** В очереди — убирает из очереди и ставит `CANCELLED`; в работе — отменяет задание. */
    fun cancel(sessionId: String)

    /**
     * Повторяет `FAILED`/`CANCELLED` сессию (MIC — только с сохранённым WAV): старые сегменты стираются, существующий WAV используется
     * без новой конвертации. Как и [enqueueImport], вызывать только из действия пользователя.
     */
    fun retry(sessionId: String)

    /** Удаляет сессию, её сегменты и WAV. Обрабатываемую сейчас сессию не трогает. */
    fun delete(sessionId: String)

    /**
     * Обрывает текущее задание и всю очередь: все они становятся `FAILED` с [errorCode].
     * Для случаев, когда foreground больше не удержать (лимит времени Android 15, отказ системы).
     */
    fun failAll(errorCode: ErrorCode)
}

data class ImportSubmission(val sessionId: String, val openDetails: Boolean)

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
    val busy: Boolean = false,
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
    val fraction: Float?,
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
    RECOGNITION,
}

/** Live capture data is not written to Room on every tick. */
data class RecordingState(
    val sessionId: String,
    val title: String,
    val phase: RecordingPhase = RecordingPhase.STARTING,
    val elapsedMs: Long = 0,
)
enum class RecordingPhase { STARTING, RECORDING, STOPPING }
