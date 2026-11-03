package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

data class AppColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val primary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val isDark: Boolean
)

val LocalAppColors = staticCompositionLocalOf {
    AppColors(
        background = LightBackground,
        surface = LightSurface,
        surfaceVariant = LightSurfaceVariant,
        border = LightBorder,
        textPrimary = LightTextPrimary,
        textSecondary = LightTextSecondary,
        primary = LightPrimary,
        primaryContainer = LightPrimaryContainer,
        onPrimaryContainer = LightOnPrimaryContainer,
        isDark = false
    )
}

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = Color(0xFF0A0E1A),
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onBackground = DarkTextPrimary,
    onSurface = DarkTextPrimary,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkBorder,
    outlineVariant = DarkBorder.copy(alpha = 0.5f)
)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    onBackground = LightTextPrimary,
    onSurface = LightTextPrimary,
    onSurfaceVariant = LightTextSecondary,
    outline = LightBorder,
    outlineVariant = LightBorder.copy(alpha = 0.5f)
)

@Composable
fun MyApplicationTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val customColors = if (darkTheme) {
        AppColors(
            background = DarkBackground,
            surface = DarkSurface,
            surfaceVariant = DarkSurfaceVariant,
            border = DarkBorder,
            textPrimary = DarkTextPrimary,
            textSecondary = DarkTextSecondary,
            primary = DarkPrimary,
            primaryContainer = DarkPrimaryContainer,
            onPrimaryContainer = DarkOnPrimaryContainer,
            isDark = true
        )
    } else {
        AppColors(
            background = LightBackground,
            surface = LightSurface,
            surfaceVariant = LightSurfaceVariant,
            border = LightBorder,
            textPrimary = LightTextPrimary,
            textSecondary = LightTextSecondary,
            primary = LightPrimary,
            primaryContainer = LightPrimaryContainer,
            onPrimaryContainer = LightOnPrimaryContainer,
            isDark = false
        )
    }

    CompositionLocalProvider(LocalAppColors provides customColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = Typography,
            content = content
        )
    }
}
