package com.example.onwin.ui.screens.recordings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.onwin.R
import com.example.onwin.domain.model.Session
import com.example.onwin.domain.model.SessionListItem
import com.example.onwin.domain.model.SessionSource
import com.example.onwin.domain.model.SessionStatus
import com.example.onwin.domain.processing.RecordingPhase
import com.example.onwin.ui.components.GlassButton
import com.example.onwin.ui.components.recordingPanelPrimary
import com.example.onwin.ui.navigation.NavigationPanelContent
import com.example.onwin.ui.navigation.panelElement
import com.example.onwin.ui.navigation.NavigationPanelSlot
import com.example.onwin.ui.navigation.OnWinScreenLayout
import com.example.onwin.ui.navigation.isCompact
import com.example.onwin.ui.theme.RecordingsTheme

internal const val PreviewRecordingCount = 3
internal const val ExpandDurationMs = 360

/** Зазор между раскрытым списком и панелью захвата, чтобы кнопка «Свернуть» не липла к ней. */
private val ListPanelGap = 16.dp

@Composable
internal fun RecordingsScreen(
    modifier: Modifier = Modifier,
    state: RecordingsUiState,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String) -> Unit,
    onRecord: () -> Unit,
    onImport: () -> Unit,
    onStopRecording: (String) -> Unit,
    panelSlot: NavigationPanelSlot? = null,
    panelDimmed: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val canExpand = state.sessions.size > PreviewRecordingCount
    LaunchedEffect(canExpand) { if (!canExpand) expanded = false }
    val listExpanded = expanded && canExpand
    val listPanelGap by animateDpAsState(
        targetValue = if (listExpanded) ListPanelGap else 0.dp,
        animationSpec = tween(ExpandDurationMs),
        label = "list panel gap",
    )
    val recording = state.processing.recording
    val savingRecording = recording?.phase == RecordingPhase.STOPPING ||
        (recording == null && state.sessions.any { it.session.status == SessionStatus.RECORDING })

    RecordingsTheme {
        OnWinScreenLayout(
            modifier = modifier,
            slot = panelSlot,
            dimmed = panelDimmed,
            panel = { viewport ->
                capturePanel(
                    expanded = listExpanded,
                    compact = viewport.isCompact,
                    recordingId = recording?.sessionId,
                    loadingLabel = when {
                        state.startingRecording || recording?.phase == RecordingPhase.STARTING -> R.string.recording_starting
                        savingRecording -> R.string.recording_stopping
                        else -> null
                    },
                    importing = state.importing,
                    canImport = state.canImport,
                    onRecord = onRecord,
                    onImport = onImport,
                    onStopRecording = onStopRecording,
                )
            },
        ) { viewport ->
            val compact = viewport.isCompact
            val safeTop = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.sessions_title),
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .padding(top = maxOf(safeTop, if (compact) 28.dp else 40.dp), start = 24.dp, end = 24.dp)
                        .semantics { heading() },
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.headlineLarge,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(max = 600.dp)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .padding(start = 22.dp, end = 22.dp, top = if (compact) 24.dp else 35.dp),
                ) {
                    if (state.sessions.isEmpty()) {
                        Text(
                            text = stringResource(R.string.recordings_empty),
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        RecordingsList(
                            state = state,
                            expanded = listExpanded,
                            viewportHeight = viewport.height,
                            cardHeight = if (compact) 68.dp else 79.dp,
                            onToggle = { expanded = !listExpanded },
                            onOpen = onOpen,
                            onDelete = onDelete,
                            onRename = onRename,
                        )
                    }
                }
                Spacer(Modifier.height(listPanelGap))
            }
        }
    }
}

@Composable
private fun capturePanel(
    expanded: Boolean,
    compact: Boolean,
    recordingId: String?,
    loadingLabel: Int?,
    importing: Boolean,
    canImport: Boolean,
    onRecord: () -> Unit,
    onImport: () -> Unit,
    onStopRecording: (String) -> Unit,
): NavigationPanelContent = NavigationPanelContent(
    horizontalPadding = 26.dp,
    topPadding = if (expanded) 12.dp else if (compact) 24.dp else 29.dp,
    bottomPadding = if (compact) 22.dp else 26.dp,
    primaryBottomGap = 19.dp,
    heading = if (expanded) null else panelElement(R.string.recordings_capture_title) {
        Text(
            text = stringResource(R.string.recordings_capture_title),
            modifier = Modifier.padding(bottom = if (compact) 22.dp else 28.dp).semantics { heading() },
            style = MaterialTheme.typography.headlineMedium.let {
                if (compact) it.copy(fontSize = 27.sp, lineHeight = 32.94.sp) else it
            },
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    },
    primary = recordingPanelPrimary(
        onClick = { if (recordingId == null) onRecord() else onStopRecording(recordingId) },
        stop = recordingId != null,
        loadingLabel = loadingLabel,
    ),
    secondary = panelElement(R.string.recordings_import_device) { interactive ->
        GlassButton(
            text = stringResource(R.string.recordings_import_device),
            onClick = onImport,
            enabled = canImport && interactive,
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(min = 47.dp),
            textStyle = MaterialTheme.typography.labelLarge,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            leadingIcon = {
                if (importing) {
                    val label = stringResource(R.string.recordings_importing)
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp).semantics { contentDescription = label },
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(painterResource(R.drawable.ic_recordings_import), contentDescription = null)
                }
            },
        )
    },
)

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun RecordingsScreenPreview() {
    val sampleSessions = listOf(
        SessionListItem(
            session = Session(
                id = "1",
                title = "Интервью с пользователем",
                source = SessionSource.MIC,
                originalName = "recording_1.wav",
                sourceUri = null,
                createdAt = 1700000000000L,
                updatedAt = 1700000000000L,
                durationMs = 485_000L,
                status = SessionStatus.DONE,
            ),
            segmentCount = 24,
            speakerCount = 2,
        ),
        SessionListItem(
            session = Session(
                id = "2",
                title = "Лекция по физике",
                source = SessionSource.IMPORT,
                originalName = "lecture.mp3",
                sourceUri = null,
                createdAt = 1700001000000L,
                updatedAt = 1700001000000L,
                durationMs = 2_730_000L,
                status = SessionStatus.DONE,
            ),
            segmentCount = 150,
            speakerCount = 1,
        ),
        SessionListItem(
            session = Session(
                id = "3",
                title = "Совещание по проекту",
                source = SessionSource.MIC,
                originalName = "meeting.wav",
                sourceUri = null,
                createdAt = 1700002000000L,
                updatedAt = 1700002000000L,
                durationMs = 915_000L,
                status = SessionStatus.TRANSCRIBING,
            ),
            segmentCount = 45,
            speakerCount = 4,
        ),
        SessionListItem(
            session = Session(
                id = "4",
                title = "Голосовая заметка",
                source = SessionSource.MIC,
                originalName = "note.wav",
                sourceUri = null,
                createdAt = 1700003000000L,
                updatedAt = 1700003000000L,
                durationMs = 42_000L,
                status = SessionStatus.FAILED,
            ),
            segmentCount = 0,
            speakerCount = 0,
        ),
    )

    RecordingsScreen(
        state = RecordingsUiState(sessions = sampleSessions),
        onOpen = {},
        onDelete = {},
        onRename = {},
        onRecord = {},
        onImport = {},
        onStopRecording = {},
    )
}

