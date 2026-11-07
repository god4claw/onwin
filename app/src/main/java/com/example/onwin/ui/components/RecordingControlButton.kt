package com.example.onwin.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.onwin.R
import com.example.onwin.ui.navigation.PanelPrimary
import com.example.onwin.ui.navigation.panelElement

@Composable
internal fun recordingPanelPrimary(
    onClick: () -> Unit,
    stop: Boolean = false,
    @StringRes loadingLabel: Int? = null,
    enabled: Boolean = true,
): PanelPrimary = PanelPrimary(
    icon = panelElement(loadingLabel ?: if (stop) R.string.recording_stop else R.string.recordings_record_audio) {
        RecordingControlIcon(stop, loadingLabel)
    },
    onClick = onClick,
    enabled = enabled && loadingLabel == null,
)

@Composable
private fun RecordingControlIcon(stop: Boolean, @StringRes loadingLabel: Int?) {
    when {
        loadingLabel != null -> {
            val label = stringResource(loadingLabel)
            CircularProgressIndicator(
                modifier = Modifier.size(26.dp).semantics { contentDescription = label },
                color = LocalContentColor.current,
                strokeWidth = 2.dp,
            )
        }
        stop -> {
            val label = stringResource(R.string.recording_stop)
            Box(
                Modifier.size(26.dp)
                    .background(LocalContentColor.current, RoundedCornerShape(2.dp))
                    .semantics { contentDescription = label },
            )
        }
        else -> Icon(
            painter = painterResource(R.drawable.ic_recordings_mic),
            contentDescription = stringResource(R.string.recordings_record_audio),
        )
    }
}
