package com.example.onwin.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp

internal val TranscriptHighlightAccent = Color(0xFFFF9E5A)
internal val TranscriptHighlightLabel = Color(0xFFFFD0AA)
internal val TranscriptHighlightText = Color(0xFFFFF9F0)

internal fun Modifier.transcriptHighlight(opacity: () -> Float) = drawWithCache {
    // The CSS surface extends past the content without moving the neighboring replies.
    val horizontalInset = 8.dp.toPx()
    val verticalInset = 7.dp.toPx()
    val surfaceSize = Size(size.width + 2 * horizontalInset, size.height + 2 * verticalInset)
    val width = surfaceSize.width
    val height = surfaceSize.height
    val radius = 13.dp.toPx()
    val rimWidth = 1.dp.toPx()
    val accentWidth = 3.dp.toPx()
    val gradient = surfaceSize.linearGradient(
        105f, 0f to Color(0x36FE7522), 0.56f to Color(0x4A406C8E), 1f to Color(0x38376785),
    )

    val outer = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, surfaceSize), CornerRadius(radius))) }
    val inside = Path().apply {
        val left = CornerRadius(radius - accentWidth, radius - rimWidth)
        val right = CornerRadius(radius - rimWidth)
        addRoundRect(RoundRect(Rect(accentWidth, rimWidth, width - rimWidth, height - rimWidth), left, right, right, left))
    }
    val rim = Path.combine(PathOperation.Difference, outer, inside)
    // The diagonal joins let the orange edge follow both rounded corners.
    val join = radius * rimWidth / accentWidth
    val leftEdge = Path().apply {
        moveTo(0f, 0f)
        lineTo(radius, join)
        lineTo(radius, height - join)
        lineTo(0f, height)
        close()
    }
    val accent = Path.combine(PathOperation.Intersect, rim, leftEdge)
    val insetShine = Path.combine(
        PathOperation.Difference, inside, Path().apply { addPath(inside, Offset(0f, rimWidth)) },
    )

    onDrawBehind {
        val alpha = opacity()
        translate(-horizontalInset, -verticalInset) {
            drawPath(outer, gradient, alpha = alpha)
            drawPath(rim, Color(0x3BB8E5F3), alpha = alpha)
            drawPath(accent, TranscriptHighlightAccent, alpha = alpha)
            drawPath(insetShine, Color(0x14E3F7FF), alpha = alpha)
        }
    }
}
