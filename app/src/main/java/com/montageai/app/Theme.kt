package com.montageai.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Warm cream + terracotta palette, serif headings: the same feel as the Claude app. */
object Clay {
    val Terracotta = Color(0xFFC96442)
    val TerracottaSoft = Color(0xFFD97757)
    val Warning = Color(0xFFB7791F)
}

private val LightColors = lightColorScheme(
    primary = Color(0xFFC96442),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF1DED3),
    onPrimaryContainer = Color(0xFF4A2314),
    secondary = Color(0xFF5E5D59),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E6DC),
    onSecondaryContainer = Color(0xFF141413),
    background = Color(0xFFF5F4ED),
    onBackground = Color(0xFF141413),
    surface = Color(0xFFFAF9F5),
    onSurface = Color(0xFF141413),
    surfaceVariant = Color(0xFFEFEDE3),
    onSurfaceVariant = Color(0xFF5E5D59),
    outline = Color(0xFFD3D0C2),
    outlineVariant = Color(0xFFE6E3D6),
    error = Color(0xFFB3412E),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD97757),
    onPrimary = Color(0xFF1F1E1D),
    primaryContainer = Color(0xFF4A2B20),
    onPrimaryContainer = Color(0xFFF6DDD2),
    secondary = Color(0xFFB8B5A9),
    onSecondary = Color(0xFF1F1E1D),
    secondaryContainer = Color(0xFF3A3936),
    onSecondaryContainer = Color(0xFFFAF9F5),
    background = Color(0xFF262624),
    onBackground = Color(0xFFFAF9F5),
    surface = Color(0xFF30302E),
    onSurface = Color(0xFFFAF9F5),
    surfaceVariant = Color(0xFF3A3936),
    onSurfaceVariant = Color(0xFFB8B5A9),
    outline = Color(0xFF55534E),
    outlineVariant = Color(0xFF42413D),
    error = Color(0xFFFF8A75),
    onError = Color(0xFF1F1E1D),
)

private val serif = FontFamily.Serif
private val base = Typography()

private val ClaudeTypography = base.copy(
    headlineLarge = TextStyle(fontFamily = serif, fontWeight = FontWeight.Medium, fontSize = 34.sp, lineHeight = 44.sp),
    headlineMedium = TextStyle(fontFamily = serif, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = serif, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = serif, fontWeight = FontWeight.Medium, fontSize = 21.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
)

@Composable
fun ClaudeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = ClaudeTypography,
        content = content,
    )
}
