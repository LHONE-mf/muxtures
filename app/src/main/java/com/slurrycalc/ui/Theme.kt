package com.slurrycalc.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/** Фирменная палитра: графитово-бирюзовая (лаборатория / батарейная тематика) */
private val LightColors = lightColorScheme(
    primary = Color(0xFF00696D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF6FF6FE),
    onPrimaryContainer = Color(0xFF002021),
    secondary = Color(0xFF4B6366),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE8EB),
    onSecondaryContainer = Color(0xFF051F21),
    tertiary = Color(0xFF8A5200),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCB0),
    onTertiaryContainer = Color(0xFF2C1700),
    background = Color(0xFFF6FAFA),
    onBackground = Color(0xFF191C1D),
    surface = Color(0xFFF6FAFA),
    onSurface = Color(0xFF191C1D),
    surfaceVariant = Color(0xFFDAE4E5),
    onSurfaceVariant = Color(0xFF3F4849),
    outline = Color(0xFF6F797A),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4DD9E0),
    onPrimary = Color(0xFF003739),
    primaryContainer = Color(0xFF004F52),
    onPrimaryContainer = Color(0xFF6FF6FE),
    secondary = Color(0xFFB1CBCE),
    onSecondary = Color(0xFF1C3537),
    secondaryContainer = Color(0xFF334B4E),
    onSecondaryContainer = Color(0xFFCDE8EB),
    tertiary = Color(0xFFFFB95B),
    onTertiary = Color(0xFF482A00),
    tertiaryContainer = Color(0xFF683F00),
    onTertiaryContainer = Color(0xFFFFDCB0),
    background = Color(0xFF191C1D),
    onBackground = Color(0xFFE1E8E8),
    surface = Color(0xFF191C1D),
    onSurface = Color(0xFFE1E8E8),
    surfaceVariant = Color(0xFF3F4849),
    onSurfaceVariant = Color(0xFFBFC8C9),
    outline = Color(0xFF899393),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/**
 * Шкала типографики с учётом пользовательского множителя размера шрифта.
 * Sizes — базовые значения Material 3; LocalDensity перемасштабирует sp.
 */
object SlurryTypography {
    val Base = Typography(
        displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 36.sp, fontWeight = FontWeight.Normal, lineHeight = 44.sp),
        headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, lineHeight = 36.sp),
        headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp),
        titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp),
        titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp),
        titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
        bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
        bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp),
        labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
        labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
        labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
    )
}

val LocalFontScale = staticCompositionLocalOf { 1.0f }

@Composable
fun SlurryTheme(
    mode: AppThemeMode,
    fontScale: Float,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    val colors = if (dark) DarkColors else LightColors
    // масштабируем и системный шрифт тоже — интерфейс остаётся гармоничным
    val density = LocalDensity.current
    val scaledDensity = Density(density.density, density.fontScale * fontScale)
    androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides scaledDensity) {
        MaterialTheme(
            colorScheme = colors,
            typography = SlurryTypography.Base,
            shapes = MaterialTheme.shapes,
            content = content,
        )
    }
}
