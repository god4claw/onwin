package com.example.onwin.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.onwin.data.database.AppDatabase
import com.example.onwin.data.audio.WavWriter
import com.example.onwin.data.repository.RoomSessionRepository
import com.example.onwin.data.export.TranscriptExporter
import com.example.onwin.data.protocol.ProtocolExporter
import com.example.onwin.data.preferences.WelcomePreferences
import com.example.onwin.domain.model.ErrorCode
import com.example.onwin.domain.model.Session
import com.example.onwin.domain.model.SessionSource
import com.example.onwin.domain.model.SessionStatus
import com.example.onwin.domain.model.SpeakerName
import com.example.onwin.domain.processing.ImportSubmission
import com.example.onwin.domain.processing.ProcessingController
import com.example.onwin.domain.processing.ProtocolService
import com.example.onwin.domain.processing.ProcessingState
import com.example.onwin.domain.repository.TranscriptOnboardingStep
import com.example.onwin.ui.screens.recording.ActiveRecordingRoute
import com.example.onwin.ui.screens.recording.RecordingViewModel
import com.example.speech.api.Segment
import java.io.File
import java.time.Clock
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeakerRenameTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)

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
                protocolService = ProtocolService(null, repository, IdleController, Clock.systemUTC()),
                protocolExporter = ProtocolExporter(context),
                transcriptOnboarding = completedTranscriptOnboarding,
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

    @Test
    fun onboardingSeeksWithoutPlayingAndOpensTheRenameDialog() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val preferenceScope = CoroutineScope(Dispatchers.IO)
        try {
            val preferences = WelcomePreferences(PreferenceDataStoreFactory.create(scope = preferenceScope) {
                File(temp.root, "onboarding.preferences_pb")
            })
            val repository = RoomSessionRepository(db.sessionDao(), temp.root, Clock.systemUTC())
            val session = Session(
                id = "onboarding",
                title = "Встреча",
                source = SessionSource.MIC,
                originalName = "mic.wav",
                sourceUri = null,
                createdAt = 1,
                updatedAt = 1,
                wavFileName = "onboarding.wav",
                durationMs = 4_000,
                status = SessionStatus.DONE,
            )
            WavWriter(repository.wavFile("onboarding.wav")).use { it.writeShorts(ShortArray(4 * 16_000)) }
            runBlocking {
                repository.create(session)
                repository.appendSegment(session.id, 0, Segment(0, 0, 1_000, "Первая реплика"))
                repository.appendSegment(session.id, 1, Segment(1, 2_000, 3_000, "Последняя реплика"))
            }
            val viewModel = RecordingViewModel(
                SavedStateHandle(mapOf("sessionId" to session.id)),
                repository,
                IdleController,
                TranscriptExporter(context),
                protocolService = ProtocolService(null, repository, IdleController, Clock.systemUTC()),
                protocolExporter = ProtocolExporter(context),
                transcriptOnboarding = preferences,
            )
            try {
                compose.setContent { ActiveRecordingRoute(onBack = {}, viewModel = viewModel) }
                compose.waitUntil(10_000) { compose.onAllNodesWithText("1 из 2").fetchSemanticsNodes().isNotEmpty() }
                val target = compose.onNodeWithTag("transcript-onboarding-target")
                assertArrowPointsAtTarget("1 из 2")
                target.performTouchInput { click() }
                compose.waitUntil(5_000) { compose.onAllNodesWithText("2 из 2").fetchSemanticsNodes().isNotEmpty() }
                assertArrowPointsAtTarget("2 из 2")
                // Both the transcript timecode and the paused playhead now show 00:02.
                assertEquals(2, compose.onAllNodesWithText("00:02", useUnmergedTree = true).fetchSemanticsNodes().size)
                compose.onNodeWithContentDescription("Воспроизвести аудио", useUnmergedTree = true).assertIsDisplayed()

                target.performTouchInput { click() }
                compose.onNodeWithContentDescription("Имя спикера").assertTextEquals("Спикер 2")
                compose.waitUntil(5_000) { viewModel.transcriptOnboardingStep.value == TranscriptOnboardingStep.COMPLETE }
            } finally {
                viewModel.viewModelScope.cancel()
            }
        } finally {
            preferenceScope.cancel()
            db.close()
        }
    }

    private fun assertArrowPointsAtTarget(stepLabel: String) {
        val target = compose.onNodeWithTag("transcript-onboarding-target").fetchSemanticsNode()
        val card = compose.onNodeWithText(stepLabel).fetchSemanticsNode()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val x = (target.positionOnScreen.x + target.size.width / 2f).roundToInt()
        val contentTop = card.positionOnScreen.y
        // Sample the card fill and the arrow just past the card's 14 dp bottom padding.
        val bodyY = (contentTop - 2 * density).roundToInt()
        val arrowY = (contentTop + card.size.height + 17 * density).roundToInt()
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        assertEquals("Arrow fill at the center of the highlighted target", screenshot.getPixel(x, bodyY), screenshot.getPixel(x, arrowY))
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
