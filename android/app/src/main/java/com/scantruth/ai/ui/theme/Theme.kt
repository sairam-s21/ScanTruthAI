package com.scantruth.ai.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = Brand.Primary,
    onPrimary = Color.White,
    primaryContainer = Brand.Soft,
    onPrimaryContainer = Brand.Deep,
    secondary = Brand.Secondary,
    onSecondary = Color.White,
    secondaryContainer = Brand.Soft,
    onSecondaryContainer = Brand.Deep,
    tertiary = Brand.Secondary,
    onTertiary = Color.White,
    tertiaryContainer = Brand.Soft,
    onTertiaryContainer = Brand.Deep,
    background = Brand.Background,
    onBackground = Brand.TextPrimary,
    surface = Brand.Card,
    onSurface = Brand.TextPrimary,
    surfaceVariant = Brand.Soft,
    onSurfaceVariant = Brand.TextSecondary,
    surfaceContainer = Brand.Card,
    surfaceContainerHigh = Brand.Card,
    surfaceContainerHighest = Brand.SoftRaised,
    surfaceContainerLow = Brand.Background,
    surfaceContainerLowest = Color.White,
    inverseSurface = Brand.Deep,
    inverseOnSurface = Brand.TextOnDeep,
    outline = Brand.Outline,
    outlineVariant = Brand.Divider,
    error = Brand.Danger,
    onError = Color.White,
    errorContainer = Brand.DangerSoft,
    onErrorContainer = Brand.OnDangerSoft,
    scrim = Color(0xCC0B3F32)
)

private val DarkColorScheme = darkColorScheme(
    primary = Brand.DarkPrimary,
    onPrimary = Brand.Deep,
    primaryContainer = Brand.DarkPrimaryContainer,
    onPrimaryContainer = Brand.Soft,
    secondary = Brand.Secondary,
    onSecondary = Color.White,
    secondaryContainer = Brand.DarkSurfaceVariant,
    onSecondaryContainer = Brand.Soft,
    tertiary = Brand.DarkPrimary,
    onTertiary = Brand.Deep,
    tertiaryContainer = Brand.DarkSurfaceVariant,
    onTertiaryContainer = Brand.Soft,
    background = Brand.DarkBackground,
    onBackground = Brand.DarkTextPrimary,
    surface = Brand.DarkSurface,
    onSurface = Brand.DarkTextPrimary,
    surfaceVariant = Brand.DarkSurfaceVariant,
    onSurfaceVariant = Brand.DarkTextSecondary,
    surfaceContainer = Brand.DarkSurface,
    surfaceContainerHigh = Brand.DarkSurfaceVariant,
    surfaceContainerHighest = Brand.DarkSurfaceVariant,
    surfaceContainerLow = Brand.DarkBackground,
    surfaceContainerLowest = Brand.DarkBackground,
    outline = Brand.DarkOutline,
    outlineVariant = Brand.DarkOutline,
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF3B1512),
    onErrorContainer = Color(0xFFF9DEDC)
)

/** Generous rounded cards and sheets, matching the reference layout. */
val ScanTruthShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun ScanTruthTheme(
    // Light only, like the website and the iOS app.
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = ScanTruthTypography,
        shapes = ScanTruthShapes,
        content = content
    )
}
