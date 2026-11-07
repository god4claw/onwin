package com.example.onwin.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.example.onwin.R

internal val GolosText = FontFamily(Font(R.font.golos_text))

private val MaterialTypography = Typography()
val Typography = MaterialTypography.copy(
    headlineSmall = MaterialTypography.headlineSmall.copy(fontFamily = GolosText),
    bodyLarge = MaterialTypography.bodyLarge.copy(fontFamily = GolosText),
    bodyMedium = MaterialTypography.bodyMedium.copy(fontFamily = GolosText),
    labelLarge = MaterialTypography.labelLarge.copy(fontFamily = GolosText),
)
