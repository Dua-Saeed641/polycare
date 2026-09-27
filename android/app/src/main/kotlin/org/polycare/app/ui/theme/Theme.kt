package org.polycare.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * PolyCare's look follows the repo banner: warm paper background, soft pink/red orbs,
 * a deep plum clover and Tenor Sans. The app is light-only, like the brand.
 */
private val BrandColors = lightColorScheme(
    primary = Brand.Plum,
    onPrimary = Brand.Paper,
    primaryContainer = Brand.Blush,
    onPrimaryContainer = Brand.PlumDeep,
    secondary = Brand.Rose,
    onSecondary = Brand.Paper,
    secondaryContainer = Brand.PinkMist,
    onSecondaryContainer = Brand.PlumDeep,
    tertiary = Brand.Magenta,
    onTertiary = Brand.Paper,
    background = Brand.Paper,
    onBackground = Brand.Ink,
    surface = Brand.Paper,
    onSurface = Brand.Ink,
    surfaceVariant = Brand.PinkMist,
    onSurfaceVariant = Brand.InkMuted,
    surfaceContainerLowest = Brand.White,
    surfaceContainerLow = Brand.White,
    surfaceContainer = Brand.White,
    surfaceContainerHigh = Brand.PinkMist,
    outline = Brand.Line,
    outlineVariant = Brand.LineSoft,
    error = Brand.Red,
)

private val BrandShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun PolyCareTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BrandColors,
        typography = BrandTypography,
        shapes = BrandShapes,
        content = content,
    )
}
