package com.example.onwin.ui.screens.recordings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.example.onwin.R
import com.example.onwin.domain.model.Session
import com.example.onwin.ui.components.OnWinDialog
import com.example.onwin.ui.components.OnWinDialogSecondaryText
import com.example.onwin.ui.components.OnWinNameDialog
import com.example.onwin.ui.theme.GolosStyle

@Composable
internal fun RenameRecordingDialog(
    session: Session,
    onSave: suspend (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    key(session.id) {
        OnWinNameDialog(
            title = stringResource(R.string.recording_rename_title),
            label = stringResource(R.string.recording_rename_label),
            initialName = session.title,
            failureMessage = stringResource(R.string.recording_rename_failed),
            onSave = onSave,
            onDismiss = onDismiss,
        )
    }
}

@Composable
internal fun DeleteRecordingDialog(
    session: Session,
    canDelete: Boolean,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val quotedTitle = "«${session.title}»"
    val description = stringResource(R.string.delete_text, quotedTitle)
    val titleStart = description.indexOf(quotedTitle)
    val titleColor = MaterialTheme.colorScheme.onSurface
    OnWinDialog(
        title = stringResource(R.string.delete_title),
        icon = painterResource(R.drawable.ic_dialog_recording_delete),
        confirmText = stringResource(R.string.action_delete),
        onConfirm = {
            onDelete(session.id)
            onDismiss()
        },
        onDismiss = onDismiss,
        confirmEnabled = canDelete,
        destructive = true,
    ) {
        Text(
            text = buildAnnotatedString {
                append(description)
                if (titleStart >= 0) {
                    addStyle(
                        SpanStyle(color = titleColor, fontWeight = FontWeight.SemiBold),
                        start = titleStart,
                        end = titleStart + quotedTitle.length,
                    )
                }
            },
            color = OnWinDialogSecondaryText,
            style = GolosStyle.copy(fontSize = 15.sp, lineHeight = 22.5.sp),
            textAlign = TextAlign.Center,
        )
    }
}
