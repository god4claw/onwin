package com.example.onwin.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.onwin.data.database.AppDatabase
import com.example.onwin.data.repository.RoomSessionRepository
import com.example.onwin.data.export.TranscriptExporter
import com.example.onwin.domain.model.ErrorCode
import com.example.onwin.domain.model.Session
import com.example.onwin.domain.model.SessionSource
import com.example.onwin.domain.model.SessionStatus
import com.example.onwin.domain.processing.ImportSubmission
import com.example.onwin.domain.processing.ProcessingController
import com.example.onwin.domain.processing.ProcessingState
import com.example.onwin.domain.processing.RecordingPhase
import com.example.onwin.domain.processing.RecordingState
import com.example.onwin.ui.screens.recording.ActiveRecordingRoute
import com.example.onwin.ui.screens.recording.RecordingViewModel
import java.time.Clock
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingCancellationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun recordingCancellationRequiresConfirmation() = verifyConfirmation(
        status = SessionStatus.RECORDING,
        cancel = "Отменить запись",
        continueAction = "Продолжить запись",
        message = "Аудио будет удалено. Карточка останется в списке со статусом «Отменено».",
    )

    @Test
    fun processingCancellationRequiresConfirmation() = verifyConfirmation(
        status = SessionStatus.TRANSCRIBING,
        cancel = "Отменить обработку",
        continueAction = "Продолжить обработку",
        message = "Расшифровка остановится, а аудио сохранится.",
    )

    private fun verifyConfirmation(status: SessionStatus, cancel: String, continueAction: String, message: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = RoomSessionRepository(db.sessionDao(), context.cacheDir, Clock.systemUTC())
            val session = Session(
                id = "test-recording",
                title = "Запись",
                source = SessionSource.MIC,
                originalName = "mic.wav",
                sourceUri = null,
                createdAt = 1,
                updatedAt = 1,
                status = status,
            )
            runBlocking { repository.create(session) }
            val controller = CancellationController(
                recording = if (status == SessionStatus.RECORDING) {
                    RecordingState(session.id, session.title, RecordingPhase.RECORDING)
                } else null,
            )
            val viewModel = RecordingViewModel(
                SavedStateHandle(mapOf("sessionId" to session.id)),
                repository,
                controller,
                TranscriptExporter(context),
            )
            try {
                compose.setContent { ActiveRecordingRoute(onBack = {}, viewModel = viewModel) }
                val cancelButton = compose.onNodeWithText(cancel)
                compose.waitUntil(timeoutMillis = 5_000) {
                    compose.onAllNodesWithText(cancel).fetchSemanticsNodes().size == 1
                }
                cancelButton.performClick()
                compose.onNodeWithText("$cancel?").assertIsDisplayed()
                compose.onNodeWithText(message).assertIsDisplayed()
                compose.onNodeWithText(continueAction).performClick()
                compose.onNode(isDialog()).assertDoesNotExist()
                compose.runOnIdle { assertEquals(emptyList<String>(), controller.cancelled) }

                cancelButton.performClick()
                compose.onNode(hasText(cancel) and hasAnyAncestor(isDialog())).performClick()
                compose.onNode(isDialog()).assertDoesNotExist()
                compose.runOnIdle { assertEquals(listOf(session.id), controller.cancelled) }
            } finally {
                viewModel.viewModelScope.cancel()
            }
        } finally {
            db.close()
        }
    }

    private class CancellationController(recording: RecordingState?) : ProcessingController {
        override val state = MutableStateFlow(ProcessingState(recording = recording, busy = true))
        val cancelled = mutableListOf<String>()
        override fun cancel(sessionId: String) { cancelled += sessionId }
        override fun startRecording(liveTranscription: Boolean): String = error("Unexpected recording")
        override fun stopRecording(sessionId: String) = Unit
        override fun enqueueImport(source: String): String = error("Unexpected import")
        override suspend fun importAudio(source: String): ImportSubmission = error("Unexpected import")
        override fun retry(sessionId: String) = Unit
        override fun delete(sessionId: String) = Unit
        override fun failAll(errorCode: ErrorCode) = Unit
    }
}
