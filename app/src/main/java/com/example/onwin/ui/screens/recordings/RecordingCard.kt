package com.example.onwin.ui.screens.recordings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.onwin.R
import com.example.onwin.domain.model.Session
import com.example.onwin.domain.model.SessionSource
import com.example.onwin.domain.model.SessionStatus
import com.example.onwin.ui.labelRes
import com.example.onwin.ui.theme.PlayfairStyle
import com.example.onwin.ui.theme.RecordingActiveColor
import com.example.onwin.ui.theme.RecordingCancelColor
import com.example.onwin.ui.theme.RecordingDurationColor
import com.example.onwin.ui.theme.RecordingReadyColor
import com.example.onwin.ui.theme.RecordingsTheme
import com.example.onwin.ui.theme.WinSurfaceOutline
import com.example.onwin.ui.theme.recordingCardBackground

@Composable
internal fun RecordingCard(
    session: Session,
    durationMs: Long?,
    canDelete: Boolean,
    minHeight: Dp,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    modifier: Modifier = Modifier,
    visibleBlurFraction: Float = 0f,
) {
    val shape = RoundedCornerShape(22.dp)
    val verticalPadding = if (minHeight < 79.dp) 10.dp else 14.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .shadow(5.dp, shape, clip = false)
            .clip(shape)
            .recordingTailBlur(visibleBlurFraction)
            .recordingCardBackground()
            .border(1.dp, WinSurfaceOutline, shape)
            .clickable(onClick = onOpen)
            .padding(start = 20.dp, end = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = verticalPadding)
                .padding(end = 8.dp)
        ) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = PlayfairStyle.fontFamily),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            val seconds = durationMs?.coerceAtLeast(0)?.div(1000)

            Text(
                text = if (seconds == null) stringResource(R.string.duration_unknown)
                else stringResource(R.string.recordings_duration, seconds / 60, seconds % 60),
                modifier = Modifier.padding(top = 3.dp),
                style = MaterialTheme.typography.bodySmall,
                color = RecordingDurationColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val status = session.status
        val micProcessing = session.source == SessionSource.MIC &&
                (status == SessionStatus.QUEUED || status == SessionStatus.TRANSCRIBING)

        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        when {
                            status == SessionStatus.RECORDING -> R.string.recordings_active
                            micProcessing -> R.string.recording_processing
                            else -> status.labelRes
                        }
                    ),
                    modifier = Modifier
                        .widthIn(max = 128.dp),
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = PlayfairStyle.fontFamily),
                    color = when (status) {
                        SessionStatus.DONE -> RecordingReadyColor
                        SessionStatus.FAILED -> MaterialTheme.colorScheme.error
                        SessionStatus.RECORDING, SessionStatus.CONVERTING, SessionStatus.TRANSCRIBING -> RecordingActiveColor
                        SessionStatus.QUEUED, SessionStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                IconButton(
                    modifier = Modifier.size(32.dp),
                    onClick = onRename,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_recording_rename),
                        contentDescription = stringResource(R.string.recordings_rename, session.title),
                    )
                }

                IconButton(
                    modifier = Modifier.size(32.dp),
                    onClick = onDelete,
                    enabled = canDelete,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = RecordingCancelColor,
                        disabledContentColor = RecordingCancelColor.copy(alpha = 0.38f),
                    ),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_recording_delete),
                        contentDescription = stringResource(R.string.recordings_delete, session.title),
                    )
                }
            }
        }
    }
}

@Preview(
    name = "Карточки записей",
    locale = "ru",
    showBackground = true,
    widthDp = 390,
    heightDp = 420
)
@Composable
private fun RecordingCardPreview() {
    RecordingsTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 22.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RecordingCard(
                session = previewCardSession(
                    id = "preview-recording",
                    title = "Встреча с командой",
                    status = SessionStatus.RECORDING,
                ),
                durationMs = 12 * 60_000L + 34_000L,
                canDelete = false,
                minHeight = PreviewCardMinHeight,
                onOpen = {},
                onDelete = {},
                onRename = {},
            )
            RecordingCard(
                session = previewCardSession(
                    id = "preview-done",
                    title = "Интервью с пользователем",
                    status = SessionStatus.DONE,
                ),
                durationMs = 8 * 60_000L + 5_000L,
                canDelete = true,
                minHeight = PreviewCardMinHeight,
                onOpen = {},
                onDelete = {},
                onRename = {},
            )
            RecordingCard(
                session = previewCardSession(
                    id = "preview-processing",
                    title = "Лекция по физике",
                    status = SessionStatus.TRANSCRIBING,
                ),
                durationMs = 45 * 60_000L + 30_000L,
                canDelete = true,
                minHeight = PreviewCardMinHeight,
                onOpen = {},
                onDelete = {},
                onRename = {},
            )
            RecordingCard(
                session = previewCardSession(
                    id = "preview-failed",
                    title = "Голосовая заметка",
                    status = SessionStatus.FAILED,
                ),
                durationMs = null,
                canDelete = true,
                minHeight = PreviewCardMinHeight,
                onOpen = {},
                onDelete = {},
                onRename = {},
            )
        }
    }
}

private val PreviewCardMinHeight = 79.dp

private fun previewCardSession(id: String, title: String, status: SessionStatus) = Session(
    id = id,
    title = title,
    source = SessionSource.MIC,
    originalName = "$id.wav",
    sourceUri = null,
    createdAt = 1_700_000_000_000L,
    updatedAt = 1_700_000_000_000L,
    wavFileName = "$id.wav",
    durationMs = null,
    status = status,
)
