package com.example.onwin.ui.screens.recording

import android.database.sqlite.SQLiteException
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.example.onwin.data.export.TranscriptExportFormat
import com.example.onwin.data.export.TranscriptExporter
import com.example.onwin.domain.model.SessionDetail
import com.example.onwin.domain.model.SessionStatus
import com.example.onwin.domain.processing.ProcessingController
import com.example.onwin.domain.processing.RecordingState
import com.example.onwin.domain.repository.SessionRepository
import com.example.onwin.domain.repository.TranscriptOnboardingRepository
import com.example.onwin.domain.repository.TranscriptOnboardingStep
import com.example.onwin.ui.navigation.ActiveRecordingDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Экран записи: сегменты появляются по мере расшифровки.
 *
 * @param sessionId сессия.
 * @param repository источник сессии и сегментов.
 * @param controller очередь обработки.
 * @param transcriptExporter сохраняет итоговую расшифровку в выбранный документ.
 * @param transcriptOnboarding хранит пройденные шаги подсказок для расшифровки.
 */
@HiltViewModel
class RecordingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SessionRepository,
    private val controller: ProcessingController,
    private val transcriptExporter: TranscriptExporter,
    private val transcriptOnboarding: TranscriptOnboardingRepository,
) : ViewModel() {
    val sessionId: String = savedStateHandle.toRoute<ActiveRecordingDestination>().sessionId

    /** Состояние экрана. */
    val uiState: StateFlow<RecordingUiState> =
        combine(repository.observeSession(sessionId), controller.state) { detail, processing ->
            RecordingUiState(
                loaded = true,
                detail = detail,
                recording = processing.recording?.takeIf { it.sessionId == sessionId },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RecordingUiState())

    private val exporting = MutableStateFlow(false)
    internal val isExporting = exporting.asStateFlow()
    private val exportResult = Channel<Boolean>(Channel.BUFFERED)
    internal val exportResults = exportResult.receiveAsFlow()

    private val onboardingStep = MutableStateFlow<TranscriptOnboardingStep?>(null)
    internal val transcriptOnboardingStep = onboardingStep.asStateFlow()
    private var submittedOnboardingStep: TranscriptOnboardingStep? = null
    private var onboardingUnavailable = false

    init {
        viewModelScope.launch {
            try {
                transcriptOnboarding.progress.collect { step ->
                    if (!onboardingUnavailable) onboardingStep.value = step
                }
            } catch (_: IOException) {
                onboardingUnavailable = true
                onboardingStep.value = null
            }
        }
    }

    internal fun advanceTranscriptOnboarding(step: TranscriptOnboardingStep): Boolean {
        if (onboardingStep.value != step || submittedOnboardingStep == step) return false
        submittedOnboardingStep = step
        viewModelScope.launch {
            try {
                transcriptOnboarding.advance(step)
            } catch (_: IOException) {
                onboardingUnavailable = true
                onboardingStep.value = null
            }
        }
        return true
    }

    internal fun exportTranscript(destination: Uri, format: TranscriptExportFormat) {
        if (!exporting.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            val saved = try {
                val detail = repository.observeSession(sessionId).first()
                if (detail?.session?.status == SessionStatus.DONE) {
                    transcriptExporter.write(destination, format, detail)
                    true
                } else {
                    false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            } finally {
                exporting.value = false
            }
            exportResult.send(saved)
        }
    }

    fun stopRecording() = controller.stopRecording(sessionId)

    internal fun wavFile(name: String): File = repository.wavFile(name)

    internal suspend fun setSpeakerName(speaker: Int, name: String?): Boolean {
        val trimmed = name?.trim()
        if (trimmed != null && trimmed.isEmpty()) return false
        return try {
            repository.setSpeakerName(sessionId, speaker, trimmed)
            true
        } catch (_: SQLiteException) {
            false
        }
    }

    /** Отменяет обработку. */
    fun cancel() = controller.cancel(sessionId)

    /** Повторяет обработку. */
    fun retry() = controller.retry(sessionId)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * @property loaded `false` до первого ответа БД.
 * @property detail сессия с сегментами; `null` — её нет.
 */
data class RecordingUiState(
    val loaded: Boolean = false,
    val detail: SessionDetail? = null,
    val recording: RecordingState? = null,
)
