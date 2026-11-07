package com.example.onwin.ui.screens.recordings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.onwin.R
import com.example.onwin.ui.components.GlassButton

private val CardGap = 9.dp
private val ToggleInset = 23.dp

@Composable
internal fun RecordingsList(
    state: RecordingsUiState,
    expanded: Boolean,
    viewportHeight: Dp,
    cardHeight: Dp,
    bottomOverlayHeight: Dp,
    onToggle: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val hasToggle = state.sessions.size > PreviewRecordingCount
    val visibleSessions = if (expanded) state.sessions else state.sessions.take(PreviewRecordingCount)
    val bottomPadding = if (hasToggle) ToggleInset else 0.dp
    val density = LocalDensity.current
    val lastVisibleRow = remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val bottomEdge = layout.viewportEndOffset
            val tail = layout.visibleItemsInfo.lastOrNull { it.offset < bottomEdge }
            if (!listState.canScrollForward || tail == null || tail.size == 0) null
            else tail.key to ((bottomEdge - tail.offset).toFloat() / tail.size).coerceIn(0f, 1f)
        }
    }
    val collapsedHeight = cardHeight * PreviewRecordingCount * density.fontScale.coerceAtLeast(1f) +
        CardGap * (PreviewRecordingCount - 1) + bottomPadding
    val heightLimit by animateDpAsState(
        targetValue = if (expanded) viewportHeight else collapsedHeight,
        animationSpec = tween(ExpandDurationMs),
        label = "recordings list height",
    )
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        val listHeight = minOf(maxHeight, heightLimit)
        // Keep the toggle tappable and let the final card scroll clear of the overlay.
        val overlayInset = (bottomOverlayHeight - (maxHeight - listHeight)).coerceAtLeast(0.dp)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = listHeight),
            contentPadding = PaddingValues(bottom = bottomPadding + overlayInset),
            verticalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            itemsIndexed(visibleSessions, key = { _, item -> item.session.id }) { index, item ->
                val session = item.session
                val live = state.processing.recording?.takeIf { it.sessionId == session.id }
                val blurFraction: () -> Float = remember(listState, session.id, expanded, hasToggle, index) {
                    {
                        if (expanded) {
                            lastVisibleRow.value?.takeIf { it.first == session.id }?.second ?: 0f
                        } else if (hasToggle && index == PreviewRecordingCount - 1) {
                            1f
                        } else {
                            0f
                        }
                    }
                }
                RecordingCard(
                    session = session,
                    durationMs = live?.elapsedMs ?: session.durationMs,
                    canDelete = state.canDelete(session),
                    minHeight = cardHeight,
                    onOpen = { onOpen(session.id) },
                    onDelete = { onDelete(session.id) },
                    onRename = { onRename(session.id) },
                    modifier = Modifier.animateItem(),
                    visibleBlurFraction = blurFraction,
                )
            }
        }
        if (hasToggle) {
            GlassButton(
                text = stringResource(if (expanded) R.string.recordings_collapse else R.string.recordings_all),
                modifier = Modifier
                    .padding(bottom = overlayInset)
                    .widthIn(min = 136.dp)
                    .heightIn(min = 40.dp),
                onClick = {
                    if (expanded) listState.requestScrollToItem(0)
                    onToggle()
                },
            )
        }
    }
}
