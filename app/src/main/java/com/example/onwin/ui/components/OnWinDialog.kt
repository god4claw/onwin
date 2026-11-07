package com.example.onwin.ui.components

import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.WindowManager.LayoutParams
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.shadow.Shadow as ShapeShadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.example.onwin.R
import com.example.onwin.ui.theme.GolosStyle
import com.example.onwin.ui.theme.ellipseGradient
import com.example.onwin.ui.theme.linearGradient

private val DialogRadius = 28.dp
private val DialogShape = RoundedCornerShape(DialogRadius)
private val DialogMargin = 18.dp
private val DialogButtonHeight = 44.dp
private val DialogButtonPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
private val DialogButtonStyle = GolosStyle.copy(fontSize = 16.sp, lineHeight = 19.2.sp)
internal val OnWinDialogSecondaryText = Color(0xFFCBD8E0)

internal fun Modifier.onWinDialogBackdrop() = drawWithContent {
    drawContent()
    drawRect(Color(0x80041322))
}

@Composable
internal fun OnWinDialog(
    title: String,
    icon: Painter,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    dismissEnabled: Boolean = true,
    dismissText: String = stringResource(R.string.dialog_cancel),
    content: @Composable () -> Unit,
) {
    val screenWidth = LocalWindowInfo.current.containerSize.width
    val width = with(LocalDensity.current) {
        minOf(350.dp, (screenWidth.toDp() - DialogMargin * 2).coerceAtLeast(1.dp))
    }
    Dialog(
        onDismissRequest = { if (dismissEnabled) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = dismissEnabled,
            dismissOnClickOutside = dismissEnabled,
            windowTitle = title,
        ),
    ) {
        ConfigureDialogWindow(width)
        Column(
            modifier = Modifier
                .width(width)
                .layout { measurable, constraints ->
                    val maxHeight = if (constraints.hasBoundedHeight) {
                        (constraints.maxHeight - (DialogMargin * 2).roundToPx())
                            .coerceAtLeast(constraints.minHeight)
                    } else {
                        constraints.maxHeight
                    }
                    val panel = measurable.measure(constraints.copy(maxHeight = maxHeight))
                    layout(panel.width, panel.height) { panel.placeRelative(0, 0) }
                }
                .dropShadow(
                    DialogShape,
                    ShapeShadow(radius = 55.dp, color = Color(0x8800101E), offset = DpOffset(0.dp, 22.dp)),
                )
                .clip(DialogShape)
                .dialogFill()
                .border(1.dp, Color(0x44BDDFF3), DialogShape)
                .verticalScroll(rememberScrollState())
                .padding(start = 23.dp, top = 24.dp, end = 23.dp, bottom = 23.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DialogIcon(icon, destructive)
            Spacer(Modifier.height(14.dp))
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onSurface,
                style = GolosStyle.copy(fontSize = 25.sp, lineHeight = 30.sp, letterSpacing = (-0.5).sp),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(if (destructive) 14.dp else 20.dp))
            content()
            Spacer(Modifier.height(if (destructive) 22.dp else 19.dp))
            DialogActions(confirmText, dismissText, onConfirm, onDismiss, destructive, confirmEnabled, dismissEnabled)
        }
    }
}

@Composable
private fun ConfigureDialogWindow(width: Dp) {
    val window = (LocalView.current.parent as DialogWindowProvider).window
    val density = LocalDensity.current
    val background = remember(density) {
        GradientDrawable().apply { cornerRadius = with(density) { DialogRadius.toPx() } }
    }
    SideEffect {
        if (window.decorView.background !== background) window.setBackgroundDrawable(background)
        window.setDimAmount(0f)
        @Suppress("DEPRECATION")
        window.setSoftInputMode(LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val widthPx = with(density) { width.roundToPx() }
        if (window.attributes.width != widthPx) window.setLayout(widthPx, LayoutParams.WRAP_CONTENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply {
                blurBehindRadius = with(density) { 7.dp.roundToPx() }
            }
            window.setBackgroundBlurRadius(with(density) { 18.dp.roundToPx() })
        }
    }
}

private fun Modifier.dialogFill() = drawWithCache {
    val fill = size.linearGradient(150f, 0f to Color(0xEF173C58), 0.75f to Color(0xF709283C))
    val glow = size.ellipseGradient(
        0.12f, 0f, 1.2445f, 1.4142f, 0f to Color(0x384D7B9C), 0.68f to Color(0x004D7B9C),
    )
    val highlight = size.linearGradient(
        180f,
        0f to Color(0x24E1F3FF),
        (2.dp.toPx() / size.height.coerceAtLeast(1f)).coerceAtMost(1f) to Color.Transparent,
    )
    onDrawBehind {
        drawRect(fill)
        drawRect(glow)
        drawRect(highlight)
    }
}

@Composable
private fun DialogIcon(icon: Painter, destructive: Boolean) {
    val shape = RoundedCornerShape(15.dp)
    val start = if (destructive) Color(0x50FF6E66) else Color(0x40FFAD62)
    val end = if (destructive) Color(0x15FF625F) else Color(0x1AFE7522)
    val outline = if (destructive) Color(0x48FF8277) else Color(0x4AFFBF83)
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(shape)
            .drawWithCache {
                val fill = size.linearGradient(150f, 0f to start, 1f to end)
                onDrawBehind { drawRect(fill) }
            }
            .border(1.dp, outline, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            modifier = Modifier.size(23.dp),
            tint = if (destructive) Color(0xFFFFA39A) else Color(0xFFFFD3A9),
        )
    }
}

@Composable
private fun DialogActions(
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean,
    confirmEnabled: Boolean,
    dismissEnabled: Boolean,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassButton(
                text = dismissText,
                onClick = onDismiss,
                modifier = Modifier.weight(1f).heightIn(min = DialogButtonHeight).fillMaxHeight(),
                textStyle = DialogButtonStyle,
                contentPadding = DialogButtonPadding,
                enabled = dismissEnabled,
            )
            GradientButton(
                onClick = onConfirm,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                enabled = confirmEnabled,
                destructive = destructive,
                minHeight = DialogButtonHeight,
                contentPadding = DialogButtonPadding,
            ) {
                Text(
                    text = confirmText,
                    style = DialogButtonStyle.copy(
                        shadow = Shadow(
                            color = if (destructive) Color(0x8065242B) else Color(0x659F400E),
                            offset = with(LocalDensity.current) { androidx.compose.ui.geometry.Offset(0f, 1.dp.toPx()) },
                            blurRadius = with(LocalDensity.current) { 4.dp.toPx() },
                        ),
                    ),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
