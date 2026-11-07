package com.example.onwin.ui.screens.recording

import android.database.sqlite.SQLiteException
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.example.onwin.data.transcription.TranscriptJsonExporter
import com.example.onwin.domain.model.SessionDetail
import com.example.onwin.domain.model.SessionStatus
import com.example.onwin.domain.processing.ProcessingController
import com.example.onwin.domain.processing.RecordingState
import com.example.onwin.domain.repository.SessionRepository
import com.example.onwin.ui.navigation.ActiveRecordingDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
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
 */
@HiltViewModel
class RecordingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SessionRepository,
    private val controller: ProcessingController,
    private val transcriptExporter: TranscriptJsonExporter,
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

    internal fun exportTranscript(destination: Uri) {
        if (!exporting.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            val saved = try {
                val detail = repository.observeSession(sessionId).first()
                if (detail?.session?.status == SessionStatus.DONE) {
                    transcriptExporter.write(destination, detail.segments)
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
