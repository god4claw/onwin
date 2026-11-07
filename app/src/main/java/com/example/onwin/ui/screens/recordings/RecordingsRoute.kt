package com.example.onwin.ui.screens.recordings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import com.example.onwin.R
import com.example.onwin.domain.model.Session
import com.example.onwin.domain.model.SessionStatus
import com.example.onwin.ui.navigation.NavigationPanelSlot
import com.example.onwin.ui.theme.RecordingsTheme

@Composable
internal fun RecordingsRoute(
    modifier: Modifier = Modifier,
    onOpen: (String) -> Unit,
    viewModel: RecordingsViewModel = hiltViewModel(),
    panelSlot: NavigationPanelSlot? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    var renamingId by rememberSaveable { mutableStateOf<String?>(null) }
    val openRecording by rememberUpdatedState(onOpen)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val requestRecording = rememberRequestRecording(viewModel::startRecording)
    var pickingAudio by rememberSaveable { mutableStateOf(false) }
    var pickedAudio by rememberSaveable { mutableStateOf<Uri?>(null) }
    val audioPicker = rememberLauncherForActivityResult(OpenAudioDocument) { uri ->
        pickingAudio = false
        pickedAudio = uri
    }

    LaunchedEffect(pickedAudio, viewModel, lifecycle) {
        val uri = pickedAudio ?: return@LaunchedEffect
        lifecycle.withResumed {
            pickedAudio = null
            viewModel.import(uri)
        }
    }

    LaunchedEffect(viewModel, lifecycle, context) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.recordingEntryEvents.collect { event ->
                when (event) {
                    is RecordingEntryEvent.OpenMic -> if (viewModel.isRecording(event.id)) openRecording(event.id)
                    is RecordingEntryEvent.OpenImport -> if (viewModel.canOpenImport()) openRecording(event.id)
                    RecordingEntryEvent.MicFailed ->
                        Toast.makeText(context, R.string.recording_start_failed, Toast.LENGTH_SHORT).show()
                    RecordingEntryEvent.ImportFailed ->
                        Toast.makeText(context, R.string.recordings_import_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val deleting = state.sessions.firstOrNull { it.session.id == deletingId }?.session
    val renaming = state.sessions.firstOrNull { it.session.id == renamingId }?.session
    val dialogOpen = deleting != null || renaming != null
    RecordingsScreen(
        modifier = modifier,
        panelSlot = panelSlot,
        panelDimmed = dialogOpen,
        state = state,
        onOpen = onOpen,
        onDelete = { deletingId = it },
        onRename = { renamingId = it },
        onRecord = requestRecording,
        onImport = {
            if (state.canImport && !pickingAudio && pickedAudio == null) {
                pickingAudio = true
                try {
                    audioPicker.launch(arrayOf("audio/*", "application/ogg", "application/x-ogg"))
                } catch (_: ActivityNotFoundException) {
                    pickingAudio = false
                    Toast.makeText(context, R.string.recordings_import_picker_unavailable, Toast.LENGTH_SHORT).show()
                }
            }
        },
        onStopRecording = viewModel::stopRecording,
    )

    RecordingsTheme {
        if (deleting != null) {
            DeleteRecordingDialog(
                session = deleting,
                canDelete = state.canDelete(deleting),
                onDelete = { viewModel.delete(it) },
                onDismiss = { deletingId = null },
            )
        }
        if (renaming != null) {
            RenameRecordingDialog(
                session = renaming,
                onSave = { viewModel.rename(renaming.id, it) },
                onDismiss = { renamingId = null },
            )
        }
    }
}

private object OpenAudioDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE)
}

internal fun RecordingsUiState.canDelete(session: Session): Boolean =
    session.status != SessionStatus.RECORDING &&
        session.status != SessionStatus.CONVERTING &&
        session.status != SessionStatus.TRANSCRIBING &&
        processing.recording?.sessionId != session.id &&
        processing.running?.sessionId != session.id
