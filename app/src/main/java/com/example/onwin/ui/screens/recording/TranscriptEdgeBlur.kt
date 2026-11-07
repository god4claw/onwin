package com.example.onwin.ui.screens.recording

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.withSaveLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun Modifier.transcriptEdgeBlur(
    canScrollBackward: () -> Boolean,
    canScrollForward: () -> Boolean,
): Modifier {
    val content = rememberGraphicsLayer()
    val topBlur = rememberGraphicsLayer()
    val bottomBlur = rememberGraphicsLayer()
    val supportsBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    return drawWithCache {
        val edgeHeight = minOf(16.dp.toPx(), size.height / 2f)
        val bottomStart = size.height - edgeHeight
        val top = Rect(0f, 0f, size.width, edgeHeight)
        val bottom = Rect(0f, bottomStart, size.width, size.height)
        val topMask = Brush.verticalGradient(listOf(Color.Black, Color.Transparent), 0f, edgeHeight)
        val bottomMask = Brush.verticalGradient(listOf(Color.Transparent, Color.Black), bottomStart, size.height)
        val paint = Paint()
        val radius = 3.dp.toPx()
        val samplePadding = (radius * 3f).roundToInt()
        if (supportsBlur) {
            val effect = BlurEffect(radius, radius, TileMode.Clamp)
            topBlur.renderEffect = effect
            topBlur.clip = true
            bottomBlur.renderEffect = effect
            bottomBlur.clip = true
        }

        fun DrawScope.drawEdge(bounds: Rect, mask: Brush, blur: GraphicsLayer) {
            if (supportsBlur) {
                // Capture a little past the blend boundary for the blur kernel.
                val sampleTop = (bounds.top.roundToInt() - samplePadding).coerceAtLeast(0)
                val sampleBottom = (bounds.bottom.roundToInt() + samplePadding)
                    .coerceAtMost(size.height.roundToInt())
                blur.topLeft = IntOffset(0, sampleTop)
                blur.record(size = IntSize(size.width.roundToInt(), sampleBottom - sampleTop)) {
                    translate(top = -sampleTop.toFloat()) { drawLayer(content) }
                }
            }
            clipRect(top = bounds.top, bottom = bounds.bottom) {
                val canvas = drawContext.canvas
                canvas.withSaveLayer(bounds, paint) {
                    drawLayer(content)
                    drawRect(mask, blendMode = BlendMode.DstOut)
                    if (supportsBlur) {
                        canvas.withSaveLayer(bounds, paint) {
                            drawLayer(blur)
                            drawRect(mask, blendMode = BlendMode.DstIn)
                        }
                    }
                }
            }
        }

        onDrawWithContent {
            // Read scrollability during drawing so scrolling doesn't recompose the list.
            val blurTop = canScrollBackward()
            val blurBottom = canScrollForward()
            if ((!blurTop && !blurBottom) || edgeHeight < 1f) {
                drawContent()
                return@onDrawWithContent
            }
            content.record { this@onDrawWithContent.drawContent() }
            clipRect(
                top = if (blurTop) edgeHeight else 0f,
                bottom = if (blurBottom) bottomStart else size.height,
            ) { drawLayer(content) }
            if (blurTop) drawEdge(top, topMask, topBlur)
            if (blurBottom) drawEdge(bottom, bottomMask, bottomBlur)
        }
    }
}
