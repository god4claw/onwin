package com.example.onwin.ui.screens.recordings

import android.content.ContentResolver
import android.database.sqlite.SQLiteException
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.onwin.domain.model.SessionListItem
import com.example.onwin.domain.model.SessionStatus
import com.example.onwin.domain.processing.ProcessingController
import com.example.onwin.domain.processing.ProcessingState
import com.example.onwin.domain.processing.RecordingPhase
import com.example.onwin.domain.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Экран списка записей: сессии из БД плюс живой прогресс из контроллера.
 *
 * @param repository источник сессий.
 * @param controller очередь обработки.
 */
@HiltViewModel
class RecordingsViewModel @Inject constructor(
    private val repository: SessionRepository,
    private val controller: ProcessingController,
) : ViewModel() {

    private val startingRecording = MutableStateFlow(false)
    private val importing = MutableStateFlow(false)
    private val entryEvents = Channel<RecordingEntryEvent>(Channel.BUFFERED)
    internal val recordingEntryEvents = entryEvents.receiveAsFlow()

    /** Состояние экрана. */
    val uiState: StateFlow<RecordingsUiState> =
        combine(repository.observeSessions(), controller.state, startingRecording, importing, ::RecordingsUiState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RecordingsUiState())

    /** Импорт выбранного файла; запускать, когда экран снова виден после пикера. */
    fun import(uri: Uri) {
        if (importing.value) return
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            entryEvents.trySend(RecordingEntryEvent.ImportFailed)
            return
        }
        importing.value = true
        viewModelScope.launch {
            val submission = try {
                controller.importAudio(uri.toString())
            } finally {
                importing.value = false
            }
            when {
                submission == null -> entryEvents.send(RecordingEntryEvent.ImportFailed)
                submission.openDetails -> entryEvents.send(RecordingEntryEvent.OpenImport(submission.sessionId))
            }
        }
    }

    fun startRecording() {
        if (startingRecording.value) return
        val id = controller.startRecording() ?: return
        startingRecording.value = true
        viewModelScope.launch {
            val ready = try {
                combine(repository.observeSession(id), controller.state) { detail, processing ->
                    val recording = processing.recording
                    when {
                        recording?.sessionId != id -> false
                        detail?.session?.status == SessionStatus.RECORDING &&
                            recording.phase == RecordingPhase.RECORDING -> true
                        else -> null
                    }
                }.filterNotNull().first()
            } catch (_: SQLiteException) {
                false
            } finally {
                startingRecording.value = false
            }
            entryEvents.send(if (ready) RecordingEntryEvent.OpenMic(id) else RecordingEntryEvent.MicFailed)
        }
    }

    internal fun isRecording(id: String): Boolean = controller.state.value.recording?.let {
        it.sessionId == id && it.phase == RecordingPhase.RECORDING
    } == true

    internal fun canOpenImport(): Boolean = !startingRecording.value && controller.state.value.recording == null

    fun stopRecording(sessionId: String) = controller.stopRecording(sessionId)

    /** Отменяет обработку сессии. */
    fun cancel(sessionId: String) = controller.cancel(sessionId)

    /** Повторяет упавшую или отменённую сессию. */
    fun retry(sessionId: String) = controller.retry(sessionId)

    /** Удаляет сессию. */
    fun delete(sessionId: String) = controller.delete(sessionId)

    suspend fun rename(sessionId: String, title: String): Boolean {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return false
        return try {
            repository.rename(sessionId, trimmed)
        } catch (_: SQLiteException) {
            false
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * @property sessions сессии, новые сверху.
 * @property processing текущее задание и очередь.
 */
data class RecordingsUiState(
    val sessions: List<SessionListItem> = emptyList(),
    val processing: ProcessingState = ProcessingState(),
    val startingRecording: Boolean = false,
    val importing: Boolean = false,
) {
    val canImport: Boolean
        get() = !importing
}

internal sealed interface RecordingEntryEvent {
    data class OpenMic(val id: String) : RecordingEntryEvent
    data class OpenImport(val id: String) : RecordingEntryEvent
    data object MicFailed : RecordingEntryEvent
    data object ImportFailed : RecordingEntryEvent
}
