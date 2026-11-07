package com.example.onwin.ui.screens.recording

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.onwin.R
import com.example.onwin.domain.repository.TranscriptOnboardingStep
import com.skydoves.balloon.ArrowPositionRules
import com.skydoves.balloon.Balloon
import com.skydoves.balloon.BalloonAnimation
import com.skydoves.balloon.rememberBalloonBuilder
import com.skydoves.balloon.rememberBalloonState
import kotlin.math.roundToInt

internal class TranscriptOnboardingTarget(val step: TranscriptOnboardingStep) {
    var bounds by mutableStateOf(Rect.Zero)
        private set

    fun anchor(modifier: Modifier, step: TranscriptOnboardingStep): Modifier {
        if (this.step != step) return modifier
        return modifier.onGloballyPositioned { bounds = it.boundsInWindow() }
    }
}

@Composable
internal fun TranscriptOnboardingOverlay(
    step: TranscriptOnboardingStep,
    targetBoundsInWindow: Rect,
    onTap: (targetHit: Boolean) -> Unit,
) {
    val isTextStep = step == TranscriptOnboardingStep.TEXT
    val next = stringResource(if (isTextStep) R.string.transcript_onboarding_next else R.string.transcript_onboarding_done)
    val action = stringResource(if (isTextStep) R.string.transcript_onboarding_seek else R.string.speaker_rename_title)
    val currentOnTap by rememberUpdatedState(onTap)
    var origin by remember { mutableStateOf<Offset?>(null) }
    val target = origin?.let { targetBoundsInWindow.translate(-it) }
    val currentTarget by rememberUpdatedState(target)
    val shade = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shade.animateTo(0.55f, tween(180)) }

    Box(
        Modifier.fillMaxSize()
            .testTag("transcript-onboarding-overlay")
            .onGloballyPositioned { origin = it.positionInWindow() }
            .pointerInput(step) {
                detectTapGestures { position -> currentOnTap(currentTarget?.contains(position) == true) }
            },
    ) {
        if (target != null) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = if (isTextStep) 8.dp.toPx() else target.height / 2
                val cutout = Path().apply {
                    addRoundRect(RoundRect(target, CornerRadius(radius)))
                }
                clipPath(cutout, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = shade.value)) }
            }
            val density = LocalDensity.current
            val anchor = Modifier.absoluteOffset { IntOffset(target.left.roundToInt(), target.top.roundToInt()) }
                .size(with(density) { target.width.toDp() }, with(density) { target.height.toDp() })
            OnboardingBalloon(
                step = step,
                modifier = anchor,
                next = next,
                onNext = { currentOnTap(false) },
            ) {
                Box(
                    Modifier.fillMaxSize()
                        .testTag("transcript-onboarding-target")
                        .semantics {
                            contentDescription = action
                            role = Role.Button
                            onClick {
                                currentOnTap(true)
                                true
                            }
                            customActions = listOf(CustomAccessibilityAction(next) {
                                currentOnTap(false)
                                true
                            })
                        },
                )
            }
        }
    }
}

@Composable
private fun OnboardingBalloon(
    step: TranscriptOnboardingStep,
    modifier: Modifier,
    next: String,
    onNext: () -> Unit,
    anchor: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val style = rememberBalloonBuilder {
        setBackgroundColor(colors.surface)
        setBorder(color = colors.outline, thickness = 1.dp)
        setArrowSize(10.dp)
        setArrowPositionRules(ArrowPositionRules.ALIGN_ANCHOR)
        setArrowPosition(0.5f)
        setCornerRadius(18.dp)
        setMarginHorizontal(16.dp)
        setMaxWidth(310.dp)
        setPadding(14.dp)
        setBalloonAnimation(BalloonAnimation.FADE)
        setFocusable(false)
        setDismissWhenClicked(false)
        setDismissWhenTouchOutside(false)
        setDismissWhenBackPressed(false)
    }
    val balloon = rememberBalloonState(style)
    SideEffect { balloon.onBalloonClick = onNext }
    LaunchedEffect(step, balloon) {
        balloon.dismiss()
        balloon.showAlignTop()
    }
    val isTextStep = step == TranscriptOnboardingStep.TEXT
    Balloon(
        modifier = modifier,
        state = balloon,
        balloonContent = {
            Column(
                modifier = Modifier.clickable(role = Role.Button, onClickLabel = next, onClick = onNext)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.transcript_onboarding_step, if (isTextStep) 1 else 2),
                    color = colors.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    stringResource(
                        if (isTextStep) R.string.transcript_onboarding_text else R.string.transcript_onboarding_speaker,
                    ),
                    color = colors.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
    ) { anchor() }
}
