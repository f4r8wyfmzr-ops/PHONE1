package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = IndigoRoyal,
    onPrimary = SurfaceWhite,
    primaryContainer = IndigoSoft,
    onPrimaryContainer = IndigoDeep,
    secondary = VerifiedEmerald,
    onSecondary = SurfaceWhite,
    secondaryContainer = VerifiedEmeraldBg,
    onSecondaryContainer = VerifiedEmerald,
    tertiary = PendingAmber,
    onTertiary = SurfaceWhite,
    tertiaryContainer = PendingAmberBg,
    onTertiaryContainer = PendingAmber,
    error = FailureCrimson,
    onError = SurfaceWhite,
    errorContainer = FailureCrimsonBg,
    onErrorContainer = FailureCrimson,
    background = PorcelainBg,
    onBackground = TextPrimary,
    surface = SurfaceWhite,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceMuted,
    onSurfaceVariant = TextSecondary,
    outline = OutlineSubtle,
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = IndigoDeep,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkTextPrimary,
    secondary = VerifiedEmerald,
    onSecondary = SurfaceWhite,
    secondaryContainer = ColorTokensDark.EmeraldContainer,
    onSecondaryContainer = ColorTokensDark.EmeraldOnContainer,
    tertiary = PendingAmber,
    onTertiary = SurfaceWhite,
    error = FailureCrimson,
    onError = SurfaceWhite,
    background = DarkBg,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceElevated,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkSurfaceElevated,
)

private object ColorTokensDark {
    val EmeraldContainer = androidx.compose.ui.graphics.Color(0xFF0A3A28)
    val EmeraldOnContainer = androidx.compose.ui.graphics.Color(0xFF9DF0CB)
}

private val PhonePayShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun PhonePayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    isHindi: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val typography = buildPhonePayTypography(isHindi = isHindi)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = PhonePayShapes,
        content = content,
    )
}
