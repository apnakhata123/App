package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val BlueWhiteLightColorScheme = lightColorScheme(
    primary = RoyalBlue,
    onPrimary = PureWhite,
    primaryContainer = IceBlueContainer,
    onPrimaryContainer = DeepNavyInk,
    secondary = AzureBlue,
    onSecondary = PureWhite,
    secondaryContainer = IceBlueSurface,
    onSecondaryContainer = DeepCobalt,
    tertiary = ElectricSky,
    onTertiary = PureWhite,
    tertiaryContainer = MistBlueCard,
    onTertiaryContainer = DeepNavyInk,
    background = FrostWhite,
    onBackground = DeepNavyInk,
    surface = PureWhite,
    onSurface = DeepNavyInk,
    surfaceVariant = IceBlueSurface,
    onSurfaceVariant = SlateBlueInk,
    surfaceContainerLowest = PureWhite,
    surfaceContainerLow = FrostWhite,
    surfaceContainer = IceBlueSurface,
    surfaceContainerHigh = MistBlueCard,
    outline = BlueOutline,
    outlineVariant = BlueHairline,
    error = ErrorRed,
    onError = PureWhite,
    errorContainer = ErrorContainerLight,
)

private val BlueNavyDarkColorScheme = darkColorScheme(
    primary = SkyBlueDark,
    onPrimary = DeepCobalt,
    primaryContainer = CobaltContainerDark,
    onPrimaryContainer = CrispIceText,
    secondary = AzureBlue,
    onSecondary = PureWhite,
    secondaryContainer = NavySurfaceElevated,
    onSecondaryContainer = CrispIceText,
    tertiary = ElectricSky,
    onTertiary = PureWhite,
    background = DeepOceanBackground,
    onBackground = CrispIceText,
    surface = NavySurface,
    onSurface = CrispIceText,
    surfaceVariant = NavySurfaceElevated,
    onSurfaceVariant = MutedIceText,
    surfaceContainerLowest = DeepOceanBackground,
    surfaceContainerLow = NavySurface,
    surfaceContainer = NavySurfaceElevated,
    outline = BlueOutline,
    outlineVariant = CobaltContainerDark,
)

val AskPaperShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AskPaperTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Keep explicit Blue & White aesthetic active regardless of device wallpaper
    val colorScheme = if (darkTheme) BlueNavyDarkColorScheme else BlueWhiteLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AskPaperShapes,
        content = content,
    )
}
