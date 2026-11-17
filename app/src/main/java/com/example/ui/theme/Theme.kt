package com.example.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val NovabornDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = DeepSpaceNavy,
    primaryContainer = DeepCyan,
    onPrimaryContainer = TextPrimary,
    secondary = PulseViolet,
    onSecondary = TextPrimary,
    secondaryContainer = TacticalSurfaceElevated,
    onSecondaryContainer = SoftViolet,
    tertiary = EmeraldReady,
    onTertiary = DeepSpaceNavy,
    background = DeepSpaceNavy,
    onBackground = TextPrimary,
    surface = TacticalSurface,
    onSurface = TextPrimary,
    surfaceVariant = TacticalSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = OutlineSubtle,
    error = CrimsonError,
    onError = DeepSpaceNavy,
)

@Composable
fun NovabornBotTheme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dynamicDarkColorScheme(LocalContext.current)
        } else {
            NovabornDarkColorScheme
        }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
