package com.example.onwin.ui.screens.recording

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.repeatOnLifecycle
import com.example.onwin.R
import com.example.onwin.data.export.TranscriptExportFormat
import com.example.onwin.domain.model.SessionStatus
import com.example.onwin.domain.processing.RecordingPhase
import com.example.onwin.domain.repository.TranscriptOnboardingStep
import com.example.onwin.ui.components.OnWinDialog
import com.example.onwin.ui.components.OnWinDialogSecondaryText
import com.example.onwin.ui.components.OnWinNameDialog
import com.example.onwin.ui.navigation.NavigationPanelSlot
import com.example.onwin.ui.theme.ActiveRecordingTheme
import com.example.onwin.ui.theme.GolosStyle

@Composable
internal fun ActiveRecordingRoute(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    viewModel: RecordingViewModel = hiltViewModel(),
    panelSlot: NavigationPanelSlot? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val exporting by viewModel.isExporting.collectAsStateWithLifecycle()
    val onboardingStep by viewModel.transcriptOnboardingStep.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val detail = state.detail
    val recording = state.recording
    val status = detail?.session?.status
    val isCapturing = status == SessionStatus.RECORDING
    val processing = status?.isActive == true && !isCapturing
    var confirmCancelProcessing by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var cancelling by rememberSaveable { mutableStateOf(false) }
    var lastElapsed by rememberSaveable { mutableLongStateOf(0L) }
    var editingSpeaker by rememberSaveable(viewModel.sessionId) { mutableStateOf<Int?>(null) }
    var editingSegment by rememberSaveable(viewModel.sessionId) { mutableStateOf<Int?>(null) }

    var showExportFormats by rememberSaveable(viewModel.sessionId) { mutableStateOf(false) }
    var pendingExportFormat by rememberSaveable(viewModel.sessionId) { mutableStateOf<String?>(null) }
    val exportPicker = rememberLauncherForActivityResult(TranscriptDocumentContract) { uri ->
        val format = pendingExportFormat?.let(TranscriptExportFormat::valueOf)
        pendingExportFormat = null
        if (uri != null && format != null) viewModel.exportTranscript(uri, format)
    }
    LaunchedEffect(viewModel, lifecycle, context) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.exportResults.collect { saved ->
                val message = if (saved) R.string.recording_export_saved else R.string.recording_export_save_failed
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val speakerToEdit = editingSpeaker?.takeIf { speaker ->
        detail?.segments?.any { it.speaker == speaker } == true
    }

    if (state.loaded && editingSpeaker != null && speakerToEdit == null) {
        LaunchedEffect(editingSpeaker) { editingSpeaker = null }
    }

    LaunchedEffect(recording?.elapsedMs, recording?.phase) {
        if (recording?.phase == RecordingPhase.RECORDING) lastElapsed = recording.elapsedMs
    }

    val canStop = isCapturing && recording?.phase == RecordingPhase.RECORDING && !cancelling
    val canCancel = !cancelling && (canStop || processing)
    val showCancelConfirmation = speakerToEdit == null && confirmCancelProcessing == processing && canCancel
    val elapsed = detail?.session?.durationMs
        ?: recording?.elapsedMs?.takeIf { recording.phase == RecordingPhase.RECORDING }
        ?: lastElapsed

    val completedSession = detail?.session?.takeIf { it.status == SessionStatus.DONE }
    val segmentToEdit = editingSegment?.takeIf { completedSession != null }?.let { index ->
        detail?.segments?.firstOrNull { it.index == index }
    }
    if (state.loaded && editingSegment != null && segmentToEdit == null) {
        LaunchedEffect(editingSegment) { editingSegment = null }
    }
    val wavFile = completedSession?.wavFileName?.let(viewModel::wavFile)
    val playback = rememberRecordingPlayback(wavFile)
    val visibleOnboardingStep = onboardingStep?.takeIf {
        lifecycleState == Lifecycle.State.RESUMED && it != TranscriptOnboardingStep.COMPLETE &&
            speakerToEdit == null && segmentToEdit == null && !showExportFormats &&
            pendingExportFormat == null && !showCancelConfirmation
    }

    ActiveRecordingTheme {
        ActiveRecordingScreen(
            modifier = modifier,
            panelSlot = panelSlot,
            panelDimmed = speakerToEdit != null || segmentToEdit != null || showCancelConfirmation || showExportFormats,
            detail = detail,
            loaded = state.loaded,
            elapsedMs = elapsed,
            processing = processing,
            canStop = canStop,
            canCancel = canCancel,
            cancelling = cancelling,
            onBack = onBack,
            onStop = viewModel::stopRecording,
            onCancel = { confirmCancelProcessing = processing },
            onRenameSpeaker = { editingSpeaker = it },
            onEditSegment = { editingSegment = it },
            playback = playback.state,
            playbackPosition = playback.positionState,
            onTogglePlayback = playback::toggle,
            onSeekPlayback = playback::seekTo,
            onRetry = {
                confirmCancelProcessing = null
                cancelling = false
                viewModel.retry()
            },
            exportInProgress = pendingExportFormat != null || exporting,
            onExport = completedSession?.let { { showExportFormats = true } },
            onboardingStep = visibleOnboardingStep,
            onAdvanceOnboarding = viewModel::advanceTranscriptOnboarding,
        )

        if (showExportFormats && completedSession != null) {
            TranscriptExportDialog(
                onDismiss = { showExportFormats = false },
                onFormatSelected = { format ->
                    if (pendingExportFormat == null && !viewModel.isExporting.value) {
                        showExportFormats = false
                        pendingExportFormat = format.name
                        try {
                            exportPicker.launch(TranscriptDocumentRequest(format, completedSession.title))
                        } catch (_: ActivityNotFoundException) {
                            pendingExportFormat = null
                            Toast.makeText(context, R.string.recording_export_picker_unavailable, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
        }
        if (speakerToEdit != null && detail != null) {
            val defaultName = stringResource(R.string.speaker_default, speakerToEdit + 1)
            val customName = detail.speakerNames.firstOrNull { it.speaker == speakerToEdit }?.name
            key(viewModel.sessionId, speakerToEdit) {
                OnWinNameDialog(
                    title = stringResource(R.string.speaker_rename_title),
                    label = stringResource(R.string.speaker_rename_label),
                    initialName = customName ?: defaultName,
                    failureMessage = stringResource(R.string.speaker_rename_failed),
                    onSave = { name ->
                        if (customName == null && name.trim() == defaultName) {
                            true
                        } else {
                            viewModel.setSpeakerName(speakerToEdit, name)
                        }
                    },
                    onDismiss = { editingSpeaker = null },
                    onReset = if (customName != null) {
                        { viewModel.setSpeakerName(speakerToEdit, null) }
                    } else {
                        null
                    },
                )
            }
        }
        if (segmentToEdit != null) {
            key(viewModel.sessionId, segmentToEdit.index) {
                EditSegmentDialog(
                    initialText = segmentToEdit.text,
                    onSave = { text -> viewModel.updateSegmentText(segmentToEdit.index, text) },
                    onDismiss = { editingSegment = null },
                )
            }
        }
        if (showCancelConfirmation) {
            OnWinDialog(
                title = stringResource(
                    if (processing) R.string.processing_cancel_confirm_title else R.string.recording_cancel_confirm_title,
                ),
                icon = painterResource(
                    if (processing) R.drawable.ic_stat_processing else R.drawable.ic_dialog_recording_delete,
                ),
                confirmText = stringResource(if (processing) R.string.processing_cancel else R.string.recording_cancel),
                dismissText = stringResource(if (processing) R.string.processing_continue else R.string.recording_continue),
                destructive = true,
                onDismiss = { confirmCancelProcessing = null },
                onConfirm = {
                    confirmCancelProcessing = null
                    cancelling = true
                    viewModel.cancel()
                },
            ) {
                Text(
                    text = stringResource(
                        if (processing) R.string.processing_cancel_confirm_message else R.string.recording_cancel_confirm_message,
                    ),
                    color = OnWinDialogSecondaryText,
                    style = GolosStyle.copy(fontSize = 15.sp, lineHeight = 22.5.sp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
