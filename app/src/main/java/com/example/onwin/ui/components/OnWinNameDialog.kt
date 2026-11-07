package com.example.onwin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.onwin.R
import com.example.onwin.ui.theme.GolosStyle
import kotlinx.coroutines.launch

private val NameFieldShape = RoundedCornerShape(13.dp)
private val NameFieldFocusColor = Color(0xFFFFC18B)
private val FieldLabelStyle = GolosStyle.copy(fontSize = 13.sp, lineHeight = 16.sp)

@Composable
internal fun OnWinNameDialog(
    title: String,
    label: String,
    initialName: String,
    failureMessage: String,
    onSave: suspend (String) -> Boolean,
    onDismiss: () -> Unit,
    onReset: (suspend () -> Boolean)? = null,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit(action: suspend () -> Boolean) {
        if (saving) return
        saving = true
        saveFailed = false
        scope.launch {
            val saved = action()
            saving = false
            if (saved) onDismiss() else saveFailed = true
        }
    }

    OnWinDialog(
        title = title,
        icon = painterResource(R.drawable.ic_dialog_recording_rename),
        confirmText = stringResource(R.string.action_save),
        confirmEnabled = name.isNotBlank() && !saving,
        dismissEnabled = !saving,
        onConfirm = {
            val submitted = name
            if (submitted.isNotBlank()) submit { onSave(submitted) }
        },
        onDismiss = { if (!saving) onDismiss() },
    ) {
        Column(Modifier.fillMaxWidth()) {
            NameField(
                label = label,
                name = name,
                onNameChange = {
                    name = it
                    saveFailed = false
                },
                enabled = !saving,
            )
            if (saveFailed) {
                Text(
                    text = failureMessage,
                    modifier = Modifier.padding(start = 2.dp, top = 6.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    color = Color(0xFFFFA39A),
                    style = FieldLabelStyle,
                )
            }
            if (onReset != null) {
                TextButton(
                    onClick = { submit(onReset) },
                    modifier = Modifier.align(Alignment.End),
                    enabled = !saving,
                ) {
                    Text(
                        text = stringResource(R.string.action_reset_name),
                        style = GolosStyle.copy(fontSize = 16.sp, lineHeight = 19.2.sp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NameField(
    label: String,
    name: String,
    onNameChange: (String) -> Unit,
    enabled: Boolean,
) {
    var focused by remember { mutableStateOf(false) }
    val textColor = MaterialTheme.colorScheme.onSurface
    val outline = if (focused) NameFieldFocusColor else Color(0x50BAD9EB)
    Text(
        text = label,
        modifier = Modifier.padding(start = 2.dp),
        color = OnWinDialogSecondaryText,
        style = FieldLabelStyle,
    )
    Spacer(Modifier.height(7.dp))
    BasicTextField(
        value = name,
        onValueChange = onNameChange,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = label },
        enabled = enabled,
        singleLine = true,
        textStyle = GolosStyle.copy(color = textColor, fontSize = 16.sp, lineHeight = 20.sp),
        cursorBrush = SolidColor(NameFieldFocusColor),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 46.dp)
                    .background(Color(0x9C071D2C), NameFieldShape)
                    .border(1.dp, outline, NameFieldShape)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                innerTextField()
            }
        },
    )
}
