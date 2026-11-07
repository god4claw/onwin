package com.example.onwin.ui.screens.recordings

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.dp

@Composable
internal fun Modifier.recordingTailBlur(visibleFraction: Float): Modifier {
    if (visibleFraction <= 0f) return this
    val content = rememberGraphicsLayer()
    val blurred = rememberGraphicsLayer()
    val masked = rememberGraphicsLayer()
    return drawWithCache {
        val bottom = size.height * visibleFraction
        val top = bottom / 2
        val fade = Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color(0x730E3655)),
            startY = top,
            endY = bottom,
        )
        val supportsBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val feather = minOf(12.dp.toPx(), top)
        val mask = Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color.Black),
            startY = top - feather,
            endY = top + feather,
        )
        if (supportsBlur) {
            val radius = 3.dp.toPx()
            blurred.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
            masked.compositingStrategy = CompositingStrategy.Offscreen
        }
        onDrawWithContent {
            if (supportsBlur) {
                content.record { this@onDrawWithContent.drawContent() }
                drawLayer(content)
                blurred.record { drawLayer(content) }
                masked.record {
                    clipRect(bottom = bottom) { drawLayer(blurred) }
                    drawRect(mask, blendMode = BlendMode.DstIn)
                }
                drawLayer(masked)
            } else {
                drawContent()
            }
            drawRect(fade, topLeft = Offset(0f, top), size = Size(size.width, bottom - top))
        }
    }
}
