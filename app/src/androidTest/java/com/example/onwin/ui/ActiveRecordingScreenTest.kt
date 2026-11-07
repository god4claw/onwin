package com.example.onwin.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.onwin.R
import com.example.onwin.domain.model.Session
import com.example.onwin.domain.model.SessionDetail
import com.example.onwin.domain.model.SessionSource
import com.example.onwin.domain.model.SessionStatus
import com.example.onwin.domain.model.SpeakerName
import com.example.onwin.domain.model.TranscriptSegment
import com.example.onwin.ui.navigation.NavigationPanelSlot
import com.example.onwin.ui.navigation.NavigationPanelTag
import com.example.onwin.ui.navigation.NavigationPanels
import com.example.onwin.ui.navigation.OnWinNavigationLayout
import com.example.onwin.ui.screens.recording.ActiveRecordingScreen
import com.example.onwin.ui.screens.recording.RecordingPlaybackState
import com.example.onwin.ui.theme.ActiveRecordingTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActiveRecordingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun stopKeepsControlsVisibleWhileProcessing() {
        val status = mutableStateOf(SessionStatus.RECORDING)
        val recording = Session(
            id = "mic",
            title = "Название",
            source = SessionSource.MIC,
            originalName = "mic.wav",
            sourceUri = null,
            createdAt = 1L,
            updatedAt = 1L,
            status = SessionStatus.RECORDING,
        )
        var cancelRequested = false
        compose.setContent {
            val panels = remember { NavigationPanels() }
            val slot = remember(panels) { NavigationPanelSlot(panels, recording.id) }
            ActiveRecordingTheme {
                OnWinNavigationLayout(panels, recording.id) { viewport ->
                    ActiveRecordingScreen(
                        modifier = viewport,
                        panelSlot = slot,
                        detail = SessionDetail(recording.copy(status = status.value), emptyList(), emptyList()),
                        loaded = true,
                        elapsedMs = 4_000L,
                        processing = status.value == SessionStatus.TRANSCRIBING,
                        canStop = status.value == SessionStatus.RECORDING,
                        canCancel = status.value.isActive,
                        cancelling = false,
                        onBack = {},
                        onStop = { status.value = SessionStatus.TRANSCRIBING },
                        onCancel = { cancelRequested = true },
                        onRenameSpeaker = {},
                    )
                }
            }
        }
        val panel = compose.onNodeWithTag(NavigationPanelTag)
        val bottom = panel.getUnclippedBoundsInRoot().bottom
        compose.onNodeWithText("Запись идёт\nОстановите её кнопкой").assertFont(R.font.playfair_display)
        compose.onNodeWithText("Запись…").assertFont(R.font.playfair_display)
        compose.onNodeWithText("00:04").assertFont(R.font.golos_text)
        compose.onNodeWithText("Название").assertFont(R.font.playfair_display)
        compose.onNodeWithContentDescription("Остановить запись").performClick()
        compose.onNodeWithContentDescription("Обработка…").assertIsDisplayed()
        compose.onNodeWithText("Идёт обработка\nДождитесь результата").assertFont(R.font.playfair_display)
        compose.onNodeWithText("Обработка…").assertFont(R.font.playfair_display)
        assertEquals(bottom, panel.getUnclippedBoundsInRoot().bottom)
        compose.onNodeWithContentDescription("Остановить запись").assertDoesNotExist()
        compose.onNodeWithText("Отменить обработку").performClick()
        compose.runOnIdle {
            assertTrue(cancelRequested)
            status.value = SessionStatus.DONE
        }
        compose.onNodeWithText("Отменить обработку").assertDoesNotExist()
        compose.onNodeWithContentDescription("Обработка…").assertDoesNotExist()
        compose.onNodeWithText("Запустите аудио").assertIsDisplayed().assertFont(R.font.playfair_display)
        compose.onNodeWithText("Готово").assertFont(R.font.playfair_display)
        assertEquals(bottom, panel.getUnclippedBoundsInRoot().bottom)
    }

    @Test
    fun transcriptSeekJumpsToSegmentStartOnlyWhenPlaybackReady() {
        val playback = mutableStateOf(RecordingPlaybackState())
        var sought: Long? = null
        val detail = SessionDetail(
            session = Session(
                id = "done",
                title = "Встреча",
                source = SessionSource.MIC,
                originalName = "done.wav",
                sourceUri = null,
                createdAt = 1L,
                updatedAt = 1L,
                status = SessionStatus.DONE,
            ),
            segments = listOf(
                TranscriptSegment(index = 0, speaker = 0, startMs = 9_200, endMs = 16_400, text = "Первая реплика"),
                TranscriptSegment(index = 1, speaker = 1, startMs = 25_000, endMs = 34_800, text = "Вторая реплика"),
            ),
            speakerNames = listOf(SpeakerName(speaker = 0, name = "Анна"), SpeakerName(speaker = 1, name = "Игорь")),
        )
        compose.setContent {
            ActiveRecordingTheme {
                ActiveRecordingScreen(
                    detail = detail,
                    loaded = true,
                    elapsedMs = 60_000,
                    processing = false,
                    canStop = false,
                    canCancel = false,
                    cancelling = false,
                    onBack = {},
                    onStop = {},
                    onCancel = {},
                    onRenameSpeaker = {},
                    playback = playback.value,
                    onTogglePlayback = {},
                    onSeekPlayback = { sought = it },
                )
            }
        }

        compose.onNodeWithText("Вторая реплика").assertFont(R.font.golos_text)
        compose.onNodeWithText("Вторая реплика").performClick()
        compose.runOnIdle { assertNull(sought) }

        compose.runOnIdle { playback.value = RecordingPlaybackState(durationMs = 60_000) }
        compose.onNodeWithText("Вторая реплика").performClick()
        compose.runOnIdle { assertEquals(25_000L, sought) }

        compose.onNodeWithText("00:09").performClick()
        compose.runOnIdle { assertEquals(9_200L, sought) }
    }

    @Test
    fun transcriptOpensAtLastSegmentAndFollowsNewOnes() {
        val segments = mutableStateOf(transcriptSegments(12))
        compose.setContent {
            ActiveRecordingTheme {
                ActiveRecordingScreen(
                    detail = transcriptDetail(segments.value),
                    loaded = true,
                    elapsedMs = 60_000,
                    processing = false,
                    canStop = false,
                    canCancel = false,
                    cancelling = false,
                    onBack = {},
                    onStop = {},
                    onCancel = {},
                    onRenameSpeaker = {},
                    playback = RecordingPlaybackState(durationMs = 60_000),
                    onTogglePlayback = {},
                    onSeekPlayback = {},
                )
            }
        }

        compose.onNodeWithText("Реплика 11").assertIsDisplayed()
        compose.onNodeWithText("Реплика 0").assertDoesNotExist()

        compose.runOnIdle { segments.value = segments.value + transcriptSegment(12) }
        compose.onNodeWithText("Реплика 12").assertIsDisplayed()
    }

    @Test
    fun transcriptKeepsUserScrollWhenNewSegmentArrives() {
        val segments = mutableStateOf(transcriptSegments(12))
        compose.setContent {
            ActiveRecordingTheme {
                ActiveRecordingScreen(
                    detail = transcriptDetail(segments.value),
                    loaded = true,
                    elapsedMs = 60_000,
                    processing = false,
                    canStop = false,
                    canCancel = false,
                    cancelling = false,
                    onBack = {},
                    onStop = {},
                    onCancel = {},
                    onRenameSpeaker = {},
                    playback = RecordingPlaybackState(durationMs = 60_000),
                    onTogglePlayback = {},
                    onSeekPlayback = {},
                )
            }
        }

        repeat(5) {
            compose.onNodeWithTag("transcript-list").performTouchInput { swipeDown() }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Реплика 0").assertIsDisplayed()

        compose.runOnIdle { segments.value = segments.value + transcriptSegment(12) }
        compose.onNodeWithText("Реплика 12").assertDoesNotExist()
        compose.onNodeWithText("Реплика 0").assertIsDisplayed()
    }

    @Test
    fun doneScreenWithJsonButtonFitsWithoutPageScroll() {
        compose.setContent {
            ActiveRecordingTheme {
                Box(Modifier.height(700.dp)) {
                    ActiveRecordingScreen(
                        detail = transcriptDetail(transcriptSegments(6)),
                        loaded = true,
                        elapsedMs = 60_000,
                        processing = false,
                        canStop = false,
                        canCancel = false,
                        cancelling = false,
                        onBack = {},
                        onStop = {},
                        onCancel = {},
                        onRenameSpeaker = {},
                        playback = RecordingPlaybackState(durationMs = 60_000),
                        onTogglePlayback = {},
                        onSeekPlayback = {},
                        onExportJson = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Сохранить JSON").assertIsDisplayed()
        compose.onNodeWithContentDescription("Воспроизвести аудио").assertIsDisplayed()
        // Скроллится только лента реплик: у страницы своего скролла больше нет.
        assertEquals(1, compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().size)
    }

    private fun transcriptSegments(count: Int) = (0 until count).map { transcriptSegment(it) }

    private fun transcriptSegment(index: Int) = TranscriptSegment(
        index = index,
        speaker = index % 2,
        startMs = index * 5_000L,
        endMs = index * 5_000L + 4_000L,
        text = "Реплика $index",
    )

    private fun transcriptDetail(segments: List<TranscriptSegment>) = SessionDetail(
        session = Session(
            id = "done-transcript",
            title = "Встреча",
            source = SessionSource.MIC,
            originalName = "done.wav",
            sourceUri = null,
            createdAt = 1L,
            updatedAt = 1L,
            status = SessionStatus.DONE,
        ),
        segments = segments,
        speakerNames = emptyList(),
    )
}
