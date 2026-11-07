package com.example.onwin.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.onwin.ui.theme.GlassButtonOutline
import com.example.onwin.ui.theme.GlassButtonText
import com.example.onwin.ui.theme.glassButtonBackground

@Composable
internal fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.labelMedium,
    contentPadding: PaddingValues = PaddingValues(horizontal = 19.dp, vertical = 8.dp),
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    GlassButton(onClick, modifier, contentPadding, enabled) {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(Modifier.width(9.dp))
        }
        Text(text, style = textStyle, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    // Оставляем размер из макета. Compose расширяет зону касания небольших кнопок.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier
                .shadow(7.dp, CircleShape, clip = true)
                .glassButtonBackground()
                .border(1.dp, GlassButtonOutline, CircleShape),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = GlassButtonText,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = GlassButtonText.copy(alpha = 0.6f),
            ),
            contentPadding = contentPadding,
            content = content,
        )
    }
}
