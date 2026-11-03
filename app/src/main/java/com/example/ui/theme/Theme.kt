package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

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
    val isDark: Boolean = true
)

val LocalAppColors = staticCompositionLocalOf {
    AppColors(
        background = CuktechGunmetalBackground,
        surface = CuktechGunmetalSurface,
        surfaceVariant = CuktechGunmetalSurfaceVariant,
        border = CuktechGunmetalBorder,
        textPrimary = DarkTextPrimary,
        textSecondary = DarkTextSecondary,
        primary = CuktechGunmetalPrimary,
        primaryContainer = CuktechGunmetalPrimaryContainer,
        onPrimaryContainer = CuktechGunmetalOnPrimaryContainer,
        isDark = true
    )
}

@Composable
fun MyApplicationTheme(
    palette: DarkThemePalette = DarkThemePalette.CUKTECH_CYAN,
    content: @Composable () -> Unit
) {
    val customColors = when (palette) {
        DarkThemePalette.CUKTECH_CYAN -> AppColors(
            background = CuktechGunmetalBackground,
            surface = CuktechGunmetalSurface,
            surfaceVariant = CuktechGunmetalSurfaceVariant,
            border = CuktechGunmetalBorder,
            textPrimary = DarkTextPrimary,
            textSecondary = DarkTextSecondary,
            primary = CuktechGunmetalPrimary,
            primaryContainer = CuktechGunmetalPrimaryContainer,
            onPrimaryContainer = CuktechGunmetalOnPrimaryContainer,
            isDark = true
        )
        DarkThemePalette.AMOLED_BLACK -> AppColors(
            background = AmoledBackground,
            surface = AmoledSurface,
            surfaceVariant = AmoledSurfaceVariant,
            border = AmoledBorder,
            textPrimary = DarkTextPrimary,
            textSecondary = DarkTextSecondary,
            primary = AmoledPrimary,
            primaryContainer = AmoledPrimaryContainer,
            onPrimaryContainer = AmoledOnPrimaryContainer,
            isDark = true
        )
        DarkThemePalette.CYBERPUNK_NEON -> AppColors(
            background = CyberpunkBackground,
            surface = CyberpunkSurface,
            surfaceVariant = CyberpunkSurfaceVariant,
            border = CyberpunkBorder,
            textPrimary = DarkTextPrimary,
            textSecondary = DarkTextSecondary,
            primary = CyberpunkPrimary,
            primaryContainer = CyberpunkPrimaryContainer,
            onPrimaryContainer = CyberpunkOnPrimaryContainer,
            isDark = true
        )
        DarkThemePalette.TITANIUM_AMBER -> AppColors(
            background = TitaniumBackground,
            surface = TitaniumSurface,
            surfaceVariant = TitaniumSurfaceVariant,
            border = TitaniumBorder,
            textPrimary = DarkTextPrimary,
            textSecondary = DarkTextSecondary,
            primary = TitaniumPrimary,
            primaryContainer = TitaniumPrimaryContainer,
            onPrimaryContainer = TitaniumOnPrimaryContainer,
            isDark = true
        )
        DarkThemePalette.DEEP_OCEAN_BLUE -> AppColors(
            background = OceanBackground,
            surface = OceanSurface,
            surfaceVariant = OceanSurfaceVariant,
            border = OceanBorder,
            textPrimary = DarkTextPrimary,
            textSecondary = DarkTextSecondary,
            primary = OceanPrimary,
            primaryContainer = OceanPrimaryContainer,
            onPrimaryContainer = OceanOnPrimaryContainer,
            isDark = true
        )
    }

    val colorScheme = darkColorScheme(
        primary = customColors.primary,
        onPrimary = Color(0xFF0A0E1A),
        primaryContainer = customColors.primaryContainer,
        onPrimaryContainer = customColors.onPrimaryContainer,
        background = customColors.background,
        surface = customColors.surface,
        surfaceVariant = customColors.surfaceVariant,
        onBackground = DarkTextPrimary,
        onSurface = DarkTextPrimary,
        onSurfaceVariant = DarkTextSecondary,
        outline = customColors.border,
        outlineVariant = customColors.border.copy(alpha = 0.5f)
    )

    CompositionLocalProvider(LocalAppColors provides customColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

