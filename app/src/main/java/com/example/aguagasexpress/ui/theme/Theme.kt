package com.example.aguagasexpress.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1688E8),
    secondary = Color(0xFFFF8A32),
    tertiary = Color(0xFF16834A)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF54B8FF),
    secondary = Color(0xFFFFA564),
    tertiary = Color(0xFF5CD18A)
)

@Composable
fun AguaGasExpressTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content
    )
}
