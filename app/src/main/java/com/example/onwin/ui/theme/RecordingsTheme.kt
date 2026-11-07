package com.example.onwin.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

internal val RecordingDurationColor = Color(0xFFB9C5CE)
internal val RecordingActiveColor = Color(0xFFFFCD73)
internal val RecordingReadyColor = Color(0xFFB3DBA9)
internal val GlassButtonOutline = Color(0x70D5DDE5)
internal val GlassButtonText = Color(0xFF273B49)
internal val RecordingSpeakerBackground = Color(0xFFF56E20)
internal val RecordingSpeakerOutline = Color(0x38FFB77C)
internal val RecordingTranscriptText = Color(0xFFF0F0EC)
internal val RecordingNoteColor = Color(0xFFBDCBD4)
internal val RecordingCancelColor = Color(0xFFFF8977)
internal val PlaybackTrackColor = Color(0x9EC8D4DD)
internal val PlaybackProgressColor = Color(0xFFE5EBEF)
internal val PlaybackThumbColor = Color(0xFFD7DFE4)
internal val PlaybackThumbOutline = Color(0xA0F1F3F4)
internal val PlaybackTimeColor = Color(0xFFDDE6EB)

private val RecordingsTypography = Typography.copy(
    headlineLarge = PlayfairStyle.copy(fontSize = 31.sp, lineHeight = 37.2.sp, letterSpacing = (-0.7).sp),
    headlineMedium = PlayfairStyle.copy(fontSize = 29.sp, lineHeight = 35.38.sp, letterSpacing = (-0.7).sp),
    titleLarge = GolosStyle.copy(fontSize = 21.sp, lineHeight = 25.2.sp, letterSpacing = (-0.3).sp),
    titleMedium = GolosStyle.copy(fontSize = 20.sp, lineHeight = 24.sp),
    bodySmall = GolosStyle.copy(fontSize = 13.sp, lineHeight = 16.sp),
    labelMedium = GolosStyle.copy(fontSize = 16.sp, lineHeight = 19.2.sp),
    labelLarge = GolosStyle.copy(fontSize = 17.sp, lineHeight = 20.4.sp),
)

private val ActiveRecordingTypography = RecordingsTypography.copy(
    titleLarge = PlayfairStyle.copy(fontSize = 26.sp, lineHeight = 31.2.sp, letterSpacing = (-0.5).sp),
    titleMedium = PlayfairStyle.copy(fontSize = 25.sp, lineHeight = 30.sp),
    headlineSmall = PlayfairStyle.copy(fontSize = 28.sp, lineHeight = 32.2.sp, fontFeatureSettings = "tnum"),
    bodyMedium = GolosStyle.copy(fontSize = 14.sp, lineHeight = 19.6.sp),
    labelSmall = GolosStyle.copy(fontSize = 12.sp, lineHeight = 14.4.sp, fontFeatureSettings = "tnum"),
    labelLarge = GolosStyle.copy(fontSize = 18.sp, lineHeight = 21.6.sp),
)

@Composable
internal fun RecordingsTheme(content: @Composable () -> Unit) =
    OnWinTheme(typography = RecordingsTypography, content = content)

@Composable
internal fun ActiveRecordingTheme(content: @Composable () -> Unit) =
    OnWinTheme(typography = ActiveRecordingTypography, content = content)

internal fun Modifier.recordingCardBackground() = onWinSurfaceFill()

internal fun Modifier.glassButtonBackground() = drawWithCache {
    val layers = listOf(
        size.linearGradient(
            180f, 0f to Color(0xE6C3CBD0), 0.58f to Color(0xE69DA9B2), 1f to Color(0xF2B4BEC5),
        ),
        size.ellipseGradient(1f, 0f, 0.35f, 0.85f, 0f to Color(0x30FFFFFF), 1f to Color.Transparent),
        size.ellipseGradient(0.12f, 0f, 0.22f, 0.75f, 0f to Color(0x35FFFFFF), 1f to Color.Transparent),
        size.linearGradient(
            180f, 0f to Color(0x30FFFFFF), 0.13f to Color.Transparent,
            0.87f to Color.Transparent, 1f to Color(0x388396A6),
        ),
    )
    onDrawBehind { layers.forEach { drawRect(it) } }
}
