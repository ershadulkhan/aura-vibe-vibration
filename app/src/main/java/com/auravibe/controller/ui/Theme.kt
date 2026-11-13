package com.auravibe.controller.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Aura {
    val Bg = Color(0xFF0E0E10)
    val Card = Color(0xFF1C1C1E)
    val CardHi = Color(0xFF2A2A2D)
    val Yellow = Color(0xFFF5C747)
    val Blue = Color(0xFF2B70FA)
    val Warm = Color(0xFFFF6D00)
    val WarmYellow = Color(0xFFFFD166)
    val Cool = Color(0xFFFFFFFF)
    val Muted = Color(0xFF9A9AA0)
    val Ok = Color(0xFF34C759)
    val Bad = Color(0xFFFF453A)
}

@Composable
fun AuraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Aura.Blue,
            onPrimary = Color.White,
            secondary = Aura.Yellow,
            onSecondary = Color.Black,
            background = Aura.Bg,
            onBackground = Color.White,
            surface = Aura.Card,
            onSurface = Color.White,
            surfaceVariant = Aura.CardHi,
            onSurfaceVariant = Aura.Muted,
            surfaceContainer = Aura.Card,
        ),
        content = content,
    )
}
