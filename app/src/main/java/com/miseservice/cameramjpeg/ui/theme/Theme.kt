package com.miseservice.cameramjpeg.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = ParkingPrimary,
    onPrimary = ParkingOnPrimary,
    primaryContainer = ParkingPrimaryContainer,
    onPrimaryContainer = ParkingOnDark,
    secondary = ParkingSecondary,
    onSecondary = ParkingOnPrimary,
    secondaryContainer = ParkingSecondaryContainer,
    onSecondaryContainer = ParkingOnDark,
    tertiary = ParkingTertiary,
    onTertiary = ParkingOnPrimary,
    background = ParkingBackground,
    onBackground = ParkingOnDark,
    surface = ParkingSurface,
    onSurface = ParkingOnDark,
    surfaceVariant = ParkingSurfaceAlt,
    onSurfaceVariant = ParkingOnMuted,
    outline = ParkingDivider
)

private val LightColorScheme = lightColorScheme(
    primary = ParkingPrimaryDark,
    onPrimary = ParkingOnDark,
    primaryContainer = ParkingSecondary,
    onPrimaryContainer = ParkingOnLight,
    secondary = ParkingSecondary,
    onSecondary = ParkingOnLight,
    secondaryContainer = ParkingLightSurfaceAlt,
    onSecondaryContainer = ParkingOnLight,
    tertiary = ParkingTertiary,
    onTertiary = ParkingOnLight,
    background = ParkingLightBackground,
    onBackground = ParkingOnLight,
    surface = ParkingLightSurface,
    onSurface = ParkingOnLight,
    surfaceVariant = ParkingLightSurfaceAlt,
    onSurfaceVariant = ParkingOnLight,
    outline = ParkingOutlineLight
)

@Composable
fun CameraMjpegTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}