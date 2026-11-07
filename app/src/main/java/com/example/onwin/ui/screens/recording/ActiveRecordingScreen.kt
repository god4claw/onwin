package com.example.onwin.ui.screens.recording

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.onwin.R
import com.example.onwin.domain.model.TranscriptSegment
import com.example.onwin.domain.model.Session
import com.example.onwin.domain.model.SpeakerName
import com.example.onwin.domain.model.SessionDetail
import com.example.onwin.domain.model.SessionSource
import com.example.onwin.domain.model.SessionStatus
import com.example.onwin.ui.components.GlassButton
import com.example.onwin.ui.components.recordingPanelPrimary
import com.example.onwin.ui.navigation.NavigationPanelContent
import com.example.onwin.ui.navigation.panelElement
import com.example.onwin.ui.navigation.NavigationPanelSlot
import com.example.onwin.ui.navigation.OnWinScreenLayout
import com.example.onwin.ui.navigation.isCompact
import com.example.onwin.ui.formatClock
import com.example.onwin.ui.labelRes
import com.example.onwin.ui.messageRes
import com.example.onwin.ui.theme.ActiveRecordingTheme
import com.example.onwin.ui.theme.GolosText
import com.example.onwin.ui.theme.RecordingActiveColor
import com.example.onwin.ui.theme.RecordingCancelColor
import com.example.onwin.ui.theme.WinSurfaceOutline
import com.example.onwin.ui.theme.RecordingDurationColor
import com.example.onwin.ui.theme.RecordingNoteColor
import com.example.onwin.ui.theme.RecordingReadyColor
import com.example.onwin.ui.theme.RecordingSpeakerBackground
import com.example.onwin.ui.theme.RecordingSpeakerOutline
import com.example.onwin.ui.theme.RecordingTranscriptText
import com.example.onwin.ui.theme.recordingCardBackground

@Composable
internal fun ActiveRecordingScreen(
    modifier: Modifier = Modifier,
    detail: SessionDetail?,
    loaded: Boolean,
    elapsedMs: Long,
    processing: Boolean,
    canStop: Boolean,
    canCancel: Boolean,
    cancelling: Boolean,
    onBack: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onRenameSpeaker: (Int) -> Unit,
    playback: RecordingPlaybackState = RecordingPlaybackState(),
    playbackPosition: State<Long> = mutableLongStateOf(0L),
    onTogglePlayback: () -> Unit = {},
    onSeekPlayback: (Long) -> Unit = {},
    onRetry: () -> Unit = {},
    exportInProgress: Boolean = false,
    onExportJson: (() -> Unit)? = null,
    panelSlot: NavigationPanelSlot? = null,
    panelDimmed: Boolean = false,
) {
    val status = detail?.session?.status
    val panel: (@Composable (DpSize) -> NavigationPanelContent)? = when {
        status?.isActive == true -> { viewport ->
            recordingControlsPanel(viewport.isCompact, processing, canStop, canCancel, cancelling, onStop, onCancel)
        }
        status == SessionStatus.DONE -> { viewport ->
            recordingPlaybackPanelContent(
                viewport.isCompact, playback, playbackPosition, elapsedMs, onTogglePlayback, onSeekPlayback,
                sessionId = detail.session.id,
            )
        }
        detail?.session?.canRetry == true -> { _ ->
            NavigationPanelContent(
                horizontalPadding = 22.dp, topPadding = 22.dp, bottomPadding = 22.dp,
                secondary = panelElement(R.string.action_retry) { interactive ->
                    GlassButton(text = stringResource(R.string.action_retry), onClick = onRetry, enabled = interactive)
                },
            )
        }
        else -> null
    }

    OnWinScreenLayout(modifier, panelSlot, loading = !loaded, dimmed = panelDimmed, panel = panel) { viewport ->
        val compact = viewport.isCompact
        val topInset = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            ) {
                GlassButton(
                    text = stringResource(R.string.action_back),
                    onClick = onBack,
                    modifier = Modifier.padding(start = 28.dp, top = maxOf(topInset, if (compact) 16.dp else 20.dp))
                        .widthIn(min = 94.dp).heightIn(min = 42.dp),
                    textStyle = MaterialTheme.typography.labelLarge,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 9.dp),
                )
                when {
                    !loaded -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(24.dp))
                    detail == null -> Text(
                        stringResource(R.string.detail_not_found),
                        Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    else -> Column(
                        modifier = Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 12.dp),
                    ) {
                        RecordingSummary(detail, elapsedMs, compact)
                        Spacer(Modifier.height(14.dp))
                        RecordingTranscript(
                            modifier = Modifier.weight(1f, fill = false),
                            detail = detail,
                            compact = compact,
                            canSeek = playback.ready,
                            onRenameSpeaker = onRenameSpeaker,
                            onSeek = onSeekPlayback,
                        )
                        if (detail.session.status == SessionStatus.DONE && onExportJson != null) {
                            Spacer(Modifier.height(8.dp))
                            GlassButton(
                                text = stringResource(R.string.recording_save_json),
                                onClick = onExportJson,
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                                enabled = !exportInProgress,
                            )
                        }
                        detail.session.errorCode?.let { error ->
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = stringResource(error.messageRes),
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }
                }
            }
            if (status?.isActive == true) {
                Text(
                    text = stringResource(
                        if (processing) R.string.processing_background_note else R.string.recording_background_note,
                    ),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .padding(start = 16.dp, top = if (compact) 18.dp else 22.dp, end = 16.dp, bottom = 15.dp),
                    color = RecordingNoteColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            } else if (status == SessionStatus.DONE) {
                Spacer(Modifier.height(22.dp))
            }
        }
    }
}

@Composable
private fun RecordingSummary(detail: SessionDetail, elapsedMs: Long, compact: Boolean) {
    val session = detail.session
    val completed = session.status == SessionStatus.DONE
    val statusText = when (session.status) {
        SessionStatus.RECORDING -> R.string.active_recording_status
        SessionStatus.QUEUED, SessionStatus.CONVERTING, SessionStatus.TRANSCRIBING ->
            if (session.source == SessionSource.IMPORT) session.status.labelRes else R.string.recording_processing
        else -> session.status.labelRes
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = if (completed) {
                if (compact) 100.dp else 112.dp
            } else {
                if (compact) 88.dp else 100.dp
            })
            .recordingSurface()
            .padding(horizontal = 22.dp, vertical = if (compact) 12.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(session.title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
            Text(
                text = formatClock(elapsedMs),
                modifier = Modifier.padding(top = 3.dp),
                color = RecordingDurationColor,
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = GolosText),
            )
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
        ) {
            Text(
                text = stringResource(statusText),
                color = when (session.status) {
                    SessionStatus.DONE -> RecordingReadyColor
                    SessionStatus.FAILED -> MaterialTheme.colorScheme.error
                    SessionStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> RecordingActiveColor
                },
                style = if (completed) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            )
            if (completed) {
                val speakers = remember(detail.segments) {
                    detail.segments.mapTo(HashSet()) { it.speaker }.size
                }
                Text(
                    text = pluralStringResource(R.plurals.recording_speaker_count, speakers, speakers),
                    color = RecordingTranscriptText,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 17.sp, lineHeight = 20.4.sp),
                )
            }
        }
    }
}

@Composable
private fun RecordingTranscript(
    modifier: Modifier = Modifier,
    detail: SessionDetail,
    compact: Boolean,
    canSeek: Boolean,
    onSeek: (Long) -> Unit,
    onRenameSpeaker: (Int) -> Unit,
) {
    val segments = detail.segments
    val speakerNames = remember(detail.speakerNames) {
        detail.speakerNames.associate { it.speaker to it.name }
    }
    val listState = rememberLazyListState()
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    // Автопрокрутка к последней реплике, пока пользователь сам не отлистал вверх.
    var followLast by remember { mutableStateOf(true) }
    // Первое заполнение: сразу к низу, без анимации.
    var openedAtEnd by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        var firstEmission = true
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) return@collect
            // Первое событие — начальное состояние, ещё не действие пользователя.
            if (firstEmission) {
                firstEmission = false
                return@collect
            }
            followLast = !listState.canScrollForward
        }
    }

    LaunchedEffect(segments.size) {
        if (segments.isEmpty()) return@LaunchedEffect
        val lastIndex = segments.lastIndex
        if (!openedAtEnd) {
            openedAtEnd = true
            listState.scrollToItem(lastIndex, TranscriptEndOffset)
        } else if (followLast && !isDragged) {
            listState.animateScrollToItem(lastIndex)
            // Если последняя реплика выше блока, доводим прокрутку до конца её текста.
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull { it.index == lastIndex }
            val overflow = last?.let {
                it.offset + it.size - (layout.viewportEndOffset - layout.afterContentPadding)
            } ?: 0
            if (overflow > 0) listState.animateScrollBy(overflow.toFloat())
        }
    }

    Column(
        modifier = modifier.fillMaxWidth()
            .heightIn(max = if (compact) 184.dp else 208.dp)
            .recordingSurface(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f)
                .semantics { testTagsAsResourceId = true }
                .testTag("transcript-list"),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = if (compact) 16.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp, Alignment.Bottom),
        ) {
            items(segments, key = { it.index }) { segment ->
                val speakerName = speakerNames[segment.speaker]
                    ?: stringResource(R.string.speaker_default, segment.speaker + 1)
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = speakerName,
                            modifier = Modifier.weight(1f, fill = false)
                                .clip(CircleShape)
                                .background(RecordingSpeakerBackground, CircleShape)
                                .border(1.dp, RecordingSpeakerOutline, CircleShape)
                                .clickable(
                                    enabled = detail.session.status == SessionStatus.DONE,
                                    role = Role.Button,
                                    onClickLabel = stringResource(R.string.speaker_rename_title),
                                ) { onRenameSpeaker(segment.speaker) }
                                .padding(horizontal = 11.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = formatClock(segment.startMs),
                            modifier = Modifier.clickable(enabled = canSeek) { onSeek(segment.startMs) },
                            color = RecordingDurationColor,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text(
                        text = segment.text,
                        modifier = Modifier.padding(top = 6.dp)
                            .clickable(enabled = canSeek) { onSeek(segment.startMs) },
                        color = RecordingTranscriptText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun recordingControlsPanel(
    compact: Boolean,
    processing: Boolean,
    canStop: Boolean,
    canCancel: Boolean,
    cancelling: Boolean,
    onStop: () -> Unit,
    onCancel: () -> Unit,
): NavigationPanelContent = NavigationPanelContent(
    horizontalPadding = 22.dp,
    topPadding = if (compact) 22.dp else 25.dp,
    bottomPadding = if (compact) 20.dp else 27.dp,
    primaryBottomGap = if (compact) 14.dp else 22.dp,
    heading = panelElement(if (processing) R.string.processing_heading else R.string.recording_stop_heading) {
        Text(
            text = stringResource(if (processing) R.string.processing_heading else R.string.recording_stop_heading),
            modifier = Modifier.padding(bottom = if (compact) 24.dp else 32.dp),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    },
    primary = recordingPanelPrimary(
        onClick = onStop,
        stop = true,
        loadingLabel = when {
            cancelling -> if (processing) R.string.processing_cancelling else R.string.recording_cancelling
            processing -> R.string.recording_processing
            !canStop -> R.string.recording_stopping
            else -> null
        },
    ),
    secondary = panelElement(if (processing) R.string.processing_cancel else R.string.recording_cancel) { interactive ->
        TextButton(onClick = onCancel, enabled = canCancel && interactive) {
            Text(
                text = stringResource(if (processing) R.string.processing_cancel else R.string.recording_cancel),
                color = RecordingCancelColor,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 19.sp, lineHeight = 24.7.sp),
            )
        }
    },
)

private fun Modifier.recordingSurface(): Modifier {
    val shape = RoundedCornerShape(22.dp)
    return clip(shape).recordingCardBackground().border(1.dp, WinSurfaceOutline, shape)
}

@Preview(name = "Активная запись", locale = "ru", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun ActiveRecordingScreenPreview() {
    ActiveRecordingTheme {
        ActiveRecordingScreen(
            detail = previewRecordingDetail,
            loaded = true,
            elapsedMs = 12 * 60_000L + 34_000L,
            processing = false,
            canStop = true,
            canCancel = true,
            cancelling = false,
            onBack = {},
            onStop = {},
            onCancel = {},
            onRenameSpeaker = {},
        )
    }
}

private const val PreviewSessionId = "preview-recording"

/**
 * Смещение, заведомо большее высоты списка: прокрутка упирается в нижнюю границу, поэтому даже
 * у длинной последней реплики виден конец текста.
 */
private const val TranscriptEndOffset = Int.MAX_VALUE

private val previewRecordingDetail = SessionDetail(
    session = Session(
        id = PreviewSessionId,
        title = "Встреча с командой",
        source = SessionSource.MIC,
        originalName = "meeting.wav",
        sourceUri = null,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_000L,
        wavFileName = "meeting.wav",
        durationMs = null,
        status = SessionStatus.RECORDING,
    ),
    segments = listOf(
        previewSegment(0, speaker = 0, startMs = 0, endMs = 8_400, text = "Добрый день. Начнём с итогов прошлой недели."),
        previewSegment(
            1, speaker = 1, startMs = 9_200, endMs = 16_400,
            text = "Мы закончили макет и отправили его на проверку.",
        ),
        previewSegment(2, speaker = 0, startMs = 17_100, endMs = 24_300, text = "Отлично. Что осталось до релиза?"),
        previewSegment(
            3, speaker = 1, startMs = 25_000, endMs = 34_800,
            text = "Осталась интеграция с оплатой. Планирую закончить к среде.",
        ),
    ),
    speakerNames = listOf(
        SpeakerName(speaker = 0, name = "Анна"),
        SpeakerName(speaker = 1, name = "Игорь"),
    ),
)

private fun previewSegment(index: Int, speaker: Int, startMs: Long, endMs: Long, text: String) =
    TranscriptSegment(
        index = index,
        speaker = speaker,
        startMs = startMs,
        endMs = endMs,
        text = text,
    )
