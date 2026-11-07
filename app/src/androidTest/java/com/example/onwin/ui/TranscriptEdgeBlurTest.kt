package com.example.onwin.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.onwin.ui.screens.recording.transcriptEdgeBlur
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscriptEdgeBlurTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun softensOnlyEdgesWithContentBeyondThem() {
        val canScrollBackward = mutableStateOf(false)
        val canScrollForward = mutableStateOf(false)
        compose.setContent {
            Box(Modifier.size(96.dp).background(Color.Black).testTag("edge-preview")) {
                Canvas(
                    Modifier.fillMaxSize().transcriptEdgeBlur(
                        canScrollBackward = { canScrollBackward.value },
                        canScrollForward = { canScrollForward.value },
                    ),
                ) {
                    for (x in 0 until size.width.toInt() step 4) {
                        drawRect(Color.White, Offset(x.toFloat(), 0f), Size(2f, size.height))
                    }
                }
            }
        }

        val viewport = compose.onNodeWithTag("edge-preview")
        val sharp = viewport.captureToImage().toPixelMap()
        val top = 2
        val center = sharp.height / 2
        val bottom = sharp.height - 3

        compose.runOnIdle { canScrollBackward.value = true }
        val softenedTop = viewport.captureToImage().toPixelMap()
        assertTrue(softenedTop.contrastAt(top) < sharp.contrastAt(top) * 0.7f)
        assertEquals(sharp.contrastAt(center), softenedTop.contrastAt(center), 0.01f)
        assertEquals(sharp.contrastAt(bottom), softenedTop.contrastAt(bottom), 0.01f)

        compose.runOnIdle {
            canScrollBackward.value = false
            canScrollForward.value = true
        }
        val softenedBottom = viewport.captureToImage().toPixelMap()
        assertEquals(sharp.contrastAt(top), softenedBottom.contrastAt(top), 0.01f)
        assertEquals(sharp.contrastAt(center), softenedBottom.contrastAt(center), 0.01f)
        assertTrue(softenedBottom.contrastAt(bottom) < sharp.contrastAt(bottom) * 0.7f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Blurring also spreads brightness into the dark gaps between the stripes.
            assertTrue(softenedTop.brightnessAt(top).min() > 0.05f)
            assertTrue(softenedBottom.brightnessAt(bottom).min() > 0.05f)
        }
    }

    private fun PixelMap.contrastAt(y: Int): Float {
        val brightness = brightnessAt(y)
        return brightness.max() - brightness.min()
    }

    private fun PixelMap.brightnessAt(y: Int): List<Float> =
        (width / 4 until width * 3 / 4).map { x -> this[x, y].red }
}
