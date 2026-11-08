package com.example.onwin.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.onwin.ui.theme.GlassButtonOutline
import com.example.onwin.ui.theme.GradientButtonOutline
import com.example.onwin.ui.theme.RecordingsTheme
import com.example.onwin.ui.theme.glassButtonBackground
import com.example.onwin.ui.theme.gradientButtonBackground

private val TrackWidth = 52.dp
private val TrackHeight = 32.dp
private val TrackShape = RoundedCornerShape(16.dp)
private val ThumbDiameter = 24.dp
private val ThumbPadding = 4.dp
private val ThumbTravel = TrackWidth - ThumbDiameter - ThumbPadding * 2

/**
 * Переключатель в стиле OnWin: трек и кружок меняются местами парой
 * «оранжевый градиент / стеклянный белый», повторяя главную кнопку панели
 * ([gradientButtonBackground]) и стеклянные кнопки ([glassButtonBackground]).
 *
 * Размеры совпадают с Material3 [androidx.compose.material3.Switch], а семантика
 * переключателя (ToggleableState + Role.Switch) сохраняется для тестов и TalkBack.
 */
@Composable
internal fun GradientSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "gradientSwitchProgress",
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.5f,
        animationSpec = tween(durationMillis = 150),
        label = "gradientSwitchAlpha",
    )
    // Включено: трек оранжевый, кружок стеклянный. Выключено — наоборот.
    val trackOutline by animateColorAsState(
        targetValue = if (checked) GradientButtonOutline else GlassButtonOutline,
        animationSpec = tween(durationMillis = 200),
        label = "gradientSwitchTrackOutline",
    )
    val thumbOutline by animateColorAsState(
        targetValue = if (checked) GlassButtonOutline else GradientButtonOutline,
        animationSpec = tween(durationMillis = 200),
        label = "gradientSwitchThumbOutline",
    )
    val thumbOffset = ThumbPadding + ThumbTravel * progress

    Box(
        modifier = modifier
            .then(
                if (onCheckedChange != null) {
                    Modifier.toggleable(
                        value = checked,
                        enabled = enabled,
                        role = Role.Switch,
                        onValueChange = onCheckedChange,
                        interactionSource = interactionSource,
                    )
                } else {
                    Modifier
                }
            )
            .size(width = TrackWidth, height = TrackHeight)
            .graphicsLayer { alpha = contentAlpha },
        contentAlignment = Alignment.CenterStart,
    ) {
        // Трек: выключен — стеклянный белый.
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = 1f - progress }
                .clip(TrackShape)
                .glassButtonBackground(),
        )
        // Трек: включён — оранжевый градиент.
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = progress }
                .clip(TrackShape)
                .gradientButtonBackground(destructive = false),
        )
        Box(Modifier.matchParentSize().border(1.dp, trackOutline, TrackShape))

        // Кружок.
        Box(
            Modifier
                .offset(x = thumbOffset)
                .size(ThumbDiameter),
        ) {
            Box(Modifier.matchParentSize().shadow(4.dp, CircleShape))
            // Кружок: выключен — оранжевый градиент.
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = 1f - progress }
                    .clip(CircleShape)
                    .gradientButtonBackground(destructive = false),
            )
            // Кружок: включён — стеклянный белый.
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = progress }
                    .clip(CircleShape)
                    .glassButtonBackground(),
            )
            Box(Modifier.matchParentSize().border(1.dp, thumbOutline, CircleShape))
        }
    }
}

@Preview(
    name = "Переключатель GradientSwitch",
    locale = "ru",
    showBackground = true,
    widthDp = 360,
)
@Composable
private fun GradientSwitchPreview() {
    RecordingsTheme {
        var interactiveChecked by remember { mutableStateOf(true) }

        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GradientSwitch(
                    checked = interactiveChecked,
                    onCheckedChange = { interactiveChecked = it },
                )
                Text(
                    text = if (interactiveChecked) "Включено (интерактивный)" else "Выключено (интерактивный)",
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GradientSwitch(
                    checked = true,
                    onCheckedChange = null,
                )
                Text(
                    text = "Включено",
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GradientSwitch(
                    checked = false,
                    onCheckedChange = null,
                )
                Text(
                    text = "Выключено",
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GradientSwitch(
                    checked = true,
                    enabled = false,
                    onCheckedChange = null,
                )
                Text(
                    text = "Включено (неактивно)",
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GradientSwitch(
                    checked = false,
                    enabled = false,
                    onCheckedChange = null,
                )
                Text(
                    text = "Выключено (неактивно)",
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

