package com.example.onwin.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.onwin.ui.components.GradientButton
import com.example.onwin.ui.theme.RecordingsTheme

internal const val PanelPrimaryTag = "navigation-panel-primary"
internal val PanelPrimaryHeight = 60.dp
private const val PanelDuration = 220
private val PanelTrailingGap = 12.dp

internal data class PanelElement(
    val key: Any,
    val content: @Composable (enabled: Boolean) -> Unit,
)

/**
 * Keep the outgoing element's content and typography until its exit finishes.
 * Inlining creates the content lambda inside key(), so a new role cannot overwrite the old lambda.
 */
@Composable
internal inline fun panelElement(key: Any, crossinline content: @Composable (Boolean) -> Unit): PanelElement {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val shapes = MaterialTheme.shapes
    return key(key) {
        PanelElement(key) { enabled ->
            MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes) { content(enabled) }
        }
    }
}

internal data class PanelPrimary(
    val icon: PanelElement,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val width: Dp? = 136.dp,
    val trailing: PanelElement? = null,
)

internal data class NavigationPanelContent(
    val heading: PanelElement? = null,
    val supporting: PanelElement? = null,
    val primary: PanelPrimary? = null,
    val secondary: PanelElement? = null,
    val horizontalPadding: Dp = 26.dp,
    val topPadding: Dp = 0.dp,
    val bottomPadding: Dp = 0.dp,
    val primaryBottomGap: Dp = 0.dp,
)

private data class PanelLayoutKey(
    val owner: String?,
    val heading: Any?,
    val supporting: Any?,
    val hasPrimary: Boolean,
    val secondary: Any?,
    val trailing: Any?,
    val width: Dp?,
    val horizontal: Dp,
    val top: Dp,
    val bottom: Dp,
    val gap: Dp,
)

@Composable
internal fun NavigationPanelContents(model: NavigationPanelContent, owner: String?, enabled: Boolean) {
    val layoutKey = PanelLayoutKey(
        owner, model.heading?.key, model.supporting?.key, model.primary != null,
        model.secondary?.key, model.primary?.trailing?.key, model.primary?.width, model.horizontalPadding,
        model.topPadding, model.bottomPadding, model.primaryBottomGap,
    )
    var settledKey by remember { mutableStateOf(layoutKey) }
    val settling = remember { Animatable(1f) }
    LaunchedEffect(layoutKey) {
        if (settledKey != layoutKey) {
            settling.snapTo(0f)
            settling.animateTo(1f, tween(PanelDuration))
            settledKey = layoutKey
        }
    }
    val interactive = enabled && settledKey == layoutKey
    val horizontal by animateDpAsState(model.horizontalPadding, tween(PanelDuration), label = "panel sides")
    val top by animateDpAsState(model.topPadding, tween(PanelDuration), label = "panel top")
    val bottom by animateDpAsState(model.bottomPadding, tween(PanelDuration), label = "panel bottom")
    val gap by animateDpAsState(model.primaryBottomGap, tween(PanelDuration), label = "panel button gap")

    RecordingsTheme {
        Column(
            Modifier.fillMaxWidth().padding(start = horizontal, end = horizontal, top = top, bottom = bottom),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PanelElementTransition(model.heading, interactive, vertical = true)
            PanelElementTransition(model.supporting, interactive)
            PersistentPrimary(model.primary, interactive)
            Spacer(Modifier.height(gap))
            PanelElementTransition(model.secondary, interactive, collapse = true)
        }
    }
}

@Composable
private fun PanelElementTransition(
    element: PanelElement?,
    enabled: Boolean,
    vertical: Boolean = false,
    collapse: Boolean = false,
) {
    val distance = with(LocalDensity.current) { 8.dp.roundToPx() }
    AnimatedContent(
        targetState = element,
        contentKey = { it?.key },
        contentAlignment = Alignment.Center,
        transitionSpec = {
            val enter = fadeIn(tween(180, delayMillis = 40)) + when {
                vertical -> slideInVertically(tween(180, delayMillis = 40)) { distance }
                collapse -> scaleIn(tween(150, delayMillis = 70), initialScale = 0.88f)
                else -> scaleIn(tween(180, delayMillis = 40), initialScale = 0.98f)
            }
            val exit = fadeOut(tween(140)) + when {
                vertical -> slideOutVertically(tween(140)) { distance }
                collapse -> scaleOut(tween(110), targetScale = 0.88f)
                else -> scaleOut(tween(140), targetScale = 0.98f)
            }
            (enter togetherWith exit).using(SizeTransform(clip = true) { _, _ -> tween(PanelDuration) })
        },
        label = "panel element",
    ) { visible ->
        val current = visible?.key == element?.key
        Box(if (current) Modifier else Modifier.clearAndSetSemantics {}) {
            visible?.content?.invoke(enabled && current)
        }
    }
}

@Composable
private fun PersistentPrimary(primary: PanelPrimary?, enabled: Boolean) {
    // Keep the same Button instance while its position, size and inner symbol change.
    var retained by remember { mutableStateOf(primary) }
    if (primary != null) SideEffect { retained = primary }
    val visible = primary ?: retained
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val trailing = visible?.trailing
        val trailingSpace = if (trailing != null) PanelPrimaryHeight + PanelTrailingGap else 0.dp
        val widthLimit = (maxWidth - trailingSpace * 2).coerceAtLeast(0.dp)
        val width by animateDpAsState(
            (visible?.width ?: maxWidth.coerceAtMost(480.dp)).coerceAtMost(widthLimit),
            tween(PanelDuration), label = "panel primary width",
        )
        AnimatedVisibility(
            visible = primary != null,
            enter = expandVertically(tween(PanelDuration)) + fadeIn(tween(PanelDuration)),
            exit = shrinkVertically(tween(PanelDuration)) + fadeOut(tween(140)),
        ) {
            if (visible != null) {
                val primaryWidth = width.coerceAtMost(widthLimit)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    GradientButton(
                        onClick = { if (enabled && primary != null) primary.onClick() },
                        modifier = Modifier.width(primaryWidth).testTag(PanelPrimaryTag),
                        enabled = enabled && primary?.enabled == true,
                        minHeight = PanelPrimaryHeight,
                    ) {
                        AnimatedContent(
                            targetState = visible.icon,
                            contentKey = { it.key },
                            transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(140)) },
                            label = "panel primary icon",
                        ) { icon ->
                            Box(if (icon.key == visible.icon.key) Modifier else Modifier.clearAndSetSemantics {}) {
                                icon.content(enabled && primary != null)
                            }
                        }
                    }
                    if (trailing != null) {
                        Box(Modifier.offset(x = (primaryWidth + PanelPrimaryHeight) / 2 + PanelTrailingGap)) {
                            trailing.content(enabled && primary != null)
                        }
                    }
                }
            }
        }
    }
}
