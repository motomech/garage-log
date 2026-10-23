package com.motomech.garagelog.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Colors from the simulator: warm paper background, burnt-orange accent, teal for "ok".
@Immutable
data class GColors(
    val bg: Color, val card: Color, val line: Color, val ink: Color, val muted: Color, val chip: Color,
    val accent: Color, val okBg: Color, val ok: Color, val warnBg: Color, val warn: Color, val badBg: Color, val bad: Color,
    val catFuel: Color, val catService: Color, val catParts: Color, val catOther: Color,
)

private val Light = GColors(
    bg = Color(0xFFF3F0E8), card = Color(0xFFFFFFFF), line = Color(0xFFDDD8CC), ink = Color(0xFF1C1B19),
    muted = Color(0xFF5E5B54), chip = Color(0xFFECE8DD), accent = Color(0xFFC2410C),
    okBg = Color(0xFFD9EFEC), ok = Color(0xFF0B5E57), warnBg = Color(0xFFFBE6DA), warn = Color(0xFF9A3412),
    badBg = Color(0xFFF8D7D3), bad = Color(0xFF9B1C1C),
    catFuel = Color(0xFFC2410C), catService = Color(0xFF0B5E57), catParts = Color(0xFF3B5B8C), catOther = Color(0xFF7A6F5A),
)

private val Dark = GColors(
    bg = Color(0xFF17161A), card = Color(0xFF222126), line = Color(0xFF38363C), ink = Color(0xFFECE8DD),
    muted = Color(0xFFA8A396), chip = Color(0xFF2C2B31), accent = Color(0xFFF07A3E),
    okBg = Color(0xFF173B37), ok = Color(0xFF7FD3C8), warnBg = Color(0xFF4A2A18), warn = Color(0xFFF5B48A),
    badBg = Color(0xFF4A1C1C), bad = Color(0xFFF4A3A3),
    catFuel = Color(0xFFF07A3E), catService = Color(0xFF3FB3A6), catParts = Color(0xFF7C9CD0), catOther = Color(0xFFB3A688),
)

val LocalG = staticCompositionLocalOf { Light }
val G: GColors @Composable get() = LocalG.current

@Composable
fun GarageTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val g = if (dark) Dark else Light
    val scheme = if (dark) darkColorScheme(
        primary = g.accent, onPrimary = Color.Black, background = g.bg, surface = g.card, onSurface = g.ink,
        onBackground = g.ink, surfaceVariant = g.chip, onSurfaceVariant = g.muted, outline = g.line,
        secondaryContainer = g.okBg, onSecondaryContainer = g.ok, surfaceContainer = g.card,
        surfaceContainerLow = g.card, surfaceContainerHigh = g.card, surfaceContainerHighest = g.chip,
    ) else lightColorScheme(
        primary = g.accent, onPrimary = Color.White, background = g.bg, surface = g.card, onSurface = g.ink,
        onBackground = g.ink, surfaceVariant = g.chip, onSurfaceVariant = g.muted, outline = g.line,
        secondaryContainer = g.okBg, onSecondaryContainer = g.ok, surfaceContainer = g.card,
        surfaceContainerLow = g.card, surfaceContainerHigh = g.card, surfaceContainerHighest = g.chip,
    )
    CompositionLocalProvider(LocalG provides g) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
