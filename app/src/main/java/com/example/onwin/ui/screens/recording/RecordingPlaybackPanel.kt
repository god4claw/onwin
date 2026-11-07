package com.example.onwin.ui.screens.recording

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.onwin.R
import com.example.onwin.ui.navigation.OnWinPanelSurface
import com.example.onwin.ui.navigation.NavigationPanelContent
import com.example.onwin.ui.navigation.NavigationPanelContents
import com.example.onwin.ui.navigation.PanelPrimary
import com.example.onwin.ui.navigation.panelElement
import com.example.onwin.ui.formatClock
import com.example.onwin.ui.theme.PlaybackProgressColor
import com.example.onwin.ui.theme.PlaybackThumbColor
import com.example.onwin.ui.theme.PlaybackThumbOutline
import com.example.onwin.ui.theme.PlaybackTimeColor
import com.example.onwin.ui.theme.PlaybackTrackColor
import kotlin.math.roundToLong

@Composable
internal fun RecordingPlaybackPanel(
    compact: Boolean,
    playback: RecordingPlaybackState,
    recordingDurationMs: Long,
    onTogglePlayback: () -> Unit,
    onSeekPlayback: (Long) -> Unit,
) {
    OnWinPanelSurface(Modifier.padding(top = 22.dp)) {
        RecordingPlaybackContent(compact, playback, recordingDurationMs, onTogglePlayback, onSeekPlayback)
    }
}

@Composable
internal fun RecordingPlaybackContent(
    compact: Boolean,
    playback: RecordingPlaybackState,
    recordingDurationMs: Long,
    onTogglePlayback: () -> Unit,
    onSeekPlayback: (Long) -> Unit,
) {
    NavigationPanelContents(
        recordingPlaybackPanelContent(compact, playback, recordingDurationMs, onTogglePlayback, onSeekPlayback),
        owner = null,
        enabled = true,
    )
}

@Composable
internal fun recordingPlaybackPanelContent(
    compact: Boolean,
    playback: RecordingPlaybackState,
    recordingDurationMs: Long,
    onTogglePlayback: () -> Unit,
    onSeekPlayback: (Long) -> Unit,
    sessionId: String? = null,
): NavigationPanelContent = NavigationPanelContent(
    horizontalPadding = 26.dp,
    topPadding = if (compact) 24.dp else 27.dp,
    bottomPadding = if (compact) 42.dp else 68.dp,
    heading = panelElement(R.string.playback_heading) {
        Text(
            text = stringResource(R.string.playback_heading),
            modifier = Modifier.padding(bottom = if (compact) 20.dp else 33.dp),
            color = MaterialTheme.colorScheme.onSurface,
            style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge,
        )
    },
    supporting = panelElement(listOf(R.string.playback_timeline, sessionId)) { interactive ->
        Column(Modifier.padding(bottom = 25.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PlaybackTimeline(
                playback = playback,
                durationMs = playback.durationMs.takeIf { it > 0 } ?: recordingDurationMs,
                onSeek = onSeekPlayback,
                enabled = interactive,
            )
            val message = when {
                playback.focusUnavailable -> R.string.playback_cannot_start
                !playback.ready && !playback.preparing -> R.string.playback_unavailable
                else -> null
            }
            if (message != null) {
                Text(
                    text = stringResource(message),
                    modifier = Modifier.padding(top = 8.dp),
                    color = PlaybackTimeColor,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    },
    primary = PanelPrimary(
        onClick = onTogglePlayback,
        enabled = playback.ready,
        icon = panelElement(when {
            playback.preparing -> "playback-preparing"
            playback.playing -> R.string.playback_pause
            else -> R.string.playback_play
        }) {
            if (playback.preparing) {
                CircularProgressIndicator(Modifier.size(26.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
            } else {
                Icon(
                    painter = painterResource(if (playback.playing) R.drawable.ic_recording_pause else R.drawable.ic_recording_play),
                    contentDescription = stringResource(if (playback.playing) R.string.playback_pause else R.string.playback_play),
                    modifier = if (playback.playing) Modifier else Modifier.padding(start = 4.dp),
                )
            }
        },
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackTimeline(
    playback: RecordingPlaybackState,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    enabled: Boolean = true,
) {
    var draggedFraction by remember(playback.ready, durationMs) { mutableStateOf<Float?>(null) }
    val playbackFraction = if (durationMs > 0) {
        (playback.positionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    val fraction = draggedFraction ?: playbackFraction
    val positionMs = draggedFraction?.let { (durationMs.toDouble() * it).roundToLong() } ?: playback.positionMs
    val timelineLabel = stringResource(R.string.playback_timeline)
    Slider(
        value = fraction,
        onValueChange = {
            draggedFraction = it.coerceIn(0f, 1f)
        },
        onValueChangeFinished = {
            draggedFraction?.let { onSeek((durationMs.toDouble() * it).roundToLong()) }
            draggedFraction = null
        },
        modifier = Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 2.dp)
            .semantics { contentDescription = timelineLabel },
        enabled = playback.ready && enabled,
        thumb = {
            Box(
                Modifier.size(12.dp).background(PlaybackThumbColor, CircleShape)
                    .border(1.dp, PlaybackThumbOutline, CircleShape),
            )
        },
        track = {
            Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                val y = size.height / 2f
                val left = Offset(0f, y)
                val right = Offset(size.width, y)
                drawLine(PlaybackTrackColor, left, right, size.height, cap = StrokeCap.Round)
                val progress = fraction
                if (progress > 0f) {
                    val isRtl = layoutDirection == LayoutDirection.Rtl
                    val endX = size.width * if (isRtl) (1f - progress) else progress
                    drawLine(PlaybackProgressColor, if (isRtl) right else left, Offset(endX, y), size.height, cap = StrokeCap.Round)
                }
            }
        },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val timeStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp, lineHeight = 15.6.sp)
        val positionSeconds = positionMs.coerceAtLeast(0) / 1_000
        val positionText = remember(positionSeconds) { formatClock(positionSeconds * 1_000) }
        val durationText = remember(durationMs) { formatClock(durationMs) }
        Text(positionText, color = PlaybackTimeColor, style = timeStyle)
        Text(durationText, color = PlaybackTimeColor, style = timeStyle)
    }
}
