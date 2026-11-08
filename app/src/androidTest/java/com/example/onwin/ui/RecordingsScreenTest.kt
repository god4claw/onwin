package com.example.onwin.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.onwin.R
import com.example.onwin.domain.model.Session
import com.example.onwin.domain.model.SessionListItem
import com.example.onwin.domain.model.SessionSource
import com.example.onwin.domain.model.SessionStatus
import com.example.onwin.domain.processing.ProcessingState
import com.example.onwin.domain.processing.RecordingPhase
import com.example.onwin.domain.processing.RecordingState
import com.example.onwin.ui.navigation.NavigationPanelSlot
import com.example.onwin.ui.navigation.NavigationPanelTag
import com.example.onwin.ui.navigation.NavigationPanels
import com.example.onwin.ui.navigation.OnWinNavigationLayout
import com.example.onwin.ui.navigation.PanelPrimaryTag
import com.example.onwin.ui.screens.recordings.RecordingsScreen
import com.example.onwin.ui.screens.recordings.RecordingsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun captureButtonSwitchesBetweenMicrophoneLoadingAndStop() {
        val state = mutableStateOf(RecordingsUiState())
        val liveTranscription = mutableStateOf(true)
        var stopped: String? = null
        compose.setContent {
            RecordingsScreen(
                state = state.value,
                onOpen = {},
                onDelete = {},
                onRename = {},
                onRecord = { state.value = RecordingsUiState(startingRecording = true) },
                onImport = {},
                liveTranscription = liveTranscription.value,
                onLiveTranscriptionChange = { liveTranscription.value = it },
                onStopRecording = { id ->
                    stopped = id
                    state.value = RecordingsUiState(
                        processing = ProcessingState(
                            recording = RecordingState(id, "Название", RecordingPhase.STOPPING),
                            busy = true,
                        )
                    )
                },
            )
        }
        val mode = compose.onNodeWithContentDescription("Расшифровка во время записи")
        mode.assertIsOn().performClick()
        mode.assertIsOff()
        compose.onNodeWithContentDescription("Записать аудио").performClick()
        compose.onNodeWithContentDescription("Подготовка микрофона…").assertIsDisplayed()
        compose.runOnIdle {
            state.value = RecordingsUiState(
                processing = ProcessingState(
                    recording = RecordingState("mic", "Название", RecordingPhase.RECORDING),
                    busy = true,
                )
            )
        }
        mode.assertIsNotEnabled()
        compose.onNodeWithContentDescription("Остановить запись").performClick()
        compose.onNodeWithContentDescription("Сохранение записи…").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("mic", stopped)
            state.value = RecordingsUiState()
        }
        compose.onNodeWithContentDescription("Записать аудио").assertIsDisplayed()
    }

    @Test
    fun importAndMicrophoneControlsAreIndependent() {
        val state = mutableStateOf(RecordingsUiState())
        compose.setContent {
            RecordingsScreen(
                state = state.value,
                onOpen = {},
                onDelete = {},
                onRename = {},
                onRecord = {},
                onImport = { state.value = RecordingsUiState(importing = true) },
                onStopRecording = {},
            )
        }

        val idleHeading = compose.onNodeWithText("Запишите аудио или\nимпортируйте его")
        val activeHeading = compose.onNodeWithText("Остановите запись или\nимпортируйте аудио")
        idleHeading.assertIsDisplayed()
        activeHeading.assertDoesNotExist()

        val importButton = compose.onNodeWithText("Импортировать с устройства")
        importButton.performClick()
        compose.onNodeWithContentDescription("Подготовка файла…").assertIsDisplayed()
        importButton.assertIsNotEnabled()
        compose.onNodeWithContentDescription("Записать аудио").assertIsEnabled()

        compose.runOnIdle { state.value = RecordingsUiState(processing = ProcessingState(busy = true)) }
        importButton.assertIsEnabled()
        compose.onNodeWithContentDescription("Записать аудио").assertIsEnabled()
        compose.onNodeWithContentDescription("Подготовка файла…").assertDoesNotExist()

        compose.runOnIdle {
            state.value = RecordingsUiState(
                processing = ProcessingState(
                    busy = true,
                    recording = RecordingState("mic", "Название", RecordingPhase.RECORDING),
                ),
            )
        }
        activeHeading.assertIsDisplayed()
        idleHeading.assertDoesNotExist()
        importButton.assertIsEnabled()
        importButton.performClick()
        compose.onNodeWithContentDescription("Подготовка файла…").assertIsDisplayed()

        for (phase in listOf(RecordingPhase.STARTING, RecordingPhase.STOPPING)) {
            compose.runOnIdle {
                state.value = RecordingsUiState(
                    processing = ProcessingState(recording = RecordingState("mic", "Название", phase)),
                )
            }
            idleHeading.assertIsDisplayed()
            activeHeading.assertDoesNotExist()
        }
    }

    @Test
    fun threeRecordingsHaveNoToggleAndShowAllCards() {
        showRecordings(count = 3)

        compose.onNodeWithText("Записи").assertFont(R.font.playfair_display)
        compose.onNodeWithText("Запишите аудио или\nимпортируйте его").assertFont(R.font.playfair_display)
        compose.onNodeWithText("Импортировать с устройства").assertFont(R.font.golos_text)
        compose.onNodeWithText("Все записи").assertDoesNotExist()
        compose.onNodeWithText("Запись 3").assertIsDisplayed().assertFont(R.font.playfair_display)
        compose.onNodeWithText("Запись…", useUnmergedTree = true).assertFont(R.font.playfair_display)
    }

    @Test
    fun previewWithFourRecordingsCanExpand() {
        showRecordings(count = 4)

        compose.onNodeWithText("Запись 4").assertDoesNotExist()
        compose.onNodeWithText("Все записи").assertIsDisplayed().performClick()
        compose.onNodeWithText("Запись 4").assertIsDisplayed()
        compose.onNodeWithText("Свернуть записи").assertIsDisplayed()
        compose.onNodeWithText("Запишите аудио или\nимпортируйте его").assertDoesNotExist()
    }

    @Test
    fun transcriptionSwitchFollowsSharedPanelAndStaysTappableOverExpandedList() {
        val state = RecordingsUiState(sessions = recordingItems(12).map {
            it.copy(session = it.session.copy(status = SessionStatus.DONE))
        })
        val checked = mutableStateOf(true)
        var opened: String? = null
        compose.setContent {
            val panels = remember { NavigationPanels() }
            val slot = remember(panels) { NavigationPanelSlot(panels, "recordings") }
            OnWinNavigationLayout(panels, "recordings") { modifier ->
                RecordingsScreen(
                    modifier = modifier,
                    panelSlot = slot,
                    state = state,
                    onOpen = { opened = it },
                    onDelete = {},
                    onRename = {},
                    onRecord = {},
                    onImport = {},
                    onStopRecording = {},
                    liveTranscription = checked.value,
                    onLiveTranscriptionChange = { checked.value = it },
                )
            }
        }

        val label = "Расшифровка во время записи"
        val labelText = compose.onNodeWithText(label)
        val mode = compose.onNodeWithContentDescription(label)
        val panel = compose.onNodeWithTag(NavigationPanelTag)
        labelText.assertIsDisplayed()
        val initialSwitch = mode.getUnclippedBoundsInRoot()
        val initialPanel = panel.getUnclippedBoundsInRoot()
        val gap = initialPanel.top - initialSwitch.bottom
        assertTrue(gap in 4.dp..24.dp)
        assertTrue(initialPanel.right - initialSwitch.right in 12.dp..40.dp)

        compose.onNodeWithText("Все записи").performTouchInput { click() }
        mode.assertIsDisplayed()
        labelText.assertDoesNotExist()
        val expandedSwitch = mode.getUnclippedBoundsInRoot()
        val expandedPanel = panel.getUnclippedBoundsInRoot()
        val microphone = compose.onNodeWithTag(PanelPrimaryTag).getUnclippedBoundsInRoot()
        assertEquals(16f, (microphone.top - expandedPanel.top).value, 1f)
        assertTrue(expandedSwitch.top > initialSwitch.top)
        assertEquals(gap.value, (expandedPanel.top - expandedSwitch.bottom).value, 1f)
        assertEquals(initialSwitch.right.value, expandedSwitch.right.value, 1f)
        assertTrue(compose.onNode(hasScrollAction()).getUnclippedBoundsInRoot().bottom > expandedSwitch.top)
        mode.assertIsOn().performTouchInput { click() }
        mode.assertIsOff()
        compose.runOnIdle { assertEquals(null, opened) }
        compose.onNodeWithText("Свернуть записи").performTouchInput { click() }
        labelText.assertIsDisplayed()
        assertEquals(initialSwitch.top.value, mode.getUnclippedBoundsInRoot().top.value, 1f)
    }

    @Test
    fun expandedListScrollsAndCollapsingReturnsToFirstRecording() {
        showRecordings(count = 12)

        compose.onNodeWithText("Запись 4").assertDoesNotExist()
        compose.onNodeWithText("Все записи").performClick()
        compose.onNode(hasScrollAction()).performScrollToIndex(11)
        compose.onNodeWithText("Запись 12").assertIsDisplayed()
        compose.onNodeWithText("Свернуть записи").assertIsDisplayed().performClick()
        compose.onNodeWithText("Запись 1").assertIsDisplayed()
        compose.onNodeWithText("Запись 4").assertDoesNotExist()
        compose.onNodeWithText("Запишите аудио или\nимпортируйте его").assertIsDisplayed()
    }

    @Test
    fun activeRecordingCannotBeDeletedButCanBeRenamed() {
        var renamedId: String? = null
        showRecordings(count = 2, onRename = { renamedId = it })

        compose.onNodeWithText("Все записи").assertDoesNotExist()
        compose.onNodeWithContentDescription("Удалить запись «Запись 1»").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Переименовать запись «Запись 1»").performClick()
        compose.runOnIdle { assertEquals("1", renamedId) }
    }

    @Test
    fun cardButtonsActOnSelectedRecordingWithoutOpeningIt() {
        val actions = mutableListOf<String>()
        showRecordings(
            count = 2,
            onOpen = { actions += "open:$it" },
            onDelete = { actions += "delete:$it" },
            onRename = { actions += "rename:$it" },
        )

        compose.onNodeWithContentDescription("Меню записи «Запись 2»").assertDoesNotExist()
        compose.onNodeWithContentDescription("Переименовать запись «Запись 2»")
            .performTouchInput { click() }
        compose.onNodeWithContentDescription("Удалить запись «Запись 2»")
            .performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("rename:2", "delete:2"), actions) }
    }

    @Test
    fun shrinkingToThreeRecordingsCollapsesListAndHidesToggle() {
        val sessions = mutableStateOf(recordingItems(4))
        compose.setContent {
            RecordingsScreen(
                state = RecordingsUiState(sessions = sessions.value),
                onOpen = {},
                onDelete = {},
                onRename = {},
                onRecord = {},
                onImport = {},
                onStopRecording = {},
            )
        }

        compose.onNodeWithText("Все записи").performClick()
        compose.onNodeWithText("Свернуть записи").assertIsDisplayed()

        compose.runOnIdle { sessions.value = sessions.value.take(3) }
        compose.onNodeWithText("Все записи").assertDoesNotExist()
        compose.onNodeWithText("Свернуть записи").assertDoesNotExist()
        compose.onNodeWithText("Запись 3").assertIsDisplayed()
    }

    private fun showRecordings(
        count: Int,
        onOpen: (String) -> Unit = {},
        onDelete: (String) -> Unit = {},
        onRename: (String) -> Unit = {},
    ) {
        compose.setContent {
            RecordingsScreen(
                state = RecordingsUiState(sessions = recordingItems(count)),
                onOpen = onOpen,
                onDelete = onDelete,
                onRename = onRename,
                onRecord = {},
                onImport = {},
                onStopRecording = {},
            )
        }
    }

    private fun recordingItems(count: Int) = (1..count).map { index ->
        SessionListItem(
            session = Session(
                id = index.toString(),
                title = "Запись $index",
                source = SessionSource.MIC,
                originalName = "",
                sourceUri = null,
                createdAt = 1,
                updatedAt = 1,
                durationMs = 155_000,
                status = if (index == 1) SessionStatus.RECORDING else SessionStatus.DONE,
            ),
            segmentCount = 0,
            speakerCount = 0,
        )
    }
}
