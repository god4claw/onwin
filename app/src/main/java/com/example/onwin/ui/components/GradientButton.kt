package com.example.onwin.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.onwin.ui.theme.GradientButtonOutline
import com.example.onwin.ui.theme.DestructiveButtonOutline
import com.example.onwin.ui.theme.gradientButtonBackground

@Composable
internal fun GradientButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    minHeight: Dp = 60.dp,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.extraLarge
    val contentColor = MaterialTheme.colorScheme.onPrimary
    val outline = if (destructive) DestructiveButtonOutline else GradientButtonOutline
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = minHeight)
            .shadow(8.dp, shape, clip = true)
            .gradientButtonBackground(destructive)
            .border(1.dp, outline, shape),
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            contentColor = contentColor,
            disabledContentColor = contentColor,
        ),
        contentPadding = contentPadding,
        content = content,
    )
}
