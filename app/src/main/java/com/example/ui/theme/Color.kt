package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Light Theme Palette (Sleek Brushed Titanium & CUKTECH Hardware - White & Cyan)
val LightBackground = Color(0xFFF8FAFC)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFEDF2F7)
val LightBorder = Color(0xFFCBD5E1)
val LightTextPrimary = Color(0xFF0F172A)
val LightTextSecondary = Color(0xFF475569)
val LightPrimary = Color(0xFF007A87) // Refined Deep High-Contrast Cyan for Light Mode readability
val LightPrimaryContainer = Color(0xFFE0F7FA)
val LightOnPrimaryContainer = Color(0xFF004D56)

// =============================================================================
// DARK THEME PALETTES (Dark Mode Only)
// =============================================================================

enum class DarkThemePalette(
    val label: String,
    val description: String,
    val primaryColor: Color
) {
    CUKTECH_CYAN(
        "CUKTECH Gunmetal",
        "Classic charger look: deep navy gunmetal with balanced cyan accents",
        Color(0xFF00ADB5)
    ),
    AMOLED_BLACK(
        "AMOLED Pure Black",
        "True #000000 black background with soothing soft electric cyan",
        Color(0xFF00C4CC)
    ),
    CYBERPUNK_NEON(
        "Cyberpunk Neon",
        "High-tech midnight obsidian with neon emerald green accents",
        Color(0xFF00E676)
    ),
    TITANIUM_AMBER(
        "Titanium Amber",
        "Warm dark titanium with industrial hazard amber/gold accents",
        Color(0xFFFFB300)
    ),
    DEEP_OCEAN_BLUE(
        "Deep Ocean Blue",
        "Subtle midnight navy with cool sapphire blue accents",
        Color(0xFF38BDF8)
    )
}

// CUKTECH Gunmetal Default (Balanced, pleasant, not blinding)
val CuktechGunmetalBackground = Color(0xFF0B0E14)
val CuktechGunmetalSurface = Color(0xFF131722)
val CuktechGunmetalSurfaceVariant = Color(0xFF1C2232)
val CuktechGunmetalBorder = Color(0xFF283246)
val CuktechGunmetalPrimary = Color(0xFF00ADB5) // Balanced, pleasant CUKTECH cyan
val CuktechGunmetalPrimaryContainer = Color(0xFF0A333A)
val CuktechGunmetalOnPrimaryContainer = Color(0xFF8CEFF5)

// AMOLED Pure Black
val AmoledBackground = Color(0xFF000000)
val AmoledSurface = Color(0xFF0A0A0E)
val AmoledSurfaceVariant = Color(0xFF14141A)
val AmoledBorder = Color(0xFF22222C)
val AmoledPrimary = Color(0xFF00C4CC)
val AmoledPrimaryContainer = Color(0xFF072B2E)
val AmoledOnPrimaryContainer = Color(0xFF90F7FC)

// Cyberpunk Neon
val CyberpunkBackground = Color(0xFF080C0A)
val CyberpunkSurface = Color(0xFF0F1713)
val CyberpunkSurfaceVariant = Color(0xFF17241D)
val CyberpunkBorder = Color(0xFF22362A)
val CyberpunkPrimary = Color(0xFF00E676)
val CyberpunkPrimaryContainer = Color(0xFF0A331E)
val CyberpunkOnPrimaryContainer = Color(0xFFA7FCD0)

// Titanium Amber
val TitaniumBackground = Color(0xFF0E0D0C)
val TitaniumSurface = Color(0xFF181614)
val TitaniumSurfaceVariant = Color(0xFF24201D)
val TitaniumBorder = Color(0xFF38322D)
val TitaniumPrimary = Color(0xFFFFB300)
val TitaniumPrimaryContainer = Color(0xFF3B2A05)
val TitaniumOnPrimaryContainer = Color(0xFFFFE082)

// Deep Ocean Blue
val OceanBackground = Color(0xFF080E18)
val OceanSurface = Color(0xFF0E1726)
val OceanSurfaceVariant = Color(0xFF17253B)
val OceanBorder = Color(0xFF233654)
val OceanPrimary = Color(0xFF38BDF8)
val OceanPrimaryContainer = Color(0xFF0C2C45)
val OceanOnPrimaryContainer = Color(0xFFBAE6FD)

// Base Dark Text Colors
val DarkTextPrimary = Color(0xFFF1F5F9)
val DarkTextSecondary = Color(0xFF94A3B8)

// Status Colors
val StatusSuccess = Color(0xFF22C55E)
val StatusWarning = Color(0xFFF59E0B)
val StatusError = Color(0xFFEF4444)
val StatusErrorContainer = Color(0xFF450A0A)
val StatusOnErrorContainer = Color(0xFFFCA5A5)
