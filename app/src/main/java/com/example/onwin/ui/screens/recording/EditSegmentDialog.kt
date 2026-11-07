package com.example.onwin.ui.screens.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.onwin.R
import com.example.onwin.ui.components.OnWinDialog
import kotlinx.coroutines.launch

@Composable
internal fun EditSegmentDialog(
    initialText: String,
    onSave: suspend (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initialText) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val textLabel = stringResource(R.string.segment_edit_text)

    OnWinDialog(
        title = stringResource(R.string.segment_edit_title),
        icon = painterResource(R.drawable.ic_dialog_recording_rename),
        confirmText = stringResource(R.string.action_save),
        confirmEnabled = text.isNotBlank() && !saving,
        dismissEnabled = !saving,
        onDismiss = onDismiss,
        onConfirm = {
            if (text.isNotBlank() && !saving) {
                saving = true
                saveFailed = false
                val submittedText = text
                scope.launch {
                    val saved = onSave(submittedText)
                    saving = false
                    if (saved) onDismiss() else saveFailed = true
                }
            }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    saveFailed = false
                },
                label = { Text(textLabel) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = textLabel },
                minLines = 2,
                maxLines = 5,
                enabled = !saving,
            )
            if (saveFailed) {
                Text(
                    text = stringResource(R.string.segment_edit_failed),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}
