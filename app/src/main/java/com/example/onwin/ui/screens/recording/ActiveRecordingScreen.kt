package com.example.onwin.ui.screens.recording

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.example.onwin.R
import com.example.onwin.domain.model.TranscriptSegment
import com.example.onwin.domain.model.Session
import com.example.onwin.domain.model.SpeakerName
import com.example.onwin.domain.model.SessionDetail
import com.example.onwin.domain.model.SessionSource
import com.example.onwin.domain.model.SessionStatus
import com.example.onwin.domain.repository.TranscriptOnboardingStep
import com.example.onwin.ui.components.GlassButton
import com.example.onwin.ui.components.recordingPanelPrimary
import com.example.onwin.ui.navigation.NavigationPanelContent
import com.example.onwin.ui.navigation.panelElement
import com.example.onwin.ui.navigation.NavigationPanelSlot
import com.example.onwin.ui.navigation.OnWinScreenLayout
import com.example.onwin.ui.navigation.ScreenControls
import com.example.onwin.ui.navigation.ScreenControlsMotionMillis
import com.example.onwin.ui.navigation.isCompact
import com.example.onwin.ui.formatClock
import com.example.onwin.ui.labelRes
import com.example.onwin.ui.messageRes
import com.example.onwin.ui.screens.recordings.NotificationPermissionAction
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
import com.example.onwin.ui.theme.TranscriptHighlightAccent
import com.example.onwin.ui.theme.TranscriptHighlightLabel
import com.example.onwin.ui.theme.TranscriptHighlightText
import com.example.onwin.ui.theme.recordingCardBackground
import com.example.onwin.ui.theme.transcriptHighlight
import kotlin.math.roundToInt

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
    onEditSegment: ((Int) -> Unit)? = null,
    playback: RecordingPlaybackState = RecordingPlaybackState(),
    playbackPosition: State<Long> = mutableLongStateOf(0L),
    onTogglePlayback: () -> Unit = {},
    onSeekPlayback: (Long) -> Unit = {},
    onRetry: () -> Unit = {},
    exportInProgress: Boolean = false,
    onExport: (() -> Unit)? = null,
    onApplySuggestedName: ((Int, String) -> Unit)? = null,
    panelSlot: NavigationPanelSlot? = null,
    panelDimmed: Boolean = false,
    onboardingStep: TranscriptOnboardingStep? = null,
    onAdvanceOnboarding: (TranscriptOnboardingStep) -> Boolean = { false },
) {
    val status = detail?.session?.status
    val lastSegment = detail?.segments?.lastOrNull()
    var expandTranscript by rememberSaveable { mutableStateOf(false) }
    val expanded = expandTranscript && lastSegment != null
    val expansion = animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(ScreenControlsMotionMillis),
        label = "transcript expansion",
    )
    val expansionSettled by remember(expansion) { derivedStateOf { expansion.value == 0f } }
    BackHandler(enabled = expanded) { expandTranscript = false }
    if (expanded) ExpandedTranscriptStatusBar()

    val step = onboardingStep?.takeIf {
        it != TranscriptOnboardingStep.COMPLETE && loaded && !panelDimmed && !expanded && expansionSettled &&
            status == SessionStatus.DONE && playback.ready && lastSegment != null
    }
    val target = remember(detail?.session?.id, lastSegment?.index, step) {
        step?.let(::TranscriptOnboardingTarget)
    }
    val targetBounds = target?.bounds
    val overlay: (@Composable () -> Unit)? =
        if (step != null && lastSegment != null && targetBounds != null && !targetBounds.isEmpty) {
            {
                ActiveRecordingTheme {
                    TranscriptOnboardingOverlay(step, targetBounds) { targetHit ->
                        if (onAdvanceOnboarding(step) && targetHit) {
                            when (step) {
                                TranscriptOnboardingStep.TEXT -> onSeekPlayback(lastSegment.startMs)
                                TranscriptOnboardingStep.SPEAKER -> onRenameSpeaker(lastSegment.speaker)
                                TranscriptOnboardingStep.COMPLETE -> Unit
                            }
                        }
                    }
                }
            }
        } else null
    val panel: (@Composable (DpSize) -> NavigationPanelContent)? = when {
        status?.isActive == true -> { viewport ->
            recordingControlsPanel(viewport.isCompact, processing, canStop, canCancel, cancelling, onStop, onCancel)
        }
        status == SessionStatus.DONE -> { viewport ->
            recordingPlaybackPanelContent(
                viewport.isCompact, playback, playbackPosition, elapsedMs, onTogglePlayback, onSeekPlayback,
                sessionId = detail.session.id,
                onExport = onExport,
                exportInProgress = exportInProgress,
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

    OnWinScreenLayout(
        modifier, panelSlot, loading = !loaded, dimmed = panelDimmed, panel = panel, overlay = overlay,
        bottomPanelVisible = !expanded,
    ) { viewport ->
        val compact = viewport.isCompact
        val systemPadding = WindowInsets.safeDrawing.asPaddingValues()
        val topInset = systemPadding.calculateTopPadding()
        val blue = MaterialTheme.colorScheme.background
        val progress = expansion.value
        Column(Modifier.fillMaxSize().drawBehind {
            val progress = expansion.value
            if (progress > 0f) drawRect(blue, alpha = progress)
        }) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            ) {
                ScreenControls(visible = !expanded) {
                    GlassButton(
                        text = stringResource(R.string.action_back),
                        onClick = onBack,
                        enabled = !expanded,
                        modifier = Modifier.padding(start = 28.dp, top = maxOf(topInset, if (compact) 16.dp else 20.dp))
                            .widthIn(min = 94.dp).heightIn(min = 42.dp),
                        textStyle = MaterialTheme.typography.labelLarge,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 9.dp),
                    )
                }
                when {
                    !loaded -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(24.dp))
                    detail == null -> Text(
                        stringResource(R.string.detail_not_found),
                        Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    else -> Column(
                        modifier = Modifier.fillMaxSize().padding(
                            start = lerp(20.dp, 0.dp, progress),
                            end = lerp(20.dp, 0.dp, progress),
                            top = lerp(12.dp, topInset + 8.dp, progress),
                            bottom = (systemPadding.calculateBottomPadding() + 8.dp) * progress,
                        ),
                    ) {
                        ScreenControls(visible = !expanded) {
                            Column {
                                RecordingSummary(detail, elapsedMs, compact)
                                val suggested = detail.suggestedNames.filterNot { suggestion ->
                                    detail.speakerNames.any { it.speaker == suggestion.speaker }
                                }
                                if (detail.summary != null || suggested.isNotEmpty()) {
                                    Spacer(Modifier.height(12.dp))
                                    LlmNotes(detail.summary, suggested, onApplySuggestedName)
                                }
                                Spacer(Modifier.height(14.dp))
                            }
                        }
                        }
                        val maxHeight = if (status == SessionStatus.DONE) Modifier else {
                            val collapsedHeight = if (compact) 184.dp else 208.dp
                            Modifier.heightIn(max = lerp(collapsedHeight, viewport.height, progress))
                        }
                        RecordingTranscript(
                            modifier = Modifier.weight(1f, fill = status == SessionStatus.DONE).then(maxHeight),
                            detail = detail,
                            expanded = expanded,
                            expansion = expansion,
                            onToggleExpanded = if (lastSegment != null) {
                                { expandTranscript = !expanded }
                            } else null,
                            canSeek = playback.ready,
                            playing = playback.ready && playback.playing && status == SessionStatus.DONE,
                            playbackPosition = playbackPosition,
                            onRenameSpeaker = onRenameSpeaker,
                            onEditSegment = onEditSegment,
                            onSeek = onSeekPlayback,
                            onboardingTarget = target,
                        )
                        detail.session.errorCode?.let { error ->
                            ScreenControls(visible = !expanded, anchor = Alignment.Bottom) {
                                Text(
                                    text = stringResource(error.messageRes),
                                    modifier = Modifier.padding(top = 14.dp),
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                            }
                        }
                    }
                }
            }
            ScreenControls(
                visible = !expanded,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                anchor = Alignment.Bottom,
            ) {
                if (status?.isActive == true) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(
                                if (processing) R.string.processing_background_note else R.string.recording_background_note,
                            ),
                            modifier = Modifier
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                                .padding(start = 16.dp, top = if (compact) 18.dp else 22.dp, end = 16.dp, bottom = 15.dp),
                            color = RecordingNoteColor,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        NotificationPermissionAction(Modifier.align(Alignment.CenterHorizontally))
                    }
                } else if (status == SessionStatus.DONE) {
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun ExpandedTranscriptStatusBar() {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    DisposableEffect(window, view) {
        val insets = WindowCompat.getInsetsController(window, view)
        val previousAppearance = insets.isAppearanceLightStatusBars
        insets.isAppearanceLightStatusBars = false
        onDispose { insets.isAppearanceLightStatusBars = previousAppearance }
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
    expanded: Boolean,
    expansion: State<Float>,
    onToggleExpanded: (() -> Unit)?,
    canSeek: Boolean,
    playing: Boolean,
    playbackPosition: State<Long>,
    onSeek: (Long) -> Unit,
    onRenameSpeaker: (Int) -> Unit,
    onEditSegment: ((Int) -> Unit)?,
    onboardingTarget: TranscriptOnboardingTarget?,
) {
    val segments = detail.segments
    val speakerNames = remember(detail.speakerNames) {
        detail.speakerNames.associate { it.speaker to it.name }
    }
    val listState = rememberLazyListState()
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    val currentItem by remember(playing, segments, playbackPosition) {
        val timeline = if (playing) segments.withIndex().sortedBy { it.value.startMs } else emptyList()
        derivedStateOf(structuralEqualityPolicy()) {
            if (timeline.isEmpty()) {
                null
            } else {
                val position = playbackPosition.value
                timeline.lastOrNull { position >= it.value.startMs && position < it.value.endMs }
            }
        }
    }
    var followPlayback by remember(playing) { mutableStateOf(playing) }

    LaunchedEffect(isDragged, playing) {
        if (isDragged) followPlayback = false
    }
    LaunchedEffect(currentItem?.index, followPlayback, isDragged) {
        val index = currentItem?.index ?: return@LaunchedEffect
        if (!followPlayback || isDragged) return@LaunchedEffect
        val layout = listState.layoutInfo
        val visible = layout.visibleItemsInfo.firstOrNull { it.index == index }
        if (visible == null || visible.offset < 0 || visible.offset + visible.size >
            layout.viewportEndOffset - layout.afterContentPadding
        ) {
            listState.scrollToItem(index)
        }
    }

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
        if (!openedAtEnd) {
            openedAtEnd = true
            if (onboardingTarget == null) listState.scrollToTranscriptEnd(segments.lastIndex, animate = false)
        } else if (followLast && !isDragged) {
            listState.scrollToTranscriptEnd(segments.lastIndex, animate = true)
        }
    }

    LaunchedEffect(listState, playing, onboardingTarget) {
        if (playing || onboardingTarget != null) return@LaunchedEffect
        var previousHeight = 0
        var pinnedToEnd = false
        snapshotFlow {
            listState.layoutInfo.viewportSize.height to (listState.canScrollBackward && !listState.canScrollForward)
        }.collect { (height, atEnd) ->
            // Keep the last reply visible if the controls reduce the viewport after layout.
            if (height in 1 until previousHeight && pinnedToEnd && !atEnd && !listState.isScrollInProgress) {
                val lastIndex = listState.layoutInfo.totalItemsCount - 1
                if (lastIndex >= 0) listState.scrollToTranscriptEnd(lastIndex, animate = false)
            }
            previousHeight = listState.layoutInfo.viewportSize.height
            pinnedToEnd = listState.canScrollBackward && !listState.canScrollForward
        }
    }

    LaunchedEffect(onboardingTarget) {
        if (onboardingTarget != null) {
            // Let an already visible anchor report its bounds before scrolling the last row into view.
            withFrameNanos { }
            if (onboardingTarget.bounds.isEmpty) listState.scrollToItem(segments.lastIndex)
        }
    }

    val listAlignment = remember(expansion) {
        Alignment.Vertical { size, space -> ((space - size) * (1f - expansion.value)).roundToInt() }
    }
    TranscriptSurface(modifier.fillMaxWidth(), expansion) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
                .transcriptEdgeBlur(
                    canScrollBackward = { listState.canScrollBackward },
                    canScrollForward = { listState.canScrollForward },
                )
                .semantics { testTagsAsResourceId = true }
                .testTag("transcript-list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp, listAlignment),
        ) {
            items(segments, key = { it.index }) { segment ->
                val target = onboardingTarget?.takeIf { segment.index == segments.lastOrNull()?.index }
                val speakerName = speakerNames[segment.speaker]
                    ?: stringResource(R.string.speaker_default, segment.speaker + 1)
                val timecode = formatClock(segment.startMs)
                val current = currentItem?.value?.index == segment.index
                val highlight = remember { Animatable(0f) }
                val highlightSurface = remember(highlight) {
                    Modifier.transcriptHighlight { highlight.value }
                }
                LaunchedEffect(current) {
                    val targetOpacity = if (current) 1f else 0f
                    if (highlight.value != targetOpacity) {
                        highlight.animateTo(targetOpacity, tween(durationMillis = 220))
                    }
                }
                val showHighlight = current || highlight.value > 0f
                Column(
                    Modifier.fillMaxWidth().then(
                        if (showHighlight) highlightSurface else Modifier,
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = speakerName,
                                modifier = Modifier.weight(1f, fill = false)
                                    .then(target?.anchor(Modifier, TranscriptOnboardingStep.SPEAKER) ?: Modifier)
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = timecode,
                                    modifier = Modifier.clickable(enabled = canSeek) { onSeek(segment.startMs) },
                                    color = RecordingDurationColor,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                if (detail.session.status == SessionStatus.DONE && onEditSegment != null) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_recording_rename),
                                        contentDescription = stringResource(R.string.segment_edit_action, timecode),
                                        modifier = Modifier.size(20.dp)
                                            .clickable(role = Role.Button) { onEditSegment(segment.index) },
                                        tint = RecordingDurationColor,
                                    )
                                }
                            }
                        }
                        if (showHighlight) {
                            TranscriptPlaybackLabel(
                                Modifier.graphicsLayer { alpha = highlight.value }
                                    .then(if (current) Modifier else Modifier.clearAndSetSemantics {}),
                            )
                        }
                    }
                    Text(
                        text = segment.text,
                        modifier = Modifier.padding(top = 6.dp)
                            .then(target?.anchor(Modifier, TranscriptOnboardingStep.TEXT) ?: Modifier)
                            .clickable(enabled = canSeek) { onSeek(segment.startMs) },
                        color = lerp(RecordingTranscriptText, TranscriptHighlightText, highlight.value),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (onToggleExpanded != null) {
            IconButton(
                onClick = onToggleExpanded,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp),
            ) {
                Icon(
                    painter = painterResource(
                        if (expanded) R.drawable.ic_transcript_minimize else R.drawable.ic_transcript_maximize,
                    ),
                    contentDescription = stringResource(
                        if (expanded) R.string.transcript_minimize else R.string.transcript_maximize,
                    ),
                    tint = RecordingDurationColor,
                )
            }
        }
    }
}

private suspend fun LazyListState.scrollToTranscriptEnd(lastIndex: Int, animate: Boolean) {
    if (animate) animateScrollToItem(lastIndex) else scrollToItem(lastIndex)
    val layout = layoutInfo
    val last = layout.visibleItemsInfo.lastOrNull { it.index == lastIndex } ?: return
    val overflow = last.offset + last.size - (layout.viewportEndOffset - layout.afterContentPadding)
    if (overflow > 0) {
        if (animate) animateScrollBy(overflow.toFloat()) else scrollBy(overflow.toFloat())
    }
}

@Composable
private fun TranscriptPlaybackLabel(modifier: Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_transcript_playing),
            contentDescription = null,
            tint = TranscriptHighlightAccent,
        )
        Text(
            text = stringResource(R.string.playback_current_reply),
            color = TranscriptHighlightLabel,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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

@Composable
private fun LlmNotes(
    summary: String?,
    suggestions: List<SpeakerName>,
    onApply: ((Int, String) -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxWidth().recordingSurface().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (summary != null) {
            Text(
                text = stringResource(R.string.llm_summary_title),
                color = RecordingDurationColor,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = summary,
                color = RecordingTranscriptText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (suggestions.isNotEmpty() && onApply != null) {
            Text(
                text = stringResource(R.string.llm_names_title),
                color = RecordingDurationColor,
                style = MaterialTheme.typography.labelMedium,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                suggestions.forEach { suggestion ->
                    val shape = RoundedCornerShape(12.dp)
                    Text(
                        text = stringResource(
                            R.string.llm_suggested_name,
                            suggestion.speaker + 1,
                            suggestion.name,
                        ),
                        modifier = Modifier
                            .clip(shape)
                            .background(RecordingSpeakerBackground, shape)
                            .border(1.dp, RecordingSpeakerOutline, shape)
                            .clickable(role = Role.Button) { onApply(suggestion.speaker, suggestion.name) }
                            .padding(horizontal = 11.dp, vertical = 5.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun Modifier.recordingSurface(): Modifier {
    val shape = RoundedCornerShape(22.dp)
    return clip(shape).recordingCardBackground().border(1.dp, WinSurfaceOutline, shape)
}

@Composable
private fun TranscriptSurface(
    modifier: Modifier,
    expansion: State<Float>,
    content: @Composable BoxScope.() -> Unit,
) {
    val showCard by remember(expansion) { derivedStateOf { expansion.value < 1f } }
    Box(modifier.testTag("transcript-panel").clip(RoundedCornerShape(22.dp * (1f - expansion.value)))) {
        if (showCard) {
            Box(
                Modifier.matchParentSize()
                    .graphicsLayer { alpha = 1f - expansion.value }
                    .recordingSurface(),
            )
        }
        content()
    }
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
