package com.gulshan.pocketprint.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Ink = Color(0xFF0B4F6C)
private val InkLight = Color(0xFF7FD1DE)
private val Accent = Color(0xFF01718B)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE9F2),
    onPrimaryContainer = Color(0xFF04303F),
    secondary = Accent,
    onSecondary = Color.White,
    surfaceVariant = Color(0xFFE8EEF1),
)

private val DarkColors = darkColorScheme(
    primary = InkLight,
    onPrimary = Color(0xFF04303F),
    primaryContainer = Color(0xFF0B4F6C),
    onPrimaryContainer = Color(0xFFCDE9F2),
    secondary = Color(0xFF6FC5D8),
    onSecondary = Color(0xFF04303F),
)

@Composable
fun PocketPrintTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // Material You is available from Android 12; older devices get our palette.
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(colorScheme = colors, content = content)
}
