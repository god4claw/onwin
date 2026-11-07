package com.example.onwin.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
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
import com.example.onwin.domain.model.SpeakerName
import com.example.onwin.domain.processing.ImportSubmission
import com.example.onwin.domain.processing.ProcessingController
import com.example.onwin.domain.processing.ProcessingState
import com.example.onwin.ui.screens.recording.ActiveRecordingRoute
import com.example.onwin.ui.screens.recording.RecordingViewModel
import com.example.speech.api.Segment
import java.time.Clock
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeakerRenameTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun speakerChipsCanBeRenamedAndResetOnceLabelsAreFinal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = RoomSessionRepository(db.sessionDao(), context.cacheDir, Clock.systemUTC())
            val session = Session(
                id = "transcribing",
                title = "Встреча",
                source = SessionSource.MIC,
                originalName = "mic.wav",
                sourceUri = null,
                createdAt = 1,
                updatedAt = 1,
                status = SessionStatus.TRANSCRIBING,
            )
            runBlocking {
                repository.create(session)
                repository.appendSegment(session.id, 0, Segment(0, 0, 1000, "Первая реплика"))
                repository.appendSegment(session.id, 1, Segment(0, 1000, 2000, "Вторая реплика"))
            }
            val viewModel = RecordingViewModel(
                SavedStateHandle(mapOf("sessionId" to session.id)),
                repository,
                IdleController,
                TranscriptExporter(context),
            )
            try {
                compose.setContent { ActiveRecordingRoute(onBack = {}, viewModel = viewModel) }
                val field = compose.onNodeWithContentDescription("Имя спикера")

                awaitChips("Спикер 1")
                compose.onAllNodesWithText("Спикер 1")[0].assertIsNotEnabled()
                runBlocking { repository.updateStatus(session.id, SessionStatus.DONE) }

                openSpeaker("Спикер 1")
                compose.onNodeWithText("Переименовать спикера").assertIsDisplayed()
                field.assertTextEquals("Спикер 1")
                compose.onNodeWithText("Сбросить имя").assertDoesNotExist()
                field.performTextReplacement("   ")
                compose.onNodeWithText("Сохранить").assertIsNotEnabled()
                field.performTextReplacement("  Анна  ")
                compose.onNodeWithText("Сохранить").performClick()
                awaitDialogClosed()
                awaitChips("Анна")
                assertEquals(
                    listOf(SpeakerName(0, "Анна")),
                    runBlocking { repository.observeSession(session.id).first()?.speakerNames },
                )

                openSpeaker("Анна")
                field.assertTextEquals("Анна")
                compose.onNodeWithText("Сбросить имя").performClick()
                awaitDialogClosed()
                awaitChips("Спикер 1")
                assertEquals(
                    emptyList<SpeakerName>(),
                    runBlocking { repository.observeSession(session.id).first()?.speakerNames },
                )
            } finally {
                viewModel.viewModelScope.cancel()
            }
        } finally {
            db.close()
        }
    }

    private fun openSpeaker(name: String) {
        awaitChips(name)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(name)[0].fetchSemanticsNode().config
                .contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not()
        }
        compose.onAllNodesWithText(name)[0].performClick()
    }

    private fun awaitChips(name: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(name).fetchSemanticsNodes().size == 2
        }
    }

    private fun awaitDialogClosed() {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Переименовать спикера").fetchSemanticsNodes().isEmpty()
        }
    }

    private object IdleController : ProcessingController {
        override val state = MutableStateFlow(ProcessingState())
        override fun startRecording(liveTranscription: Boolean): String? = null
        override fun stopRecording(sessionId: String) = Unit
        override fun enqueueImport(source: String): String = error("Unexpected import")
        override suspend fun importAudio(source: String): ImportSubmission = error("Unexpected import")
        override fun cancel(sessionId: String) = Unit
        override fun retry(sessionId: String) = Unit
        override fun delete(sessionId: String) = Unit
        override fun failAll(errorCode: ErrorCode) = Unit
    }
}
