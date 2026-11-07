package com.example.onwin.ui.theme

import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.onwin.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private val Orange = Color(0xFFFE7522)
private val Blue = Color(0xFF0E3655)
private val Ivory = Color(0xFFFFF9F0)
private val SecondaryText = Color(0xFFE5E6E4)
internal val WinSurfaceOutline = Color(0x22B8E5F3)
internal val GradientButtonOutline = Color(0x50FFD0A8)
internal val DestructiveButtonOutline = Color(0x68FFAAA5)
private val PanelRadius = 32.dp
internal val OnWinPanelShape = RoundedCornerShape(topStart = PanelRadius, topEnd = PanelRadius)

private val OnWinColors = darkColorScheme(
    primary = Orange,
    onPrimary = Ivory,
    background = Blue,
    onBackground = Ivory,
    surface = Blue,
    onSurface = Ivory,
    onSurfaceVariant = SecondaryText,
)

private val PlayfairDisplay = FontFamily(Font(R.font.playfair_display))
internal val PlayfairStyle = TextStyle(
    fontFamily = PlayfairDisplay,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)
internal val GolosStyle = PlayfairStyle.copy(fontFamily = GolosText)

private val WelcomeTypography = Typography.copy(
    displayLarge = PlayfairStyle.copy(fontSize = 57.sp, lineHeight = 61.sp, letterSpacing = (-2.5).sp),
    headlineMedium = PlayfairStyle.copy(fontSize = 30.sp, lineHeight = 34.5.sp, letterSpacing = (-0.85).sp),
    bodyMedium = GolosStyle.copy(fontSize = 15.sp, lineHeight = 22.5.sp),
    labelLarge = GolosStyle.copy(fontSize = 21.sp, lineHeight = 25.2.sp),
)

@Composable
internal fun WelcomeTheme(content: @Composable () -> Unit) =
    OnWinTheme(typography = WelcomeTypography, content = content)

@Composable
internal fun OnWinTheme(
    typography: Typography,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = OnWinColors,
        typography = typography,
        shapes = Shapes(large = OnWinPanelShape, extraLarge = CircleShape),
        content = content,
    )
}

internal fun Modifier.onWinAtmosphere(viewport: DpSize? = null) = drawWithCache {
    val fullSize = viewport?.let { Size(it.width.toPx(), it.height.toPx()) } ?: size
    val atmosphere = listOf(
        fullSize.linearGradient(
            degrees = 166f,
            0.02f to Color(0xFFFF7928), 0.28f to Color(0xFFD65A29), 0.39f to Color(0xFF875043),
            0.54f to Color(0xFF21465F), 0.72f to Blue, 1f to Color(0xFF0A2C46),
        ),
        fullSize.ellipseGradient(0.06f, 0.49f, 0.86f, 0.34f, 0f to Color(0xFF1D5C76), 0.76f to Color(0x00194E6B)),
        fullSize.ellipseGradient(
            0.96f, 0.23f, 0.83f, 0.33f,
            0f to Color(0xFFF86B20), 0.43f to Color(0xFFBC532C), 0.78f to Color(0x00A84A2D),
        ),
        fullSize.ellipseGradient(
            -0.12f, 0.11f, 1f, 0.44f,
            0.10f to Color(0xFFFF7A20), 0.45f to Color(0xFFF4661D), 0.85f to Color(0x00DC4D21),
        ),
        fullSize.ellipseGradient(1.03f, 0.01f, 0.95f, 0.35f, 0f to Color(0xFFFFAD64), 0.80f to Color(0x00FF9A45)),
    )
    onDrawBehind {
        drawRect(Blue)
        atmosphere.forEach { drawRect(it) }
        drawRect(Grain, alpha = 0.0585f, blendMode = BlendMode.Softlight)
    }
}

/**
 * Общая заливка карточек и панелей: одинаковые цвета, прозрачность и верхняя подсветка.
 * Прозрачность взята по карточкам (81–92 %), чтобы фон меньше просвечивал под текстом.
 */
internal fun Modifier.onWinSurfaceFill() = drawWithCache {
    val fill = size.linearGradient(
        degrees = 145f,
        0f to Color(0xCE12394D), 0.45f to Color(0xDB09283C), 1f to Color(0xEB061E30),
    )
    val highlight = size.linearGradient(
        degrees = 180f,
        0f to Color(0x12E3F7FF), (1.dp.toPx() / size.height.coerceAtLeast(1f)).coerceAtMost(1f) to Color.Transparent,
    )
    onDrawBehind {
        drawRect(fill)
        drawRect(highlight)
    }
}

internal fun Modifier.onWinPanelBackground() = onWinSurfaceFill().drawWithCache {
    val stroke = 1.dp.toPx()
    val radius = PanelRadius.toPx()
    onDrawBehind {
        // Продлеваем контур ниже области отсечения, чтобы оставить низ панели открытым.
        drawRoundRect(
            color = WinSurfaceOutline,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size((size.width - stroke).coerceAtLeast(0f), size.height + radius),
            cornerRadius = CornerRadius(radius - stroke / 2),
            style = Stroke(stroke),
        )
    }
}

internal fun Modifier.gradientButtonBackground(destructive: Boolean) = drawWithCache {
    val button = if (destructive) listOf(
        size.linearGradient(
            degrees = 180f,
            0f to Color(0xFFDE6863), 0.65f to Color(0xFFB43C41), 1f to Color(0xFFBA4548),
        ),
        size.ellipseGradient(1f, 0f, 0.38f, 0.86f, 0f to Color(0x45FFAFA4), 1f to Color(0x00FFAFA4)),
        size.ellipseGradient(0.13f, 0f, 0.24f, 0.78f, 0f to Color(0x4AFFD3C2), 1f to Color(0x00FFD3C2)),
        size.linearGradient(
            degrees = 180f,
            0f to Color(0x2EFFFFFF), 0.14f to Color.Transparent,
            0.86f to Color.Transparent, 1f to Color(0x24FFD2CB),
        ),
    ) else listOf(
        size.linearGradient(
            degrees = 180f,
            0f to Color(0xFFFF9C4B), 0.33f to Orange, 0.65f to Color(0xFFE9570D), 1f to Color(0xFFF77B2B),
        ),
        size.ellipseGradient(0.5f, 1.1f, 0.60f, 0.36f, 0f to Color(0x40FFBC82), 1f to Color(0x00FF9B59)),
        size.ellipseGradient(1f, 0f, 0.32f, 0.8f, 0f to Color(0x50FFDBB3), 1f to Color(0x00FFC18A)),
        size.ellipseGradient(0.15f, 0f, 0.16f, 0.7f, 0f to Color(0x48FFF0D8), 1f to Color(0x00FFCCA1)),
        size.linearGradient(
            degrees = 180f,
            0f to Color(0x28FFFFFF), 0.12f to Color.Transparent,
            0.88f to Color.Transparent, 1f to Color(0x32FFD2AB),
        ),
    )
    onDrawBehind { button.forEach { drawRect(it) } }
}

private val Grain by lazy {
    val tileSize = 180
    val random = Random(0)
    val pixels = IntArray(tileSize * tileSize) {
        val shade = random.nextInt(256)
        android.graphics.Color.rgb(shade, shade, shade)
    }
    val tile = Bitmap.createBitmap(pixels, tileSize, tileSize, Bitmap.Config.ARGB_8888)
    ShaderBrush(ImageShader(tile.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

// CSS задаёт угол от вертикали. Длина оси равна проекции прямоугольника на неё.
internal fun Size.linearGradient(degrees: Float, vararg stops: Pair<Float, Color>): ShaderBrush {
    val radians = Math.toRadians(degrees.toDouble())
    val direction = Offset(sin(radians).toFloat(), -cos(radians).toFloat())
    val halfLength = (width * abs(direction.x) + height * abs(direction.y)).coerceAtLeast(1f) / 2
    val center = Offset(width / 2, height / 2)
    val start = center - direction * halfLength
    val end = center + direction * halfLength
    return ShaderBrush(
        LinearGradient(
            start.x, start.y, end.x, end.y,
            IntArray(stops.size) { stops[it].second.toArgb() },
            FloatArray(stops.size) { stops[it].first },
            Shader.TileMode.CLAMP,
        )
    )
}

internal fun Size.ellipseGradient(
    centerX: Float,
    centerY: Float,
    radiusX: Float,
    radiusY: Float,
    vararg stops: Pair<Float, Color>,
): ShaderBrush {
    val shader = RadialGradient(
        0f, 0f, 1f,
        IntArray(stops.size) { stops[it].second.toArgb() },
        FloatArray(stops.size) { stops[it].first },
        Shader.TileMode.CLAMP,
    )
    shader.setLocalMatrix(Matrix().apply {
        setScale((width * radiusX).coerceAtLeast(1f), (height * radiusY).coerceAtLeast(1f))
        postTranslate(width * centerX, height * centerY)
    })
    return ShaderBrush(shader)
}
