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
    val (bg, surf, surfVar, bord, prim, primCont, onPrimCont) = when (palette) {
        DarkThemePalette.CUKTECH_CYAN -> {
            listOf(
                CuktechGunmetalBackground,
                CuktechGunmetalSurface,
                CuktechGunmetalSurfaceVariant,
                CuktechGunmetalBorder,
                CuktechGunmetalPrimary,
                CuktechGunmetalPrimaryContainer,
                CuktechGunmetalOnPrimaryContainer
            )
        }
        DarkThemePalette.AMOLED_BLACK -> {
            listOf(
                AmoledBackground,
                AmoledSurface,
                AmoledSurfaceVariant,
                AmoledBorder,
                AmoledPrimary,
                AmoledPrimaryContainer,
                AmoledOnPrimaryContainer
            )
        }
        DarkThemePalette.CYBERPUNK_NEON -> {
            listOf(
                CyberpunkBackground,
                CyberpunkSurface,
                CyberpunkSurfaceVariant,
                CyberpunkBorder,
                CyberpunkPrimary,
                CyberpunkPrimaryContainer,
                CyberpunkOnPrimaryContainer
            )
        }
        DarkThemePalette.TITANIUM_AMBER -> {
            listOf(
                TitaniumBackground,
                TitaniumSurface,
                TitaniumSurfaceVariant,
                TitaniumBorder,
                TitaniumPrimary,
                TitaniumPrimaryContainer,
                TitaniumOnPrimaryContainer
            )
        }
        DarkThemePalette.DEEP_OCEAN_BLUE -> {
            listOf(
                OceanBackground,
                OceanSurface,
                OceanSurfaceVariant,
                OceanBorder,
                OceanPrimary,
                OceanPrimaryContainer,
                OceanOnPrimaryContainer
            )
        }
    }

    val customColors = AppColors(
        background = bg,
        surface = surf,
        surfaceVariant = surfVar,
        border = bord,
        textPrimary = DarkTextPrimary,
        textSecondary = DarkTextSecondary,
        primary = prim,
        primaryContainer = primCont,
        onPrimaryContainer = onPrimCont,
        isDark = true
    )

    val colorScheme = darkColorScheme(
        primary = prim,
        onPrimary = Color(0xFF0A0E1A),
        primaryContainer = primCont,
        onPrimaryContainer = onPrimCont,
        background = bg,
        surface = surf,
        surfaceVariant = surfVar,
        onBackground = DarkTextPrimary,
        onSurface = DarkTextPrimary,
        onSurfaceVariant = DarkTextSecondary,
        outline = bord,
        outlineVariant = bord.copy(alpha = 0.5f)
    )

    CompositionLocalProvider(LocalAppColors provides customColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

