package com.example.onwin.domain.processing

import com.example.onwin.domain.model.SessionStatus
import com.example.onwin.domain.repository.SessionRepository
import com.example.speech.api.ProtocolAnalyzer
import com.example.speech.api.Segment
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Опциональный протокол по готовой расшифровке.
 *
 * Запускается только по действию пользователя, не входит в очередь ASR и не
 * мешает ей: если очередь занята, генерация отклоняется. Модель загружается
 * на время запроса и освобождается в `finally`.
 */
class ProtocolService(
    private val analyzer: ProtocolAnalyzer?,
    private val repository: SessionRepository,
    private val controller: ProcessingController,
    private val clock: Clock,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(ProtocolUiState(available = analyzer != null))
    val state: StateFlow<ProtocolUiState> = _state.asStateFlow()

    private var job: Job? = null

    /** Запускает генерацию протокола; повторный вызов во время работы игнорируется. */
    fun generate(sessionId: String) {
        val analyzer = analyzer ?: run {
            _state.update { it.copy(error = "Локальная LLM не установлена на устройстве.") }
            return
        }
        if (job?.isCompleted == false) return
        if (controller.state.value.busy) {
            _state.update {
                it.copy(sessionId = sessionId, error = "Дождитесь окончания обработки записи.")
            }
            return
        }
        job = scope.launch {
            _state.value = ProtocolUiState(
                available = true,
                running = true,
                sessionId = sessionId,
                error = null,
            )
            try {
                val started = controller.withIdleModels {
                    val detail = repository.observeSession(sessionId).first()
                    if (detail == null || detail.session.status != SessionStatus.DONE) {
                        _state.update { it.copy(error = "Расшифровка ещё не готова.") }
                        return@withIdleModels
                    }
                    try {
                        analyzer.load()
                        val protocol = analyzer.analyzeProtocol(
                            segments = detail.segments.map {
                                Segment(it.speaker, it.startMs, it.endMs, it.text)
                            },
                            speakerNames = detail.speakerNames.associate { it.speaker to it.name },
                        )
                        if (protocol == null) {
                            _state.update { it.copy(error = "Не удалось составить протокол: модель вернула пустой ответ.") }
                        } else {
                            repository.saveProtocol(sessionId, protocol)
                            _state.update { it.copy(error = null, generatedAtMs = clock.millis()) }
                        }
                    } finally {
                        analyzer.release()
                    }
                }
                if (!started) {
                    _state.update { it.copy(error = "Дождитесь окончания обработки записи.") }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Не удалось составить протокол.") }
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }

    /** Отменяет текущую генерацию; модель освободится в `finally` корутины. */
    fun cancel() {
        job?.cancel()
    }
}

/**
 * @property available есть ли на устройстве модель, с которой можно строить протокол.
 * @property running идёт генерация.
 * @property sessionId сессия, для которой запускали протокол.
 * @property error текст ошибки для UI; `null` — ошибки нет.
 * @property generatedAtMs время последнего успешного протокола.
 */
data class ProtocolUiState(
    val available: Boolean = false,
    val running: Boolean = false,
    val sessionId: String? = null,
    val error: String? = null,
    val generatedAtMs: Long? = null,
)
