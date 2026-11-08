package com.example.onwin.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.onwin.R
import com.example.onwin.domain.model.Session
import com.example.onwin.domain.model.SessionDetail
import com.example.onwin.domain.model.SessionSource
import com.example.onwin.domain.model.SessionStatus
import com.example.onwin.domain.model.SpeakerName
import com.example.onwin.domain.model.TranscriptSegment
import com.example.onwin.domain.processing.ProtocolUiState
import com.example.onwin.domain.repository.TranscriptOnboardingStep
import com.example.onwin.domain.repository.TranscriptOnboardingStep.COMPLETE
import com.example.onwin.domain.repository.TranscriptOnboardingStep.SPEAKER
import com.example.onwin.domain.repository.TranscriptOnboardingStep.TEXT
import com.example.onwin.ui.navigation.NavigationPanelSlot
import com.example.onwin.ui.navigation.NavigationPanelTag
import com.example.onwin.ui.navigation.PanelPrimaryTag
import com.example.onwin.ui.navigation.NavigationPanels
import com.example.onwin.ui.navigation.OnWinNavigationLayout
import com.example.onwin.ui.screens.recording.ActiveRecordingScreen
import com.example.onwin.ui.screens.recording.RecordingPlaybackState
import com.example.onwin.ui.theme.ActiveRecordingTheme
import com.example.speech.api.ProtocolAction
import com.example.speech.api.ProtocolNotes
import java.util.Locale
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
    fun transcriptSeekAndEditRemainSeparateInACompactRow() {
        val playback = mutableStateOf(RecordingPlaybackState())
        val editingAvailable = mutableStateOf(false)
        var sought: Long? = null
        var edited: Int? = null
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
                    onEditSegment = if (editingAvailable.value) { index -> edited = index } else null,
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

        val speaker = compose.onNodeWithText("Анна")
        val timecode = compose.onNodeWithText("00:09")
        val text = compose.onNodeWithText("Первая реплика")
        val originalGap = text.getUnclippedBoundsInRoot().top - speaker.getUnclippedBoundsInRoot().bottom

        compose.runOnIdle { editingAvailable.value = true }
        val edit = compose.onNodeWithContentDescription("Изменить реплику в 00:09")
        val gap = text.getUnclippedBoundsInRoot().top - speaker.getUnclippedBoundsInRoot().bottom
        assertEquals(originalGap.value, gap.value, 1f)
        assertTrue((edit.getUnclippedBoundsInRoot().left - timecode.getUnclippedBoundsInRoot().right) in 5.dp..7.dp)

        timecode.performTouchInput { click() }
        compose.runOnIdle { assertEquals(9_200L, sought) }
        edit.performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(0, edited)
            assertEquals(9_200L, sought)
        }
    }

    @Test
    fun playbackBringsCurrentReplyIntoViewAndClearsHighlightDuringSilenceOrPause() {
        val playback = mutableStateOf(RecordingPlaybackState(durationMs = 60_000L))
        val position = mutableLongStateOf(6_000L)
        val detail = transcriptDetail(transcriptSegments(12))
        compose.setContent {
            ActiveRecordingTheme {
                ActiveRecordingScreen(
                    detail = detail,
                    loaded = true,
                    elapsedMs = 60_000L,
                    processing = false,
                    canStop = false,
                    canCancel = false,
                    cancelling = false,
                    onBack = {},
                    onStop = {},
                    onCancel = {},
                    onRenameSpeaker = {},
                    playback = playback.value,
                    playbackPosition = position,
                )
            }
        }

        val label = compose.onNodeWithText("Сейчас звучит")
        label.assertDoesNotExist()
        compose.onNodeWithText("Реплика 11").assertIsDisplayed()

        fun assertHighlightOnReply(index: Int) {
            label.assertIsDisplayed()
            val reply = compose.onNodeWithText("Реплика $index").assertIsDisplayed()
            val gap = reply.getUnclippedBoundsInRoot().top - label.getUnclippedBoundsInRoot().bottom
            assertTrue(gap in 0.dp..16.dp)
        }

        compose.runOnIdle { playback.value = playback.value.copy(playing = true) }
        assertHighlightOnReply(1)
        compose.runOnIdle { position.longValue = 9_000L }
        label.assertDoesNotExist()
        compose.runOnIdle { position.longValue = 10_000L }
        assertHighlightOnReply(2)
        compose.runOnIdle { position.longValue = 55_500L }
        assertHighlightOnReply(11)
        compose.runOnIdle { playback.value = playback.value.copy(playing = false) }
        label.assertDoesNotExist()
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
    fun doneScreenFillsTranscriptAndPlacesExportBesideCenteredPlayback() {
        val detail = transcriptDetail(transcriptSegments(6))
        compose.setContent {
            val panels = remember { NavigationPanels() }
            val slot = remember(panels) { NavigationPanelSlot(panels, detail.session.id) }
            ActiveRecordingTheme {
                Box(Modifier.height(700.dp)) {
                    OnWinNavigationLayout(panels, detail.session.id) { viewport ->
                        ActiveRecordingScreen(
                            modifier = viewport,
                            panelSlot = slot,
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
                            playback = RecordingPlaybackState(durationMs = 60_000),
                            onTogglePlayback = {},
                            onSeekPlayback = {},
                            onExport = {},
                        )
                    }
                }
            }
        }

        val panel = compose.onNodeWithTag(NavigationPanelTag).getUnclippedBoundsInRoot()
        val play = compose.onNodeWithTag(PanelPrimaryTag).assertIsDisplayed().getUnclippedBoundsInRoot()
        val export = compose.onNodeWithContentDescription("Экспорт в документ")
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val transcript = compose.onNodeWithTag("transcript-list").getUnclippedBoundsInRoot()
        val transcriptPanel = compose.onNodeWithTag("transcript-panel").getUnclippedBoundsInRoot()
        assertEquals(play.top, export.top)
        assertEquals(play.bottom, export.bottom)
        assertEquals(export.right - export.left, export.bottom - export.top)
        assertEquals(12f, (export.left - play.right).value, 1f)
        assertEquals(((panel.left + panel.right) / 2).value, ((play.left + play.right) / 2).value, 1f)
        assertEquals(32f, (panel.top - transcript.bottom).value, 1f)
        val status = compose.onNodeWithText("Готово").getUnclippedBoundsInRoot()
        assertTrue(transcriptPanel.top >= status.bottom)
        // Скроллится только лента реплик: у страницы своего скролла больше нет.
        assertEquals(1, compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().size)
        val replies = compose.onNodeWithTag("transcript-list")
        for (index in listOf(0, 5)) {
            replies.performScrollToIndex(index)
            compose.onNodeWithText("Реплика $index").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun longTitleSharesRowWithDurationAndProtocolControlsKeepTheirHeight() {
        val title = "Длинное название записи ".repeat(6).trimEnd()
        val summary = "Обсудили план встречи."
        val initial = transcriptDetail(listOf(transcriptSegment(0)))
        val detail = mutableStateOf(initial.copy(session = initial.session.copy(title = title), summary = summary))
        val generating = mutableStateOf(false)
        compose.setContent {
            ActiveRecordingTheme {
                ActiveRecordingScreen(
                    detail = detail.value,
                    loaded = true,
                    elapsedMs = 4_321_000L,
                    processing = false,
                    canStop = false,
                    canCancel = false,
                    cancelling = false,
                    onBack = {},
                    onStop = {},
                    onCancel = {},
                    onRenameSpeaker = {},
                    onApplySuggestedName = { _, _ -> },
                    protocolState = ProtocolUiState(available = true, running = generating.value),
                    onGenerateProtocol = { generating.value = true },
                    onCancelProtocol = { generating.value = false },
                )
            }
        }

        val titleNode = compose.onNodeWithText(title).assertIsDisplayed()
        val titleLayouts = mutableListOf<TextLayoutResult>()
        titleNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
        assertEquals(1, titleLayouts.single().lineCount)
        assertTrue(titleLayouts.single().isLineEllipsized(0))
        val titleBounds = titleNode.fetchSemanticsNode().boundsInRoot
        val durationBounds = compose.onAllNodesWithText("1:12:01").fetchSemanticsNodes()
            .map { it.boundsInRoot }
            .single { titleBounds.top < it.bottom && it.top < titleBounds.bottom }
        assertTrue(titleBounds.right <= durationBounds.left)

        val generate = compose.onNodeWithText("Составить протокол")
        val summaryHeadingTop = compose.onNodeWithText("Кратко").getUnclippedBoundsInRoot().top
        val summaryBottom = compose.onNodeWithText(summary).getUnclippedBoundsInRoot().bottom
        assertEquals(16f, (generate.getUnclippedBoundsInRoot().top - summaryBottom).value, 1f)

        val idleButton = generate.getUnclippedBoundsInRoot()
        val transcriptTop = compose.onNodeWithTag("transcript-panel").getUnclippedBoundsInRoot().top
        generate.performClick()
        generate.assertIsNotEnabled()
        compose.onNodeWithContentDescription("Модель составляет протокол…", useUnmergedTree = true).assertIsDisplayed()
        val loadingButton = generate.getUnclippedBoundsInRoot()
        val cancel = compose.onNodeWithContentDescription("Отменить").assertIsDisplayed()
        assertEquals(idleButton.top.value, loadingButton.top.value, 1f)
        assertEquals(idleButton.bottom.value, loadingButton.bottom.value, 1f)
        assertEquals(4f, (cancel.getUnclippedBoundsInRoot().left - loadingButton.right).value, 1f)
        assertEquals(
            transcriptTop.value,
            compose.onNodeWithTag("transcript-panel").getUnclippedBoundsInRoot().top.value,
            1f,
        )
        cancel.performTouchInput { click() }
        cancel.assertDoesNotExist()

        compose.runOnIdle { detail.value = detail.value.copy(summary = null) }
        assertEquals(summaryHeadingTop.value, generate.getUnclippedBoundsInRoot().top.value, 1f)

        compose.runOnIdle {
            detail.value = detail.value.copy(summary = summary, suggestedNames = listOf(SpeakerName(0, "Анна")))
        }
        val suggestionBottom = compose.onNodeWithText("Спикер 1 — Анна").getUnclippedBoundsInRoot().bottom
        assertEquals(8f, (generate.getUnclippedBoundsInRoot().top - suggestionBottom).value, 1f)
    }

    @Test
    fun cancelledRecordingShowsTranscriptOnlyWhenItHasReplies() {
        val initial = transcriptDetail(emptyList())
        val detail = mutableStateOf(initial.copy(session = initial.session.copy(status = SessionStatus.CANCELLED)))
        compose.setContent {
            ActiveRecordingTheme {
                ActiveRecordingScreen(
                    detail = detail.value,
                    loaded = true,
                    elapsedMs = 4_000L,
                    processing = false,
                    canStop = false,
                    canCancel = false,
                    cancelling = false,
                    onBack = {},
                    onStop = {},
                    onCancel = {},
                    onRenameSpeaker = {},
                )
            }
        }

        compose.onNodeWithText("Отменено").assertIsDisplayed()
        val transcript = compose.onNodeWithTag("transcript-panel")
        transcript.assertDoesNotExist()

        val reply = transcriptSegment(0)
        compose.runOnIdle { detail.value = detail.value.copy(segments = listOf(reply)) }
        transcript.assertIsDisplayed()
        compose.onNodeWithText(reply.text).assertIsDisplayed()
    }

    @Test
    fun speakerCountUsesRussianFormsWithAnEnglishDeviceLocale() {
        val speakerCount = mutableStateOf(0)
        compose.setContent {
            val context = LocalContext.current
            val englishContext = remember(context) {
                context.createConfigurationContext(Configuration().apply { setLocale(Locale.US) })
            }
            CompositionLocalProvider(LocalContext provides englishContext) {
                ActiveRecordingTheme {
                    ActiveRecordingScreen(
                        detail = transcriptDetail(List(speakerCount.value) { transcriptSegment(it).copy(speaker = it) }),
                        loaded = true,
                        elapsedMs = 4_000L,
                        processing = false,
                        canStop = false,
                        canCancel = false,
                        cancelling = false,
                        onBack = {},
                        onStop = {},
                        onCancel = {},
                        onRenameSpeaker = {},
                    )
                }
            }
        }

        for ((count, label) in listOf(
            0 to "0 спикеров",
            1 to "1 спикер",
            2 to "2 спикера",
            4 to "4 спикера",
            5 to "5 спикеров",
            11 to "11 спикеров",
            12 to "12 спикеров",
            14 to "14 спикеров",
            21 to "21 спикер",
            22 to "22 спикера",
            25 to "25 спикеров",
        )) {
            compose.runOnIdle { speakerCount.value = count }
            compose.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun longProtocolScrollsToExportWithoutHidingTranscript() {
        val reply = transcriptSegment(0)
        val detail = transcriptDetail(listOf(reply)).copy(
            summary = "Участники обсудили сроки и договорились о следующих шагах.",
            protocol = ProtocolNotes(
                participants = emptyList(),
                summary = "Итоги встречи",
                decisions = List(5) { "Решение ${it + 1} по итогам обсуждения расписания" },
                actions = List(5) { ProtocolAction(task = "Поручение ${it + 1} по подготовке отчёта") },
            ),
        )
        var exported = false
        compose.setContent {
            ActiveRecordingTheme {
                Box(Modifier.height(700.dp)) {
                    ActiveRecordingScreen(
                        detail = detail,
                        loaded = true,
                        elapsedMs = 4_000L,
                        processing = false,
                        canStop = false,
                        canCancel = false,
                        cancelling = false,
                        onBack = {},
                        onStop = {},
                        onCancel = {},
                        onRenameSpeaker = {},
                        onExportProtocol = { exported = true },
                    )
                }
            }
        }

        compose.onNodeWithText(reply.text).assertIsDisplayed()
        compose.onNodeWithText("Сохранить .md").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(reply.text).assertIsDisplayed()
        compose.runOnIdle { assertTrue(exported) }
    }

    @Test
    fun transcriptExpandsOverControlsAndCollapsesWithoutResettingScroll() {
        val detail = transcriptDetail(transcriptSegments(50))
        compose.setContent {
            val panels = remember { NavigationPanels() }
            val slot = remember(panels) { NavigationPanelSlot(panels, detail.session.id) }
            ActiveRecordingTheme {
                OnWinNavigationLayout(panels, detail.session.id) { viewport ->
                    ActiveRecordingScreen(
                        modifier = viewport,
                        panelSlot = slot,
                        detail = detail,
                        loaded = true,
                        elapsedMs = 250_000,
                        processing = false,
                        canStop = false,
                        canCancel = false,
                        cancelling = false,
                        onBack = {},
                        onStop = {},
                        onCancel = {},
                        onRenameSpeaker = {},
                        playback = RecordingPlaybackState(durationMs = 250_000),
                    )
                }
            }
        }

        val list = compose.onNodeWithTag("transcript-list")
        val transcriptPanel = compose.onNodeWithTag("transcript-panel")
        val panel = compose.onNodeWithTag(NavigationPanelTag)
        val expand = compose.onNodeWithContentDescription("Развернуть реплики на весь экран")
        val collapse = compose.onNodeWithContentDescription("Свернуть реплики")
        val reply = compose.onNodeWithText("Реплика 25")
        list.performScrollToIndex(25)
        reply.assertIsDisplayed()
        val normal = list.getUnclippedBoundsInRoot()
        assertEquals(transcriptPanel.getUnclippedBoundsInRoot(), normal)
        assertTrue(expand.getUnclippedBoundsInRoot().top - normal.top in 0.dp..8.dp)
        panel.assertIsDisplayed()

        compose.mainClock.autoAdvance = false
        expand.performClick()
        compose.mainClock.advanceTimeBy(160)
        val expanding = list.getUnclippedBoundsInRoot()
        assertTrue(expanding.left < normal.left)
        compose.mainClock.autoAdvance = true

        collapse.assertIsDisplayed()
        expand.assertDoesNotExist()
        panel.assertDoesNotExist()
        compose.onNodeWithText("Встреча").assertDoesNotExist()
        reply.assertIsDisplayed()
        val fullscreen = list.getUnclippedBoundsInRoot()
        assertEquals(transcriptPanel.getUnclippedBoundsInRoot(), fullscreen)
        assertTrue(collapse.getUnclippedBoundsInRoot().top - fullscreen.top in 0.dp..8.dp)
        assertTrue(fullscreen.left < expanding.left)
        assertTrue(fullscreen.top < normal.top)
        assertTrue(fullscreen.bottom > normal.bottom)

        collapse.performClick()
        expand.assertIsDisplayed()
        collapse.assertDoesNotExist()
        panel.assertIsDisplayed()
        compose.onNodeWithText("Встреча").assertIsDisplayed()
        reply.assertIsDisplayed()
        assertEquals(normal, list.getUnclippedBoundsInRoot())
    }

    @Test
    fun onboardingWaitsForPlaybackAndTargetsTheLastLongSegment() {
        val step = mutableStateOf(TEXT)
        val status = mutableStateOf(SessionStatus.TRANSCRIBING)
        val playback = mutableStateOf(RecordingPlaybackState())
        val seeks = mutableListOf<Long>()
        val renamed = mutableListOf<Int>()
        var toggles = 0
        setOnboardingContent(step, status, playback, seeks::add, renamed::add, { toggles++ })
        val overlay = compose.onNodeWithTag("transcript-onboarding-overlay")
        val target = compose.onNodeWithTag("transcript-onboarding-target")

        overlay.assertDoesNotExist()
        compose.runOnIdle { status.value = SessionStatus.DONE }
        overlay.assertDoesNotExist()
        compose.runOnIdle { playback.value = RecordingPlaybackState(durationMs = 60_000) }
        compose.onNodeWithText("1 из 2").assertIsDisplayed()

        target.performTouchInput { click() }
        compose.onNodeWithText("2 из 2").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(55_000L), seeks) }

        target.performTouchInput { click() }
        overlay.assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(1), renamed)
            assertEquals(0, toggles)
            assertEquals(COMPLETE, step.value)
        }
    }

    @Test
    fun cardAdvancesAndOverlayConsumesTapOnPlaybackPanel() {
        val step = mutableStateOf(TEXT)
        var actions = 0
        setOnboardingContent(step, onSeek = { actions++ }, onRename = { actions++ }, onToggle = { actions++ })

        compose.onNodeWithText("1 из 2").performTouchInput { click() }
        compose.onNodeWithText("2 из 2").assertIsDisplayed()
        compose.onNodeWithTag(NavigationPanelTag, useUnmergedTree = true).performTouchInput { click() }
        compose.onNodeWithTag("transcript-onboarding-overlay").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, actions)
            assertEquals(COMPLETE, step.value)
        }
    }

    @Test
    fun onboardingStartsAtTheSavedSpeakerStep() {
        val step = mutableStateOf(SPEAKER)
        setOnboardingContent(step)

        compose.onNodeWithText("1 из 2").assertDoesNotExist()
        compose.onNodeWithText("2 из 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Переименовать спикера").performClick()
        compose.onNodeWithTag("transcript-onboarding-overlay").assertDoesNotExist()
    }

    private fun setOnboardingContent(
        step: MutableState<TranscriptOnboardingStep>,
        status: MutableState<SessionStatus> = mutableStateOf(SessionStatus.DONE),
        playback: MutableState<RecordingPlaybackState> = mutableStateOf(RecordingPlaybackState(durationMs = 60_000)),
        onSeek: (Long) -> Unit = {},
        onRename: (Int) -> Unit = {},
        onToggle: () -> Unit = {},
    ) {
        val segments = transcriptSegments(11) + transcriptSegment(11).copy(text = "Длинная реплика. ".repeat(75))
        val detail = transcriptDetail(segments)
        compose.setContent {
            val panels = remember { NavigationPanels() }
            val slot = remember(panels) { NavigationPanelSlot(panels, detail.session.id) }
            ActiveRecordingTheme {
                Box(Modifier.height(640.dp)) {
                    OnWinNavigationLayout(panels, detail.session.id) { viewport ->
                        ActiveRecordingScreen(
                            modifier = viewport,
                            panelSlot = slot,
                            detail = detail.copy(session = detail.session.copy(status = status.value)),
                            loaded = true,
                            elapsedMs = 60_000,
                            processing = status.value.isActive,
                            canStop = false,
                            canCancel = false,
                            cancelling = false,
                            onBack = {},
                            onStop = {},
                            onCancel = {},
                            onRenameSpeaker = onRename,
                            playback = playback.value,
                            onSeekPlayback = onSeek,
                            onTogglePlayback = onToggle,
                            onboardingStep = step.value,
                            onAdvanceOnboarding = { from ->
                                if (step.value != from) false else {
                                    step.value = if (from == TEXT) SPEAKER else COMPLETE
                                    true
                                }
                            },
                        )
                    }
                }
            }
        }
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
